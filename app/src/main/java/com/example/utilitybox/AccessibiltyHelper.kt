// AccessibilityHelper.kt
package com.example.utilitybox

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityHelper : AccessibilityService() {

    companion object {
        private var instance: AccessibilityHelper? = null

        fun getInstance(): AccessibilityHelper? = instance

        fun isServiceEnabled(): Boolean = instance != null

        fun pasteText(text: String) {
            instance?.performPaste(text)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var clipboardManager: ClipboardManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Log.d("AccessibilityHelper", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed for paste functionality
    }

    override fun onInterrupt() {
        Log.d("AccessibilityHelper", "Accessibility service interrupted")
    }

    private fun performPaste(text: String) {
        handler.post {
            try {
                // Copy the text to clipboard
                val clip = ClipData.newPlainText("Auto Paste", text)
                clipboardManager?.setPrimaryClip(clip)

                // Small delay to ensure clipboard update
                handler.postDelayed({
                    val rootNode = rootInActiveWindow
                    if (rootNode != null) {
                        val focusedNode = findFocusedEditableNode(rootNode)
                        if (focusedNode != null) {
                            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                            Log.d("AccessibilityHelper", "Paste action performed: $success")
                        } else {
                            Log.w("AccessibilityHelper", "No focused editable node found")
                        }
                    } else {
                        Log.w("AccessibilityHelper", "Root node is null")
                    }
                }, 100)

            } catch (e: Exception) {
                Log.e("AccessibilityHelper", "Error performing paste: ${e.message}")
            }
        }
    }

    private fun findFocusedEditableNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (rootNode.isFocused && rootNode.isEditable) {
            return rootNode
        }

        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i)
            child?.let {
                val result = findFocusedEditableNode(it)
                if (result != null) {
                    return result
                }
                it.recycle()
            }
        }

        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d("AccessibilityHelper", "Accessibility service destroyed")
    }
}
