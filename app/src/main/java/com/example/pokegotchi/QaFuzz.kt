package com.example.pokegotchi

import com.example.pokegotchi.QaHarness.T

/**
 * Bloque B del plan: fuzz de operaciones al azar con la API real, invariantes tras cada paso. SOLO PRUEBAS.
 * Para no repetir en cada vuelta los problemas YA conocidos (ver QaConflicts), por defecto NO se provocan:
 * evolucionar sobre una especie ya tenida, evolucionar a quien esta en una mazmorra en curso y quedarse una cria
 * cuya especie esta ocupada. Se cuenta cuantas veces se evitaron.
 */
object QaFuzz {
    private const val S = "fuzz"
    private const val HOUR_MS = 3_600_000L

    fun run(t: T, n: Int) {
        val runs = 6
        val seen = HashSet<String>()
        var totalOps = 0; var skippedKnown = 0
        val opCounts = HashMap<String, Int>()
        for (run in 0 until runs) {
            t.wipe()
            t.give("charmander", false, 5); t.setActive("charmander")
            val trail = ArrayDeque<String>()
            var failed = false
            var step = 0
            while (step < n && !failed) {
                step++
                val op = pickOp(t)
                opCounts[op.first] = (opCounts[op.first] ?: 0) + 1
                val snapBefore = snapshot(t)
                val trainerMaxBefore = PetState.trainerLevelMax(t.c)
                try {
                    val skipped = op.second()
                    if (skipped) skippedKnown++
                    trail.addLast(op.first + if (skipped) "(evitado)" else ""); if (trail.size > 14) trail.removeFirst()
                } catch (e: Throwable) {
                    trail.addLast(op.first + "!EXC")
                    val sig = "EXC ${op.first} ${e.javaClass.simpleName}"
                    if (seen.add(sig)) t.r.problem("GRAVE", S, "run $run paso $step: excepcion en '${op.first}': ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }} | trail=${trail.toList()}")
                    failed = true
                }
                totalOps++
                val v = ArrayList(t.invariants())
                // Nada se pierde en silencio: un individuo vivo solo puede dejar de estarlo al evolucionar, y su XP nunca baja.
                val snapAfter = snapshot(t)
                for ((k, xpB) in snapBefore) {
                    val xpA = snapAfter[k]
                    if (xpA == null) { if (op.first != "evolucionar") v.add("PERDIDO: $k (xp ${"%.0f".format(xpB)}) ya no existe tras '${op.first}'") }
                    else if (xpA + 0.5f < xpB) v.add("XP BAJA: $k ${"%.1f".format(xpB)} -> ${"%.1f".format(xpA)} tras '${op.first}'")
                }
                if (PetState.trainerLevelMax(t.c) < trainerMaxBefore) v.add("el nivel de entrenador maximo baja ($trainerMaxBefore -> ${PetState.trainerLevelMax(t.c)})")
                val unlocked = t.st().getStringSet("unlocked_species", emptySet()) ?: emptySet()
                snapAfter.keys.map { it.removeSuffix("✨") }.filter { it !in unlocked }.take(3).forEach { v.add("$it vivo pero no esta en la Pokedex (unlocked_species)") }
                if (v.isNotEmpty()) {
                    val sig = "INV ${op.first} ${v.first().replace(Regex("[a-z0-9-]+"), "x").take(40)}"
                    if (seen.add(sig)) t.r.problem("MEDIA", S, "run $run paso $step tras '${op.first}': ${v.joinToString(" | ")} | trail=${trail.toList()}")
                    failed = true
                }
            }
        }
        t.r.info(S, "$totalOps operaciones en $runs corridas, $skippedKnown evitadas por ser problemas ya conocidos; mezcla: ${opCounts.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }}")
        if (seen.isEmpty()) t.r.ok(S, "ninguna violacion de invariantes ni excepcion")
    }

    /** Individuos vivos -> XP (clave "nombre" o "nombre✨"). */
    private fun snapshot(t: T): Map<String, Float> =
        PetState.ownedIndividuals(t.c).associate { (n, s) -> t.who(n, s) to (t.xp(n, s) ?: 0f) }

    private fun owned(t: T) = PetState.ownedIndividuals(t.c)
    private fun free(t: T) = owned(t).filter { (n, s) -> !(n == t.active().first && s == t.active().second) && !PetState.isInNursery(t.c, n, s) && !DungeonState.isInParty(t.c, n, s) }
    private fun pick(t: T, l: List<Pair<String, Boolean>>) = if (l.isEmpty()) null else l[t.rnd.nextInt(l.size)]

