package com.familyremote.app.parent

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.familyremote.app.databinding.FragmentParentHomeBinding
import com.familyremote.app.pairing.OnboardingWizardActivity

/**
 * Parent-mode tab, default tab shown on launch (spec.md §4). Currently only exposes the pairing
 * entry point ("Pair a new child device" -> [OnboardingWizardActivity]) -- the "Show Screen"
 * entry point belongs to screen-sharing-core (Tier 1, not yet implemented).
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
