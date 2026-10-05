package com.screenkit.screenrecorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Fullscreen overlay that allows the user to drag and select a custom rectangle on screen
 * for partial screenshot or partial screen recording.
 */
class AreaSelectionOverlay(
    private val context: Context,
    private val title: String,
    private val onConfirmed: (RectF) -> Unit,
    private val onCancelled: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: FrameLayout? = null

    private var selectedRect: RectF? = null
    private var startPoint: PointF? = null

    fun show() {
        if (rootLayout != null) return

        val root = FrameLayout(context)
        val selectionCanvas = SelectionCanvasView(context)
        root.addView(
            selectionCanvas,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Control bar at the top
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(0xCC090D10.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val cancelButton = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = GradientDrawable().apply {
                setColor(0xFF2A383D.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { dismiss(); onCancelled() }
        }
        topBar.addView(cancelButton, LinearLayout.LayoutParams(dp(80), dp(36)))

        val confirmButton = Button(context).apply {
            text = "Confirm"
            setTextColor(0xFF10170D.toInt())
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(0xFFB7F36B.toInt())
                cornerRadius = dp(8).toFloat()
            }
            visibility = View.GONE
            setOnClickListener {
                val rect = selectedRect
                if (rect != null && rect.width() >= 16 && rect.height() >= 16) {
                    dismiss()
                    onConfirmed(rect)
                }
            }
        }
        val confirmLp = LinearLayout.LayoutParams(dp(84), dp(36)).apply {
            leftMargin = dp(8)
        }
        topBar.addView(confirmButton, confirmLp)

        val topBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            topMargin = dp(16)
        }
        root.addView(topBar, topBarParams)

        // Touch handling for drawing rectangle
        selectionCanvas.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startPoint = PointF(event.rawX, event.rawY)
                    selectedRect = null
                    confirmButton.visibility = View.GONE
                    selectionCanvas.invalidate()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val sp = startPoint ?: return@setOnTouchListener true
                    val left = min(sp.x, event.rawX)
                    val right = max(sp.x, event.rawX)
                    val top = min(sp.y, event.rawY)
                    val bottom = max(sp.y, event.rawY)
                    selectedRect = RectF(left, top, right, bottom)
                    selectionCanvas.invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val rect = selectedRect
                    if (rect != null && rect.width() >= 32 && rect.height() >= 32) {
                        confirmButton.visibility = View.VISIBLE
                    } else {
                        selectedRect = null
                        confirmButton.visibility = View.GONE
                    }
                    selectionCanvas.invalidate()
                    true
                }
                else -> false
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )

        rootLayout = root
        runCatching { windowManager.addView(root, params) }
    }

    fun dismiss() {
        val root = rootLayout ?: return
        runCatching { windowManager.removeView(root) }
        rootLayout = null
    }

    private inner class SelectionCanvasView(context: Context) : View(context) {
        init {
            // Need software layer for PorterDuff.Mode.CLEAR on canvas
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        private val dimPaint = Paint().apply {
            color = 0x88000000.toInt()
            style = Paint.Style.FILL
        }

        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        private val borderPaint = Paint().apply {
            color = 0xFFB7F36B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(dp(6).toFloat(), dp(4).toFloat()), 0f)
        }

        private val hintPaint = Paint().apply {
            color = 0xFFF1F6F2.toInt()
            textSize = dp(14).toFloat()
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Draw dim full screen
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            val rect = selectedRect
            if (rect != null && rect.width() > 0 && rect.height() > 0) {
                // Clear the selection cutout
                canvas.drawRect(rect, clearPaint)
                // Draw selection border
                canvas.drawRect(rect, borderPaint)
            } else {
                // Show hint text
                canvas.drawText("Drag finger across screen to select area", width / 2f, height / 2f, hintPaint)
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
