package com.screenkit.screenrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.screenkit.screenrecorder.data.CaptureAudioMode
import com.screenkit.screenrecorder.data.ProjectionScope
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.services.CameraCaptureService
import com.screenkit.screenrecorder.services.ScreenCaptureService

/**
 * Intent builders shared by the main activity and the overlay trampoline, so a button in the
 * bubble and the same button in the app always start an identical capture.
 */
internal object CaptureIntents {
    /**
     * Android 14+ shows a "share one app" option that freezes the recording as soon as the user
     * switches away. The default scope therefore asks for the whole display only.
     */
    fun projectionConsentIntent(context: Context): Intent? {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            SettingsStore.projectionScope(context) == ProjectionScope.ENTIRE_SCREEN
        ) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    fun startScreenCapture(
        context: Context,
        mode: String,
        resultCode: Int,
        data: Intent,
        cropRect: android.graphics.RectF? = null,
    ): Boolean {
        val audio = SettingsStore.audioMode(context)
        val intent = Intent(context, ScreenCaptureService::class.java)
            .setAction(
                if (mode == CaptureContract.MODE_SCREENSHOT || mode == CaptureContract.MODE_PARTIAL_SCREENSHOT) {
                    ScreenCaptureService.ACTION_SCREENSHOT
                } else {
                    ScreenCaptureService.ACTION_START_RECORDING
                },
            )
            .putExtra(CaptureContract.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureContract.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureContract.EXTRA_AUDIO_MODE, audio.id)
            .putExtra(
                CaptureContract.EXTRA_AUDIO_ENABLED,
                audio.usesMicrophone && hasPermission(context, Manifest.permission.RECORD_AUDIO),
            )
        if (cropRect != null) {
            intent.putExtra(CaptureContract.EXTRA_CROP_LEFT, cropRect.left)
            intent.putExtra(CaptureContract.EXTRA_CROP_TOP, cropRect.top)
            intent.putExtra(CaptureContract.EXTRA_CROP_RIGHT, cropRect.right)
            intent.putExtra(CaptureContract.EXTRA_CROP_BOTTOM, cropRect.bottom)
        }
        return startForeground(context, intent)
    }

    fun startCameraRecording(context: Context): Boolean {
        val audio = SettingsStore.audioMode(context)
        val audioCapable = audio.usesMicrophone && hasPermission(context, Manifest.permission.RECORD_AUDIO)
        val intent = Intent(context, CameraCaptureService::class.java)
            .setAction(CameraCaptureService.ACTION_START_RECORDING)
            .putExtra(CaptureContract.EXTRA_CAMERA_FRONT, SettingsStore.cameraFront(context))
            .putExtra(CaptureContract.EXTRA_AUDIO_CAPABLE, audioCapable)
            .putExtra(CaptureContract.EXTRA_AUDIO_MUTED, !audio.usesMicrophone)
            .putExtra(CaptureContract.EXTRA_SHOW_CAMERA_OVERLAY, SettingsStore.cameraOverlayEnabled(context))
        return startForeground(context, intent)
    }

    fun startCameraPreview(context: Context, front: Boolean): Boolean = startForeground(
        context,
        Intent(context, CameraCaptureService::class.java)
            .setAction(CameraCaptureService.ACTION_SHOW_PREVIEW)
            .putExtra(CaptureContract.EXTRA_CAMERA_FRONT, front),
    )

    /**
     * Stop commands go through startService, not startForegroundService: if no capture is running
     * there is nothing to promote, and a foreground-service start must always call startForeground.
     */
    fun stopScreenCapture(context: Context): Boolean = runCatching {
        context.startService(
            Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP),
        )
        true
    }.getOrDefault(false)

    fun stopCameraCapture(context: Context): Boolean = runCatching {
        context.startService(
            Intent(context, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP),
        )
        true
    }.getOrDefault(false)

    fun hideCameraPreview(context: Context): Boolean = runCatching {
        context.startService(
            Intent(context, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_HIDE_PREVIEW),
        )
        true
    }.getOrDefault(false)

    fun setCameraFacing(context: Context, front: Boolean): Boolean = runCatching {
        context.startService(
            Intent(context, CameraCaptureService::class.java)
                .setAction(CameraCaptureService.ACTION_SET_FACING)
                .putExtra(CaptureContract.EXTRA_CAMERA_FRONT, front),
        )
        true
    }.getOrDefault(false)

    fun setCameraAudioMuted(context: Context, muted: Boolean): Boolean = runCatching {
        context.startService(
            Intent(context, CameraCaptureService::class.java)
                .setAction(CameraCaptureService.ACTION_SET_AUDIO_MUTED)
                .putExtra(CaptureContract.EXTRA_AUDIO_MUTED, muted),
        )
        true
    }.getOrDefault(false)

    fun startForeground(context: Context, intent: Intent): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
        true
    }.getOrDefault(false)

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canWriteExternalStorage(context: Context): Boolean =
        Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** Runtime permissions a capture needs before Android's own consent sheet is shown. */
    fun requiredPermissions(context: Context, mode: String): List<String> {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !canWriteExternalStorage(context)) {
            required += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        when (mode) {
            CaptureContract.MODE_SCREEN_RECORDING -> {
                if (SettingsStore.audioMode(context).requiresRecordPermission &&
                    !hasPermission(context, Manifest.permission.RECORD_AUDIO)
                ) {
                    required += Manifest.permission.RECORD_AUDIO
                }
            }
            CaptureContract.MODE_CAMERA_RECORDING -> {
                if (!hasPermission(context, Manifest.permission.CAMERA)) {
                    required += Manifest.permission.CAMERA
                }
                if (SettingsStore.audioMode(context).usesMicrophone &&
                    !hasPermission(context, Manifest.permission.RECORD_AUDIO)
                ) {
                    required += Manifest.permission.RECORD_AUDIO
                }
            }
            CaptureContract.MODE_CAMERA_PREVIEW -> {
                if (!hasPermission(context, Manifest.permission.CAMERA)) {
                    required += Manifest.permission.CAMERA
                }
            }
            CaptureContract.MODE_MICROPHONE -> {
                if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                    required += Manifest.permission.RECORD_AUDIO
                }
            }
            else -> Unit
        }
        return required.distinct()
    }

    /** The audio mode that will actually be used, taking permissions and API level into account. */
    fun effectiveAudioMode(context: Context, requested: CaptureAudioMode): CaptureAudioMode {
        if (!requested.requiresRecordPermission) return requested
        if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            return if (requested.usesDeviceAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requested
            } else {
                CaptureAudioMode.NONE
            }
        }
        return requested
    }

    fun toast(context: Context, message: String) {
        runCatching { Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show() }
    }
}
