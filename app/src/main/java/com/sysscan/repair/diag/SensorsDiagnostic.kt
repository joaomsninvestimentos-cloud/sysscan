package com.sysscan.repair.diag

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder

class SensorsDiagnostic(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val essentialSensors = mapOf(
        Sensor.TYPE_ACCELEROMETER to "Acelerômetro",
        Sensor.TYPE_GYROSCOPE to "Giroscópio",
        Sensor.TYPE_PROXIMITY to "Sensor de proximidade",
        Sensor.TYPE_LIGHT to "Sensor de luz"
    )

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()
        val present = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val presentTypes = present.map { it.type }.toSet()

        for ((type, name) in essentialSensors) {
            results.add(
                if (type in presentTypes) {
                    ScanCheckBuilder.ok(
                        "sensor_$type", ScanCategory.SENSORS, name,
                        "Sensor detectado e disponível."
                    )
                } else {
                    ScanCheckBuilder.info(
                        "sensor_$type", ScanCategory.SENSORS, name,
                        "Sensor não presente neste aparelho. Isso é normal em alguns modelos."
                    )
                }
            )
        }

        results.add(
            if (present.isEmpty()) {
                ScanCheckBuilder.warning(
                    "sensor_total", ScanCategory.SENSORS, "Sensores",
                    "Nenhum sensor detectado pelo sistema.", "sensor_verify"
                )
            } else {
                ScanCheckBuilder.ok(
                    "sensor_total", ScanCategory.SENSORS, "Sensores",
                    "${present.size} sensores detectados pelo sistema."
                )
            }
        )

        return results
    }
}
