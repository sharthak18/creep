package com.creep.screenrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.creep.screenrecorder.CaptureContract
import com.creep.screenrecorder.CaptureIntents
import com.creep.screenrecorder.CaptureRequestActivity
import com.creep.screenrecorder.MainActivity
import com.creep.screenrecorder.R
import com.creep.screenrecorder.data.CaptureAudioMode
import com.creep.screenrecorder.data.SettingsStore
import kotlin.math.abs

/**
 * User-enabled, draggable bubble with explicit capture shortcuts.
 *
 * Actions that need no permission and no Android consent sheet (stop a recording, switch camera,
 * hide the bubble) run right here. Everything else is handed to [CaptureRequestActivity], which
 * shows Android's prompt over the current app and disappears. Neither path brings the ScreenKit
 * activity forward, so a screenshot or a recording started from the bubble captures the app the
 * user was actually looking at.
 */
class FloatingOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayRoot: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var screenRecording = false
    private var cameraRecording = false
    private var cameraPreview = false
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                CaptureContract.ACTION_SCREEN_STATE -> {
                    screenRecording = intent.getBooleanExtra(CaptureContract.EXTRA_RECORDING, false)
                    rebuildOverlay()
                }
                CaptureContract.ACTION_CAMERA_STATE -> {
                    cameraRecording = intent.getBooleanExtra(CaptureContract.EXTRA_RECORDING, false)
                    cameraPreview = intent.getBooleanExtra(CaptureContract.EXTRA_PREVIEW, false)
                    rebuildOverlay()
                }
            }
        }
    }

    companion object {
        const val ACTION_SHOW = "com.creep.screenrecorder.overlay.SHOW"
        const val ACTION_HIDE = "com.creep.screenrecorder.overlay.HIDE"
        const val ACTION_UPDATE = "com.creep.screenrecorder.overlay.UPDATE"
        private const val CHANNEL_ID = "floating_controls"
        private const val NOTIFICATION_ID = 5301
        private const val BUBBLE_DP = 54
        private const val CELL_WIDTH_DP = 46
        private const val CELL_HEIGHT_DP = 48
        private const val COLUMNS = 4

        @Volatile var isRunning: Boolean = false
            private set

        fun show(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun hide(context: Context) {
            context.startService(Intent(context, FloatingOverlayService::class.java).setAction(ACTION_HIDE))
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(CaptureContract.ACTION_SCREEN_STATE)
            addAction(CaptureContract.ACTION_CAMERA_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeOverlay()
                isRunning = false
                SettingsStore.setOverlayEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_SHOW, ACTION_UPDATE, null -> {
                if (!Settings.canDrawOverlays(this)) {
                    isRunning = false
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                promoteToForeground()
                isRunning = true
                if (overlayRoot == null) {
                    screenRecording = ScreenCaptureService.isRecording
                    cameraRecording = CameraCaptureService.isRecording
                    cameraPreview = CameraCaptureService.isPreviewVisible
                    addOverlay()
                } else {
                    rebuildOverlay()
                }
            }
            else -> Unit
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun addOverlay() {
        val manager = windowManager ?: return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(14)
            y = dp(260)
        }
        overlayParams = params
        val root = LinearLayout(this)
        overlayRoot = root
        rebuildOverlay()
        try {
            manager.addView(root, params)
        } catch (_: SecurityException) {
            stopAfterOverlayFailure()
        } catch (_: WindowManager.BadTokenException) {
            stopAfterOverlayFailure()
        }
    }

    private fun stopAfterOverlayFailure() {
        overlayRoot = null
        overlayParams = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun rebuildOverlay() {
        val root = overlayRoot ?: return
        val params = overlayParams ?: return
        val manager = windowManager ?: return
        val beforeWidth = root.width
        root.removeAllViews()
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(dp(6), dp(6), dp(6), dp(6))
        val background = GradientDrawable().apply {
            setColor(0xF01A2328.toInt())
            cornerRadius = dp(23).toFloat()
            setStroke(dp(1), 0xAA50615C.toInt())
        }
        root.background = background
        root.elevation = dp(12).toFloat()

        val bubble = makeBubble()
        val menu = if (expanded) makeMenu() else null
        val placeMenuBeforeBubble = expanded && params.x > resources.displayMetrics.widthPixels / 2
        if (placeMenuBeforeBubble && menu != null) {
            root.addView(menu, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(CELL_HEIGHT_DP * 2),
            ))
            root.addView(bubble, LinearLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP)).apply {
                leftMargin = dp(4)
            })
        } else {
            root.addView(bubble, LinearLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP)))
            if (menu != null) {
                root.addView(menu, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(CELL_HEIGHT_DP * 2),
                ).apply { leftMargin = dp(4) })
            }
        }
        bubble.setOnTouchListener(makeBubbleTouchListener(root, params))

        if (beforeWidth > 0 || expanded) {
            val estimatedWidth = if (expanded) {
                dp(BUBBLE_DP + 4 + CELL_WIDTH_DP * COLUMNS + 12)
            } else {
                dp(BUBBLE_DP + 12)
            }
            val maxX = (resources.displayMetrics.widthPixels - estimatedWidth).coerceAtLeast(0)
            params.x = params.x.coerceIn(0, maxX)
        }
        runCatching { manager.updateViewLayout(root, params) }
    }

    private fun makeBubble(): FrameLayout {
        val bubble = FrameLayout(this)
        val recordingNow = screenRecording || cameraRecording
        val fill = if (recordingNow) 0xFFE54655.toInt() else 0xFFB7F36B.toInt()
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2), if (recordingNow) 0xFFFFD3D7.toInt() else 0xFFE1FFC0.toInt())
        }
        val mark = TextView(this).apply {
            text = if (recordingNow) "■" else "S"
            textSize = if (recordingNow) 18f else 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (recordingNow) Color.WHITE else 0xFF13200D.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        bubble.addView(mark, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (recordingNow) Color.WHITE else 0xFF3D7550.toInt())
                setStroke(dp(1), if (recordingNow) 0xFFE54655.toInt() else 0xFFB7F36B.toInt())
            }
        }
        bubble.addView(dot, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(3)
            rightMargin = dp(3)
        })
        bubble.contentDescription = if (recordingNow) {
            "ScreenKit capture active. Tap for controls."
        } else {
            "ScreenKit floating controls"
        }
        return bubble
    }

    /** Two rows of four shortcuts so every action fits on a phone without covering the screen. */
    private fun makeMenu(): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val soundActive = SettingsStore.audioMode(this) != CaptureAudioMode.NONE
        val rows = listOf(
            listOf(
                MenuAction("▧", "Shot", CaptureContract.COMMAND_SCREENSHOT, false),
                MenuAction(
                    if (screenRecording) "■" else "●",
                    if (screenRecording) "Stop" else "Screen",
                    CaptureContract.COMMAND_TOGGLE_SCREEN_RECORDING,
                    screenRecording,
                ),
                MenuAction(
                    if (cameraRecording) "■" else "◉",
                    if (cameraRecording) "Stop" else "Camera",
                    CaptureContract.COMMAND_TOGGLE_CAMERA_RECORDING,
                    cameraRecording,
                ),
                MenuAction(
                    if (cameraPreview) "◉" else "◎",
                    "Lens",
                    CaptureContract.COMMAND_TOGGLE_CAMERA_OVERLAY,
                    cameraPreview,
                ),
            ),
            listOf(
                MenuAction("⇄", "Flip", CaptureContract.COMMAND_FLIP_CAMERA, false),
                MenuAction(soundIcon(), "Sound", CaptureContract.COMMAND_CYCLE_AUDIO, soundActive),
                MenuAction("⚙", "App", CaptureContract.COMMAND_OPEN_APP, false),
                MenuAction("✕", "Hide", CaptureContract.COMMAND_HIDE_OVERLAY, false),
            ),
        )
        rows.forEach { actions ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.forEach { action ->
                row.addView(makeMenuButton(action), LinearLayout.LayoutParams(dp(CELL_WIDTH_DP), dp(CELL_HEIGHT_DP)))
            }
            column.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(CELL_HEIGHT_DP),
            ))
        }
        return column
    }

    private fun soundIcon(): String = when (SettingsStore.audioMode(this)) {
        CaptureAudioMode.NONE -> "×"
        CaptureAudioMode.MICROPHONE -> "♩"
        CaptureAudioMode.DEVICE -> "♪"
        CaptureAudioMode.MIXED -> "♫"
    }

    private fun makeMenuButton(action: MenuAction): LinearLayout {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = selectableBackground()
            setOnClickListener { dispatch(action.command) }
        }
        val tint = if (action.active) 0xFFB7F36B.toInt() else Color.WHITE
        val icon = TextView(this).apply {
            text = action.icon
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(tint)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        button.addView(icon, LinearLayout.LayoutParams(dp(CELL_WIDTH_DP - 4), dp(25)))
        val label = TextView(this).apply {
            text = action.label
            textSize = 8.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (action.active) 0xFFB7F36B.toInt() else 0xFFC3D0CA.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        button.addView(label, LinearLayout.LayoutParams(dp(CELL_WIDTH_DP - 4), dp(14)))
        return button
    }

    /**
     * Runs what the tap asked for. Only actions that need a permission prompt or Android's
     * screen-share consent go through [CaptureRequestActivity]; the rest stay in this service so
     * the user stays exactly where they were.
     */
    private fun dispatch(command: String) {
        expanded = false
        rebuildOverlay()
        when (command) {
            CaptureContract.COMMAND_OPEN_APP -> {
                openMain()
                return
            }
            CaptureContract.COMMAND_HIDE_OVERLAY -> {
                hideOverlay()
                return
            }
            CaptureContract.COMMAND_FLIP_CAMERA -> {
                flipCamera()
                return
            }
            CaptureContract.COMMAND_TOGGLE_SCREEN_RECORDING -> if (ScreenCaptureService.isRecording) {
                CaptureIntents.stopScreenCapture(this)
                CaptureIntents.toast(this, "Saving the screen recording…")
                return
            }
            CaptureContract.COMMAND_TOGGLE_CAMERA_RECORDING -> if (CameraCaptureService.isRecording) {
                CaptureIntents.stopCameraCapture(this)
                return
            }
            CaptureContract.COMMAND_TOGGLE_CAMERA_OVERLAY -> if (CameraCaptureService.isPreviewVisible) {
                CaptureIntents.hideCameraPreview(this)
                return
            }
        }
        launchRequest(command)
    }

    private fun launchRequest(command: String) {
        val intent = Intent(this, CaptureRequestActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            .putExtra(CaptureContract.EXTRA_OVERLAY_COMMAND, command)
        runCatching { startActivity(intent) }.onFailure {
            CaptureIntents.toast(this, "ScreenKit could not start that action.")
        }
    }

    private fun flipCamera() {
        val front = !SettingsStore.cameraFront(this)
        SettingsStore.setCameraFront(this, front)
        val label = if (front) "Front camera" else "Back camera"
        when {
            CameraCaptureService.isRecording ->
                CaptureIntents.toast(this, "Stop the camera recording before switching cameras.")
            CameraCaptureService.isActive || CameraCaptureService.isPreviewVisible -> {
                CaptureIntents.setCameraFacing(this, front)
                CaptureIntents.toast(this, label)
            }
            else -> CaptureIntents.toast(this, "$label selected for the next capture")
        }
        refreshServiceState()
    }

    private fun refreshServiceState() {
        screenRecording = ScreenCaptureService.isRecording
        cameraRecording = CameraCaptureService.isRecording
        cameraPreview = CameraCaptureService.isPreviewVisible
        rebuildOverlay()
    }

    private fun hideOverlay() {
        SettingsStore.setOverlayEnabled(this, false)
        removeOverlay()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        runCatching { startActivity(intent) }
    }

    private fun makeBubbleTouchListener(
        root: View,
        params: WindowManager.LayoutParams,
    ): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        val maxX = (resources.displayMetrics.widthPixels - root.width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - root.height).coerceAtLeast(dp(28))
                        params.x = (startX + dx).coerceIn(0, maxX)
                        params.y = (startY + dy).coerceIn(dp(28), maxY)
                        runCatching { windowManager?.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val maxX = (screenWidth - root.width).coerceAtLeast(0)
                        params.x = if (params.x + root.width / 2 < screenWidth / 2) 0 else maxX
                        runCatching { windowManager?.updateViewLayout(root, params) }
                    } else {
                        expanded = !expanded
                        rebuildOverlay()
                    }
                    true
                }
                else -> true
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val root = overlayRoot ?: return
        val params = overlayParams ?: return
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        params.x = params.x.coerceIn(0, (width - root.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(dp(28), (height - root.height - dp(28)).coerceAtLeast(dp(28)))
        runCatching { windowManager?.updateViewLayout(root, params) }
        rebuildOverlay()
    }

    private fun promoteToForeground() {
        val open = PendingIntent.getActivity(
            this, 601,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hide = PendingIntent.getService(
            this, 602,
            Intent(this, FloatingOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle("ScreenKit floating controls are on")
            .setContentText("Tap the bubble to capture without leaving this app.")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide", hide)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Floating capture controls", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when the user-enabled ScreenKit bubble is active."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun removeOverlay() {
        overlayRoot?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayRoot = null
        overlayParams = null
    }

    private fun selectableBackground(): android.graphics.drawable.Drawable {
        val drawable = GradientDrawable().apply {
            setColor(0x001A2328)
            cornerRadius = dp(9).toFloat()
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x335B6B62), drawable, null,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        removeOverlay()
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class MenuAction(val icon: String, val label: String, val command: String, val active: Boolean)
}
