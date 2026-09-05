package com.familyremote.app.pairing

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.View
import com.familyremote.app.R
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivityAuthorizedChildListBinding

/**
 * issue #31: lists [AuthorizedChild] entries (devices allowed to view this parent's screen) so
 * stale/duplicate ones -- accumulated by re-pairing the same physical device -- can actually be
 * removed. Launched from [com.familyremote.app.parent.ParentHomeFragment]; there is no equivalent
 * "add" action here since entries are only ever created by the pairing handshake
 * ([PairingServer]), never typed in by hand.
 */
class AuthorizedChildListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthorizedChildListBinding
    private lateinit var repository: PairingRepository
    private lateinit var adapter: AuthorizedChildAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthorizedChildListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()

        repository = PairingRepository(this)
        adapter = AuthorizedChildAdapter(onDelete = ::showDeleteDialog)
        binding.authorizedChildRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.authorizedChildRecyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val children = repository.getAuthorizedChildren()
        adapter.submitList(children)
        binding.emptyText.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
        binding.authorizedChildRecyclerView.visibility = if (children.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showDeleteDialog(child: AuthorizedChild) {
        AlertDialog.Builder(this)
            .setTitle(R.string.authorized_child_delete_dialog_title)
            .setMessage(R.string.authorized_child_delete_dialog_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.deleteAuthorizedChild(child.childId)
                refreshList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
