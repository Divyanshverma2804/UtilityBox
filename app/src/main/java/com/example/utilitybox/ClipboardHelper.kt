// ClipboardHelper.kt
package com.example.utilitybox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*

class ClipboardHelper private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: ClipboardHelper? = null
        private const val MAX_HISTORY_SIZE = 10

        fun getInstance(): ClipboardHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClipboardHelper().also { INSTANCE = it }
            }
        }
    }

    private val clipboardHistory = LinkedList<ClipboardItem>()
    private var clipboardManager: ClipboardManager? = null
    private var lastClipText = ""
    private val handler = Handler(Looper.getMainLooper())
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var onHistoryChangedCallback: (() -> Unit)? = null

    data class ClipboardItem(
        val text: String,
        val timestamp: Long,
        val id: String = UUID.randomUUID().toString()
    ) {
        fun getFormattedTime(): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < 60_000 -> "Just now"
                diff < 3600_000 -> "${diff / 60_000}m ago"
                diff < 86400_000 -> "${diff / 3600_000}h ago"
                else -> "${diff / 86400_000}d ago"
            }
        }

        fun getPreviewText(): String {
            return if (text.length > 50) {
                text.substring(0, 47) + "..."
            } else {
                text
            }
        }
    }

    fun initialize(context: Context) {
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        startClipboardMonitoring()

        // Initialize with current clipboard content if any
        val currentClip = clipboardManager?.primaryClip
        if (currentClip != null && currentClip.itemCount > 0) {
            val currentText = currentClip.getItemAt(0).text?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                lastClipText = currentText
                addToHistory(currentText)
            }
        }
    }

    private fun startClipboardMonitoring() {
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            checkClipboardChange()
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        Log.d("ClipboardHelper", "Clipboard monitoring started")
    }

    private fun checkClipboardChange() {
        handler.post {
            try {
                val clip = clipboardManager?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val clipText = clip.getItemAt(0).text?.toString() ?: ""
                    if (clipText.isNotEmpty() && clipText != lastClipText) {
                        addToHistory(clipText)
                        lastClipText = clipText
                        Log.d("ClipboardHelper", "New clipboard content detected: ${clipText.take(20)}...")
                    }
                }
            } catch (e: Exception) {
                Log.e("ClipboardHelper", "Error checking clipboard: ${e.message}")
            }
        }
    }

    private fun addToHistory(text: String) {
        // Don't add empty or very short text
        if (text.trim().length < 2) return

        // Remove if already exists (to move to front)
        clipboardHistory.removeAll { it.text == text }

        // Add to front using index 0
        clipboardHistory.add(0, ClipboardItem(text, System.currentTimeMillis()))

        // Remove oldest if over limit
        while (clipboardHistory.size > MAX_HISTORY_SIZE) {
            clipboardHistory.removeLast()
        }

        // Notify callback
        onHistoryChangedCallback?.invoke()

        Log.d("ClipboardHelper", "Added to history. Total items: ${clipboardHistory.size}")
    }


    fun getHistory(): List<ClipboardItem> {
        return clipboardHistory.toList()
    }

    fun copyToClipboard(context: Context, text: String) {
        try {
            val clip = ClipData.newPlainText("Copied Text", text)
            clipboardManager?.setPrimaryClip(clip)
            // Don't update lastClipText here to avoid duplicate detection
            handler.postDelayed({
                lastClipText = text
            }, 100)
        } catch (e: Exception) {
            Log.e("ClipboardHelper", "Error copying to clipboard: ${e.message}")
        }
    }

    fun deleteFromHistory(itemId: String) {
        clipboardHistory.removeAll { it.id == itemId }
        onHistoryChangedCallback?.invoke()
        Log.d("ClipboardHelper", "Deleted item from history. Remaining: ${clipboardHistory.size}")
    }

    fun clearHistory() {
        clipboardHistory.clear()
        onHistoryChangedCallback?.invoke()
        Log.d("ClipboardHelper", "Clipboard history cleared")
    }

    fun setOnHistoryChangedCallback(callback: () -> Unit) {
        onHistoryChangedCallback = callback
    }

    fun cleanup() {
        clipboardListener?.let {
            clipboardManager?.removePrimaryClipChangedListener(it)
        }
        onHistoryChangedCallback = null
        Log.d("ClipboardHelper", "Clipboard helper cleaned up")
    }
}