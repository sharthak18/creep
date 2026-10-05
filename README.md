# ScreenKit

A native, high-performance Android capture suite built with Modern Android Architecture (Jetpack Compose, CameraX, Foreground Services, MediaProjection, OpenGL ES, and MediaCodec). ScreenKit delivers screen recording with custom cropping, screenshot capture with auto-hidden overlays, live interactive brush annotation, a draggable floating camera lens, sound recording (Mic / Device Audio / Mixed), and zero cloud uploads.

---

## Download and Install

[⬇️ **Download the latest ScreenKit APK**](https://github.com/sharthak18/ScreenKit/releases/latest/download/ScreenKit.apk)

- Compatible with **Android 7.0 (API 24) to Android 15 (API 35)**.
- Device Audio playback capture supported on **Android 10+**.
- Full screen scope and single-app exclusion selection supported on **Android 14+**.
- On Android, open the downloaded APK and follow the system prompt. Allow "Install unknown apps" for your browser or file manager if prompted.

---

## Key Features

### 1. Smart Floating Overlay & Quick Action Bubble
- **Non-Intrusive Quick Controls**: Operates as a system overlay bubble that never switches your active app or pulls ScreenKit to the foreground.
- **Two-Row Responsive Menu**: Quick access to Full Screenshot, Cropped Screenshot, Full Screen Recording, Cropped Screen Recording, Camera Video, Camera Lens Overlay, Lens Flip, Audio Mode Cycling, Drawing Brush Tool, and Dismissal.
- **Snap-to-Edge & Drag-to-Dismiss**: Smooth animated physics that snap to the nearest screen edge or smoothly dismiss into a bottom removal target.
- **Automatic Overlay Launch**: Automatically initiates on launch once display permission is granted.

### 2. Auto-Hiding Overlay for Clean Screenshots
- **No Bubble in Screenshots**: When capturing full or cropped screenshots, ScreenKit automatically broadcasts an instant temporary hide signal to the floating bubble and all overlay tools before the display frame is captured, restoring visibility immediately after capture.
- **Full-Resolution Capture**: Clean, untouched PNG output saved directly to your chosen destination.

### 3. Screen Brush & Drawing Tool
- **On-Screen Canvas Annotation**: Draw lines, highlight UI elements, point out bugs, or mark tutorial steps directly over any application on screen.
- **Color Palette & Stroke Customization**: Multiple vibrant colors (Red, Green, Blue, Yellow, Orange, Pink, White).
- **Undo, Eraser & Clear Screen**: Step-by-step stroke undo, precise eraser brush, and one-tap canvas clearing.
- **Transparent Drawing Overlay**: Floating bottom control bar with a translucent drawing surface that does not interfere with the active app's layout.

### 4. Flexible Floating Camera Overlay & Audio Toggling
- **Live Camera Lens**: Launch a draggable live CameraX preview overlay anywhere on screen.
- **Concurrent Screen Recording**: Freely open or close the floating camera lens *before or during* active screen recording without interruptions or restrictions.
- **One-Tap Camera Flip**: Switch between front and back camera lenses on the fly.
- **Flexible Sound Modes**: Toggle or cycle between:
  - **Mute / Silent** (Video only)
  - **Microphone** (Voiceover & commentary)
  - **Device Audio** (Internal app and game audio via Android `AudioPlaybackCapture`)
  - **Mixed Audio** (Simultaneous microphone and internal device sound mixed into AAC)

### 5. Touch Feedback ("Show Touches")
- **Visual Touch Points**: Toggle "Show touches" in the settings card. ScreenKit automatically activates touch visualization or provides one-tap navigation to Android Developer Options (`Show taps` / `Show touches`).

### 6. Crop Area Recording & Cropped Screenshots
- **Area Selection Overlay**: Interactive bounding box selection to specify any sub-region of the screen.
- **Real-Time OpenGL ES Cropping**: Cropped screen recording utilizes an internal EGL texture and OpenGL ES external OES shader pipeline (`CropSurfaceRenderer`), rendering only the specified coordinates directly into the hardware H.264 `MediaCodec` input surface.
- **Crop Screenshots**: Saves only the selected sub-rectangle with zero padding.

### 7. Custom Storage & Privacy
- **Configurable Save Destinations**:
  - Device MediaStore gallery (`Pictures/ScreenCapture` and `Movies/ScreenCapture`).
  - Secondary storage volumes (SD cards) with volume-aware MediaStore indexing.
  - Custom SAF folder chosen through Android's Storage Access Framework.
- **Local & Private**: All encoding and saving is strictly on-device. No telemetry, no background cloud uploads.

---

## Technical Architecture

```
                       ┌─────────────────────────────────────────┐
                       │          MainActivity (Compose)         │
                       └───────────────────┬─────────────────────┘
                                           │
                      ┌────────────────────┴─────────────────────┐
                      ▼                                          ▼
     ┌──────────────────────────────────┐      ┌──────────────────────────────────┐
     │      FloatingOverlayService      │      │       BrushOverlay / Canvas      │
     │  - Draggable Bubble & Actions    │      │  - Custom Interactive Canvas    │
     │  - Auto-Hide during Screenshot   │      │  - Color & Undo Management       │
     └────────────────┬─────────────────┘      └──────────────────────────────────┘
                      │
                      ▼
     ┌──────────────────────────────────┐      ┌──────────────────────────────────┐
     │      CaptureRequestActivity      │      │       CameraCaptureService       │
     │  - Transparent Permission Hop    │◄────►│  - CameraX Video & Preview       │
     │  - Per-Capture Consent Sheets    │      │  - Overlay Draggable Window      │
     └────────────────┬─────────────────┘      └──────────────────────────────────┘
                      │
                      ▼
     ┌──────────────────────────────────────────────────────────┐
     │                  ScreenCaptureService                    │
     │  - MediaProjection Display Feeding                       │
     │  - CropSurfaceRenderer (EGL14 + GLES20 Texture Crop)     │
     │  - MediaCodec + MediaMuxer (AudioPlaybackCapture Engine) │
     │  - ImageReader Frame Pipeline for Clean Screenshots      │
     └──────────────────────────────────────────────────────────┘
```

---

## Platform Limitations & Android Security Constraints

ScreenKit is built on standard Android APIs without compromising OS integrity or requiring root access:

- **Per-Capture Consent**: On Android 14+ (API 34+), MediaProjection tokens cannot be reused for multiple capture sessions by system security design. ScreenKit streamlines this by hosting consent flows in a transient, zero-animation activity so the user never leaves their target application.
- **Protected Windows (`FLAG_SECURE`)**: DRM-protected content (e.g., banking apps, copyright-protected streaming) produces blank output by Android hardware security design.
- **Call Audio Capture**: Telephony voice calls cannot be captured via Android's internal `AudioPlaybackCapture` graph; microphone recording is used for commentary.
- **System Settings Permissions**: Automated toggling of global system settings (such as touch feedback) requires `WRITE_SETTINGS` or Developer Options on modern Android versions. ScreenKit attempts direct programmatic configuration and offers one-tap developer options shortcuts when restricted.

---

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/sharthak18/ScreenKit.git
   cd ScreenKit
   ```
2. Open in **Android Studio Ladybug (or newer)** with **JDK 17**.
3. Target SDK: **35**, Minimum SDK: **24**.
4. Build debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

---

## License

This project is licensed under the Apache License 2.0. See the LICENSE file for details.
