// FloatingWidgetService.kt - Enhanced with smart clipboard refresh and focus toggling
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
import android.widget.ImageButton
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
    private lateinit var refreshButton: ImageButton
    private var isExpanded = false
    private var lastTapTime = 0L
    private val doubleTapTimeout = 300L

    // Smart clipboard detection
    private var lastClipboardCheck = 0L
    private var isSmartDetectionEnabled = true
    private val smartCheckInterval = 3000L // Check every 3 seconds when smart mode is on
    private var smartDetectionHandler: Handler? = null

    // Focus state management
    private var isInFocusableMode = false
    private var focusToggleHandler: Handler? = null
    private val focusToggleDuration = 2000L // Keep focusable for 2 seconds

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

    // BroadcastReceiver to listen for capture completion and clipboard events
    private val captureCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "CAPTURE_COMPLETE" -> {
                    showWidget()
                }
                "SHOW_FLOATING_WIDGET" -> {
                    showWidget()
                }
                "CLIPBOARD_MIGHT_HAVE_CHANGED" -> {
                    Log.d(TAG, "🔔 Received clipboard change hint - triggering smart refresh")
                    performSmartClipboardRefresh()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate started")

//        // Create heartbeat activity
//        val heartbeat = Intent(this, ClipboardHeartbeatActivity::class.java)
//        heartbeat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//        startActivity(heartbeat)

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction("CAPTURE_COMPLETE")
            addAction("SHOW_FLOATING_WIDGET")
            addAction("CLIPBOARD_MIGHT_HAVE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureCompleteReceiver, filter, RECEIVER_NOT_EXPORTED)
        }

        // Start as foreground service
        createNotificationChannel()
        startForegroundService()

        // Set up the UI
        setupUI()

        // Initialize clipboard helper
        initializeClipboardHelper()

        // Start smart clipboard detection
        startSmartClipboardDetection()

        Log.d(TAG, "Service onCreate completed")
    }

    private fun setupUI() {
        // Inflate the overlay view
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)
        mainWidget = overlayView.findViewById(R.id.main_widget)
        expandedButtons = overlayView.findViewById(R.id.expanded_buttons)
        refreshButton = overlayView.findViewById(R.id.btn_refresh_clipboard)

        setupLayoutParams()
        setupTouchListener()
        setupButtonClickListeners()

        // Add overlay to window
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(overlayView, params)

        Log.d(TAG, "UI setup completed")
    }

    private fun setupLayoutParams() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            // Start with non-focusable for best UX
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100
    }



    private fun toggleFocusMode(enableFocus: Boolean, duration: Long = focusToggleDuration) {
        Log.d(TAG, "🎯 Toggling focus mode: enableFocus=$enableFocus, duration=$duration")

        try {
            // Common flags
            val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

            params.flags = if (enableFocus) {
                baseFlags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            } else {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }

            windowManager.updateViewLayout(overlayView, params)
            isInFocusableMode = enableFocus

            updateRefreshButtonState(enableFocus)

            Log.d(TAG, "✅ Switched to ${if (enableFocus) "FOCUSABLE" else "NON-FOCUSABLE"} mode")

            // Schedule toggle back if needed
            focusToggleHandler?.removeCallbacksAndMessages(null)
            if (enableFocus) {
                focusToggleHandler = Handler(Looper.getMainLooper())
                focusToggleHandler?.postDelayed({
                    toggleFocusMode(false, 0)
                }, duration)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error toggling focus mode: ${e.message}", e)
        }
    }

    private fun updateRefreshButtonState(isRefreshing: Boolean) {
        mainHandler.post {
            if (isRefreshing) {
                refreshButton.alpha = 1.0f
                refreshButton.setBackgroundResource(R.drawable.ic_refresh_active) // You'll need this drawable
            } else {
                refreshButton.alpha = 0.7f
                refreshButton.setBackgroundResource(R.drawable.ic_refresh) // You'll need this drawable
            }
        }
    }

    private fun performManualClipboardRefresh() {
        Log.d(TAG, "🔄 Manual clipboard refresh triggered")

        if (isInFocusableMode) {
            Log.d(TAG, "Already in focusable mode, just refreshing clipboard")
            refreshClipboardData()
            return
        }

        // Toggle to focusable mode temporarily
        toggleFocusMode(true, 2500L) // Give extra time for manual refresh

        // Wait a bit for focus change to take effect, then refresh
        mainHandler.postDelayed({
            refreshClipboardData()
        }, 200)

        Toast.makeText(this, "🔄 Refreshing clipboard...", Toast.LENGTH_SHORT).show()
    }

    private fun performSmartClipboardRefresh() {
        Log.d(TAG, "🧠 Smart clipboard refresh triggered")

        val currentTime = System.currentTimeMillis()

        // Avoid too frequent smart refreshes
        if (currentTime - lastClipboardCheck < 1000) {
            Log.d(TAG, "Smart refresh too soon, skipping")
            return
        }

        lastClipboardCheck = currentTime

        if (!isInFocusableMode) {
            // Quick toggle for smart refresh
            toggleFocusMode(true, 1500L)

            mainHandler.postDelayed({
                refreshClipboardData()
            }, 100)
        } else {
            // Already focusable, just refresh
            refreshClipboardData()
        }
    }

    private fun refreshClipboardData() {
        Log.d(TAG, "📋 Refreshing clipboard data...")

        try {
            if (!clipboardHelper.isInitialized()) {
                Log.w(TAG, "ClipboardHelper not initialized, initializing now")
                clipboardHelper.initialize(applicationContext)
                return
            }

            // Force check current clipboard content
            clipboardHelper.forceCheckClipboard()

//            // Trigger heartbeat activity for additional access
//            val heartbeat = Intent(this, ClipboardHeartbeatActivity::class.java)
//            heartbeat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
//            heartbeat.putExtra("action", "clipboard_refresh")
//            startActivity(heartbeat)

            val historySize = clipboardHelper.getHistory().size
            Log.d(TAG, "✅ Clipboard refreshed. History size: $historySize")

            if (historySize > 0) {
                Toast.makeText(this, "📋 Found ${historySize} clipboard items", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error refreshing clipboard: ${e.message}", e)
        }
    }

    private fun startSmartClipboardDetection() {
        Log.d(TAG, "🧠 Starting smart clipboard detection")

        smartDetectionHandler = Handler(Looper.getMainLooper())

        val smartDetectionRunnable = object : Runnable {
            override fun run() {
                if (isSmartDetectionEnabled && !isInFocusableMode) {
                    // Only do smart detection when we're in non-focusable mode
                    detectClipboardActivity()
                }

                // Schedule next check
                smartDetectionHandler?.postDelayed(this, smartCheckInterval)
            }
        }

        // Start the detection loop
        smartDetectionHandler?.postDelayed(smartDetectionRunnable, smartCheckInterval)
    }

    private fun detectClipboardActivity() {
        // This is where we can add heuristics to detect when clipboard might have changed
        // For now, we'll use a simple time-based approach, but you can enhance this

        val currentTime = System.currentTimeMillis()

        // If it's been a while since last check and user might be active, do a smart refresh
        if (currentTime - lastClipboardCheck > 10000) { // 10 seconds
            Log.d(TAG, "🔍 Smart detection: Performing periodic clipboard check")
            performSmartClipboardRefresh()
        }
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
        // Clipboard refresh button (NEW)
        refreshButton.setOnClickListener {
            Log.d(TAG, "🔄 Refresh button clicked")
            performManualClipboardRefresh()
        }

        // Long press on refresh button to toggle smart detection
        refreshButton.setOnLongClickListener {
            isSmartDetectionEnabled = !isSmartDetectionEnabled
            val status = if (isSmartDetectionEnabled) "enabled" else "disabled"
            Toast.makeText(this, "Smart clipboard detection $status", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "🧠 Smart detection toggled: $isSmartDetectionEnabled")
            true
        }

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
                Log.d(TAG, "After OCR complete Triggering Manual Clipboard Refresh >>")
                performManualClipboardRefresh()
            } else {
                Toast.makeText(this, "Media projection not ready. Please restart app.", Toast.LENGTH_LONG).show()
            }
        }

        // Clipboard History button
        overlayView.findViewById<Button>(R.id.btn_clipboard_history).setOnClickListener {
            Log.d(TAG, "📋 Clipboard History button clicked")

            // Force refresh before opening history
            if (!isInFocusableMode) {
                performSmartClipboardRefresh()
                // Delay opening history to allow refresh
                mainHandler.postDelayed({
                    openClipboardHistory()
                }, 500)
            } else {
                refreshClipboardData()
                mainHandler.postDelayed({
                    openClipboardHistory()
                }, 200)
            }
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

        val history = clipboardHelper.getHistory()
        Log.d(TAG, "Current clipboard history size: ${history.size}")

        if (!clipboardHelper.isInitialized()) {
            Log.w(TAG, "❌ ClipboardHelper not initialized")
            Toast.makeText(this, "Clipboard service not ready. Please try refresh button.", Toast.LENGTH_SHORT).show()
            return
        }

        if (history.isEmpty()) {
            Log.d(TAG, "❌ Clipboard history is empty")
            Toast.makeText(this, "No clipboard history. Try copying some text first, then use refresh button.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "✅ Found ${history.size} clipboard items")

        // Check accessibility service
        val isAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(this)
        if (!isAccessibilityEnabled) {
            Toast.makeText(this, "Enable Accessibility Service for auto-paste", Toast.LENGTH_LONG).show()
        }

        // Start clipboard overlay
        try {
            val intent = Intent(this, ClipboardOverlayService::class.java)
            startService(intent)
            Log.d(TAG, "✅ Started ClipboardOverlayService")

            // Hide this widget temporarily
            overlayView.visibility = View.INVISIBLE

            // Show widget again after timeout
            mainHandler.postDelayed({
                if (overlayView.visibility == View.INVISIBLE) {
                    Log.d(TAG, "Fallback: Showing widget again after timeout")
                    showWidget()
                }
            }, 1500)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start ClipboardOverlayService: ${e.message}", e)
            Toast.makeText(this, "Failed to open clipboard history", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeClipboardHelper() {
        Log.d(TAG, "=== INITIALIZING CLIPBOARD HELPER IN SERVICE ===")

        mainHandler.post {
            try {
                if (!AccessibilityUtils.isAccessibilityServiceEnabled(this)) {
                    Toast.makeText(this, "Please enable Clipboard Accessibility service in Settings → Accessibility.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    clipboardHelper.initialize(applicationContext)

                    clipboardHelper.setOnHistoryChangedCallback {
                        Log.d(TAG, "📋 Clipboard history changed! New size: ${clipboardHelper.getHistory().size}")
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
            Log.d(TAG, "Clipboard initialized: $isInitialized, History size: $historySize")
            clipboardHelper.debugState()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard status: ${e.message}")
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ss_channel", "Screenshot Overlay", NotificationManager.IMPORTANCE_LOW)
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
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
        Log.d(TAG, "Started as foreground service")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== SERVICE DESTROY ===")

        // Stop smart detection
        smartDetectionHandler?.removeCallbacksAndMessages(null)
        focusToggleHandler?.removeCallbacksAndMessages(null)

        try {
            unregisterReceiver(captureCompleteReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }

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
                    ScreenshotHelper.setMediaProjection(context = this, resultCode = rc, data = data)
                    if (!ScreenshotHelper.isMediaProjectionReady()) {
                        Log.e(TAG, "MediaProjection failed to initialize")
                        Toast.makeText(this, "Failed to initialize screen capture", Toast.LENGTH_LONG).show()
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

    override fun onBind(intent: Intent?): IBinder? = null
}