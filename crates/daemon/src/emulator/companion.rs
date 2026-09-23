//! Scripted emulated companion ("the phone").
//!
//! Dials the loopback SPP listener and speaks the identical chunked
//! MsgPack/base64 wire protocol a real Android companion uses: one base64
//! chunk envelope per line, CRC32 per chunk, `rmp_serde` named encoding for
//! `MsgPackMessage`. It answers the daemon's forwarded WebSocket calls with a
//! canned Spotify library/playback model so UI flows — Now Playing, library
//! browsing, transport controls, OTA prompts — behave like a paired phone.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine;
use bytes::{Bytes, BytesMut};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

use crate::app::msgpack::{
    parse_one_chunk_envelope, ChunkEnvelopeParse, MsgPackMessage, MsgPackProtocolHandler,
};
use crate::error::Result;

const RECONNECT_DELAY: Duration = Duration::from_millis(750);

/// 64x64 opaque PNG, solid Nocturne green — enough for the UI's artwork path.
const ARTWORK_PNG: &[u8] = include_bytes!("artwork.png");

#[derive(Debug, Clone)]
struct Track {
    id: String,
    title: String,
    artist: String,
    album: String,
    duration_ms: u32,
    uri: String,
}

impl Track {
    fn new(id: &str, title: &str, artist: &str, album: &str, duration_ms: u32) -> Self {
        Self {
            id: id.to_string(),
            title: title.to_string(),
            artist: artist.to_string(),
            album: album.to_string(),
            duration_ms,
            uri: format!("spotify:track:{id}"),
        }
    }

    fn media_attributes(&self) -> serde_json::Value {
        serde_json::json!({
            "MediaItemTitle": self.title,
            "MediaItemArtist": self.artist,
            "MediaItemAlbumTitle": self.album,
            "MediaItemPlaybackDurationInMilliseconds": self.duration_ms,
            "MediaItemDuration": self.duration_ms,
            "MediaItemPersistentID": self.id,
            "MediaItemStoreIdentifier": self.uri,
        })
    }

    fn spotify_item(&self) -> serde_json::Value {
        serde_json::json!({
            "id": self.id,
            "name": self.title,
            "uri": self.uri,
            "duration_ms": self.duration_ms,
            "artists": [{ "name": self.artist }],
            "album": {
                "name": self.album,
                "uri": format!("spotify:album:{}-album", self.id),
                "images": [{ "url": "emulator://artwork", "width": 64, "height": 64 }],
            },
        })
    }
}

#[derive(Default)]
struct FakeSpotify {
    tracks: Vec<Track>,
    current: usize,
    playing: bool,
    volume: u8,
    shuffle: bool,
    repeat: String,
    media_generation: u64,
    position_anchor_ms: u64,
    position_anchor_at: Option<std::time::Instant>,
}

impl FakeSpotify {
    fn library() -> Self {
        Self {
            tracks: vec![
                Track::new(
                    "emu1",
                    "Midnight Circuit",
                    "The Nocturne Project",
                    "Dashboard Dreams",
                    213_000,
                ),
                Track::new("emu2", "Static Fields", "Looper", "Magnetic Sleep", 187_000),
                Track::new("emu3", "Silverline", "Aria Vale", "Coast Roads", 241_000),
                Track::new(
                    "emu4",
                    "Emulator Waltz",
                    "The Nocturne Project",
                    "Dashboard Dreams",
                    199_000,
                ),
                Track::new("emu5", "Greenline", "Panel Beat", "Aux Inputs", 176_000),
            ],
            playing: true,
            volume: 60,
            repeat: "off".to_string(),
            ..Default::default()
        }
    }

    fn elapsed_ms(&self) -> u64 {
        let mut elapsed = self.position_anchor_ms;
        if self.playing {
            if let Some(anchor) = self.position_anchor_at {
                elapsed = elapsed.saturating_add(anchor.elapsed().as_millis() as u64);
            }
        }
        if let Some(track) = self.tracks.get(self.current) {
            elapsed % u64::from(track.duration_ms).max(1)
        } else {
            elapsed
        }
    }

