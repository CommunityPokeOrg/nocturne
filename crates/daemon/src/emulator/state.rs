//! Emulated Bluetooth device registry.
//!
//! Mirrors what BlueZ `org.bluez.Device1` reports for a paired phone so the
//! `bluetooth.devices.list` RPC and UI pairing screens behave like real
//! firmware. State persists under the virtual rootfs so it survives daemon
//! restarts, and factory reset clears it like real `/var/lib/bluetooth`.

use std::collections::HashMap;
use std::sync::OnceLock;

use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::error::Result;
use crate::platform;

const REGISTRY_FILE: &str = "emulator-bluetooth.json";

/// BlueZ-style `class` value for a phone / smartphone peer (major class in bits 8..12).
const CLASS_PHONE: u32 = 0x5a020c;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmulatedDevice {
    pub address: String,
    pub name: String,
    #[serde(default)]
    pub alias: Option<String>,
    #[serde(default = "default_icon")]
    pub icon: String,
    #[serde(default = "default_class")]
    pub class: u32,
    #[serde(default)]
    pub paired: bool,
    #[serde(default)]
    pub connected: bool,
    #[serde(default)]
    pub trusted: bool,
    #[serde(default)]
    pub blocked: bool,
    #[serde(default)]
    pub device_type: Option<String>,
}

fn default_icon() -> String {
    "phone".to_string()
}

fn default_class() -> u32 {
    CLASS_PHONE
}

impl EmulatedDevice {
    pub fn android_phone(address: &str, name: &str) -> Self {
        Self {
            address: address.to_string(),
            name: name.to_string(),
            alias: None,
            icon: "phone".to_string(),
            class: CLASS_PHONE,
            paired: true,
            connected: false,
            trusted: true,
            blocked: false,
            device_type: Some("android".to_string()),
        }
    }

    /// Shape expected by the webapp's `bluetooth.devices.list` handler.
    pub fn to_payload(&self) -> serde_json::Value {
        let mut payload = serde_json::json!({
            "address": self.address,
            "blocked": self.blocked,
            "default": self.trusted,
            "connected": self.connected,
            "device_info": {
                "name": self.name,
                "icon": self.icon,
                "class": self.class,
            },
        });
        if let Some(device_type) = &self.device_type {
            payload["device_type"] = serde_json::json!(device_type);
            if device_type == "macos_connector" {
                payload["connection_type"] = serde_json::json!("macos_connector");
                payload["channel"] = serde_json::json!(crate::spp::MACOS_CONNECTOR_PROBE_CHANNEL);
            }
        }
        payload
    }
}

#[derive(Default)]
pub struct EmulatedRegistry {
    devices: HashMap<String, EmulatedDevice>,
}

fn registry() -> &'static Mutex<EmulatedRegistry> {
    static REGISTRY: OnceLock<Mutex<EmulatedRegistry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(EmulatedRegistry::load_blocking()))
}

impl EmulatedRegistry {
    fn path() -> std::path::PathBuf {
        platform::state_dir().join(REGISTRY_FILE)
    }

    fn load_blocking() -> Self {
        let path = Self::path();
        let Ok(raw) = std::fs::read_to_string(&path) else {
            return Self::with_defaults();
        };
        match serde_json::from_str::<Vec<EmulatedDevice>>(&raw) {
            Ok(devices) => Self {
                devices: devices
                    .into_iter()
                    .map(|device| (device.address.clone(), device))
                    .collect(),
            },
            Err(error) => {
                tracing::warn!(
                    "Failed to parse emulated device registry at {}: {error}; using defaults",
                    path.display()
                );
                Self::with_defaults()
            }
        }
    }

    fn with_defaults() -> Self {
        let mut devices = HashMap::new();
        let phone = EmulatedDevice::android_phone("02:00:00:00:01:01", "Emulator Phone");
        devices.insert(phone.address.clone(), phone);
        Self { devices }
    }

    async fn persist(&self) {
        let path = Self::path();
        if let Some(parent) = path.parent() {
            if let Err(error) = tokio::fs::create_dir_all(parent).await {
                tracing::warn!(
                    "Failed to create emulated registry dir {}: {error}",
                    parent.display()
                );
                return;
            }
        }
        let devices: Vec<&EmulatedDevice> = self.devices.values().collect();
        match serde_json::to_vec_pretty(&devices) {
            Ok(raw) => {
                if let Err(error) = tokio::fs::write(&path, raw).await {
                    tracing::warn!(
                        "Failed to persist emulated registry at {}: {error}",
                        path.display()
                    );
                }
            }
            Err(error) => tracing::warn!("Failed to serialize emulated registry: {error}"),
        }
    }
}

pub async fn list_devices() -> Vec<EmulatedDevice> {
    registry().lock().await.devices.values().cloned().collect()
}

pub async fn devices_payload() -> Vec<serde_json::Value> {
    list_devices()
        .await
        .iter()
        .map(EmulatedDevice::to_payload)
        .collect()
}

pub async fn get_device(address: &str) -> Option<EmulatedDevice> {
    registry().lock().await.devices.get(address).cloned()
}

pub async fn set_connected(address: &str, connected: bool) -> Result<()> {
    let mut registry = registry().lock().await;
    if let Some(device) = registry.devices.get_mut(address) {
        device.connected = connected;
    }
    registry.persist().await;
    Ok(())
}

pub async fn unpair(address: &str) -> Result<()> {
    let mut registry = registry().lock().await;
    registry.devices.remove(address);
    registry.persist().await;
    Ok(())
}

pub async fn upsert(device: EmulatedDevice) -> Result<()> {
    let mut registry = registry().lock().await;
    registry.devices.insert(device.address.clone(), device);
    registry.persist().await;
    Ok(())
}

/// Whether a non-paired peer may open the emulated SPP listener right now.
/// Mirrors the real pairing window: only while the UI asked for
/// discoverable/pairable, or for a peer that is already paired.
pub async fn accepts_spp_peer(address: &str) -> bool {
    let registry = registry().lock().await;
    if registry
        .devices
        .get(address)
        .is_some_and(|device| device.paired)
    {
        return true;
    }
    drop(registry);
    discoverable()
}

static DISCOVERABLE: OnceLock<std::sync::atomic::AtomicBool> = OnceLock::new();

fn discoverable_flag() -> &'static std::sync::atomic::AtomicBool {
    DISCOVERABLE.get_or_init(|| std::sync::atomic::AtomicBool::new(false))
}

pub fn set_discoverable(value: bool) {
    discoverable_flag().store(value, std::sync::atomic::Ordering::Relaxed);
}

pub fn discoverable() -> bool {
    discoverable_flag().load(std::sync::atomic::Ordering::Relaxed)
}
