# NOCTURNE-ANDROID — ANDROID EMULATOR APP

This guide covers `packages/android` — a Kotlin app that runs the real
`nocturned` daemon in emulator mode and renders the real `packages/ui` bundle in
a WebView with a drawn Car Thing bezel. See the root `AGENTS.md` for repo-wide
conventions.

## WHAT IT IS

An offline, full-firmware emulator for the Spotify Car Thing UX:

- `DaemonService` spawns the cross-compiled `nocturned` binary (bundled as
  `lib/<abi>/libnocturned.so`, extracted executable via
  `android:extractNativeLibs="true"` + `useLegacyPackaging`) with
  `NOCTURNE_EMULATOR=1`, `NOCTURNE_FS_ROOT=<filesDir>/fsroot` (all daemon device
  paths reroot into a virtual rootfs), `NOCTURNE_WEBAPPS_DIR=<filesDir>/webapps/ui`
  (must point at the `ui` subdirectory itself, not its parent), and
  `NOCTURNE_SLOTS_STUB=1`. The supervisor restarts the binary if it exits.
- The daemon's emulator module starts a TCP SPP listener (default
  `127.0.0.1:5001`) and a scripted companion speaks the real chunked MsgPack
  wire protocol against it (`app.ready`, now-playing, artwork, `device.time.get`,
  etc.). The UI is none the wiser.
- `MainActivity` hosts a WebView pointed at `http://127.0.0.1:8080/` (the
  daemon's webapp server) and a `BezelView`. The UI is authored for a fixed
  800x480 CSS viewport, so the WebView is sized **in dp** (800dp x 480dp) — a
  view W dp wide always yields a W css px `device-width` viewport. The whole
  960x480 panel is then uniformly scaled about its top-left corner to fit the
  screen; the FrameLayout entry must use `Gravity.TOP|START` (centering is done
  by translation, not gravity).
- `BezelView` injects the exact DOM events the UI listens for, dispatched once
  on `document` (propagation covers window-capture + document + window-bubble
  listeners; dispatching on both nodes double-fires toggles like the 'm' lock).
  Presets send `key="1".."4"`, dial press sends `Enter`, back sends `Escape`,
  settings sends `key="m"`/`code="KeyM"`, and dial rotation sends `wheel`
  `deltaX` steps.

## BUILD

Prereqs: Android SDK at `~/Android/Sdk` (or `local.properties` `sdk.dir`), NDK
27.x under `sdk/ndk`, JDK 17. Gradle cross-compiles the daemon per ABI
(`cargoBuild_aarch64_linux_android`, `cargoBuild_x86_64_linux_android`) with
NDK clang env (`CC_<triple>`, `AR_<triple>`, `CARGO_TARGET_<TRIPLE>_LINKER`,
`ANDROID_NDK`) set inside the task — no cargo-ndk required. The UI bundle comes
from `packages/ui/dist` (`bun install && bun run build` there first).

```bash
bun install && bun run build            # in packages/ui
./gradlew assembleDebug                  # in packages/android — builds both ABIs' release daemon + APK
```

Debug APKs enable `WebView.setWebContentsDebuggingEnabled`; attach via
`adb forward tcp:9229 localabstract:webview_devtools_remote_<pid>` and
`http://127.0.0.1:9229/json` (suppress the `Origin` header in WS clients —
Android rejects non-empty origins).

## CAR THING FIRMWARE FLASHER

`org.nocturne.emulator.flasher` adds an in-app firmware flasher for a physical
Spotify Car Thing connected over USB OTG (entry point: the "flasher" link in
the corner of `MainActivity`, or plugging the device in — `USB_DEVICE_ATTACHED`
launches `FlasherActivity` via `res/xml/device_filter.xml`).

- `CarThingUsb.kt` — device detection by VID/PID + `GX-CHIP` product string
  (modes mirror flashthing: `Usb` = buttons 1&4 held, `UsbBurn` = maskrom,
  `Normal`/`Fastboot` rejected with guidance) and the vendor-control + bulk
  transport layer over `UsbDeviceConnection`.
- `AmlogicDevice.kt` — Kotlin port of flashthing's Amlogic burn-mode protocol
  (`lib/src/aml.rs`): memory ops, `run`, `identify`, `writeLargeMemory`
  staging, the AMLC/AMLS BL2 handshake, `bulkcmd`, partition validation, boot
  hwpart/user-area/partition writes, `env import`. Requests, addresses, block
  sizes, and retry/cooldown behavior must stay in lockstep with flashthing.
- `FlashConfig.kt` — `meta.json` (Terbium subset) parser + the superbird
  partition table; rejects the same step types flashthing rejects
  (identify/reads/getBootAMLC/bulkcmdStat/validatePartitionSize/user-input
  wait).
- `FirmwarePackage.kt` — payload store over a picked zip (meta.json inside) or
  a stock dump zip (falls back to the bundled `stock-meta.json`).
- `FlashEngine.kt` — orchestration: detect, BL2-boot to burn mode when needed,
  run every plan step with progress events.
- `FlasherActivity.kt` — picker UI + explicit risk confirmation before any
  write; requires android.hardware.usb.host (declared optional).

`app/src/main/assets/flasher/` vendors `superbird.bl2.encrypted.bin`,
`superbird.bootloader.img`, and `stock-meta.json` from flashthing (MIT —
see `NOTICE.md`). These blobs are required to move a button-held Car Thing
into burn mode; do not regenerate them here.

## CONVENTIONS

- The daemon binary is the same `crates/daemon` crate: anything it needs at
  runtime on-device must exist under `NOCTURNE_FS_ROOT` or degrade gracefully
  via the emulator path. Do not add Android-only hacks into the daemon; keep
  platform differences behind `crate::platform` / `NOCTURNE_EMULATOR`.
- BlueZ/dbus/iAP2 are `cfg(not(target_os = "android"))`; the portable SPP
  plumbing lives in `crate::spp` with `macaddr::MacAddr6` peer identity. New
  SPP-companion code goes there, not `bluetooth/`.
- Touch/key injection must emit BOTH `key` (main UI) and `code` (Mockingbird)
  on every KeyboardEvent, dispatched on `document` only.
- `local.properties`, `build/`, `.gradle/` are gitignored; never commit them.
