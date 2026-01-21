// AccessibilityUtils.kt
package com.example.utilitybox

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityUtils {

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(
            context,
            ClipboardAccessibilityService::class.java
        ).flattenToString()
    
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
    
        return enabledServices
            .split(':')
            .any { it.equals(expectedComponent, ignoreCase = true) }
    }


    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun showAccessibilityDialog(context: Context, onPositive: () -> Unit) {
        if (context !is android.app.Activity) {
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Enable Accessibility Service")
            .setMessage("To enable auto-paste functionality, please enable the Utility Box accessibility service in Settings.\n\nThis allows the app to automatically paste selected clipboard items into text fields.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAccessibilitySettings(context)
                onPositive()
            }
            .setNegativeButton("Not Now") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}