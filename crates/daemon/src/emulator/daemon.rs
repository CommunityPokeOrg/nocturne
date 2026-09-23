//! Emulator-side replacement for [`crate::bluetooth::BluetoothDaemon`].
//!
//! Runs the same responsibilities — an accept loop for companion transports,
//! WebSocket-to-companion routing, audio/wakeword command plumbing — but the
//! transport is a loopback TCP listener speaking the identical chunked
//! MsgPack/SPP wire protocol, and the device inventory comes from the
//! emulated registry in [`crate::emulator::state`].

use std::net::SocketAddr;
use std::str::FromStr;
use std::sync::Arc;

use anyhow::Result;
use libnocturne::generated::bluetooth::{
    BluetoothConnectionEvent, BluetoothDeviceConnectRequest, BluetoothDeviceConnectResponse,
    BluetoothDeviceDisconnectRequest, BluetoothDeviceDisconnectResponse,
    BluetoothDeviceUnpairRequest, BluetoothDeviceUnpairResponse,
};
use macaddr::MacAddr6 as Address;
use serde::Serialize;
use serde_json as json;
use tokio::net::TcpListener;
use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::{broadcast, mpsc, Mutex};
use tracing::{debug, error, info, warn};

use crate::app::AppMessage;
use crate::audio::{AudioCommand, AudioEvent, WakeWordCommand};
use crate::emulator::{companion, state};
use crate::http::WebSocketServer;
use crate::spp::{
    should_route_message, spp_connection_route, GenericConnection, GenericConnectionIdentity,
};

const DEFAULT_SPP_LISTEN: &str = "127.0.0.1:5001";

fn typed_json<T: Serialize>(payload: T) -> json::Value {
    json::to_value(payload).unwrap_or_else(|_| json::json!({}))
}

/// Next synthetic address handed to loopback clients that are not a paired
/// registry device. Locally administered range `02:00:00:00:10:XX`.
fn next_synthetic_address(taken: usize) -> Address {
    let index = u8::try_from(taken % 256).unwrap_or(0);
    Address::new(0x02, 0x00, 0x00, 0x00, 0x10, index)
}

pub struct EmulatorDaemon {
    generic_connections: Arc<Mutex<Vec<GenericConnection>>>,
    ws_to_app_rx: Option<mpsc::UnboundedReceiver<AppMessage>>,
    websocket_server: Option<Arc<WebSocketServer>>,
    audio_event_rx: broadcast::Receiver<AudioEvent>,
    audio_cmd_tx: mpsc::UnboundedSender<AudioCommand>,
    wakeword_pause_tx: mpsc::UnboundedSender<WakeWordCommand>,
    ota_cmd_tx: Option<mpsc::Sender<crate::ota::Command>>,
}

impl EmulatorDaemon {
    /// Same wiring contract as `BluetoothDaemon::new` (minus the `Config`,
    /// which the emulator does not need).
    pub async fn new(
        ws_to_app_rx: Option<mpsc::UnboundedReceiver<AppMessage>>,
        websocket_server: Option<Arc<WebSocketServer>>,
        audio_event_rx: broadcast::Receiver<AudioEvent>,
        audio_cmd_tx: mpsc::UnboundedSender<AudioCommand>,
        wakeword_pause_tx: mpsc::UnboundedSender<WakeWordCommand>,
        ota_cmd_tx: Option<mpsc::Sender<crate::ota::Command>>,
    ) -> Result<Self> {
        Ok(Self {
            generic_connections: Arc::new(Mutex::new(Vec::new())),
            ws_to_app_rx,
            websocket_server,
            audio_event_rx,
            audio_cmd_tx,
            wakeword_pause_tx,
            ota_cmd_tx,
        })
    }

    fn spp_listen_addr() -> SocketAddr {
        std::env::var("NOCTURNE_EMULATOR_SPP_LISTEN")
            .ok()
            .and_then(|value| value.parse().ok())
            .unwrap_or_else(|| DEFAULT_SPP_LISTEN.parse().expect("default listen addr"))
    }

