package com.taka0.callrecorder

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35+ enforces edge-to-edge drawing, so content draws behind the status/navigation
 * bars unless the root view's padding accounts for them. Without this, the first view in a
 * screen (e.g. a top button) can render underneath the status bar and become untappable.
 */
object WindowInsetsUtil {
    fun applySystemBarPadding(rootView: View) {
        val initialPaddingLeft = rootView.paddingLeft
        val initialPaddingTop = rootView.paddingTop
        val initialPaddingRight = rootView.paddingRight
        val initialPaddingBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialPaddingLeft + bars.left,
                initialPaddingTop + bars.top,
                initialPaddingRight + bars.right,
                initialPaddingBottom + bars.bottom
            )
            insets
        }
    }
}
