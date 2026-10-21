package com.example.pokegotchi

import android.content.Context
import com.example.pokegotchi.QaHarness.T
import org.json.JSONObject

/** Bloques C y H del plan: coherencia de datos/assets y curvas de progresion. SOLO PRUEBAS. */
object QaData {
    private const val S = "datos"
    private const val SC = "curvas"

    private fun listNames(c: Context, dir: String, strip: String): Set<String> =
        (c.assets.list(dir) ?: emptyArray()).map { it.removeSuffix(strip).lowercase() }.toSet()

    fun run(t: T) {
        val c = t.c
        val names = t.dex.map { it.name }
        t.r.info(S, "${names.size} especies en pokedex.json, ${t.evo.size} en evolutions.json")

        // --- coherencia pokedex <-> evolutions ---
        val missingInEvo = names.filter { it !in t.evo }
        val extraInEvo = t.evo.keys.filter { it !in names }
        if (missingInEvo.isNotEmpty()) t.r.problem("MEDIA", S, "${missingInEvo.size} especies de pokedex.json sin entrada en evolutions.json: ${missingInEvo.take(10)}") else t.r.ok(S, "todas las especies de la pokedex estan en evolutions.json")
        if (extraInEvo.isNotEmpty()) t.r.info(S, "${extraInEvo.size} entradas de evolutions.json que no estan en pokedex.json (formas): ${extraInEvo.take(12)}")
        val idMismatch = names.filter { t.evo[it] != null && t.evo[it]!!.id != t.id(it) }
        if (idMismatch.isNotEmpty()) t.r.problem("BAJA", S, "ids distintos entre pokedex y evolutions: ${idMismatch.take(10)}")

        // --- cadenas: referencias, ciclos, niveles ---
        var badRef = 0; var badLevel = 0; var cycles = 0
        for ((name, e) in t.evo) {
            e.from?.let { f -> if (t.evo[f] == null) { badRef++; if (badRef <= 5) t.r.problem("MEDIA", S, "$name evolves_from '$f' que no existe") } else if (t.evo[f]!!.to.none { it.name == name }) { badRef++; if (badRef <= 5) t.r.problem("BAJA", S, "$name dice evolucionar desde $f pero $f no lo lista") } }
            for (o in e.to) {
                if (t.evo[o.name] == null) { badRef++; if (badRef <= 5) t.r.problem("MEDIA", S, "$name evoluciona a '${o.name}' que no existe") }
                if (o.level !in 1..100) { badLevel++; if (badLevel <= 5) t.r.problem("MEDIA", S, "$name -> ${o.name} con nivel ${o.level}") }
            }
            // ciclo
            var cur: String? = name; var steps = 0
            while (cur != null && steps++ < 12) cur = t.evo[cur]?.from
            if (steps >= 12) { cycles++; t.r.problem("GRAVE", S, "ciclo en la cadena de $name") }
        }
        if (badRef == 0 && badLevel == 0 && cycles == 0) t.r.ok(S, "cadenas evolutivas coherentes (referencias, niveles, sin ciclos)")
        val roots = t.evo.values.count { it.from == null }
        t.r.info(S, "$roots formas base; ${t.evo.values.count { it.to.size > 1 }} especies con ramas")

        // --- stats y ritmos de crecimiento ---
        val base = JSONObject(c.assets.open("base_stats.json").bufferedReader().use { it.readText() })
        val growth = JSONObject(c.assets.open("growth_rates.json").bufferedReader().use { it.readText() })
        val noStats = names.filter { !base.has(it) }
        val noGrowth = names.filter { !growth.has(it) }
        if (noStats.isNotEmpty()) t.r.problem("MEDIA", S, "${noStats.size} especies sin base_stats (caen a la media): ${noStats.take(12)}") else t.r.ok(S, "base_stats cubre todas las especies")
        if (noGrowth.isNotEmpty()) t.r.problem("MEDIA", S, "${noGrowth.size} especies sin ritmo de crecimiento (caen a 'medium'): ${noGrowth.take(12)}") else t.r.ok(S, "growth_rates cubre todas las especies")
        val unknownRates = names.mapNotNull { runCatching { growth.getString(it) }.getOrNull() }.toSet() - setOf("fast", "medium", "medium-slow", "slow", "slow-then-very-fast", "fast-then-very-slow")
        if (unknownRates.isNotEmpty()) t.r.problem("MEDIA", S, "ritmos de crecimiento desconocidos (caen a 1.0): $unknownRates")
        val badStats = names.filter { n -> base.optJSONObject(n)?.let { o -> listOf("hp", "speed", "defense", "attack", "special_attack").any { k -> !o.has(k) || o.optInt(k, -1) <= 0 } } == true }
        if (badStats.isNotEmpty()) t.r.problem("MEDIA", S, "especies con stats base invalidas (<=0 o ausentes): ${badStats.take(10)}")

        // --- sprites y recursos ---
        val normal = listNames(c, "localsprites/normal", ".png"); val shiny = listNames(c, "localsprites/shiny", ".png")
        val noNormal = names.filter { it !in normal }; val noShiny = names.filter { it !in shiny }
        if (noNormal.isNotEmpty()) t.r.problem("MEDIA", S, "${noNormal.size} especies sin sprite normal en localsprites: ${noNormal.take(15)}") else t.r.ok(S, "sprite normal local para las ${names.size} especies")
        if (noShiny.isNotEmpty()) t.r.problem("MEDIA", S, "${noShiny.size} especies sin sprite shiny en localsprites: ${noShiny.take(15)}") else t.r.ok(S, "sprite shiny local para las ${names.size} especies")
        val cries = listNames(c, "cries", ".ogg")
        val noCry = names.filter { it !in cries }
        if (noCry.isNotEmpty()) t.r.problem("BAJA", S, "${noCry.size} especies sin grito: ${noCry.take(15)}") else t.r.ok(S, "grito para todas las especies")
        // Sprites de mazmorra (los resuelve DungeonSpriteRepository con su propia normalizacion de nombres).
        var noDungeon = ArrayList<String>(); var noDungeonShiny = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        for (n in names) {
            if (DungeonSpriteRepository.frames(c, n, false, DungeonSpriteRepository.Direction.DOWN).isNullOrEmpty()) noDungeon.add(n)
            if (DungeonSpriteRepository.frames(c, n, true, DungeonSpriteRepository.Direction.DOWN).isNullOrEmpty()) noDungeonShiny.add(n)
        }
        if (noDungeon.isNotEmpty()) t.r.problem("MEDIA", S, "${noDungeon.size} especies sin sprite de mazmorra: ${noDungeon.take(20)}") else t.r.ok(S, "sprite de mazmorra para todas (${System.currentTimeMillis() - t0} ms)")
        if (noDungeonShiny.isNotEmpty()) t.r.problem("MEDIA", S, "${noDungeonShiny.size} especies sin sprite de mazmorra shiny: ${noDungeonShiny.take(20)}")
        // Las 4 direcciones de una muestra.
        for (d in DungeonSpriteRepository.Direction.values()) {
            val bad = names.take(150).filter { DungeonSpriteRepository.frames(c, it, false, d).isNullOrEmpty() }
            if (bad.isNotEmpty()) t.r.problem("MEDIA", S, "direccion $d sin frames para ${bad.size} especies de la muestra: ${bad.take(8)}")
        }
        // Objetos de mazmorra: cada icono del catalogo existe.
        val catalog = JSONObject(c.assets.open("dungeonitems_catalog.json").bufferedReader().use { it.readText() })
        val icons = (c.assets.list("dungeonitems") ?: emptyArray()).map { it.removeSuffix(".png") }.toSet()
        val missingIcons = catalog.keys().asSequence().filter { it !in icons }.toList()
        if (missingIcons.isNotEmpty()) t.r.problem("BAJA", S, "${missingIcons.size} objetos del catalogo sin icono: ${missingIcons.take(10)}") else t.r.ok(S, "iconos de todos los objetos del catalogo")

        // --- megas ---
        val megas = JSONObject(c.assets.open("megas.json").bufferedReader().use { it.readText() })
        var megaBad = 0
        for (sp in megas.keys()) {
            if (t.evo[sp] == null && sp !in names) { megaBad++; t.r.problem("BAJA", S, "megas.json: especie '$sp' desconocida") }
            val arr = megas.getJSONArray(sp)
            for (i in 0 until arr.length()) {
                val nm = arr.getJSONObject(i).getString("name")
                if (nm !in normal) { megaBad++; if (megaBad <= 8) t.r.problem("MEDIA", S, "mega '$nm' (de $sp) sin sprite normal local") }
                if (nm !in shiny) { megaBad++; if (megaBad <= 8) t.r.problem("BAJA", S, "mega '$nm' (de $sp) sin sprite shiny local") }
            }
        }
        if (megaBad == 0) t.r.ok(S, "todas las megas/formas de megas.json tienen sprite")

        // --- el sprite que dice mostrar el juego existe? (muestra por mecanicas) ---
        t.wipe()
        var missingDisplay = 0
        for (n in names) {
            for (shinyFlag in listOf(false, true)) {
                t.give(n, shinyFlag, 1)
                val s = PetState.displaySpriteName(c, n, shinyFlag)
                val set = if (shinyFlag) shiny else normal
                if (s.lowercase() !in set) { missingDisplay++; if (missingDisplay <= 12) t.r.problem("MEDIA", S, "displaySpriteName($n, shiny=$shinyFlag) = '$s' no existe como sprite local") }
            }
        }
        if (missingDisplay == 0) t.r.ok(S, "displaySpriteName devuelve siempre un sprite existente (estado base, ${names.size * 2} individuos)") else t.r.info(S, "$missingDisplay displaySpriteName apuntan a sprites inexistentes")
    }