    /** Devuelve (nombre, accion); la accion devuelve true si evito provocar un problema conocido. */
    private fun pickOp(t: T): Pair<String, () -> Boolean> {
        val ops = listOf<Triple<Int, String, () -> Boolean>>(
            Triple(10, "dar") { give(t) },
            Triple(6, "activar") { activate(t) },
            Triple(6, "guarderia+") { val m = pick(t, free(t)); if (m != null) PetState.nurseryAdd(t.c, m.first, m.second); false },
            Triple(3, "guarderia-") { val m = pick(t, PetState.nurseryMembers(t.c)); if (m != null) PetState.nurseryRemove(t.c, m.first, m.second); false },
            Triple(6, "evolucionar") { evolve(t) },
            Triple(6, "mazmorra") { dungeon(t) },
            Triple(2, "abandonar") { if (DungeonState.status(t.c) != DungeonState.STATUS_IDLE) DungeonState.abandon(t.c); false },
            Triple(4, "caramelos") { PetState.addCandies(t.c, 1 + t.rnd.nextInt(5)); val m = pick(t, owned(t)); if (m != null) PetState.useCandy(t.c, m.first, m.second); false },
            Triple(5, "huevo") { egg(t) },
            Triple(4, "regalo") { gift(t) },
            Triple(4, "tiempo-guarderia") { t.nurseryTimeTravel(t.rnd.nextLong(1, 20 * 24 * HOUR_MS)); PetState.settleNursery(t.c, force = true); false },
            Triple(3, "tiempo-activo") { PetState.debugSkipHours(t.c, t.rnd.nextFloat() * 300f); PetState.loadWithDecay(t.c); false },
            Triple(2, "nivel-entrenador") { PetState.debugSetTrainerLevelMax(t.c, maxOf(PetState.trainerLevelMax(t.c), 1 + t.rnd.nextInt(60))); false },
            Triple(3, "cuidar") { PetState.tryApplyAction(t.c, listOf(PokeWidgetProvider.ACTION_FEED, PokeWidgetProvider.ACTION_PET, PokeWidgetProvider.ACTION_WASH)[t.rnd.nextInt(3)]); false },
            Triple(3, "mega") { mega(t) },
            Triple(2, "favorito") { val m = pick(t, owned(t)); if (m != null) PetState.setFavorite(t.c, m.first, m.second, t.rnd.nextBoolean()); false },
            Triple(2, "fondo") { bg(t) },
            Triple(2, "sprite") { sprite(t) }
        )
        val total = ops.sumOf { it.first }
        var r = t.rnd.nextInt(total)
        for (o in ops) { r -= o.first; if (r < 0) return o.second to o.third }
        return ops.last().let { it.second to it.third }
    }

    private fun give(t: T): Boolean {
        if (owned(t).size >= 120) return false
        val d = t.dex[t.rnd.nextInt(t.dex.size)]
        val shiny = t.rnd.nextInt(100) < 8
        t.give(d.name, shiny, 1 + t.rnd.nextInt(100))
        return false
    }

    private fun activate(t: T): Boolean {
        val m = pick(t, owned(t).filter { (n, s) -> !DungeonState.isInParty(t.c, n, s) }) ?: return false
        PetState.setPokemon(t.c, m.first, t.id(m.first), m.second)   // misma proteccion que selectPokemon: sin los de la mazmorra
        return false
    }

    private fun evolve(t: T): Boolean {
        val m = pick(t, owned(t).filter { (n, _) -> t.evoOptionsOf(n).isNotEmpty() }) ?: return false
        val opts = t.evoOptionsOf(m.first)
        val o = opts[t.rnd.nextInt(opts.size)]
        if ((PetState.levelForPokemon(t.c, m.first, m.second) ?: 1) < o.level) t.setLevel(m.first, m.second, o.level + 1)
        val (target, tid) = PetState.resolveEvolutionTarget(o.name, o.id)
        if (t.alive(target, m.second)) return true                      // problema conocido: pisaria al destino
        if (DungeonState.isInParty(t.c, m.first, m.second)) return true // problema conocido: evolucionar a quien explora
        PetState.evolveTo(t.c, m.first, target, tid, m.second)
        return false
    }

