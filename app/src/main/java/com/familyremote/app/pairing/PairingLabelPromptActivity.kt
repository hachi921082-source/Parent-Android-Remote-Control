package com.familyremote.app.pairing

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.familyremote.app.R
import com.familyremote.app.databinding.ActivityPairingLabelPromptBinding
import java.util.UUID

/**
 * Onboarding step 5 (detailed-spec §13): collects a free-form label for the newly-paired parent
 * device and persists the [PairingEntry]. See PLAN.md §4 step 5.
 */
class PairingLabelPromptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingLabelPromptBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingLabelPromptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.labelEditText.setText(getString(R.string.pairing_label_prompt_default))
        binding.labelEditText.setSelection(binding.labelEditText.text?.length ?: 0)

        binding.saveButton.setOnClickListener { save() }
    }

    private fun save() {
        val childId = UUID.fromString(requireNotNull(intent.getStringExtra(EXTRA_CHILD_ID)))
        val parentHost = requireNotNull(intent.getStringExtra(EXTRA_PARENT_HOST))
        val sharedSecret = requireNotNull(intent.getStringExtra(EXTRA_SHARED_SECRET))
        val label = binding.labelEditText.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) getString(R.string.pairing_label_prompt_default) else it
        }

        val entry = PairingEntry(
            id = childId,
            label = label,
            parentHost = parentHost,
            sharedSecret = sharedSecret,
            createdAt = System.currentTimeMillis()
        )
        PairingRepository(this).addPairingEntry(entry)

        // Whatever pairing-list screen is underneath on the back stack (MainActivity's child tab,
        // or a standalone PairingListActivity per PLAN.md §3's "reused, not a separate screen")
        // refreshes its list on resume -- no need to navigate there explicitly.
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_CHILD_ID = "extra_child_id"
        const val EXTRA_PARENT_HOST = "extra_parent_host"
        const val EXTRA_SHARED_SECRET = "extra_shared_secret"
    }
}
