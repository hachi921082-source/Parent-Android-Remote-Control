package com.familyremote.app.pairing

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.familyremote.app.R
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivityOpenSourceLicensesBinding

/**
 * Bundled open-source-license notice for BOOTH-style commercial distribution: the full Apache
 * License 2.0 text (covering every runtime dependency this app ships, see
 * open_source_licenses_library_list) is included as a raw text asset rather than an external
 * link, so it stays with the app itself and doesn't depend on a webpage remaining reachable.
 * Reached from the "Initial Setup" screen (AccessibilityOnboardingActivity).
 */
class OpenSourceLicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityOpenSourceLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()

        binding.licenseFullText.text = resources.openRawResource(R.raw.license_apache_2_0)
            .bufferedReader()
            .use { it.readText() }
    }
}