    fun curves(t: T) {
        val c = t.c
        // Una especie por ritmo de crecimiento.
        val perRate = LinkedHashMap<String, String>()
        for (d in t.dex) perRate.putIfAbsent(PetState.growthRateOf(c, d.name), d.name)
        t.r.info(SC, "ritmos y especie de muestra: $perRate")
        for ((rate, sp) in perRate) {
            var prev = -1f; var okRate = true
            for (l in 1..100) {
                val x = PetState.xpForLevel(c, l, sp)
                if (x < prev) { okRate = false; t.r.problem("GRAVE", SC, "$rate/$sp: xpForLevel no es monotona en el nivel $l"); break }
                if (PetState.levelOf(c, x, sp) != l) { okRate = false; t.r.problem("MEDIA", SC, "$rate/$sp: levelOf(xpForLevel($l)) = ${PetState.levelOf(c, x, sp)}"); break }
                prev = x
            }
            val p = PetState.progressOf(c, PetState.xpForLevel(c, 50, sp) + 1f, sp)
            if (p !in 0f..1f) t.r.problem("MEDIA", SC, "progressOf fuera de [0,1]: $p")
            if (PetState.levelOf(c, 1e9f, sp) != 100) t.r.problem("MEDIA", SC, "$rate: levelOf(1e9) = ${PetState.levelOf(c, 1e9f, sp)}")
            if (PetState.levelOf(c, -5f, sp) != 1) t.r.problem("BAJA", SC, "$rate: levelOf(-5) = ${PetState.levelOf(c, -5f, sp)}")
            if (okRate) t.r.ok(SC, "ritmo $rate: monotono, inverso exacto, nivel 100 = ${"%.0f".format(PetState.xpForLevel(c, 100, sp))} XP")
        }
        // XP por tiempo hasta el nivel 100 del activo (TIME_XP_PER_H = 40): dias de cuidado puro.
        for ((rate, sp) in perRate) {
            val days = PetState.xpForLevel(c, 100, sp) / 40f / 24f
            t.r.info(SC, "nivel 100 ($rate) solo por tiempo del activo: ${"%.1f".format(days)} dias (${"%.1f".format(days / 30f)} meses)")
        }
        // Escalera de desbloqueos.
        var prevSlots = 0
        for (l in 1..100) {
            val s = NurseryRules.slotsForTrainerLevel(l)
            if (s < prevSlots) t.r.problem("MEDIA", SC, "plazas de guarderia bajan en el nivel $l ($prevSlots -> $s)")
            prevSlots = s
        }
        t.r.info(SC, "plazas de guarderia: nivel 6=${NurseryRules.slotsForTrainerLevel(6)}, 8=${NurseryRules.slotsForTrainerLevel(8)}, 14=${NurseryRules.slotsForTrainerLevel(14)}, 24=${NurseryRules.slotsForTrainerLevel(24)}, 35=${NurseryRules.slotsForTrainerLevel(35)}, 50=${NurseryRules.slotsForTrainerLevel(50)}, 100=${NurseryRules.slotsForTrainerLevel(100)}")
        var prevComp = 0
        for (l in 1..100) { val m = ExplorerPartyRules.maxCompanions(l); if (m < prevComp) t.r.problem("MEDIA", SC, "companeros bajan en el nivel $l"); prevComp = m }
        // Nivel de entrenador: ¿se llega al 100? Informe del pozo teorico maximo.
        t.wipe(); PetState.debugSetTrainerLevelMax(c, null)
        val report = PetState.debugTrainerReport(c)
        t.r.info(SC, "informe del nivel de entrenador (extracto): " + report.lines().take(14).joinToString(" / "))
    }

