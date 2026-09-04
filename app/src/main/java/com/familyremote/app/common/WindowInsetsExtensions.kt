package com.familyremote.app.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Adds the system status/navigation bar top inset to [this] view's existing top padding, so
 * content isn't drawn under the status bar on API 35+ (edge-to-edge is enforced by default there
 * -- see issue #22). Call once, right after `setContentView`, on the screen's actual top-level
 * root view (not a nested fragment root already covered by its host Activity's own call).
 */
fun View.applyStatusBarTopInsetPadding() {
    val basePaddingTop = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(top = basePaddingTop + systemBarsInsets.top)
        insets
    }
}
