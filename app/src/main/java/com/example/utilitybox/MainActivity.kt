// MainActivity.kt - Updated with proper Clipboard Helper initialization and debugging
package com.example.utilitybox

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_CODE_OVERLAY = 1000
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }

    private var isWidgetServiceRunning = false
    private lateinit var startButton: Button
    private lateinit var debugToggleSwitch: Switch
    private val PREF_DEBUG_IMAGES = "debug_images_enabled"
    // Clipboard helper instance
    private val clipboardHelper = ClipboardHelper.getInstance()

    private val TAG = "MainActivity"

    private val floatingWidgetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                "SHOW_FLOATING_WIDGET" -> {
                    val serviceIntent = Intent(this@MainActivity, FloatingWidgetService::class.java)
                    serviceIntent.putExtra("action", "show_widget")
                    startService(serviceIntent)
                    Log.d(TAG, "Sent show_widget command to service")
                }
                "WIDGET_SERVICE_STOPPED" -> {
                    Log.d(TAG, "🛑 FloatingWidgetService stopped, updating UI")
                    isWidgetServiceRunning = false
                    updateButtonState()
                    Toast.makeText(this@MainActivity, "Widget service stopped", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate started")


        setContentView(R.layout.activity_main)
        showIntroSlideshow()
        findViewById<ImageView>(R.id.app_icon).setOnClickListener {
            showIntroSlideshow()
        }

        startButton = findViewById(R.id.btn_start_widget)
        debugToggleSwitch = findViewById(R.id.switch_debug_images)

        // Initialize clipboard helper with proper timing and debugging
        Log.d(TAG, "Initializing clipboard helper in MainActivity")
        initializeClipboardHelper()

        // Initialize debug toggle
        val sharedPref = getSharedPreferences("UtilityBoxSettings", Context.MODE_PRIVATE)
        val debugEnabled = sharedPref.getBoolean(PREF_DEBUG_IMAGES, false)
        debugToggleSwitch.isChecked = debugEnabled
        ScreenshotHelper.setSaveDebugImages(debugEnabled)

        // Set debug toggle listener
        debugToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            ScreenshotHelper.setSaveDebugImages(isChecked)

            // Save preference
            with(sharedPref.edit()) {
                putBoolean(PREF_DEBUG_IMAGES, isChecked)
                apply()
            }

            val message = if (isChecked) "OCR debug images will be saved" else "OCR debug images disabled"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction("SHOW_FLOATING_WIDGET")
            addAction("WIDGET_SERVICE_STOPPED")   // listen for service stop
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(floatingWidgetReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(floatingWidgetReceiver, filter, RECEIVER_NOT_EXPORTED)
        }

        Log.d(TAG, "Broadcast receiver registered")

        startButton.setOnClickListener {
            Log.d(TAG, "Start button clicked. Service running: $isWidgetServiceRunning")
            if (!isWidgetServiceRunning) {
                checkPermissionsAndStart()
            } else {
                stopWidgetService()
            }
        }

        // Check if accessibility service is enabled
        checkAccessibilityService()

        updateButtonState()

        Log.d(TAG, "MainActivity onCreate completed")
    }

    private fun initializeClipboardHelper() {
        Log.d(TAG, "initializeClipboardHelper() called")

        try {
            // Initialize the clipboard helper
            clipboardHelper.initialize(this)

            // Give it some time to initialize and then check status
            Handler(Looper.getMainLooper()).postDelayed({
                if (clipboardHelper.isInitialized()) {
                    Log.d(TAG, "ClipboardHelper initialized successfully in MainActivity")

                    // Add some test data to verify it's working
                    val historySize = clipboardHelper.getHistory().size
                    Log.d(TAG, "Initial clipboard history size: $historySize")

                    if (historySize == 0) {
                        Log.d(TAG, "Adding test data from MainActivity")
                        clipboardHelper.addTestData()

                        Handler(Looper.getMainLooper()).postDelayed({
                            val newSize = clipboardHelper.getHistory().size
                            Log.d(TAG, "Clipboard history size after test data: $newSize")
                        }, 1000)
                    }

                } else {
                    Log.e(TAG, "ClipboardHelper failed to initialize in MainActivity")
                    Toast.makeText(this, "Clipboard initialization failed", Toast.LENGTH_SHORT).show()
                }
            }, 2000) // Wait 2 seconds for initialization

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing clipboard helper in MainActivity", e)
        }
    }

    private fun checkAccessibilityService() {
        Log.d(TAG, "Checking accessibility service status")
        if (!AccessibilityUtils.isAccessibilityServiceEnabled(this)) {
            Log.d(TAG, "Accessibility service not enabled")
            // Show dialog to enable accessibility service
            AccessibilityUtils.showAccessibilityDialog(this) {
                // User opened settings
                Log.d(TAG, "User opened accessibility settings")
            }
        } else {
            Log.d(TAG, "Accessibility service is enabled")
        }
    }

    private fun showIntroSlideshow() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_slideshow, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()


        val slides = listOf(
            Slide(R.mipmap.snappy, "Snappy", "Fast & sharp – captures any region instantly!"),
            Slide(R.mipmap.lexi, "Lexi", "Playful & text-focused – loves pulling words from screens!"),
            Slide(R.mipmap.clippy_jr, "Clippy Jr.", "Your modern clipboard buddy – remembers everything!")
        )

        val viewPager = dialogView.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.view_pager)
        viewPager.adapter = SlideshowAdapter(slides)

        dialogView.findViewById<Button>(R.id.btn_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }


    private fun checkPermissionsAndStart() {
        Log.d(TAG, "checkPermissionsAndStart() called")

        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission not granted, requesting...")
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
        } else {
            Log.d(TAG, "Overlay permission granted, requesting media projection")
            // Request media projection permission
            ScreenshotHelper.requestMediaProjection(this, REQUEST_CODE_SCREEN_CAPTURE)
        }
    }

    private fun startWidgetService() {
        Log.d(TAG, "startWidgetService() called")

        // Double-check clipboard helper before starting service
        if (!clipboardHelper.isInitialized()) {
            Log.w(TAG, "ClipboardHelper not initialized before starting service, initializing now...")
            clipboardHelper.initialize(this)
        }

        val serviceIntent = Intent(this, FloatingWidgetService::class.java)
        startService(serviceIntent)
        isWidgetServiceRunning = true
        updateButtonState()

        Toast.makeText(this, "Widget started! Double-tap to activate, single-tap to fold/unfold", Toast.LENGTH_LONG).show()
        Log.d(TAG, "Widget service started, minimizing app")

        // Minimize the app
        moveTaskToBack(true)
    }

    private fun stopWidgetService() {
        Log.d(TAG, "stopWidgetService() called")

        val serviceIntent = Intent(this, FloatingWidgetService::class.java)
        stopService(serviceIntent)
        isWidgetServiceRunning = false
        updateButtonState()

        // Clean up screenshot helper
        ScreenshotHelper.cleanup()

        Toast.makeText(this, "Widget stopped", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Widget service stopped")
    }

    private fun updateButtonState() {
        startButton.text = if (isWidgetServiceRunning) {
            "Stop Widget"
        } else {
            "Start Overlay Widget"
        }
        Log.d(TAG, "Button text updated to: ${startButton.text}")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult - requestCode: $requestCode, resultCode: $resultCode")

        when (requestCode) {
            REQUEST_CODE_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "Overlay permission granted, requesting media projection")
                    // Now request media projection
                    ScreenshotHelper.requestMediaProjection(this, REQUEST_CODE_SCREEN_CAPTURE)
                } else {
                    Log.w(TAG, "Overlay permission denied")
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d(TAG, "Screen capture permission granted, starting service")

                    // Start the service with projection data
                    val svc = Intent(this, FloatingWidgetService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("dataIntent", data)
                        action = "INIT_PROJECTION"
                    }
                    startService(svc)
                    isWidgetServiceRunning = true
                    updateButtonState()
                    moveTaskToBack(true)

                } else {
                    Log.w(TAG, "Screen capture permission denied")
                    Toast.makeText(this, "Screen capture permission is required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        // Check if service is still running and update UI accordingly
        // You could implement a more robust service status check here if needed

        // Also re-check clipboard helper status
        if (clipboardHelper.isInitialized()) {
            val historySize = clipboardHelper.getHistory().size
            Log.d(TAG, "onResume - clipboard history size: $historySize")
        } else {
            Log.w(TAG, "onResume - clipboard helper not initialized")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")

        try {
            unregisterReceiver(floatingWidgetReceiver)
            Log.d(TAG, "Broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }

        // Cleanup clipboard helper
        try {
            clipboardHelper.cleanup()
            Log.d(TAG, "ClipboardHelper cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up clipboard helper", e)
        }
    }
}