    fn companion_enabled() -> bool {
        !matches!(
            std::env::var("NOCTURNE_EMULATOR_COMPANION").as_deref(),
            Ok("0") | Ok("false") | Ok("off")
        )
    }

    /// Pick the peer identity for a fresh loopback connection: the first
    /// paired device not already attached to a live connection, else a
    /// synthetic address.
    async fn claim_peer_address(
        generic_connections: &Arc<Mutex<Vec<GenericConnection>>>,
    ) -> Address {
        let conns = generic_connections.lock().await;
        let active: Vec<String> = conns
            .iter()
            .map(|conn| conn.device_address.to_string())
            .collect();
        drop(conns);

        for device in state::list_devices().await {
            if device.paired && !active.contains(&device.address) {
                if let Ok(address) = Address::from_str(&device.address) {
                    return address;
                }
            }
        }

        let taken = {
            let conns = generic_connections.lock().await;
            conns.len()
        };
        let address = next_synthetic_address(taken);
        let mut entry = state::EmulatedDevice::android_phone(&address.to_string(), "Emulator Peer");
        entry.paired = true;
        if let Err(error) = state::upsert(entry).await {
            warn!("Failed to register synthetic emulated peer: {error}");
        }
        address
    }

    async fn drop_connections_for_peer(
        generic_connections: &Arc<Mutex<Vec<GenericConnection>>>,
        address: Address,
    ) {
        let cancels: Vec<_> = {
            let conns = generic_connections.lock().await;
            conns
                .iter()
                .filter(|conn| conn.device_address == address)
                .map(|conn| conn.cancel.clone())
                .collect()
        };
        for cancel in cancels {
            cancel.cancel();
        }
    }

    async fn handle_connect(
        msg_id: String,
        params: Option<json::Value>,
        generic_connections: Arc<Mutex<Vec<GenericConnection>>>,
        websocket_server: Option<Arc<WebSocketServer>>,
    ) {
        let Some(ws_server) = websocket_server else {
            return;
        };
        let request = params.and_then(|params| {
            serde_json::from_value::<BluetoothDeviceConnectRequest>(params).ok()
        });
        let Some(request) = request else {
            ws_server
                .send_response(
                    msg_id,
                    json::json!({ "error": "Missing or invalid address parameter" }),
                )
                .await;
            return;
        };

        let Some(device) = state::get_device(&request.address).await else {
            ws_server
                .send_response(
                    msg_id,
                    json::json!({ "error": format!("Unknown emulated device {}", request.address) }),
                )
                .await;
            return;
        };

        // The scripted companion and any external SPP clients are inbound:
        // "connect" means the peer may now dial the loopback listener. If a
        // live connection already exists, report connected immediately,
        // matching the real path's Connected outcome.
        let already_connected = {
            let conns = generic_connections.lock().await;
            conns
                .iter()
                .any(|conn| conn.device_address.to_string() == device.address)
        };

        let status = if already_connected {
            if let Err(error) = state::set_connected(&device.address, true).await {
                warn!("Failed to mark emulated device connected: {error}");
            }
            "connected"
        } else {
            "waiting_for_android"
        };
        ws_server
            .send_response(
                msg_id,
                typed_json(BluetoothDeviceConnectResponse {
                    status: status.to_string(),
                    device: request.address,
                }),
            )
            .await;
    }

