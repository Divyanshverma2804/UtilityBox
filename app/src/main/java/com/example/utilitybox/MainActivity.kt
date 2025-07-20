// MainActivity.kt - Updated with Clipboard Helper initialization
package com.example.utilitybox

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
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

    // Clipboard helper instance
    private val clipboardHelper = ClipboardHelper.getInstance()

    private val floatingWidgetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SHOW_FLOATING_WIDGET") {
                // Notify service to show widget again
                val serviceIntent = Intent(this@MainActivity, FloatingWidgetService::class.java)
                serviceIntent.putExtra("action", "show_widget")
                startService(serviceIntent)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.btn_start_widget)

        // Initialize clipboard helper
        clipboardHelper.initialize(this)

        // Register broadcast receiver
        val filter = IntentFilter("SHOW_FLOATING_WIDGET")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(floatingWidgetReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(floatingWidgetReceiver, filter)
        }

        startButton.setOnClickListener {
            if (!isWidgetServiceRunning) {
                checkPermissionsAndStart()
            } else {
                stopWidgetService()
            }
        }

        // Check if accessibility service is enabled
        checkAccessibilityService()

        updateButtonState()
    }

    private fun checkAccessibilityService() {
        if (!AccessibilityUtils.isAccessibilityServiceEnabled(this)) {
            // Show dialog to enable accessibility service
            AccessibilityUtils.showAccessibilityDialog(this) {
                // User opened settings
                android.util.Log.d("MainActivity", "User opened accessibility settings")
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
        } else {
            // Request media projection permission
            ScreenshotHelper.requestMediaProjection(this, REQUEST_CODE_SCREEN_CAPTURE)
        }
    }

    private fun startWidgetService() {
        val serviceIntent = Intent(this, FloatingWidgetService::class.java)
        startService(serviceIntent)
        isWidgetServiceRunning = true
        updateButtonState()

        Toast.makeText(this, "Widget started! Double-tap to activate, single-tap to fold/unfold", Toast.LENGTH_LONG).show()

        // Minimize the app
        moveTaskToBack(true)
    }

    private fun stopWidgetService() {
        val serviceIntent = Intent(this, FloatingWidgetService::class.java)
        stopService(serviceIntent)
        isWidgetServiceRunning = false
        updateButtonState()

        // Clean up screenshot helper
        ScreenshotHelper.cleanup()

        Toast.makeText(this, "Widget stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonState() {
        startButton.text = if (isWidgetServiceRunning) {
            "Stop Widget"
        } else {
            "Start Overlay Widget"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == RESULT_OK) {
            // 1) start the service *before* creating projection
            val svc = Intent(this, FloatingWidgetService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("dataIntent", data)
                action = "INIT_PROJECTION"
            }
            startService(svc)
            isWidgetServiceRunning = true
            updateButtonState()
            moveTaskToBack(true)
        }

        when (requestCode) {
            REQUEST_CODE_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    // Now request media projection
                    ScreenshotHelper.requestMediaProjection(this, REQUEST_CODE_SCREEN_CAPTURE)
                } else {
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Set up persistent media projection
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
                    Toast.makeText(this, "Screen capture permission is required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if service is still running
        // You can implement a more robust check here if needed
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(floatingWidgetReceiver)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error unregistering receiver: ${e.message}")
        }

        // Cleanup clipboard helper
        clipboardHelper.cleanup()
    }
}