    fn now_playing_update(&self) -> serde_json::Value {
        let track = self.tracks.get(self.current);
        serde_json::json!({
            "media_generation": self.media_generation,
            "media_item_attributes": track.map(Track::media_attributes),
            "playback_attributes": {
                "PlaybackStatus": if self.playing { "Playing" } else { "Paused" },
                "PlaybackAppName": "Spotify",
                "PlaybackRate": if self.playing { 1.0 } else { 0.0 },
                "PlaybackElapsedTimeInMilliseconds": self.elapsed_ms(),
                "PlaybackQueueIndex": self.current,
                "PlaybackQueueLength": self.tracks.len(),
            },
        })
    }

    fn player_state(&self) -> serde_json::Value {
        let track = self.tracks.get(self.current);
        serde_json::json!({
            "is_playing": self.playing,
            "progress_ms": self.elapsed_ms(),
            "item": track.map(Track::spotify_item),
            "device": {
                "id": "emulator-device",
                "name": "Nocturne Emulator",
                "type": "automobile",
                "is_active": true,
                "volume_percent": self.volume,
            },
            "shuffle_state": self.shuffle,
            "repeat_state": self.repeat,
            "context": { "uri": "spotify:playlist:emulator-mix" },
        })
    }

    fn set_playing(&mut self, playing: bool) {
        self.position_anchor_ms = self.elapsed_ms();
        self.position_anchor_at = Some(std::time::Instant::now());
        self.playing = playing;
    }

    fn step(&mut self, delta: i64) {
        let len = self.tracks.len() as i64;
        if len == 0 {
            return;
        }
        self.current = ((self.current as i64 + delta).rem_euclid(len)) as usize;
        self.position_anchor_ms = 0;
        self.position_anchor_at = Some(std::time::Instant::now());
        self.media_generation = self.media_generation.saturating_add(1);
    }

    fn seek(&mut self, position_ms: u64) {
        self.position_anchor_ms = position_ms;
        self.position_anchor_at = Some(std::time::Instant::now());
    }
}

type Outbox = mpsc::UnboundedSender<Bytes>;

fn send_event(outbox: &Outbox, topic: &str, data: serde_json::Value) {
    enqueue(
        outbox,
        MsgPackMessage::Event {
            topic: topic.to_string(),
            data,
        },
    );
}

fn enqueue(outbox: &Outbox, message: MsgPackMessage) {
    let serialized = match rmp_serde::to_vec_named(&message) {
        Ok(serialized) => serialized,
        Err(error) => {
            error!("Emulated companion failed to serialize message: {error}");
            return;
        }
    };
    match MsgPackProtocolHandler::create_chunks(&serialized) {
        Ok(chunks) => {
            for chunk in chunks {
                let encoded = base64::engine::general_purpose::STANDARD.encode(&chunk);
                let mut line = BytesMut::from(encoded.as_bytes());
                line.extend_from_slice(b"\n");
                let _ = outbox.send(line.freeze());
            }
        }
        Err(error) => error!("Emulated companion failed to chunk message: {error}"),
    }
}

fn app_ready_event() -> serde_json::Value {
    serde_json::json!({
        "datetime": chrono_like_now(),
        "timezone": { "identifier": "UTC", "name": "UTC" },
        "platform": "android",
        "subscribed": true,
        "subscription_status": "nocturne_plus",
        "has_lifetime": true,
        "is_admin": true,
        "entitlements_verified": true,
        "spotify_skipped": false,
    })
}