    private fun dungeon(t: T): Boolean {
        when (DungeonState.status(t.c)) {
            DungeonState.STATUS_IDLE -> {
                val leader = pick(t, free(t)) ?: return false
                val wanted = free(t).filter { it != leader }.shuffled(t.rnd).take(t.rnd.nextInt(3))
                val comps = DungeonState.usableCompanions(t.c, leader, wanted)
                DungeonState.assign(t.c, leader.first, leader.second, DungeonSimulator.maxHp(t.c, leader.first, leader.second), t.xp(leader.first, leader.second) ?: 0f,
                    autoRepeat = t.rnd.nextBoolean(), companions = comps)
            }
            DungeonState.STATUS_COOLDOWN -> { DungeonState.rewriteCooldownUntil(t.c, System.currentTimeMillis() - 1); DungeonSimulator.resolveCooldownIfReady(t.c) }
            else -> repeat(1 + t.rnd.nextInt(30)) { DungeonSimulator.runTick(t.c) }
        }
        return false
    }

    private fun egg(t: T): Boolean {
        when (t.rnd.nextInt(5)) {
            0 -> { repeat(200) { if (!PetState.hasActiveEgg(t.c)) PetState.maybeLayEgg(t.c, PetState.loadWithDecay(t.c)) } }
            1 -> { repeat(400) { if (PetState.hasActiveEgg(t.c) && !PetState.isEggReady(t.c)) PetState.maybeHatchEgg(t.c) } }
            2 -> if (PetState.hasActiveEgg(t.c) && PetState.isEggReady(t.c)) {
                val parent = PetState.eggParent(t.c)
                val baby = parent?.let { PetState.eggHatchSpecies(t.c) }
                val shiny = PetState.eggIsShiny(t.c)
                if (baby != null && (t.alive(baby, shiny) && (PetState.isInNursery(t.c, baby, shiny) || DungeonState.isInParty(t.c, baby, shiny)))) return true   // conocido
                PetState.keepEggHatch(t.c)
            }
            3 -> if (PetState.hasActiveEgg(t.c)) PetState.releaseEggForCandy(t.c)
            else -> PetState.eggCrackStage(t.c)
        }
        return false
    }

    private fun gift(t: T): Boolean {
        when (t.rnd.nextInt(4)) {
            0 -> PetState.forceNewOffer(t.c)
            1 -> PetState.currentOffer(t.c).firstOrNull()?.let { PetState.resolveOffer(t.c, it.name, it.shiny) }
            2 -> if (PetState.currentOffer(t.c).isNotEmpty()) PetState.dismissOffer(t.c)
            else -> PetState.maybeGenerateOffer(t.c)
        }
        val offer = PetState.currentOffer(t.c)
        if (offer.map { it.name }.toSet().size != offer.size) throw IllegalStateException("oferta con repetidos: ${offer.map { it.name }}")
        return false
    }

    private fun mega(t: T): Boolean {
        val (n, s) = t.active()
        val lvl = PetState.levelForPokemon(t.c, n, s) ?: 1
        val opts = PetState.megaOptionsEligible(t.c, n, maxOf(lvl, 100))
        if (opts.isNotEmpty() && t.rnd.nextBoolean()) PetState.activateMega(t.c, n, opts[t.rnd.nextInt(opts.size)].name)
        else PetState.debugDeactivateMega(t.c, n)
        PetState.displaySpriteName(t.c, n, s)
        return false
    }

    private fun bg(t: T): Boolean {
        val tokens = PetState.bgTokensAvailable(t.c)
        if (tokens > 0) {
            val all = listOf("bg_meadow", "bg_beach", "bg_forest", "bg_cave", "bg_desert", "bg_snow", "bg_city", "bg_volcano")
            PetState.unlockBackground(t.c, all[t.rnd.nextInt(all.size)])
        }
        return false
    }

    /** El sprite que dice mostrar el juego para el activo siempre existe. */
    private fun sprite(t: T): Boolean {
        val (n, s) = t.active()
        val name = PetState.displaySpriteName(t.c, n, s).lowercase()
        val set = (t.c.assets.list(if (s) "localsprites/shiny" else "localsprites/normal") ?: emptyArray()).map { it.removeSuffix(".png").lowercase() }.toSet()
        if (name !in set) throw IllegalStateException("displaySpriteName(${t.who(n, s)}) = $name no existe")
        return false
    }
}
