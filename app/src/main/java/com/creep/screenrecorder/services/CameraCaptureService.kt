package com.creep.screenrecorder.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.creep.screenrecorder.CaptureContract
import com.creep.screenrecorder.MainActivity
import com.creep.screenrecorder.R
import com.creep.screenrecorder.data.CaptureKind
import com.creep.screenrecorder.data.MediaStoreRepository
import java.util.Locale
import java.util.concurrent.Executor

/** CameraX foreground service for user-requested camera video and a visible draggable preview. */
class CameraCaptureService : LifecycleService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }

    private var provider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var previewWindow: View? = null
    private var previewView: PreviewView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previewWanted = false
    private var wantsRecording = false
    private var audioCapable = false
    private var audioMuted = true
    private var cameraFront = false
    private var sessionActive = false
    private var cameraRecording = false
    private var foregroundStarted = false
    private var bindGeneration = 0
    private var recordingStartedAt = 0L
    private var lastOutputUri: Uri? = null

    private val timerTick = object : Runnable {
        override fun run() {
            if (!cameraRecording) return
            updateNotification()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    companion object {
        const val ACTION_SHOW_PREVIEW = "com.creep.screenrecorder.camera.SHOW_PREVIEW"
        const val ACTION_HIDE_PREVIEW = "com.creep.screenrecorder.camera.HIDE_PREVIEW"
        const val ACTION_START_RECORDING = "com.creep.screenrecorder.camera.START_RECORDING"
        const val ACTION_STOP = "com.creep.screenrecorder.camera.STOP"
        const val ACTION_SET_AUDIO_MUTED = "com.creep.screenrecorder.camera.SET_AUDIO_MUTED"
        const val ACTION_SET_FACING = "com.creep.screenrecorder.camera.SET_FACING"

        private const val CHANNEL_ID = "camera_capture"
        private const val NOTIFICATION_ID = 5201
        private const val PREVIEW_WIDTH_DP = 174
        private const val PREVIEW_HEIGHT_DP = 232

        @Volatile var isActive: Boolean = false
            private set
        @Volatile var isRecording: Boolean = false
            private set
        @Volatile var isPreviewVisible: Boolean = false
            private set
        @Volatile var recordingStartedAt: Long = 0L
            private set
        @Volatile var muted: Boolean = true
            private set
        @Volatile var hasAudioTrack: Boolean = false
            private set
        @Volatile var isStartingRecording: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_SHOW_PREVIEW -> showPreview(intent)
            ACTION_HIDE_PREVIEW -> hidePreview()
            ACTION_START_RECORDING -> beginRecording(intent)
            ACTION_STOP -> stopByUser()
            ACTION_SET_AUDIO_MUTED -> setAudioMuted(intent.getBooleanExtra(CaptureContract.EXTRA_AUDIO_MUTED, true))
            ACTION_SET_FACING -> changeFacing(intent.getBooleanExtra(CaptureContract.EXTRA_CAMERA_FRONT, false))
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun showPreview(intent: Intent) {
        if (!hasCameraPermission()) {
            fail("Camera permission is required to show the preview.")
            return
        }
        if (cameraRecording) {
            broadcastMessage("Stop the camera recording before changing the live preview.")
            return
        }
        cameraFront = intent.getBooleanExtra(CaptureContract.EXTRA_CAMERA_FRONT, cameraFront)
        previewWanted = true
        sessionActive = true
        isActive = true
        isPreviewVisible = true
        try {
            promoteToForeground(audioCapable = false)
            ensurePreviewWindow()
            broadcastState()
            bindUseCases()
        } catch (error: Exception) {
            fail("Could not start the camera preview. Check camera and overlay access.")
        }
    }

    private fun hidePreview() {
        previewWanted = false
        isPreviewVisible = false
        removePreviewWindow()
        previewUseCase?.setSurfaceProvider(null)
        previewUseCase = null
        if (cameraRecording) {
            broadcastState()
            return
        }
        finishCameraService("", success = true)
    }

    private fun beginRecording(intent: Intent) {
        if (cameraRecording || wantsRecording) return
        if (!hasCameraPermission()) {
            fail("Camera permission is required to record video.")
            return
        }
        cameraFront = intent.getBooleanExtra(CaptureContract.EXTRA_CAMERA_FRONT, cameraFront)
        previewWanted = intent.getBooleanExtra(CaptureContract.EXTRA_SHOW_CAMERA_OVERLAY, previewWanted)
        audioCapable = intent.getBooleanExtra(CaptureContract.EXTRA_AUDIO_CAPABLE, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        hasAudioTrack = audioCapable
        audioMuted = !audioCapable || intent.getBooleanExtra(CaptureContract.EXTRA_AUDIO_MUTED, true)
        muted = audioMuted
        wantsRecording = true
        isStartingRecording = true
        sessionActive = true
        isActive = true
        isPreviewVisible = previewWanted

        try {
            promoteToForeground(audioCapable)
            if (previewWanted) ensurePreviewWindow() else removePreviewWindow()
            broadcastState()
            bindUseCases()
        } catch (error: Exception) {
            fail("Could not start the camera recorder. Please try again.")
        }
    }

    private fun bindUseCases() {
        if (!sessionActive) return
        val generation = ++bindGeneration
        val currentProvider = provider
        if (currentProvider != null) {
            bindWithProvider(currentProvider, generation)
            return
        }
        val future = ProcessCameraProvider.getInstance(applicationContext)
        future.addListener({
            if (!sessionActive || generation != bindGeneration) return@addListener
            try {
                provider = future.get()
                bindWithProvider(provider!!, generation)
            } catch (error: Exception) {
                fail("Camera is unavailable or already in use by another app.")
            }
        }, mainExecutor)
    }

    private fun bindWithProvider(cameraProvider: ProcessCameraProvider, generation: Int) {
        if (!sessionActive || generation != bindGeneration) return
        if (cameraRecording) return
        try {
            cameraProvider.unbindAll()
            val selector = if (cameraFront) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
            if (!cameraProvider.hasCamera(selector)) {
                fail(if (cameraFront) "This device has no front camera." else "This device has no back camera.")
                return
            }

            val preview = if (previewWanted) {
                Preview.Builder().build().also { useCase ->
                    previewView?.let { useCase.setSurfaceProvider(it.surfaceProvider) }
                }
            } else null

            val capture = if (wantsRecording) {
                val quality = QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD),
                )
                VideoCapture.withOutput(Recorder.Builder().setQualitySelector(quality).build())
            } else null

            when {
                preview != null && capture != null -> cameraProvider.bindToLifecycle(
                    this, selector, preview, capture,
                )
                preview != null -> cameraProvider.bindToLifecycle(this, selector, preview)
                capture != null -> cameraProvider.bindToLifecycle(this, selector, capture)
                else -> error("No camera use case was requested.")
            }

            previewUseCase = preview
            videoCapture = capture
            if (wantsRecording) startCameraXRecording(capture!!)
            broadcastState()
        } catch (error: Exception) {
            fail("Camera could not be opened. Check that another app is not using it.")
        }
    }

    private fun startCameraXRecording(capture: VideoCapture<Recorder>) {
        if (recording != null) return
        val outputValues = MediaStoreRepository.cameraOutputValues(this, CaptureKind.CAMERA_RECORDING)
        val options = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(outputValues).build()

        try {
            var pending = capture.output.prepareRecording(this, options)
            if (audioCapable) {
                // CameraX 1.5 supports a muted start and Recording.mute() while recording.
                pending = pending.withAudioEnabled(audioMuted)
            }
            recording = pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        wantsRecording = false
                        isStartingRecording = false
                        cameraRecording = true
                        isRecording = true
                        recordingStartedAt = System.currentTimeMillis()
                        recording?.let { if (audioCapable) it.mute(audioMuted) }
                        acquireWakeLock()
                        broadcastState()
                        mainHandler.removeCallbacks(timerTick)
                        mainHandler.post(timerTick)
                        updateNotification()
                    }
                    is VideoRecordEvent.Finalize -> onRecordingFinalized(event)
                    else -> Unit
                }
            }
        } catch (error: SecurityException) {
            fail("Microphone access was not available. Allow it and try again.")
        } catch (error: Exception) {
            fail("Could not begin camera recording. Check free storage and try again.")
        }
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize) {
        mainHandler.removeCallbacks(timerTick)
        val uri = event.outputResults.outputUri
        val success = !event.hasError() && uri != Uri.EMPTY
        if (!success && uri != Uri.EMPTY) MediaStoreRepository.delete(this, uri)

        recording = null
        cameraRecording = false
        isRecording = false
        wantsRecording = false
        recordingStartedAt = 0L
        isStartingRecording = false
        audioCapable = false
        hasAudioTrack = false
        releaseWakeLock()
        lastOutputUri = if (success) uri else null

        val message = when {
            success -> "Camera video saved to Movies/CameraCapture."
            event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
                "Not enough storage to finish the camera video."
            else -> "Camera recording could not be finalized. Please try again."
        }
        broadcastState()
        broadcastFinished(success, if (success) uri else null, message)

        if (previewWanted && previewWindow != null) {
            sessionActive = true
            isActive = true
            isPreviewVisible = true
            updateNotification()
            broadcastState()
        } else {
            finishCameraService("", success)
        }
    }

    private fun setAudioMuted(mute: Boolean) {
        val activeRecording = recording
        if (activeRecording != null && !audioCapable && !mute) {
            audioMuted = true
            muted = true
            broadcastState()
            broadcastMessage("Microphone audio was not enabled for this recording. Stop and restart with Record Audio enabled.")
            return
        }
        audioMuted = mute
        muted = mute
        if (activeRecording != null && audioCapable) {
            if (cameraRecording) runCatching { activeRecording.mute(mute) }
            // If start is still pending, the Start event applies this latest state.
            broadcastState()
            updateNotification()
        } else {
            broadcastState()
        }
    }

    private fun changeFacing(front: Boolean) {
        if (cameraRecording || wantsRecording) {
            broadcastMessage("Stop the camera recording before changing cameras.")
            return
        }
        if (cameraFront == front) return
        cameraFront = front
        if (sessionActive) bindUseCases()
        broadcastState()
    }

    private fun stopByUser() {
        if (recording != null) {
            recording?.stop()
            return
        }
        if (wantsRecording) {
            wantsRecording = false
            finishCameraService("Camera recording was cancelled before it started.", success = false)
            return
        }
        previewWanted = false
        removePreviewWindow()
        finishCameraService("", success = true)
    }

    @Suppress("DEPRECATION")
    private fun ensurePreviewWindow() {
        if (!Settings.canDrawOverlays(this)) {
            throw SecurityException("Overlay permission is required for the camera preview.")
        }
        if (previewWindow != null) return

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 25, 29))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = dp(10).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(7), dp(7))
            setBackgroundColor(Color.rgb(30, 42, 46))
        }
        val label = TextView(this).apply {
            text = "LIVE CAMERA  ·  DRAG"
            textSize = 10f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.rgb(190, 245, 125))
            letterSpacing = 0.05f
        }
        header.addView(label, LinearLayout.LayoutParams(0, dp(32), 1f))
        val close = TextView(this).apply {
            text = "×"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            setOnClickListener {
                val hide = Intent(this@CameraCaptureService, CameraCaptureService::class.java)
                    .setAction(ACTION_HIDE_PREVIEW)
                startService(hide)
            }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(32), dp(32)))

        val cameraPreview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(header, LinearLayout.LayoutParams(dp(PREVIEW_WIDTH_DP), dp(46)))
        root.addView(cameraPreview, LinearLayout.LayoutParams(dp(PREVIEW_WIDTH_DP), dp(PREVIEW_HEIGHT_DP - 46)))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(PREVIEW_WIDTH_DP),
            dp(PREVIEW_HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (resources.displayMetrics.widthPixels - dp(PREVIEW_WIDTH_DP) - dp(16)).coerceAtLeast(0)
            y = dp(120)
        }
        header.setOnTouchListener(makeDragListener(windowManager, root, params, ::dp))
        windowManager.addView(root, params)
        previewWindow = root
        previewView = cameraPreview
    }

    private fun makeDragListener(
        windowManager: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
        dp: (Int) -> Int,
    ): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(4)) dragged = true
                    if (dragged) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(dp(24))
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        val maxX = (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)
                        params.x = if (params.x + view.width / 2 < resources.displayMetrics.widthPixels / 2) {
                            0
                        } else maxX
                        params.y = params.y.coerceIn(dp(24),
                            (resources.displayMetrics.heightPixels - view.height - dp(24)).coerceAtLeast(dp(24)))
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun removePreviewWindow() {
        val view = previewWindow ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        previewWindow = null
        previewView = null
        isPreviewVisible = false
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun promoteToForeground(audio: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (audio) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            // The camera foreground-service type is not available before Android 11.
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(): Notification {
        val title = if (cameraRecording) "Camera recording" else "Camera preview active"
        val body = if (cameraRecording) {
            "${elapsedText()} · Tap Stop to save the video."
        } else {
            "ScreenKit camera is in use. Tap Stop to close it."
        }
        val open = PendingIntent.getActivity(
            this, 501,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 502,
            Intent(this, CameraCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    private fun elapsedText(): String {
        val seconds = ((System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L) / 1_000L)
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun updateNotification() {
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Camera capture", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while ScreenKit camera preview or recording is active."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:camera-recording")
            .apply { acquire(8 * 60 * 60 * 1_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    private fun finishCameraService(message: String, success: Boolean) {
        bindGeneration++
        runCatching { provider?.unbindAll() }
        provider = null
        previewUseCase = null
        videoCapture = null
        recording = null
        wantsRecording = false
        isStartingRecording = false
        cameraRecording = false
        audioCapable = false
        hasAudioTrack = false
        sessionActive = false
        isActive = false
        isRecording = false
        isPreviewVisible = false
        recordingStartedAt = 0L
        muted = audioMuted
        mainHandler.removeCallbacks(timerTick)
        releaseWakeLock()
        removePreviewWindow()
        broadcastState()
        if (message.isNotBlank()) broadcastFinished(success, lastOutputUri, message)
        lastOutputUri = null
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun fail(message: String) {
        broadcastMessage(message)
        finishCameraService("", success = false)
    }

    private fun broadcastState() {
        sendBroadcast(
            Intent(CaptureContract.ACTION_CAMERA_STATE)
                .setPackage(packageName)
                .putExtra(CaptureContract.EXTRA_ACTIVE, sessionActive)
                .putExtra(CaptureContract.EXTRA_RECORDING, cameraRecording)
                .putExtra(CaptureContract.EXTRA_PREVIEW, previewWanted && previewWindow != null)
                .putExtra(CaptureContract.EXTRA_STARTED_AT, recordingStartedAt)
                .putExtra(CaptureContract.EXTRA_AUDIO_MUTED, audioMuted)
                .putExtra(CaptureContract.EXTRA_CAMERA_FRONT, cameraFront),
        )
    }

    private fun broadcastFinished(success: Boolean, uri: Uri?, message: String) {
        val update = Intent(CaptureContract.ACTION_CAMERA_FINISHED)
            .setPackage(packageName)
            .putExtra(CaptureContract.EXTRA_SUCCESS, success)
            .putExtra(CaptureContract.EXTRA_MESSAGE, message)
        uri?.let { update.putExtra(CaptureContract.EXTRA_URI, it.toString()) }
        sendBroadcast(update)
    }

    private fun broadcastMessage(message: String) {
        sendBroadcast(
            Intent(CaptureContract.ACTION_CAMERA_FINISHED)
                .setPackage(packageName)
                .putExtra(CaptureContract.EXTRA_SUCCESS, false)
                .putExtra(CaptureContract.EXTRA_MESSAGE, message),
        )
    }

    override fun onDestroy() {
        if (recording != null) runCatching { recording?.stop() }
        finishCameraService("", success = false)
        super.onDestroy()
    }
}