    async fn handle_disconnect(
        msg_id: String,
        params: Option<json::Value>,
        generic_connections: Arc<Mutex<Vec<GenericConnection>>>,
        websocket_server: Option<Arc<WebSocketServer>>,
    ) {
        let address_str = params
            .and_then(|params| {
                serde_json::from_value::<BluetoothDeviceDisconnectRequest>(params).ok()
            })
            .map(|request| request.address);
        let Some(ws_server) = websocket_server else {
            return;
        };
        let Some(addr) = address_str else {
            ws_server
                .send_response(
                    msg_id,
                    json::json!({ "error": "Missing address parameter" }),
                )
                .await;
            return;
        };
        let Ok(address) = Address::from_str(&addr) else {
            ws_server
                .send_response(
                    msg_id,
                    json::json!({ "error": "Invalid Bluetooth address" }),
                )
                .await;
            return;
        };

        Self::drop_connections_for_peer(&generic_connections, address).await;
        if let Err(error) = state::set_connected(&addr, false).await {
            warn!("Failed to mark emulated device disconnected: {error}");
        }
        ws_server
            .send_response(
                msg_id,
                typed_json(BluetoothDeviceDisconnectResponse {
                    status: "disconnected".to_string(),
                    device: addr,
                }),
            )
            .await;
    }

    async fn handle_unpair(
        msg_id: String,
        params: Option<json::Value>,
        generic_connections: Arc<Mutex<Vec<GenericConnection>>>,
        websocket_server: Option<Arc<WebSocketServer>>,
    ) {
        let address_str = params
            .and_then(|params| serde_json::from_value::<BluetoothDeviceUnpairRequest>(params).ok())
            .map(|request| request.address);
        let Some(ws_server) = websocket_server else {
            return;
        };
        let Some(addr) = address_str else {
            ws_server
                .send_response(
                    msg_id,
                    json::json!({ "error": "Missing address parameter" }),
                )
                .await;
            return;
        };
        let Ok(address) = Address::from_str(&addr) else {
            ws_server
                .send_response(
                    msg_id,
                    json::json!({ "error": "Invalid Bluetooth address" }),
                )
                .await;
            return;
        };

        Self::drop_connections_for_peer(&generic_connections, address).await;
        if let Err(error) = state::unpair(&addr).await {
            warn!("Failed to unpair emulated device: {error}");
        }
        ws_server
            .broadcast_event(
                "bluetooth.device.unpaired".to_string(),
                json::json!({ "device": addr }),
            )
            .await;
        ws_server
            .send_response(
                msg_id,
                typed_json(BluetoothDeviceUnpairResponse {
                    status: "unpaired".to_string(),
                    device: addr,
                }),
            )
            .await;
    }

