package com.example.utilitybox

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

class ClipboardHeartbeatActivity : Activity() {
    companion object {
        private const val TAG = "ClipboardHeartbeat"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(1, 1)
        ClipboardHelper.getInstance().forceCheckClipboard()
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HeartbeatActivity destroyed")
    }
}
