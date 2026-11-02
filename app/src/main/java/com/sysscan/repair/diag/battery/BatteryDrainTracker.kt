package com.sysscan.repair.diag.battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda amostras de nível/carga ao longo do tempo para estimar o consumo real do aparelho
 * em mAh por hora. Sem isso não dá para traduzir o "peso" dos apps em miliampere-hora.
 *
 * As amostras são leves (uma a cada abertura da tela de bateria/varredura) e ficam em
 * SharedPreferences. Nada sai do aparelho.
 */
object BatteryDrainTracker {

    private const val PREFS = "battery_samples"
    private const val KEY_SAMPLES = "samples"
    private const val MAX_SAMPLES = 240

    /** Intervalo mínimo entre duas amostras para considerar o cálculo confiável. */
    private const val MIN_SPAN_MS = 15 * 60 * 1000L

    /** Janela máxima considerada (acima disso a tela/aquecedor pode ter mudado muito). */
    private const val MAX_SPAN_MS = 12 * 60 * 60 * 1000L

    /** Intervalo mínimo para gravar uma nova amostra (evita encher o histórico à toa). */
    private const val RECORD_INTERVAL_MS = 5 * 60 * 1000L

    data class Sample(
        val ts: Long,
        val level: Int,
        val chargeUah: Long?,
        val charging: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", ts)
            put("level", level)
            put("charge", chargeUah ?: -1L)
            put("charging", charging)
        }

        companion object {
            fun fromJson(obj: JSONObject): Sample {
                val charge = obj.optLong("charge", -1L)
                return Sample(
                    ts = obj.optLong("ts"),
                    level = obj.optInt("level", -1),
                    chargeUah = if (charge > 0) charge else null,
                    charging = obj.optBoolean("charging", false)
                )
            }
        }
    }

    fun record(context: Context, reading: BatteryLiveReading) {
        if (reading.levelPercent < 0) return
        val samples = load(context).toMutableList()
        val last = samples.lastOrNull()
        val now = reading.readAt
        if (last != null && now - last.ts < RECORD_INTERVAL_MS &&
            last.charging == reading.charging
        ) {
            return
        }
        samples.add(
            Sample(
                ts = now,
                level = reading.levelPercent,
                chargeUah = reading.chargeCounterUah,
                charging = reading.charging
            )
        )
        while (samples.size > MAX_SAMPLES) {
            samples.removeAt(0)
        }
        save(context, samples)
    }

    /**
     * Consumo médio observado do aparelho, em mAh/h, a partir das duas amostras mais
     * distantes dentro da janela válida. `null` quando ainda não há dados suficientes.
     */
    fun observedMahPerHour(context: Context): Double? {
        val samples = load(context).filter { !it.charging && it.level >= 0 }
        if (samples.size < 2) return null
        val last = samples.last()
        val capacityUah = capacityUah(samples)

        for (index in samples.indices.reversed()) {
            val first = samples[index]
            if (first.ts >= last.ts) continue
            val spanMs = last.ts - first.ts
            if (spanMs < MIN_SPAN_MS) continue
            if (spanMs > MAX_SPAN_MS) break
            if (last.level > first.level) continue

            val hours = spanMs / 3_600_000.0
            val usedMah = when {
                first.chargeUah != null && last.chargeUah != null &&
                    first.chargeUah > last.chargeUah ->
                    (first.chargeUah - last.chargeUah) / 1000.0

                capacityUah != null && first.level > last.level ->
                    ((first.level - last.level) / 100.0) * capacityUah / 1000.0

                else -> null
            }
            if (usedMah != null && usedMah > 0.0 && hours > 0.0) {
                return usedMah / hours
            }
        }
        return null
    }

    fun load(context: Context): List<Sample> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SAMPLES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { Sample.fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, samples: List<Sample>) {
        val array = JSONArray()
        samples.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAMPLES, array.toString())
            .apply()
    }

    /**
     * Capacidade estimada da bateria em µAh, deduzida de uma amostra com carga e nível
     * conhecidos (ex.: 1.750.000 µAh em 50% => ~3.500 mAh).
     */
    private fun capacityUah(samples: List<Sample>): Double? {
        for (sample in samples.asReversed()) {
            val charge = sample.chargeUah ?: continue
            if (charge > 0 && sample.level in 5..100) {
                return charge * 100.0 / sample.level
            }
        }
        return null
    }
}
