package com.sysscan.repair.security

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Configuração do VirusTotal (chave do próprio usuário, guardada só no aparelho). */
object VtSettings {

    private const val PREFS = "vt_settings"
    private const val KEY_API = "vt_api_key"

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty()

    fun save(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API, apiKey.trim())
            .apply()
    }

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()
}

sealed class VtLookupResult {
    data class Done(val verdict: VtVerdict) : VtLookupResult()
    data class Failed(val reason: String, val quotaExceeded: Boolean = false) : VtLookupResult()
}

/**
 * Consulta o **VirusTotal** pelo SHA-256 do APK.
 *
 * Vantagens de consultar pelo hash: o arquivo **não sai do aparelho** (só o hash, que não
 * permite reconstruir o app) e a privacidade do usuário é preservada.
 *
 * A chave é gratuita (conta pessoal no virustotal.com) e tem limite de requisições por
 * minuto/dia — por isso o app só verifica os apps que levantaram suspeita.
 */
object VtClient {

    private const val BASE = "https://www.virustotal.com/api/v3/files/"

    /** Sem chave configurada, nada é enviado. */
    fun lookup(context: Context, sha256: String): VtLookupResult {
        val key = VtSettings.apiKey(context)
        if (key.isBlank()) {
            return VtLookupResult.Failed("Chave do VirusTotal não configurada.")
        }
        if (sha256.length != 64) {
            return VtLookupResult.Failed("Hash inválido para consulta.")
        }

        val cached = SecurityHashStore.verdicts(context)[sha256]
        if (cached != null &&
            System.currentTimeMillis() - cached.checkedAt < TimeUnit.DAYS.toMillis(7)
        ) {
            return VtLookupResult.Done(cached)
        }

        return try {
            val conn = URL(BASE + sha256).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("x-apikey", key)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            val code = conn.responseCode
            if (code == 404) {
                // Arquivo ainda não analisado pelo VirusTotal.
                val verdict = VtVerdict(
                    malicious = 0, suspicious = 0, harmless = 0, undetected = 0,
                    permalink = "https://www.virustotal.com/gui/file/$sha256",
                    checkedAt = System.currentTimeMillis(),
                    found = false
                )
                SecurityHashStore.saveVerdict(context, sha256, verdict)
                return VtLookupResult.Done(verdict)
            }
            if (code == 401) return VtLookupResult.Failed("Chave do VirusTotal inválida.")
            if (code == 429 || code == 403) {
                return VtLookupResult.Failed(
                    "Limite de consultas do VirusTotal atingido. Tente mais tarde.",
                    quotaExceeded = true
                )
            }
            if (code !in 200..299) {
                return VtLookupResult.Failed("VirusTotal respondeu HTTP $code.")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val verdict = parse(body, sha256)
                ?: return VtLookupResult.Failed("Resposta do VirusTotal não reconhecida.")
            SecurityHashStore.saveVerdict(context, sha256, verdict)
            VtLookupResult.Done(verdict)
        } catch (e: Exception) {
            VtLookupResult.Failed("Sem conexão com o VirusTotal: ${e.javaClass.simpleName}.")
        }
    }

    private fun parse(body: String, sha256: String): VtVerdict? = try {
        val attributes = JSONObject(body)
            .optJSONObject("data")
            ?.optJSONObject("attributes")
            ?: return null
        val stats = attributes.optJSONObject("last_analysis_stats")
        val malicious = stats?.optInt("malicious") ?: 0
        val suspicious = stats?.optInt("suspicious") ?: 0
        val harmless = stats?.optInt("harmless") ?: 0
        val undetected = stats?.optInt("undetected") ?: 0
        val permalink = attributes.optString(
            "permalink", "https://www.virustotal.com/gui/file/$sha256"
        )
        VtVerdict(
            malicious = malicious,
            suspicious = suspicious,
            harmless = harmless,
            undetected = undetected,
            permalink = permalink,
            checkedAt = System.currentTimeMillis()
        )
    } catch (_: Exception) {
        null
    }
}
