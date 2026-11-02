package com.sysscan.repair.util

import java.util.Locale

object Formatters {

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun percent(value: Int): String = "$value%"

    fun celsius(milliCelsius: Int): String =
        String.format(Locale.US, "%.1f°C", milliCelsius / 10.0)

    fun elapsedMillis(start: Long, end: Long): String {
        val ms = end - start
        return String.format(Locale.US, "%.1f s", ms / 1000.0)
    }

    /** Duração curta em português: "0min", "45min", "6h 12min", "2h". */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "0min"
        val totalMinutes = millis / 60_000L
        if (totalMinutes < 1L) return "<1min"
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours == 0L -> "${minutes}min"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}min"
        }
    }

    /** mAh com uma casa quando faz sentido: "8,4 mAh". */
    fun milliampHours(value: Double): String =
        String.format(Locale.US, "%.1f mAh", value)
}
