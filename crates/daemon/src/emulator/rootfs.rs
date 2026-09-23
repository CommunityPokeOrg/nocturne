//! Virtual rootfs staging for the emulator.
//!
//! [`seed`] populates `NOCTURNE_FS_ROOT` with the device files the firmware
//! expects — a virtual sysfs (backlight, ambient light sensor, efuse cells),
//! a `/dev/misc` blob carrying valid A/B metadata, `/etc` device identity
//! files, and the daemon's state directories — so the unmodified daemon code
//! paths run against ordinary files. Preexisting files are never overwritten,
//! letting the Android app or host tooling provide its own identity values.

use std::path::Path;

use tracing::{info, warn};

use crate::error::Result;
use crate::platform;
use crate::system::ab::{save_ab_data, ABData, MISC_BUF_SIZE};

const DEFAULT_SERIAL: &str = "EMU00000000001";
const DEFAULT_BT_MAC: &str = "02:00:00:00:00:01";

const SUPERBIRD_METADATA: &str = r#"{
    "version": "5.0.0-emulator",
    "imageVersion": "5.0.0-emulator",
    "imageBuildDate": "20260101",
    "btMac": "02:00:00:00:00:01",
    "serialNumber": "EMU00000000001"
}
"#;

const PROC_CMDLINE: &str = "root=/dev/ram0 ro console=ttyS0 quiet superbird.slot=a\n";

fn write_file_if_absent(path: &Path, contents: &[u8]) -> std::io::Result<()> {
    if path.exists() {
        return Ok(());
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, contents)
}

fn ambient_seed() -> String {
    std::env::var("NOCTURNE_EMULATOR_AMBIENT").unwrap_or_else(|_| "500".to_string())
}

/// Seed the virtual rootfs under `NOCTURNE_FS_ROOT`. No-op when the fs root
/// is not configured; each file is only written when absent.
pub fn seed() -> Result<()> {
    let Some(root) = platform::fs_root() else {
        return Ok(());
    };

    let staged = |relative: &str| root.join(relative);

    write_file_if_absent(
        &staged("etc/nocturne/config.json"),
        b"{\"debug_logs\":false}\n",
    )?;
    write_file_if_absent(&staged("etc/superbird"), SUPERBIRD_METADATA.as_bytes())?;
    write_file_if_absent(&staged("proc/cmdline"), PROC_CMDLINE.as_bytes())?;

    write_file_if_absent(
        &staged("sys/bus/nvmem/devices/efuse0/cells/serial-number@12"),
        DEFAULT_SERIAL.as_bytes(),
    )?;
    write_file_if_absent(
        &staged("sys/bus/nvmem/devices/efuse0/cells/bt-mac@6"),
        DEFAULT_BT_MAC.as_bytes(),
    )?;
    write_file_if_absent(&staged("sys/class/efuse/usid"), DEFAULT_SERIAL.as_bytes())?;

    write_file_if_absent(&staged("sys/class/backlight/emulator/brightness"), b"128\n")?;
    write_file_if_absent(
        &staged("sys/class/backlight/emulator/max_brightness"),
        b"255\n",
    )?;
    write_file_if_absent(
        &staged("sys/class/backlight/emulator/actual_brightness"),
        b"128\n",
    )?;

    write_file_if_absent(
        &staged("sys/bus/iio/devices/iio:device0/in_intensity0_raw"),
        ambient_seed().as_bytes(),
    )?;
    write_file_if_absent(
        &staged("sys/bus/iio/devices/iio:device0/in_intensity0_integration_time"),
        b"0.100",
    )?;
    write_file_if_absent(
        &staged("sys/bus/iio/devices/iio:device0/in_intensity0_calibscale"),
        b"16",
    )?;

    for dir in [
        "var/lib",
        "var/lib/superbird",
        "var/lib/bandaid/nocturne",
        "opt/nocturne/webapps",
    ] {
        std::fs::create_dir_all(staged(dir))?;
    }
    std::fs::create_dir_all(platform::runtime_dir())?;
    std::fs::create_dir_all(platform::state_dir().join("transfers"))?;
    std::fs::create_dir_all(platform::cache_dir().join("images"))?;

    // A/B metadata lives at a fixed offset inside /dev/misc; stage a blob the
    // real ABData reader accepts so device.ab.* works end to end.
    let misc = staged("dev/misc");
    if !misc.exists() {
        if let Some(parent) = misc.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&misc, vec![0u8; MISC_BUF_SIZE])?;
    }
    match std::fs::metadata(&misc) {
        Ok(meta) if meta.len() >= MISC_BUF_SIZE as u64 => {
            let mut data = ABData::default();
            if let Err(err) = crate::system::ab::open_and_load_ab_data() {
                info!("dev/misc has no valid AB data ({err}); seeding defaults");
                data.reset();
                if let Err(err) = save_ab_data(data) {
                    warn!("Failed to seed A/B metadata in dev/misc: {err}");
                }
            }
        }
        Ok(meta) => warn!(
            "dev/misc is only {} bytes (< {MISC_BUF_SIZE}); leaving it alone",
            meta.len()
        ),
        Err(err) => warn!("Failed to stat dev/misc: {err}"),
    }

    info!("Emulator rootfs staged at {}", root.display());
    Ok(())
}
