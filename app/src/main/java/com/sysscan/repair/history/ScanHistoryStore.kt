package com.sysscan.repair.history

import android.content.Context
import com.sysscan.repair.model.ScanSummary
import org.json.JSONArray
import org.json.JSONObject

data class ScanHistoryEntry(
    val timestamp: Long,
    val score: Int,
    val critical: Int,
    val warning: Int,
    val ok: Int,
    val hasRoot: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", timestamp)
        put("score", score)
        put("critical", critical)
        put("warning", warning)
        put("ok", ok)
        put("root", hasRoot)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScanHistoryEntry = ScanHistoryEntry(
            timestamp = obj.optLong("ts"),
            score = obj.optInt("score"),
            critical = obj.optInt("critical"),
            warning = obj.optInt("warning"),
            ok = obj.optInt("ok"),
            hasRoot = obj.optBoolean("root")
        )
    }
}

object ScanHistoryStore {

    private const val PREFS = "scan_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 20

    fun add(context: Context, summary: ScanSummary) {
        val entries = getAll(context).toMutableList()
        entries.add(
            0,
            ScanHistoryEntry(
                timestamp = System.currentTimeMillis(),
                score = summary.score,
                critical = summary.criticalCount,
                warning = summary.warningCount,
                ok = summary.okCount,
                hasRoot = summary.hasRoot
            )
        )
        if (entries.size > MAX_ENTRIES) {
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(entries.size - 1)
            }
        }
        save(context, entries)
    }

    fun getAll(context: Context): List<ScanHistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { ScanHistoryEntry.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, entries: List<ScanHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }
}