    /** Deja la cuenta secundaria con TODAS las especies como individuos (algunos shiny), niveles al azar y perfil de entrenador, en UN solo
     *  commit (para poder probar la interfaz con una coleccion enorme). [count] limita cuantas especies. */
    fun populate(t: T, count: Int) {
        t.wipe()
        PetState.setTrainerName(t.c, "QA"); PetState.setTrainerSprite(t.c, "CHICO")
        val e = t.st().edit()
        val unlocked = HashSet(t.st().getStringSet("unlocked_species", emptySet()) ?: emptySet())
        var made = 0
        for (d in t.dex) {
            if (made >= count) break
            if (d.name == "pikachu") continue
            for (shiny in listOf(false, t.rnd.nextInt(100) < 12)) {
                val slot = if (shiny) "${d.name}#shiny" else d.name
                val level = 1 + t.rnd.nextInt(100)
                e.putInt("$slot.dexid", d.id)
                    .putFloat("$slot.health", 30f + t.rnd.nextFloat() * 70f).putFloat("$slot.hygiene", 30f + t.rnd.nextFloat() * 70f).putFloat("$slot.happiness", 30f + t.rnd.nextFloat() * 70f)
                    .putFloat("$slot.xp", PetState.xpForLevel(t.c, level, d.name) + t.rnd.nextFloat() * 10f)
                    .putLong("$slot.last_update", System.currentTimeMillis())
                if (d.name in PetState.GENDER_SPECIES) e.putString("$slot.gender", PetState.rollGender(d.name))
                made++
            }
            unlocked.add(d.name)
        }
        e.putStringSet("unlocked_species", unlocked).putInt("candy_count", 30).commit()
        t.r.info("poblar", "$made individuos creados (${unlocked.size} especies), perfil de entrenador 'QA', nivel de entrenador max ${PetState.trainerLevelMax(t.c)}")
    }

