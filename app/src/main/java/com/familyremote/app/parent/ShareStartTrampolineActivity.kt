package com.familyremote.app.parent

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Invisible/translucent activity, the single common entry point from both the home-screen widget
 * and the in-app "Show Screen" button. Requests the MediaProjection grant and starts
 * [ScreenShareForegroundService]. Never visible for more than a frame. See
 * docs/plans/screen-sharing-core/PLAN.md §3.
 */
class ShareStartTrampolineActivity : AppCompatActivity() {

    private val requestProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultData = result.data
            if (result.resultCode == Activity.RESULT_OK && resultData != null) {
                ContextCompat.startForegroundService(
                    this,
                    ScreenShareForegroundService.createStartIntent(this, result.resultCode, resultData)
                )
            }
            // Denied: no further action -- the parent simply sees nothing happened (PLAN.md §3
            // step 3), a deliberate parent action out of scope to handle specially.
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ScreenShareForegroundService.isRunning.value) {
            // Duplicate-launch guard (PLAN.md §4) -- a session is already active. There is no
            // parent-side UI screen to "bring to front" beyond the persistent foreground
            // notification, which is already visible and non-dismissible while the service runs,
            // so this is a no-op beyond skipping a second concurrent MediaProjection request.
            finish()
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        // Force whole-screen-only capture, skipping the OS's per-app share choice entirely
        // (GitHub issue #20) -- MediaProjectionConfig.createConfigForDefaultDisplay() requires
        // API 34+; below that, the OS dialog's app-vs-screen choice (where it exists at all) has
        // no app-level override, so the plain overload is the only option there.
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        requestProjection.launch(captureIntent)
    }
}
