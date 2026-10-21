package com.example.pokegotchi

import java.util.Calendar
import java.util.TimeZone

/**
 * Horario del regalo (pedido del usuario: "que el regalo siempre sea a las 9 de la mañana y a las 9 de la noche, y asi siempre se abren a
 * la vez los regalos" - antes salia 12 h despues del anterior, asi que quien empezaba por la tarde recibia el segundo de madrugada).
 * Como los visitantes de la guarderia (franjas de 15 min del reloj), el regalo va por la HORA DEL RELOJ del movil: aparece a las 9:00 y a
 * las 21:00 de cada dia, sea cuando sea que se abrio el anterior. Lógica pura (sin Android) para poder probarla con tests.
 */
object OfferSchedule {
    /** Horas del dia (hora local) a las que aparece un regalo. */
    val SLOT_HOURS = intArrayOf(9, 21)

    /** Lo mas que pueden separar dos reparto seguidos: 12 h, mas una hora por un cambio de hora (verano/invierno). */
    const val MAX_GAP_MS = 13L * 3_600_000L

    /** Primer reparto ESTRICTAMENTE posterior a [afterMs] (epoch ms), en la zona horaria [zone]. */
    fun nextSlotAfter(afterMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        for (dayOffset in 0..2) {
            for (hour in SLOT_HOURS) {
                val cal = Calendar.getInstance(zone)
                cal.timeInMillis = afterMs
                cal.add(Calendar.DAY_OF_YEAR, dayOffset)
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis > afterMs) return cal.timeInMillis
            }
        }
        return afterMs + MAX_GAP_MS   // (no deberia llegarse aqui: en 3 dias siempre hay un reparto)
    }

    /** Primer reparto igual o posterior a [ms]. */
    fun firstSlotAtOrAfter(ms: Long, zone: TimeZone = TimeZone.getDefault()): Long = nextSlotAfter(ms - 1L, zone)
}
