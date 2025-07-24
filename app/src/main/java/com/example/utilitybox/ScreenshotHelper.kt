// ScreenshotHelper.kt - Fixed MediaProjection and Coordinate Issues
package com.example.utilitybox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ScreenshotHelper {
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var resultCode: Int = 0
    private var data: Intent? = null
    private var isMediaProjectionReady = false
    private var isRecreating = false // Prevent infinite recreation

    fun requestMediaProjection(activity: Activity, requestCode: Int) {
        mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager!!.createScreenCaptureIntent()
        activity.startActivityForResult(intent, requestCode)
    }

    fun setMediaProjection(context: Context, resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.data = data

        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Create persistent media projection
        createMediaProjection()
        isMediaProjectionReady = true

        Log.d("SCREENSHOT", "MediaProjection has been set and is persistent.")
    }

    private fun createMediaProjection() {
        if (isRecreating) {
            Log.d("SCREENSHOT", "Already recreating, skipping...")
            return
        }

        try {
            isRecreating = true
            mediaProjection?.stop() // Stop any existing projection
            mediaProjection = null

            // Small delay before creating new one
            Thread.sleep(100)

            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data!!)

            // REMOVED the callback that was causing infinite recreation
            Log.d("SCREENSHOT", "MediaProjection created successfully")
            isRecreating = false

        } catch (e: Exception) {
            Log.e("SCREENSHOT", "Error creating MediaProjection: ${e.message}")
            isMediaProjectionReady = false
            isRecreating = false
        }
    }

    fun isMediaProjectionReady(): Boolean = isMediaProjectionReady && mediaProjection != null

    fun captureScreenshot(context: Context, callback: (String) -> Unit) {
        val metrics = context.resources.displayMetrics
        val rect = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        captureScreenshotRegion(context, rect, callback)
    }

    fun captureScreenshotRegion(context: Context, rect: Rect, callback: (String) -> Unit) {
        if (!ensureMediaProjection()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "MediaProjection not available", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val realSize = Point()
        display.getRealSize(realSize)

        val screenWidth = realSize.x
        val screenHeight = realSize.y
        val densityDpi = context.resources.displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        val virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        Thread {
            try {
                // Wait for capture
                Thread.sleep(500)

                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bitmapWidth = rowStride / pixelStride
                    val rawBitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                    rawBitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    // Crop to remove padding
                    val fullBitmap = if (bitmapWidth > screenWidth) {
                        val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                        rawBitmap.recycle()
                        cropped
                    } else {
                        rawBitmap
                    }

                    // Use exact coordinates - no scaling needed as we match screen dimensions
                    val cropWidth = rect.width()
                    val cropHeight = rect.height()

                    val left = rect.left.coerceIn(0, fullBitmap.width - 1)
                    val top = rect.top.coerceIn(0, fullBitmap.height - 1)
                    val right = (rect.left + cropWidth).coerceIn(left + 1, fullBitmap.width)
                    val bottom = (rect.top + cropHeight).coerceIn(top + 1, fullBitmap.height)

                    if (right > left && bottom > top) {
                        val croppedBitmap = Bitmap.createBitmap(
                            fullBitmap,
                            left, top,
                            right - left, bottom - top
                        )

                        val path = saveScreenshot(context, croppedBitmap, "screenshot")
                        Log.d("SCREENSHOT", "Screenshot saved at: $path")

                        Handler(Looper.getMainLooper()).post {
                            callback(path)
                        }

                        fullBitmap.recycle()
                        croppedBitmap.recycle()
                    }
                }

                virtualDisplay.release()
                imageReader.close()

            } catch (e: Exception) {
                Log.e("SCREENSHOT", "Exception while taking screenshot: ${e.message}")
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Screenshot failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                virtualDisplay.release()
                imageReader.close()
            }
        }.start()
    }

    fun captureScreenshotForOCR(context: Context, rect: Rect, callback: (String) -> Unit) {
        if (!ensureMediaProjection()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "MediaProjection not available", Toast.LENGTH_SHORT).show()
            }
            callback("")
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val realSize = Point()
        val screenSize = Point()

        display.getRealSize(realSize)
        display.getSize(screenSize)

        val screenWidth = realSize.x
        val screenHeight = realSize.y
        val densityDpi = context.resources.displayMetrics.densityDpi

        Log.d("OCR_DEBUG", "Real screen size: ${screenWidth}x${screenHeight}")
        Log.d("OCR_DEBUG", "Display screen size: ${screenSize.x}x${screenSize.y}")
        Log.d("OCR_DEBUG", "Selection rect: $rect")

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

//        -------
        // Before calling createVirtualDisplay, register a callback
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Handle when media projection stops
                Log.d("MediaProjection", "Media projection stopped")
            }
        }, null)
