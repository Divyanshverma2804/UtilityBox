// MainActivity.kt - Cleaned up without clipboard helper and accessibility service
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
import android.util.Log
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

    private val TAG = "MainActivity"

    // BroadcastReceiver to listen for widget service state changes
    private val widgetStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                "SHOW_FLOATING_WIDGET" -> {
                    // Notify service to show widget again
                    val serviceIntent = Intent(this@MainActivity, FloatingWidgetService::class.java)
                    serviceIntent.putExtra("action", "show_widget")
                    startService(serviceIntent)
                    Log.d(TAG, "Sent show_widget command to service")
                }
                "WIDGET_SERVICE_STOPPED" -> {
                    // Widget service has been stopped (e.g., by drag and drop delete)
                    Log.d(TAG, "Widget service stopped - updating UI")
                    isWidgetServiceRunning = false
                    updateButtonState()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate started")

        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.btn_start_widget)

        // Register broadcast receiver for widget state changes
        val filter = IntentFilter().apply {
            addAction("SHOW_FLOATING_WIDGET")
            addAction("WIDGET_SERVICE_STOPPED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(widgetStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(widgetStateReceiver, filter)
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

        updateButtonState()

        Log.d(TAG, "MainActivity onCreate completed")
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

        // Check if the service is actually running by trying to ping it
        // This is a simple way to verify service state when app resumes
        checkServiceStatus()
    }

    private fun checkServiceStatus() {
        // We can implement a simple service health check here if needed
        // For now, we rely on the broadcast receiver to keep state in sync
        Log.d(TAG, "Service status - isWidgetServiceRunning: $isWidgetServiceRunning")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")

        try {
            unregisterReceiver(widgetStateReceiver)
            Log.d(TAG, "Broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }
}