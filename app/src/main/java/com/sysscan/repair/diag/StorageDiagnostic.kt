package com.sysscan.repair.diag

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.root.RootChecker
import com.sysscan.repair.util.Formatters

class StorageDiagnostic(
    private val context: Context,
    private val hasRoot: Boolean
) {

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

        results.add(checkAppCache(hasRoot))
        results.add(checkFsync())

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

    private fun checkAppCache(hasRoot: Boolean): ScanCheck {
        val ownCache = try {
            context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }

        if (!hasRoot) {
            val threshold = 300L * 1024 * 1024
            return if (ownCache > threshold) {
                ScanCheckBuilder.warning(
                    "storage_cache", ScanCategory.STORAGE, "Cache do app elevado",
                    "Este app acumulou ${Formatters.bytes(ownCache)} em cache. " +
                        "Sem root, limpe manualmente em Configurações.", "storage_clean"
                )
            } else {
                ScanCheckBuilder.ok(
                    "storage_cache", ScanCategory.STORAGE, "Cache de aplicativos",
                    "Cache deste app: ${Formatters.bytes(ownCache)}. Para medir todos os apps é necessário root."
                )
            }
        }

        val totalCache = totalSystemCache()
        return when {
            totalCache <= 0L -> ScanCheckBuilder.info(
                "storage_cache", ScanCategory.STORAGE, "Cache de aplicativos",
                "Não foi possível medir o cache dos apps."
            )
            totalCache > 500L * 1024 * 1024 -> ScanCheckBuilder.warning(
                "storage_cache", ScanCategory.STORAGE, "Cache de apps elevado",
                "Os apps acumularam ${Formatters.bytes(totalCache)} em cache, ocupando espaço " +
                    "e podendo causar lentidão.", "storage_clean_root"
            )
            else -> ScanCheckBuilder.ok(
                "storage_cache", ScanCategory.STORAGE, "Cache de aplicativos",
                "${Formatters.bytes(totalCache)} em cache. Dentro do esperado."
            )
        }
    }

    private fun totalSystemCache(): Long {
        val result = RootChecker.executeAsRoot(
            "du -cs /data/data/*/cache /data/user/*/*/cache 2>/dev/null | tail -n 1",
            timeoutSeconds = 20
        )
        if (!result.success) return -1L
        val lastLine = result.stdout.lines().lastOrNull()?.trim() ?: return -1L
        val kb = lastLine.substringBefore('\t').toLongOrNull() ?: return -1L
        return kb * 1024
    }

    private fun checkFsync(): ScanCheck {
        val result = RootChecker.executeAsRoot("sync && echo ok", timeoutSeconds = 10)
        return if (result.success) {
            ScanCheckBuilder.ok(
                "storage_fs", ScanCategory.STORAGE, "Integridade do sistema de arquivos",
                "Sincronização do sistema de arquivos concluída."
            )
        } else {
            ScanCheckBuilder.info(
                "storage_fs", ScanCategory.STORAGE, "Integridade do sistema de arquivos",
                "Não foi possível sincronizar (requer root): ${result.stdout}"
            )
        }
    }
}
