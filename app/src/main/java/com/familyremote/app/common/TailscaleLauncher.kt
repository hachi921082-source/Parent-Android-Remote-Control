package com.familyremote.app.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens the Tailscale app directly (issue #52), e.g. so a parent can get back to its
 * connect/disconnect switch in two taps after accidentally turning it off, without needing to
 * explain the notification shade's Quick Settings tile. Falls back to Tailscale's own website if
 * it isn't installed -- installTailscaleButton's Play Store listing already covers that case.
 */
fun Context.openTailscaleAppOrWebsite() {
    val launchIntent = packageManager.getLaunchIntentForPackage(Constants.TAILSCALE_PACKAGE_NAME)
    if (launchIntent != null) {
        startActivity(launchIntent)
    } else {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Constants.TAILSCALE_HOMEPAGE_URL)))
    }
}

fun Context.openTailscalePlayStoreListing() {
    startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Constants.TAILSCALE_PACKAGE_NAME}"))
    )
}
