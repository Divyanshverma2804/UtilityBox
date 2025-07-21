// DrawingOverlayService.kt - Fixed Coordinate Issues and Better Visual Feedback
package com.example.utilitybox

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Toast

class DrawingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var drawingView: DrawingView
    private var mode = "screenshot" // or "ocr"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = intent?.getStringExtra("mode") ?: "screenshot"
        Log.d("DRAWING_SERVICE", "Starting in mode: $mode")

        drawingView = DrawingView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(drawingView, params)
        } catch (e: Exception) {
            Log.e("DRAWING_SERVICE", "Failed to add drawing view", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    inner class DrawingView(private val service: DrawingOverlayService) : View(service) {

        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f
        private var isDrawing = false
        private var hasSelection = false

        // Screen dimensions
        private val screenWidth: Int
        private val screenHeight: Int

        init {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val realSize = Point()
            wm.defaultDisplay.getRealSize(realSize)
            screenWidth = realSize.x
            screenHeight = realSize.y

            Log.d("DRAWING_DEBUG", "Screen dimensions: ${screenWidth}x${screenHeight}")
        }

        private val selectionPaint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 6f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
            alpha = 255
        }

        private val backgroundPaint = Paint().apply {
            color = Color.BLACK
            alpha = 120 // Semi-transparent overlay
        }

        private val cornerPaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 8f
            style = Paint.Style.STROKE
        }

        private val debugPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        private val instructionPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            if (canvas != null) {
                super.onDraw(canvas)
            }
            canvas.let { c ->
                // Draw semi-transparent background
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

                // Show instructions if no selection
                if (!hasSelection && !isDrawing) {
                    val instruction = if (mode == "ocr") "Draw rectangle around text to extract" else "Draw rectangle to capture screenshot"
                    c.drawText(instruction, width / 2f, 100f, instructionPaint)
                }

                if (isDrawing || hasSelection) {
                    val left = minOf(startX, endX)
                    val top = minOf(startY, endY)
                    val right = maxOf(startX, endX)
                    val bottom = maxOf(startY, endY)

                    // Clear the selected area to show what will be captured
                    val clearPaint = Paint().apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    }
                    c.drawRect(left, top, right, bottom, clearPaint)

                    // Draw selection border
                    c.drawRect(left, top, right, bottom, selectionPaint)

                    // Draw corner indicators
                    val cornerSize = 40f

                    // Top-left
                    c.drawLine(left, top, left + cornerSize, top, cornerPaint)
                    c.drawLine(left, top, left, top + cornerSize, cornerPaint)

                    // Top-right
                    c.drawLine(right - cornerSize, top, right, top, cornerPaint)
                    c.drawLine(right, top, right, top + cornerSize, cornerPaint)

                    // Bottom-left
                    c.drawLine(left, bottom - cornerSize, left, bottom, cornerPaint)
                    c.drawLine(left, bottom, left + cornerSize, bottom, cornerPaint)

                    // Bottom-right
                    c.drawLine(right - cornerSize, bottom, right, bottom, cornerPaint)
                    c.drawLine(right, bottom - cornerSize, right, bottom, cornerPaint)

                    // Show coordinates and size
                    val sizeText = "${(right - left).toInt()}x${(bottom - top).toInt()}"
                    c.drawText("(${left.toInt()},${top.toInt()})", left + 10, top - 10, debugPaint)
                    c.drawText(sizeText, (left + right) / 2, bottom + 35, debugPaint.apply { textAlign = Paint.Align.CENTER })
                    debugPaint.textAlign = Paint.Align.LEFT // Reset alignment

                    // Show completion instruction if has selection
                    if (hasSelection) {
                        c.drawText("Tap anywhere to capture", width / 2f, height - 100f, instructionPaint)
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean {
            event?.let { e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hasSelection && !hasCaptured) {
                            captureCurrentSelection()
                            return true
                        }

                        // Start new selection
                        startX = e.x
                        startY = e.y
                        endX = e.x
                        endY = e.y
                        isDrawing = true
                        hasSelection = false
                        hasCaptured = false // reset before starting new selection
                        Log.d("TOUCH_DEBUG", "Started selection at: (${startX}, ${startY})")
                        invalidate()
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (isDrawing) {
                            endX = e.x
                            endY = e.y
                            invalidate()
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (isDrawing) {
                            endX = e.x
                            endY = e.y
                            isDrawing = false

                            val left = minOf(startX, endX).toInt()
                            val top = minOf(startY, endY).toInt()
                            val right = maxOf(startX, endX).toInt()
                            val bottom = maxOf(startY, endY).toInt()

                            val width = right - left
                            val height = bottom - top

                            Log.d("TOUCH_DEBUG", "Selection completed: ($left, $top, $right, $bottom)")
                            Log.d("TOUCH_DEBUG", "Selection size: ${width}x${height}")

                            // Check minimum size
                            if (width >= 50 && height >= 50) {
                                hasSelection = true
                                invalidate()

                                // Auto-capture after a brief moment, or wait for tap
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (hasSelection) {
                                        captureCurrentSelection()
                                    }
                                }, 2000) // Auto capture after 2 seconds

                            } else {
                                Toast.makeText(context, "Selection too small (minimum 50x50)", Toast.LENGTH_SHORT).show()
                                hasSelection = false
                                startX = 0f
                                startY = 0f
                                endX = 0f
                                endY = 0f
                                invalidate()
                            }
                        }
                        return true
                    }

                    else -> {}
                }
            }
            return super.onTouchEvent(event)
        }

        private fun sendCaptureCompleteSignal() {
            val intent = Intent("CAPTURE_COMPLETE")
            sendBroadcast(intent)
            android.util.Log.d("DrawingOverlay", "Sent CAPTURE_COMPLETE broadcast")
        }

        private var hasCaptured = false
        private fun captureCurrentSelection() {
            if (!hasSelection || hasCaptured) return
            hasCaptured = true

            val left = minOf(startX, endX).toInt()
            val top = minOf(startY, endY).toInt()
            val right = maxOf(startX, endX).toInt()
            val bottom = maxOf(startY, endY).toInt()

            val rect = Rect(left, top+100, right, bottom+100)

            Log.d("CAPTURE_DEBUG", "Capturing region: $rect")
            Log.d("CAPTURE_DEBUG", "Mode: $mode")

            when (mode) {
                "screenshot" -> {
                    ScreenshotHelper.captureScreenshotRegion(service, rect) { path ->
                        Handler(Looper.getMainLooper()).post {
                            if (path.isNotEmpty()) {
                                Toast.makeText(service, "Screenshot saved!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(service, "Screenshot failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    sendCaptureCompleteSignal()
                }

                "ocr" -> {
                    // Show overlay to indicate what's being captured
//                    ScreenshotHelper.showOverlayRect(service, rect, 1000L)

                    ScreenshotHelper.captureScreenshotForOCR(service, rect) { text ->
                        Handler(Looper.getMainLooper()).post {
                            if (text.isNotEmpty()) {
                                OCRHelper.showExtractedText(service, text)
                            } else {
                                Toast.makeText(service, "No text found in selected region", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    sendCaptureCompleteSignal()


                }
            }

            // Stop the service
            service.stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::windowManager.isInitialized && ::drawingView.isInitialized) {
            try {
                windowManager.removeView(drawingView)
            } catch (e: Exception) {
                Log.e("DRAWING_SERVICE", "Error removing view", e)
            }
        }

        // Show the main floating widget again
        val intent = Intent("SHOW_FLOATING_WIDGET")
        sendBroadcast(intent)

        Log.d("DRAWING_SERVICE", "DrawingOverlayService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}