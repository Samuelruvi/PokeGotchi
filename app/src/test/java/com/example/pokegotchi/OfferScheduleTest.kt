package com.example.pokegotchi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class OfferScheduleTest {
    private val madrid = TimeZone.getTimeZone("Europe/Madrid")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: TimeZone = madrid): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    @Test
    fun theNextGiftIsAlwaysNineOrTwentyOne() {
        // De madrugada -> las 9 de ese mismo dia.
        assertEquals(at(2026, 10, 18, 9), OfferSchedule.nextSlotAfter(at(2026, 10, 18, 3, 30), madrid))
        // Por la mañana (antes de las 9) y entre las 9 y las 21 -> las 9 / las 21 del mismo dia.
        assertEquals(at(2026, 10, 18, 9), OfferSchedule.nextSlotAfter(at(2026, 10, 18, 8, 59), madrid))
        assertEquals(at(2026, 10, 18, 21), OfferSchedule.nextSlotAfter(at(2026, 10, 18, 17, 5), madrid))
        // De noche (despues de las 21) -> las 9 del dia siguiente.
        assertEquals(at(2026, 10, 19, 9), OfferSchedule.nextSlotAfter(at(2026, 10, 18, 22, 15), madrid))
    }

    @Test
    fun theNextGiftIsStrictlyAfter() {
        // Justo a las 9:00 el siguiente es el de las 21:00; y a las 21:00, el de las 9:00 de manana.
        assertEquals(at(2026, 10, 18, 21), OfferSchedule.nextSlotAfter(at(2026, 10, 18, 9), madrid))
        assertEquals(at(2026, 10, 19, 9), OfferSchedule.nextSlotAfter(at(2026, 10, 18, 21), madrid))
        // firstSlotAtOrAfter si cuenta el propio instante.
        assertEquals(at(2026, 10, 18, 9), OfferSchedule.firstSlotAtOrAfter(at(2026, 10, 18, 9), madrid))
    }

    @Test
    fun startingInTheAfternoonDoesNotGiveTheSecondGiftAtNight() {
        // El problema que motivo el cambio: empezar a las 17:00 -> el segundo regalo era 12 h despues (5:00). Ahora es a las 21:00.
        val start = at(2026, 10, 18, 17)
        assertEquals(at(2026, 10, 18, 21), OfferSchedule.nextSlotAfter(start, madrid))
    }

    @Test
    fun gapsBetweenGiftsNeverExceedTheMaximumEvenAcrossDstChanges() {
        // Del 1 de marzo al 30 de noviembre (cambios de hora de primavera y otoño de Europa): cada reparto sigue al anterior en <= 13 h.
        var t = at(2026, 3, 1, 0)
        val end = at(2026, 11, 30, 0)
        var previous = OfferSchedule.nextSlotAfter(t, madrid)
        while (previous < end) {
            val next = OfferSchedule.nextSlotAfter(previous, madrid)
            assertTrue("hueco de ${(next - previous) / 3_600_000.0} h tras ${java.util.Date(previous)}", next - previous in 1..OfferSchedule.MAX_GAP_MS && next - previous >= 11L * 3_600_000L)
            previous = next
        }
    }

    @Test
    fun worksInAnyTimeZone() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val now = at(2026, 10, 18, 22, 0, tokyo)
        val hour = Calendar.getInstance(tokyo).apply { timeInMillis = OfferSchedule.nextSlotAfter(now, tokyo) }.get(Calendar.HOUR_OF_DAY)
        assertEquals(9, hour)
    }
}
