// FloatingWidgetService.kt - Updated with Clipboard History
package com.example.utilitybox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.RequiresApi

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var mainWidget: LinearLayout
    private lateinit var expandedButtons: LinearLayout
    private var isExpanded = false
    private var lastTapTime = 0L
    private val doubleTapTimeout = 300L

    // Dragging variables
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private lateinit var params: WindowManager.LayoutParams

    // Clipboard helper instance
    private val clipboardHelper = ClipboardHelper.getInstance()

    // BroadcastReceiver to listen for capture completion
    private val captureCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "CAPTURE_COMPLETE" -> {
                    // Show widget back after capture
                    showWidget()
                }
                "SHOW_FLOATING_WIDGET" -> {
                    // Show widget back (from MainActivity)
                    showWidget()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("FloatingWidget", "Service onCreate started")

        // Initialize clipboard helper
        clipboardHelper.initialize(this)

        // Register broadcast receiver for capture completion
        val filter = IntentFilter().apply {
            addAction("CAPTURE_COMPLETE")
            addAction("SHOW_FLOATING_WIDGET")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureCompleteReceiver, filter, RECEIVER_NOT_EXPORTED)
        }

        // Start as foreground service IMMEDIATELY
        createNotificationChannel()
        startForegroundService()

        // Inflate the overlay view
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        mainWidget = overlayView.findViewById(R.id.main_widget)
        expandedButtons = overlayView.findViewById(R.id.expanded_buttons)

        setupLayoutParams()
        setupTouchListener()
        setupButtonClickListeners()

        // Add overlay to window
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(overlayView, params)

        android.util.Log.d("FloatingWidget", "Widget view added to window")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ss_channel",
                "Screenshot Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, "ss_channel")
            .setContentTitle("Screenshot Service")
            .setContentText("Floating overlay running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
        android.util.Log.d("FloatingWidget", "Started as foreground service")
    }

    private fun setupLayoutParams() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100
    }

    private fun setupTouchListener() {
        var isDragging = false

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isDragging = true
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        handleTap()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun handleTap() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastTapTime < doubleTapTimeout) {
            // Double tap - activate widget
            activateWidget()
        } else {
            // Single tap - fold/unfold
            toggleWidget()
        }

        lastTapTime = currentTime
    }

    private fun activateWidget() {
        // Make widget fully visible and show expanded buttons
        overlayView.alpha = 1.0f
        expandedButtons.visibility = View.VISIBLE
        isExpanded = true

        Toast.makeText(this, "Widget Activated", Toast.LENGTH_SHORT).show()
    }

    private fun toggleWidget() {
        if (isExpanded) {
            // Fold - hide expanded buttons, make semi-transparent
            expandedButtons.visibility = View.GONE
            overlayView.alpha = 0.3f
            isExpanded = false
        } else {
            // Unfold - show main widget clearly but not expanded buttons
            overlayView.alpha = 0.7f
        }
    }

    private fun setupButtonClickListeners() {
        // Screenshot region button
        overlayView.findViewById<Button>(R.id.btn_screenshot_region).setOnClickListener {
            if (ScreenshotHelper.isMediaProjectionReady()) {
                startRegionCapture()
            } else {
                Toast.makeText(this, "Media projection not ready. Please restart app.", Toast.LENGTH_LONG).show()
            }
        }

        // OCR button
        overlayView.findViewById<Button>(R.id.btn_ocr).setOnClickListener {
            if (ScreenshotHelper.isMediaProjectionReady()) {
                startOCRCapture()
            } else {
                Toast.makeText(this, "Media projection not ready. Please restart app.", Toast.LENGTH_LONG).show()
            }
        }

        // NEW: Clipboard History button
        overlayView.findViewById<Button>(R.id.btn_clipboard_history).setOnClickListener {
            android.util.Log.d("FloatingWidget", "Clipboard History button clicked")
            openClipboardHistory()
        }


        // Close button
        overlayView.findViewById<Button>(R.id.btn_close).setOnClickListener {
            stopSelf()
        }

        // Delete button (long press to delete)
        overlayView.findViewById<Button>(R.id.btn_delete).setOnLongClickListener {
            stopSelf()
            true
        }
    }

    private fun openClipboardHistory() {
        Log.d("FloatingWidget", "openClipboardHistory called")

        val history = clipboardHelper.getHistory()
        Log.d("FloatingWidget", "Clipboard history size: ${history.size}")

        if (history.isEmpty()) {
            Toast.makeText(this, "No clipboard history yet", Toast.LENGTH_SHORT).show()
            Log.d("FloatingWidget", "Clipboard history is empty. Exiting.")
            return
        }

        // Check if accessibility service is enabled
        val isAccessibilityEnabled = AccessibilityHelper.isServiceEnabled()
        Log.d("FloatingWidget", "Accessibility service enabled: $isAccessibilityEnabled")

        if (!isAccessibilityEnabled) {
            Toast.makeText(this, "Enable Accessibility Service for auto-paste functionality", Toast.LENGTH_LONG).show()
        }

        // Start clipboard overlay service
        try {
            val intent = Intent(this, ClipboardOverlayService::class.java)
            startService(intent)
            Log.d("FloatingWidget", "Started ClipboardOverlayService")
        } catch (e: Exception) {
            Log.e("FloatingWidget", "Failed to start ClipboardOverlayService: ${e.message}")
        }

        // Temporarily hide this widget
        overlayView.visibility = View.INVISIBLE
        Log.d("FloatingWidget", "Overlay view set to INVISIBLE")

        // Set a fallback timer to show widget back
        Handler(Looper.getMainLooper()).postDelayed({
            if (overlayView.visibility == View.INVISIBLE) {
                Log.d("FloatingWidget", "Fallback: Showing widget again after 15s")
                showWidget()
            }
        }, 15000)
    }


    private fun startRegionCapture() {
        // Start drawing overlay for region selection
        val intent = Intent(this, DrawingOverlayService::class.java)
        intent.putExtra("mode", "screenshot")
        startService(intent)

        // Temporarily hide this widget - but don't set GONE, use INVISIBLE
        overlayView.visibility = View.INVISIBLE

        // Set a fallback timer to show widget back if broadcast doesn't come
        Handler(Looper.getMainLooper()).postDelayed({
            if (overlayView.visibility == View.INVISIBLE) {
                showWidget()
            }
        }, 10000) // 10 second fallback
    }

    private fun startOCRCapture() {
        // Start drawing overlay for OCR region selection
        val intent = Intent(this, DrawingOverlayService::class.java)
        intent.putExtra("mode", "ocr")
        startService(intent)

        // Temporarily hide this widget - but don't set GONE, use INVISIBLE
        overlayView.visibility = View.INVISIBLE

        // Set a fallback timer to show widget back if broadcast doesn't come
        Handler(Looper.getMainLooper()).postDelayed({
            if (overlayView.visibility == View.INVISIBLE) {
                showWidget()
            }
        }, 10000) // 10 second fallback
    }

    fun showWidget() {
        overlayView.visibility = View.VISIBLE
        // Reset to folded state but keep it visible
        expandedButtons.visibility = View.GONE
        overlayView.alpha = 0.7f
        isExpanded = false
        android.util.Log.d("FloatingWidget", "Widget shown back")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(captureCompleteReceiver)
        } catch (e: Exception) {
            android.util.Log.e("FloatingWidget", "Error unregistering receiver: ${e.message}")
        }

        // Cleanup clipboard helper
        clipboardHelper.cleanup()

        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "INIT_PROJECTION") {
            val rc = intent.getIntExtra("resultCode", 0)
            val data = intent.getParcelableExtra<Intent>("dataIntent")!!

            // Wait a bit to ensure foreground service is running
            Handler(Looper.getMainLooper()).postDelayed({
                ScreenshotHelper.setMediaProjection(context = this, resultCode = rc, data = data)

                // Check if projection was created successfully
                if (!ScreenshotHelper.isMediaProjectionReady()) {
                    android.util.Log.e("FloatingWidget", "MediaProjection failed to initialize")
                    Toast.makeText(this, "Failed to initialize screen capture", Toast.LENGTH_LONG).show()
                } else {
                    android.util.Log.d("FloatingWidget", "MediaProjection initialized successfully")
                }
            }, 1000)
        } else if (intent?.getStringExtra("action") == "show_widget") {
            // Handle show widget request from MainActivity
            showWidget()
        }

        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

}