package com.sysscan.repair.advisor

import android.content.Context

object LlmSettings {

    private const val PREFS = "llm_settings"
    private const val KEY_API = "user_llm_api_key"
    private const val KEY_URL = "user_llm_base_url"
    private const val KEY_MODEL = "user_llm_model"

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty()

    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, "https://api.deepseek.com/v1").orEmpty()

    fun model(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, "deepseek-chat").orEmpty()

    fun save(context: Context, apiKey: String, baseUrl: String, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API, apiKey.trim())
            .putString(KEY_URL, baseUrl.trim().ifBlank { "https://api.deepseek.com/v1" })
            .putString(KEY_MODEL, model.trim().ifBlank { "deepseek-chat" })
            .apply()
    }

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()
}
