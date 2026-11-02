package com.sysscan.repair.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import com.sysscan.repair.diag.battery.BatteryUsageStore
import java.io.File

/**
 * Varredura de segurança do aparelho, **sem root**.
 *
 * O que ela realmente consegue ver:
 *  - todos os pacotes instalados, inclusive os que **não têm ícone** (apps ocultos);
 *  - quem instalou cada app (loja, instalação manual, outro app ou origem desconhecida);
 *  - permissões sensíveis pedidas por cada app;
 *  - quais apps têm **acessibilidade**, **leitura de notificações** ou **administrador do
 *    aparelho** ativos (os poderes usados por spyware/golpes);
 *  - caminho e tamanho do APK, se está corrompido, e o **SHA-256** (para consulta no
 *    VirusTotal);
 *  - cruza com o consumo de bateria medido pela própria varredura de bateria.
 *
 * O que **não** dá para fazer sem root: ler o conteúdo de todos os arquivos do sistema,
 * achar binários executáveis soltos em /data, ou garantir ausência total de malware.
 * Por isso a varredura aponta indícios e explica cada um.
 */
class SecurityScanner(private val context: Context) {

    private val packageManager = context.packageManager

    /** Hash completo do APK é caro: limitamos aos apps que importam para a análise. */
    private val hashLimit = 40

    /**
     * @param hashApks quando false, pula o cálculo de SHA-256 (usado na varredura geral, que
     *        precisa terminar rápido; a tela de segurança calcula os hashes sob demanda).
     */
    fun scan(
        hashApks: Boolean = true,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): SecurityScanReport {
        val packages = installedPackages()
        val launcherPackages = launcherPackages()
        val accessibilityPackages = enabledAccessibilityPackages()
        val notificationPackages = enabledNotificationListenerPackages()
        val adminPackages = activeAdminPackages()
        val batteryShares = batteryShares()

        val cachedHashes = SecurityHashStore.hashes(context)
        val hashCache = cachedHashes.toMutableMap()

        val drafts = mutableListOf<AppSecurityReport>()
        packages.forEachIndexed { index, packageInfo ->
            onProgress?.invoke(index, packages.size, "Analisando ${packageInfo.packageName}")
            val report = buildReport(
                packageInfo = packageInfo,
                launcherPackages = launcherPackages,
                accessibilityPackages = accessibilityPackages,
                notificationPackages = notificationPackages,
                adminPackages = adminPackages,
                batteryShares = batteryShares
            ) ?: return@forEachIndexed
            drafts.add(report)
        }

        // Hasheia primeiro os apps que já levantaram suspeita, os ocultos e os privilegiados.
        val targets = if (hashApks) {
            drafts
                .filter { it.isSuspicious || it.isHidden || it.accessibilityService }
                .sortedByDescending { it.score }
                .take(hashLimit)
        } else {
            emptyList()
        }
        if (targets.isNotEmpty()) {
            onProgress?.invoke(packages.size, packages.size, "Calculando assinaturas (SHA-256)")
        }
        targets.forEach { report ->
            val file = File(report.apkPath)
            if (report.apkPath.isBlank() || !file.exists()) return@forEach
            val sha = hashCache[report.packageName]
                ?.takeIf { it.size == file.length() }
                ?.sha256
                ?: ApkInspector.sha256(file)
            if (sha != null) {
                hashCache[report.packageName] =
                    SecurityHashStore.HashEntry(sha, file.length(), report.apkPath)
            }
        }
        runCatching { SecurityHashStore.saveHashes(context, hashCache) }

        val reports = drafts.sortedWith(
            compareByDescending<AppSecurityReport> { it.score }
                .thenByDescending { it.batteryShare ?: 0.0 }
                .thenBy { it.label.lowercase() }
        )

        return SecurityScanReport(
            scannedAt = System.currentTimeMillis(),
            totalApps = reports.size,
            apps = reports,
            vtConfigured = VtSettings.isConfigured(context),
            notes = buildNotes(reports)
        )
    }

