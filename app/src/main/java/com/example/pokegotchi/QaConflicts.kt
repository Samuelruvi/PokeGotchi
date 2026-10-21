package com.example.pokegotchi

import com.example.pokegotchi.QaHarness.T

/** Casos dirigidos de conflictos entre mecanicas (bloque A del plan). Cada caso parte de un estado vacio. SOLO PRUEBAS. */
object QaConflicts {
    private const val S = "conflictos"

    fun run(t: T) {
        evolveOverOwnedTarget(t)
        eggLoopOverwritesParent(t)
        evolveDungeonLeader(t)
        evolveDungeonCompanion(t)
        evolveIntoExplorer(t)
        evolveAcrossGrowthGroups(t)
        evolveNurseryMember(t)
        deleteNurseryMember(t)
        deleteDungeonMembers(t)
        deleteActiveAndOnly(t)
        setActiveOnBusy(t)
        nurseryAddBlocks(t)
        candyEdges(t)
        offerDoubleResolve(t)
        trainerLevelPersistence(t)
        keepEggBabyInDungeon(t)
        loopReassignsGhostLeader(t)
        deleteEggParent(t)
        shinyNormalCoexist(t)
        dungeonAssignInvalid(t)
        evolutionChainsSpecials(t)
    }

    /** Evolucionar a una especie que YA tienes como individuo vivo. COMPORTAMIENTO ACORDADO (el usuario, 2026-10-18: "no puedes tener dos
     *  Charizard: el Charmeleon que evoluciona pasa a ser tu Charizard"): el que evoluciona SUSTITUYE al que tenias. Se comprueba que es lo que
     *  pasa y que no deja nada incoherente (un solo individuo, con el XP del que evoluciono, y fuera de la guarderia si estaba en ella). */
    private fun evolveOverOwnedTarget(t: T) = t.case(S, "evolucionar hacia una especie ya tenida (la sustituye)") {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 50)
        t.give("charmander", false, 5); t.setActive("charmander")
        val opt = t.evoOptionsOf("pikachu").first()
        t.give("pikachu", false, opt.level + 2)
        t.setLevel("pikachu", false, opt.level + 2)   // pikachu es el inicial (ya existe): give() no le toca el XP
        t.give(opt.name, false, 60)
        PetState.nurseryAdd(t.c, opt.name, false)?.let { t.r.problem("MEDIA", S, "no entra ${opt.name} en la guarderia: $it") }   // el destino estaba EN la guarderia
        val evolving = t.xp("pikachu")!!
        PetState.evolveTo(t.c, "pikachu", opt.name, opt.id, false)
        val after = t.xp(opt.name)!!
        if (kotlin.math.abs(after - evolving) > 1f) t.r.problem("MEDIA", S, "tras evolucionar sobre ${opt.name} no queda el individuo que evoluciono (XP $evolving -> $after)")
        else t.r.ok(S, "el Pikachu que evoluciona sustituye al ${opt.name} que ya tenias (nivel ${PetState.levelForPokemon(t.c, opt.name)})")
        if (PetState.isInNursery(t.c, opt.name, false)) t.r.problem("MEDIA", S, "${opt.name} sigue en la guarderia aunque ahora es el activo del widget")
        else t.r.ok(S, "el sustituido ya no esta en la guarderia (el evolucionado es el activo)")
        if (t.active().first != opt.name) t.r.problem("MEDIA", S, "el evolucionado no es el activo: ${t.active()}")
        t.reportInvariants(S, "tras evolucionar sobre un destino existente")
    }

    /** El flujo REAL del huevo: el padre (forma final) pone un huevo, te quedas la cria y la evolucionas hasta la forma del padre. */
    private fun eggLoopOverwritesParent(t: T) = t.case(S, "bucle del huevo: la cria llega a la forma del padre") {
        t.wipe()
        val parent = "raichu"
        t.give(parent, false, 45); t.setActive(parent)
        var laid = false
        repeat(5000) { if (!laid && PetState.maybeLayEgg(t.c, PetState.loadWithDecay(t.c))) laid = true }
        if (!laid) { t.r.info(S, "no se pudo poner un huevo con $parent en 5000 tiradas"); return@case }
        var ready = false
        repeat(20000) { if (!ready) { PetState.maybeHatchEgg(t.c); ready = PetState.isEggReady(t.c) } }
        if (!ready) { t.r.info(S, "el huevo no llego a estar listo en 20000 tiradas"); return@case }
        val baby = PetState.keepEggHatch(t.c)
        val babyShiny = t.active().second
        t.r.info(S, "huevo de $parent -> cria $baby (shiny=$babyShiny), activo=${t.active()}")
        // Sube la cria por la cadena hasta la forma del padre, como haria el jugador.
        var cur = baby ?: return@case
        val parentXpBefore = t.xp(parent, false)
        var guard = 0
        while (cur != parent && guard++ < 6) {
            val opt = t.evoOptionsOf(cur).firstOrNull { it.name == parent || t.evoOptionsOf(it.name).isNotEmpty() || true } ?: break
            val chosen = t.evoOptionsOf(cur).firstOrNull { it.name == parent } ?: opt
            t.setLevel(cur, babyShiny, chosen.level + 1)
            PetState.evolveTo(t.c, cur, chosen.name, chosen.id, babyShiny)
            cur = chosen.name
        }
        val parentXpAfter = t.xp(parent, babyShiny)
        // Que la cria evolucionada sustituya al padre es el comportamiento acordado (no puede haber dos): solo se informa.
        if (cur == parent && parentXpBefore != null && !babyShiny && parentXpAfter != parentXpBefore) {
            t.r.info(S, "la cria evolucionada hasta $parent sustituye al $parent padre (XP ${"%.0f".format(parentXpBefore)} -> ${"%.0f".format(parentXpAfter)}); comportamiento acordado")
        } else t.r.ok(S, "la cria llega a $cur (shiny=$babyShiny)")
        t.reportInvariants(S, "tras el bucle del huevo")
    }

    private fun evolveDungeonLeader(t: T) = t.case(S, "evolucionar al LIDER de una mazmorra en curso") {
        t.wipe()
        t.give("charmander", false, 20)
        val opt = t.evoOptionsOf("charmander").first()
        DungeonState.assign(t.c, "charmander", false, DungeonSimulator.maxHp(t.c, "charmander", false), t.xp("charmander") ?: 0f)
        PetState.evolveTo(t.c, "charmander", opt.name, opt.id, false)
        val v = t.invariants()
        if (v.isNotEmpty()) t.r.problem("MEDIA", S, "evolucionar al lider con la mazmorra en curso deja el estado incoherente: ${v.joinToString(" | ")}")
        else t.r.ok(S, "evolucionar al lider mantiene la coherencia")
        // La carrera ¿sigue con un lider que ya no existe como esa especie?
        repeat(5) { DungeonSimulator.runTick(t.c) }
        t.r.info(S, "tras 5 ciclos: estado=${DungeonState.status(t.c)}, lider guardado=${DungeonState.currentSpecies(t.c)}, activo=${t.active()}, xp lider(away)=${t.xp("charmander")}, xp nuevo=${t.xp(opt.name)}")
    }

    private fun evolveDungeonCompanion(t: T) = t.case(S, "evolucionar a un COMPAÑERO de una mazmorra en curso") {
        t.wipe()
        t.give("charmander", false, 20); t.give("squirtle", false, 20)
        val opt = t.evoOptionsOf("squirtle").first()
        DungeonState.assign(t.c, "charmander", false, DungeonSimulator.maxHp(t.c, "charmander", false), t.xp("charmander") ?: 0f,
            companions = listOf("squirtle" to false))
        PetState.evolveTo(t.c, "squirtle", opt.name, opt.id, false)
        val v = t.invariants()
        if (v.isNotEmpty()) t.r.problem("MEDIA", S, "evolucionar a un compañero con la mazmorra en curso deja el estado incoherente: ${v.joinToString(" | ")}")
        else t.r.ok(S, "evolucionar a un compañero mantiene la coherencia")
        repeat(5) { DungeonSimulator.runTick(t.c) }
        t.r.info(S, "tras 5 ciclos: estado=${DungeonState.status(t.c)}, grupo=${DungeonState.party(t.c).map { it.first }}")
    }

    /** Evolucionar hacia una especie que YA esta explorando (la sustituye, comportamiento acordado): nunca puede quedar el mismo individuo
     *  explorando y activo a la vez, ni dos entradas iguales en el grupo. */
    private fun evolveIntoExplorer(t: T) = t.case(S, "evolucionar hacia una especie que esta explorando la mazmorra") {
        // 1) el ACTIVO evoluciona a la especie que explora: el explorador sustituido sale del grupo (la carrera se abandona).
        t.wipe()
        t.give("charmander", false, 20); t.setActive("charmander"); t.give("charmeleon", false, 40)
        val opt = t.evoOptionsOf("charmander").first { it.name == "charmeleon" }
        DungeonState.assign(t.c, "charmeleon", false, DungeonSimulator.maxHp(t.c, "charmeleon", false), t.xp("charmeleon") ?: 0f)
        PetState.evolveTo(t.c, "charmander", opt.name, opt.id, false)
        if (DungeonState.isInParty(t.c, "charmeleon", false) || t.active() != ("charmeleon" to false))
            t.r.problem("MEDIA", S, "el activo evoluciona a un explorador: activo=${t.active()}, grupo=${DungeonState.party(t.c)}, estado=${DungeonState.status(t.c)}")
        t.reportInvariants(S, "tras evolver el activo hacia el explorador")
        // 2) el LIDER evoluciona a la especie que ya iba de compañero: una sola entrada, la del lider.
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 30)
        t.give("charmander", false, 20); t.give("charmeleon", false, 40); t.give("squirtle", false, 20)
        DungeonState.assign(t.c, "charmander", false, DungeonSimulator.maxHp(t.c, "charmander", false), 0f,
            companions = listOf("charmeleon" to false, "squirtle" to false))
        PetState.evolveTo(t.c, "charmander", opt.name, opt.id, false)
        val party = DungeonState.party(t.c)
        val hpsOk = DungeonState.companionHps(t.c).size == DungeonState.companions(t.c).size
        if (party != listOf("charmeleon" to false, "squirtle" to false) || !hpsOk)
            t.r.problem("MEDIA", S, "el lider evoluciona a un compañero: grupo=$party, vidas ${DungeonState.companionHps(t.c).size}/${DungeonState.companions(t.c).size}")
        t.reportInvariants(S, "tras evolucionar al lider hacia un compañero")
        repeat(3) { DungeonSimulator.runTick(t.c) }
        t.reportInvariants(S, "tras 3 ciclos con el grupo ya fusionado")
        // 3) un miembro de la GUARDERIA evoluciona a la especie que explora: toma ese sitio y sale de la guarderia.
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 50)
        t.give("charmander", false, 20); t.give("charmeleon", false, 40); t.give("squirtle", false, 20); t.setActive("squirtle")
        PetState.nurseryAdd(t.c, "charmander", false)
        DungeonState.assign(t.c, "charmeleon", false, DungeonSimulator.maxHp(t.c, "charmeleon", false), 0f)
        PetState.evolveTo(t.c, "charmander", opt.name, opt.id, false)
        t.reportInvariants(S, "tras evolucionar un miembro de la guarderia hacia el explorador")
        t.r.info(S, "guarderia=${PetState.nurseryMembers(t.c)}, grupo=${DungeonState.party(t.c)}, activo=${t.active()}")
    }

    /** Evolucionar entre especies con OTRO ritmo de crecimiento (solo la linea de Applin: erratico -> lento -> erratico): el nivel se conserva. */
    private fun evolveAcrossGrowthGroups(t: T) = t.case(S, "evolucion entre ritmos de crecimiento distintos (Applin)") {
        t.wipe()
        val toDipplin = t.evoOptionsOf("applin").first { it.name == "dipplin" }
        t.give("applin", false, toDipplin.level); t.setLevel("applin", false, toDipplin.level)
        val before = PetState.levelForPokemon(t.c, "applin", false)
        PetState.evolveTo(t.c, "applin", toDipplin.name, toDipplin.id, false)
        val mid = PetState.levelForPokemon(t.c, "dipplin", false)
        val toHydra = t.evoOptionsOf("dipplin").first { it.name == "hydrapple" }
        t.setLevel("dipplin", false, toHydra.level)
        val midBefore = PetState.levelForPokemon(t.c, "dipplin", false)
        PetState.evolveTo(t.c, "dipplin", toHydra.name, toHydra.id, false)
        val end = PetState.levelForPokemon(t.c, "hydrapple", false)
        t.r.info(S, "Applin Nv$before -> Dipplin Nv$mid; Dipplin Nv$midBefore -> Hydrapple Nv$end")
        if (mid != before || end != midBefore) t.r.problem("MEDIA", S, "la evolucion cambia el nivel: Applin Nv$before -> Dipplin Nv$mid, Dipplin Nv$midBefore -> Hydrapple Nv$end")
        else t.r.ok(S, "Applin -> Dipplin -> Hydrapple conservan el nivel")
        t.reportInvariants(S, "tras evolucionar Applin hasta Hydrapple")
    }

    private fun evolveNurseryMember(t: T) = t.case(S, "evolucionar a un miembro de la guarderia") {
        t.wipe()
        t.give("charmander", false, 20)
        val err = PetState.nurseryAdd(t.c, "charmander", false)
        if (err != null) { t.r.problem("MEDIA", S, "no se pudo meter a charmander en la guarderia: $err"); return@case }
        val opt = t.evoOptionsOf("charmander").first()
        PetState.evolveTo(t.c, "charmander", opt.name, opt.id, false)
        if (!t.reportInvariants(S, "tras evolucionar a un miembro de la guarderia")) return@case
        t.r.ok(S, "evolucionar a un miembro de la guarderia lo saca de ella y lo deja activo (${t.active()})")
    }

    private fun deleteNurseryMember(t: T) = t.case(S, "borrar (Debug) a un miembro de la guarderia") {
        t.wipe()
        t.give("charmander", false, 20)
        PetState.nurseryAdd(t.c, "charmander", false)
        PetState.debugDeleteIndividual(t.c, "charmander", false)
        val before = PetState.nurseryMembers(t.c)
        PetState.settleNursery(t.c, force = true)
        val after = PetState.nurseryMembers(t.c)
        if (before.isNotEmpty()) t.r.problem("BAJA", S, "borrar a un miembro de la guarderia (solo Debug) lo deja como fantasma hasta la siguiente liquidacion (antes=$before, despues de settleNursery=$after)")
        else t.r.ok(S, "borrar a un miembro de la guarderia lo saca")
        t.reportInvariants(S, "tras borrar un miembro de la guarderia y liquidar", "BAJA")
    }

    private fun deleteDungeonMembers(t: T) = t.case(S, "borrar (Debug) al lider y a un compañero de la mazmorra") {
        t.wipe()
        t.give("charmander", false, 20); t.give("squirtle", false, 20)
        DungeonState.assign(t.c, "charmander", false, DungeonSimulator.maxHp(t.c, "charmander", false), 0f, companions = listOf("squirtle" to false))
        PetState.debugDeleteIndividual(t.c, "squirtle", false)
        PetState.debugDeleteIndividual(t.c, "charmander", false)
        val v = t.invariants()
        if (v.isNotEmpty()) t.r.problem("BAJA", S, "borrar (solo Debug) al lider/compañero de una carrera en curso: ${v.joinToString(" | ")}")
        repeat(5) { DungeonSimulator.runTick(t.c) }   // no debe petar
        t.r.info(S, "tras borrar y 5 ciclos: estado=${DungeonState.status(t.c)}, lider=${DungeonState.currentSpecies(t.c)}")
    }

    private fun deleteActiveAndOnly(t: T) = t.case(S, "borrar al activo y al unico Pokemon") {
        t.wipe()
        t.give("eevee", false, 10)
        val moved = PetState.debugDeleteIndividual(t.c, "pikachu", false)
        if (moved == null || !t.has("eevee") || t.active().first != "eevee") t.r.problem("MEDIA", S, "borrar al activo no dejo a otro de activo: $moved, activo=${t.active()}")
        else t.r.ok(S, "borrar al activo pasa a ${t.active()}")
        PetState.debugDeleteIndividual(t.c, "eevee", false)
        if (PetState.hasChosenStarter(t.c)) t.r.problem("MEDIA", S, "borrar al unico Pokemon deja starter_done=true con activo ${t.active()}")
        else t.r.ok(S, "borrar al unico Pokemon vuelve a la eleccion de inicial")
    }

    /** setPokemon es la API que la UI protege con selectPokemon; se comprueba que pasa si algo la llama a pelo. */
    private fun setActiveOnBusy(t: T) = t.case(S, "poner de activo a uno ocupado") {
        t.wipe()
        t.give("charmander", false, 20); t.give("squirtle", false, 20)
        PetState.nurseryAdd(t.c, "squirtle", false)
        t.setActive("squirtle")
        if (PetState.isInNursery(t.c, "squirtle", false)) t.r.problem("MEDIA", S, "setPokemon(squirtle) lo deja activo Y en la guarderia")
        else t.r.ok(S, "setPokemon saca de la guarderia al que se activa")
        DungeonState.assign(t.c, "charmander", false, DungeonSimulator.maxHp(t.c, "charmander", false), 0f)
        t.setActive("charmander")
        if (DungeonState.isInParty(t.c, "charmander", false) && t.active().first == "charmander") {
            t.r.problem("BAJA", S, "la API setPokemon permite activar al explorador de una mazmorra en curso (la UI lo bloquea en selectPokemon, pero no hay proteccion en PetState)")
        }
    }

    private fun nurseryAddBlocks(t: T) = t.case(S, "reglas de entrada a la guarderia") {
        t.wipe()
        t.give("charmander", false, 20); t.give("squirtle", false, 20); t.give("bulbasaur", false, 20)
        fun expectBlock(label: String, name: String) {
            val e = PetState.nurseryAdd(t.c, name, false)
            if (e == null) t.r.problem("MEDIA", S, "guarderia deja entrar: $label") else t.r.ok(S, "guarderia bloquea: $label ($e)")
        }
        expectBlock("el activo", "pikachu")
        expectBlock("uno que no existe", "mew")
        DungeonState.assign(t.c, "squirtle", false, DungeonSimulator.maxHp(t.c, "squirtle", false), 0f, companions = listOf("bulbasaur" to false))
        expectBlock("el lider de la mazmorra", "squirtle")
        expectBlock("un compañero de la mazmorra", "bulbasaur")
        if (PetState.nurseryAdd(t.c, "charmander", false) != null) t.r.problem("MEDIA", S, "no deja meter a un Pokemon libre")
        expectBlock("uno ya dentro", "charmander")
        // Capacidad.
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 6)
        val names = listOf("charmander", "squirtle", "bulbasaur", "eevee")
        names.forEach { t.give(it, false, 5) }
        val cap = PetState.nurseryCapacity(t.c)
        val results = names.map { PetState.nurseryAdd(t.c, it, false) }
        val inside = PetState.nurseryMembers(t.c).size
        if (inside != cap) t.r.problem("MEDIA", S, "con capacidad $cap hay $inside dentro tras intentar meter ${names.size}") else t.r.ok(S, "capacidad respetada ($cap)")
        t.reportInvariants(S, "tras llenar la guarderia")
    }

    private fun candyEdges(t: T) = t.case(S, "caramelos: casos limite") {
        t.wipe()
        t.give("charmander", false, 10); t.give("squirtle", false, 100)
        PetState.addCandies(t.c, 5)
        val a = PetState.useCandy(t.c, "mew", false)
        val b = PetState.useCandy(t.c, "squirtle", false)   // nivel 100
        val cndl = PetState.candyCount(t.c)
        if (a != null || b != null || cndl != 5) t.r.problem("MEDIA", S, "useCandy en inexistente/nivel100 devolvio $a/$b y los caramelos pasaron a $cndl (esperado null/null y 5)") else t.r.ok(S, "caramelo a inexistente / nivel 100: no se gasta")
        val opt = t.evoOptionsOf("charmander").first()
        t.setLevel("charmander", false, opt.level + 1)
        PetState.evolveTo(t.c, "charmander", opt.name, opt.id, false)
        val c2 = PetState.useCandy(t.c, "charmander", false)
        if (c2 != null || PetState.candyCount(t.c) != 5) t.r.problem("MEDIA", S, "useCandy sobre un evolucionado-away funciono: $c2") else t.r.ok(S, "caramelo a un evolucionado-away: no se gasta")
        val xpBefore = t.xp(opt.name)!!
        val c3 = PetState.useCandy(t.c, opt.name, false)   // el activo
        if (c3 == null || PetState.candyCount(t.c) != 4 || t.xp(opt.name)!! <= xpBefore) t.r.problem("MEDIA", S, "useCandy sobre el activo: $c3, caramelos=${PetState.candyCount(t.c)}, xp ${t.xp(opt.name)} (antes $xpBefore)")
        else t.r.ok(S, "caramelo al activo suma XP (${"%.1f".format(c3.xp)})")
        // Suficientes caramelos para subir muchos niveles de golpe y comprobar que el nivel nunca pasa de 100.
        t.give("eevee", false, 99); PetState.addCandies(t.c, 400)
        repeat(300) { PetState.useCandy(t.c, "eevee", false) }
        val l = PetState.levelForPokemon(t.c, "eevee")
        if (l == null || l > 100) t.r.problem("MEDIA", S, "nivel tras muchos caramelos: $l") else t.r.ok(S, "muchos caramelos: nivel $l")
        t.reportInvariants(S, "tras usar caramelos")
    }

    private fun offerDoubleResolve(t: T) = t.case(S, "regalo: resolver dos veces / oferta vieja") {
        t.wipe()
        PetState.forceNewOffer(t.c)
        val offer = PetState.currentOffer(t.c)
        if (offer.size != 5) t.r.problem("MEDIA", S, "la oferta tiene ${offer.size} candidatos (esperado 5)")
        if (offer.map { it.name }.toSet().size != offer.size) t.r.problem("MEDIA", S, "la oferta tiene candidatos repetidos: ${offer.map { it.name }}")
        val owned = offer.filter { PetState.isUnlocked(t.c, it.name) }
        if (owned.isNotEmpty()) t.r.problem("MEDIA", S, "la oferta incluye especies ya desbloqueadas: ${owned.map { it.name }}")
        val first = offer.first()
        PetState.resolveOffer(t.c, first.name, first.shiny)
        val r1 = PetState.offerCooldownRemainingMs(t.c)
        PetState.resolveOffer(t.c, first.name, first.shiny)   // doble toque / dialogo viejo
        val r2 = PetState.offerCooldownRemainingMs(t.c)
        t.r.info(S, "ciclo tras resolver: ${r1 / 3_600_000f}h; tras resolver otra vez la misma oferta: ${r2 / 3_600_000f}h")
        if (r2 > r1 + 3_600_000L) t.r.problem("BAJA", S, "resolver dos veces la MISMA oferta adelanta el ancla del ciclo otras 12h (restante ${r1 / 3_600_000f}h -> ${r2 / 3_600_000f}h): un doble toque en 'Elegir' retrasaria el siguiente regalo")
        else t.r.ok(S, "resolver dos veces no mueve el ciclo")
        t.give("mew", false, 1)
        // Resolver un candidato que ya no esta en la oferta (la UI no lo permite, la API si).
        PetState.forceNewOffer(t.c)
        val stale = PetState.currentOffer(t.c).first()
        PetState.dismissOffer(t.c)
        PetState.resolveOffer(t.c, stale.name, stale.shiny)
        if (t.has(stale.name, stale.shiny)) t.r.info(S, "resolveOffer acepta un candidato de una oferta ya descartada (${stale.name}): sin proteccion en la API")
    }

    private fun trainerLevelPersistence(t: T) = t.case(S, "nivel de entrenador: persistencia y desbloqueos") {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, null)
        val pool0 = PetState.trainerXpPool(t.c)
        t.dex.take(60).forEach { t.give(it.name, false, 50) }
        val lvlHigh = PetState.trainerLevel(t.c)
        val maxHigh = PetState.noteTrainerLevel(t.c).first
        t.dex.take(60).forEach { if (it.name != "pikachu") PetState.debugDeleteIndividual(t.c, it.name, false) }
        val lvlLow = PetState.trainerLevel(t.c)
        val maxAfter = PetState.trainerLevelMax(t.c)
        t.r.info(S, "pozo ${"%.0f".format(pool0)} -> nivel $lvlHigh (max $maxHigh); tras borrar: nivel $lvlLow, max guardado $maxAfter")
        if (maxAfter < maxHigh) t.r.problem("MEDIA", S, "el nivel de entrenador maximo guardado BAJA al borrar Pokemon ($maxHigh -> $maxAfter): los desbloqueos se re-bloquearian")
        else t.r.ok(S, "el maximo guardado no baja (nivel actual $lvlLow, max $maxAfter)")
        if (PetState.nurseryUnlocked(t.c) != (maxAfter >= PetState.UNLOCK_NURSERY_LEVEL)) t.r.problem("MEDIA", S, "nurseryUnlocked incoherente con el nivel max $maxAfter")
    }

    private fun keepEggBabyInDungeon(t: T) = t.case(S, "quedarse la cria cuando esa especie esta en la mazmorra") {
        t.wipe()
        val parent = "raichu"
        t.give(parent, false, 45); t.setActive(parent)
        t.give("pichu", false, 10)   // un Pichu vivo (p. ej. de una segunda linea)
        DungeonState.assign(t.c, "pichu", false, DungeonSimulator.maxHp(t.c, "pichu", false), t.xp("pichu") ?: 0f)
        var laid = false
        repeat(5000) { if (!laid && PetState.maybeLayEgg(t.c, PetState.loadWithDecay(t.c))) laid = true }
        var ready = false
        repeat(20000) { if (laid && !ready) { PetState.maybeHatchEgg(t.c); ready = PetState.isEggReady(t.c) } }
        if (!laid || !ready) { t.r.info(S, "no hubo huevo listo"); return@case }
        val baby = PetState.keepEggHatch(t.c)
        val v = t.invariants()
        if (v.isNotEmpty()) t.r.problem("MEDIA", S, "quedarse la cria ($baby) con esa especie explorando deja: ${v.joinToString(" | ")}")
        else t.r.ok(S, "quedarse la cria no choca con la mazmorra")
    }

    private fun loopReassignsGhostLeader(t: T) = t.case(S, "modo continuo con el lider evolucionado/borrado durante el cierre") {
        t.wipe()
        t.give("charmander", false, 20)
        DungeonState.assign(t.c, "charmander", false, DungeonSimulator.maxHp(t.c, "charmander", false), 0f, autoRepeat = true)
        DungeonState.startCooldown(t.c, 3, 1L)
        val opt = t.evoOptionsOf("charmander").first()
        PetState.evolveTo(t.c, "charmander", opt.name, opt.id, false)
        Thread.sleep(5)
        DungeonSimulator.resolveCooldownIfReady(t.c)
        val st = DungeonState.status(t.c)
        if (st == DungeonState.STATUS_EXPLORING && !t.alive(DungeonState.currentSpecies(t.c) ?: "")) {
            t.r.problem("MEDIA", S, "el modo continuo REASIGNA a ${DungeonState.currentSpecies(t.c)}, que ya evoluciono (queda explorando un Pokemon fantasma: ni gana XP ni existe)")
        } else t.r.ok(S, "tras evolucionar al lider durante el cierre el bucle queda en $st")
        // Lo mismo si el lider se borra.
        t.wipe()
        t.give("charmander", false, 20)
        DungeonState.assign(t.c, "charmander", false, DungeonSimulator.maxHp(t.c, "charmander", false), 0f, autoRepeat = true)
        DungeonState.startCooldown(t.c, 3, 1L)
        PetState.debugDeleteIndividual(t.c, "charmander", false)
        Thread.sleep(5)
        DungeonSimulator.resolveCooldownIfReady(t.c)
        if (DungeonState.status(t.c) == DungeonState.STATUS_EXPLORING && !t.has(DungeonState.currentSpecies(t.c) ?: "")) {
            t.r.problem("BAJA", S, "el modo continuo reasigna a un Pokemon BORRADO (${DungeonState.currentSpecies(t.c)}) (solo alcanzable con el borrado de Debug)")
        }
    }

    private fun deleteEggParent(t: T) = t.case(S, "huevo activo con el padre borrado / cambiado") {
        t.wipe()
        t.give("raichu", false, 45); t.setActive("raichu")
        var laid = false
        repeat(5000) { if (!laid && PetState.maybeLayEgg(t.c, PetState.loadWithDecay(t.c))) laid = true }
        if (!laid) { t.r.info(S, "sin huevo"); return@case }
        t.setActive("pikachu")
        t.r.info(S, "huevo con el padre fuera de activo: activo=${t.active()}, activeIsEggParent=${PetState.activeIsEggParent(t.c)}, hasActiveEgg=${PetState.hasActiveEgg(t.c)}")
        var progressed = 0
        repeat(2000) { val before = PetState.eggCrackStage(t.c); PetState.maybeHatchEgg(t.c); if (PetState.eggCrackStage(t.c) != before || PetState.isEggReady(t.c)) progressed++ }
        t.r.info(S, "el huevo avanza aunque el padre no sea el activo: $progressed avances en 2000 tiradas")
        PetState.debugDeleteIndividual(t.c, "raichu", false)
        val species = PetState.eggHatchSpecies(t.c)
        val spriteName = PetState.eggHatchSpriteName(t.c)
        t.r.info(S, "con el padre borrado: eggHatchSpecies=$species eggHatchSpriteName=$spriteName")
        var ready = PetState.isEggReady(t.c)
        repeat(20000) { if (!ready) { PetState.maybeHatchEgg(t.c); ready = PetState.isEggReady(t.c) } }
        val kept = PetState.keepEggHatch(t.c)
        t.r.info(S, "keepEggHatch con el padre borrado -> $kept, activo=${t.active()}")
        t.reportInvariants(S, "tras quedarse una cria cuyo padre se borro", "BAJA")
    }

    private fun shinyNormalCoexist(t: T) = t.case(S, "normal y shiny de la misma especie") {
        t.wipe()
        t.give("charmander", false, 20); t.give("charmander", true, 30)
        val opt = t.evoOptionsOf("charmander").first()
        PetState.evolveTo(t.c, "charmander", opt.name, opt.id, true)
        if (!t.alive("charmander", false) || t.alive("charmander", true) || !t.alive(opt.name, true)) {
            t.r.problem("MEDIA", S, "evolucionar el shiny afecto al normal: normal vivo=${t.alive("charmander", false)} shiny vivo=${t.alive("charmander", true)} destino shiny vivo=${t.alive(opt.name, true)}")
        } else t.r.ok(S, "evolucionar al shiny no toca al normal")
        PetState.setFavorite(t.c, "charmander", false, true)
        val favNormal = PetState.isFavorite(t.c, opt.name, false); val favShiny = PetState.isFavorite(t.c, opt.name, true)
        if (!favNormal || favShiny) t.r.problem("BAJA", S, "favorito por linea: tras marcar charmander normal, ${opt.name} normal=$favNormal shiny=$favShiny")
        else t.r.ok(S, "el favorito sigue a la linea y distingue shiny")
        t.reportInvariants(S, "tras evolucionar un shiny")
    }

    private fun dungeonAssignInvalid(t: T) = t.case(S, "mazmorra: grupos invalidos") {
        t.wipe()
        t.give("charmander", false, 20)
        val usable = DungeonState.usableCompanions(t.c, "charmander" to false, listOf("charmander" to false, "pikachu" to false, "mew" to false, "charmander" to false))
        if (usable.isNotEmpty()) t.r.problem("MEDIA", S, "usableCompanions deja pasar a $usable (lider repetido / activo / inexistente)") else t.r.ok(S, "usableCompanions filtra lider, activo e inexistentes")
        t.give("squirtle", false, 20); t.give("bulbasaur", false, 20); t.give("eevee", false, 20)
        PetState.debugSetTrainerLevelMax(t.c, 5)
        val u2 = DungeonState.usableCompanions(t.c, "charmander" to false, listOf("squirtle" to false, "bulbasaur" to false, "eevee" to false))
        if (u2.isNotEmpty()) t.r.problem("MEDIA", S, "con nivel de entrenador 5 se permiten compañeros: $u2") else t.r.ok(S, "sin nivel de entrenador no hay compañeros")
        PetState.debugSetTrainerLevelMax(t.c, 10)
        val u3 = DungeonState.usableCompanions(t.c, "charmander" to false, listOf("squirtle" to false, "bulbasaur" to false, "eevee" to false))
        if (u3.size != 1) t.r.problem("MEDIA", S, "con nivel 10 deberia permitir 1 compañero y permite ${u3.size}") else t.r.ok(S, "nivel 10: 1 compañero")
        PetState.debugSetTrainerLevelMax(t.c, 15)
        val u4 = DungeonState.usableCompanions(t.c, "charmander" to false, listOf("squirtle" to false, "bulbasaur" to false, "eevee" to false))
        if (u4.size != 2) t.r.problem("MEDIA", S, "con nivel 15 deberia permitir 2 compañeros y permite ${u4.size}") else t.r.ok(S, "nivel 15: 2 compañeros")
    }

    /** Todas las cadenas evolutivas por la API (evolveTo seguido de la comprobacion de invariantes). */
    private fun evolutionChainsSpecials(t: T) = t.case(S, "especies con evolucion especial (aleatoria / formas)") {
        for (name in listOf("ursaring", "dudunsparce", "dunsparce", "tandemaus", "eevee", "burmy", "tyrogue", "wurmple", "poliwhirl", "kirlia", "snorunt", "clamperl", "gloom", "slowpoke", "applin", "scyther")) {
            if (t.evo[name] == null) { t.r.info(S, "$name no esta en evolutions.json"); continue }
            t.wipe()
            val opts = t.evoOptionsOf(name)
            if (opts.isEmpty()) { t.r.info(S, "$name sin evoluciones"); continue }
            t.give(name, false, opts.maxOf { it.level } + 1)
            for (o in opts) {
                try {
                    t.give(name, false, o.level + 1)
                    val (target, tid) = PetState.resolveEvolutionTarget(o.name, o.id)
                    PetState.evolveTo(t.c, name, target, tid, false)
                    val v = t.invariants()
                    if (v.isNotEmpty()) t.r.problem("MEDIA", S, "$name -> $target deja: ${v.joinToString(" | ")}")
                    // Para poder probar la siguiente rama se "revive" a la forma base.
                    PetState.debugRevertEvolution(t.c, name, target)
                } catch (e: Throwable) {
                    t.r.problem("GRAVE", S, "$name -> ${o.name}: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        t.r.ok(S, "recorridas las evoluciones especiales")
    }
}
