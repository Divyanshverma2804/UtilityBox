// ClipboardOverlayService.kt
package com.example.utilitybox

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ClipboardOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var clipboardRecyclerView: RecyclerView
    private lateinit var clipboardAdapter: ClipboardAdapter
    private val clipboardHelper = ClipboardHelper.getInstance()

    // Broadcast receiver for closing overlay
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "CLOSE_CLIPBOARD_OVERLAY") {
                stopSelf()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d("ClipboardOverlay", "Service created")

        // Register receiver
        val filter = IntentFilter("CLOSE_CLIPBOARD_OVERLAY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter, RECEIVER_NOT_EXPORTED)
        }

        createOverlayView()
        setupRecyclerView()
    }

    private fun createOverlayView() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_clipboard_overlay, null)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.CENTER
        params.width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        params.height = (resources.displayMetrics.heightPixels * 0.6).toInt()

        // Close button listener
        overlayView.findViewById<ImageButton>(R.id.btn_close_clipboard).setOnClickListener {
            stopSelf()
        }

        // Clear all button listener
        overlayView.findViewById<Button>(R.id.btn_clear_all).setOnClickListener {
            clipboardHelper.clearHistory()
            clipboardAdapter.notifyDataSetChanged()
        }

        // Handle outside touch to close
        overlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                stopSelf()
                true
            } else {
                false
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun setupRecyclerView() {
        clipboardRecyclerView = overlayView.findViewById(R.id.rv_clipboard_history)
        clipboardAdapter = ClipboardAdapter(
            onItemClick = { clipboardItem ->
                // Paste the selected text
                if (AccessibilityHelper.isServiceEnabled()) {
                    AccessibilityHelper.pasteText(clipboardItem.text)
                    Toast.makeText(this, "Pasted: ${clipboardItem.getPreviewText()}", Toast.LENGTH_SHORT).show()
                    stopSelf() // Close overlay after pasting
                } else {
                    // Fallback: copy to clipboard
                    clipboardHelper.copyToClipboard(this, clipboardItem.text)
                    Toast.makeText(this, "Copied to clipboard. Enable Accessibility Service for auto-paste.", Toast.LENGTH_LONG).show()
                }
            },
            onDeleteClick = { clipboardItem ->
                clipboardHelper.deleteFromHistory(clipboardItem.id)
                clipboardAdapter.notifyDataSetChanged()
            }
        )

        clipboardRecyclerView.layoutManager = LinearLayoutManager(this)
        clipboardRecyclerView.adapter = clipboardAdapter

        // Set history changed callback to update UI
        clipboardHelper.setOnHistoryChangedCallback {
            runOnUiThread {
                clipboardAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(closeReceiver)
        } catch (e: Exception) {
            Log.e("ClipboardOverlay", "Error unregistering receiver: ${e.message}")
        }

        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }

        Log.d("ClipboardOverlay", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // This service is not meant to be bound
        return null
    }
    // RecyclerView Adapter
    inner class ClipboardAdapter(
        private val onItemClick: (ClipboardHelper.ClipboardItem) -> Unit,
        private val onDeleteClick: (ClipboardHelper.ClipboardItem) -> Unit
    ) : RecyclerView.Adapter<ClipboardAdapter.ClipboardViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipboardViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_clipboard_history, parent, false)
            return ClipboardViewHolder(view)
        }

        override fun onBindViewHolder(holder: ClipboardViewHolder, position: Int) {
            val item = clipboardHelper.getHistory()[position]
            holder.bind(item)
        }

        override fun getItemCount(): Int = clipboardHelper.getHistory().size

        inner class ClipboardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textPreview: TextView = itemView.findViewById(R.id.tv_clipboard_preview)
            private val textTime: TextView = itemView.findViewById(R.id.tv_clipboard_time)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_clipboard_item)

            fun bind(item: ClipboardHelper.ClipboardItem) {
                textPreview.text = item.getPreviewText()
                textTime.text = item.getFormattedTime()

                itemView.setOnClickListener { onItemClick(item) }
                btnDelete.setOnClickListener { onDeleteClick(item) }
            }
        }
    }
}