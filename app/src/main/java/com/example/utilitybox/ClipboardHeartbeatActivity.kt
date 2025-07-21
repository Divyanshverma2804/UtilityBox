package com.example.utilitybox

import android.app.Activity
import android.os.Bundle
import android.util.Log

class ClipboardHeartbeatActivity : Activity() {
    companion object {
        private const val TAG = "ClipboardHeartbeat"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make window 1×1 pixel so it’s effectively invisible
        window.setLayout(1, 1)
        Log.d(TAG, "HeartbeatActivity started to hold focus for clipboard")

        // Immediately move this task to the BACK of the stack,
        // but still keep it “focused” for the system
//        moveTaskToBack(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HeartbeatActivity destroyed")
    }
}
