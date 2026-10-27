# Zeron for Android (dev)

This is the community build, kept beside upstream's official app in `apps/android`. It adds Android
Auto, app lock, `@` mentions, agent switching, commands, message copy and more. Build its core with
`scripts/android/build-dev-core.sh` (the shared `build-core.sh` at the small `mobile-android` profile,
bindings patched, libraries stripped).

A native viewport onto the zeron mesh, built on the same Rust mobile core as
the iOS app (`crates/mobile`, via UniFFI). The phone is a peer device: it
mirrors the workspace registry, joins per-chat session rooms and drives remote
engines through the durable command ledger. No agent runs on the phone.

**Rust decides what to paint and where; Kotlin paints, scrolls and handles
gestures.** Transcript rows are painted from Rust display lists at Rust-computed
positions (`transcript/RowView.kt`); agent visuals (`show_visual`: canvases,
charts, wgpu scenes and shaders) are drawn from the worker's output by
`transcript/VisualView.kt`. Chrome (lists, composer, pickers) is Jetpack Compose.

## Build & run

Requires JDK 17, the Android SDK (platform 35, NDK r27), `cargo-ndk`, and the
Rust Android targets:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
scripts/android/build-dev-core.sh      # .so + Kotlin bindings -> target/android-core
cd apps/android-dev
./gradlew :app:installDebug
```

A phone-only (arm64) dev APK:

```sh
./gradlew :app:assembleDebug -Pabis=arm64-v8a
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sign-in uses WorkOS through a Custom Tab and returns to `zeron://callback`. Debug builds
also accept `--es chat <id>` to open a chat on launch. A local `wrangler dev` edge can be
targeted by setting the `edgeOverride` preference (see `AppModel.edgeUrl`).

## Layout

```
app/src/main/java/sh/zeron/android/
  core/        Fonts + Android PlatformMeasurer (the bytes Rust measures)
  design/      Palette, status glyphs, SVG tool/file icons
  transcript/  RowView (display-list painter), TranscriptList (virtualized
               host), VisualView (agent visuals)
  app/         AppModel: CoreClient owner, credentials, change events
  ui/          Sign-in, sessions, session + composer, new session, settings
```

## Notifications

Session alerts (run finished, waiting on input, run failed) are raised on the phone itself,
by the same rule the desktop uses (`notify/SessionNotifier.kt`), with the desktop's three
chimes as the notification-channel sounds (`res/raw/session_*.wav`). While alerts are on, a
quiet foreground service (`notify/KeepAliveService.kt`) keeps the sync connection alive with
the app closed; there is no server push, so a phone that Android has put to sleep or killed
the app on will not alert until it wakes. Real push would need an FCM path in `edge/`
(today it only sends APNs).

## Voice

Voice calls follow the iOS design: the phone is only the audio endpoint. The Rust core
(`start_voice`) runs the call protocol with a registered host that advertises
`voice-client-media-v1`; `voice/CodexVoicePeer.kt` answers its media requests with WebRTC
(`stream-webrtc-android`): prepare, gathered offer, apply answer, mute, levels, close. The
orb is the desktop's own (`OrbRenderer`) painted by `ui/VoiceUi.kt`. A microphone foreground
service (`voice/VoiceCallService.kt`) keeps a call alive with the screen off. Start one from the
waveform button in the composer when a host is available. Debug builds accept
`--ez voicePreview true` for a scripted call with no audio or host.

## Commands

Typing `/` (commands and skills) or `$` (skills) at the end of the composer lists what the
agent offers in that workspace (`ListCommands` / `ListSkills` through the host relay, merged
like the desktop in `Client::list_invocations`); picking one inserts its prompt text
(`ui/Commands.kt`).

## Not done yet

- Server push (FCM) for alerts when the app has been killed.
- Wallpaper, queued-message editing, `@` file mentions.
