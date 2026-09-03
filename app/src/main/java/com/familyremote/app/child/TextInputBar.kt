package com.familyremote.app.child

import android.view.View
import android.widget.EditText
import com.familyremote.app.net.InputTextPayload
import com.familyremote.app.net.MessageFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Controller for the child-side text-entry UI (`TextInputBar` component,
 * docs/plans/remote-input-control/PLAN.md §8, detailed-spec §16): a text field + send button,
 * sending the complete composed string once on send (no per-keystroke streaming, Decision #9).
 * Disabled entirely (field + button both non-interactive) whenever `inputAvailable == false` (§7)
 * -- since the controls themselves prevent the attempt, there is no separate "send attempted while
 * permission missing" error path to handle here.
 */
class TextInputBar(
    private val editText: EditText,
    private val sendButton: View,
    private val scope: CoroutineScope,
    private val sendFrame: suspend (MessageFrame) -> Boolean
) {
    init {
        sendButton.setOnClickListener { onSendClicked() }
    }

    private fun onSendClicked() {
        val text = editText.text?.toString()?.takeIf { it.isNotEmpty() } ?: return
        editText.text?.clear()
        scope.launch { sendFrame(InputTextPayload(text).toFrame()) }
    }

    /** Enables/disables both the field and the send button together, per §7's "scope of
     * view-only" requirement that all input UI is disabled as one unit. */
    fun setEnabled(enabled: Boolean) {
        editText.isEnabled = enabled
        sendButton.isEnabled = enabled
    }
}
