package com.example.utilitybox

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClipboardAccessibilityService : AccessibilityService() {
    private val TAG = "ClipboardAccService"
    private lateinit var clipboardManager: ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🟢 SERVICE CONNECTED")
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // 1) listener
        clipboardManager.addPrimaryClipChangedListener {
            onClipboardChanged("LISTENER")
        }

//        // 2) polling fallback
//        handler.post(pollRunnable)
    }

//    private val pollRunnable = object : Runnable {
//        override fun run() {
//            Log.d(TAG, "🔄 poll tick — checking clipboard")      // <— add this
//            onClipboardChanged("POLL")
//            handler.postDelayed(this, 500)
//        }
//    }

//    private fun onClipboardChanged(source: String) {
//        val clip = clipboardManager.primaryClip
//        val text = clip?.getItemAt(0)?.text?.toString() ?: ""
//        if (text.isNotBlank() && text != lastText) {
//            Log.d(TAG, "✂️  $source detected: “$text”")
//            lastText = text
//            ClipboardHelper.getInstance().forceCheckClipboard()
//        }
//    }

    private fun onClipboardChanged(source: String) {
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isNotBlank() && text != lastText) {
            Log.d(TAG, "✂️  $source detected: “$text”")
            lastText = text
            val intent = Intent("com.example.utilitybox.CLIPBOARD_UPDATED")
            intent.putExtra("clip_text", text)
            sendBroadcast(intent)
        }
    }


    // implement the required abstract method
//    override fun onAccessibilityEvent(event: AccessibilityEvent) {
//            Log.d("onAccessEvent__XDR","ClipboardAccessibilityService detectecting .. UI events such as TYPE_VIEW_TEXT_SELECTION_CHANGED, TYPE_VIEW_TEXT_CHANGED,")
//        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
//            Log.d("onAccessEvent__XDR","Detected change and triggering Heartbeat..>")
//            triggerHeartbeatIfNeeded()
//
//        }
//    }

//    override fun onAccessibilityEvent(event: AccessibilityEvent) {
//        Log.d("onAccessEvent__XDR", "Event received: ${event.eventType} from package: ${event.packageName}")
//
//        // Show event type as string
//        when (event.eventType) {
//            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> Log.d("onAccessEvent__XDR", "TYPE_VIEW_TEXT_SELECTION_CHANGED")
//            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> Log.d("onAccessEvent__XDR", "TYPE_VIEW_TEXT_CHANGED")
//            AccessibilityEvent.TYPE_VIEW_CLICKED -> Log.d("onAccessEvent__XDR", "TYPE_VIEW_CLICKED")
//            AccessibilityEvent.TYPE_VIEW_FOCUSED -> Log.d("onAccessEvent__XDR", "TYPE_VIEW_FOCUSED")
//            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Log.d("onAccessEvent__XDR", "TYPE_WINDOW_STATE_CHANGED")
//            else -> Log.d("onAccessEvent__XDR", "Unhandled type: ${event.eventType}")
//        }
//    }
//
//    override fun onAccessibilityEvent(event: AccessibilityEvent) {
//        val type = event.eventType
//        val packageName = event.packageName?.toString() ?: "unknown"
//        Log.d("onAccessEvent__XDR", "Event received: $type from package: $packageName")
//
//        when (type) {
//            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
//                Log.d("onAccessEvent__XDR", "TYPE_VIEW_TEXT_SELECTION_CHANGED Detected -> Triggering heartbeat")
//                triggerHeartbeatIfNeeded()
//            }
//            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
//                Log.d("onAccessEvent__XDR", "TYPE_VIEW_TEXT_CHANGED Detected -> Triggering heartbeat")
//                triggerHeartbeatIfNeeded()
//            }
//            else -> {
//                Log.d("onAccessEvent__XDR", "Unhandled type: $type")
//            }
//        }
//    }
//
override fun onAccessibilityEvent(event: AccessibilityEvent) {}
//    private fun triggerHeartbeatIfNeeded() {
//        val intent = Intent(this, ClipboardHeartbeatActivity::class.java)
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//        startActivity(intent)
//    }

    private fun triggerHeartbeatIfNeeded() {
        val intent = Intent(this, ClipboardHeartbeatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        Log.d("onAccessEvent__XDR", "Starting Heartbeat Activity from AccessibilityService")
        startActivity(intent)
    }


    override fun onInterrupt() { }

    override fun onDestroy() {
        super.onDestroy()
//        handler.removeCallbacks(pollRunnable)
    }
}
