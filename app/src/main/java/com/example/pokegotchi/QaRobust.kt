package com.example.pokegotchi

import com.example.pokegotchi.QaHarness.T

/** Bloque J del plan (robustez): valores raros en las preferencias, relojes movidos, claves huerfanas. SOLO PRUEBAS. */
object QaRobust {
    private const val S = "robustez"
    private const val HOUR_MS = 3_600_000L

    fun run(t: T, n: Int) {
        t.case(S, "valores de otro tipo (versiones antiguas)") { legacyTypes(t) }
        t.case(S, "individuo a medias (faltan claves)") { partialIndividual(t) }
        t.case(S, "claves huerfanas e inflado del nivel de entrenador") { ghostKeys(t) }
        t.case(S, "numeros extremos (NaN, infinito, negativos, enormes)") { extremeNumbers(t) }
        t.case(S, "relojes: reloj del activo en 1970 y en el futuro") { activeClock(t) }
        t.case(S, "relojes: ciclo del regalo en el futuro (hora del movil adelantada)") { offerClock(t) }
        if (n == 7) t.case(S, "estado roto: activo sin individuo (se deja asi para arrancar la app)") { brokenActive(t) }
    }

    /** Ejecuta una bateria de lecturas/acciones y devuelve las que lanzan excepcion. */
    private fun battery(t: T, species: String): List<String> {
        val c = t.c; val bad = ArrayList<String>()
        fun tryIt(label: String, f: () -> Unit) { try { f() } catch (e: Throwable) { bad.add("$label -> ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull { it.className.contains("pokegotchi") }?.let { "${it.fileName}:${it.lineNumber}" }}") } }
        tryIt("rawStats") { PetState.rawStats(c, species, false) }
        tryIt("levelForPokemon") { PetState.levelForPokemon(c, species, false) }
        tryIt("ownedIndividuals") { PetState.ownedIndividuals(c) }
        tryIt("trainerXpPool") { PetState.trainerXpPool(c) }
        tryIt("trainerLevel") { PetState.trainerLevel(c) }
        tryIt("loadWithDecay") { PetState.loadWithDecay(c) }
        tryIt("tryApplyAction") { PetState.tryApplyAction(c, PokeWidgetProvider.ACTION_FEED) }
        tryIt("displaySpriteName") { PetState.displaySpriteName(c, PetState.currentPokemon(c), false) }
        tryIt("useCandy") { PetState.addCandies(c, 1); PetState.useCandy(c, species, false) }
        tryIt("grantXpToIndividual") { PetState.grantXpToIndividual(c, species, false, 5f) }
        tryIt("settleNursery") { PetState.settleNursery(c, force = true) }
        tryIt("estimatedActionsPerDay") { PetState.estimatedActionsPerDay(c, PetState.currentPokemon(c)) }
        tryIt("maybeGenerateOffer") { PetState.maybeGenerateOffer(c) }
        tryIt("dungeon assign+tick") {
            DungeonState.abandon(c)
            DungeonState.assign(c, species, false, DungeonSimulator.maxHp(c, species, false), PetState.rawStats(c, species, false)?.xp ?: 0f)
            DungeonSimulator.runTick(c); DungeonState.abandon(c)
        }
        return bad
    }

    private fun legacyTypes(t: T) {
        t.wipe()
        t.give("bulbasaur", false, 30); t.give("charmander", false, 30)
        val e = t.st().edit()
        e.remove("bulbasaur.xp").putInt("bulbasaur.xp", 5000)              // XP guardada como Int (versiones antiguas)
        e.remove("charmander.health").putInt("charmander.health", 80)      // salud como Int
        e.commit()
        for (sp in listOf("bulbasaur", "charmander")) {
            val bad = battery(t, sp)
            if (bad.isEmpty()) t.r.ok(S, "$sp con valores de tipo Int en las preferencias: toda la bateria funciona")
            else bad.forEach { t.r.problem("MEDIA", S, "$sp con valores de otro tipo (Int en vez de Float): $it") }
        }
    }

    private fun partialIndividual(t: T) {
        t.wipe()
        t.give("squirtle", false, 20)
        val e = t.st().edit()
        e.remove("squirtle.health").remove("squirtle.last_update").remove("squirtle.dexid").commit()
        val bad = battery(t, "squirtle")
        if (bad.isEmpty()) t.r.ok(S, "individuo sin salud / reloj / id: la bateria funciona")
        else bad.forEach { t.r.problem("MEDIA", S, "individuo a medias (sin salud/reloj/id): $it") }
        // Solo la clave de XP (como dejaba el bug de los individuos fantasma): no debe romper nada.
        t.st().edit().putFloat("ghostmon.xp", 100f).commit()
        val bad2 = battery(t, "ghostmon")
        if (bad2.isNotEmpty()) bad2.forEach { t.r.problem("MEDIA", S, "clave suelta 'ghostmon.xp' (especie inexistente): $it") }
        else t.r.ok(S, "clave de XP de una especie inexistente: no rompe la bateria")
    }