//        -------

        // Create a fresh virtual display for OCR
        val vd = mediaProjection!!.createVirtualDisplay(
            "OCRCapture_${System.currentTimeMillis()}", // Unique name
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        Thread {
            try {
                // Wait for the virtual display to be ready
                Thread.sleep(800) // Increased wait time

                val image = reader.acquireLatestImage()
                if (image == null) {
                    Log.e("OCR", "Failed to acquire image")
                    Handler(Looper.getMainLooper()).post {
                        callback("")
                    }
                    vd.release()
                    reader.close()
                    return@Thread
                }

                val imageWidth = image.width
                val imageHeight = image.height
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride

                Log.d("OCR_DEBUG", "Image size: ${imageWidth}x${imageHeight}")
                Log.d("OCR_DEBUG", "Row stride: $rowStride, Pixel stride: $pixelStride")

                val bitmapWidth = rowStride / pixelStride
                val rawBitmap = Bitmap.createBitmap(bitmapWidth, imageHeight, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Remove padding if present
                val fullBitmap = if (bitmapWidth > imageWidth) {
                    val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, imageWidth, imageHeight)
                    rawBitmap.recycle()
                    cropped
                } else {
                    rawBitmap
                }

                Log.d("OCR_DEBUG", "Full bitmap size: ${fullBitmap.width}x${fullBitmap.height}")

                // Direct coordinate mapping - no scaling since we use real screen dimensions
                val left = rect.left.coerceIn(0, fullBitmap.width - 1)
                val top = rect.top.coerceIn(0, fullBitmap.height - 1)
                val right = rect.right.coerceIn(left + 1, fullBitmap.width)
                val bottom = rect.bottom.coerceIn(top + 1, fullBitmap.height)

                val cropWidth = right - left
                val cropHeight = bottom - top

                Log.d("OCR_DEBUG", "Crop coordinates: ($left, $top, $right, $bottom)")
                Log.d("OCR_DEBUG", "Crop dimensions: ${cropWidth}x${cropHeight}")

                if (cropWidth > 0 && cropHeight > 0) {
                    val croppedBitmap = Bitmap.createBitmap(
                        fullBitmap,
                        left, top,
                        cropWidth, cropHeight
                    )

                    // Save debug image
                    val debugPath = saveScreenshot(context, croppedBitmap, "ocr_debug")
                    Log.d("OCR_DEBUG", "OCR debug image saved at: $debugPath")

                    // Extract text
                    val extractedText = OCRHelper.extractTextFromBitmap(croppedBitmap)

                    fullBitmap.recycle()
                    croppedBitmap.recycle()

                    Log.d("OCR", "Text extracted: '$extractedText'")

                    Handler(Looper.getMainLooper()).post {
                        callback(extractedText)
                    }
                } else {
                    Log.e("OCR", "Invalid crop dimensions: ${cropWidth}x${cropHeight}")
                    fullBitmap.recycle()
                    Handler(Looper.getMainLooper()).post {
                        callback("")
                    }
                }

                vd.release()
                reader.close()

            } catch (ex: Exception) {
                Log.e("OCR", "OCR capture failed", ex)
                vd.release()
                reader.close()
                Handler(Looper.getMainLooper()).post {
                    callback("")
                }
            }
        }.start()
    }

    fun showOverlayRect(context: Context, rect: Rect, durationMillis: Long = 1000L) {
        val overlayView = object : View(context) {
            private val paint = Paint().apply {
                color = Color.argb(200, 255, 0, 0)
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }

            private val fillPaint = Paint().apply {
                color = Color.argb(50, 255, 0, 0)
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawRect(rect, fillPaint)
                canvas.drawRect(rect, paint)

                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 24f
                    isAntiAlias = true
                }
                canvas.drawText("(${rect.left},${rect.top})", rect.left.toFloat(), rect.top.toFloat() - 10, textPaint)
            }
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(overlayView, params)

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    wm.removeView(overlayView)
                } catch (e: Exception) {
                    Log.e("OVERLAY", "Error removing overlay", e)
                }
            }, durationMillis)
        } catch (e: Exception) {
            Log.e("OVERLAY", "Error showing overlay", e)
        }
    }

    private fun ensureMediaProjection(): Boolean {
        if (mediaProjection == null && isMediaProjectionReady && !isRecreating) {
            Log.d("SCREENSHOT", "Recreating MediaProjection...")
            createMediaProjection()
        }
        return mediaProjection != null
    }

    private fun saveScreenshot(context: Context, bitmap: Bitmap, prefix: String): String {
        val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val picturesDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots")
        if (!picturesDir.exists()) {
            picturesDir.mkdirs()
        }

        val file = File(picturesDir, "${prefix}_$now.png")
        val fos = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        fos.flush()
        fos.close()

        return file.absolutePath
    }

    fun cleanup() {
        isMediaProjectionReady = false
        isRecreating = false
        mediaProjection?.stop()
        mediaProjection = null
        Log.d("SCREENSHOT", "ScreenshotHelper cleanup completed")
    }
}