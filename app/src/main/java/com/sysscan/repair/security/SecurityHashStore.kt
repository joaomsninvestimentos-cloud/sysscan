package com.sysscan.repair.security

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Guarda o SHA-256 dos APKs analisados e os laudos recebidos do VirusTotal. */
object SecurityHashStore {

    private const val PREFS = "security_scan"
    private const val KEY_HASHES = "hashes"
    private const val KEY_VERDICTS = "verdicts"
    private const val MAX_ENTRIES = 400

    data class HashEntry(val sha256: String, val size: Long, val path: String)

    fun hashes(context: Context): MutableMap<String, HashEntry> {
        val raw = prefs(context).getString(KEY_HASHES, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, HashEntry>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = obj.optJSONObject(key)
                if (item != null) {
                    val sha = item.optString("sha")
                    if (sha.isNotBlank()) {
                        map[key] = HashEntry(
                            sha256 = sha,
                            size = item.optLong("size"),
                            path = item.optString("path")
                        )
                    }
                }
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun saveHashes(context: Context, entries: Map<String, HashEntry>) {
        val trimmed = entries.entries.takeLast(MAX_ENTRIES)
        val obj = JSONObject()
        trimmed.forEach { (packageName, entry) ->
            obj.put(
                packageName,
                JSONObject().apply {
                    put("sha", entry.sha256)
                    put("size", entry.size)
                    put("path", entry.path)
                }
            )
        }
        prefs(context).edit().putString(KEY_HASHES, obj.toString()).apply()
    }

    /** Hash guardado para o app, se o APK no disco ainda tiver o mesmo tamanho. */
    fun cachedHash(context: Context, packageName: String, apk: File): String? {
        val entry = hashes(context)[packageName] ?: return null
        return if (entry.size == apk.length()) entry.sha256 else null
    }

    fun verdicts(context: Context): MutableMap<String, VtVerdict> {
        val raw = prefs(context).getString(KEY_VERDICTS, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, VtVerdict>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = obj.optJSONObject(key)
                if (item != null) {
                    map[key] = VtVerdict.fromJson(item)
                }
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun saveVerdict(context: Context, sha256: String, verdict: VtVerdict) {
        val all = verdicts(context)
        all[sha256] = verdict
        val obj = JSONObject()
        for ((key, value) in all.entries.takeLast(MAX_ENTRIES)) {
            obj.put(key, value.toJson())
        }
        prefs(context).edit().putString(KEY_VERDICTS, obj.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
