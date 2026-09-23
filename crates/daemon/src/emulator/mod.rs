//! Offline firmware emulator backend.
//!
//! When `NOCTURNE_EMULATOR=1` (the default on Android builds), the daemon
//! skips the BlueZ/RFCOMM stack entirely and instead:
//!
//! - reroots every device path under `NOCTURNE_FS_ROOT` (see [`crate::platform`])
//!   and seeds a virtual `/sys`, `/dev`, `/proc`, `/etc` tree there so the
//!   real firmware code paths run unmodified;
//! - replaces the Bluetooth daemon with [`EmulatorDaemon`], which serves the
//!   real MsgPack/SPP wire protocol on a loopback TCP listener and routes
//!   WebSocket traffic to it exactly like a paired phone;
//! - optionally spawns a scripted companion (`NOCTURNE_EMULATOR_COMPANION`)
//!   that dials the loopback listener and speaks the real chunked MsgPack
//!   RPC protocol, so the UI exercises Now Playing, library, OTA, and settings
//!   flows end-to-end with no phone attached.

pub mod companion;
pub mod daemon;
pub mod rootfs;
pub mod state;

pub use daemon::EmulatorDaemon;

/// Whether the emulator backend is active. On Android it is the default;
/// elsewhere it must be requested explicitly.
pub fn enabled() -> bool {
    match std::env::var("NOCTURNE_EMULATOR").as_deref() {
        Ok("1") | Ok("true") | Ok("yes") | Ok("on") => true,
        Ok("0") | Ok("false") | Ok("no") | Ok("off") => false,
        _ => cfg!(target_os = "android"),
    }
}
