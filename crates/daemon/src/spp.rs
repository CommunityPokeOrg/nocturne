//! Shared SPP (Serial Port Profile) plumbing used by both the real
//! [`crate::bluetooth::BluetoothDaemon`] (RFCOMM streams) and the emulator
//! backend (loopback TCP). Peer identity is `macaddr::MacAddr6` so this module
//! compiles without BlueZ/D-Bus; the Bluetooth daemon converts at the edge.

use std::sync::Arc;
use std::time::{Duration, Instant};

use base64::Engine;
use bytes::BytesMut;
use libnocturne::generated::bluetooth::BluetoothConnectionEvent;
use libnocturne::generated::bt_only::{AudioRecordingStartedEvent, AudioRecordingStoppedEvent};
use macaddr::MacAddr6;
use serde::Serialize;
use serde_json as json;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::{broadcast, mpsc, Mutex};
use tokio_util::sync::CancellationToken;
use tracing::{debug, error, info, warn};

use crate::app::msgpack::{
    create_audio_data_event, create_audio_recording_started_event,
    create_audio_recording_stopped_event, create_daemon_ready_event, MsgPackMessage,
    MsgPackProtocolHandler,
};
use crate::app::{AppMessage, AppMessagePriority};
use crate::audio::AudioEvent;
use crate::hardware::ImageCache;
use crate::http::WebSocketServer;

/// Bluetooth channel a macOS connector probes before it dials back.
pub(crate) const MACOS_CONNECTOR_PROBE_CHANNEL: u8 = 3;

/// A live outbound SPP route: every accepted peer stream registers here so
/// WebSocket-originated app messages can be fanned out per device.
pub(crate) struct GenericConnection {
    pub connection_id: String,
    pub device_address: MacAddr6,
    pub tx: mpsc::UnboundedSender<AppMessage>,
    pub cancel: CancellationToken,
}

pub(crate) struct GenericConnectionIdentity {
    pub(crate) connection_id: String,
    pub(crate) device: MacAddr6,
    pub(crate) cancel: CancellationToken,
}

fn typed_json<T: Serialize>(payload: T) -> json::Value {
    json::to_value(payload).unwrap_or_else(|_| json::json!({}))
}

pub(crate) fn remove_generic_connection(
    connections: &mut Vec<GenericConnection>,
    connection_id: &str,
    device: MacAddr6,
) -> bool {
    connections.retain(|connection| connection.connection_id != connection_id);
    connections
        .iter()
        .any(|connection| connection.device_address == device)
}

fn target_peer(message: &AppMessage) -> Option<String> {
    serde_json::from_slice::<json::Value>(&message.data)
        .ok()
        .and_then(|data| {
            data.get("_targetPeer")
                .and_then(|peer| peer.as_str())
                .map(ToOwned::to_owned)
        })
}

fn target_connection(message: &AppMessage) -> Option<String> {
    serde_json::from_slice::<json::Value>(&message.data)
        .ok()
        .and_then(|data| {
            data.get("_targetConnection")
                .and_then(|route| route.as_str())
                .map(ToOwned::to_owned)
        })
}

pub(crate) fn spp_connection_route(connection_id: &str) -> String {
    format!("spp:{connection_id}")
}

pub(crate) fn should_route_message(message: &AppMessage, route: &str, peer: MacAddr6) -> bool {
    if let Some(target) = target_connection(message) {
        return target == route;
    }

    match target_peer(message) {
        Some(target) => target == peer.to_string(),
        None => true,
    }
}

