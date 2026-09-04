package com.familyremote.app.common

import android.view.View
import android.view.ViewGroup
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

/**
 * Adds the system navigation bar's bottom inset to [this] view's existing bottom *margin* (not
 * padding -- padding would grow the view's own drawn bounds, e.g. visibly inflating a button's
 * background box; margin only repositions it), so a bottom-pinned action button isn't crowded by
 * (or drawn under) the nav bar on API 35+ edge-to-edge (issue #21 follow-up). Requires the view to
 * already have [ViewGroup.MarginLayoutParams] (e.g. a direct LinearLayout child).
 */
fun View.applyNavigationBarBottomInsetMargin() {
    val params = layoutParams as ViewGroup.MarginLayoutParams
    val baseMarginBottom = params.bottomMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val marginParams = view.layoutParams as ViewGroup.MarginLayoutParams
        marginParams.bottomMargin = baseMarginBottom + systemBarsInsets.bottom
        view.layoutParams = marginParams
        insets
    }
}
