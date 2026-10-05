package com.creep.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.creep.screenrecorder.data.CaptureAudioMode
import com.creep.screenrecorder.data.SettingsStore
import com.creep.screenrecorder.services.CameraCaptureService
import com.creep.screenrecorder.services.FloatingOverlayService
import com.creep.screenrecorder.services.ScreenCaptureService

/**
 * The invisible hop between a tap on the floating control and Android's permission or consent
 * sheet.
 *
 * Two platform rules force something like this to exist: only an activity can show the
 * screen-capture consent sheet, and Android 14+ expects the capture service to be started while
 * the app is visible. This activity is the smallest thing that satisfies both — a transparent,
 * animation-free window that starts exactly what the tap asked for and disappears immediately. It
 * never shows an app screen, so a screenshot taken from the bubble captures what the user was
 * looking at instead of ScreenKit itself.
 */
class CaptureRequestActivity : ComponentActivity() {
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>

    private var command: String? = null
    private var pendingMode: String? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var handled = false
    private var overlaySettingsPending = false
    private val watchdog = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        command = intent?.getStringExtra(CaptureContract.EXTRA_OVERLAY_COMMAND)
            ?: savedInstanceState?.getString(KEY_COMMAND)
        pendingMode = savedInstanceState?.getString(KEY_MODE)
        overlaySettingsPending = savedInstanceState?.getBoolean(KEY_OVERLAY_SETTINGS) ?: false
        // A restored instance already has a launcher in flight; re-running the command would show
        // Android's consent sheet twice.
        if (savedInstanceState != null) handled = true

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (action != null) action() else finishSafely()
        }
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val mode = pendingMode ?: command ?: CaptureContract.MODE_SCREENSHOT
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                CaptureIntents.toast(this, "Screen capture was cancelled.")
                finishSafely()
                return@registerForActivityResult
            }
            if (!CaptureIntents.startScreenCapture(this, mode, result.resultCode, data)) {
                CaptureIntents.toast(this, "Could not start the screen capture.")
            }
            finishSafely()
        }
        overlaySettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            if (overlaySettingsPending && Settings.canDrawOverlays(this)) {
                overlaySettingsPending = false
                startCameraPreviewFlow()
            } else {
                finishSafely()
            }
        }

        // Never leave a stray transparent window behind if a system sheet never comes back.
        watchdog.postDelayed({
            if (!isFinishing) finishSafely()
        }, WATCHDOG_MS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_COMMAND, command)
        outState.putString(KEY_MODE, pendingMode)
        outState.putBoolean(KEY_OVERLAY_SETTINGS, overlaySettingsPending)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (handled) return
        handled = true
        when (command) {
            CaptureContract.COMMAND_SCREENSHOT -> requestScreenCapture(CaptureContract.MODE_SCREENSHOT)
            CaptureContract.COMMAND_TOGGLE_SCREEN_RECORDING -> {
                if (ScreenCaptureService.isRecording) {
                    CaptureIntents.stopScreenCapture(this)
                    CaptureIntents.toast(this, "Saving the screen recording…")
                    finishSafely()
                } else {
                    requestScreenCapture(CaptureContract.MODE_SCREEN_RECORDING)
                }
            }
            CaptureContract.COMMAND_TOGGLE_CAMERA_RECORDING -> {
                if (CameraCaptureService.isRecording) {
                    CaptureIntents.stopCameraCapture(this)
                    finishSafely()
                } else {
                    requestPermissions(CaptureContract.MODE_CAMERA_RECORDING) { continueCameraRecording() }
                }
            }
            CaptureContract.COMMAND_TOGGLE_CAMERA_OVERLAY -> {
                if (CameraCaptureService.isPreviewVisible) {
                    CaptureIntents.hideCameraPreview(this)
                    finishSafely()
                } else {
                    startCameraPreviewFlow()
                }
            }
            CaptureContract.COMMAND_FLIP_CAMERA -> {
                flipCamera()
                finishSafely()
            }
            CaptureContract.COMMAND_CYCLE_AUDIO, CaptureContract.COMMAND_TOGGLE_AUDIO -> cycleAudio()
            CaptureContract.COMMAND_OPEN_APP -> {
                openMainApp()
                finishSafely()
            }
            CaptureContract.COMMAND_HIDE_OVERLAY -> {
                FloatingOverlayService.hide(this)
                finishSafely()
            }
            null -> finishSafely()
            else -> {
                CaptureIntents.toast(this, "ScreenKit does not know that action.")
                finishSafely()
            }
        }
    }

    // ------------------------------------------------------------------ screen capture

    private fun requestScreenCapture(mode: String) {
        pendingMode = mode
        requestPermissions(mode) { continueScreenCapture(mode) }
    }

    private fun continueScreenCapture(mode: String) {
        if (mode == CaptureContract.MODE_SCREEN_RECORDING && CameraCaptureService.isRecording) {
            CaptureIntents.toast(this, "Stop the camera recording before starting a screen recording.")
            finishSafely()
            return
        }
        if (!CaptureIntents.canWriteExternalStorage(this)) {
            CaptureIntents.toast(this, "Storage permission is needed to save captures on this Android version.")
            finishSafely()
            return
        }
        val consent = CaptureIntents.projectionConsentIntent(this)
        if (consent == null) {
            CaptureIntents.toast(this, "Screen capture is not available on this device.")
            finishSafely()
            return
        }
        runCatching { projectionLauncher.launch(consent) }.onFailure {
            CaptureIntents.toast(this, "Could not open Android's screen-capture prompt.")
            finishSafely()
        }
    }

    // ------------------------------------------------------------------ camera

    private fun continueCameraRecording() {
        if (ScreenCaptureService.isActive) {
            CaptureIntents.toast(this, "Finish the screen capture before starting a camera recording.")
            finishSafely()
            return
        }
        if (!CaptureIntents.hasPermission(this, Manifest.permission.CAMERA)) {
            CaptureIntents.toast(this, "Camera permission is required to record video.")
            finishSafely()
            return
        }
        if (!CaptureIntents.canWriteExternalStorage(this)) {
            CaptureIntents.toast(this, "Storage permission is needed to save a camera video.")
            finishSafely()
            return
        }
        val audio = SettingsStore.audioMode(this)
        if (!CaptureIntents.startCameraRecording(this)) {
            CaptureIntents.toast(this, "Could not start the camera recording.")
        } else if (audio.usesMicrophone && !CaptureIntents.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            CaptureIntents.toast(this, "Recording video without sound.")
        }
        finishSafely()
    }

    private fun startCameraPreviewFlow() {
        requestPermissions(CaptureContract.MODE_CAMERA_PREVIEW) { continueCameraPreview() }
    }

    private fun continueCameraPreview() {
        if (!CaptureIntents.hasPermission(this, Manifest.permission.CAMERA)) {
            CaptureIntents.toast(this, "Camera permission is required for the live preview.")
            finishSafely()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsPending = true
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { overlaySettingsLauncher.launch(intent) }.onFailure {
                CaptureIntents.toast(this, "Allow display over other apps to show the camera preview.")
                finishSafely()
            }
            return
        }
        val front = SettingsStore.cameraFront(this)
        if (!CaptureIntents.startCameraPreview(this, front)) {
            CaptureIntents.toast(this, "Could not start the camera preview.")
        } else {
            SettingsStore.setCameraOverlayEnabled(this, true)
        }
        finishSafely()
    }

    private fun flipCamera() {
        val front = !SettingsStore.cameraFront(this)
        SettingsStore.setCameraFront(this, front)
        val label = if (front) "Front camera" else "Back camera"
        when {
            CameraCaptureService.isRecording ->
                CaptureIntents.toast(this, "Stop the camera recording before switching cameras.")
            CameraCaptureService.isActive -> {
                CaptureIntents.setCameraFacing(this, front)
                CaptureIntents.toast(this, label)
            }
            else -> CaptureIntents.toast(this, "$label selected for the next capture")
        }
    }

    // ------------------------------------------------------------------ audio

    private fun cycleAudio() {
        val next = SettingsStore.audioMode(this).next()
        if (next.requiresRecordPermission &&
            !CaptureIntents.hasPermission(this, Manifest.permission.RECORD_AUDIO)
        ) {
            pendingPermissionAction = { applyAudioMode(next) }
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        applyAudioMode(next)
    }

    private fun applyAudioMode(requested: CaptureAudioMode) {
        val applied = CaptureIntents.effectiveAudioMode(this, requested)
        SettingsStore.setAudioMode(this, applied)
        if (requested.usesDeviceAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            CaptureIntents.toast(this, "Device audio needs Android 10 or newer.")
        } else if (applied != requested) {
            CaptureIntents.toast(this, "Microphone permission is off, so sound was set to ${applied.label}.")
        } else if (ScreenCaptureService.isRecording) {
            CaptureIntents.toast(this, "Screen-recording audio is fixed when the MP4 starts; this applies next time.")
        } else {
            if (CameraCaptureService.isActive) {
                CaptureIntents.setCameraAudioMuted(this, !applied.usesMicrophone)
            }
            CaptureIntents.toast(this, "Sound: ${applied.label}")
        }
        finishSafely()
    }

    // ------------------------------------------------------------------ helpers

    private fun requestPermissions(mode: String, action: () -> Unit) {
        val missing = CaptureIntents.requiredPermissions(this, mode)
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingPermissionAction = action
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        runCatching { startActivity(intent) }
    }

    @Suppress("DEPRECATION")
    private fun finishSafely() {
        runCatching { finish() }
        runCatching { overridePendingTransition(0, 0) }
    }

    private companion object {
        const val KEY_COMMAND = "screenkit.request.command"
        const val KEY_MODE = "screenkit.request.mode"
        const val KEY_OVERLAY_SETTINGS = "screenkit.request.overlay_settings"
        const val WATCHDOG_MS = 90_000L
    }
}
