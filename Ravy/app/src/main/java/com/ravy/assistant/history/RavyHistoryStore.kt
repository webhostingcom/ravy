package com.ravy.assistant.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val timestamp: Long, val userText: String, val ravyText: String)

/**
 * A real, persisted (not just in-memory) log of what was said and what Ravy
 * did/replied, so "Hamburger -> History" shows actual past turns rather than
 * a static placeholder. Stored as plain JSON in normal SharedPreferences —
 * deliberately not the encrypted store, since this is meant to be viewable
 * and clearable by the user like a normal history list, not a secret.
 *
 * Capped at the most recent 200 turns so it can't grow unbounded.
 */
object RavyHistoryStore {

    private const val PREFS_NAME = "ravy_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun add(context: Context, userText: String, ravyText: String) {
        val list = getAll(context).toMutableList()
        list.add(HistoryEntry(System.currentTimeMillis(), userText, ravyText))
        while (list.size > MAX_ENTRIES) list.removeAt(0)
        save(context, list)
    }

    fun getAll(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(o.getLong("t"), o.getString("u"), o.getString("r"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(context: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("t", e.timestamp).put("u", e.userText).put("r", e.ravyText))
        }
        prefs(context).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
