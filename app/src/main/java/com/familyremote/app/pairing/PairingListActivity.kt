package com.familyremote.app.pairing

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.familyremote.app.R

/**
 * Thin standalone host for [PairingListFragment], so other features (e.g.
 * connection-status-and-resilience's device-selection screen) can launch the pairing list via a
 * plain `Intent` without needing to be inside [com.familyremote.app.MainActivity]'s tab host.
 * See PLAN.md §3.
 */
class PairingListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing_list_host)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragmentContainer, PairingListFragment())
            }
        }
    }
}
