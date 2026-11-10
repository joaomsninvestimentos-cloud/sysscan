package com.sysscan.repair.battery

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.sysscan.repair.diag.battery.AppBatteryUsage
import com.sysscan.repair.diag.battery.BatteryLiveReading
import com.sysscan.repair.diag.battery.BatteryUsageReport
import com.sysscan.repair.util.Formatters
import java.util.Locale

/**
 * "Modo chamada": mostra, com número, quanto cada app de chamada de vídeo (Zoom, Meet,
 * Teams, WhatsApp...) está pesando e o que mais economiza.
 *
 * Por que chamada de vídeo gasta tanto: câmera ligada, tela acesa, codificação de vídeo,
 * cancelamento de eco e o rádio transmitindo em tempo real. Áudio-only derruba o maior
 * bloco desse consumo.
 *
 * O SysScan só **mede e orienta**: não altera configurações do sistema (brilho, modo de
 * tela etc.). As ações levam o usuário à tela certa do Android, que ele mesmo ajusta.
 */
object CallModeHelper {

    /** Apps de chamada conhecidos (não é lista fechada: a heurística pega os demais). */
    private val KNOWN_CALL_PACKAGES = setOf(
        "us.zoom.videomeetings",                    // Zoom
        "com.google.android.apps.meetings",         // Google Meet
        "com.google.android.apps.tachyon",          // Google Meet (Duo)
        "com.microsoft.teams",                      // Microsoft Teams
        "com.microsoft.teams2",                     // Microsoft Teams (novo)
        "com.skype.raider",                         // Skype
        "com.whatsapp", "com.whatsapp.w4b",         // WhatsApp
        "org.telegram.messenger", "org.telegram.messenger.web",
        "com.discord",                              // Discord
        "com.facebook.orca",                        // Messenger
        "com.cisco.webex.meetings",                 // Webex
        "org.jitsi.meet",                           // Jitsi
        "com.viber.voip",                           // Viber
        "com.imo.android.imoim",                    // imo
        "com.slack",                                // Slack (huddles)
        "com.tencent.mm",                           // WeChat
        "com.linkedin.android"                      // LinkedIn (vídeo)
    )

    data class CallApp(
        val packageName: String,
        val label: String,
        /** Consumo medido na última análise de bateria, quando disponível. */
        val usage: AppBatteryUsage?,
        /** Isento da otimização (Doze) — pode gastar mesmo fechado. */
        val ignoresOptimizations: Boolean,
        val cameraAndMic: Boolean
    ) {
        val share: Double get() = usage?.sharePercent ?: 0.0

        val shareLabel: String
            get() = if (usage == null) "sem uso registrado" else
                String.format(Locale.US, "%.0f%% do consumo", share)

        val detailLabel: String
            get() = buildString {
                append(shareLabel)
                usage?.let { used ->
                    append(" · ").append(used.usageLabel()).append(" em uso")
                    if (used.totalBytes > 0) append(" · ").append(used.dataLabel())
                    used.estimatedMah?.let { append(" · ≈ ").append(Formatters.milliampHours(it)) }
                }
                if (ignoresOptimizations) append(" · fora do Doze")
            }
    }

    /** Apps de chamada encontrados no aparelho, ordenados pelo consumo medido. */
    fun callApps(context: Context, report: BatteryUsageReport?): List<CallApp> {
        val packageManager = context.packageManager
        val usageByPackage = report?.apps?.associateBy { it.packageName }.orEmpty()

        val candidates = LinkedHashSet<String>()
        candidates.addAll(KNOWN_CALL_PACKAGES)
        candidates.addAll(heuristicPackages(context))

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val result = mutableListOf<CallApp>()
        for (packageName in candidates) {
            val info = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (_: Exception) {
                null
            } ?: continue
            val hasLauncher = packageManager.getLaunchIntentForPackage(packageName) != null
            val known = packageName in KNOWN_CALL_PACKAGES
            // Apps de sistema sem ícone e desconhecidos entram só se forem da lista conhecida.
            if (!known && !hasLauncher) continue

            val label = try {
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                packageName
            }
            val ignores = try {
                powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
            } catch (_: Exception) {
                false
            }
            result.add(
                CallApp(
                    packageName = packageName,
                    label = label,
                    usage = usageByPackage[packageName],
                    ignoresOptimizations = ignores,
                    cameraAndMic = hasCameraAndMic(context, packageName)
                )
            )
        }
        return result.sortedWith(
            compareByDescending<CallApp> { it.usage != null }
                .thenByDescending { it.share }
                .thenBy { it.label.lowercase() }
        )
    }

    /** Soma da fatia de consumo dos apps de chamada. */
    fun callSharePercent(apps: List<CallApp>): Double = apps.sumOf { it.share }

    /** Apps de chamada com uso registrado (os que realmente pesaram). */
    fun activeCallApps(apps: List<CallApp>): List<CallApp> = apps.filter { it.usage != null }

    /** Checklist curto e específico, com os números do aparelho. */
    fun tips(live: BatteryLiveReading, apps: List<CallApp>): String {
        val active = activeCallApps(apps)
        val sb = StringBuilder()

        if (active.isNotEmpty()) {
            sb.append("Chamadas na última janela: ")
            sb.append(active.take(3).joinToString("; ") { "${it.label} (${it.shareLabel})" })
            sb.append(".\n\n")
        }

        sb.append("O que mais economia traz, na ordem:\n")
        sb.append("1. Desligue a câmera ou use só áudio — corta o maior bloco de consumo.\n")
        sb.append("2. Baixe o brilho da tela durante a chamada.\n")
        sb.append("3. Prefira Wi-Fi; com 5G e sinal fraco o rádio gasta muito mais.\n")
        sb.append("4. Desative fundo virtual, desfoque e filtros (consomem GPU a cada frame).\n")
        sb.append("5. Evite carregar durante a chamada: esquenta e acelera o desgaste.\n")
        sb.append("6. Depois da chamada, restrinja a bateria dos apps que ficam acordando o aparelho.\n")

        if (live.charging) {
            sb.append("\nO aparelho está carregando: a medição em mA só vale fora do carregador.")
        } else if (live.hasDrain) {
            sb.append("\nConsumo agora: ").append(live.drainLabel())
            live.autonomyHours?.let { sb.append(" · autonomia estimada ").append(live.autonomyLabel()) }
            sb.append(". Abra o app de chamada e toque em Medir para comparar.")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ atalhos

    fun networkSettingsIntent(): Intent =
        Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appBatterySettingsIntent(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ heurística

    /**
     * Apps instalados pelo usuário que pedem câmera **e** microfone **e** internet:
     * na prática, todo app capaz de fazer chamada de vídeo.
     */
    private fun heuristicPackages(context: Context): Set<String> {
        val packageManager = context.packageManager
        val packages = try {
            packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Exception) {
            return emptySet()
        }
        val result = mutableSetOf<String>()
        for (pkg in packages) {
            val app: ApplicationInfo = pkg.applicationInfo ?: continue
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) continue
            val permissions = pkg.requestedPermissions?.toSet().orEmpty()
            val camera = "android.permission.CAMERA" in permissions
            val mic = "android.permission.RECORD_AUDIO" in permissions
            val internet = "android.permission.INTERNET" in permissions
            if (camera && mic && internet) result.add(pkg.packageName)
        }
        return result
    }

    private fun hasCameraAndMic(context: Context, packageName: String): Boolean = try {
        val permissions = context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
        "android.permission.CAMERA" in permissions &&
            "android.permission.RECORD_AUDIO" in permissions
    } catch (_: Exception) {
        false
    }
}
