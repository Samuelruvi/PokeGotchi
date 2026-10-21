package com.example.pokegotchi

import com.example.pokegotchi.QaHarness.T

/** Bloque J del plan (rendimiento con una coleccion enorme): tiempos de las operaciones que corren en el hilo principal. SOLO PRUEBAS. */
object QaPerf {
    private const val S = "rendimiento"

    private fun time(t: T, label: String, reps: Int = 15, warnMs: Long = 120, block: () -> Unit) {
        var total = 0L; var max = 0L
        repeat(reps) {
            val t0 = System.nanoTime(); block(); val ms = (System.nanoTime() - t0) / 1_000_000
            total += ms; if (ms > max) max = ms
        }
        val avg = total / reps
        if (avg > warnMs) t.r.problem("MEDIA", S, "$label: media $avg ms (max $max ms) con ${PetState.ownedIndividuals(t.c).size} individuos: en el hilo principal congelaria la interfaz")
        else t.r.info(S, "$label: media $avg ms (max $max ms)")
    }

    fun run(t: T, n: Int) {
        if (PetState.ownedIndividuals(t.c).size < 500) QaData.populate(t, 2000)
        val owned = PetState.ownedIndividuals(t.c)
        t.r.info(S, "${owned.size} individuos, ${t.st().all.size} claves de preferencias")
        val c = t.c
        t.setActive("bulbasaur", false)
        time(t, "guardar un cambio (commit de preferencias: debugSetIndividualStats)") { PetState.debugSetIndividualStats(c, "ivysaur", false, health = 50f) }
        time(t, "cuidar al activo (tryApplyAction comer)") { PetState.tryApplyAction(c, PokeWidgetProvider.ACTION_FEED) }
        time(t, "loadWithDecay") { PetState.loadWithDecay(c) }
        time(t, "trainerXpPool") { PetState.trainerXpPool(c) }
        time(t, "noteTrainerLevel") { PetState.noteTrainerLevel(c) }
        time(t, "ownedIndividuals") { PetState.ownedIndividuals(c) }
        time(t, "favoriteIndividuals") { PetState.favoriteIndividuals(c) }
        time(t, "rawStats x100") { owned.take(100).forEach { PetState.rawStats(c, it.first, it.second) } }
        time(t, "levelForPokemon x100") { owned.take(100).forEach { PetState.levelForPokemon(c, it.first, it.second) } }
        time(t, "setFavorite") { PetState.setFavorite(c, "charmander", false, t.rnd.nextBoolean()) }
        time(t, "maybeGenerateOffer") { PetState.maybeGenerateOffer(c) }
        time(t, "forceNewOffer", reps = 5) { PetState.forceNewOffer(c) }
        PetState.debugSetTrainerLevelMax(c, 50)
        val free = owned.filter { it.first != "bulbasaur" }.take(6)
        time(t, "guarderia: meter y sacar", reps = 6) { free.forEach { PetState.nurseryAdd(c, it.first, it.second) }; PetState.nurseryMembers(c).forEach { PetState.nurseryRemove(c, it.first, it.second) } }
        free.forEach { PetState.nurseryAdd(c, it.first, it.second) }
        time(t, "settleNursery (6 miembros)", reps = 5) { t.nurseryTimeTravel(24L * 3_600_000L); PetState.settleNursery(c, force = true) }
        time(t, "usar un caramelo", reps = 10) { PetState.addCandies(c, 1); PetState.useCandy(c, "ivysaur", false) }
        time(t, "activar a otro Pokemon (setPokemon)", reps = 10) { PetState.setPokemon(c, owned[t.rnd.nextInt(owned.size)].first, -1, false) }
        time(t, "displaySpriteName", reps = 30) { PetState.displaySpriteName(c, PetState.currentPokemon(c), PetState.isActiveShiny(c)) }
        time(t, "DebugLog.log x20", reps = 5) { repeat(20) { DebugLog.log(c, "qa perf") } }
        val logBytes = DebugLog.file(c).length()
        t.r.info(S, "tamaño del log persistente: ${logBytes / 1024} KB")
        t.reportInvariants(S, "tras las pruebas de rendimiento")
    }
}
