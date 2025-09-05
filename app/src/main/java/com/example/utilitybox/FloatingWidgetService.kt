// FloatingWidgetService.kt - Enhanced with clipboard features and drag-to-delete
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
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import androidx.annotation.RequiresApi
import kotlin.math.pow
import kotlin.math.sqrt

class FloatingWidgetService : Service() {

    companion object {
        private const val TAG = "FloatingWidget"
        private const val NOTIFICATION_CHANNEL_ID = "floating_widget_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val WIDGET_HIDE_TIMEOUT = 15000L // Increased for Android 15
        private const val DRAG_THRESHOLD = 10
        private const val CLICK_DURATION_THRESHOLD = 200L
        private const val DELETE_ZONE_SIZE = 550
        private const val DELETE_ZONE_MARGIN_BOTTOM = 100
        private const val DELETE_ZONE_ANIMATION_DURATION = 300L
        private const val DELETE_ZONE_HIDE_DELAY = 500L
        private const val DELETE_DISTANCE_THRESHOLD = 1.5
        private const val PERMISSION_RETRY_DELAY = 2000L
        private const val MAX_PERMISSION_RETRIES = 3
    }

    // UI Components
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var mainWidget: LinearLayout
    private lateinit var expandedButtons: LinearLayout
    private lateinit var refreshButton: ImageButton
    private lateinit var params: WindowManager.LayoutParams

    // Delete Zone Components
    private var deleteZone: View? = null
    private lateinit var deleteParams: WindowManager.LayoutParams

    // Widget State
    private var isExpanded = false
    private var lastTapTime = 0L

    // Dragging variables
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var dragStartTime = 0L

    // Permission state
    private var isWaitingForPermission = false
    private var permissionRetryCount = 0
    private var pendingCaptureMode: String? = null

    // Smart clipboard detection
    private var lastClipboardCheck = 0L
    private var isSmartDetectionEnabled = true
    private val smartCheckInterval = 3000L // Check every 3 seconds when smart mode is on
    private var smartDetectionHandler: Handler? = null

    // Focus state management
    private var isInFocusableMode = false
    private var focusToggleHandler: Handler? = null
    private val focusToggleDuration = 2000L // Keep focusable for 2 seconds

    // Handlers
    private val mainHandler = Handler(Looper.getMainLooper())

    // Service state tracking
    private var isServiceInitialized = false

    // Clipboard helper instance
    private val clipboardHelper = ClipboardHelper.getInstance()

