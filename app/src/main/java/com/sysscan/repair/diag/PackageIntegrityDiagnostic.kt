package com.sysscan.repair.diag

import android.content.Context
import android.content.pm.PackageManager
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import java.io.File

class PackageIntegrityDiagnostic(private val context: Context) {

    fun check(): List<ScanCheck> {
        val pm = context.packageManager
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            emptyList()
        }

        var corrupt = 0
        val examples = mutableListOf<String>()
        var total = 0

        for (app in apps) {
            total++
            val source = app.sourceDir
            val ok = try {
                val f = File(source)
                f.exists() && f.length() > 0
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                corrupt++
                if (examples.size < 3) {
                    examples.add(
                        try {
                            pm.getApplicationLabel(app).toString()
                        } catch (e: Exception) {
                            app.packageName
                        }
                    )
                }
            }
        }

        val check = when {
            corrupt == 0 -> ScanCheckBuilder.ok(
                "pkg_integrity", ScanCategory.APPS, "Integridade dos aplicativos",
                "$total pacotes instalados verificados. Nenhum pacote corrompido."
            )
            corrupt <= 2 -> ScanCheckBuilder.warning(
                "pkg_integrity", ScanCategory.APPS, "Pacote corrompido",
                "$corrupt pacote(s) com arquivos ausentes ou inválidos: " +
                    examples.joinToString(", "), "pkg_fix"
            )
            else -> ScanCheckBuilder.critical(
                "pkg_integrity", ScanCategory.APPS, "Vários pacotes corrompidos",
                "$corrupt de $total pacotes estão corrompidos (ex.: ${examples.joinToString(", ")}). " +
                    "Isso causa falhas e travamentos.", "pkg_fix"
            )
        }

        return listOf(check)
    }
}
