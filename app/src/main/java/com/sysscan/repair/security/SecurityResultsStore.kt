package com.sysscan.repair.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Guarda o último resultado da varredura de segurança (para o assistente e a tela). */
object SecurityResultsStore {

    private const val PREFS = "security_results"
    private const val KEY_REPORT = "last_report"
    private const val MAX_APPS = 400

    fun save(context: Context, report: SecurityScanReport, hashIndex: Map<String, String>) {
        val apps = JSONArray()
        report.apps.take(MAX_APPS).forEach { app ->
            apps.put(
                JSONObject().apply {
                    put("pkg", app.packageName)
                    put("label", app.label)
                    put("sys", app.isSystemApp)
                    put("level", app.level.name)
                    put("score", app.score)
                    put("hidden", app.isHidden)
                    put("a11y", app.accessibilityService)
                    put("notif", app.notificationListener)
                    put("admin", app.deviceAdmin)
                    put("installer", app.installerLabel)
                    put("signals", JSONArray(app.signals.map { it.title }))
                }
            )
        }
        val hashObj = JSONObject()
        hashIndex.forEach { (pkg, sha) -> hashObj.put(pkg, sha) }

        val notes = JSONArray()
        report.notes.forEach { notes.put(it) }

        val json = JSONObject().apply {
            put("ts", report.scannedAt)
            put("total", report.totalApps)
            put("vt", report.vtConfigured)
            put("apps", apps)
            put("hashes", hashObj)
            put("notes", notes)
        }
        prefs(context).edit().putString(KEY_REPORT, json.toString()).apply()
    }

    fun load(context: Context): StoredScan? {
        val raw = prefs(context).getString(KEY_REPORT, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val appsArray = obj.optJSONArray("apps") ?: JSONArray()
            val apps = (0 until appsArray.length()).mapNotNull { index ->
                val item = appsArray.optJSONObject(index) ?: return@mapNotNull null
                StoredApp(
                    packageName = item.optString("pkg"),
                    label = item.optString("label"),
                    isSystemApp = item.optBoolean("sys"),
                    level = runCatching {
                        RiskLevel.valueOf(item.optString("level", "CLEAN"))
                    }.getOrDefault(RiskLevel.CLEAN),
                    score = item.optInt("score"),
                    hidden = item.optBoolean("hidden"),
                    accessibility = item.optBoolean("a11y"),
                    notificationListener = item.optBoolean("notif"),
                    deviceAdmin = item.optBoolean("admin"),
                    installer = item.optString("installer"),
                    signals = (0 until (item.optJSONArray("signals")?.length() ?: 0)).map { i ->
                        item.optJSONArray("signals")!!.optString(i)
                    }
                )
            }
            val hashObj = obj.optJSONObject("hashes")
            val hashes = mutableMapOf<String, String>()
            if (hashObj != null) {
                val hashKeys = hashObj.keys()
                while (hashKeys.hasNext()) {
                    val key = hashKeys.next()
                    hashes[key] = hashObj.optString(key)
                }
            }
            StoredScan(
                scannedAt = obj.optLong("ts"),
                totalApps = obj.optInt("total"),
                vtConfigured = obj.optBoolean("vt"),
                apps = apps,
                hashes = hashes
            )
        } catch (_: Exception) {
            null
        }
    }

    data class StoredApp(
        val packageName: String,
        val label: String,
        val isSystemApp: Boolean,
        val level: RiskLevel,
        val score: Int,
        val hidden: Boolean,
        val accessibility: Boolean,
        val notificationListener: Boolean,
        val deviceAdmin: Boolean,
        val installer: String,
        val signals: List<String>
    )

    data class StoredScan(
        val scannedAt: Long,
        val totalApps: Int,
        val vtConfigured: Boolean,
        val apps: List<StoredApp>,
        val hashes: Map<String, String>
    ) {
        val suspicious: List<StoredApp>
            get() = apps.filter { it.level == RiskLevel.MEDIUM || it.level == RiskLevel.HIGH ||
                it.level == RiskLevel.CRITICAL }

        val hidden: List<StoredApp> get() = apps.filter { it.hidden }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
