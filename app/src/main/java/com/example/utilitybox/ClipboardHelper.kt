// ClipboardHelper.kt - Enhanced with smart detection and better clipboard monitoring
package com.example.utilitybox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.*

class ClipboardHelper private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: ClipboardHelper? = null
        private const val MAX_HISTORY_SIZE = 10
        private const val TAG = "ClipboardHelper"

        fun getInstance(): ClipboardHelper {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClipboardHelper().also { INSTANCE = it }
            }
            return instance
        }
    }

    private val clipboardHistory = LinkedList<ClipboardItem>()
    private var clipboardManager: ClipboardManager? = null
    private var lastClipText = ""
    private var lastClipHash = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var onHistoryChangedCallback: (() -> Unit)? = null
    private var isInitialized = false
    private var applicationContext: Context? = null
    private var isMonitoring = false
    private var lastAccessTime = 0L
    private var forceRefreshCount = 0

    data class ClipboardItem(
        val text: String,
        val timestamp: Long,
        val id: String = UUID.randomUUID().toString(),
        val source: String = "unknown"
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
        Log.d(TAG, "=== INITIALIZE CALLED ===")

        val appContext = context.applicationContext
        Log.d(TAG, "Using application context: ${appContext.javaClass.simpleName}")

        if (isInitialized && applicationContext == appContext) {
            Log.d(TAG, "Already initialized with same context, skipping")
            return
        }

        if (isInitialized) {
            Log.d(TAG, "Re-initializing with different context")
            cleanup()
        }

        applicationContext = appContext

        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.d(TAG, "Not on main thread, posting to main thread")
            mainHandler.post { performInitialization() }
        } else {
            performInitialization()
        }
    }

    private fun performInitialization() {
        Log.d(TAG, "=== PERFORMING INITIALIZATION ON MAIN THREAD ===")

        try {
            clipboardManager =
                applicationContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

            if (clipboardManager == null) {
                Log.e(TAG, "CRITICAL: Failed to get ClipboardManager!")
                return
            }

            Log.d(TAG, "ClipboardManager obtained successfully")

            // Initialize with current clipboard content
            getCurrentClipboardContent()

            // Start clipboard monitoring
            startClipboardMonitoring()

            isInitialized = true
            Log.d(TAG, "=== INITIALIZATION COMPLETED SUCCESSFULLY ===")

        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR during initialization: ${e.message}", e)
        }
    }

    private fun getCurrentClipboardContent() {
        try {
            val currentClip = clipboardManager?.primaryClip
            if (currentClip != null && currentClip.itemCount > 0) {
                val item = currentClip.getItemAt(0)
                val currentText = item?.text?.toString() ?: ""

                if (currentText.isNotEmpty() && currentText.trim().length >= 2) {
                    lastClipText = currentText
                    lastClipHash = currentText.hashCode()
                    addToHistory(currentText, "initialization")
                    Log.d(
                        TAG,
                        "Added current clipboard content to history: '${currentText.take(30)}...'"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current clipboard content: ${e.message}")
        }
    }

    private fun startClipboardMonitoring() {
        Log.d(TAG, "=== STARTING CLIPBOARD MONITORING ===")

        try {
            if (clipboardManager == null) {
                Log.e(TAG, "Cannot start monitoring - clipboardManager is null!")
                return
            }

            // Remove any existing listener first
            stopClipboardMonitoring()

            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                Log.d(TAG, "🎯 CLIPBOARD CHANGE DETECTED by listener")

                // Show feedback but don't block
                mainHandler.post {
                    try {
                        applicationContext?.let { context ->
                            Toast.makeText(context, "📋 Clipboard updated!", Toast.LENGTH_SHORT)
                                .show()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not show clipboard toast: ${e.message}")
                    }
                }

                if (Looper.myLooper() == Looper.getMainLooper()) {
                    handleClipboardChange()
                } else {
                    mainHandler.post { handleClipboardChange() }
                }
            }

            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            isMonitoring = true

            Log.d(TAG, "✅ Clipboard listener added successfully")

            // Test the setup
            testClipboardMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR starting clipboard monitoring: ${e.message}", e)
        }
    }

    private fun testClipboardMonitoring() {
        mainHandler.postDelayed({
            Log.d(TAG, "=== TESTING CLIPBOARD MONITORING ===")
            try {
                val hasClip = clipboardManager?.hasPrimaryClip() ?: false
                Log.d(TAG, "Has primary clip: $hasClip")

                if (hasClip) {
                    val clip = clipboardManager?.primaryClip
                    Log.d(TAG, "Current clip item count: ${clip?.itemCount}")
                }

                Log.d(TAG, "Clipboard monitoring test completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during clipboard monitoring test: ${e.message}")
            }
        }, 1000)
    }

    private fun handleClipboardChange() {
        Log.d(TAG, "=== HANDLING CLIPBOARD CHANGE ===")

        if (!isMonitoring) {
            Log.d(TAG, "Not monitoring, ignoring change")
            return
        }

        try {
            val clip = clipboardManager?.primaryClip

            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0)?.text?.toString() ?: ""
                val clipHash = clipText.hashCode()

                Log.d(
                    TAG,
                    "New clipboard text: '${clipText.take(50)}...' (length: ${clipText.length})"
                )
                Log.d(TAG, "Hash comparison - New: $clipHash, Last: $lastClipHash")

                // Use hash comparison for better duplicate detection
                if (clipText.isNotEmpty() && clipHash != lastClipHash && clipText.trim().length >= 2) {
                    Log.d(TAG, "✅ NEW CLIPBOARD TEXT DETECTED - Adding to history")

                    // Update tracking variables immediately
                    lastClipText = clipText
                    lastClipHash = clipHash
                    lastAccessTime = System.currentTimeMillis()

                    // Add to history
                    mainHandler.post {
                        addToHistory(clipText, "listener")

                        // Notify floating widget about potential clipboard change
                        try {
                            applicationContext?.sendBroadcast(
                                Intent("CLIPBOARD_MIGHT_HAVE_CHANGED")
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not send clipboard change broadcast: ${e.message}")
                        }
                    }

                } else {
                    Log.d(TAG, "❌ Clipboard text not added - empty, duplicate, or too short")
                }
            } else {
                Log.d(TAG, "❌ No valid clipboard data found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ERROR handling clipboard change: ${e.message}", e)
        }
    }

    private fun stopClipboardMonitoring() {
        if (clipboardListener != null && clipboardManager != null) {
            try {
                clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
                Log.d(TAG, "Clipboard listener removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing clipboard listener: ${e.message}")
            }
        }
        clipboardListener = null
        isMonitoring = false
    }

    private fun addToHistory(text: String, source: String = "unknown") {
        Log.d(TAG, "=== ADDING TO HISTORY ===")
        Log.d(TAG, "Text: '${text.take(100)}...' (${text.length} chars) from $source")

        // Remove any existing duplicate
        val sizeBefore = clipboardHistory.size
        clipboardHistory.removeAll { it.text == text }
        val removedDuplicates = sizeBefore - clipboardHistory.size

        if (removedDuplicates > 0) {
            Log.d(TAG, "Removed $removedDuplicates duplicate entries")
        }

        // Add new item to the front
        val newItem = ClipboardItem(text, System.currentTimeMillis(), source = source)
        clipboardHistory.add(0, newItem)

        Log.d(TAG, "✅ Added item to history. ID: ${newItem.id}, Source: $source")

        // Trim history if needed
        while (clipboardHistory.size > MAX_HISTORY_SIZE) {
            val removed = clipboardHistory.removeLast()
            Log.d(TAG, "Removed oldest item: ${removed.id}")
        }

        Log.d(TAG, "Final history size: ${clipboardHistory.size}")

        // Notify callback
        try {
            onHistoryChangedCallback?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking history changed callback: ${e.message}")
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        Log.d(TAG, "copyToClipboard called with text length: ${text.length}")

        mainHandler.post {
            try {
                val clip = ClipData.newPlainText("Copied Text", text)
                clipboardManager?.setPrimaryClip(clip)

                // Update tracking variables to prevent re-adding
                lastClipText = text
                lastClipHash = text.hashCode()
                lastAccessTime = System.currentTimeMillis()

                Log.d(TAG, "Text copied to clipboard and tracking variables updated")
            } catch (e: Exception) {
                Log.e(TAG, "Error copying to clipboard: ${e.message}")
            }
        }
    }

    fun forceCheckClipboard() {
        Log.d(TAG, "=== FORCE CHECK CLIPBOARD ===")
        forceRefreshCount++

        if (!isInitialized) {
            Log.w(TAG, "Cannot force check - not initialized")
            return
        }

        try {
            val currentTime = System.currentTimeMillis()
            Log.d(TAG, "Force refresh #$forceRefreshCount at $currentTime")

            // Get current clipboard content
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0)?.text?.toString() ?: ""
                val clipHash = clipText.hashCode()

                Log.d(TAG, "Force check found: '${clipText.take(50)}...'")
                Log.d(TAG, "Hash comparison - Current: $clipHash, Last known: $lastClipHash")

                // Check if this is new content
                if (clipText.isNotEmpty() && clipHash != lastClipHash && clipText.trim().length >= 2) {
                    Log.d(TAG, "🔄 Force check found NEW content!")

                    lastClipText = clipText
                    lastClipHash = clipHash
                    lastAccessTime = currentTime

                    addToHistory(clipText, "force_check")

                    // Show feedback
                    applicationContext?.let { context ->
                        mainHandler.post {
                            Toast.makeText(
                                context,
                                "📋 New clipboard content found!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    Log.d(TAG, "Force check - no new content found")
                }
            } else {
                Log.d(TAG, "Force check - no clipboard data")
            }

            lastAccessTime = currentTime

        } catch (e: Exception) {
            Log.e(TAG, "Error in force check: ${e.message}", e)
        }
    }

    fun smartClipboardRefresh(): Boolean {
        Log.d(TAG, "=== SMART CLIPBOARD REFRESH ===")

        if (!isInitialized) {
            Log.w(TAG, "Smart refresh - not initialized")
            return false
        }

        val currentTime = System.currentTimeMillis()
        val timeSinceLastAccess = currentTime - lastAccessTime

        Log.d(TAG, "Time since last access: ${timeSinceLastAccess}ms")

        // Only refresh if enough time has passed
        if (timeSinceLastAccess < 1000) {
            Log.d(TAG, "Smart refresh - too soon since last access")
            return false
        }

        try {
            // Trigger heartbeat activity for foreground access
            applicationContext?.let { context ->
                val heartbeat = Intent(context, ClipboardHeartbeatActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra("action", "smart_refresh")
                }
                context.startActivity(heartbeat)
            }

            // Also do a force check
            mainHandler.postDelayed({
                forceCheckClipboard()
            }, 200)

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error in smart refresh: ${e.message}", e)
            return false
        }
    }

    fun getHistory(): List<ClipboardItem> {
        return clipboardHistory.toList()
    }

    fun deleteFromHistory(itemId: String) {
        val sizeBefore = clipboardHistory.size
        clipboardHistory.removeAll { it.id == itemId }
        val deleted = sizeBefore - clipboardHistory.size

        if (deleted > 0) {
            Log.d(TAG, "Deleted $deleted items with ID: $itemId")
            onHistoryChangedCallback?.invoke()
        }
    }

    fun clearHistory() {
        clipboardHistory.clear()
        Log.d(TAG, "History cleared")
        onHistoryChangedCallback?.invoke()
    }

    fun setOnHistoryChangedCallback(callback: () -> Unit) {
        onHistoryChangedCallback = callback
    }

    fun isInitialized(): Boolean = isInitialized

    fun getStats(): Map<String, Any> {
        return mapOf(
            "initialized" to isInitialized,
            "monitoring" to isMonitoring,
            "historySize" to clipboardHistory.size,
            "lastAccessTime" to lastAccessTime,
            "forceRefreshCount" to forceRefreshCount,
            "lastClipLength" to lastClipText.length
        )
    }

    fun addTestData() {
        Log.d(TAG, "=== ADDING TEST DATA ===")
        val timestamp = System.currentTimeMillis()

        addToHistory("Test clipboard item 1 - $timestamp", "test_data")
        mainHandler.postDelayed({
            addToHistory(
                "Test clipboard item 2 - Long text example with more content to test preview functionality and scrolling behavior in the clipboard history overlay",
                "test_data"
            )
        }, 100)
        mainHandler.postDelayed({
            addToHistory(
                "Test clipboard item 3 - Another example with different content",
                "test_data"
            )
        }, 200)

        Log.d(TAG, "Test data addition scheduled")
    }

    fun cleanup() {
        Log.d(TAG, "=== CLEANUP CALLED ===")

        stopClipboardMonitoring()
        onHistoryChangedCallback = null
        clipboardManager = null
        applicationContext = null
        isInitialized = false
        lastClipText = ""
        lastClipHash = 0
        lastAccessTime = 0L
        forceRefreshCount = 0

        Log.d(TAG, "Cleanup completed")
    }

    // Debug methods
    fun debugState() {
        Log.d(TAG, "=== DEBUG STATE ===")
        Log.d(TAG, "Is initialized: $isInitialized")
        Log.d(TAG, "Is monitoring: $isMonitoring")
        Log.d(TAG, "Application context: $applicationContext")
        Log.d(TAG, "ClipboardManager: $clipboardManager")
        Log.d(TAG, "Listener: $clipboardListener")
        Log.d(TAG, "History size: ${clipboardHistory.size}")
        Log.d(TAG, "Last clip text length: ${lastClipText.length}")
        Log.d(TAG, "Last clip hash: $lastClipHash")
        Log.d(TAG, "Last access time: $lastAccessTime")
        Log.d(TAG, "Force refresh count: $forceRefreshCount")
        Log.d(TAG, "Current thread: ${Thread.currentThread().name}")
        Log.d(TAG, "Is main thread: ${Looper.myLooper() == Looper.getMainLooper()}")
        Log.d(TAG, "==================")
    }
}