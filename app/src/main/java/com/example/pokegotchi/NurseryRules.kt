package com.example.pokegotchi

import kotlin.math.min
import kotlin.random.Random

/**
 * Reglas PURAS (sin Android, probadas en JVM) de la GUARDERIA: un Pokemon dentro gana XP sin que
 * lo cuides, y si puede poner huevos los pone e incuba SOLO. La parte que toca preferencias vive en
 * PetState (settleNursery); aqui solo esta la aritmetica.
 *
 * Decisiones acordadas con el usuario:
 *  - se desbloquea con el nivel de entrenador, plazas en los niveles [SLOT_LEVELS] (hasta 6);
 *  - cada plaza gana el [XP_FRACTION] (50 %) del XP por tiempo del Pokemon activo;
 *  - los huevos van "como el activo" (misma probabilidad de puesta por nivel y misma incubacion de
 *    4 exitos al 50 % por tick de 15 min, ~2 h de media);
 *  - el bono de eclosion al que puso el huevo es el [HATCH_BONUS_FRACTION] (50 %) del de siempre.
 */
object NurseryRules {
    const val MAX_SLOTS = 6
    const val XP_FRACTION = 0.5f
    const val HATCH_BONUS_FRACTION = 0.5f

    /** Cada cuanto se tira la puesta/avance de un huevo (igual que el tick de 15 min del widget). */
    const val TICK_MS = 15 * 60_000L
    /** Tope de ticks que se recuperan tras una ausencia larga (~50 h), como el huevo del activo. */
    const val MAX_CATCHUP_TICKS = 200
    /** Exitos (cada uno con probabilidad [ADVANCE_PROB] por tick) hasta eclosionar. */
    const val EGG_SUCCESSES_TO_HATCH = 4
    const val ADVANCE_PROB = 0.5f

    /** Nivel de entrenador al que se abre cada plaza (la 1.a en el 6, la 6.a en el 50). Elegidos por el usuario el 2026-10-17. */
    private val SLOT_LEVELS = intArrayOf(6, 8, 14, 24, 35, 50)

    fun slotsForTrainerLevel(level: Int): Int = SLOT_LEVELS.count { level >= it }

    /** Nivel al que se abre la siguiente plaza, o null si ya estan todas. */
    fun nextSlotLevel(level: Int): Int? = SLOT_LEVELS.firstOrNull { level < it }

    /** XP por hora de una plaza, dado el XP por hora del activo. */
    fun xpPerHour(timeXpPerHour: Float): Float = timeXpPerHour * XP_FRACTION

    /** Probabilidad de poner huevo en un tick (misma formula que el activo, con tope). */
    fun layProbability(level: Int, base: Float, perLevel: Float, max: Float): Float =
        min(max, base + perLevel * level)

    class EggResult(val hatches: IntArray, val finalStates: IntArray)

    /**
     * Simula [ticks] ticks de cada plaza. [states][i] = 0 si la plaza no tiene huevo; 1..[EGG_SUCCESSES_TO_HATCH]
     * si lo tiene, con (estado - 1) exitos ya conseguidos. [layProb][i] = probabilidad de poner en un tick
     * (0 si ese Pokemon no puede poner: no es forma final, o tiene la puesta desactivada). En un tick sin
     * huevo solo se tira la puesta; con huevo solo se tira el avance (igual que PetState.maybeLayEgg /
     * maybeHatchEgg: nunca las dos cosas a la vez). El huevo que ya existe sigue incubando aunque el
     * padre deje de poder poner.
     */
    fun simulateEggs(ticks: Int, states: IntArray, layProb: FloatArray, rnd: Random): EggResult {
        require(states.size == layProb.size)
        val s = states.copyOf()
        val hatches = IntArray(s.size)
        for (i in s.indices) {
            repeat(ticks) {
                if (s[i] == 0) {
                    if (layProb[i] > 0f && rnd.nextFloat() < layProb[i]) s[i] = 1
                } else if (rnd.nextFloat() < ADVANCE_PROB) {
                    if (s[i] >= EGG_SUCCESSES_TO_HATCH) { hatches[i]++; s[i] = 0 } else s[i]++
                }
            }
        }
        return EggResult(hatches, s)
    }

    /** Ticks completos que caben en [elapsedMs] (con el tope de recuperacion) y cuanto avanza el reloj
     *  de huevos: normalmente solo los ticks completos (el resto de tiempo se conserva para el
     *  siguiente), pero tras una ausencia mas larga que el tope se pone al dia entero. */
    class TickPlan(val ticks: Int, val advanceMs: Long)

    fun planTicks(elapsedMs: Long): TickPlan {
        if (elapsedMs <= 0L) return TickPlan(0, 0L)
        val whole = (elapsedMs / TICK_MS).toInt()
        return if (whole > MAX_CATCHUP_TICKS) TickPlan(MAX_CATCHUP_TICKS, elapsedMs)
        else TickPlan(whole, whole.toLong() * TICK_MS)
    }

    // ==================== NECESIDADES (comer / jugar / beber) ====================
    // Los Pokemon de la guarderia tienen LAS MISMAS necesidades que el compañero activo (vida, felicidad
    // e higiene bajan igual, con su personalidad y sus umbrales) - la diferencia es que ellos mismos las
    // cubren con lo que hay en el prado (manzanas -> vida, pelota -> felicidad, bebedero -> higiene) y
    // cada vez que lo hacen ganan el XP de una accion, como si los hubieras cuidado tu.

    /** Cada cuanto se avanza el decaimiento de las necesidades (y se comprueba si toca cubrir alguna). */
    const val CARE_STEP_MS = 10 * 60_000L
    /** Tope de pasos que se recuperan tras una ausencia larga (14 dias). */
    const val MAX_CARE_CATCHUP_STEPS = 14 * 24 * 6

    /** Cuantos pasos de simulacion hay entre [lastMs] y [nowMs]: [steps] pasos que se simulan a partir de
     *  [fromMs], y el reloj pasa a valer [newLastMs] (normalmente solo los pasos completos, conservando
     *  el resto de tiempo para la proxima vez; tras una ausencia mas larga que el tope se pone al dia
     *  entero y solo se simulan los ULTIMOS [MAX_CARE_CATCHUP_STEPS] pasos). */
    class CarePlan(val steps: Int, val fromMs: Long, val newLastMs: Long)

    fun planCareSteps(lastMs: Long, nowMs: Long): CarePlan {
        val elapsed = nowMs - lastMs
        if (elapsed < CARE_STEP_MS) return CarePlan(0, lastMs, lastMs)
        val whole = (elapsed / CARE_STEP_MS).toInt()
        return if (whole > MAX_CARE_CATCHUP_STEPS) CarePlan(MAX_CARE_CATCHUP_STEPS, nowMs - MAX_CARE_CATCHUP_STEPS * CARE_STEP_MS, nowMs)
        else CarePlan(whole, lastMs, lastMs + whole * CARE_STEP_MS)
    }

    /** Indices de las necesidades que piden atencion (valor por debajo de su umbral), la mas hundida primero. */
    fun needsInOrder(values: FloatArray, thresholds: FloatArray): List<Int> {
        require(values.size == thresholds.size)
        return values.indices.filter { values[it] < thresholds[it] }.sortedBy { values[it] }
    }
}