    /** Pokedex completa (pedido del usuario para la cuenta secundaria): desbloquea TODAS las especies del juego de una vez, sin crear
     *  individuos ni tocar nada mas (a diferencia de [populate], no borra el estado). Con todo desbloqueado ya no quedan regalos que ofrecer. */
    fun dexFull(t: T) {
        val unlocked = HashSet(t.st().getStringSet("unlocked_species", emptySet()) ?: emptySet())
        val e = t.st().edit()
        val now = System.currentTimeMillis()
        var added = 0
        for (d in t.dex) if (unlocked.add(d.name)) { e.putLong("${d.name}.unlocked_at", now); added++ }
        e.putStringSet("unlocked_species", unlocked).commit()
        t.r.info("pokedex", "$added especies desbloqueadas de ${t.dex.size}: la Pokedex queda completa (${unlocked.size} registradas)")
    }

    /** Un Pokemon de CADA especie del juego en "Mis Pokemon" (nivel 1, barras al azar), sin borrar nada: solo se crean los que faltan. Pensado para
     *  la cuenta secundaria, junto con [dexFull] (la Pokedex ya completa no enseña "tus" Pokemon si no tienes un individuo de cada uno). */
    fun ownAll(t: T) {
        // Sin perfil de entrenador la app manda a la pantalla de alta (y no enseña nada de esto): se deja el mismo que usa [populate].
        if (!PetState.hasTrainerProfile(t.c)) { PetState.setTrainerName(t.c, "QA"); PetState.setTrainerSprite(t.c, "CHICO") }
        dexFull(t)
        val e = t.st().edit()
        val now = System.currentTimeMillis()
        var made = 0
        for (d in t.dex) {
            if (PetState.hasIndividual(t.c, d.name, false)) continue
            e.putInt("${d.name}.dexid", d.id)
                .putFloat("${d.name}.health", 60f + t.rnd.nextFloat() * 40f).putFloat("${d.name}.hygiene", 60f + t.rnd.nextFloat() * 40f).putFloat("${d.name}.happiness", 60f + t.rnd.nextFloat() * 40f)
                .putFloat("${d.name}.xp", 0f).putLong("${d.name}.last_update", now)
            if (d.name in PetState.GENDER_SPECIES) e.putString("${d.name}.gender", PetState.rollGender(d.name))
            made++
        }
        e.commit()
        t.r.info("pokedex", "$made Pokemon nuevos (nivel 1): ahora hay ${PetState.ownedIndividuals(t.c).size} individuos para ${t.dex.size} especies, nivel de entrenador ${PetState.trainerLevel(t.c)}")
    }

