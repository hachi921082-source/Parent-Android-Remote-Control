package com.familyremote.app.pairing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.familyremote.app.databinding.ItemAuthorizedChildBinding

/** No rename here (unlike [PairingEntryAdapter]) -- [AuthorizedChild] has no label field, only
 * the [AuthorizedChild.childHost] address, since it is written by the pairing handshake, not by a
 * user. */
class AuthorizedChildAdapter(
    private val onDelete: (AuthorizedChild) -> Unit
) : RecyclerView.Adapter<AuthorizedChildAdapter.ViewHolder>() {

    private var children: List<AuthorizedChild> = emptyList()

    fun submitList(newChildren: List<AuthorizedChild>) {
        children = newChildren
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAuthorizedChildBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(children[position])
    }

    override fun getItemCount(): Int = children.size

    inner class ViewHolder(private val binding: ItemAuthorizedChildBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(child: AuthorizedChild) {
            binding.labelText.text = child.childHost
            binding.deleteButton.setOnClickListener { onDelete(child) }
        }
    }
}