    /** Aplica um laudo do VirusTotal ao relatório já exibido. */
    fun applyVerdict(report: SecurityScanReport, packageName: String, verdict: VtVerdict):
        SecurityScanReport {
        val updated = report.apps.map { app ->
            if (app.packageName == packageName) app.withVerdict(verdict) else app
        }
        return report.copy(apps = updated)
    }

    // ------------------------------------------------------------------ montagem

    private fun buildReport(
        packageInfo: PackageInfo,
        launcherPackages: Set<String>,
        accessibilityPackages: Set<String>,
        notificationPackages: Set<String>,
        adminPackages: Set<String>,
        batteryShares: Map<String, Double>
    ): AppSecurityReport? {
        val app: ApplicationInfo = packageInfo.applicationInfo ?: return null
        val packageName = packageInfo.packageName
        if (packageName == context.packageName) return null

        val isSystem = isSystemApp(app)
        val permissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        val installerPackage = installerOf(packageName)
        val installer = installerKind(installerPackage)
        val apkPath = app.publicSourceDir ?: app.sourceDir.orEmpty()

        val hasLauncher = packageName in launcherPackages
        val accessibility = packageName in accessibilityPackages
        val notifications = packageName in notificationPackages
        val admin = packageName in adminPackages
        val keyboard = hasServiceWithPermission(packageInfo, "android.permission.BIND_INPUT_METHOD")
        val batteryShare = batteryShares[packageName]

        val profile = RiskRules.Profile(
            packageName = packageName,
            label = labelOf(app),
            isSystemApp = isSystem,
            hasLauncher = hasLauncher,
            permissions = permissions,
            accessibilityEnabled = accessibility,
            notificationListener = notifications,
            isKeyboard = keyboard,
            deviceAdmin = admin,
            drawOverlays = "android.permission.SYSTEM_ALERT_WINDOW" in permissions,
            installer = installer,
            installerPackage = installerPackage,
            debuggable = (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            targetSdk = app.targetSdkVersion,
            apkPath = apkPath,
            batteryShare = batteryShare
        )

        val signals = RiskRules.evaluate(profile).toMutableList()

        val apkFile = if (apkPath.isNotBlank()) File(apkPath) else null
        val apkSize = apkFile?.let { ApkInspector.size(it) } ?: 0L
        val corruptedApk = apkFile != null && apkFile.exists() && apkSize > 0 &&
            !ApkInspector.looksLikeZip(apkFile)
        if (corruptedApk) {
            signals.add(
                RiskSignal(
                    "apk_corrupt", "APK inválido",
                    "O arquivo do app não é um APK válido (assinatura ZIP ausente). Pode ter " +
                        "sido trocado ou corrompido.", 25
                )
            )
        }

        val score = RiskRules.score(signals)
        return AppSecurityReport(
            packageName = packageName,
            label = labelOf(app),
            isSystemApp = isSystem,
            uid = app.uid,
            versionName = packageInfo.versionName.orEmpty(),
            targetSdk = app.targetSdkVersion,
            installerPackage = installerPackage,
            installerLabel = installerLabel(installerPackage),
            apkPath = apkPath,
            apkSizeBytes = apkSize,
            hasLauncher = hasLauncher,
            enabled = app.enabled,
            debuggable = (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            accessibilityService = accessibility,
            notificationListener = notifications,
            keyboard = keyboard,
            deviceAdmin = admin,
            drawOverlays = "android.permission.SYSTEM_ALERT_WINDOW" in permissions,
            dangerousPermissions = RiskRules.dangerousPermissions(permissions),
            signals = signals,
            score = score,
            level = RiskRules.levelFor(score),
            batteryShare = batteryShare
        )
    }

    // ------------------------------------------------------------------ dados do sistema

    @Suppress("DEPRECATION")
    private fun installedPackages(): List<PackageInfo> = try {
        packageManager.getInstalledPackages(
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
        )
    } catch (_: Exception) {
        try {
            packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Pacotes que têm ícone na tela inicial ou na gaveta de apps. */
    private fun launcherPackages(): Set<String> {
        val result = mutableSetOf<String>()
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO)
        ).forEach { intent ->
            runCatching {
                packageManager.queryIntentActivities(intent, flags)
                    .forEach { result.add(it.activityInfo.packageName) }
            }
        }
        return result
    }

    /** Apps com serviço de acessibilidade efetivamente ativo no sistema. */
    private fun enabledAccessibilityPackages(): Set<String> {
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        return raw.split(':')
            .mapNotNull { component ->
                val packageName = component.substringBefore('/').trim()
                packageName.ifBlank { null }
            }
            .toSet()
    }

    /** Apps com acesso a todas as notificações ativo. */
    private fun enabledNotificationListenerPackages(): Set<String> {
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
        }.getOrNull().orEmpty()
        return raw.split(':')
            .mapNotNull { component ->
                val packageName = component.substringBefore('/').trim()
                packageName.ifBlank { null }
            }
            .toSet()
    }

    /** Apps com administrador do aparelho ativo. */
    private fun activeAdminPackages(): Set<String> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptySet()
        return runCatching {
            dpm.activeAdmins?.map { it.packageName }?.toSet().orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun hasServiceWithPermission(packageInfo: PackageInfo, permission: String): Boolean {
        val services = packageInfo.services ?: return false
        return services.any { permission in (it.permission.orEmpty()) }
    }

    private fun batteryShares(): Map<String, Double> = try {
        BatteryUsageStore.load(context)?.apps
            ?.associate { it.packageName to it.sharePercent }
            .orEmpty()
    } catch (_: Exception) {
        emptyMap()
    }

    @Suppress("DEPRECATION")
    private fun installerOf(packageName: String): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            packageManager.getInstallerPackageName(packageName)
        }
    } catch (_: Exception) {
        null
    }

