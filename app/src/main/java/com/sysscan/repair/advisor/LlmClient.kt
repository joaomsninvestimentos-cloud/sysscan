package com.sysscan.repair.advisor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object LlmClient {

    fun ask(
        context: Context,
        userMessage: String,
        snapshot: ScanSnapshot?,
        history: List<Pair<String, String>>
    ): String {
        val key = LlmSettings.apiKey(context)
        if (key.isBlank()) {
            throw IllegalStateException("Chave não configurada")
        }
        val base = LlmSettings.baseUrl(context).trimEnd('/')
        val model = LlmSettings.model(context)
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt(snapshot))
                })
                history.takeLast(8).forEach { (role, content) ->
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
        }
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.connectTimeout = 20_000
        conn.readTimeout = 45_000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("LLM HTTP $code")
        }
        val json = JSONObject(text)
        return json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
            .ifBlank { throw IllegalStateException("Resposta vazia") }
    }

    private fun systemPrompt(snapshot: ScanSnapshot?): String {
        val scan = if (snapshot == null) {
            "Ainda não há varredura salva."
        } else {
            buildString {
                append("Score ${snapshot.score}. Root=${snapshot.hasRoot}. ")
                append("OK=${snapshot.ok} Atenção=${snapshot.warning} Crítico=${snapshot.critical}. ")
                if (snapshot.issues.isNotEmpty()) {
                    append("Problemas: ")
                    snapshot.issues.forEach {
                        append("[${it.severity}] ${it.title}: ${it.detail}. ")
                    }
                }
            }
        }
        return "Você é o assistente do app Android SysScan. Responda em português, curto e prático. " +
            "Ajude a melhorar bateria, RAM, calor, armazenamento e estabilidade. " +
            "Não invente arquivos de sistema que o app não consegue restaurar. " +
            "Não peça root se não for necessário. Dados da última varredura: $scan"
    }
}
