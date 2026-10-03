package com.creep.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.creep.screenrecorder.data.MediaCapture
import com.creep.screenrecorder.data.MediaStoreRepository
import com.creep.screenrecorder.services.CameraCaptureService
import com.creep.screenrecorder.services.FloatingOverlayService
import com.creep.screenrecorder.services.ScreenCaptureService
import com.creep.screenrecorder.ui.CameraFacing
import com.creep.screenrecorder.ui.CaptureViewModel
import com.creep.screenrecorder.ui.MainScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: CaptureViewModel
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>

    private var pendingProjectionMode: String? = null
    private var pendingPermissionFlow: String? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var overlaySettingsPending = false
    private var cameraPreviewSettingsPending = false
    private var overlayAutoStartPending = false
    private var receiverRegistered = false
    private var showOverlayWelcome by androidx.compose.runtime.mutableStateOf(false)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                CaptureContract.ACTION_SCREEN_STATE -> {
                    val active = intent.getBooleanExtra(CaptureContract.EXTRA_ACTIVE, false)
                    val recording = intent.getBooleanExtra(CaptureContract.EXTRA_RECORDING, false)
                    val mode = intent.getStringExtra(CaptureContract.EXTRA_MODE).orEmpty()
                    val startedAt = intent.getLongExtra(CaptureContract.EXTRA_STARTED_AT, 0L)
                    viewModel.update { old ->
                        old.copy(
                            screenSessionActive = active,
                            screenRecording = active && recording,
                            screenshotInProgress = active && mode == "screenshot",
                            screenRecordingStartedAt = if (active && recording) startedAt else 0L,
                        )
                    }
                    if (!active) refreshRecentMedia()
                }
                CaptureContract.ACTION_CAMERA_STATE -> {
                    val active = intent.getBooleanExtra(CaptureContract.EXTRA_ACTIVE, false)
                    val recording = intent.getBooleanExtra(CaptureContract.EXTRA_RECORDING, false)
                    val preview = intent.getBooleanExtra(CaptureContract.EXTRA_PREVIEW, false)
                    val startedAt = intent.getLongExtra(CaptureContract.EXTRA_STARTED_AT, 0L)
                    val muted = intent.getBooleanExtra(CaptureContract.EXTRA_AUDIO_MUTED, true)
                    val front = intent.getBooleanExtra(CaptureContract.EXTRA_CAMERA_FRONT, false)
                    viewModel.update { old ->
                        old.copy(
                            cameraSessionActive = active,
                            cameraRecording = active && recording,
                            cameraPreviewVisible = active && preview,
                            cameraOverlayEnabled = active && preview,
                            cameraRecordingStartedAt = if (active && recording) startedAt else 0L,
                            cameraFacing = if (front) CameraFacing.FRONT else CameraFacing.BACK,
                            microphoneEnabled = if (recording) !muted else old.microphoneEnabled,
                        )
                    }
                }
                CaptureContract.ACTION_SCREEN_FINISHED,
                CaptureContract.ACTION_CAMERA_FINISHED -> {
                    val success = intent.getBooleanExtra(CaptureContract.EXTRA_SUCCESS, false)
                    val message = intent.getStringExtra(CaptureContract.EXTRA_MESSAGE)
                    if (!message.isNullOrBlank()) showMessage(message)
                    if (!success) {
                        // A failed screenshot/video still needs to clear a pending busy state.
                        syncServiceStates()
                    }
                    refreshRecentMedia()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProjectionMode = savedInstanceState?.getString("pending_projection_mode")
        pendingPermissionFlow = savedInstanceState?.getString("pending_permission_flow")
        overlaySettingsPending = savedInstanceState?.getBoolean("overlay_settings_pending") ?: false
        cameraPreviewSettingsPending = savedInstanceState?.getBoolean("camera_preview_settings_pending") ?: false
        overlayAutoStartPending = savedInstanceState?.getBoolean("overlay_auto_start_pending") ?: false
        viewModel = ViewModelProvider(this)[CaptureViewModel::class.java]
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            refreshPermissionSnapshot()
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (action != null) {
                pendingPermissionFlow = null
                action()
            } else {
                val flow = pendingPermissionFlow
                pendingPermissionFlow = null
                resumePermissionFlow(flow)
            }
        }
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val mode = pendingProjectionMode
            pendingProjectionMode = null
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null || mode == null) {
                showMessage("Screen capture was cancelled.")
                return@registerForActivityResult
            }
            val prefs = getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE)
            val includeAudio = prefs.getBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, false) &&
                hasPermission(Manifest.permission.RECORD_AUDIO)
            val service = Intent(this, ScreenCaptureService::class.java)
                .setAction(if (mode == "screenshot") ScreenCaptureService.ACTION_SCREENSHOT
                    else ScreenCaptureService.ACTION_START_RECORDING)
                .putExtra(CaptureContract.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(CaptureContract.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureContract.EXTRA_AUDIO_ENABLED, includeAudio)
            runCatching { startCaptureService(service) }
                .onFailure { showMessage("Could not start screen capture. Please try again.") }
        }
        overlaySettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            refreshPermissionSnapshot()
            continueAfterOverlaySettings()
        }

        val preferences = getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE)
        val microphoneEnabled = preferences.getBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, false)
            && hasPermission(Manifest.permission.RECORD_AUDIO)
        val cameraFront = preferences.getBoolean(CaptureContract.PREF_CAMERA_FRONT, false)
        viewModel.update {
            it.copy(
                microphoneEnabled = microphoneEnabled,
                cameraOverlayEnabled = CameraCaptureService.isPreviewVisible,
                cameraFacing = if (cameraFront) CameraFacing.FRONT else CameraFacing.BACK,
                overlayEnabled = FloatingOverlayService.isRunning,
            )
        }
        refreshPermissionSnapshot()
        syncServiceStates()
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            MainScreen(
                state = state,
                showOverlayWelcome = showOverlayWelcome,
                onDismissOverlayWelcome = {
                    showOverlayWelcome = false
                    markOverlayIntroSeen()
                },
                onEnableOverlay = {
                    showOverlayWelcome = false
                    markOverlayIntroSeen()
                    enableFloatingOverlay()
                },
                onDisableOverlay = { disableFloatingOverlay() },
                onTakeScreenshot = { requestScreenCapture("screenshot") },
                onToggleScreenRecording = { toggleScreenRecording() },
                onToggleCameraRecording = { toggleCameraRecording() },
                onToggleMicrophone = { enabled -> setMicrophoneEnabled(enabled) },
                onToggleCameraOverlay = { enabled -> setCameraOverlayEnabled(enabled) },
                onCameraFacingSelected = { facing -> setCameraFacing(facing) },
                onOpenCapture = { capture -> openCapture(capture) },
            )
        }

        val introAlreadySeen = preferences.getBoolean("overlay_intro_seen", false)
        if (!introAlreadySeen && !Settings.canDrawOverlays(this)) {
            showOverlayWelcome = true
        } else if (Settings.canDrawOverlays(this) &&
            (!introAlreadySeen || preferences.getBoolean(CaptureContract.PREF_OVERLAY_ENABLED, false))) {
            markOverlayIntroSeen()
            overlayAutoStartPending = true
        }
        handleOverlayCommand(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_projection_mode", pendingProjectionMode)
        outState.putString("pending_permission_flow", pendingPermissionFlow)
        outState.putBoolean("overlay_settings_pending", overlaySettingsPending)
        outState.putBoolean("camera_preview_settings_pending", cameraPreviewSettingsPending)
        outState.putBoolean("overlay_auto_start_pending", overlayAutoStartPending)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(CaptureContract.ACTION_SCREEN_STATE)
            addAction(CaptureContract.ACTION_SCREEN_FINISHED)
            addAction(CaptureContract.ACTION_CAMERA_STATE)
            addAction(CaptureContract.ACTION_CAMERA_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionSnapshot()
        syncServiceStates()
        refreshRecentMedia()
        continueAfterOverlaySettings()
        val shouldRestoreOverlay = getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(CaptureContract.PREF_OVERLAY_ENABLED, false)
        if (overlayAutoStartPending || (shouldRestoreOverlay && Settings.canDrawOverlays(this)
                && !FloatingOverlayService.isRunning && !overlaySettingsPending)) {
            overlayAutoStartPending = false
            startFloatingOverlay()
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(permissionReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshPermissionSnapshot()
        syncServiceStates()
        handleOverlayCommand(intent)
    }

    private fun handleOverlayCommand(intent: Intent?) {
        when (intent?.getStringExtra(CaptureContract.EXTRA_OVERLAY_COMMAND)) {
            CaptureContract.COMMAND_SCREENSHOT -> requestScreenCapture("screenshot")
            CaptureContract.COMMAND_TOGGLE_SCREEN_RECORDING -> toggleScreenRecording()
            CaptureContract.COMMAND_TOGGLE_CAMERA_RECORDING -> toggleCameraRecording()
            CaptureContract.COMMAND_TOGGLE_CAMERA_OVERLAY -> {
                val enabled = !viewModel.state.value.cameraOverlayEnabled
                setCameraOverlayEnabled(enabled)
            }
            CaptureContract.COMMAND_TOGGLE_AUDIO -> {
                val enabled = !viewModel.state.value.microphoneEnabled
                setMicrophoneEnabled(enabled)
            }
            CaptureContract.COMMAND_OPEN_APP, null -> Unit
        }
    }

    private fun refreshPermissionSnapshot() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val cameraGranted = hasPermission(Manifest.permission.CAMERA)
        val microphoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val storageGranted = Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        viewModel.update { state ->
            state.copy(
                overlayPermission = overlayGranted,
                cameraPermission = cameraGranted,
                microphonePermission = microphoneGranted,
                notificationPermission = notificationsGranted,
                storagePermission = storageGranted,
                overlayEnabled = overlayGranted && FloatingOverlayService.isRunning,
            )
        }
    }

    private fun syncServiceStates() {
        val screenActive = ScreenCaptureService.isActive
        val screenRecording = ScreenCaptureService.isRecording
        val screenMode = ScreenCaptureService.activeMode
        val cameraActive = CameraCaptureService.isActive
        viewModel.update { old ->
            old.copy(
                screenSessionActive = screenActive,
                screenRecording = screenActive && screenRecording,
                screenshotInProgress = screenActive && screenMode == "screenshot",
                screenRecordingStartedAt = if (screenRecording) ScreenCaptureService.recordingStartedAt else 0L,
                cameraSessionActive = cameraActive,
                cameraRecording = cameraActive && CameraCaptureService.isRecording,
                cameraPreviewVisible = cameraActive && CameraCaptureService.isPreviewVisible,
                cameraOverlayEnabled = cameraActive && CameraCaptureService.isPreviewVisible,
                cameraRecordingStartedAt = if (CameraCaptureService.isRecording) {
                    CameraCaptureService.recordingStartedAt
                } else 0L,
                microphoneEnabled = if (CameraCaptureService.isRecording) {
                    !CameraCaptureService.muted
                } else old.microphoneEnabled,
            )
        }
    }

    private fun refreshRecentMedia() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                MediaStoreRepository.queryRecent(this@MainActivity)
            }
            viewModel.setRecentCaptures(items)
        }
    }

    private fun requestScreenCapture(mode: String) {
        val state = viewModel.state.value
        if (state.screenRecording) {
            if (mode == "screenshot") {
                showMessage("Stop the current screen recording before taking a screenshot.")
            } else {
                stopScreenRecording()
            }
            return
        }
        if (state.screenSessionActive || state.cameraRecording) {
            showMessage("Finish the active capture before starting another one.")
            return
        }
        pendingProjectionMode = mode
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (mode == "screen_recording" && state.microphoneEnabled &&
                !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        runWithPermissions(required, permissionFlow = "screen:$mode") {
            continueScreenCaptureAfterPermissions(mode)
        }
    }

    private fun continueScreenCaptureAfterPermissions(mode: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pendingProjectionMode = null
            showMessage("Storage permission is needed to save captures on this Android version.")
            return
        }
        if (mode == "screen_recording" &&
            getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, false) &&
            !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, false).apply()
            viewModel.update { it.copy(microphoneEnabled = false, microphonePermission = false) }
            showMessage("Microphone permission was declined; the screen recording will be silent.")
        }
        launchProjectionConsent(mode)
    }

    private fun launchProjectionConsent(mode: String) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            pendingProjectionMode = null
            showMessage("Screen capture is not available on this device.")
            return
        }
        pendingProjectionMode = mode
        runCatching { projectionLauncher.launch(projectionManager.createScreenCaptureIntent()) }
            .onFailure {
                pendingProjectionMode = null
                showMessage("Could not open Android's screen-capture approval prompt.")
            }
    }

    private fun toggleScreenRecording() {
        val state = viewModel.state.value
        when {
            state.screenRecording -> stopScreenRecording()
            state.screenSessionActive -> showMessage("A screenshot is already being saved.")
            else -> requestScreenCapture("screen_recording")
        }
    }

    private fun stopScreenRecording() {
        runCatching {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        }.onFailure { showMessage("Use the ScreenKit capture notification to stop the recording.") }
    }

    private fun toggleCameraRecording() {
        val state = viewModel.state.value
        if (state.cameraRecording) {
            runCatching {
                startService(Intent(this, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP))
            }.onFailure { showMessage("Use the camera capture notification to stop recording.") }
            return
        }
        if (state.screenSessionActive) {
            showMessage("Finish the screen capture before starting a camera recording.")
            return
        }
        val required = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (viewModel.state.value.microphoneEnabled &&
                !hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        }
        runWithPermissions(required, permissionFlow = "camera_recording") {
            continueCameraRecordingAfterPermissions()
        }
    }

    private fun continueCameraRecordingAfterPermissions() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            showMessage("Camera permission is required to record video.")
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            showMessage("Storage permission is needed to save a camera video on this Android version.")
            return
        }
        if (viewModel.state.value.microphoneEnabled &&
            !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            saveMicrophonePreference(false)
            showMessage("Microphone access was declined; the camera video will be silent.")
        }
        startCameraRecording()
    }

    private fun startCameraRecording() {
        val state = viewModel.state.value
        val audioCapable = state.microphoneEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)
        val intent = Intent(this, CameraCaptureService::class.java)
            .setAction(CameraCaptureService.ACTION_START_RECORDING)
            .putExtra(CaptureContract.EXTRA_CAMERA_FRONT, state.cameraFacing == CameraFacing.FRONT)
            .putExtra(CaptureContract.EXTRA_AUDIO_CAPABLE, audioCapable)
            .putExtra(CaptureContract.EXTRA_AUDIO_MUTED, !state.microphoneEnabled)
            .putExtra(CaptureContract.EXTRA_SHOW_CAMERA_OVERLAY, state.cameraOverlayEnabled)
        runCatching { startCaptureService(intent) }
            .onFailure { showMessage("Could not start camera capture. Please try again.") }
    }

    private fun setCameraOverlayEnabled(enabled: Boolean) {
        if (!enabled) {
            getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("camera_overlay_enabled", false).apply()
            viewModel.update { it.copy(cameraOverlayEnabled = false, cameraPreviewVisible = false) }
            runCatching {
                startService(Intent(this, CameraCaptureService::class.java)
                    .setAction(CameraCaptureService.ACTION_HIDE_PREVIEW))
            }
            return
        }
        if (viewModel.state.value.cameraPreviewVisible) return
        if (!Settings.canDrawOverlays(this)) {
            cameraPreviewSettingsPending = true
            requestOverlaySettings()
            return
        }
        val required = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        runWithPermissions(required, permissionFlow = "camera_preview") {
            continueCameraPreviewAfterPermissions()
        }
    }

    private fun continueCameraPreviewAfterPermissions() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            showMessage("Camera permission is required for a live overlay preview.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            cameraPreviewSettingsPending = true
            requestOverlaySettings()
            return
        }
        startCameraPreview()
    }

    private fun startCameraPreview() {
        val facing = viewModel.state.value.cameraFacing
        val intent = Intent(this, CameraCaptureService::class.java)
            .setAction(CameraCaptureService.ACTION_SHOW_PREVIEW)
            .putExtra(CaptureContract.EXTRA_CAMERA_FRONT, facing == CameraFacing.FRONT)
        runCatching { startCaptureService(intent) }
            .onSuccess {
                getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean("camera_overlay_enabled", true).apply()
                viewModel.update { it.copy(cameraOverlayEnabled = true) }
            }
            .onFailure { showMessage("Could not start the live camera overlay.") }
    }

    private fun setCameraFacing(facing: CameraFacing) {
        val front = facing == CameraFacing.FRONT
        getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(CaptureContract.PREF_CAMERA_FRONT, front).apply()
        viewModel.setCameraFacing(facing)
        if (viewModel.state.value.cameraPreviewVisible && !viewModel.state.value.cameraRecording) {
            runCatching {
                startService(Intent(this, CameraCaptureService::class.java)
                    .setAction(CameraCaptureService.ACTION_SET_FACING)
                    .putExtra(CaptureContract.EXTRA_CAMERA_FRONT, front))
            }
        }
    }

    private fun setMicrophoneEnabled(enabled: Boolean) {
        val current = viewModel.state.value
        if (current.screenSessionActive) {
            showMessage("Screen-capture audio is fixed at the start of a recording; this setting applies next time.")
            return
        }
        if (enabled && (current.cameraRecording || CameraCaptureService.isStartingRecording) &&
            !CameraCaptureService.hasAudioTrack) {
            showMessage("Microphone audio was not enabled for this camera video. Stop and restart with Record Audio enabled.")
            return
        }
        if (enabled && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            runWithPermissions(
                listOf(Manifest.permission.RECORD_AUDIO),
                permissionFlow = "microphone_on",
            ) {
                continueEnableMicrophone()
            }
            return
        }
        saveMicrophonePreference(enabled)
        applyLiveAudioPreference(enabled)
    }

    private fun continueEnableMicrophone() {
        if ((viewModel.state.value.cameraRecording || CameraCaptureService.isStartingRecording) &&
            !CameraCaptureService.hasAudioTrack) {
            showMessage("Microphone audio was not enabled for this camera video. Stop and restart with Record Audio enabled.")
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            showMessage("Microphone access was not granted.")
            viewModel.update { it.copy(microphoneEnabled = false, microphonePermission = false) }
            return
        }
        saveMicrophonePreference(true)
        applyLiveAudioPreference(true)
    }

    private fun saveMicrophonePreference(enabled: Boolean) {
        getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, enabled).apply()
        viewModel.update { it.copy(microphoneEnabled = enabled, microphonePermission = hasPermission(Manifest.permission.RECORD_AUDIO)) }
    }

    private fun applyLiveAudioPreference(enabled: Boolean) {
        val state = viewModel.state.value
        if (state.cameraSessionActive) {
            runCatching {
                startService(Intent(this, CameraCaptureService::class.java)
                    .setAction(CameraCaptureService.ACTION_SET_AUDIO_MUTED)
                    .putExtra(CaptureContract.EXTRA_AUDIO_MUTED, !enabled))
            }
        }
        if (state.screenRecording) {
            showMessage("Screen-recording audio is fixed when the MP4 starts; this setting applies to the next recording.")
        }
    }

    private fun enableFloatingOverlay() {
        markOverlayIntroSeen()
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsPending = true
            requestOverlaySettings()
            return
        }
        startFloatingOverlay()
    }

    private fun requestOverlaySettings() {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { overlaySettingsLauncher.launch(settingsIntent) }
            .onFailure { showMessage("Open Android Settings to allow display over other apps.") }
    }

    private fun continueAfterOverlaySettings() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlaySettingsPending) {
            overlaySettingsPending = false
            startFloatingOverlay()
        }
        if (cameraPreviewSettingsPending) {
            cameraPreviewSettingsPending = false
            setCameraOverlayEnabled(true)
        }
    }

    private fun startFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            showMessage("Allow ScreenKit to display over other apps to enable the floating control.")
            return
        }
        runCatching { FloatingOverlayService.show(this) }
            .onSuccess {
                getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(CaptureContract.PREF_OVERLAY_ENABLED, true).apply()
                viewModel.update { it.copy(overlayPermission = true, overlayEnabled = true) }
            }
            .onFailure { showMessage("Could not show the floating control.") }
    }

    private fun disableFloatingOverlay() {
        runCatching { FloatingOverlayService.hide(this) }
        getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(CaptureContract.PREF_OVERLAY_ENABLED, false).apply()
        viewModel.update { it.copy(overlayEnabled = false) }
    }

    private fun markOverlayIntroSeen() {
        getSharedPreferences(CaptureContract.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean("overlay_intro_seen", true).apply()
    }

    private fun runWithPermissions(
        required: List<String>,
        permissionFlow: String? = null,
        action: () -> Unit,
    ) {
        val missing = required.distinct().filterNot(::hasPermission)
        if (missing.isEmpty()) {
            action()
        } else {
            pendingPermissionFlow = permissionFlow
            pendingPermissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun resumePermissionFlow(flow: String?) {
        when (flow) {
            "screen:screenshot" -> continueScreenCaptureAfterPermissions("screenshot")
            "screen:screen_recording" -> continueScreenCaptureAfterPermissions("screen_recording")
            "camera_recording" -> continueCameraRecordingAfterPermissions()
            "camera_preview" -> continueCameraPreviewAfterPermissions()
            "microphone_on" -> continueEnableMicrophone()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCaptureService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun openCapture(capture: MediaCapture) {
        val mime = if (capture.kind.isVideo) "video/mp4" else "image/png"
        val openIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(capture.uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(openIntent) }
            .onFailure { showMessage("No installed app can open this capture.") }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