fn chrono_like_now() -> String {
    // RFC 3339 UTC timestamp without pulling in a datetime dependency.
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = secs / 86_400;
    let rem = secs % 86_400;
    let (year, month, day) = days_to_ymd(days);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

fn local_time_hms() -> String {
    // The emulated companion reports UTC as its local timezone.
    let rem = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
        % 86_400;
    format!("{:02}:{:02}:{:02}", rem / 3600, (rem % 3600) / 60, rem % 60)
}

fn days_to_ymd(days_since_epoch: u64) -> (u64, u64, u64) {
    // Howard Hinnant's civil-from-days algorithm.
    let z = days_since_epoch + 719_468;
    let era = z / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn playlist_payload() -> serde_json::Value {
    serde_json::json!({
        "items": [{
            "id": "emulator-mix",
            "name": "Emulator Mix",
            "uri": "spotify:playlist:emulator-mix",
            "images": [{ "url": "emulator://artwork" }],
            "tracks": { "total": 5 },
        }],
        "total": 1,
    })
}

fn handle_call(
    state: &Arc<Mutex<FakeSpotify>>,
    outbox: &Outbox,
    method: &str,
    params: &serde_json::Value,
) -> serde_json::Value {
    let method = match method {
        // The UI translates canonical snake case to the connector's camel case
        // for `web` companions; the emulated companion accepts both.
        "spotify.player.nextTrack" => "spotify.player.next",
        "spotify.player.previousTrack" => "spotify.player.previous",
        "spotify.auth.getStatus" => "spotify.auth.status",
        other => other,
    };

    let mut st = state.lock().unwrap();
    let mut emit_update = false;
    let mut emit_artwork = false;

    let result = match method {
        "spotify.auth.status" => serde_json::json!({
            "authenticated": true,
            "needsAuthorization": false,
            "skipped": false,
        }),
        "spotify.player.state" | "spotify.player.update" => st.player_state(),
        "spotify.player.play" => {
            st.set_playing(true);
            emit_update = true;
            serde_json::json!({ "status": "ok" })
        }
        "spotify.player.pause" => {
            st.set_playing(false);
            emit_update = true;
            serde_json::json!({ "status": "ok" })
        }
        "spotify.player.next" => {
            st.step(1);
            emit_update = true;
            emit_artwork = true;
            serde_json::json!({ "status": "ok" })
        }
        "spotify.player.previous" => {
            st.step(-1);
            emit_update = true;
            emit_artwork = true;
            serde_json::json!({ "status": "ok" })
        }
        "spotify.player.seek" => {
            if let Some(ms) = params
                .get("position_ms")
                .or_else(|| params.get("positionMs"))
                .and_then(|v| v.as_u64())
            {
                st.seek(ms);
            }
            emit_update = true;
            serde_json::json!({ "status": "ok" })
        }
        "spotify.player.volume" => {
            if let Some(volume) = params
                .get("volume_percent")
                .or_else(|| params.get("volumePercent"))
                .or_else(|| params.get("volume"))
                .and_then(|v| v.as_u64())
            {
                st.volume = volume.clamp(0, 100) as u8;
            }
            send_event(
                outbox,
                "phone.volume.update",
                serde_json::json!({ "volume_percent": st.volume }),
            );
            serde_json::json!({ "status": "ok", "volume_percent": st.volume })
        }
        "spotify.player.shuffle" => {
            st.shuffle = params
                .get("state")
                .or_else(|| params.get("enabled"))
                .and_then(|v| v.as_bool())
                .unwrap_or(!st.shuffle);
            serde_json::json!({ "status": "ok" })
        }
        "spotify.player.repeat" => {
            if let Some(mode) = params.get("state").and_then(|v| v.as_str()) {
                st.repeat = mode.to_string();
            }
            serde_json::json!({ "status": "ok" })
        }
        "spotify.player.transfer"
        | "spotify.player.queue"
        | "spotify.player.queue.add"
        | "spotify.player.speed" => {
            serde_json::json!({ "status": "ok" })
        }
        "spotify.me.profile" => serde_json::json!({
            "id": "emulator",
            "display_name": "Emulator User",
            "product": "premium",
        }),
        "spotify.me.playlists" => playlist_payload(),
        "spotify.me.tracks" => serde_json::json!({
            "items": st.tracks.iter().map(|track| serde_json::json!({
                "track": track.spotify_item(),
            })).collect::<Vec<_>>(),
            "total": st.tracks.len(),
        }),
        "spotify.me.tracks.contains" => serde_json::json!([true]),
        "spotify.me.tracks.save" | "spotify.me.tracks.remove" => {
            serde_json::json!({ "status": "ok" })
        }
        "spotify.me.shows" => serde_json::json!({ "items": [], "total": 0 }),
        "spotify.playlist.get" => serde_json::json!({
            "id": params.get("id").cloned().unwrap_or_else(|| serde_json::json!("emulator-mix")),
            "name": "Emulator Mix",
            "uri": "spotify:playlist:emulator-mix",
            "images": [{ "url": "emulator://artwork" }],
        }),
        "spotify.playlist.tracks" | "spotify.album.tracks" => serde_json::json!({
            "items": st.tracks.iter().map(|track| serde_json::json!({
                "track": track.spotify_item(),
                "id": track.id,
                "name": track.title,
                "artists": [{ "name": track.artist }],
                "duration_ms": track.duration_ms,
                "uri": track.uri,
            })).collect::<Vec<_>>(),
            "total": st.tracks.len(),
        }),
        "spotify.album.get" | "spotify.show.get" => serde_json::json!({
            "id": "emulator-album",
            "name": "Dashboard Dreams",
            "artists": [{ "name": "The Nocturne Project" }],
            "images": [{ "url": "emulator://artwork" }],
        }),
        "spotify.artist.get" => serde_json::json!({
            "id": "emulator-artist",
            "name": "The Nocturne Project",
            "images": [{ "url": "emulator://artwork" }],
        }),
        "spotify.show.episodes" => serde_json::json!({ "items": [], "total": 0 }),
        "spotify.devices" => serde_json::json!({
            "devices": [{
                "id": "emulator-device",
                "name": "Nocturne Emulator",
                "type": "automobile",
                "is_active": true,
                "volume_percent": st.volume,
            }],
        }),
        "spotify.image.fetch" => serde_json::json!({
            "data": base64::engine::general_purpose::STANDARD.encode(ARTWORK_PNG),
            "content_type": "image/png",
        }),
        "spotify.track.lyrics" => serde_json::json!({
            "lyrics": {
                "syncType": "LINE_SYNCED",
                "lines": [
                    { "startTimeMs": "0", "words": "Emulated lyrics line one" },
                    { "startTimeMs": "8000", "words": "Emulated lyrics line two" },
                ],
            },
        }),
        "spotify.dj.start"
        | "spotify.dj.signal"
        | "spotify.radio.discoveries"
        | "spotify.radio.mixes"
        | "spotify.radio.topMix" => {
            serde_json::json!({ "items": [], "status": "ok" })
        }
        "device.time.get" => serde_json::json!({
            "datetime": chrono_like_now(),
            "time": local_time_hms(),
        }),
        "device.timezone.get" => {
            serde_json::json!({ "identifier": "UTC", "name": "UTC" })
        }
        "phone.calls.get" => serde_json::json!({ "calls": [] }),
        "phone.call.accept" | "phone.call.decline" => serde_json::json!({ "status": "ok" }),
        "tts.speak" | "voice.cancel" | "notification.remove" => serde_json::json!({}),
        m if m.starts_with("ota.") || m.starts_with("device.ota.") => {
            serde_json::json!({ "status": "ok" })
        }
        _ => serde_json::json!({}),
    };

    drop(st);
    if emit_update {
        let st = state.lock().unwrap();
        send_event(outbox, "media.now_playing.update", st.now_playing_update());
        if emit_artwork {
            send_event(
                outbox,
                "media.now_playing.artwork",
                serde_json::json!({
                    "data": base64::engine::general_purpose::STANDARD.encode(ARTWORK_PNG),
                    "content_type": "image/png",
                    "media_generation": st.media_generation,
                }),
            );
        }
    }
    result
}

/// Consume one chunk envelope line; returns a fully reassembled MsgPack
/// payload when the last missing chunk of a message arrives.
fn ingest_chunk_line(
    pending: &mut HashMap<String, (u16, HashMap<u16, Bytes>)>,
    line: &[u8],
) -> Option<Bytes> {
    let decoded = match base64::engine::general_purpose::STANDARD.decode(line) {
        Ok(decoded) => decoded,
        Err(error) => {
            warn!("Emulated companion dropping undecodable line: {error}");
            return None;
        }
    };

    match parse_one_chunk_envelope(&decoded) {
        ChunkEnvelopeParse::Complete {
            message_id,
            index,
            total,
            checksum,
            payload,
            consumed: _,
        } => {
            if crc32fast::hash(&payload) != checksum {
                warn!("Emulated companion dropping chunk with bad CRC ({message_id})");
                return None;
            }
            if total == 1 {
                return Some(payload);
            }
            let entry = pending
                .entry(message_id.clone())
                .or_insert_with(|| (total, HashMap::new()));
            if entry.0 != total {
                entry.0 = total;
                entry.1.clear();
            }
            entry.1.insert(index, payload);
            if entry.1.len() as u16 != total {
                return None;
            }
            let (_, chunks) = pending.remove(&message_id)?;
            let mut assembled = BytesMut::new();
            for index in 0..total {
                assembled.extend_from_slice(&chunks[&index]);
            }
            Some(assembled.freeze())
        }
        ChunkEnvelopeParse::NeedMore | ChunkEnvelopeParse::Invalid => {
            warn!("Emulated companion dropping malformed chunk envelope");
            None
        }
    }
}

async fn run_session(stream: TcpStream, state: Arc<Mutex<FakeSpotify>>) {
    let _ = stream.set_nodelay(true);
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let (outbox_tx, mut outbox_rx) = mpsc::unbounded_channel::<Bytes>();
    let writer = tokio::spawn(async move {
        while let Some(line) = outbox_rx.recv().await {
            if let Err(error) = write_half.write_all(&line).await {
                warn!("Emulated companion write failed: {error}");
                break;
            }
            let _ = write_half.flush().await;
        }
    });

    // Identify as a ready Android companion, then present the current track.
    send_event(&outbox_tx, "app.ready", app_ready_event());
    send_event(
        &outbox_tx,
        "network.status",
        serde_json::json!({ "status": "connected", "type": "wifi" }),
    );
    {
        let st = state.lock().unwrap();
        send_event(
            &outbox_tx,
            "media.now_playing.update",
            st.now_playing_update(),
        );
        send_event(
            &outbox_tx,
            "media.now_playing.artwork",
            serde_json::json!({
                "data": base64::engine::general_purpose::STANDARD.encode(ARTWORK_PNG),
                "content_type": "image/png",
                "media_generation": st.media_generation,
            }),
        );
    }

    let mut pending: HashMap<String, (u16, HashMap<u16, Bytes>)> = HashMap::new();
    let mut line = Vec::new();

    loop {
        line.clear();
        match reader.read_until(b'\n', &mut line).await {
            Ok(0) => {
                info!("Emulated companion connection closed by daemon");
                break;
            }
            Ok(_) => {
                let trimmed = line.strip_suffix(b"\n").unwrap_or(&line);
                let Some(payload) = ingest_chunk_line(&mut pending, trimmed) else {
                    continue;
                };
                let message = match rmp_serde::from_slice::<MsgPackMessage>(&payload) {
                    Ok(message) => message,
                    Err(error) => {
                        warn!("Emulated companion failed to decode MsgPack message: {error}");
                        continue;
                    }
                };
                match message {
                    MsgPackMessage::Call { id, method, params } => {
                        debug!("Emulated companion received call {method}");
                        if method == "device.time.get" {
                            // answered below via the generic dispatch
                        }
                        let result = handle_call(&state, &outbox_tx, &method, &params);
                        enqueue(&outbox_tx, MsgPackMessage::Result { id, result });
                    }
                    MsgPackMessage::Event { topic, data } => {
                        debug!("Emulated companion received event {topic}: {data}");
                    }
                    MsgPackMessage::Result { .. } | MsgPackMessage::Error { .. } => {}
                }
            }
            Err(error) => {
                warn!("Emulated companion read failed: {error}");
                break;
            }
        }
    }

    writer.abort();
}

/// Dial the daemon's emulated SPP listener forever, reconnecting after drops.
pub async fn run(addr: SocketAddr) -> Result<()> {
    let state = Arc::new(Mutex::new(FakeSpotify::library()));
    loop {
        match TcpStream::connect(addr).await {
            Ok(stream) => {
                info!("Emulated companion connected to {addr}");
                run_session(stream, Arc::clone(&state)).await;
            }
            Err(error) => {
                debug!("Emulated companion connect to {addr} failed: {error}; retrying");
            }
        }
        tokio::time::sleep(RECONNECT_DELAY).await;
    }
}