    private fun installerKind(installerPackage: String?): RiskRules.InstallerKind {
        if (installerPackage.isNullOrBlank()) return RiskRules.InstallerKind.UNKNOWN
        if (installerPackage in STORE_INSTALLERS) return RiskRules.InstallerKind.STORE
        val installerInfo = try {
            packageManager.getApplicationInfo(installerPackage, 0)
        } catch (_: Exception) {
            null
        } ?: return RiskRules.InstallerKind.UNKNOWN
        // Instaladores do próprio sistema (MIUI, Samsung, AOSP) = instalação manual de APK.
        return if (isSystemApp(installerInfo)) {
            RiskRules.InstallerKind.MANUAL
        } else {
            RiskRules.InstallerKind.THIRD_PARTY
        }
    }

    private fun installerLabel(installerPackage: String?): String {
        if (installerPackage.isNullOrBlank()) return "desconhecida"
        if (installerPackage in STORE_INSTALLERS) return "loja de aplicativos"
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(installerPackage, 0)
            ).toString()
        } catch (_: Exception) {
            installerPackage
        }
    }

    private fun isSystemApp(app: ApplicationInfo): Boolean =
        (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    private fun labelOf(app: ApplicationInfo): String = try {
        packageManager.getApplicationLabel(app).toString().ifBlank { app.packageName }
    } catch (_: Exception) {
        app.packageName
    }

    private fun buildNotes(reports: List<AppSecurityReport>): List<String> {
        val notes = mutableListOf<String>()
        notes.add(
            "A varredura não usa root e não lê arquivos do sistema: ela aponta indícios " +
                "(app oculto, permissões, acessibilidade, origem da instalação) para você decidir."
        )
        if (!VtSettings.isConfigured(context)) {
            notes.add(
                "Para verificar os APKs contra mais de 60 antivírus, configure a chave do " +
                    "VirusTotal na tela de segurança (opcional)."
            )
        }
        val unverifiable = reports.count { it.isSuspicious }
        if (unverifiable > 0) {
            notes.add("$unverifiable app(s) com indícios — confira a ficha de cada um abaixo.")
        }
        return notes
    }

    companion object {
        private val STORE_INSTALLERS = setOf(
            "com.android.vending",
            "com.google.android.feedback",
            "com.huawei.appmarket",
            "com.sec.android.app.samsungapps",
            "com.amazon.venezia",
            "com.xiaomi.market",
            "com.oppo.market",
            "com.heytap.market",
            "com.transsion.appstore",
            "com.bbk.appstore",
            "com.vivo.appstore"
        )

        /** Intent para os ajustes de acessibilidade do sistema. */
        fun accessibilitySettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
