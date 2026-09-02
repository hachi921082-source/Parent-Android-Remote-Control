package com.familyremote.app.child

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.familyremote.app.R
import com.familyremote.app.pairing.PairingListFragment

/**
 * Child-mode tab. Its content *is* [PairingListFragment] (embedded as a child fragment) -- per
 * PLAN.md §3, that screen is reused rather than duplicated as a separate "child home" UI.
 */
class ChildHomeFragment : Fragment(R.layout.fragment_child_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            childFragmentManager.commit {
                replace(R.id.childHomeFragmentContainer, PairingListFragment())
            }
        }
    }
}
