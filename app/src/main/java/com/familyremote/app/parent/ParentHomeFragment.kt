package com.familyremote.app.parent

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.familyremote.app.R
import com.familyremote.app.databinding.FragmentParentHomeBinding
import com.familyremote.app.pairing.AuthorizedChildListActivity
import com.familyremote.app.pairing.OnboardingWizardActivity
import kotlinx.coroutines.launch

/**
 * Parent-mode tab, default tab shown on launch (spec.md §4). Exposes the initial-setup/pairing
 * entry point ("Initial Setup" -> [OnboardingWizardActivity]) and the "Show Screen" entry point,
 * which invokes the same [ShareStartTrampolineActivity] the home-screen widget uses -- this is
 * also how the 3-tap baseline flow (detailed-spec §4) is realized on setups without the widget
 * placed. See docs/plans/screen-sharing-core/PLAN.md §3 step 5.
 */
class ParentHomeFragment : Fragment() {

    private var _binding: FragmentParentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentParentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.pairNewChildButton.setOnClickListener {
            startActivity(Intent(requireContext(), OnboardingWizardActivity::class.java))
        }
        binding.manageViewersButton.setOnClickListener {
            startActivity(Intent(requireContext(), AuthorizedChildListActivity::class.java))
        }

        // issue #36: this was the only screen-sharing control, and it only ever started a
        // session -- there was no way to stop one short of the OS's own generic MediaProjection
        // control. Reusing this same button as a start/stop toggle (rather than adding a second
        // button) keeps the elderly-parent-facing screen at its existing one-primary-action size.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScreenShareForegroundService.isRunning.collect { running ->
                    binding.showScreenButton.setText(
                        if (running) R.string.parent_home_stop_screen_button else R.string.parent_home_show_screen_button
                    )
                    binding.showScreenButton.setOnClickListener {
                        if (running) {
                            requireContext().startService(ScreenShareForegroundService.createStopIntent(requireContext()))
                        } else {
                            startActivity(Intent(requireContext(), ShareStartTrampolineActivity::class.java))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
