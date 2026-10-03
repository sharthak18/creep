package com.creep.screenrecorder.ui

import androidx.lifecycle.ViewModel
import com.creep.screenrecorder.data.MediaCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class CameraFacing { FRONT, BACK }

internal data class CaptureUiState(
    val overlayPermission: Boolean = false,
    val overlayEnabled: Boolean = false,
    val cameraPermission: Boolean = false,
    val microphonePermission: Boolean = false,
    val notificationPermission: Boolean = true,
    val storagePermission: Boolean = true,
    val microphoneEnabled: Boolean = false,
    val cameraOverlayEnabled: Boolean = false,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val screenSessionActive: Boolean = false,
    val screenRecording: Boolean = false,
    val screenshotInProgress: Boolean = false,
    val screenRecordingStartedAt: Long = 0L,
    val cameraSessionActive: Boolean = false,
    val cameraRecording: Boolean = false,
    val cameraPreviewVisible: Boolean = false,
    val cameraRecordingStartedAt: Long = 0L,
    val recentCaptures: List<MediaCapture> = emptyList(),
)

/** UI state is fed by Android permission checks and foreground-service state broadcasts. */
internal class CaptureViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()

    fun update(transform: (CaptureUiState) -> CaptureUiState) {
        mutableState.update(transform)
    }

    fun setRecentCaptures(captures: List<MediaCapture>) {
        mutableState.update { it.copy(recentCaptures = captures) }
    }

    fun setCameraFacing(facing: CameraFacing) {
        mutableState.update { it.copy(cameraFacing = facing) }
    }
}
