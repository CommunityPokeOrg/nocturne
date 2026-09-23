//! Host filesystem layout helpers.
//!
//! The daemon normally reads device files straight off the firmware rootfs
//! (`/etc/superbird`, `/dev/misc`, sysfs, `/var/lib/nocturne`, ...). Setting
//! `NOCTURNE_FS_ROOT` reroots every absolute device path under an alternate
//! directory so the unmodified code paths run against a staged filesystem
//! tree. The emulator uses this to run the real firmware logic inside a
//! virtual rootfs; it is equally useful for host-side testing.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

static FS_ROOT: OnceLock<Option<PathBuf>> = OnceLock::new();

pub fn fs_root() -> Option<&'static Path> {
    FS_ROOT
        .get_or_init(|| {
            std::env::var_os("NOCTURNE_FS_ROOT")
                .map(PathBuf::from)
                .filter(|path| !path.as_os_str().is_empty())
        })
        .as_deref()
}

/// Resolve an absolute device path against `NOCTURNE_FS_ROOT` when set.
pub fn path(absolute: &str) -> PathBuf {
    match fs_root() {
        Some(root) => root.join(absolute.trim_start_matches('/')),
        None => PathBuf::from(absolute),
    }
}

/// Directory for mutable daemon state (equivalent to `/var/lib/nocturne`).
pub fn state_dir() -> PathBuf {
    path("/var/lib/nocturne")
}

/// Directory for volatile runtime state (equivalent to `/run/nocturne`).
pub fn runtime_dir() -> PathBuf {
    path("/run/nocturne")
}

/// Directory for transient caches (equivalent to `/var/cache/nocturned`).
pub fn cache_dir() -> PathBuf {
    path("/var/cache/nocturned")
}
