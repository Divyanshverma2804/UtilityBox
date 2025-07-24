// FloatingWidgetService.kt - Enhanced with broadcast on service stop
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
import android.util.Log
import android.view.*
import android.widget.Button
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
        private const val WIDGET_HIDE_TIMEOUT = 10000L // 10 seconds
        private const val DRAG_THRESHOLD = 10
        private const val CLICK_DURATION_THRESHOLD = 200L
        private const val DELETE_ZONE_SIZE = 550
        private const val DELETE_ZONE_MARGIN_BOTTOM = 100
        private const val DELETE_ZONE_ANIMATION_DURATION = 300L
        private const val DELETE_ZONE_HIDE_DELAY = 500L
        private const val DELETE_DISTANCE_THRESHOLD = 1.5 // Division factor for delete zone width
    }

    // UI Components
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var mainWidget: LinearLayout
    private lateinit var expandedButtons: LinearLayout
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

    // Handlers
    private val mainHandler = Handler(Looper.getMainLooper())

    // BroadcastReceiver for capture completion events
    private val captureCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "CAPTURE_COMPLETE" -> {
                    Log.d(TAG, "📸 Capture completed - showing widget")
                    showWidget()
                }
                "SHOW_FLOATING_WIDGET" -> {
                    Log.d(TAG, "🔄 Widget show requested")
                    showWidget()
                }
                "OCR_COMPLETE" -> {
                    Log.d(TAG, "📝 OCR completed - showing widget")
                    showWidget()
                    val extractedText = intent.getStringExtra("extracted_text")
                    if (!extractedText.isNullOrEmpty()) {
                        Toast.makeText(context, "✅ Text extracted: ${extractedText.take(50)}...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 FloatingWidgetService starting...")

        initializeService()

        Log.i(TAG, "✅ FloatingWidgetService initialized successfully")
    }

    private fun initializeService() {
        Log.d(TAG, "⚙️ Initializing service components...")

        // Register broadcast receiver
        registerBroadcastReceiver()

        // Start as foreground service
        createNotificationChannel()
        startForegroundService()

        // Set up the UI
        setupUI()

        // Initialize delete zone
        initializeDeleteZone()

        Log.d(TAG, "✅ All service components initialized")
    }

    private fun registerBroadcastReceiver() {
        Log.d(TAG, "📡 Registering broadcast receivers...")

        val filter = IntentFilter().apply {
            addAction("CAPTURE_COMPLETE")
            addAction("SHOW_FLOATING_WIDGET")
            addAction("OCR_COMPLETE")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(captureCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(captureCompleteReceiver, filter)
            }
            Log.d(TAG, "✅ Broadcast receivers registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register broadcast receivers", e)
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
                        windowManager.updateViewLayout(overlayView, params)
                        Log.v(TAG, "📍 Widget moved to (${params.x}, ${params.y})")
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

        // Send broadcast to MainActivity to update UI
        notifyMainActivity()

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

    private fun setupButtonClickListeners() {
        Log.d(TAG, "🔘 Setting up button click listeners...")

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

        Log.d(TAG, "✅ Button click listeners configured")
    }

    private fun handleScreenshotRequest() {
        Log.d(TAG, "📸 Processing screenshot request...")

        if (!ScreenshotHelper.isMediaProjectionReady()) {
            Log.w(TAG, "⚠️ MediaProjection not ready")
            Toast.makeText(this, "⚠️ Screen capture not ready. Please restart app.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            Log.d(TAG, "🚀 Starting region capture...")
            startRegionCapture()
            Log.i(TAG, "✅ Screenshot capture initiated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start screenshot capture", e)
            Toast.makeText(this, "❌ Failed to start screenshot", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleOCRRequest() {
        Log.d(TAG, "📝 Processing OCR request...")

        if (!ScreenshotHelper.isMediaProjectionReady()) {
            Log.w(TAG, "⚠️ MediaProjection not ready for OCR")
            Toast.makeText(this, "⚠️ Screen capture not ready. Please restart app.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            Log.d(TAG, "🚀 Starting OCR capture...")
            startOCRCapture()
            Log.i(TAG, "✅ OCR capture initiated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start OCR capture", e)
            Toast.makeText(this, "❌ Failed to start text extraction", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRegionCapture() {
        Log.d(TAG, "📸 Launching DrawingOverlayService for screenshot...")

        val intent = Intent(this, DrawingOverlayService::class.java).apply {
            putExtra("mode", "screenshot")
        }

        startService(intent)
        hideWidgetTemporarily("screenshot capture")
    }

    private fun startOCRCapture() {
        Log.d(TAG, "📝 Launching DrawingOverlayService for OCR...")

        val intent = Intent(this, DrawingOverlayService::class.java).apply {
            putExtra("mode", "ocr")
        }

        startService(intent)
        hideWidgetTemporarily("OCR capture")
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

    fun showWidget() {
        Log.d(TAG, "👁️ Showing widget")

        mainHandler.post {
            overlayView.visibility = View.VISIBLE
            expandedButtons.visibility = View.GONE
            overlayView.alpha = 0.7f
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
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "✅ Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground service", e)
            throw e
        }
    }

    private fun notifyMainActivity() {
        Log.d(TAG, "📢 Sending broadcast to MainActivity about service stop")

        try {
            val intent = Intent("WIDGET_SERVICE_STOPPED")
            sendBroadcast(intent)
            Log.d(TAG, "✅ Broadcast sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send broadcast", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🎯 onStartCommand called with action: ${intent?.action}")

        when (intent?.action) {
            "INIT_PROJECTION" -> {
                Log.i(TAG, "🎬 Initializing MediaProjection...")

                val resultCode = intent.getIntExtra("resultCode", 0)
                val dataIntent = intent.getParcelableExtra<Intent>("dataIntent")

                if (dataIntent != null) {
                    mainHandler.postDelayed({
                        try {
                            ScreenshotHelper.setMediaProjection(
                                context = this,
                                resultCode = resultCode,
                                data = dataIntent
                            )

                            if (ScreenshotHelper.isMediaProjectionReady()) {
                                Log.i(TAG, "✅ MediaProjection initialized successfully")
                                Toast.makeText(this, "✅ Screen capture ready", Toast.LENGTH_SHORT).show()
                            } else {
                                Log.e(TAG, "❌ MediaProjection initialization failed")
                                Toast.makeText(this, "❌ Failed to initialize screen capture", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Exception during MediaProjection initialization", e)
                        }
                    }, 1000)
                } else {
                    Log.e(TAG, "❌ DataIntent is null for MediaProjection initialization")
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

        // Notify MainActivity that service is stopping
        notifyMainActivity()

        try {
            // Unregister broadcast receiver
            unregisterReceiver(captureCompleteReceiver)
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

        Log.i(TAG, "✅ FloatingWidgetService destroyed successfully")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "🔗 onBind called - returning null (unbound service)")
        return null
    }
}