    private fun ghostKeys(t: T) {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, null)
        val before = PetState.trainerXpPool(t.c)
        val e = t.st().edit()
        repeat(50) { i -> e.putFloat("fantasma$i.xp", 20_000f) }
        e.commit()
        val after = PetState.trainerXpPool(t.c)
        if (after > before + 1f) t.r.problem("MEDIA", S, "50 claves '<algo>.xp' de especies que NO existen suben el pozo del nivel de entrenador de ${"%.0f".format(before)} a ${"%.0f".format(after)} (trainerXpPool cuenta cualquier clave .xp, no solo especies reales): un individuo fantasma de un bug antiguo regalaria niveles de entrenador")
        else t.r.ok(S, "las claves de especies inexistentes no cuentan para el nivel de entrenador")
    }

    private fun extremeNumbers(t: T) {
        t.wipe()
        t.give("eevee", false, 10)
        for ((label, v) in listOf("NaN" to Float.NaN, "infinito" to Float.POSITIVE_INFINITY, "-infinito" to Float.NEGATIVE_INFINITY, "negativo" to -500f, "enorme" to 3.0e38f)) {
            t.st().edit().putFloat("eevee.xp", v).putFloat("eevee.health", v).putFloat("eevee.happiness", v).putFloat("eevee.hygiene", v).commit()
            val bad = battery(t, "eevee")
            val lvl = try { PetState.levelForPokemon(t.c, "eevee", false) } catch (e: Throwable) { null }
            val st = try { PetState.rawStats(t.c, "eevee", false) } catch (e: Throwable) { null }
            if (bad.isNotEmpty()) bad.forEach { t.r.problem("MEDIA", S, "XP/barras = $label: $it") }
            if (lvl == null || lvl !in 1..100) t.r.problem("MEDIA", S, "XP = $label: nivel $lvl")
            t.r.info(S, "valor $label -> nivel $lvl, barras ${st?.let { "%.0f/%.0f/%.0f".format(it.health, it.hygiene, it.happiness) }}, xp ${st?.xp}")
        }
    }

    private fun activeClock(t: T) {
        t.wipe()
        t.give("charmander", false, 20); t.setActive("charmander")
        val xp0 = t.xp("charmander") ?: 0f
        t.st().edit().putLong("charmander.last_update", 1L).commit()
        val s1 = PetState.loadWithDecay(t.c)
        t.r.info(S, "reloj del activo en 1970: XP ${"%.0f".format(xp0)} -> ${"%.0f".format(s1.xp)} (+${"%.0f".format(s1.xp - xp0)}), barras ${"%.0f".format(s1.health)}/${"%.0f".format(s1.hygiene)}/${"%.0f".format(s1.happiness)}")
        if (s1.xp - xp0 > 40f * 24f * 30f) t.r.problem("MEDIA", S, "con el reloj del activo corrupto (1970) loadWithDecay regala ${"%.0f".format(s1.xp - xp0)} XP de golpe (sin tope de ausencia: 40 XP/h x ${(System.currentTimeMillis() / HOUR_MS)} h)")
        else t.r.ok(S, "la XP por tiempo del activo tiene tope razonable")
        val xp2 = PetState.loadWithDecay(t.c).xp
        t.st().edit().putLong("charmander.last_update", System.currentTimeMillis() + 10L * 24 * HOUR_MS).commit()
        val s2 = PetState.loadWithDecay(t.c)
        t.r.info(S, "reloj del activo 10 dias en el futuro: XP ${"%.1f".format(xp2)} -> ${"%.1f".format(s2.xp)}")
        if (s2.xp < xp2 - 0.5f) t.r.problem("MEDIA", S, "reloj del activo en el futuro: la XP BAJA de ${"%.1f".format(xp2)} a ${"%.1f".format(s2.xp)}")
        val left = t.st().getLong("charmander.last_update", 0L) - System.currentTimeMillis()
        if (left > 5L * 24 * HOUR_MS) t.r.problem("BAJA", S, "loadWithDecay deja el reloj del activo ${left / HOUR_MS} h en el futuro: el activo no gana XP por tiempo hasta que el reloj real lo alcance (si el movil tuvo la hora adelantada, 'congela' el crecimiento ese tiempo)")
    }

    /** Deja la cuenta con el activo apuntando a una especie sin individuo (lo que dejaba un bug antiguo al borrar) para comprobar que la app arranca. */
    private fun brokenActive(t: T) {
        t.wipe()
        PetState.setTrainerName(t.c, "QA")
        t.st().edit().putString("pokemon", "eevee").putBoolean("active_shiny", false).commit()
        t.r.info(S, "estado roto: activo=${PetState.currentPokemon(t.c)} sin individuo; starter_done=${PetState.hasChosenStarter(t.c)}")
        val bad = battery(t, "eevee")
        if (bad.isEmpty()) t.r.ok(S, "la bateria de lecturas aguanta un activo sin individuo") else bad.forEach { t.r.problem("MEDIA", S, "activo sin individuo: $it") }
    }

    private fun offerClock(t: T) {
        t.wipe()
        PetState.dismissOffer(t.c)
        t.st().edit().putString("offer_species", "").putLong("offer_next_at", System.currentTimeMillis() + 30L * 24 * HOUR_MS).commit()
        val rem = PetState.offerCooldownRemainingMs(t.c) / HOUR_MS
        val generated = PetState.maybeGenerateOffer(t.c)
        t.r.info(S, "ancla del regalo 30 dias en el futuro: restan ${rem} h, generado=$generated")
        if (rem > 13 && !generated) t.r.problem("MEDIA", S, "si la hora del movil se adelanto y se volvio a corregir, el ciclo del regalo queda ${rem / 24} dias en el futuro: el siguiente regalo tardaria todo ese tiempo (no hay proteccion contra relojes movidos)")
    }
}
