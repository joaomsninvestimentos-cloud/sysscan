package com.sysscan.repair.diag.battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda o último relatório de consumo por app. A varredura grava aqui e a tela de
 * bateria / assistente leem daqui, então o resultado continua disponível sem refazer
 * toda a consulta ao sistema.
 */
object BatteryUsageStore {

    private const val PREFS = "battery_usage"
    private const val KEY_REPORT = "last_report"
    private const val MAX_APPS = 60

    fun save(context: Context, report: BatteryUsageReport) {
        val json = toJson(report)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REPORT, json.toString())
            .apply()
    }

    fun load(context: Context): BatteryUsageReport? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REPORT, null) ?: return null
        return try {
            fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    private fun toJson(report: BatteryUsageReport): JSONObject {
        val apps = JSONArray()
        report.apps.take(MAX_APPS).forEach { app ->
            apps.put(
                JSONObject().apply {
                    put("pkg", app.packageName)
                    put("label", app.label)
                    put("system", app.isSystemApp)
                    put("fg", app.foregroundMs)
                    put("sessions", app.sessions)
                    put("last", app.lastUsedAt)
                    put("mobile", app.mobileBytes)
                    put("wifi", app.wifiBytes)
                    put("weight", app.weight)
                    put("share", app.sharePercent)
                    put("mah", app.estimatedMah ?: -1.0)
                    put("bg", app.backgroundSuspect)
                    put("doze", app.ignoresOptimizations)
                }
            )
        }
        val notes = JSONArray()
        report.notes.forEach { notes.put(it) }

        return JSONObject().apply {
            put("ts", report.generatedAt)
            put("window", report.windowHours)
            put("granted", report.usageAccessGranted)
            put("apps", apps)
            put("notes", notes)
            put("mahPerHour", report.observedMahPerHour ?: -1.0)
            put("windowMah", report.windowMah ?: -1.0)
            put("live", liveToJson(report.live))
        }
    }

    private fun liveToJson(live: BatteryLiveReading): JSONObject = JSONObject().apply {
        put("drain", live.drainMa ?: -1.0)
        put("level", live.levelPercent)
        put("charging", live.charging)
        put("plugged", live.plugged)
        put("temp", live.temperatureC ?: -1.0)
        put("charge", live.chargeCounterUah ?: -1L)
        put("autonomy", live.autonomyHours ?: -1.0)
        put("at", live.readAt)
        put("samples", live.samples)
    }

    private fun fromJson(obj: JSONObject): BatteryUsageReport {
        val appsArray = obj.optJSONArray("apps") ?: JSONArray()
        val apps = (0 until appsArray.length()).mapNotNull { index ->
            val item = appsArray.optJSONObject(index) ?: return@mapNotNull null
            val mah = item.optDouble("mah", -1.0)
            AppBatteryUsage(
                packageName = item.optString("pkg"),
                label = item.optString("label"),
                isSystemApp = item.optBoolean("system", false),
                foregroundMs = item.optLong("fg", 0L),
                sessions = item.optInt("sessions", 0),
                lastUsedAt = item.optLong("last", 0L),
                mobileBytes = item.optLong("mobile", 0L),
                wifiBytes = item.optLong("wifi", 0L),
                weight = item.optDouble("weight", 0.0),
                sharePercent = item.optDouble("share", 0.0),
                estimatedMah = if (mah > 0.0) mah else null,
                backgroundSuspect = item.optBoolean("bg", false),
                ignoresOptimizations = item.optBoolean("doze", false)
            )
        }
        val notesArray = obj.optJSONArray("notes") ?: JSONArray()
        val notes = (0 until notesArray.length()).map { notesArray.optString(it) }
        val mahPerHour = obj.optDouble("mahPerHour", -1.0)
        val windowMah = obj.optDouble("windowMah", -1.0)

        return BatteryUsageReport(
            generatedAt = obj.optLong("ts", 0L),
            windowHours = obj.optInt("window", 24),
            usageAccessGranted = obj.optBoolean("granted", false),
            apps = apps,
            live = liveFromJson(obj.optJSONObject("live")),
            observedMahPerHour = if (mahPerHour > 0.0) mahPerHour else null,
            windowMah = if (windowMah > 0.0) windowMah else null,
            notes = notes
        )
    }

    private fun liveFromJson(obj: JSONObject?): BatteryLiveReading {
        if (obj == null) return BatteryLiveReading()
        val drain = obj.optDouble("drain", -1.0)
        val temp = obj.optDouble("temp", -1.0)
        val autonomy = obj.optDouble("autonomy", -1.0)
        val charge = obj.optLong("charge", -1L)
        return BatteryLiveReading(
            drainMa = if (drain > 0.0) drain else null,
            levelPercent = obj.optInt("level", -1),
            charging = obj.optBoolean("charging", false),
            plugged = obj.optBoolean("plugged", false),
            temperatureC = if (temp > 0.0) temp else null,
            chargeCounterUah = if (charge > 0) charge else null,
            autonomyHours = if (autonomy > 0.0) autonomy else null,
            readAt = obj.optLong("at", 0L),
            samples = obj.optInt("samples", 1)
        )
    }
}
