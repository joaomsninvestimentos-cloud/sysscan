package com.sysscan.repair.diag

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder

class AppDiagnostic(private val context: Context) {

    private val packageManager = context.packageManager

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()
        val packages = try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            emptyList()
        }

        val userApps = packages.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        val systemApps = packages.filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }

        results.add(
            if (packages.isEmpty()) {
                ScanCheckBuilder.warning(
                    "apps_total", ScanCategory.APPS, "Aplicativos instalados",
                    "Não foi possível listar os aplicativos.", "apps_clear"
                )
            } else {
                ScanCheckBuilder.ok(
                    "apps_total", ScanCategory.APPS, "Aplicativos instalados",
                    "${packages.size} apps no total (${systemApps.size} do sistema, " +
                        "${userApps.size} instalados pelo usuário)."
                )
            }
        )

        results.add(checkSuspiciousPermissions(userApps))

        results.add(checkUnknownSources(userApps))

        results.add(
            if (userApps.size > 120) {
                ScanCheckBuilder.warning(
                    "apps_count", ScanCategory.APPS, "Muitos aplicativos instalados",
                    "$userApps apps de usuário. Muitos apps aumentam o uso de recursos e o risco de conflitos.",
                    "apps_cleanup"
                )
            } else {
                ScanCheckBuilder.ok(
                    "apps_count", ScanCategory.APPS, "Volume de aplicativos",
                    "$userApps apps instalados pelo usuário."
                )
            }
        )

        return results
    }

    private fun checkSuspiciousPermissions(userApps: List<ApplicationInfo>): ScanCheck {
        var flagged = 0
        val examples = mutableListOf<String>()

        for (app in userApps) {
            val pi: PackageInfo = try {
                packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            } catch (_: Exception) {
                continue
            }
            val perms = pi.requestedPermissions ?: continue
            val dangerous = perms.count { it.startsWith("android.permission.") && isSensitive(it) }
            if (dangerous >= 6) {
                flagged++
                if (examples.size < 3) {
                    examples.add(packageManager.getApplicationLabel(app).toString())
                }
            }
        }

        return when {
            flagged == 0 -> ScanCheckBuilder.ok(
                "apps_permissions", ScanCategory.APPS, "Permissões dos apps",
                "Nenhum app solicitou uma quantidade excessiva de permissões sensíveis."
            )
            flagged <= 2 -> ScanCheckBuilder.info(
                "apps_permissions", ScanCategory.APPS, "Permissões dos apps",
                "$flagged app(s) solicitam muitas permissões sensíveis: ${examples.joinToString(", ")}."
            )
            else -> ScanCheckBuilder.warning(
                "apps_permissions", ScanCategory.APPS, "Permissões excessivas",
                "$flagged apps solicitam muitas permissões sensíveis (ex.: ${examples.joinToString(", ")}). " +
                    "Revise as permissões para reduzir riscos.", "apps_permission_fix"
            )
        }
    }

    private fun isSensitive(permission: String): Boolean = when (permission) {
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO" -> true
        else -> false
    }

    private fun checkUnknownSources(userApps: List<ApplicationInfo>): ScanCheck {
        val unknown = userApps.filter { isFromUnknownSource(it) }
        return if (unknown.isEmpty()) {
            ScanCheckBuilder.ok(
                "apps_unknown_src", ScanCategory.APPS, "Origem dos aplicativos",
                "Nenhum app detectado fora de fontes conhecidas."
            )
        } else {
            ScanCheckBuilder.info(
                "apps_unknown_src", ScanCategory.APPS, "Aplicativos de fontes externas",
                "${unknown.size} app(s) instalados de fontes fora da Play Store: " +
                    unknown.take(3).joinToString { it.packageName }.let {
                        if (unknown.size > 3) "$it e mais ${unknown.size - 3}" else it
                    }
            )
        }
    }

    private fun isFromUnknownSource(app: ApplicationInfo): Boolean {
        return try {
            val source = if (android.os.Build.VERSION.SDK_INT >= 30) {
                packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                app.publicSourceDir
            }
            when {
                source.isNullOrBlank() -> false
                source == "com.android.vending" -> false
                source == "com.google.android.packageinstaller" -> false
                source == "com.google.android.permissioncontroller" -> false
                else -> true
            }
        } catch (_: Exception) {
            false
        }
    }
}