    /** Caramelos SIN borrar nada (a diferencia de las suites de conflictos): la autocomprobacion del juego (no activo / activo con XP por tiempo
     *  pendiente), mas subida de nivel, sin caramelos, shiny, y varios ritmos de crecimiento. Deja los caramelos y la XP como estaban. */
    fun candies(t: T) {
        val S = "caramelos"
        val c = t.c; val p = t.st()
        val candiesBefore = PetState.candyCount(c)
        // 1) la autocomprobacion que ya trae el juego (se restaura sola)
        PetState.debugCandySelfTest(c).lines().forEach { l -> if (l.contains("FALLO")) t.r.problem("MEDIA", S, l) else t.r.info(S, l) }
        PetState.debugOverflowSelfTest(c).lines().forEach { l -> if (l.contains("FALLO")) t.r.problem("MEDIA", S, l) else t.r.info(S, l) }
        val active = t.active()
        val pool = PetState.ownedIndividuals(c).filter { it != active && !PetState.isInNursery(c, it.first, it.second) && !DungeonState.isInParty(c, it.first, it.second) && (PetState.levelForPokemon(c, it.first, it.second) ?: 100) < 100 }
        if (pool.isEmpty()) { t.r.info(S, "no hay Pokemon libres para el resto de pruebas"); return }
        val touched = LinkedHashMap<Pair<String, Boolean>, Float>()   // individuo -> XP original (para restaurarlo)
        val poolBefore = PetState.overflowXp(c)
        fun remember(m: Pair<String, Boolean>) { if (m !in touched) touched[m] = PetState.rawStats(c, m.first, m.second)?.xp ?: 0f }
        try {
            // 2) subida de nivel: con el XP justo por debajo del siguiente nivel, un caramelo sube exactamente uno
            val up = pool.first(); remember(up)
            val lvl = PetState.levelForPokemon(c, up.first, up.second) ?: 1
            PetState.debugSetIndividualStats(c, up.first, up.second, xp = PetState.xpForLevel(c, lvl + 1, up.first) - 0.5f)
            p.edit().putInt("candy_count", 3).commit()
            val use = PetState.useCandy(c, up.first, up.second)
            if (use == null || !use.leveledUp || use.newLevel != lvl + 1 || PetState.candyCount(c) != 2) t.r.problem("MEDIA", S, "con el XP justo por debajo del nivel ${lvl + 1}, un caramelo dio $use (esperado: sube a ${lvl + 1}); caramelos=${PetState.candyCount(c)}")
            else t.r.ok(S, "un caramelo con el XP justo por debajo sube de Nv$lvl a Nv${use.newLevel} (+${"%.1f".format(use.xp)} XP) y gasta 1")
            val lvlNow = PetState.levelForPokemon(c, up.first, up.second)
            if (lvlNow != lvl + 1) t.r.problem("MEDIA", S, "el nivel guardado tras el caramelo es $lvlNow (esperado ${lvl + 1})")
            // 2b) varios de golpe == uno a uno (la XP de cada caramelo depende del nivel en que se da), y la vista previa coincide con lo real
            run {
                val m = pool.first(); remember(m)
                val startXp = PetState.xpForLevel(c, 61, m.first) - 2f   // a 2 XP de subir: el 1.º caramelo cruza de nivel y los demas ya cuestan mas
                PetState.debugSetIndividualStats(c, m.first, m.second, xp = startXp)
                p.edit().putInt("candy_count", 40).commit()
                val plan = PetState.candyPlan(c, m.first, m.second, 25)
                repeat(25) { PetState.useCandy(c, m.first, m.second) }
                val oneByOne = PetState.rawStats(c, m.first, m.second)?.xp ?: 0f
                PetState.debugSetIndividualStats(c, m.first, m.second, xp = startXp)
                p.edit().putInt("candy_count", 40).commit()
                val batch = PetState.useCandies(c, m.first, m.second, 25)
                val batchXp = PetState.rawStats(c, m.first, m.second)?.xp ?: 0f
                val ok = batch != null && batch.used == 25 && Math.abs(batchXp - oneByOne) < 0.05f && PetState.candyCount(c) == 15 &&
                    plan != null && plan.used == 25 && Math.abs((plan.xpGained) - (batchXp - startXp)) < 0.05f &&
                    plan.levelAfter == PetState.levelOf(c, batchXp, m.first) && batch.newLevel == plan.levelAfter
                if (!ok) t.r.problem("MEDIA", S, "25 caramelos de golpe: xp $startXp -> $batchXp (uno a uno: $oneByOne), uso=$batch, plan=$plan, caramelos=${PetState.candyCount(c)}")
                else t.r.ok(S, "25 caramelos de golpe = 25 uno a uno (Nv${plan!!.levelBefore} -> Nv${plan.levelAfter}, +${"%.0f".format(plan.xpGained)} XP) y la vista previa coincide")
            }
            // 2c) tope: a un paso del 100 solo hace falta 1 caramelo aunque se pidan 10
            run {
                val m = pool.last(); remember(m)
                PetState.debugSetIndividualStats(c, m.first, m.second, xp = PetState.xpForLevel(c, 100, m.first) - 3f)
                p.edit().putInt("candy_count", 10).commit()
                val plan = PetState.candyPlan(c, m.first, m.second, 10)
                val u = PetState.useCandies(c, m.first, m.second, 10)
                val lv = PetState.levelForPokemon(c, m.first, m.second)
                if (u == null || u.used != 1 || plan?.used != 1 || lv != 100 || PetState.candyCount(c) != 9) t.r.problem("MEDIA", S, "a 3 XP del nivel 100 con 10 caramelos: uso=$u, plan=$plan, nivel=$lv, caramelos=${PetState.candyCount(c)} (esperado: gasta 1, queda en 100 y sobran 9)")
                else t.r.ok(S, "a un paso del nivel 100 se gasta 1 caramelo aunque se pidan 10 (y el resto no se gasta)")
            }
            // 3) sin caramelos: no se gasta ni se baja de 0
            p.edit().putInt("candy_count", 0).commit()
            val xp0 = PetState.rawStats(c, up.first, up.second)?.xp ?: 0f
            val none = PetState.useCandy(c, up.first, up.second)
            val xp1 = PetState.rawStats(c, up.first, up.second)?.xp ?: 0f
            if (none != null || PetState.candyCount(c) != 0 || xp1 != xp0) t.r.problem("MEDIA", S, "sin caramelos useCandy dio $none, caramelos=${PetState.candyCount(c)}, xp $xp0 -> $xp1") else t.r.ok(S, "sin caramelos no pasa nada")
            // 4) varios ritmos de crecimiento: cada uno suma exactamente lo que dice candyXpFor y el nivel nunca baja
            val groups = pool.drop(1).groupBy { PetState.growthRateOf(c, it.first) }
            val sample = groups.values.map { it.first() }
            for (m in sample) {
                remember(m)
                p.edit().putInt("candy_count", 1).commit()
                val xpB = PetState.rawStats(c, m.first, m.second)?.xp ?: 0f
                val lvB = PetState.levelOf(c, xpB, m.first)
                val expected = PetState.candyXpFor(c, m.first, xpB) ?: 0f
                val u = PetState.useCandy(c, m.first, m.second)
                val xpA = PetState.rawStats(c, m.first, m.second)?.xp ?: 0f
                val ok = u != null && Math.abs(xpA - xpB - expected) < 0.01f && PetState.levelOf(c, xpA, m.first) >= lvB && PetState.candyCount(c) == 0
                if (!ok) t.r.problem("MEDIA", S, "${m.first} (${PetState.growthRateOf(c, m.first)}): xp $xpB -> $xpA, esperado +$expected, uso=$u, caramelos=${PetState.candyCount(c)}")
            }
            t.r.ok(S, "${sample.size} ritmos de crecimiento (${groups.keys.joinToString()}): cada caramelo suma lo esperado")
            // 5) shiny: el caramelo va al individuo shiny y no toca al normal
            val sh = pool.first().first
            val hadShiny = PetState.hasIndividual(c, sh, true)
            if (!hadShiny) PetState.rollFreshIndividual(c, sh, true, t.id(sh))
            try {
                val normalB = PetState.rawStats(c, sh, false)?.xp ?: 0f; val shinyB = PetState.rawStats(c, sh, true)?.xp ?: 0f
                p.edit().putInt("candy_count", 1).commit()
                val u = PetState.useCandy(c, sh, true)
                val normalA = PetState.rawStats(c, sh, false)?.xp ?: 0f; val shinyA = PetState.rawStats(c, sh, true)?.xp ?: 0f
                if (u == null || shinyA <= shinyB || normalA != normalB) t.r.problem("MEDIA", S, "caramelo a $sh shiny: uso=$u, xp shiny $shinyB -> $shinyA, xp normal $normalB -> $normalA") else t.r.ok(S, "caramelo a un shiny: solo sube el shiny (+${"%.1f".format(shinyA - shinyB)} XP)")
            } finally { if (!hadShiny) PetState.debugDeleteIndividual(c, sh, true) }
            t.reportInvariants(S, "tras probar los caramelos")
        } finally {
            for ((m, xp) in touched) PetState.debugSetIndividualStats(c, m.first, m.second, xp = xp)
            p.edit().putInt("candy_count", candiesBefore).putFloat("overflow_xp", poolBefore).commit()
            t.r.info(S, "restaurado: caramelos=${PetState.candyCount(c)} (antes $candiesBefore), XP devuelta a ${touched.size} Pokemon")
        }
    }

