#[cfg(not(target_os = "android"))]
pub mod hid_mapping;
pub mod msgpack;
#[cfg(not(target_os = "android"))]
pub mod websocket_handler;

#[cfg(not(target_os = "android"))]
use crate::error::Result;
use bytes::Bytes;
#[cfg(not(target_os = "android"))]
use std::collections::HashMap;
#[cfg(not(target_os = "android"))]
use std::sync::atomic::AtomicBool;
#[cfg(not(target_os = "android"))]
use std::sync::Arc;
use tokio::sync::mpsc;
#[cfg(not(target_os = "android"))]
use tracing::{debug, info, warn};

#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct AppMessage {
    pub id: String,
    pub protocol: String,
    pub session_id: u8,
    pub priority: AppMessagePriority,
    pub data: Bytes,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum AppMessagePriority {
    #[default]
    Normal,
    Bulk,
}

#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct AppSession {
    pub id: u8,
    pub protocol: String,
    pub tx: mpsc::UnboundedSender<AppMessage>,
}

#[cfg(not(target_os = "android"))]
use crate::app::msgpack::MsgPackProtocolHandler;
#[cfg(not(target_os = "android"))]
use crate::app::websocket_handler::WebSocketProtocolHandler;

#[cfg(not(target_os = "android"))]
pub enum AppProtocolHandlerEnum {
    MsgPack(Box<MsgPackProtocolHandler>),
    WebSocket(WebSocketProtocolHandler),
}

#[cfg(not(target_os = "android"))]
impl AppProtocolHandlerEnum {
    pub fn protocol_name(&self) -> &str {
        match self {
            AppProtocolHandlerEnum::MsgPack(handler) => handler.protocol_name(),
            AppProtocolHandlerEnum::WebSocket(handler) => handler.protocol_name(),
        }
    }

    pub async fn handle_message(&mut self, message: AppMessage) -> Result<Option<AppMessage>> {
        match self {
            AppProtocolHandlerEnum::MsgPack(handler) => handler.handle_message(message).await,
            AppProtocolHandlerEnum::WebSocket(handler) => handler.handle_message(message).await,
        }
    }

    pub fn as_msgpack_mut(&mut self) -> Option<&mut MsgPackProtocolHandler> {
        match self {
            AppProtocolHandlerEnum::MsgPack(handler) => Some(handler),
            _ => None,
        }
    }
}

#[cfg(not(target_os = "android"))]
pub struct AppCommunicationManager {
    sessions: HashMap<u8, AppSession>,
    handlers: HashMap<String, AppProtocolHandlerEnum>,
    to_iap2_tx: mpsc::UnboundedSender<(u8, AppMessagePriority, Bytes)>,
}

#[cfg(not(target_os = "android"))]
impl AppCommunicationManager {
    pub fn new(to_iap2_tx: mpsc::UnboundedSender<(u8, AppMessagePriority, Bytes)>) -> Self {
        Self {
            sessions: HashMap::new(),
            handlers: HashMap::new(),
            to_iap2_tx,
        }
    }

    pub fn register_handler(&mut self, handler: AppProtocolHandlerEnum) {
        let protocol = handler.protocol_name().to_string();
        info!("Registering app protocol handler: {}", protocol);
        self.handlers.insert(protocol, handler);
    }

    pub fn get_handler_mut(&mut self, protocol: &str) -> Option<&mut AppProtocolHandlerEnum> {
        self.handlers.get_mut(protocol)
    }

    pub fn app_ready_flag(&self) -> Option<Arc<AtomicBool>> {
        self.handlers
            .get("com.usenocturne.daemon")
            .and_then(|h| match h {
                AppProtocolHandlerEnum::MsgPack(handler) => Some(handler.app_ready_flag()),
                _ => None,
            })
    }

    pub fn create_session(&mut self, session_id: u8, protocol: String) -> Result<()> {
        let (tx, mut rx) = mpsc::unbounded_channel();

        let session = AppSession {
            id: session_id,
            protocol: protocol.clone(),
            tx,
        };

        self.sessions.insert(session_id, session);
        info!(
            "Created app session {} for protocol {}",
            session_id, protocol
        );

        let to_iap2_tx = self.to_iap2_tx.clone();
        let protocol_clone = protocol.clone();

        tokio::spawn(async move {
            while let Some(message) = rx.recv().await {
                debug!(
                    "App session {} received message: {:?}",
                    session_id, message.id
                );

                if let Err(e) = to_iap2_tx.send((session_id, message.priority, message.data)) {
                    warn!("Failed to send message to iAP2: {}", e);
                    break;
                }
            }
            info!("App session {} ({}) task ended", session_id, protocol_clone);
        });

        Ok(())
    }

    pub async fn handle_incoming_data(&mut self, session_id: u8, data: Bytes) -> Result<()> {
        let session = self.sessions.get(&session_id);
        if session.is_none() {
            warn!("No app session found for ID {}", session_id);
            return Ok(());
        }

        let session = session.unwrap();
        let protocol = &session.protocol;
        let session_tx = session.tx.clone();

        if let Some(handler) = self.handlers.get_mut(protocol) {
            if let Some(mp_handler) = handler.as_msgpack_mut() {
                mp_handler.set_session_info(session_tx, session_id).await;
            }
            let message = AppMessage {
                id: uuid::Uuid::new_v4().to_string(),
                protocol: protocol.clone(),
                session_id,
                priority: AppMessagePriority::Normal,
                data,
            };

            debug!(
                "Processing app message: {} (session {}, protocol {})",
                message.id, session_id, protocol
            );

            if let Some(mut response) = handler.handle_message(message).await? {
                response.session_id = session_id;

                if let Some(session) = self.sessions.get(&session_id) {
                    debug!(
                        "Sending response to app session {}: {}",
                        session_id, response.id
                    );
                    if let Err(e) = session.tx.send(response) {
                        warn!(
                            "Failed to send response to app session {}: {}",
                            session_id, e
                        );
                    }
                }
            }
        } else {
            warn!("No handler registered for protocol {}", protocol);
        }

        Ok(())
    }
}
