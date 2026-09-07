package com.sysscan.repair.advisor

import android.content.Context
import com.sysscan.repair.model.ScanSeverity
import com.sysscan.repair.model.ScanSummary
import org.json.JSONArray
import org.json.JSONObject

data class ScanSnapshot(
    val score: Int,
    val hasRoot: Boolean,
    val critical: Int,
    val warning: Int,
    val ok: Int,
    val issues: List<Issue>
) {
    data class Issue(
        val title: String,
        val detail: String,
        val severity: String,
        val category: String
    )
}

object LastScanStore {

    private const val PREFS = "last_scan"
    private const val KEY = "snapshot"

    fun save(context: Context, summary: ScanSummary) {
        val issues = summary.checks
            .filter { it.severity == ScanSeverity.WARNING || it.severity == ScanSeverity.CRITICAL }
            .map {
                JSONObject().apply {
                    put("title", it.title)
                    put("detail", it.detail)
                    put("severity", it.severity.name)
                    put("category", it.category.name)
                }
            }
        val obj = JSONObject().apply {
            put("score", summary.score)
            put("root", summary.hasRoot)
            put("critical", summary.criticalCount)
            put("warning", summary.warningCount)
            put("ok", summary.okCount)
            put("issues", JSONArray(issues))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, obj.toString())
            .apply()
    }

    fun load(context: Context): ScanSnapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("issues") ?: JSONArray()
            val issues = (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                ScanSnapshot.Issue(
                    title = item.optString("title"),
                    detail = item.optString("detail"),
                    severity = item.optString("severity"),
                    category = item.optString("category")
                )
            }
            ScanSnapshot(
                score = obj.optInt("score"),
                hasRoot = obj.optBoolean("root"),
                critical = obj.optInt("critical"),
                warning = obj.optInt("warning"),
                ok = obj.optInt("ok"),
                issues = issues
            )
        } catch (_: Exception) {
            null
        }
    }
}