    /// Same WebSocket fan-out contract as `BluetoothDaemon::run`.
    fn spawn_ws_consumer(&mut self) {
        let Some(mut ws_rx) = self.ws_to_app_rx.take() else {
            return;
        };
        let generic_connections = Arc::clone(&self.generic_connections);
        let websocket_server = self.websocket_server.clone();
        let audio_cmd_tx = self.audio_cmd_tx.clone();
        let wakeword_pause_tx = self.wakeword_pause_tx.clone();

        tokio::spawn(async move {
            while let Some(ws_message) = ws_rx.recv().await {
                debug!("Emulator received WebSocket message: {:?}", ws_message);

                if ws_message.protocol == "bluetooth.control" {
                    if let Ok(data) = serde_json::from_slice::<json::Value>(&ws_message.data) {
                        let method = data.get("method").and_then(|m| m.as_str());
                        let params = data.get("params").cloned();
                        match method {
                            Some("bluetooth.device.connect") => {
                                Self::handle_connect(
                                    ws_message.id,
                                    params,
                                    Arc::clone(&generic_connections),
                                    websocket_server.clone(),
                                )
                                .await;
                            }
                            Some("bluetooth.device.disconnect") => {
                                Self::handle_disconnect(
                                    ws_message.id,
                                    params,
                                    Arc::clone(&generic_connections),
                                    websocket_server.clone(),
                                )
                                .await;
                            }
                            Some("bluetooth.device.unpair" | "bluetooth.device.forget") => {
                                Self::handle_unpair(
                                    ws_message.id,
                                    params,
                                    Arc::clone(&generic_connections),
                                    websocket_server.clone(),
                                )
                                .await;
                            }
                            Some(other) => warn!("Unknown bluetooth control method: {other}"),
                            None => {}
                        }
                    }
                    continue;
                }

                if let Ok(data) = serde_json::from_slice::<json::Value>(&ws_message.data) {
                    if let Some(method) = data.get("method").and_then(|m| m.as_str()) {
                        if method == "voice.cancel" {
                            let _ = audio_cmd_tx.send(AudioCommand::Stop);
                        }
                        if method == "audio.record.start" {
                            let (ack_tx, ack_rx) = tokio::sync::oneshot::channel();
                            let _ = wakeword_pause_tx.send(WakeWordCommand::Pause {
                                ack: Some(ack_tx),
                                persist: false,
                            });
                            match tokio::time::timeout(std::time::Duration::from_secs(1), ack_rx)
                                .await
                            {
                                Ok(Ok(())) => {}
                                _ => warn!("Wakeword pause ack timed out, proceeding anyway"),
                            }
                            let _ = audio_cmd_tx.send(AudioCommand::Start);
                            if let Some(ws_server) = &websocket_server {
                                ws_server
                                    .send_response(
                                        ws_message.id.clone(),
                                        json::json!({ "status": "recording" }),
                                    )
                                    .await;
                            }
                            continue;
                        }
                        if method == "audio.record.stop" {
                            let _ = audio_cmd_tx.send(AudioCommand::Stop);
                            if let Some(ws_server) = &websocket_server {
                                ws_server
                                    .send_response(
                                        ws_message.id.clone(),
                                        json::json!({ "status": "idle" }),
                                    )
                                    .await;
                            }
                            continue;
                        }
                        if method == "wakeword.pause" {
                            let _ = wakeword_pause_tx.send(WakeWordCommand::Pause {
                                ack: None,
                                persist: true,
                            });
                            if let Some(ws_server) = &websocket_server {
                                ws_server
                                    .send_response(
                                        ws_message.id.clone(),
                                        json::json!({ "status": "paused" }),
                                    )
                                    .await;
                            }
                            continue;
                        }
                        if method == "wakeword.resume" {
                            let _ =
                                wakeword_pause_tx.send(WakeWordCommand::Resume { persist: true });
                            if let Some(ws_server) = &websocket_server {
                                ws_server
                                    .send_response(
                                        ws_message.id.clone(),
                                        json::json!({ "status": "resumed" }),
                                    )
                                    .await;
                            }
                            continue;
                        }
                    }
                }

                let generic_conns = generic_connections.lock().await;
                for conn in generic_conns.iter() {
                    if !should_route_message(
                        &ws_message,
                        &spp_connection_route(&conn.connection_id),
                        conn.device_address,
                    ) {
                        continue;
                    }
                    if let Err(e) = conn.tx.send(ws_message.clone()) {
                        warn!("Failed to send WebSocket message to emulated SPP connection: {e}");
                    }
                }
            }
        });
    }

