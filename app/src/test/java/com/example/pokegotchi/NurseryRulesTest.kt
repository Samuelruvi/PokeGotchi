package com.example.pokegotchi

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NurseryRulesTest {

    @Test
    fun slotsFollowTheAgreedLevels() {
        val expected = mapOf(1 to 0, 5 to 0, 6 to 1, 7 to 1, 8 to 2, 13 to 2, 14 to 3, 23 to 3, 24 to 4, 34 to 4, 35 to 5, 49 to 5, 50 to 6, 100 to 6)
        for ((level, slots) in expected) assertEquals("nivel $level", slots, NurseryRules.slotsForTrainerLevel(level))
    }

    @Test
    fun nextSlotLevelEndsAtTheCap() {
        assertEquals(6, NurseryRules.nextSlotLevel(1))
        assertEquals(8, NurseryRules.nextSlotLevel(6))
        assertEquals(50, NurseryRules.nextSlotLevel(35))
        assertNull(NurseryRules.nextSlotLevel(50))
    }

    @Test
    fun slotXpIsHalfOfTheActivesTimeXp() {
        assertEquals(20f, NurseryRules.xpPerHour(40f), 1e-6f)
        // 6 plazas durante un dia = 2.880 XP, el triple que el activo (960): el efecto que el usuario acepto.
        assertEquals(2880f, 6 * 24 * NurseryRules.xpPerHour(40f), 1e-3f)
    }

    @Test
    fun layProbabilityCapsLikeTheActive() {
        assertEquals(0.11f, NurseryRules.layProbability(10, 0.01f, 0.01f, 0.4f), 1e-6f)
        assertEquals(0.4f, NurseryRules.layProbability(80, 0.01f, 0.01f, 0.4f), 1e-6f)
    }

    @Test
    fun noEggWithoutAParentThatCanLay() {
        val r = NurseryRules.simulateEggs(500, intArrayOf(0, 0), floatArrayOf(0f, 0f), Random(1))
        assertArrayEquals(intArrayOf(0, 0), r.hatches)
        assertArrayEquals(intArrayOf(0, 0), r.finalStates)
    }

    @Test
    fun anExistingEggKeepsIncubatingEvenIfTheParentCannotLayAnymore() {
        val r = NurseryRules.simulateEggs(500, intArrayOf(NurseryRules.EGG_SUCCESSES_TO_HATCH), floatArrayOf(0f), Random(2))
        assertEquals(1, r.hatches[0])
        assertEquals(0, r.finalStates[0])
    }

    @Test
    fun eggNeedsFourSuccessesNeverFewer() {
        // Con probabilidad de avance 0.5 por tick, en 3 ticks no puede eclosionar (hacen falta 4 exitos).
        repeat(200) { seed ->
            val r = NurseryRules.simulateEggs(3, intArrayOf(1), floatArrayOf(0f), Random(seed))
            assertEquals(0, r.hatches[0])
        }
    }

    @Test
    fun hatchRateAtLevel30MatchesTheActiveEggCycle() {
        // Nivel 30: p(puesta)=0.31 por tick; espera media 1/0.31 ticks + 4/0.5 = 8 ticks de incubacion.
        // Ciclo medio ~11.2 ticks -> ~8.5 huevos por dia (96 ticks). Se promedia sobre muchos dias.
        val rnd = Random(42)
        val days = 400
        var total = 0
        var states = intArrayOf(0)
        repeat(days) {
            val r = NurseryRules.simulateEggs(96, states, floatArrayOf(0.31f), rnd)
            total += r.hatches[0]
            states = r.finalStates
        }
        val perDay = total.toDouble() / days
        assertTrue("huevos/dia = $perDay", perDay in 8.0..9.0)
    }

    @Test
    fun sixSlotsAtLevel30ProduceAboutFiftyEggsPerDay() {
        val rnd = Random(7)
        val days = 200
        var total = 0
        var states = IntArray(6)
        val probs = FloatArray(6) { 0.31f }
        repeat(days) {
            val r = NurseryRules.simulateEggs(96, states, probs, rnd)
            total += r.hatches.sum()
            states = r.finalStates
        }
        val perDay = total.toDouble() / days
        assertTrue("huevos/dia con 6 plazas = $perDay", perDay in 48.0..54.0)
    }

    @Test
    fun planTicksKeepsTheRemainderForTheNextSettle() {
        val plan = NurseryRules.planTicks(NurseryRules.TICK_MS * 3 + 5_000L)
        assertEquals(3, plan.ticks)
        assertEquals(NurseryRules.TICK_MS * 3, plan.advanceMs)
    }

    @Test
    fun planTicksCapsLongAbsencesAndCatchesTheClockUp() {
        val elapsed = NurseryRules.TICK_MS * 1000
        val plan = NurseryRules.planTicks(elapsed)
        assertEquals(NurseryRules.MAX_CATCHUP_TICKS, plan.ticks)
        assertEquals(elapsed, plan.advanceMs)
    }

    @Test
    fun planTicksIsEmptyWhenNothingElapsed() {
        assertEquals(0, NurseryRules.planTicks(0).ticks)
        assertEquals(0, NurseryRules.planTicks(-5).ticks)
        assertEquals(0, NurseryRules.planTicks(NurseryRules.TICK_MS - 1).ticks)
    }

    @Test
    fun carePlanKeepsTheRemainderForTheNextSettle() {
        val last = 1_000_000L
        val plan = NurseryRules.planCareSteps(last, last + NurseryRules.CARE_STEP_MS * 3 + 7_000L)
        assertEquals(3, plan.steps)
        assertEquals(last, plan.fromMs)
        assertEquals(last + NurseryRules.CARE_STEP_MS * 3, plan.newLastMs)
    }

    @Test
    fun carePlanIsEmptyBelowOneStep() {
        val plan = NurseryRules.planCareSteps(5_000L, 5_000L + NurseryRules.CARE_STEP_MS - 1)
        assertEquals(0, plan.steps)
        assertEquals(5_000L, plan.newLastMs)
    }

    @Test
    fun carePlanCapsLongAbsencesAndSimulatesTheLastStepsOnly() {
        val now = 10_000_000_000L
        val plan = NurseryRules.planCareSteps(now - NurseryRules.CARE_STEP_MS * 100_000, now)
        assertEquals(NurseryRules.MAX_CARE_CATCHUP_STEPS, plan.steps)
        assertEquals(now, plan.newLastMs)
        // Los pasos simulados terminan justo en "ahora".
        assertEquals(now, plan.fromMs + plan.steps * NurseryRules.CARE_STEP_MS)
    }

    @Test
    fun needsComeMostUrgentFirstAndOnlyThoseBelowTheirThreshold() {
        // vida 30 (umbral 50: pide), felicidad 90 (umbral 70: no), higiene 10 (umbral 30: pide, mas hundida)
        val order = NurseryRules.needsInOrder(floatArrayOf(30f, 90f, 10f), floatArrayOf(50f, 70f, 30f))
        assertEquals(listOf(2, 0), order)
        assertTrue(NurseryRules.needsInOrder(floatArrayOf(80f, 90f, 60f), floatArrayOf(50f, 70f, 30f)).isEmpty())
    }
}
