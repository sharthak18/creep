package com.screenkit.screenrecorder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fullscreen interactive drawing overlay with brush tool, color selection,
 * stroke width, undo, eraser, and clear screen options.
 */
class BrushOverlay(
    private val context: Context,
    private val onClose: () -> Unit = {},
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: FrameLayout? = null

    // Drawing state
    private val strokePaths = mutableListOf<DrawnStroke>()
    private var currentColor = Color.parseColor("#FF4336") // Default Vibrant Red
    private var currentStrokeWidth = 14f
    private var isEraser = false

    private val colors = listOf(
        Color.parseColor("#FF4336"), // Red
        Color.parseColor("#4CAF50"), // Green
        Color.parseColor("#2196F3"), // Blue
        Color.parseColor("#FFEB3B"), // Yellow
        Color.parseColor("#FF9800"), // Orange
        Color.parseColor("#E91E63"), // Pink
        Color.WHITE,
    )

    data class DrawnStroke(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val isEraser: Boolean,
    )

    private inner class DrawingCanvasView(context: Context) : View(context) {
        private var currentPath: Path? = null
        private val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        private var bufferBitmap: Bitmap? = null
        private var bufferCanvas: Canvas? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) {
                bufferBitmap?.recycle()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bufferBitmap = bitmap
                bufferCanvas = Canvas(bitmap)
                redrawAllStrokes()
            }
        }

        fun redrawAllStrokes() {
            val canvas = bufferCanvas ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for (stroke in strokePaths) {
                paint.strokeWidth = stroke.strokeWidth
                if (stroke.isEraser) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else {
                    paint.xfermode = null
                    paint.color = stroke.color
                }
                canvas.drawPath(stroke.path, paint)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bufferBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            currentPath?.let { path ->
                paint.strokeWidth = currentStrokeWidth
                if (isEraser) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else {
                    paint.xfermode = null
                    paint.color = currentColor
                }
                canvas.drawPath(path, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val path = Path().apply { moveTo(x, y) }
                    currentPath = path
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentPath?.lineTo(x, y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentPath?.let { path ->
                        strokePaths.add(DrawnStroke(path, currentColor, currentStrokeWidth, isEraser))
                        currentPath = null
                        redrawAllStrokes()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        fun undo() {
            if (strokePaths.isNotEmpty()) {
                strokePaths.removeAt(strokePaths.size - 1)
                redrawAllStrokes()
            }
        }

        fun clear() {
            strokePaths.clear()
            redrawAllStrokes()
        }
    }

    private var canvasView: DrawingCanvasView? = null

    fun show() {
        if (rootLayout != null) return

        val root = FrameLayout(context)
        val drawingView = DrawingCanvasView(context)
        canvasView = drawingView
        root.addView(
            drawingView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Floating Toolbar
        val toolbar = buildToolbar()
        val toolbarLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            val margin = dp(14)
            setMargins(margin, 0, margin, dp(24))
        }
        root.addView(toolbar, toolbarLp)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

        runCatching {
            windowManager.addView(root, params)
            rootLayout = root
        }
    }

    fun dismiss() {
        val root = rootLayout ?: return
        runCatching { windowManager.removeView(root) }
        rootLayout = null
        canvasView = null
        onClose()
    }

    private fun buildToolbar(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE6161F23.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x33B7F36B.toInt())
            }
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            elevation = dp(8).toFloat()
        }

        // Color and tool selector row
        val toolsScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val toolsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Color swatches
        colors.forEach { col ->
            val swatch = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    setStroke(dp(2), if (col == currentColor && !isEraser) Color.WHITE else 0x44FFFFFF)
                }
                isClickable = true
                setOnClickListener {
                    isEraser = false
                    currentColor = col
                    updateToolbarSwatches(toolsRow)
                }
            }
            val lp = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                rightMargin = dp(8)
            }
            toolsRow.addView(swatch, lp)
        }

        // Eraser button
        val eraserBtn = makeButton("Eraser") {
            isEraser = true
            updateToolbarSwatches(toolsRow)
        }
        toolsRow.addView(eraserBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            rightMargin = dp(6)
        })

        // Undo button
        val undoBtn = makeButton("Undo") {
            canvasView?.undo()
        }
        toolsRow.addView(undoBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            rightMargin = dp(6)
        })

        // Clear button
        val clearBtn = makeButton("Clear") {
            canvasView?.clear()
        }
        toolsRow.addView(clearBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            rightMargin = dp(6)
        })

        // Close / Exit button
        val closeBtn = makeButton("✕ Done", isAccent = true) {
            dismiss()
        }
        toolsRow.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))

        toolsScrollView.addView(toolsRow)
        container.addView(toolsScrollView)

        return container
    }

    private fun updateToolbarSwatches(toolsRow: LinearLayout) {
        var colorIdx = 0
        for (i in 0 until toolsRow.childCount) {
            val child = toolsRow.getChildAt(i)
            if (colorIdx < colors.size && child.layoutParams.width == dp(28)) {
                val col = colors[colorIdx]
                (child.background as? GradientDrawable)?.apply {
                    setStroke(dp(2), if (col == currentColor && !isEraser) Color.WHITE else 0x44FFFFFF)
                }
                colorIdx++
            }
        }
    }

    private fun makeButton(text: String, isAccent: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(if (isAccent) 0xFF0D1612.toInt() else Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(if (isAccent) 0xFFB7F36B.toInt() else 0xFF2A373E.toInt())
                cornerRadius = dp(12).toFloat()
            }
            val padH = dp(12)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
