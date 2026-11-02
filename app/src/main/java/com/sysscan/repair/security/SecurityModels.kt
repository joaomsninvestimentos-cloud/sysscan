package com.sysscan.repair.security

import java.util.Locale

/** Nível de risco de um aplicativo. */
enum class RiskLevel(val label: String) {
    CLEAN("Limpo"),
    LOW("Baixo"),
    MEDIUM("Médio"),
    HIGH("Alto"),
    CRITICAL("Crítico")
}

/**
 * Um indício encontrado em um app. Cada indício tem um peso em pontos; a soma (limitada a
 * 100) vira o score de risco do aplicativo.
 */
data class RiskSignal(
    val id: String,
    val title: String,
    val detail: String,
    val points: Int
)

/** Resultado da consulta de um APK no VirusTotal (hash SHA-256). */
data class VtVerdict(
    val malicious: Int,
    val suspicious: Int,
    val harmless: Int,
    val undetected: Int,
    val permalink: String,
    val checkedAt: Long,
    val found: Boolean = true
) {
    val engines: Int get() = malicious + suspicious + harmless + undetected

    val isBad: Boolean get() = malicious > 0 || suspicious > 0

    fun summary(): String = when {
        !found -> "APK ainda não conhecido pelo VirusTotal (nenhum motor analisou este arquivo)."
        engines == 0 -> "VirusTotal sem dados para este APK."
        isBad -> "$malicious motor(es) detectaram malware e $suspicious marcaram como suspeito " +
            "de $engines."
        else -> "Nenhum dos $engines motores detectou malware."
    }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("malicious", malicious)
        put("suspicious", suspicious)
        put("harmless", harmless)
        put("undetected", undetected)
        put("permalink", permalink)
        put("checkedAt", checkedAt)
        put("found", found)
    }

    companion object {
        fun fromJson(obj: org.json.JSONObject): VtVerdict = VtVerdict(
            malicious = obj.optInt("malicious"),
            suspicious = obj.optInt("suspicious"),
            harmless = obj.optInt("harmless"),
            undetected = obj.optInt("undetected"),
            permalink = obj.optString("permalink"),
            checkedAt = obj.optLong("checkedAt"),
            found = obj.optBoolean("found", true)
        )
    }
}

/** Ficha de segurança de um aplicativo instalado. */
data class AppSecurityReport(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val uid: Int,
    val versionName: String,
    val targetSdk: Int,
    val installerPackage: String?,
    val installerLabel: String,
    val apkPath: String,
    val apkSizeBytes: Long,
    val hasLauncher: Boolean,
    val enabled: Boolean,
    val debuggable: Boolean,
    val accessibilityService: Boolean,
    val notificationListener: Boolean,
    val keyboard: Boolean,
    val deviceAdmin: Boolean,
    val drawOverlays: Boolean,
    val dangerousPermissions: List<String>,
    val signals: List<RiskSignal>,
    val score: Int,
    val level: RiskLevel,
    val foregroundMs: Long = 0L,
    val batteryShare: Double? = null,
    val vt: VtVerdict? = null
) {
    /** App instalado pelo usuário que não tem ícone na tela nem na gaveta de apps. */
    val isHidden: Boolean get() = !hasLauncher && !isSystemApp

    val isSuspicious: Boolean get() = score >= SecurityScanReport.SUSPICIOUS_SCORE

    fun signalsText(): String = signals.joinToString(" · ") { it.title }

    fun permissionsLabel(): String = if (dangerousPermissions.isEmpty()) {
        "nenhuma permissão sensível"
    } else {
        dangerousPermissions.joinToString(", ") { it.removePrefix("android.permission.") }
    }

    fun batteryLabel(): String? {
        val share = batteryShare ?: return null
        if (share < 0.5) return null
        return String.format(Locale.US, "%.0f%% do consumo de bateria", share)
    }

    fun withVerdict(verdict: VtVerdict?): AppSecurityReport = copy(vt = verdict)
}

/** Resultado de uma varredura de segurança. */
data class SecurityScanReport(
    val scannedAt: Long,
    val totalApps: Int,
    val apps: List<AppSecurityReport>,
    val vtConfigured: Boolean,
    val notes: List<String> = emptyList()
) {
    val hiddenApps: List<AppSecurityReport> get() = apps.filter { it.isHidden }

    val accessibilityApps: List<AppSecurityReport>
        get() = apps.filter { it.accessibilityService || it.notificationListener || it.deviceAdmin }

    val criticalApps: List<AppSecurityReport>
        get() = apps.filter { it.level == RiskLevel.CRITICAL }

    val highApps: List<AppSecurityReport> get() = apps.filter { it.level == RiskLevel.HIGH }

    val suspiciousApps: List<AppSecurityReport> get() = apps.filter { it.isSuspicious }

    val isClean: Boolean get() = criticalApps.isEmpty() && highApps.isEmpty()

    /** Pior nível encontrado na varredura. */
    val worstLevel: RiskLevel
        get() = apps.maxOfOrNull { it.score }
            ?.let { score -> RiskRules.levelFor(score) } ?: RiskLevel.CLEAN

    fun topLabels(limit: Int = 3): String =
        apps.take(limit).joinToString(", ") { it.label }

    companion object {
        /** A partir daqui o app entra na lista de suspeitos. */
        const val SUSPICIOUS_SCORE = 20
    }
}