    /// Accept loop: loopback TCP stands in for RFCOMM/SPP, then hands each
    /// stream to the real chunked-MsgPack handler.
    async fn start_spp_listener(&self) -> Result<()> {
        let addr = Self::spp_listen_addr();
        let listener = TcpListener::bind(addr).await?;
        info!("Emulator SPP listener bound on {addr}");

        let generic_connections = Arc::clone(&self.generic_connections);
        let websocket_server = self.websocket_server.clone();
        let audio_event_rx = self.audio_event_rx.resubscribe();
        let ota_cmd_tx = self.ota_cmd_tx.clone();

        tokio::spawn(async move {
            loop {
                let (stream, _peer_addr) = match listener.accept().await {
                    Ok(accepted) => accepted,
                    Err(e) => {
                        error!("Emulator SPP accept failed: {e}");
                        tokio::time::sleep(std::time::Duration::from_millis(250)).await;
                        continue;
                    }
                };
                let _ = stream.set_nodelay(true);

                let device = Self::claim_peer_address(&generic_connections).await;
                if !state::accepts_spp_peer(&device.to_string()).await {
                    info!("Rejecting emulated SPP connection from unpaired peer {device}");
                    continue;
                }
                info!("Accepted emulated SPP connection for peer {device}");

                if let Err(error) = state::set_connected(&device.to_string(), true).await {
                    warn!("Failed to mark emulated peer connected: {error}");
                }

                if let Some(ws) = &websocket_server {
                    ws.broadcast_event(
                        "bluetooth.connection".to_string(),
                        typed_json(BluetoothConnectionEvent {
                            event: "connection_established".to_string(),
                            device: device.to_string(),
                            connection_type: Some("generic".to_string()),
                            device_type: None,
                            channel: None,
                            initiated_by: None,
                        }),
                    )
                    .await;
                }

                let (app_tx, app_rx) = mpsc::unbounded_channel::<AppMessage>();
                let connection_id = uuid::Uuid::new_v4().to_string();
                let cancel = tokio_util::sync::CancellationToken::new();

                {
                    let mut conns = generic_connections.lock().await;
                    conns.push(GenericConnection {
                        connection_id: connection_id.clone(),
                        device_address: device,
                        tx: app_tx,
                        cancel: cancel.clone(),
                    });
                }

                let generic_conns = Arc::clone(&generic_connections);
                let ws_clone = websocket_server.clone();
                let audio_rx = audio_event_rx.resubscribe();
                let ota_cmd = ota_cmd_tx.clone();
                let device_for_task = device;
                tokio::spawn(async move {
                    crate::spp::run_spp_msgpack_handler(
                        GenericConnectionIdentity {
                            connection_id,
                            device: device_for_task,
                            cancel,
                        },
                        stream,
                        generic_conns.clone(),
                        ws_clone.clone(),
                        app_rx,
                        audio_rx,
                        ota_cmd,
                    )
                    .await;

                    if let Err(error) =
                        state::set_connected(&device_for_task.to_string(), false).await
                    {
                        warn!("Failed to mark emulated peer disconnected: {error}");
                    }
                });
            }
        });

        Ok(())
    }

    pub async fn run(&mut self) -> Result<()> {
        self.start_spp_listener().await?;
        self.spawn_ws_consumer();

        if Self::companion_enabled() {
            let addr = Self::spp_listen_addr();
            tokio::spawn(async move {
                if let Err(error) = companion::run(addr).await {
                    warn!("Emulated companion exited: {error}");
                }
            });
            info!("Scripted emulated companion enabled");
        } else {
            info!("Scripted emulated companion disabled (NOCTURNE_EMULATOR_COMPANION)");
        }

        info!("Emulator daemon running, waiting for connections...");

        let mut sigint =
            signal(SignalKind::interrupt()).map_err(crate::error::NocturnedError::Io)?;
        let mut sigterm =
            signal(SignalKind::terminate()).map_err(crate::error::NocturnedError::Io)?;

        tokio::select! {
            _ = sigint.recv() => {
                info!("Received SIGINT, shutting down...");
            }
            _ = sigterm.recv() => {
                info!("Received SIGTERM, shutting down...");
            }
        }

        // Drop every live emulated connection so handler tasks unwind and the
        // registry records the disconnect.
        let cancels: Vec<_> = {
            let conns = self.generic_connections.lock().await;
            conns.iter().map(|conn| conn.cancel.clone()).collect()
        };
        for cancel in cancels {
            cancel.cancel();
        }

        Ok(())
    }
}

// Kept visible for future emulator transports (e.g. an emulated iAP2 link):
// the WS fan-out treats iAP2 connections the same as SPP ones.
#[allow(dead_code)]
fn route_example(conn: &GenericConnection) -> String {
    spp_connection_route(&conn.connection_id)
}