    /** Gritos: cada especie tiene archivo local y SoundPool lo carga; avisa de gritos identicos entre especies distintas (archivo equivocado). */
    fun cries(t: T) {
        val names = t.dex.map { it.name }
        val t0 = System.currentTimeMillis()
        val report = SoundManager.debugCryReport(t.c, names)
        val lines = report.lines()
        val noFile = lines.filter { it.contains("SIN archivo") }
        val failed = lines.filter { it.contains("carga=FALLO") }
        t.r.info("gritos", "${lines.size} especies comprobadas en ${(System.currentTimeMillis() - t0) / 1000} s: sin archivo ${noFile.size}, no cargan ${failed.size}")
        // Los que SoundPool rechaza se tocan con MediaPlayer (SoundManager.playCryOrFallback): solo es problema si tampoco suenan asi.
        val lost = failed.filter { it.contains("MediaPlayer=FALLO") }
        val rescued = failed - lost.toSet()
        if (rescued.isNotEmpty()) t.r.info("gritos", "${rescued.size} gritos que SoundPool no carga pero MediaPlayer si (se usa de respaldo): ${rescued.take(8).joinToString { it.substringBefore(":") }}")
        if (lost.isNotEmpty()) t.r.problem("MEDIA", "gritos", "${lost.size} gritos que ni SoundPool ni MediaPlayer consiguen cargar: ${lost.take(8).joinToString { it.substringBefore(":") }}")
        if (noFile.isNotEmpty()) t.r.problem("BAJA", "gritos", "${noFile.size} especies sin archivo de grito local (usan el generico o la red): ${noFile.take(10).joinToString { it.substringBefore(":") }}")
        val byMd5 = lines.filter { it.contains("md5=") }.groupBy { it.substringAfter("md5=").take(8) }.filter { it.value.size > 1 }
        if (byMd5.isNotEmpty()) t.r.info("gritos", "gritos con contenido identico entre especies (misma huella): ${byMd5.values.take(6).map { l -> l.map { it.substringBefore(":") } }}")
    }

