package com.creep.screenrecorder.services

import android.Manifest
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
import android.graphics.Color
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
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.creep.screenrecorder.CaptureContract
import com.creep.screenrecorder.MainActivity
import com.creep.screenrecorder.R
import com.creep.screenrecorder.data.CaptureAudioMode
import com.creep.screenrecorder.data.CaptureKind
import com.creep.screenrecorder.data.CaptureSink
import com.creep.screenrecorder.data.SettingsStore
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One user-approved capture session: a screenshot, or a screen recording.
 *
 * A session is a single MediaProjection consent, which Android 14+ requires per capture. The
 * projection is stopped as soon as the capture finishes so no capture keeps running in the
 * background, and every session has a visible notification plus Android's own screen-share chip.
 */
class ScreenCaptureService : Service() {
    private enum class Mode { SCREENSHOT, VIDEO }

    private val mainHandler by lazy { Handler(mainLooper) }
    private val screenshotClaimed = AtomicBoolean(false)
    private val pendingLock = Any()
    private var pendingBitmap: Bitmap? = null
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
    private var engine: ScreenRecorderEngine? = null
    private var sink: CaptureSink? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mode: Mode? = null
    @Volatile private var sessionActive = false
    @Volatile private var finishing = false
    private var recorderStarted = false
    private var callbackRegistered = false
    private var foregroundStarted = false
    private var audioMode = CaptureAudioMode.NONE
    private var audioNote: String? = null
    private var skippedInitialFrames = 0
    private var screenshotArmAtElapsed = 0L

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
        private const val SCREENSHOT_SETTLE_MS = 320L
        private const val SCREENSHOT_FALLBACK_MS = 700L

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
                } else {
                    stopSelf(startId)
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
        audioNote = null
        screenshotArmAtElapsed = SystemClock.elapsedRealtime()
        audioMode = resolveAudioMode(intent)

        isActive = true
        isRecording = false
        activeMode = if (mode == Mode.VIDEO) "screen" else "screenshot"
        recordingStartedAt = 0L

        try {
            promoteToForeground(mode == Mode.VIDEO && audioMode.requiresRecordPermission && canRecordAudio())
            broadcastState()
            startApprovedCapture(intent)
        } catch (error: Exception) {
            finishSession(false, null, describeStartupError(error), stopProjection = true)
        }
        return START_NOT_STICKY
    }

    private fun canRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun resolveAudioMode(intent: Intent): CaptureAudioMode {
        CaptureAudioMode.fromId(intent.getStringExtra(CaptureContract.EXTRA_AUDIO_MODE))?.let { return it }
        val legacyEnabled = intent.getBooleanExtra(CaptureContract.EXTRA_AUDIO_ENABLED, false)
        return if (legacyEnabled) CaptureAudioMode.MICROPHONE else SettingsStore.audioMode(this)
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

    // ------------------------------------------------------------------ recording

    private fun startVideoRecording() {
        val metrics = realDisplayMetrics()
        val size = videoSize(metrics.widthPixels, metrics.heightPixels)
        check(size.first >= 2 && size.second >= 2) { "Could not read the device screen dimensions." }

        val output = CaptureSink.create(this, CaptureKind.SCREEN_RECORDING, ".mp4")
            ?: throw IOException("Could not create the recording in the chosen location.")
        sink = output
        acquireWakeLock()

        val recordAudioGranted = canRecordAudio()
        val effective = when {
            !audioMode.requiresRecordPermission -> audioMode
            !recordAudioGranted -> {
                audioNote = "Microphone permission was missing, so this recording is silent."
                CaptureAudioMode.NONE
            }
            else -> audioMode
        }

        if (effective.usesDeviceAudio) {
            startEngineRecording(metrics, size, effective)
        } else {
            startMediaRecorderRecording(metrics, size, effective)
        }

        isRecording = true
        recordingStartedAt = System.currentTimeMillis()
        broadcastState()
        updateNotification(recording = true)
        mainHandler.removeCallbacks(notificationTicker)
        mainHandler.postDelayed(notificationTicker, 1_000L)
    }

    private fun startMediaRecorderRecording(
        metrics: DisplayMetrics,
        size: Pair<Int, Int>,
        effective: CaptureAudioMode,
    ) {
        val target = sink ?: throw IOException("Could not create the recording in the chosen location.")
        val descriptor = target.openDescriptorForWrite(this) ?: target.openTempDescriptor()
            ?: throw IOException("Could not open the recording file.")
        outputDescriptor = descriptor

        val mediaRecorder = createMediaRecorder()
        recorder = mediaRecorder
        if (effective.usesMicrophone) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (effective.usesMicrophone) {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioChannels(1)
            mediaRecorder.setAudioSamplingRate(48_000)
            mediaRecorder.setAudioEncodingBitRate(96_000)
        }
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setVideoSize(size.first, size.second)
        mediaRecorder.setVideoFrameRate(FRAME_RATE)
        mediaRecorder.setVideoEncodingBitRate(bitrateFor(size.first, size.second))
        mediaRecorder.setOutputFile(descriptor.fileDescriptor)
        mediaRecorder.setOnErrorListener { _, _, _ ->
            mainHandler.post {
                if (sessionActive && !finishing) {
                    finishSession(
                        keepVideo = true,
                        screenshot = null,
                        message = "The screen recorder hit an error. Any valid video was saved.",
                        stopProjection = true,
                    )
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
    }

    private fun startEngineRecording(
        metrics: DisplayMetrics,
        size: Pair<Int, Int>,
        effective: CaptureAudioMode,
    ) {
        val target = sink ?: throw IOException("Could not create the recording in the chosen location.")
        val descriptor = target.openDescriptorForWrite(this)
        if (descriptor != null) outputDescriptor = descriptor
        val file = if (descriptor == null) target.tempTarget?.also { it.parentFile?.mkdirs() } else null
        if (descriptor == null && file == null) throw IOException("Could not open the recording file.")

        val screenEngine = ScreenRecorderEngine(
            width = size.first,
            height = size.second,
            frameRate = FRAME_RATE,
            bitRate = bitrateFor(size.first, size.second),
            audioMode = effective,
            projection = projection ?: error("Android did not provide a screen capture session."),
            descriptor = descriptor,
            file = file,
            onAudioUnavailable = { note -> audioNote = note },
        )
        screenEngine.start()
        engine = screenEngine

        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenKit screen recording",
            size.first,
            size.second,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            screenEngine.surface,
            null,
            mainHandler,
        ) ?: throw IOException("Android could not create the recording display.")
    }

    private fun bitrateFor(width: Int, height: Int): Int =
        (width.toLong() * height * 3L).coerceIn(4_000_000L, 12_000_000L).toInt()

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(this)
    } else {
        MediaRecorder()
    }

    // ------------------------------------------------------------------ screenshot

    private fun startScreenshotCapture() {
        val metrics = realDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        require(width > 0 && height > 0) { "Could not read the device screen dimensions." }

        imageThread = HandlerThread("ScreenKitImageWriter").also { it.start() }
        imageHandler = Handler(imageThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener(::onImageAvailable, imageHandler!!)
        // Android is still dismissing its own consent sheet when the display appears, so wait a
        // moment before accepting a frame: that is what produced screenshots of the dialog.
        screenshotArmAtElapsed = SystemClock.elapsedRealtime() + SCREENSHOT_SETTLE_MS
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
        // A perfectly still screen may not push another frame after the settle delay, so fall back
        // to the newest frame that arrived while the delay was running.
        mainHandler.postDelayed({
            if (sessionActive && mode == Mode.SCREENSHOT && !finishing && !screenshotClaimed.get()) {
                captureStashedScreenshot()
            }
        }, SCREENSHOT_SETTLE_MS + SCREENSHOT_FALLBACK_MS)
        mainHandler.postDelayed({
            if (sessionActive && mode == Mode.SCREENSHOT && !finishing && !screenshotClaimed.get()) {
                finishSession(
                    keepVideo = false,
                    screenshot = null,
                    message = "No screen frame arrived. Please try the screenshot again.",
                    stopProjection = true,
                )
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
            if (SystemClock.elapsedRealtime() < screenshotArmAtElapsed) {
                stashPendingFrame(image)
                return
            }
            if (!screenshotClaimed.compareAndSet(false, true)) return

            bitmap = bitmapFromImage(image)
            val blank = isProtectedFrame(bitmap)
            val saved = saveScreenshot(bitmap)
            mainHandler.post {
                if (finishing) {
                    sink?.discard(this)
                } else {
                    finishSession(
                        keepVideo = false,
                        screenshot = saved,
                        message = if (blank) {
                            "Screenshot saved, but the image is blank: that screen blocks capture (secure or DRM window)."
                        } else {
                            "Screenshot saved to ${SettingsStore.describeSaveLocation(this@ScreenCaptureService)}."
                        },
                        stopProjection = true,
                    )
                }
            }
        } catch (_: Exception) {
            mainHandler.post {
                if (!finishing) {
                    finishSession(
                        keepVideo = false,
                        screenshot = null,
                        message = "The screenshot could not be saved. Check free storage and try again.",
                        stopProjection = true,
                    )
                }
            }
        } finally {
            image?.close()
            bitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    /** Keeps the newest frame captured during the settle delay, in case the screen then goes still. */
    private fun stashPendingFrame(image: Image) {
        val decoded = try {
            bitmapFromImage(image)
        } catch (_: Exception) {
            return
        }
        synchronized(pendingLock) {
            pendingBitmap?.takeUnless { it.isRecycled }?.recycle()
            pendingBitmap = decoded
        }
    }

    private fun captureStashedScreenshot() {
        val stashed = synchronized(pendingLock) {
            val current = pendingBitmap
            pendingBitmap = null
            current
        } ?: return
        if (!screenshotClaimed.compareAndSet(false, true)) {
            stashed.recycle()
            return
        }
        val worker = imageHandler
        if (worker == null) {
            stashed.recycle()
            return
        }
        worker.post {
            val blank = isProtectedFrame(stashed)
            val saved = try {
                saveScreenshot(stashed)
            } catch (_: Exception) {
                null
            } finally {
                stashed.recycle()
            }
            mainHandler.post {
                if (finishing) {
                    sink?.discard(this)
                } else {
                    finishSession(
                        keepVideo = false,
                        screenshot = saved,
                        message = when {
                            saved == null -> "The screenshot could not be saved. Check free storage and try again."
                            blank -> "Screenshot saved, but the image is blank: that screen blocks capture (secure or DRM window)."
                            else -> "Screenshot saved to ${SettingsStore.describeSaveLocation(this)}."
                        },
                        stopProjection = true,
                    )
                }
            }
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

    /** A fully opaque black frame means the source window is protected from capture. */
    private fun isProtectedFrame(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 12).coerceAtLeast(1)
        var sampled = 0
        var dark = 0
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                sampled++
                if (Color.alpha(pixel) == 255 &&
                    Color.red(pixel) == 0 && Color.green(pixel) == 0 && Color.blue(pixel) == 0
                ) {
                    dark++
                }
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && dark == sampled
    }

    private fun saveScreenshot(bitmap: Bitmap): Uri? {
        val output = CaptureSink.create(this, CaptureKind.SCREENSHOT, ".png")
            ?: throw IOException("Could not create the screenshot in the chosen location.")
        sink = output
        val stream: OutputStream = output.openStreamForWrite(this)
            ?: throw IOException("Could not open the screenshot output file.")
        var published: Uri? = null
        try {
            stream.use { target ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, target)) {
                    throw IOException("PNG compression failed.")
                }
                target.flush()
            }
            if (finishing) throw IOException("Capture stopped before the screenshot finished saving.")
            published = output.publish(this) ?: throw IOException("Could not finish the screenshot file.")
            return published
        } finally {
            if (published == null) output.discard(this)
        }
    }

    // ------------------------------------------------------------------ display helpers

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

    // ------------------------------------------------------------------ session teardown

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
            val screenEngine = engine
            if (screenEngine != null) {
                if (keepVideo) {
                    finalized = screenEngine.stop()
                } else {
                    screenEngine.abort()
                }
                engine = null
            } else if (recorderStarted && keepVideo) {
                finalized = runCatching {
                    recorder?.stop()
                    true
                }.getOrDefault(false)
            }
            recorderStarted = false
            // Disconnect the encoder surface before releasing its producer.
            virtualDisplay?.let { runCatching { it.release() } }
            virtualDisplay = null
            releaseRecorder()
            val output = sink
            resultUri = if (finalized) output?.publish(this) else null
            if (resultUri != null) {
                success = true
                if (finalMessage == "Recording saved.") {
                    finalMessage = "Recording saved to ${SettingsStore.describeSaveLocation(this)}."
                }
            } else {
                output?.discard(this)
                if (finalMessage == "Recording saved." || finalMessage.isBlank()) {
                    finalMessage = "The recording ended before a playable video could be saved."
                }
            }
        } else if (screenshot != null) {
            success = true
            resultUri = screenshot
        } else {
            sink?.discard(this)
        }
        sink = null
        audioNote?.let { note ->
            audioNote = null
            if (success) finalMessage = "$finalMessage $note"
        }

        mainHandler.removeCallbacks(notificationTicker)
        synchronized(pendingLock) {
            pendingBitmap?.takeUnless { it.isRecycled }?.recycle()
            pendingBitmap = null
        }
        releaseCaptureResources(stopProjection)
        releaseWakeLock()
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:screen-recording",
        ).apply { acquire(6 * 60 * 60 * 1_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    // ------------------------------------------------------------------ notification

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
            .setContentText(
                if (recording) "${elapsedText()} · Tap Stop to save the MP4." else "Saving a screenshot…",
            )
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
