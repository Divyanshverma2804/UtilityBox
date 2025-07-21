// ClipboardHelper.kt - Fixed version with proper clipboard monitoring
package com.example.utilitybox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var onHistoryChangedCallback: (() -> Unit)? = null
    private var isInitialized = false
    private var applicationContext: Context? = null
    private var isMonitoring = false

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
        Log.d(TAG, "=== INITIALIZE CALLED ===")

        // Always use application context to avoid memory leaks and ensure proper lifecycle
        val appContext = context.applicationContext
        Log.d(TAG, "Using application context: ${appContext.javaClass.simpleName}")

        if (isInitialized && applicationContext == appContext) {
            Log.d(TAG, "Already initialized with same context, skipping")
            return
        }

        // Clean up any existing setup
        if (isInitialized) {
            Log.d(TAG, "Re-initializing with different context")
            cleanup()
        }

        applicationContext = appContext

        // Ensure we're on the main thread for clipboard operations
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
            clipboardManager = applicationContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

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
                    addToHistory(currentText)
                    Log.d(TAG, "Added current clipboard content to history: '${currentText.take(30)}...'")
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

                // Directly show a toast (for visibility)
                Toast.makeText(applicationContext, "📋 Clipboard changed!", Toast.LENGTH_SHORT).show()

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
        // Test after a small delay to ensure everything is set up
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

                Log.d(TAG, "New clipboard text: '${clipText.take(50)}...' (length: ${clipText.length})")
                Log.d(TAG, "Last known text: '${lastClipText.take(50)}...' (length: ${lastClipText.length})")

                // Check if it's new and not empty
                if (clipText.isNotEmpty() && clipText != lastClipText && clipText.trim().length >= 2) {
                    Log.d(TAG, "✅ NEW CLIPBOARD TEXT DETECTED - Adding to history")

                    // Update lastClipText immediately to prevent duplicates
                    lastClipText = clipText

                    // Add to history with a small delay to ensure UI updates properly
                    mainHandler.post {
                        addToHistory(clipText)
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

    private fun addToHistory(text: String) {
        Log.d(TAG, "=== ADDING TO HISTORY ===")
        Log.d(TAG, "Text: '${text.take(100)}...' (${text.length} chars)")

        // Remove any existing duplicate
        val sizeBefore = clipboardHistory.size
        clipboardHistory.removeAll { it.text == text }
        val removedDuplicates = sizeBefore - clipboardHistory.size

        if (removedDuplicates > 0) {
            Log.d(TAG, "Removed $removedDuplicates duplicate entries")
        }

        // Add new item to the front
        val newItem = ClipboardItem(text, System.currentTimeMillis())
//        clipboardHistory.addFirst(newItem)
        clipboardHistory.add(0, newItem)

        Log.d(TAG, "✅ Added item to history. ID: ${newItem.id}")

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

                // Update lastClipText immediately to prevent it being added to history again
                lastClipText = text

                Log.d(TAG, "Text copied to clipboard and lastClipText updated")
            } catch (e: Exception) {
                Log.e(TAG, "Error copying to clipboard: ${e.message}")
            }
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

    fun addTestData() {
        Log.d(TAG, "=== ADDING TEST DATA ===")
        val timestamp = System.currentTimeMillis()

        addToHistory("Test clipboard item 1 - $timestamp")
        mainHandler.postDelayed({
            addToHistory("Test clipboard item 2 - Long text example with more content to test preview functionality and scrolling behavior in the clipboard history overlay")
        }, 100)
        mainHandler.postDelayed({
            addToHistory("Test clipboard item 3 - Another example with different content")
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
        Log.d(TAG, "Current thread: ${Thread.currentThread().name}")
        Log.d(TAG, "Is main thread: ${Looper.myLooper() == Looper.getMainLooper()}")
        Log.d(TAG, "==================")
    }

    fun forceCheckClipboard() {
        Log.d(TAG, "=== FORCE CHECK CLIPBOARD ===")
        if (isInitialized) {
            handleClipboardChange()
        } else {
            Log.w(TAG, "Cannot force check - not initialized")
        }
    }
}