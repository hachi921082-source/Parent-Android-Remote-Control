package com.familyremote.app.pairing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.familyremote.app.databinding.ItemPairingEntryBinding

class PairingEntryAdapter(
    private val onRename: (PairingEntry) -> Unit,
    private val onDelete: (PairingEntry) -> Unit
) : RecyclerView.Adapter<PairingEntryAdapter.ViewHolder>() {

    private var entries: List<PairingEntry> = emptyList()

    fun submitList(newEntries: List<PairingEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPairingEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class ViewHolder(private val binding: ItemPairingEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: PairingEntry) {
            binding.labelText.text = entry.label
            binding.renameButton.setOnClickListener { onRename(entry) }
            binding.deleteButton.setOnClickListener { onDelete(entry) }
        }
    }
}
