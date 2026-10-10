package com.ravy.assistant.reminders

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class RepeatType { NONE, DAILY, WEEKDAYS }

data class RavyReminder(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val hour: Int,
    val minute: Int,
    val repeat: RepeatType,
    var nextTriggerMillis: Long,
    var enabled: Boolean = true,
    var missed: Boolean = false
)

/** Real persisted storage for reminders/routines — JSON in plain SharedPreferences, viewable/editable/deletable from Hamburger -> Reminders & Routines. */
object RavyReminderStore {

    private const val PREFS_NAME = "ravy_reminders"
    private const val KEY_LIST = "reminders"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<RavyReminder> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RavyReminder(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    hour = o.getInt("hour"),
                    minute = o.getInt("minute"),
                    repeat = RepeatType.valueOf(o.getString("repeat")),
                    nextTriggerMillis = o.getLong("next"),
                    enabled = o.optBoolean("enabled", true),
                    missed = o.optBoolean("missed", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, reminders: List<RavyReminder>) {
        val arr = JSONArray()
        reminders.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id).put("label", r.label).put("hour", r.hour).put("minute", r.minute)
                    .put("repeat", r.repeat.name).put("next", r.nextTriggerMillis).put("enabled", r.enabled)
                    .put("missed", r.missed)
            )
        }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun add(context: Context, reminder: RavyReminder) {
        save(context, getAll(context) + reminder)
    }

    fun remove(context: Context, id: String) {
        save(context, getAll(context).filterNot { it.id == id })
    }

    fun update(context: Context, reminder: RavyReminder) {
        save(context, getAll(context).map { if (it.id == reminder.id) reminder else it })
    }
}
