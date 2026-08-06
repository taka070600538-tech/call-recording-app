package com.taka0.callrecorder

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Shows a minimal, non-interactive floating indicator while recording is in progress.
 *
 * This exists to work around an Android restriction (observed empirically, not documented):
 * a foreground service with AudioSource.MIC that has no visible surface during an active call
 * appears to receive silence instead of real microphone audio. An app holding an active overlay
 * (SYSTEM_ALERT_WINDOW) does not have this problem, which is how other call-recording apps on
 * this device are able to capture real audio. This overlay is otherwise inert -- it draws
 * nothing the user needs to interact with, it just needs to exist while recording is active.
 */
object RecordingOverlay {
    private var overlayView: TextView? = null

    fun show(context: Context) {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = TextView(context).apply {
            text = "●"
            setTextColor(Color.RED)
            textSize = 10f
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            // Some devices/ROMs reject overlay windows even with the permission granted;
            // recording continues without the overlay in that case.
        }
    }

    fun hide(context: Context) {
        val view = overlayView ?: return
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // View may already be detached (e.g. activity/window state changed); nothing to do.
        }
        overlayView = null
    }
}