/// Runs the chunked MsgPack/base64 protocol over any SPP-capable duplex
/// stream (RFCOMM on real hardware, TCP loopback in the emulator).
pub(crate) async fn run_spp_msgpack_handler<S>(
    connection: GenericConnectionIdentity,
    mut stream: S,
    generic_connections: Arc<Mutex<Vec<GenericConnection>>>,
    websocket_server: Option<Arc<WebSocketServer>>,
    mut app_rx: mpsc::UnboundedReceiver<AppMessage>,
    mut audio_event_rx: broadcast::Receiver<AudioEvent>,
    ota_cmd_tx: Option<mpsc::Sender<crate::ota::Command>>,
) where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let GenericConnectionIdentity {
        connection_id,
        device,
        cancel,
    } = connection;
    info!(
        "Starting MsgPack protocol handler for SPP device: {}",
        device
    );

    let image_cache = match ImageCache::new().await {
        Ok(cache) => Arc::new(Mutex::new(cache)),
        Err(e) => {
            error!("Failed to create image cache for SPP handler: {}", e);
            return;
        }
    };
    let mut handler = if let Some(ref ws) = websocket_server {
        MsgPackProtocolHandler::with_image_cache(Some(Arc::clone(ws)), Arc::clone(&image_cache))
    } else {
        MsgPackProtocolHandler::new(None)
    };
    if let Some(ota_cmd_tx) = ota_cmd_tx {
        handler.set_ota_cmd_tx(ota_cmd_tx);
    }
    handler.set_connection_peer(device);
    let connection_route = spp_connection_route(&connection_id);
    handler.set_connection_route(connection_route.clone());

    let (session_tx, mut session_rx) = mpsc::unbounded_channel::<AppMessage>();
    handler.set_session_info(session_tx, 0).await;

    let app_ready_received = handler.app_ready_flag();
    let daemon_ready_interval = Duration::from_secs(3);
    let mut last_daemon_ready = Instant::now();

    send_spp_daemon_ready(&mut stream).await;

    let mut audio_events_closed = false;

    let mut read_buf = [0u8; 4096];
    let mut input_buffer = BytesMut::new();

    loop {
        tokio::select! {
            result = stream.read(&mut read_buf) => {
                match result {
                    Ok(0) => {
                        info!("SPP connection closed by {}", device);
                        break;
                    }
                    Ok(n) => {
                        debug!("Received {} bytes from SPP device {}", n, device);
                        input_buffer.extend_from_slice(&read_buf[..n]);

                        let mut write_error = false;
                        while let Some(newline_pos) = input_buffer.iter().position(|&b| b == b'\n') {
                            let b64_data = input_buffer[..newline_pos].to_vec();

                            let remaining = input_buffer.split_off(newline_pos + 1);
                            input_buffer.clear();
                            input_buffer = remaining;

                            let decoded = match base64::engine::general_purpose::STANDARD.decode(&b64_data) {
                                Ok(d) => d,
                                Err(e) => {
                                    error!("Failed to decode base64 from SPP: {}", e);
                                    continue;
                                }
                            };

                            debug!("Decoded {} base64 bytes to {} raw bytes", b64_data.len(), decoded.len());

                            let msg = AppMessage {
                                id: uuid::Uuid::new_v4().to_string(),
                                protocol: "com.usenocturne.daemon".to_string(),
                                session_id: 0,
                                priority: AppMessagePriority::Normal,
                                data: bytes::Bytes::from(decoded),
                            };

                            debug!("Calling handle_message for msg_id={}", msg.id);
                            let result = handler.handle_message(msg).await;
                            debug!("handle_message returned: is_ok={}, has_some={}",
                                result.is_ok(),
                                result.as_ref().map(|r| r.is_some()).unwrap_or(false));

                            match result {
                                Ok(Some(response)) => {
                                    let b64_response = base64::engine::general_purpose::STANDARD.encode(&response.data);
                                    let b64_with_newline = format!("{}\n", b64_response);
                                    debug!("Sending {} bytes response as {} base64 chars", response.data.len(), b64_response.len());
                                    if let Err(e) = stream.write_all(b64_with_newline.as_bytes()).await {
                                        error!("Failed to write response to SPP stream: {}", e);
                                        write_error = true;
                                        break;
                                    }
                                    if let Err(e) = stream.flush().await {
                                        error!("Failed to flush SPP stream: {}", e);
                                    }
                                    debug!("Response sent and flushed to SPP");
                                }
                                Ok(None) => {
                                    debug!("handle_message returned Ok(None) - no response needed");
                                }
                                Err(e) => {
                                    error!("Error handling SPP message: {}", e);
                                }
                            }
                        }
                        if write_error {
                            break;
                        }
                    }
                    Err(e) => {
                        info!("SPP connection error for {}: {}", device, e);
                        break;
                    }
                }
            }

            Some(msg) = session_rx.recv() => {
                debug!("Sending {} bytes to SPP device {}", msg.data.len(), device);
                let b64_data = base64::engine::general_purpose::STANDARD.encode(&msg.data);
                let b64_with_newline = format!("{}\n", b64_data);
                if let Err(e) = stream.write_all(b64_with_newline.as_bytes()).await {
                    error!("Failed to write to SPP stream: {}", e);
                    break;
                }
                if let Err(e) = stream.flush().await {
                    error!("Failed to flush SPP stream: {}", e);
                }
            }

            Some(msg) = app_rx.recv() => {
                debug!("Forwarding app message to SPP device {}: {} bytes", device, msg.data.len());

                let msgpack_message = match MsgPackProtocolHandler::outbound_app_message(msg.id.clone(), &msg.data) {
                    Ok(message) => message,
                    Err(err) => {
                        error!(%err, "Failed to encode app message for SPP");
                        continue;
                    }
                };
                if let MsgPackMessage::Call { method, .. } = &msgpack_message {
                        handler.mark_as_websocket_message(msg.id.clone());
                        handler.mark_method_for_message(msg.id.clone(), method.to_string());

                        if method == "spotify.image.fetch" {
                            if let Ok(parsed) = serde_json::from_slice::<json::Value>(&msg.data) {
                                if let Some(url) = parsed.get("params")
                                .and_then(|p| p.get("url"))
                                .and_then(|u| u.as_str())
                            {
                                    handler.mark_as_image_request(msg.id.clone(), url.to_string());
                                }
                            }
                        }
                }

                if let Ok(msgpack_data) = rmp_serde::to_vec_named(&msgpack_message) {
                    if let Ok(chunks) = MsgPackProtocolHandler::create_chunks(&msgpack_data) {
                        for chunk in chunks {
                            let b64_chunk = base64::engine::general_purpose::STANDARD.encode(&chunk);
                            let b64_with_newline = format!("{}\n", b64_chunk);
                            if let Err(e) = stream.write_all(b64_with_newline.as_bytes()).await {
                                error!("Failed to write chunk to SPP stream: {}", e);
                                break;
                            }
                        }
                        if let Err(e) = stream.flush().await {
                            error!("Failed to flush SPP stream: {}", e);
                        }
                    }
                }
            }

            audio_event = audio_event_rx.recv(), if !audio_events_closed => {
                match audio_event {
                    Ok(event) => {
                        let msg = match &event {
                            AudioEvent::Data { seq, opus_data, timestamp_ms } => {
                                create_audio_data_event(*seq, opus_data, *timestamp_ms)
                            }
                            AudioEvent::Started { sample_rate, channels, frame_ms } => {
                                create_audio_recording_started_event(AudioRecordingStartedEvent {
                                    sample_rate: *sample_rate,
                                    channels: *channels,
                                    frame_ms: *frame_ms,
                                    noise_suppressed: Some(true),
                                })
                            }
                            AudioEvent::Stopped { reason, total_frames } => {
                                create_audio_recording_stopped_event(AudioRecordingStoppedEvent {
                                    reason: reason.clone(),
                                    total_frames: *total_frames,
                                })
                            }
                            AudioEvent::MicLevel { .. } => continue,
                        };
                        if let Ok(serialized) = rmp_serde::to_vec_named(&msg) {
                            if let Ok(chunks) = MsgPackProtocolHandler::create_chunks(&serialized) {
                                for chunk in chunks {
                                    let b64_chunk = base64::engine::general_purpose::STANDARD.encode(&chunk);
                                    let b64_with_newline = format!("{}\n", b64_chunk);
                                    if let Err(e) = stream.write_all(b64_with_newline.as_bytes()).await {
                                        error!("Failed to write audio data to SPP stream: {}", e);
                                        break;
                                    }
                                }
                                let _ = stream.flush().await;
                            }
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        warn!("SPP audio event receiver lagged by {} messages", n);
                    }
                    Err(broadcast::error::RecvError::Closed) => {
                        debug!("Audio event channel closed for SPP handler");
                        audio_events_closed = true;
                    }
                }
            }

            _ = tokio::time::sleep(Duration::from_millis(500)) => {
                if !app_ready_received.load(std::sync::atomic::Ordering::Relaxed)
                    && last_daemon_ready.elapsed() >= daemon_ready_interval
                {
                    send_spp_daemon_ready(&mut stream).await;
                    last_daemon_ready = Instant::now();
                }
            }

            _ = cancel.cancelled() => {
                info!("SPP connection for {} closed by daemon", device);
                break;
            }
        }
    }

    let has_remaining_connection = {
        let mut conns = generic_connections.lock().await;
        remove_generic_connection(&mut conns, &connection_id, device)
    };

    if let Some(ws_server) = &websocket_server {
        ws_server.clear_app_ready_for_route(&connection_route).await;
    }

    if has_remaining_connection {
        info!(
            "SPP connection {} for {} closed while another connection remains active",
            connection_id, device
        );
    } else if let Some(ws_server) = &websocket_server {
        ws_server
            .broadcast_event(
                "bluetooth.connection".to_string(),
                typed_json(BluetoothConnectionEvent {
                    event: "connection_closed".to_string(),
                    device: device.to_string(),
                    connection_type: Some("android".to_string()),
                    device_type: None,
                    channel: None,
                    initiated_by: None,
                }),
            )
            .await;
    }

    info!(
        "MsgPack protocol handler stopped for SPP device: {}",
        device
    );
}

pub(crate) async fn send_spp_daemon_ready<S>(stream: &mut S)
where
    S: AsyncWrite + Unpin + ?Sized,
{
    let event = create_daemon_ready_event();

    if let Ok(serialized) = rmp_serde::to_vec_named(&event) {
        if let Ok(chunks) = MsgPackProtocolHandler::create_chunks(&serialized) {
            for chunk in chunks {
                let b64_chunk = base64::engine::general_purpose::STANDARD.encode(&chunk);
                let b64_with_newline = format!("{}\n", b64_chunk);
                if let Err(e) = stream.write_all(b64_with_newline.as_bytes()).await {
                    error!("Failed to send daemon.ready over SPP: {}", e);
                    return;
                }
            }
            if let Err(e) = stream.flush().await {
                error!("Failed to flush SPP stream after daemon.ready: {}", e);
            }
            info!("Sent daemon.ready to Android over SPP");
        }
    }
}