    // BroadcastReceiver for capture completion events, permission updates, and clipboard events
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "📡 Received broadcast: ${intent?.action}")

            when (intent?.action) {
                "CAPTURE_COMPLETE" -> {
                    Log.d(TAG, "📸 Capture completed - showing widget")
                    handleCaptureComplete()
                }
                "SHOW_FLOATING_WIDGET" -> {
                    Log.d(TAG, "🔄 Widget show requested")
                    showWidget()
                }
                "OCR_COMPLETE" -> {
                    Log.d(TAG, "📝 OCR completed - showing widget")
                    handleOCRComplete(intent)
                }
                "MEDIA_PROJECTION_READY" -> {
                    Log.d(TAG, "🎬 MediaProjection ready - handling pending operations")
                    handleMediaProjectionReady()
                }
                "MEDIA_PROJECTION_EXPIRED" -> {
                    Log.w(TAG, "⏰ MediaProjection expired")
                    handleMediaProjectionExpired()
                }
                "PERMISSION_DENIED" -> {
                    Log.w(TAG, "❌ Permission denied by user")
                    handlePermissionDenied()
                }
                "CLIPBOARD_MIGHT_HAVE_CHANGED" -> {
                    Log.d(TAG, "🔔 Received clipboard change hint - triggering smart refresh")
                    performSmartClipboardRefresh()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 FloatingWidgetService starting...")

        try {
            initializeService()
            setupPermissionCallback()
            initializeClipboardHelper()
            startSmartClipboardDetection()
            isServiceInitialized = true
            Log.i(TAG, "✅ FloatingWidgetService initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize FloatingWidgetService", e)
            stopSelf()
        }
    }

    private fun initializeService() {
        Log.d(TAG, "⚙️ Initializing service components...")

        // Start as foreground service first (required for Android 14+)
        createNotificationChannel()
        startForegroundService()

        // Register broadcast receiver
        registerBroadcastReceiver()

        // Set up the UI
        setupUI()

        // Initialize delete zone
        initializeDeleteZone()

        Log.d(TAG, "✅ All service components initialized")
    }

    private fun setupPermissionCallback() {
        Log.d(TAG, "🔐 Setting up permission callback...")

        ScreenshotHelper.setPermissionCallback(object : ScreenshotHelper.PermissionCallback {
            override fun onPermissionGranted() {
                Log.d(TAG, "✅ Permission callback: granted")
                mainHandler.post {
                    handleMediaProjectionReady()
                }
            }

            override fun onPermissionDenied() {
                Log.w(TAG, "❌ Permission callback: denied")
                mainHandler.post {
                    handlePermissionDenied()
                }
            }

            fun onPermissionExpired() {
                Log.w(TAG, "⏰ Permission callback: expired")
                mainHandler.post {
                    handleMediaProjectionExpired()
                }
            }
        })
    }

    private fun registerBroadcastReceiver() {
        Log.d(TAG, "📡 Registering broadcast receivers...")

        val filter = IntentFilter().apply {
            addAction("CAPTURE_COMPLETE")
            addAction("SHOW_FLOATING_WIDGET")
            addAction("OCR_COMPLETE")
            addAction("MEDIA_PROJECTION_READY")
            addAction("MEDIA_PROJECTION_EXPIRED")
            addAction("PERMISSION_DENIED")
            addAction("CLIPBOARD_MIGHT_HAVE_CHANGED")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
            }
            Log.d(TAG, "✅ Broadcast receivers registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register broadcast receivers", e)
            throw e
        }
    }

    private fun setupUI() {
        Log.d(TAG, "🎨 Setting up UI components...")

        try {
            // Get window manager
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // Inflate the overlay view
            overlayView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)
            mainWidget = overlayView.findViewById(R.id.main_widget)
            expandedButtons = overlayView.findViewById(R.id.expanded_buttons)
            refreshButton = overlayView.findViewById(R.id.btn_refresh_clipboard)

            setupLayoutParams()
            setupTouchListener()
            setupButtonClickListeners()

            // Add overlay to window
            windowManager.addView(overlayView, params)

            Log.d(TAG, "✅ UI setup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup UI", e)
            throw e
        }
    }

    private fun setupLayoutParams() {
        Log.d(TAG, "📐 Configuring layout parameters...")

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            // Start with non-focusable for best UX
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        Log.d(TAG, "✅ Layout parameters configured")
    }

    private fun initializeDeleteZone() {
        Log.d(TAG, "🗑️ Initializing delete zone...")

        try {
            deleteZone = LayoutInflater.from(this).inflate(R.layout.delete_zone, null)
            deleteZone?.visibility = View.GONE

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE

            deleteParams = WindowManager.LayoutParams(
                DELETE_ZONE_SIZE,
                DELETE_ZONE_SIZE,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            deleteParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            deleteParams.y = DELETE_ZONE_MARGIN_BOTTOM

            windowManager.addView(deleteZone, deleteParams)

            Log.d(TAG, "✅ Delete zone initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize delete zone", e)
        }
    }

    private fun setupTouchListener() {
        Log.d(TAG, "👆 Setting up touch listeners...")

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.v(TAG, "👇 Touch down detected")
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    dragStartTime = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (Math.abs(deltaX) > DRAG_THRESHOLD || Math.abs(deltaY) > DRAG_THRESHOLD) {
                        if (!isDragging) {
                            Log.d(TAG, "🔄 Drag started - showing delete zone")
                            isDragging = true
                            showDeleteZone()
                        }

                        params.x = (initialX + deltaX).toInt()
                        params.y = (initialY + deltaY).toInt()

                        try {
                            windowManager.updateViewLayout(overlayView, params)
                            Log.v(TAG, "📍 Widget moved to (${params.x}, ${params.y})")
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Failed to update widget position", e)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "👆 Touch up detected")
                    endDrag()
                    true
                }

                else -> false
            }
        }

        Log.d(TAG, "✅ Touch listeners configured")
    }

    private fun endDrag() {
        val clickDuration = System.currentTimeMillis() - dragStartTime
        Log.d(TAG, "🛑 Ending drag - duration: ${clickDuration}ms, isDragging: $isDragging")

        if (clickDuration < CLICK_DURATION_THRESHOLD && !isDragging) {
            Log.d(TAG, "👆 Quick tap detected - handling as click")
            handleTap()
        }

        if (isDragging) {
            if (isInDeleteZone()) {
                Log.i(TAG, "🗑️ Widget dropped in delete zone - removing widget")
                removeWidget()
            } else {
                Log.d(TAG, "📍 Widget dropped outside delete zone")
                hideDeleteZone()
            }
        }

        isDragging = false
    }

    private fun isInDeleteZone(): Boolean {
        if (deleteZone == null) {
            Log.w(TAG, "⚠️ Delete zone is null, cannot check position")
            return false
        }

        try {
            val deleteLocation = IntArray(2)
            deleteZone!!.getLocationOnScreen(deleteLocation)
            val deleteCenterX = deleteLocation[0] + deleteZone!!.width / 2
            val deleteCenterY = deleteLocation[1] + deleteZone!!.height / 2

            val widgetLocation = IntArray(2)
            overlayView.getLocationOnScreen(widgetLocation)
            val widgetCenterX = widgetLocation[0] + overlayView.width / 2
            val widgetCenterY = widgetLocation[1] + overlayView.height / 2

            val distance = sqrt(
                (widgetCenterX - deleteCenterX).toDouble().pow(2.0) +
                        (widgetCenterY - deleteCenterY).toDouble().pow(2.0)
            )

            val threshold = deleteZone!!.width / DELETE_DISTANCE_THRESHOLD
            val isInZone = distance < threshold

            Log.d(TAG, "🎯 Delete zone check - distance: $distance, threshold: $threshold, inZone: $isInZone")
            return isInZone

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking delete zone position", e)
            return false
        }
    }

    private fun showDeleteZone() {
        deleteZone?.let { zone ->
            if (zone.visibility == View.GONE) {
                Log.d(TAG, "🗑️ Showing delete zone with animation")
                zone.visibility = View.VISIBLE
                zone.alpha = 0f
                zone.animate()
                    .alpha(1f)
                    .setDuration(DELETE_ZONE_ANIMATION_DURATION)
                    .start()
            }
        } ?: Log.w(TAG, "⚠️ Cannot show delete zone - it's null")
    }

    private fun hideDeleteZone() {
        deleteZone?.let { zone ->
            Log.d(TAG, "🗑️ Hiding delete zone with animation")
            mainHandler.postDelayed({
                zone.animate()
                    .alpha(0f)
                    .setDuration(DELETE_ZONE_ANIMATION_DURATION)
                    .withEndAction {
                        zone.visibility = View.GONE
                        Log.d(TAG, "✅ Delete zone hidden")
                    }
                    .start()
            }, DELETE_ZONE_HIDE_DELAY)
        } ?: Log.w(TAG, "⚠️ Cannot hide delete zone - it's null")
    }

    private fun removeWidget() {
        Log.i(TAG, "🗑️ Removing widget - stopping service")
        Toast.makeText(this, "🗑️ Widget removed", Toast.LENGTH_SHORT).show()

        // Clean up ScreenshotHelper resources
        try {
            ScreenshotHelper.cleanup()
            Log.d(TAG, "✅ ScreenshotHelper cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error cleaning up ScreenshotHelper", e)
        }

        // Clean up clipboard helper
        clipboardHelper.cleanup()

        // Send broadcast to MainActivity to update UI
        notifyMainActivity("WIDGET_SERVICE_STOPPED")

        stopSelf()
    }

    private fun handleTap() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastTapTime < DOUBLE_TAP_TIMEOUT) {
            Log.d(TAG, "👆👆 Double tap detected - activating widget")
            activateWidget()
        } else {
            Log.d(TAG, "👆 Single tap detected - toggling widget")
            toggleWidget()
        }

        lastTapTime = currentTime
    }

    private fun activateWidget() {
        Log.d(TAG, "🎯 Activating widget (full expand)")

        overlayView.alpha = 1.0f
        expandedButtons.visibility = View.VISIBLE
        isExpanded = true

        Toast.makeText(this, "📱 Widget Activated", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "✅ Widget fully activated")
    }

    private fun toggleWidget() {
        if (isExpanded) {
            Log.d(TAG, "📉 Collapsing widget")
            expandedButtons.visibility = View.GONE
            overlayView.alpha = 0.3f
            isExpanded = false
        } else {
            Log.d(TAG, "📈 Semi-expanding widget")
            overlayView.alpha = 0.7f
        }

        Log.d(TAG, "🔄 Widget toggle completed - expanded: $isExpanded")
    }

    // ==================== CLIPBOARD FUNCTIONALITY ====================

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

    // ==================== BUTTON CLICK LISTENERS ====================

    private fun setupButtonClickListeners() {
        Log.d(TAG, "🔘 Setting up button click listeners...")

        // Clipboard refresh button
        if (::refreshButton.isInitialized) {
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
        }

        // Screenshot region button
        overlayView.findViewById<Button>(R.id.btn_screenshot_region)?.setOnClickListener {
            Log.i(TAG, "📸 Screenshot button clicked")
            handleScreenshotRequest()
        }

        // OCR button
        overlayView.findViewById<Button>(R.id.btn_ocr)?.setOnClickListener {
            Log.i(TAG, "📝 OCR button clicked")
            handleOCRRequest()
        }

        // Clipboard History button
        overlayView.findViewById<Button>(R.id.btn_clipboard_history)?.setOnClickListener {
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



        Log.d(TAG, "✅ Button click listeners configured")
    }

    private fun handleScreenshotRequest() {
        Log.d(TAG, "📸 Processing screenshot request...")
        handleCaptureRequest("screenshot")
    }

    private fun handleOCRRequest() {
        Log.d(TAG, "📝 Processing OCR request...")
        handleCaptureRequest("ocr")
    }

    private fun handleCaptureRequest(mode: String) {
        Log.d(TAG, "🎬 Processing capture request for mode: $mode")

        // Check if we need new permission (Android 15 requirement)
        if (ScreenshotHelper.needsNewPermission()) {
            Log.w(TAG, "🔐 MediaProjection permission needed - requesting from MainActivity")
            requestNewPermission(mode)
            return
        }

        // Check if MediaProjection is ready
        if (!ScreenshotHelper.isMediaProjectionReady()) {
            Log.w(TAG, "⚠️ MediaProjection not ready - requesting from MainActivity")
            requestNewPermission(mode)
            return
        }

        try {
            Log.d(TAG, "🚀 Starting capture for mode: $mode")
            startCapture(mode)
            Log.i(TAG, "✅ Capture initiated successfully for mode: $mode")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start capture for mode: $mode", e)
            Toast.makeText(this, "❌ Failed to start $mode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNewPermission(mode: String) {
        Log.d(TAG, "🔐 Requesting new MediaProjection permission for mode: $mode")

        if (isWaitingForPermission) {
            Log.w(TAG, "⏳ Already waiting for permission")
            Toast.makeText(this, "⏳ Permission request in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        if (permissionRetryCount >= MAX_PERMISSION_RETRIES) {
            Log.e(TAG, "❌ Maximum permission retries reached")
            Toast.makeText(this, "❌ Permission request failed. Please restart the app.", Toast.LENGTH_LONG).show()
            resetPermissionState()
            return
        }

        isWaitingForPermission = true
        pendingCaptureMode = mode
        permissionRetryCount++

        // Show user feedback
        Toast.makeText(this, "🔐 Requesting screen capture permission...", Toast.LENGTH_SHORT).show()

        // Request new permission from MainActivity
        val intent = Intent("REQUEST_NEW_MEDIA_PROJECTION").apply {
            putExtra("mode", mode)
            putExtra("retryCount", permissionRetryCount)
        }
        sendBroadcast(intent)

        // Set timeout for permission request
        mainHandler.postDelayed({
            if (isWaitingForPermission) {
                Log.w(TAG, "⏰ Permission request timeout")
                handlePermissionTimeout()
            }
        }, PERMISSION_RETRY_DELAY * 3) // 6 seconds timeout
    }

    private fun startCapture(mode: String) {
        Log.d(TAG, "🎬 Launching DrawingOverlayService for $mode...")

        val intent = Intent(this, DrawingOverlayService::class.java).apply {
            putExtra("mode", mode)
        }

        try {
            startService(intent)
            hideWidgetTemporarily(mode)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start DrawingOverlayService for $mode", e)
            Toast.makeText(this, "❌ Failed to start region selection", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideWidgetTemporarily(reason: String) {
        Log.d(TAG, "👻 Hiding widget temporarily for $reason")

        overlayView.visibility = View.INVISIBLE

        // Show widget again after timeout as fallback
        mainHandler.postDelayed({
            if (overlayView.visibility == View.INVISIBLE) {
                Log.d(TAG, "⏰ Timeout reached - showing widget again (fallback)")
                showWidget()
            }
        }, WIDGET_HIDE_TIMEOUT)
    }

    private fun handleCaptureComplete() {
        Log.d(TAG, "📸 Handling capture completion")
        showWidget()
        resetPermissionState()
    }

    private fun handleOCRComplete(intent: Intent) {
        Log.d(TAG, "📝 Handling OCR completion")
        showWidget()
        resetPermissionState()

        val extractedText = intent.getStringExtra("extracted_text")
        if (!extractedText.isNullOrEmpty()) {
            Toast.makeText(this, "✅ Text extracted: ${extractedText.take(50)}...", Toast.LENGTH_SHORT).show()
        }

        // Trigger manual clipboard refresh after OCR
        Log.d(TAG, "After OCR complete Triggering Manual Clipboard Refresh >>")
        performManualClipboardRefresh()
    }

    private fun handleMediaProjectionReady() {
        Log.d(TAG, "🎬 MediaProjection ready - processing pending operations")

        if (isWaitingForPermission && pendingCaptureMode != null) {
            Log.d(TAG, "🚀 Processing pending capture: $pendingCaptureMode")

            val mode = pendingCaptureMode!!
            resetPermissionState()

            // Small delay to ensure MediaProjection is fully ready
            mainHandler.postDelayed({
                startCapture(mode)
            }, 500)
        } else {
            Log.d(TAG, "ℹ️ No pending operations to process")
            resetPermissionState()
        }
    }

    private fun handleMediaProjectionExpired() {
        Log.w(TAG, "⏰ MediaProjection expired - resetting state")

        Toast.makeText(this, "⏰ Screen capture permission expired", Toast.LENGTH_SHORT).show()
        resetPermissionState()

        // Update widget state to show permission is needed
        updateWidgetState(false)
    }

    private fun handlePermissionDenied() {
        Log.w(TAG, "❌ Permission denied by user")

        Toast.makeText(this, "❌ Screen capture permission denied", Toast.LENGTH_LONG).show()
        resetPermissionState()

        // Update widget state
        updateWidgetState(false)
    }

    private fun handlePermissionTimeout() {
        Log.w(TAG, "⏰ Permission request timeout")

        Toast.makeText(this, "⏰ Permission request timeout. Please try again.", Toast.LENGTH_LONG).show()
        resetPermissionState()
    }

    private fun resetPermissionState() {
        Log.d(TAG, "🔄 Resetting permission state")

        isWaitingForPermission = false
        pendingCaptureMode = null
        permissionRetryCount = 0
    }

    private fun updateWidgetState(hasPermission: Boolean) {
        Log.d(TAG, "🔄 Updating widget state - hasPermission: $hasPermission")

        mainHandler.post {
            // Update widget appearance based on permission state
            overlayView.alpha = if (hasPermission) 0.7f else 0.5f

            // You could also disable buttons or show different visual states
            overlayView.findViewById<Button>(R.id.btn_screenshot_region)?.isEnabled = hasPermission
            overlayView.findViewById<Button>(R.id.btn_ocr)?.isEnabled = hasPermission
        }
    }

    fun showWidget() {
        Log.d(TAG, "👁️ Showing widget")

        mainHandler.post {
            overlayView.visibility = View.VISIBLE
            expandedButtons.visibility = View.GONE

            // Update state based on current permission status
            val hasPermission = ScreenshotHelper.isMediaProjectionReady() && !ScreenshotHelper.needsNewPermission()
            overlayView.alpha = if (hasPermission) 0.7f else 0.5f
            isExpanded = false

            // Hide delete zone if it's showing
            deleteZone?.let { zone ->
                if (zone.visibility == View.VISIBLE) {
                    zone.visibility = View.GONE
                    Log.d(TAG, "🗑️ Delete zone hidden during widget show")
                }
            }
        }

        Log.d(TAG, "✅ Widget shown and reset to default state")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "📢 Creating notification channel...")

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Floating Widget Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating overlay for screenshots and OCR"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)

            Log.d(TAG, "✅ Notification channel created")
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "🔔 Starting foreground service...")

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Floating Widget Active")
            .setContentText("Screenshot and text extraction tools available")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            // CRITICAL: Use correct foreground service type for Android 14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "✅ Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground service", e)
            throw e
        }
    }

    private fun notifyMainActivity(action: String) {
        Log.d(TAG, "📢 Sending broadcast to MainActivity: $action")

        try {
            val intent = Intent(action)
            sendBroadcast(intent)
            Log.d(TAG, "✅ Broadcast sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send broadcast", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🎯 onStartCommand called with action: ${intent?.action}")

        // Ensure service is properly initialized
        if (!isServiceInitialized) {
            Log.w(TAG, "⚠️ Service not initialized, ignoring command")
            return START_NOT_STICKY
        }

        when (intent?.action) {
            "START_WIDGET" -> {
                Log.d(TAG, "🚀 Starting widget")
                showWidget()
            }
            "STOP_WIDGET" -> {
                Log.d(TAG, "🛑 Stopping widget")
                stopSelf()
            }
            "PERMISSION_READY" -> {
                Log.d(TAG, "🔐 Permission ready notification received")
                handleMediaProjectionReady()
            }
            "INIT_PROJECTION" -> {
                Log.i(TAG, "🎬 Legacy MediaProjection initialization...")

                val resultCode = intent.getIntExtra("resultCode", 0)
                val dataIntent = intent.getParcelableExtra<Intent>("dataIntent")

                if (dataIntent != null && resultCode != 0) {
                    // Delay initialization to ensure service is fully ready
                    mainHandler.postDelayed({
                        try {
                            ScreenshotHelper.setMediaProjection(
                                context = this,
                                resultCode = resultCode,
                                data = dataIntent
                            )

                            if (ScreenshotHelper.isMediaProjectionReady()) {
                                Log.i(TAG, "✅ Legacy MediaProjection initialized successfully")
                                Toast.makeText(this, "✅ Screen capture ready", Toast.LENGTH_SHORT).show()
                                updateWidgetState(true)
                            } else {
                                Log.e(TAG, "❌ Legacy MediaProjection initialization failed")
                                Toast.makeText(this, "❌ Failed to initialize screen capture", Toast.LENGTH_LONG).show()
                                updateWidgetState(false)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Exception during legacy MediaProjection initialization", e)
                            Toast.makeText(this, "❌ Screen capture setup failed", Toast.LENGTH_LONG).show()
                            updateWidgetState(false)
                        }
                    }, 1000)
                } else {
                    Log.e(TAG, "❌ Invalid data for legacy MediaProjection initialization")
                    Toast.makeText(this, "❌ Invalid screen capture permissions", Toast.LENGTH_LONG).show()
                    updateWidgetState(false)
                }
            }
            "show_widget" -> {
                Log.d(TAG, "👁️ Show widget action received")
                showWidget()
            }
            else -> {
                Log.d(TAG, "ℹ️ Standard service start")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "🛑 FloatingWidgetService shutting down...")

        // Reset permission state
        resetPermissionState()

        // Stop smart clipboard detection
        smartDetectionHandler?.removeCallbacksAndMessages(null)
        focusToggleHandler?.removeCallbacksAndMessages(null)

        // Clear permission callback
        try {
            ScreenshotHelper.setPermissionCallback(null)
            Log.d(TAG, "✅ Permission callback cleared")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error clearing permission callback", e)
        }

        // Clean up ScreenshotHelper resources first
        try {
            ScreenshotHelper.cleanup()
            Log.d(TAG, "✅ ScreenshotHelper cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error cleaning up ScreenshotHelper", e)
        }

        // Clean up clipboard helper
        clipboardHelper.cleanup()

        // Notify MainActivity that service is stopping
        notifyMainActivity("WIDGET_SERVICE_STOPPED")

        try {
            // Unregister broadcast receiver
            unregisterReceiver(serviceReceiver)
            Log.d(TAG, "✅ Broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error unregistering broadcast receiver", e)
        }

        try {
            // Remove overlay view
            if (::windowManager.isInitialized && ::overlayView.isInitialized) {
                windowManager.removeView(overlayView)
                Log.d(TAG, "✅ Overlay view removed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error removing overlay view", e)
        }

        try {
            // Remove delete zone
            deleteZone?.let { zone ->
                windowManager.removeView(zone)
                deleteZone = null
                Log.d(TAG, "✅ Delete zone removed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error removing delete zone", e)
        }

        // Clear handlers
        mainHandler.removeCallbacksAndMessages(null)

        // Send broadcast to stop heartbeat
        sendBroadcast(Intent("action.STOP_HEARTBEAT"))

        Log.i(TAG, "✅ FloatingWidgetService destroyed successfully")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "🔗 onBind called - returning null (unbound service)")
        return null
    }
}