    /** Sprites escalados (los 3 modos de nitidez) para todas las especies: decodifican, tamaños razonables y tiempo. */
    fun sprites(t: T) {
        val names = t.dex.map { it.name }
        for (mode in listOf("none", "partial", "full")) {
            var fail = 0; var maxW = 0; var maxH = 0; var maxName = ""; val t0 = System.currentTimeMillis()
            for (n in names) for (sh in listOf(false, true)) {
                val b = try { SpriteRepository.localFirstFrameScaled(t.c, n, sh, mode) } catch (e: Throwable) { null }
                if (b == null) { fail++; if (fail <= 6) t.r.problem("MEDIA", "sprites", "modo $mode: $n${if (sh) "✨" else ""} no decodifica") }
                else { if (b.width * b.height > maxW * maxH) { maxW = b.width; maxH = b.height; maxName = n }; b.recycle() }
            }
            t.r.info("sprites", "modo $mode: ${names.size * 2} sprites en ${System.currentTimeMillis() - t0} ms, fallos $fail, el mayor ${maxW}x$maxH ($maxName)")
            if (maxW * maxH > 1024 * 1024) t.r.problem("BAJA", "sprites", "modo $mode: sprite de ${maxW}x$maxH ($maxName): ${maxW * maxH * 4 / 1024 / 1024} MB como bitmap")
        }
    }

