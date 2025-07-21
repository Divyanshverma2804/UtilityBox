// FloatingWidgetService.kt - Updated with proper clipboard initialization
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
import android.provider.Settings
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private val TAG = "FloatingWidget"

    // BroadcastReceiver to listen for capture completion
    private val captureCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "CAPTURE_COMPLETE" -> {
                    showWidget()
                }

                "SHOW_FLOATING_WIDGET" -> {
                    showWidget()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate started")


        Log.d("Heartbeat Craft", "creating Heartbeat activity from floating widget .oncreate() ")

        val heartbeat = Intent(this, ClipboardHeartbeatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(heartbeat)



        // Register broadcast receiver first
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

        // Set up the UI
        setupUI()

        // Initialize clipboard helper AFTER UI is ready
        initializeClipboardHelper()

        Log.d(TAG, "Service onCreate completed")
    }

    private fun setupUI() {
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

        Log.d(TAG, "UI setup completed")
    }

    private fun initializeClipboardHelper() {
        Log.d(TAG, "=== INITIALIZING CLIPBOARD HELPER IN SERVICE ===")

        mainHandler.post {
            try {
                if (!AccessibilityUtils.isAccessibilityServiceEnabled(this)) {
                    Toast.makeText(
                        this,
                        "Please enable Clipboard Accessibility service in Settings → Accessibility.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    // Don't continue initialization
                } else {
                    // Only initialize if accessibility service is enabled
                    clipboardHelper.initialize(applicationContext)

                    clipboardHelper.setOnHistoryChangedCallback {
                        Log.d(
                            TAG,
                            "📋 Clipboard history changed! New size: ${clipboardHelper.getHistory().size}"
                        )
                    }

                    Log.d(TAG, "✅ Clipboard helper initialized in service")

                    mainHandler.postDelayed({
                        checkClipboardStatus()
                    }, 2000)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error initializing clipboard helper: ${e.message}", e)
            }
        }
    }


    private fun checkClipboardStatus() {
        Log.d(TAG, "=== CHECKING CLIPBOARD STATUS ===")

        try {
            val isInitialized = clipboardHelper.isInitialized()
            val historySize = clipboardHelper.getHistory().size

            Log.d(TAG, "Clipboard initialized: $isInitialized")
            Log.d(TAG, "History size: $historySize")

            // Debug state
            clipboardHelper.debugState()

            // If no history, add test data for demonstration
//            if (historySize == 0) {
//                Log.d(TAG, "No clipboard history, adding test data...")
//                clipboardHelper.addTestData()
//
//                // Check again after test data
//                mainHandler.postDelayed({
//                    val newSize = clipboardHelper.getHistory().size
//                    Log.d(TAG, "After test data, history size: $newSize")
//
//                    if (newSize > 0) {
//                        Log.d(TAG, "✅ Test data added successfully")
//                    } else {
//                        Log.e(TAG, "❌ Failed to add test data")
//                    }
//                }, 1000)
//            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard status: ${e.message}")
        }
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
        Log.d(TAG, "Started as foreground service")
    }

    private fun setupLayoutParams() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
//                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            else WindowManager.LayoutParams.TYPE_PHONE,
//            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,

//                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
//                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,

            // this set have the back button blocked but keyboard working with the copy working fine
//            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
//                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
//                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
//                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,

            // this set have the back button + keyboard blocked  .. the copy working fine
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
        overlayView.alpha = 1.0f
        expandedButtons.visibility = View.VISIBLE
        isExpanded = true
        Toast.makeText(this, "Widget Activated", Toast.LENGTH_SHORT).show()
    }

    private fun toggleWidget() {
        if (isExpanded) {
            expandedButtons.visibility = View.GONE
            overlayView.alpha = 0.3f
            isExpanded = false
        } else {
            overlayView.alpha = 0.7f
        }
    }

    private fun setupButtonClickListeners() {
        // Screenshot region button
        overlayView.findViewById<Button>(R.id.btn_screenshot_region).setOnClickListener {
            if (ScreenshotHelper.isMediaProjectionReady()) {
                startRegionCapture()
            } else {
                Toast.makeText(
                    this,
                    "Media projection not ready. Please restart app.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // OCR button
        overlayView.findViewById<Button>(R.id.btn_ocr).setOnClickListener {
            if (ScreenshotHelper.isMediaProjectionReady()) {
                startOCRCapture()
            } else {
                Toast.makeText(
                    this,
                    "Media projection not ready. Please restart app.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Clipboard History button
        overlayView.findViewById<Button>(R.id.btn_clipboard_history).setOnClickListener {
            Log.d(TAG, "📋 Clipboard History button clicked")
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
        Log.d(TAG, "=== OPENING CLIPBOARD HISTORY ===")

        // Force check clipboard status
        clipboardHelper.forceCheckClipboard()

        // Get current history
        val history = clipboardHelper.getHistory()
        Log.d(TAG, "Current clipboard history size: ${history.size}")

        if (!clipboardHelper.isInitialized()) {
            Log.w(TAG, "❌ ClipboardHelper not initialized")
            Toast.makeText(
                this,
                "Clipboard service not ready. Please try again.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Log current history
        if (history.isEmpty()) {
            Log.d(TAG, "❌ Clipboard history is empty")

//            // Add test data and try again
//            clipboardHelper.addTestData()
//            Toast.makeText(this, "Adding sample data. Try again in a moment.", Toast.LENGTH_SHORT)
//                .show()

            return
        } else {
            Log.d(TAG, "✅ Found ${history.size} clipboard items:")
            history.forEachIndexed { index, item ->
                Log.d(TAG, "  [$index] '${item.getPreviewText()}' - ${item.getFormattedTime()}")
            }
        }

        // Check accessibility service
        val isAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(this)
        if (!isAccessibilityEnabled) {
            Toast.makeText(this, "Enable Accessibility Service for auto-paste", Toast.LENGTH_LONG)
                .show()
        }

        // Start clipboard overlay
        try {
            val intent = Intent(this, ClipboardOverlayService::class.java)
            startService(intent)
            Log.d(TAG, "✅ Started ClipboardOverlayService")

            // Hide this widget temporarily
            overlayView.visibility = View.INVISIBLE

            // Fallback to show widget again after 15 seconds
            mainHandler.postDelayed({
                if (overlayView.visibility == View.INVISIBLE) {
                    Log.d(TAG, "Fallback: Showing widget again after timeout")
                    showWidget()
                }
            }, 211)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start ClipboardOverlayService: ${e.message}", e)
            Toast.makeText(this, "Failed to open clipboard history", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRegionCapture() {
        val intent = Intent(this, DrawingOverlayService::class.java)
        intent.putExtra("mode", "screenshot")
        startService(intent)
        overlayView.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            if (overlayView.visibility == View.INVISIBLE) {
                showWidget()
            }
        }, 10000)
    }

    private fun startOCRCapture() {
        val intent = Intent(this, DrawingOverlayService::class.java)
        intent.putExtra("mode", "ocr")
        startService(intent)
        overlayView.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            if (overlayView.visibility == View.INVISIBLE) {
                showWidget()
            }
        }, 10000)
    }

    fun showWidget() {
        overlayView.visibility = View.VISIBLE
        expandedButtons.visibility = View.GONE
        overlayView.alpha = 0.7f
        isExpanded = false
        Log.d(TAG, "Widget shown")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== SERVICE DESTROY ===")

        try {
            unregisterReceiver(captureCompleteReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }

        // Cleanup clipboard helper
        clipboardHelper.cleanup()

        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        sendBroadcast(Intent("action.STOP_HEARTBEAT"))
        Log.d(TAG, "Service destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            "INIT_PROJECTION" -> {
                val rc = intent.getIntExtra("resultCode", 0)
                val data = intent.getParcelableExtra<Intent>("dataIntent")!!

                mainHandler.postDelayed({
                    ScreenshotHelper.setMediaProjection(
                        context = this,
                        resultCode = rc,
                        data = data
                    )

                    if (!ScreenshotHelper.isMediaProjectionReady()) {
                        Log.e(TAG, "MediaProjection failed to initialize")
                        Toast.makeText(
                            this,
                            "Failed to initialize screen capture",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Log.d(TAG, "MediaProjection initialized successfully")
                    }
                }, 1000)
            }

            else -> {
                if (intent?.getStringExtra("action") == "show_widget") {
                    showWidget()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}