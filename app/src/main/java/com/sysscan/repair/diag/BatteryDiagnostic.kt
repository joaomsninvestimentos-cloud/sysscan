package com.sysscan.repair.diag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import java.util.Locale

class BatteryDiagnostic(private val context: Context) {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()

        val batteryStatus = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val health = batteryStatus?.getIntExtra(
            BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN
        ) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING

        results.add(
            when {
                level < 0 -> ScanCheckBuilder.ok(
                    "battery_level", ScanCategory.BATTERY, "Nível da bateria",
                    "Não foi possível ler o nível."
                )
                level <= 15 -> ScanCheckBuilder.critical(
                    "battery_level", ScanCategory.BATTERY, "Bateria muito baixa",
                    "Nível em $level%. Conecte o carregador para evitar desligamentos inesperados.",
                    "battery_charge"
                )
                level <= 30 -> ScanCheckBuilder.warning(
                    "battery_level", ScanCategory.BATTERY, "Bateria abaixo de 30%",
                    "Nível em $level%. Considere carregar o aparelho.", "battery_charge"
                )
                else -> ScanCheckBuilder.ok(
                    "battery_level", ScanCategory.BATTERY, "Nível da bateria",
                    "Nível em $level%."
                )
            }
        )

        results.add(
            when {
                temperature > 450 -> ScanCheckBuilder.critical(
                    "battery_temp", ScanCategory.BATTERY, "Superaquecimento da bateria",
                    "Temperatura em ${celsius(temperature)}. Risco de danos e instabilidade."
                )
                temperature > 400 -> ScanCheckBuilder.warning(
                    "battery_temp", ScanCategory.BATTERY, "Temperatura elevada",
                    "Temperatura em ${celsius(temperature)}. Evite uso intenso enquanto esquenta."
                )
                else -> ScanCheckBuilder.ok(
                    "battery_temp", ScanCategory.BATTERY, "Temperatura da bateria",
                    "Temperatura em ${celsius(temperature)}."
                )
            }
        )

        results.add(
            when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD,
                BatteryManager.BATTERY_HEALTH_UNKNOWN -> ScanCheckBuilder.ok(
                    "battery_health", ScanCategory.BATTERY, "Saúde da bateria",
                    "Bateria em boas condições."
                )
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> ScanCheckBuilder.critical(
                    "battery_health", ScanCategory.BATTERY, "Bateria superaquecida",
                    "O sistema reporta superaquecimento. Aguarde o resfriamento antes de uso intenso."
                )
                else -> ScanCheckBuilder.warning(
                    "battery_health", ScanCategory.BATTERY, "Saúde da bateria reduzida",
                    "O sistema reporta saúde da bateria fora do padrão."
                )
            }
        )

        results.add(
            if (isCharging) {
                ScanCheckBuilder.ok(
                    "battery_charging", ScanCategory.BATTERY, "Carregamento",
                    "Bateria em carregamento."
                )
            } else {
                ScanCheckBuilder.info(
                    "battery_charging", ScanCategory.BATTERY, "Carregamento",
                    "Bateria não está carregando no momento."
                )
            }
        )

        return results
    }

    private fun celsius(milliCelsius: Int): String =
        String.format(Locale.US, "%.1f°C", milliCelsius / 10.0)
}