    /** Niveles de evolucion: justo en el nivel indicado se ofrece la evolucion y un nivel antes no; cadenas con niveles no crecientes. */
    fun evolutions(t: T) {
        t.wipe()
        val c = t.c
        var checked = 0; var bad = 0; var nonMonotonic = 0
        val odd = ArrayList<String>()
        for ((name, e) in t.evo) {
            for (o in e.to) {
                checked++
                val xpBefore = PetState.xpForLevel(c, o.level, name) - 1f
                val xpAt = PetState.xpForLevel(c, o.level, name)
                val before = PetState.pendingEvolutionFor(c, name, PetState.Stats(100f, 100f, 100f, xpBefore), false)
                val at = PetState.pendingEvolutionFor(c, name, PetState.Stats(100f, 100f, 100f, xpAt), false)
                if (o.level > 1 && before?.evolvesTo?.any { it.name == o.name } == true) { bad++; if (bad <= 10) t.r.problem("MEDIA", "evoluciones", "$name -> ${o.name}: se ofrece UN nivel antes de ${o.level}") }
                if (at?.evolvesTo?.any { it.name == o.name } != true) { bad++; if (bad <= 10) t.r.problem("MEDIA", "evoluciones", "$name -> ${o.name}: NO se ofrece en el nivel ${o.level}") }
                // cadena: lo que evoluciona DESPUES no deberia pedir menos nivel que la evolucion que acaba de ocurrir
                val next = t.evo[o.name]?.to ?: emptyList()
                for (n2 in next) if (n2.level < o.level) { nonMonotonic++; if (odd.size < 12) odd.add("$name -> ${o.name} (Nv${o.level}) -> ${n2.name} (Nv${n2.level})") }
            }
        }
        if (bad == 0) t.r.ok("evoluciones", "$checked evoluciones: se ofrecen justo en su nivel (ni antes ni despues)")
        t.r.info("evoluciones", "$nonMonotonic cadenas donde la siguiente evolucion pide MENOS nivel que la anterior (se evolucionaria dos veces seguidas): ${odd.take(8)}")
        // Formas finales y huevos: la forma base existe y es base.
        var badBase = 0
        for ((name, e) in t.evo) {
            if (e.to.isNotEmpty()) continue
            var cur: String = name; var guard = 0
            while (t.evo[cur]?.from != null && guard++ < 10) cur = t.evo[cur]!!.from!!
            if (t.evo[cur]?.from != null) { badBase++; t.r.problem("MEDIA", "evoluciones", "$name: no se encuentra su forma base (hasta $cur)") }
        }
        if (badBase == 0) t.r.ok("evoluciones", "toda forma final tiene forma base (huevos)")
    }
}
