package com.sysscan.repair.diag.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * Mede o consumo **do aparelho inteiro neste instante**.
 *
 * Usa as propriedades públicas do [BatteryManager]:
 *  - `BATTERY_PROPERTY_CURRENT_NOW`: corrente em µA (a maioria dos aparelhos expõe);
 *  - `BATTERY_PROPERTY_CHARGE_COUNTER`: carga restante em µAh;
 *  - `BATTERY_PROPERTY_CAPACITY`: nível em %.
 *
 * Nenhuma permissão especial é necessária. Em aparelhos que não expõem CURRENT_NOW a
 * leitura volta com `drainMa = null` e a interface mostra apenas nível/temperatura.
 */
object BatteryLiveMeter {

    fun read(context: Context): BatteryLiveReading {
        val batteryManager =
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val level = readingLevel(batteryManager, sticky)
        val temperature = sticky
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it > 0 }
            ?.let { it / 10.0 }

        val rawCurrent = batteryManager?.let {
            intProperty(it, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }
        val drainMa = currentToMillis(rawCurrent)

        val chargeCounter = batteryManager?.let {
            intProperty(it, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }?.toLong()?.takeIf { it > 0 }

        val autonomy = autonomyHours(chargeCounter, drainMa, level, charging)

        return BatteryLiveReading(
            drainMa = drainMa,
            levelPercent = level,
            charging = charging,
            plugged = plugged != 0,
            temperatureC = temperature,
            chargeCounterUah = chargeCounter,
            autonomyHours = if (charging) null else autonomy,
            samples = 1
        )
    }

    /**
     * Média de várias leituras espaçadas — o `CURRENT_NOW` oscila muito (picos de CPU/rádio),
     * então a média dá um número mais estável. Bloqueante: chame em thread de I/O.
     */
    fun measure(context: Context, seconds: Int = 2): BatteryLiveReading {
        val samples = mutableListOf<BatteryLiveReading>()
        val rounds = 4
        val sleepMs = (seconds * 1000L / rounds).coerceAtLeast(250L)
        repeat(rounds) { index ->
            samples.add(read(context))
            if (index < rounds - 1) {
                runCatching { Thread.sleep(sleepMs) }
            }
        }
        val base = samples.last()
        val drainValues = samples.mapNotNull { it.drainMa }
        if (drainValues.isEmpty()) {
            return base.copy(samples = samples.size)
        }
        // Descarta o maior e o menor quando há amostras suficientes: reduz picos pontuais.
        val trimmed = if (drainValues.size >= 3) {
            drainValues.sorted().subList(1, drainValues.size - 1)
        } else {
            drainValues
        }
        val average = trimmed.average()
        val autonomy = autonomyHours(
            base.chargeCounterUah, average, base.levelPercent, base.charging
        )
        return base.copy(
            drainMa = average,
            autonomyHours = if (base.charging) null else autonomy,
            samples = samples.size
        )
    }

    /**
     * Normaliza o valor de CURRENT_NOW. A maioria dos aparelhos reporta µA
     * (ex.: 480000 = 480 mA), mas alguns fabricantes reportam mA direto.
     * O sinal varia (negativo = descarga em parte dos aparelhos), por isso usamos o módulo.
     */
    private fun currentToMillis(raw: Int?): Double? {
        if (raw == null) return null
        if (raw == 0 || raw == Int.MIN_VALUE || raw == Int.MAX_VALUE) return null
        val magnitude = abs(raw).toDouble()
        if (magnitude < 1.0) return null
        // Valores abaixo de 20.000 (20.000 µA = 20 mA) indicam que a unidade já é mA.
        return if (magnitude < 20_000.0) magnitude else magnitude / 1000.0
    }

    private fun autonomyHours(
        chargeCounterUah: Long?,
        drainMa: Double?,
        levelPercent: Int,
        charging: Boolean
    ): Double? {
        if (charging) return null
        if (drainMa == null || drainMa < 1.0) return null
        if (chargeCounterUah == null || chargeCounterUah <= 0) return null
        val remainingMah = chargeCounterUah / 1000.0
        if (remainingMah <= 0.0) return null
        return remainingMah / drainMa
    }

    private fun readingLevel(batteryManager: BatteryManager?, sticky: Intent?): Int {
        val propertyValue = batteryManager?.let {
            intProperty(it, BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }
        if (propertyValue != null && propertyValue in 0..100) return propertyValue
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) return (level * 100 / scale)
        return -1
    }

    private fun intProperty(batteryManager: BatteryManager, property: Int): Int? = try {
        val value = batteryManager.getIntProperty(property)
        if (value == Int.MIN_VALUE) null else value
    } catch (_: Exception) {
        null
    }
}
