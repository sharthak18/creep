# ScreenKit

A native Android app for screen screenshots, screen recording with microphone or device audio, CameraX video capture, and a draggable floating control that never pulls the app in front of what you are doing. Captures are stored locally, either in the device media library, on a chosen storage volume such as an SD card, or in a folder you pick.

## Download and install

[⬇️ **Download the latest ScreenKit APK**](https://github.com/sharthak18/creep/releases/latest/download/ScreenKit.apk)

On Android, open the downloaded APK and follow the install prompt. If Android asks, allow your browser or file manager to install unknown apps. This is a sideloadable debug build, not a Play Store release.

**No APK is published yet.** The link will work after a `v*` version tag is pushed; the Android APK workflow will build the installer and attach `ScreenKit.apk` to that GitHub Release. To create a test build without a release, open **Actions → Android APK → Run workflow** and download the `ScreenKit.apk` artifact from the completed run.

## Build

- Open the repository in Android Studio with **JDK 17** installed.
- Install **Android SDK Platform 35** (plus build tools 35.0.0) and let Gradle sync.
- Run the `app` configuration on a device/emulator running **Android 7.0 (API 24) or later**. Device-audio capture needs Android 10+, the whole-screen capture scope and the single-app exclusion need Android 14+.
- Build from a terminal with Gradle 8.9 and `gradle :app:assembleDebug`. The repository contains Kotlin DSL build files; it does not currently include the binary Gradle wrapper JAR.

## Features

- **Floating control that stays out of the way.** Actions that need no system prompt — stop a recording, flip the camera lens, hide the bubble — run inside the floating service itself. Actions that do need one (starting a capture, enabling the microphone, arming the camera preview) are launched through an invisible, animation-free activity that shows Android's own sheet and disappears. The main app screen is never brought forward, so a screenshot taken from the bubble captures the app you were using instead of ScreenKit.
- **Screenshots.** A full-resolution RGBA frame is read from a fresh display, after a short settle delay so Android's own consent sheet is never part of the picture. PNGs go to `Pictures/ScreenCapture`. A fully blank frame is reported honestly: that means the source app is protected from capture.
- **Screen recording.** H.264/MP4 at up to 1920 pixels on the long edge, 30 fps, with a partial wake lock so a long recording is not cut short by the CPU sleeping mid-session.
- **Sound options.** Off, microphone, **device audio** (the sound the phone is playing, via Android's AudioPlaybackCapture), or both mixed into one AAC track. Microphone-only and silent recordings use MediaRecorder; device audio and mixed recordings are encoded with MediaCodec and muxed by MediaMuxer, because MediaRecorder cannot accept an external audio stream.
- **Whole-screen capture scope.** On Android 14+ the screen-share sheet offers "a single app", which freezes the video as soon as you switch away. ScreenKit asks for the default display so recordings keep covering everything, and the switch in the app can hand that choice back to Android if you want it.
- **Camera recording.** CameraX front/back video to the media library, with live mute/unmute when the microphone is enabled. The bubble's **Flip** button switches lens while a preview or a recording is running, so changing camera no longer means opening the app.
- **Camera preview overlay.** A draggable CameraX preview window that sits over other apps and is included in a screen recording of the whole display.
- **Save location you control.** Device gallery (default), a chosen storage **volume such as an SD card** using MediaStore's volume-aware writes so galleries still index the files, or **any folder you pick** through Android's folder picker. Folder captures are written directly there; while a recording is running the video is buffered in cache space and copied across when it finishes, because MP4 muxers need a seekable file.
- **Notifications.** Every running capture has a persistent notification with a Stop action, and Android shows its own screen-share and microphone indicators. Nothing in this app records silently.
- **Recent library.** Shows ScreenKit-owned captures from the media library plus anything saved in your chosen folder, and opens them in an installed viewer/player.

## What Android does not allow (and why this app cannot work around it)

These are platform rules, not missing features. Every screen recorder on Android lives with them, and deliberately bypassing them would mean exploiting the OS rather than using its APIs.

- **Consent per capture session.** On Android 14+, a MediaProjection token is good for exactly one `createVirtualDisplay` call. Android throws a `SecurityException` if an app caches the consent result or reuses one projection for several captures. So a screenshot or a recording started from the bubble always shows Android's sheet — ScreenKit just makes sure that sheet appears without opening the app.
- **Phone calls cannot be recorded.** Call audio never appears in the audio graphs an app is allowed to capture: Android's playback capture only exposes media, game and unknown usage streams. There is no public API for call uplink/downlink, and non-consensual call recording is illegal in many countries. Recording your own side through the speaker and the microphone is the only thing any normal app can do.
- **Apps that opt out stay silent.** Any app can set its playback-capture policy to disallow (most streaming, banking and call apps do). Those produce silence, and their windows are usually blank too.
- **Protected windows come out blank.** Windows marked `FLAG_SECURE` (banking, DRM, some password fields) cannot be captured by any app.
- **Screen sharing stops when the device locks** (Android 15 QPR1+), and single-app sharing freezes anything outside the chosen app. Whole-screen scope is the setting that avoids the freeze.
- **Camera in the background is best-effort.** Android requires camera use to begin from an eligible, user-visible flow; vendor power policies can still stop it. The app starts a camera foreground service, and reports honestly when the OS refuses.

Captures are personal-use only: recording other people, or recording a device you do not own, may be illegal where you live. ScreenKit always shows a notification and an OS indicator while it captures.
