package com.creep.screenrecorder.services

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.creep.screenrecorder.CaptureContract
import com.creep.screenrecorder.MainActivity
import com.creep.screenrecorder.R
import com.creep.screenrecorder.data.CaptureKind
import com.creep.screenrecorder.data.MediaStoreRepository
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A fresh user-approved projection token is consumed for exactly one screenshot or video. */
class ScreenCaptureService : Service() {
    private enum class Mode { SCREENSHOT, VIDEO }

    private val mainHandler by lazy { Handler(mainLooper) }
    private val screenshotClaimed = AtomicBoolean(false)
    private val notificationTicker = object : Runnable {
        override fun run() {
            if (!isRecording) return
            updateNotification(recording = true)
            mainHandler.postDelayed(this, 1_000L)
        }
    }
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var mode: Mode? = null
    @Volatile private var sessionActive = false
    @Volatile private var finishing = false
    private var recorderStarted = false
    private var callbackRegistered = false
    private var foregroundStarted = false
    private var microphoneEnabled = false
    private var audioFallback = false
    private var skippedInitialFrames = 0
    private var startedAtElapsed = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!sessionActive || finishing) return
            if (mode == Mode.SCREENSHOT && screenshotClaimed.get()) {
                // A frame has already been handed to the image worker; let its save complete.
                return
            }
            finishSession(
                keepVideo = mode == Mode.VIDEO,
                screenshot = null,
                message = if (mode == Mode.VIDEO) {
                    "Android stopped screen sharing. Any valid recording was saved."
                } else {
                    "Screen sharing ended before a screenshot frame arrived."
                },
                stopProjection = false,
            )
        }
    }

    companion object {
        const val ACTION_SCREENSHOT = "com.creep.screenrecorder.screen.SCREENSHOT"
        const val ACTION_START_RECORDING = "com.creep.screenrecorder.screen.START_RECORDING"
        const val ACTION_STOP = "com.creep.screenrecorder.screen.STOP"

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 5101
        private const val MAX_VIDEO_EDGE = 1920
        private const val FRAME_RATE = 30

        @Volatile var isActive: Boolean = false
            private set
        @Volatile var isRecording: Boolean = false
            private set
        @Volatile var activeMode: String = ""
            private set
        @Volatile var recordingStartedAt: Long = 0L
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                if (sessionActive) {
                    finishSession(true, null, "Recording saved.", stopProjection = true)
                }
                return START_NOT_STICKY
            }
            ACTION_SCREENSHOT, ACTION_START_RECORDING -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (sessionActive) return START_NOT_STICKY

        mode = if (intent.action == ACTION_SCREENSHOT) Mode.SCREENSHOT else Mode.VIDEO
        sessionActive = true
        finishing = false
        recorderStarted = false
        screenshotClaimed.set(false)
        skippedInitialFrames = 0
        outputUri = null
        startedAtElapsed = SystemClock.elapsedRealtime()
        val wantsAudio = mode == Mode.VIDEO && intent.getBooleanExtra(CaptureContract.EXTRA_AUDIO_ENABLED, false)
        microphoneEnabled = wantsAudio &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        audioFallback = wantsAudio && !microphoneEnabled

        isActive = true
        isRecording = false
        activeMode = if (mode == Mode.VIDEO) "screen" else "screenshot"
        recordingStartedAt = 0L

        try {
            promoteToForeground(microphoneEnabled)
            broadcastState()
            startApprovedCapture(intent)
        } catch (error: Exception) {
            finishSession(false, null, describeStartupError(error), stopProjection = true)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readResultIntent(source: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        source.getParcelableExtra(CaptureContract.EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        source.getParcelableExtra(CaptureContract.EXTRA_RESULT_DATA)
    }

    private fun startApprovedCapture(startIntent: Intent) {
        val resultCode = startIntent.getIntExtra(CaptureContract.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = readResultIntent(startIntent)
        check(resultCode == Activity.RESULT_OK && resultData != null) {
            "Screen capture approval was not supplied."
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: error("Screen capture is unavailable on this device.")
        projection = manager.getMediaProjection(resultCode, resultData!!)
            ?: error("Android did not provide a screen capture session.")
        projection?.registerCallback(projectionCallback, mainHandler)
        callbackRegistered = true

        if (mode == Mode.VIDEO) startVideoRecording() else startScreenshotCapture()
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(this)
    } else {
        MediaRecorder()
    }

    private fun startVideoRecording() {
        val metrics = realDisplayMetrics()
        val size = videoSize(metrics.widthPixels, metrics.heightPixels)
        check(size.first >= 2 && size.second >= 2) { "Could not read the device screen dimensions." }

        outputUri = MediaStoreRepository.createPending(this, CaptureKind.SCREEN_RECORDING, ".mp4")
            ?: throw IOException("Could not create the recording in MediaStore.")
        outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
            ?: throw IOException("Could not open the MP4 output file.")

        val mediaRecorder = createMediaRecorder()
        recorder = mediaRecorder
        if (microphoneEnabled) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (microphoneEnabled) {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioChannels(1)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setAudioEncodingBitRate(128_000)
        }
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setVideoSize(size.first, size.second)
        mediaRecorder.setVideoFrameRate(FRAME_RATE)
        val bitrate = (size.first.toLong() * size.second * 3L)
            .coerceIn(4_000_000L, 12_000_000L).toInt()
        mediaRecorder.setVideoEncodingBitRate(bitrate)
        mediaRecorder.setOutputFile(outputDescriptor!!.fileDescriptor)
        mediaRecorder.setOnErrorListener { _, _, _ ->
            mainHandler.post {
                if (sessionActive && !finishing) {
                    finishSession(true, null,
                        "The screen recorder encountered an error. Any valid video was saved.", true)
                }
            }
        }
        mediaRecorder.prepare()

        recorderSurface = mediaRecorder.surface
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenKit screen recording",
            size.first,
            size.second,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorderSurface!!,
            null,
            mainHandler,
        ) ?: throw IOException("Android could not create the recording display.")

        mediaRecorder.start()
        recorderStarted = true
        isRecording = true
        recordingStartedAt = System.currentTimeMillis()
        broadcastState()
        updateNotification(recording = true)
        mainHandler.removeCallbacks(notificationTicker)
        mainHandler.postDelayed(notificationTicker, 1_000L)
    }

    private fun startScreenshotCapture() {
        val metrics = realDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        require(width > 0 && height > 0) { "Could not read the device screen dimensions." }

        imageThread = HandlerThread("ScreenKitImageWriter").also { it.start() }
        imageHandler = Handler(imageThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener(::onImageAvailable, imageHandler!!)
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenKit screenshot",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            mainHandler,
        ) ?: throw IOException("Android could not create a screenshot display.")

        broadcastState()
        updateNotification(recording = false)
        mainHandler.postDelayed({
            if (sessionActive && mode == Mode.SCREENSHOT && !finishing && !screenshotClaimed.get()) {
                finishSession(false, null,
                    "No screen frame arrived. Please try the screenshot again.", true)
            }
        }, 20_000L)
    }

    private fun onImageAvailable(reader: ImageReader) {
        var image: Image? = null
        var bitmap: Bitmap? = null
        try {
            image = reader.acquireLatestImage() ?: return
            if (!sessionActive || finishing || screenshotClaimed.get()) return
            // The first buffer can be empty on some devices; use the next fresh display frame.
            if (skippedInitialFrames++ == 0) return
            if (!screenshotClaimed.compareAndSet(false, true)) return

            bitmap = bitmapFromImage(image)
            val saved = saveScreenshot(bitmap)
            mainHandler.post {
                if (finishing) {
                    MediaStoreRepository.delete(this, saved)
                } else {
                    finishSession(false, saved, "Screenshot saved to Pictures/ScreenCapture.", true)
                }
            }
        } catch (_: Exception) {
            mainHandler.post {
                if (!finishing) {
                    finishSession(false, null,
                        "The screenshot could not be saved. Check free storage and try again.", true)
                }
            }
        } finally {
            image?.close()
            bitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    private fun bitmapFromImage(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: error("Screenshot frame was empty.")
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(width > 0 && height > 0 && pixelStride > 0 && rowStride > 0) {
            "Screenshot frame dimensions were invalid."
        }
        val paddedWidth = maxOf(width, rowStride / pixelStride)
        val buffer: ByteBuffer = plane.buffer.apply { rewind() }
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            padded.copyPixelsFromBuffer(buffer)
            if (paddedWidth == width) return padded
            return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        } catch (error: RuntimeException) {
            padded.recycle()
            throw error
        }
    }

    private fun saveScreenshot(bitmap: Bitmap): Uri {
        val uri = MediaStoreRepository.createPending(this, CaptureKind.SCREENSHOT, ".png")
            ?: throw IOException("Could not create a screenshot file.")
        var published = false
        try {
            contentResolver.openOutputStream(uri, "w")?.use { stream: OutputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("PNG compression failed.")
                }
                stream.flush()
            } ?: throw IOException("Could not open the screenshot output file.")
            if (finishing) throw IOException("Capture stopped before the screenshot finished saving.")
            if (!MediaStoreRepository.publish(this, uri)) throw IOException("Could not publish the PNG.")
            published = true
            return uri
        } finally {
            if (!published) MediaStoreRepository.delete(this, uri)
        }
    }

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(): DisplayMetrics {
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        return DisplayMetrics().also { metrics ->
            display.getRealMetrics(metrics)
            if (metrics.densityDpi <= 0) {
                metrics.densityDpi = (resources.displayMetrics.density * 160f).toInt()
            }
        }
    }

    private fun videoSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 0 to 0
        val scale = minOf(1f, MAX_VIDEO_EDGE.toFloat() / maxOf(sourceWidth, sourceHeight))
        val width = ((sourceWidth * scale).toInt() and -2).coerceAtLeast(2)
        val height = ((sourceHeight * scale).toInt() and -2).coerceAtLeast(2)
        return width to height
    }

    private fun finishSession(
        keepVideo: Boolean,
        screenshot: Uri?,
        message: String,
        stopProjection: Boolean,
    ) {
        if (finishing) return
        finishing = true
        var success = false
        var resultUri: Uri? = null
        var finalMessage = message

        if (mode == Mode.VIDEO) {
            var finalized = false
            if (recorderStarted && keepVideo) {
                try {
                    recorder?.stop()
                    finalized = true
                } catch (_: RuntimeException) {
                    // MediaRecorder throws for clips too short to form a playable MP4.
                }
            }
            recorderStarted = false
            // Disconnect the encoder surface before releasing MediaRecorder's Surface handle.
            virtualDisplay?.let { runCatching { it.release() } }
            virtualDisplay = null
            releaseRecorder()
            val uri = outputUri
            if (finalized && uri != null && MediaStoreRepository.publish(this, uri)) {
                success = true
                resultUri = uri
                if (finalMessage == "Recording saved.") {
                    finalMessage = "Recording saved to Movies/ScreenCapture."
                }
                if (audioFallback) {
                    finalMessage += " No microphone permission was available, so it was recorded silently."
                }
            } else {
                MediaStoreRepository.delete(this, uri)
                if (finalMessage == "Recording saved." || finalMessage.isBlank()) {
                    finalMessage = "The recording ended before a playable video could be saved."
                }
            }
        } else if (screenshot != null) {
            success = true
            resultUri = screenshot
            if (outputUri != null && outputUri != screenshot) {
                MediaStoreRepository.delete(this, outputUri)
            }
        } else {
            MediaStoreRepository.delete(this, outputUri)
        }

        mainHandler.removeCallbacks(notificationTicker)
        releaseCaptureResources(stopProjection)
        sessionActive = false
        isActive = false
        isRecording = false
        activeMode = ""
        recordingStartedAt = 0L
        broadcastState()
        broadcastFinished(success, resultUri, finalMessage)

        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun releaseRecorder() {
        recorder?.let { mediaRecorder ->
            runCatching { mediaRecorder.reset() }
            runCatching { mediaRecorder.release() }
        }
        recorder = null
        recorderSurface?.let { runCatching { it.release() } }
        recorderSurface = null
        runCatching { outputDescriptor?.close() }
        outputDescriptor = null
    }

    private fun releaseCaptureResources(stopProjection: Boolean) {
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
        imageReader?.let { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
        imageReader = null
        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null

        projection?.let { current ->
            if (callbackRegistered) runCatching { current.unregisterCallback(projectionCallback) }
            callbackRegistered = false
            if (stopProjection) runCatching { current.stop() }
        }
        projection = null
    }

    private fun promoteToForeground(withMicrophone: Boolean) {
        val notification = buildNotification(recording = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(recording: Boolean): Notification {
        val openApp = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            this, 401, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(if (recording) "Screen recording" else "Screen capture")
            .setContentText(if (recording) "${elapsedText()} · Tap Stop to save the MP4." else "Saving a screenshot…")
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (recording) {
            val stop = PendingIntent.getService(
                this,
                402,
                Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", stop)
        }
        return builder.build()
    }

    private fun elapsedText(): String {
        val elapsedSeconds = ((System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L) / 1_000L)
        return String.format(Locale.getDefault(), "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
    }

    private fun updateNotification(recording: Boolean) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            buildNotification(recording),
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent controls while ScreenKit captures the display."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun broadcastState() {
        sendBroadcast(
            Intent(CaptureContract.ACTION_SCREEN_STATE)
                .setPackage(packageName)
                .putExtra(CaptureContract.EXTRA_ACTIVE, sessionActive)
                .putExtra(CaptureContract.EXTRA_RECORDING, isRecording)
                .putExtra(CaptureContract.EXTRA_MODE, activeMode)
                .putExtra(CaptureContract.EXTRA_STARTED_AT, recordingStartedAt),
        )
    }

    private fun broadcastFinished(success: Boolean, uri: Uri?, message: String) {
        val update = Intent(CaptureContract.ACTION_SCREEN_FINISHED)
            .setPackage(packageName)
            .putExtra(CaptureContract.EXTRA_SUCCESS, success)
            .putExtra(CaptureContract.EXTRA_MESSAGE, message)
        uri?.let { update.putExtra(CaptureContract.EXTRA_URI, it.toString()) }
        sendBroadcast(update)
    }

    private fun describeStartupError(error: Exception): String = when (error) {
        is SecurityException -> "Screen capture was not approved. Accept Android's prompt and try again."
        is IOException -> "Could not create the capture file. Check available storage and try again."
        else -> "Screen capture could not start on this device. Please try again."
    }

    override fun onDestroy() {
        if (sessionActive && !finishing) {
            finishSession(true, null, "Capture service stopped.", stopProjection = true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
