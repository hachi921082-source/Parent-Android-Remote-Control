package com.familyremote.app.pairing

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyremote.app.R
import com.familyremote.app.databinding.FragmentPairingListBinding
import com.google.android.material.textfield.TextInputEditText

/**
 * Lists existing [PairingEntry] rows (label + rename/delete), entry point for "add new pairing".
 * Reused both as the child-mode tab's home content ([com.familyremote.app.child.ChildHomeFragment])
 * and wrapped standalone by [PairingListActivity] for reuse by other features as a selection
 * screen (detailed-spec §3) -- see PLAN.md §3.
 */
class PairingListFragment : Fragment(R.layout.fragment_pairing_list) {

    private var _binding: FragmentPairingListBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var repository: PairingRepository
    private lateinit var adapter: PairingEntryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPairingListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = PairingRepository(requireContext())

        adapter = PairingEntryAdapter(onRename = ::showRenameDialog, onDelete = ::showDeleteDialog)
        binding.pairingRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.pairingRecyclerView.adapter = adapter

        binding.addFab.setOnClickListener {
            startActivity(Intent(requireContext(), ChildPairingScanActivity::class.java))
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val entries = repository.getPairingEntries()
        adapter.submitList(entries)
        binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.pairingRecyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showRenameDialog(entry: PairingEntry) {
        val editText = TextInputEditText(requireContext()).apply { setText(entry.label) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pairing_rename_dialog_title)
            .setView(editText)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val newLabel = editText.text?.toString()?.trim().orEmpty()
                if (newLabel.isNotEmpty()) {
                    repository.updateLabel(entry.id, newLabel)
                    refreshList()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteDialog(entry: PairingEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pairing_delete_dialog_title)
            .setMessage(R.string.pairing_delete_dialog_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.deletePairingEntry(entry.id)
                refreshList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
