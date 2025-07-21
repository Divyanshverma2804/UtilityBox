package com.example.utilitybox

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
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

    private fun onClipboardChanged(source: String) {
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isNotBlank() && text != lastText) {
            Log.d(TAG, "✂️  $source detected: “$text”")
            lastText = text
            ClipboardHelper.getInstance().forceCheckClipboard()
        }
    }

    // implement the required abstract method
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // we don’t need to react to accessibility events here
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        super.onDestroy()
//        handler.removeCallbacks(pollRunnable)
    }
}
