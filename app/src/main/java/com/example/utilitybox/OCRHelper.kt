// OCRHelper.kt
package com.example.utilitybox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*

object OCRHelper {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun extractTextFromBitmap(bitmap: Bitmap): String {
        var extractedText = ""
        val latch = java.util.concurrent.CountDownLatch(1)

        try {

            val image = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    extractedText = visionText.text
                    Log.d("OCR", "Text extraction successful: $extractedText")
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.e("OCR", "Text extraction failed: ${e.message}")
                    latch.countDown()
                }

            // Wait for OCR to complete (with timeout)
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

        } catch (e: Exception) {
            Log.e("OCR", "Exception during OCR processing: ${e.message}")
        }

        return extractedText
    }

    fun showExtractedText(context: Context, text: String) {
        if (text.isNotEmpty()) {
            // Copy to clipboard
            copyToClipboard(context, text)

            // Show toast with first few words
            val preview = if (text.length > 100) {
                text.substring(0, 100) + "..."
            } else {
                text
            }

            Toast.makeText(
                context,
                "Text copied to clipboard:\n$preview",
                Toast.LENGTH_LONG
            ).show()

            // Optional: You can also save to a text file
//            saveTextToFile(context, text)
        } else {
            Toast.makeText(context, "No text found in the selected region", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Extracted Text", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun saveTextToFile(context: Context, text: String) {
        try {
            val textDir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "ExtractedText")
            if (!textDir.exists()) {
                textDir.mkdirs()
            }

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(textDir, "extracted_text_$timestamp.txt")

            file.writeText(text)
            Log.d("OCR", "Text saved to file: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e("OCR", "Error saving text to file: ${e.message}")
        }
    }
}