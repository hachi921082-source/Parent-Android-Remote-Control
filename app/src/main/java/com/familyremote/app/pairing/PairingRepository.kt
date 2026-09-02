package com.familyremote.app.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.util.UUID

/**
 * CRUD over the child-side [PairingEntry] list and the parent-side [AuthorizedChild] list.
 * Both lists are stored (as a single JSON array) in [EncryptedSharedPreferences] -- see
 * PLAN.md §2 for why Room was skipped in favor of this for such a tiny dataset.
 *
 * A device only ever populates one of the two lists in practice (whichever role it is currently
 * acting as), but both method groups live on this single class per PLAN.md's component table.
 */
class PairingRepository(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // --- Child-side: PairingEntry list ---

    fun getPairingEntries(): List<PairingEntry> {
        val raw = prefs.getString(KEY_PAIRING_ENTRIES, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { PairingEntry.fromJson(array.getJSONObject(it)) }
    }

    fun addPairingEntry(entry: PairingEntry) {
        val entries = getPairingEntries().toMutableList()
        entries.add(entry)
        savePairingEntries(entries)
    }

    fun updateLabel(id: UUID, newLabel: String) {
        val entries = getPairingEntries().map { if (it.id == id) it.copy(label = newLabel) else it }
        savePairingEntries(entries)
    }

    fun deletePairingEntry(id: UUID) {
        val entries = getPairingEntries().filterNot { it.id == id }
        savePairingEntries(entries)
    }

    private fun savePairingEntries(entries: List<PairingEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PAIRING_ENTRIES, array.toString()).apply()
    }

    // --- Parent-side: AuthorizedChild list ---

    fun getAuthorizedChildren(): List<AuthorizedChild> {
        val raw = prefs.getString(KEY_AUTHORIZED_CHILDREN, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { AuthorizedChild.fromJson(array.getJSONObject(it)) }
    }

    fun addAuthorizedChild(child: AuthorizedChild) {
        val children = getAuthorizedChildren().toMutableList()
        children.add(child)
        val array = JSONArray()
        children.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_AUTHORIZED_CHILDREN, array.toString()).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "pairing_secure_prefs"
        private const val KEY_PAIRING_ENTRIES = "pairing_entries_v1"
        private const val KEY_AUTHORIZED_CHILDREN = "authorized_children_v1"
    }
}
