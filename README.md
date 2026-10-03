# ScreenKit

A native Android app for screen screenshots, screen recording, CameraX video capture, and a draggable floating control. Captures are stored locally with MediaStore.

## Download and install

[⬇️ **Download the latest ScreenKit APK**](https://github.com/sharthak18/creep/releases/latest/download/ScreenKit.apk)

On Android, open the downloaded APK and follow the install prompt. If Android asks, allow your browser or file manager to install unknown apps. This is a sideloadable debug build, not a Play Store release.

**No APK is published yet.** The link will work after a `v*` version tag is pushed; the Android APK workflow will build the installer and attach `ScreenKit.apk` to that GitHub Release. To create a test build without a release, open **Actions → Android APK → Run workflow** and download the `ScreenKit.apk` artifact from the completed run.

## Build

- Open the repository in Android Studio with **JDK 17** installed.
- Install **Android SDK Platform 34** and let Gradle sync.
- Run the `app` configuration on a device/emulator running **Android 7.0 (API 24) or later**. CameraX device support and available recording qualities vary by hardware.

Build from a terminal with Gradle 8.9 and `gradle :app:assembleDebug`. The repository contains Kotlin DSL build files; it does not currently include the binary Gradle wrapper JAR.

## Features

- **Screenshot:** obtains fresh Android MediaProjection approval for each screenshot, reads a full-resolution RGBA frame, and saves PNGs to `Pictures/ScreenCapture`.
- **Screen recording:** H.264/MP4 through MediaRecorder and MediaProjection; scales proportionally to a maximum 1920-pixel long edge. Optional microphone audio is selected before starting a session.
- **Camera recording:** CameraX front/back camera video to `Movies/CameraCapture`. If microphone access is enabled for the session, CameraX can mute/unmute the active camera recording.
- **Camera preview overlay:** a draggable CameraX preview window can sit over other apps and be included in a screen recording when Android's sharing dialog is set to **Entire screen**. An app-only share may exclude the preview and floating windows.
- **Floating controls:** the user can enable an always-visible draggable bubble, snap it to either edge, and use its horizontal shortcuts for screenshots, screen/camera recording, camera preview, microphone preference, and app settings. A capture is never started without the relevant Android permission/consent.
- **Notifications:** each running foreground capture has a persistent notification with a Stop action. Screen-off camera recording uses a foreground camera service and wake lock, but final behavior is still subject to Android privacy controls, vendor camera policies, thermal/battery limits, and OS termination.
- **Recent library:** shows ScreenKit-owned captures and opens them in an installed viewer/player.

## Important Android limitations

- MediaProjection approval is per capture. Android's system prompt can limit sharing to one app, and secure/DRM-protected content can be blank or unavailable.
- MediaRecorder fixes its audio track when a screen MP4 starts, so screen-recording audio cannot be muted/unmuted mid-file. Stop and restart to change it. CameraX 1.5 provides live mute/unmute for camera recordings that started with microphone audio enabled.
- Android requires camera use to begin from an eligible, user-visible flow. The app starts a camera foreground service from its UI; Android or a device vendor can still stop or restrict camera access when the screen is off. No app can guarantee background camera operation on every device.
- The camera preview and floating bubble are separate overlay windows. Android may omit them if the user selects single-app sharing; select **Entire screen** to request their inclusion. Some protected surfaces may remain excluded regardless.
- On Android 13+, notification permission can be declined; the capture service still follows Android foreground-service rules, but its notification may not appear in the notification drawer.
- ScreenKit only queries its own MediaStore items and does not request broad `READ_MEDIA_*` access. On Android 9 and earlier, it asks for the legacy storage permission needed to write to public media folders.
