package com.sysscan.repair.diag

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.util.Formatters

class StorageDiagnostic(private val context: Context) {

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()

        results.add(checkPartition(
            "storage_internal",
            "Armazenamento interno",
            Environment.getDataDirectory(),
            "storage_clean"
        ))

        val external = Environment.getExternalStorageDirectory()
        if (Environment.isExternalStorageEmulated() || external.exists()) {
            results.add(checkPartition(
                "storage_external",
                "Armazenamento externo",
                external,
                "storage_clean"
            ))
        }

        results.add(checkAppCache())

        return results
    }

    private fun checkPartition(
        id: String,
        label: String,
        path: java.io.File,
        fixId: String
    ): ScanCheck {
        val stat = try {
            StatFs(path.absolutePath)
        } catch (e: Exception) {
            return ScanCheckBuilder.info(
                id, ScanCategory.STORAGE, label, "Não foi possível acessar o volume."
            )
        }
        val total = stat.totalBytes
        val free = stat.availableBytes
        val freeRatio = if (total > 0) free.toDouble() / total.toDouble() else 0.0

        return when {
            freeRatio < 0.10 -> ScanCheckBuilder.critical(
                id, ScanCategory.STORAGE, "$label quase cheio",
                "Restam apenas ${Formatters.bytes(free)} de ${Formatters.bytes(total)}. " +
                    "A falta de espaço causa falhas de apps e do sistema.", fixId
            )
            freeRatio < 0.20 -> ScanCheckBuilder.warning(
                id, ScanCategory.STORAGE, "$label com pouco espaço",
                "${Formatters.bytes(free)} livres de ${Formatters.bytes(total)}.", fixId
            )
            else -> ScanCheckBuilder.ok(
                id, ScanCategory.STORAGE, label,
                "${Formatters.bytes(free)} livres de ${Formatters.bytes(total)}."
            )
        }
    }

    private fun checkAppCache(): ScanCheck {
        val ownCache = try {
            context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }

        val threshold = 300L * 1024 * 1024
        return if (ownCache > threshold) {
            ScanCheckBuilder.warning(
                "storage_cache", ScanCategory.STORAGE, "Cache do app elevado",
                "Este app acumulou ${Formatters.bytes(ownCache)} em cache. " +
                    "Limpe manualmente em Configurações > Armazenamento.", "storage_clean"
            )
        } else {
            ScanCheckBuilder.ok(
                "storage_cache", ScanCategory.STORAGE, "Cache de aplicativos",
                "Cache deste app: ${Formatters.bytes(ownCache)}. Dentro do esperado."
            )
        }
    }
}
