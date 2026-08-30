package com.sysscan.repair.diag

import android.content.Context
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.root.RootChecker

class SystemDiagnostics(private val context: Context, private val hasRoot: Boolean) {

    fun runAll(onProgress: (done: Int, total: Int, label: String) -> Unit): List<ScanCheck> {
        val checks = mutableListOf<ScanCheck>()
        val modules: List<Pair<String, () -> List<ScanCheck>>> = listOf(
            "Verificando bateria" to {
                BatteryDiagnostic(context).check()
            },
            "Verificando memória" to {
                MemoryDiagnostic(context).check()
            },
            "Verificando armazenamento" to {
                StorageDiagnostic(context, hasRoot).check()
            },
            "Verificando rede" to {
                NetworkDiagnostic(context).check()
            },
            "Analisando CPU" to {
                CpuDiagnostic().check()
            },
            "Verificando sensores" to {
                SensorsDiagnostic(context).check()
            },
            "Analisando aplicativos" to {
                AppDiagnostic(context).check()
            },
            "Verificando falhas recentes" to {
                CrashDiagnostic(context).check()
            },
            "Medindo bateria por app" to {
                UsageDiagnostic(context).check()
            },
            "Verificando integridade dos pacotes" to {
                PackageIntegrityDiagnostic(context).check()
            },
            "Verificando integridade do sistema" to {
                SystemIntegrityDiagnostic(hasRoot).check()
            },
            "Análise profunda (root)" to {
                if (hasRoot) RootDeepDiagnostic().check()
                else listOf(
                    ScanCheckBuilder.info(
                        "deep_root_unavailable", ScanCategory.SYSTEM, "Análise profunda",
                        "Esta análise exige acesso root. Desbloqueie o bootloader e instale " +
                            "Magisk/KernelSU para verificação avançada."
                    )
                )
            }
        )

        val totalModules = modules.size
        modules.forEachIndexed { index, (label, block) ->
            onProgress(index, totalModules, label)
            checks += try {
                block()
            } catch (e: Exception) {
                listOf(
                    ScanCheckBuilder.info(
                        "module_$index", ScanCategory.SYSTEM, label,
                        "Verificação interrompida: ${e.javaClass.simpleName}."
                    )
                )
            }
        }
        onProgress(totalModules, totalModules, "Varredura concluída")
        return checks
    }

    fun rootStatus(): Boolean = hasRoot && RootChecker.hasRootAccess()
}
