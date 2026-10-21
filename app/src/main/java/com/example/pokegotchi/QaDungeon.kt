package com.example.pokegotchi

import com.example.pokegotchi.QaHarness.T

/** Bloque E del plan: mazmorra (mapas, carreras persistidas, puesta al dia, jefes, piso 100, guardado/recarga). SOLO PRUEBAS. */
object QaDungeon {
    private const val S = "mazmorra"
    private const val DAY_MS = 24 * 3_600_000L

    fun run(t: T, n: Int) {
        t.case(S, "validez de los mapas de los pisos 1-100") { maps(t, n) }
        t.case(S, "se llega a la escalera en todos los pisos (lider invencible)") { reachExit(t, n) }
        t.case(S, "carreras reales con equipos de 1 a 3") { persisted(t, n) }
        t.case(S, "puesta al dia tras ausencias largas") { catchUp(t, n >= 150) }
        t.case(S, "jefes y piso 100") { bosses(t) }
        t.case(S, "guardado y recarga a mitad de carrera") { roundtrip(t, n) }
        t.case(S, "simulaciones de balance variadas") { sims(t, n) }
        t.case(S, "notificacion de la mazmorra en todos los estados") { notifications(t) }
        t.case(S, "los compañeros nunca se teletransportan ni se alejan del lider") { follow(t, n) }
        t.case(S, "BFS de los enemigos: mismo primer paso que la version con HashMap") { bfsSame(t, n) }
    }

    /** El BFS de la IA de los enemigos se reescribio con arrays (mucho mas rapido en la puesta al dia): debe dar EXACTAMENTE el mismo primer paso que el de antes. */
    private fun bfsReference(map: DungeonState.DungeonMap, fromX: Int, fromY: Int, toX: Int, toY: Int): Pair<Int, Int> {
        if (fromX == toX && fromY == toY) return fromX to fromY
        fun idx(x: Int, y: Int) = y * map.width + x
        val startIdx = idx(fromX, fromY); val targetIdx = idx(toX, toY)
        val parent = HashMap<Int, Int>()
        val visited = HashSet<Int>().apply { add(startIdx) }
        val queue = ArrayDeque<Int>().apply { add(startIdx) }
        var found = false
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == targetIdx) { found = true; break }
            val cx = cur % map.width; val cy = cur / map.width
            for ((nx, ny) in DungeonSimulator.walkableNeighbors(map, cx, cy)) {
                val nIdx = idx(nx, ny)
                if (visited.add(nIdx)) { parent[nIdx] = cur; queue.add(nIdx) }
            }
        }
        if (!found) return fromX to fromY
        var cur = targetIdx
        while (parent[cur] != startIdx && parent.containsKey(cur)) cur = parent[cur]!!
        return (cur % map.width) to (cur / map.width)
    }

    private fun bfsSame(t: T, n: Int) {
        t.wipe()
        var pairs = 0; var bad = 0
        val t0 = System.currentTimeMillis(); var newNs = 0L; var oldNs = 0L
        for (floor in 1..DungeonState.MAX_FLOOR step 3) {
            val map = DungeonSimulator.generateMap(t.c, floor, "pikachu", 1 + t.rnd.nextInt(3))
            val floorTiles = ArrayList<Pair<Int, Int>>()
            for (y in 0 until map.height) for (x in 0 until map.width) if (!map.isWall(x, y)) floorTiles.add(x to y)
            if (floorTiles.size < 2) continue
            repeat(maxOf(20, n / 3)) {
                val a = floorTiles[t.rnd.nextInt(floorTiles.size)]; val b = floorTiles[t.rnd.nextInt(floorTiles.size)]
                var s0 = System.nanoTime(); val new = DungeonSimulator.bfsNextStep(map, a.first, a.second, b.first, b.second); newNs += System.nanoTime() - s0
                s0 = System.nanoTime(); val old = bfsReference(map, a.first, a.second, b.first, b.second); oldNs += System.nanoTime() - s0
                pairs++
                if (new != old) { bad++; if (bad <= 5) t.r.problem("MEDIA", S, "bfsNextStep piso $floor de $a a $b: nuevo $new, anterior $old") }
            }
        }
        t.r.info(S, "$pairs pares comprobados en ${System.currentTimeMillis() - t0} ms: BFS nuevo ${newNs / pairs / 1000} us por llamada, anterior ${oldNs / pairs / 1000} us")
        if (bad == 0) t.r.ok(S, "el BFS nuevo da el mismo primer paso que el anterior en $pairs pares")
        // Lo mismo para el flood fill del explorador (distancias y de donde viene cada casilla, con y sin casilla bloqueada).
        var floods = 0; var badFlood = 0
        for (floor in 1..DungeonState.MAX_FLOOR step 4) {
            val map = DungeonSimulator.generateMap(t.c, floor, "pikachu", 1)
            val tiles = ArrayList<Pair<Int, Int>>()
            for (y in 0 until map.height) for (x in 0 until map.width) if (!map.isWall(x, y)) tiles.add(x to y)
            repeat(6) {
                val a = tiles[t.rnd.nextInt(tiles.size)]
                val blocked = if (it % 2 == 0) (map.exitX to map.exitY) else (-1 to -1)
                val new = DungeonSimulator.floodFill(map, a.first, a.second, blocked.first, blocked.second)
                val old = floodReference(map, a.first, a.second, blocked.first, blocked.second)
                floods++
                if (!new.dist.contentEquals(old.first) || !new.parent.contentEquals(old.second)) { badFlood++; if (badFlood <= 3) t.r.problem("MEDIA", S, "floodFill piso $floor desde $a (bloqueada $blocked) difiere de la version anterior") }
            }
        }
        if (badFlood == 0) t.r.ok(S, "el flood fill nuevo da las mismas distancias y caminos en $floods casos")
    }

    private fun floodReference(map: DungeonState.DungeonMap, fromX: Int, fromY: Int, blockX: Int, blockY: Int): Pair<IntArray, IntArray> {
        fun idx(x: Int, y: Int) = y * map.width + x
        val dist = IntArray(map.width * map.height) { -1 }
        val parent = IntArray(map.width * map.height) { -1 }
        val startIdx = idx(fromX, fromY)
        dist[startIdx] = 0
        val queue = ArrayDeque<Int>().apply { add(startIdx) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val cx = cur % map.width; val cy = cur / map.width
            for ((nx, ny) in DungeonSimulator.walkableNeighbors(map, cx, cy)) {
                if (nx == blockX && ny == blockY) continue
                val nIdx = idx(nx, ny)
                if (dist[nIdx] == -1) { dist[nIdx] = dist[cur] + 1; parent[nIdx] = cur; queue.add(nIdx) }
            }
        }
        return dist to parent
    }

    /** Balance de la XP de la mazmorra POR NIVEL (no borra ni toca nada real): para una especie de referencia, a cada nivel, [n] carreras
     *  simuladas en solitario -> piso alcanzado, XP por carrera, niveles ganados, pasos, y de ahi carreras y XP por dia en bucle (ciclo = lo que
     *  tarda la carrera en segundo plano a ~32 pasos/min + el cierre de 2 min por piso). */
    fun balance(t: T, n: Int) {
        val species = "luxray"
        val c = t.c
        val factorXp = { lvl: Int -> PetState.xpForLevel(c, lvl, species) }
        for (level in listOf(5, 10, 15, 20, 30, 40, 50, 60, 70, 80, 90, 100)) {
            val startXp = factorXp(level)
            val rep = DungeonSimulator.runBalanceSimulation(c, species, false, startXp, n)
            val runs = rep.runs
            val floors = runs.map { it.floorReached }
            val xp = runs.map { it.xpGained }.average()
            val steps = runs.map { it.steps }.average()
            val levelUps = runs.map { it.levelFinal - rep.startLevel }.average()
            val cooldownMin = runs.map { it.floorReached * 2.0 }.average()
            val runMin = steps / 32.0
            val cycle = runMin + cooldownMin
            val perDay = 1440.0 / cycle
            val costNext = PetState.xpForLevel(c, level + 1, species) - startXp
            t.r.info("balance", "Nv%d: piso medio %.1f (min %d, max %d) | XP/carrera %.0f | niveles/carrera %.2f | pasos %.0f | carrera %.0f min + cierre %.0f min | %.1f carreras/dia | XP/dia %.0f | coste del nivel %.0f XP | niveles/dia %.1f"
                .format(level, floors.average(), floors.min(), floors.max(), xp, levelUps, steps, runMin, cooldownMin, perDay, xp * perDay, costNext, xp * perDay / costNext.coerceAtLeast(1f)))
        }
    }

    private fun reachable(map: DungeonState.DungeonMap, sx: Int, sy: Int): Set<Int> {
        val seen = HashSet<Int>(); val q = ArrayDeque<Int>()
        fun key(x: Int, y: Int) = y * map.width + x
        if (map.isWall(sx, sy)) return seen
        seen.add(key(sx, sy)); q.add(key(sx, sy))
        while (q.isNotEmpty()) {
            val k = q.removeFirst(); val x = k % map.width; val y = k / map.width
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx; val ny = y + dy
                if (!map.isWall(nx, ny) && seen.add(key(nx, ny))) q.add(key(nx, ny))
            }
        }
        return seen
    }

    private fun maps(t: T, n: Int) {
        t.wipe()
        val species = t.dex.map { it.name }
        var bad = 0; var slow = 0; var total = 0; var maxMs = 0L
        val reps = maxOf(2, n / 50)
        for (floor in 1..DungeonState.MAX_FLOOR) for (rep in 0 until reps) {
            val sp = species.random(t.rnd); val team = 1 + t.rnd.nextInt(3)
            val t0 = System.currentTimeMillis()
            val m = DungeonSimulator.generateMap(t.c, floor, sp, team)
            val ms = System.currentTimeMillis() - t0; maxMs = maxOf(maxMs, ms); if (ms > 300) slow++
            total++
            fun fail(msg: String) { bad++; if (bad <= 12) t.r.problem("GRAVE", S, "mapa piso $floor ($sp, equipo $team): $msg") }
            if (m.walls.size != m.width * m.height) fail("walls ${m.walls.size} != ${m.width}x${m.height}")
            if (m.isWall(m.startX, m.startY)) fail("la salida/entrada esta en una pared")
            if (m.isWall(m.exitX, m.exitY)) fail("la escalera esta en una pared")
            if (m.startX == m.exitX && m.startY == m.exitY) fail("entrada y escalera en la misma casilla")
            val reach = reachable(m, m.startX, m.startY)
            if ((m.exitY * m.width + m.exitX) !in reach) fail("la escalera NO es alcanzable desde la entrada")
            m.items.forEach { if (m.isWall(it.x, it.y)) fail("objeto ${it.kind} en una pared (${it.x},${it.y})") else if ((it.y * m.width + it.x) !in reach) fail("objeto ${it.kind} inalcanzable (${it.x},${it.y})") }
            if (m.items.map { it.x to it.y }.toSet().size != m.items.size) fail("dos objetos en la misma casilla")
            m.enemies.forEach { e ->
                if (m.isWall(e.x, e.y)) fail("enemigo ${e.species} en una pared")
                if (e.x == m.startX && e.y == m.startY) fail("enemigo ${e.species} sobre la entrada")
                if (e.level < 1) fail("enemigo ${e.species} con nivel ${e.level}")
            }
            if (m.enemies.map { it.x to it.y }.toSet().size != m.enemies.size) fail("dos enemigos en la misma casilla")
            if (floor % 10 == 0 && m.enemies.count { it.isBoss } != 1) fail("piso de jefe con ${m.enemies.count { it.isBoss }} jefes")
            if (floor % 10 != 0 && m.enemies.any { it.isBoss }) fail("jefe en un piso que no es de jefe")
            m.items.forEach { it.icon?.let { ic -> if (DungeonSpriteRepository.itemIcon(t.c, ic) == null) fail("icono de objeto '$ic' sin imagen") } }
        }
        if (bad == 0) t.r.ok(S, "$total mapas (pisos 1-100) validos: escalera y objetos alcanzables, sin solapes (el mas lento ${maxMs} ms, $slow > 300 ms)")
        else t.r.info(S, "$bad fallos de $total mapas")
    }

    /** Un lider invencible camina hasta la escalera en pisos de todo tipo: si nunca llega, la carrera se quedaria atascada. */
    private fun reachExit(t: T, n: Int) {
        t.wipe()
        t.give("tyranitar", false, 100)
        val sp = "tyranitar"
        var stuck = 0; var worst = 0; var sum = 0L; var samples = 0
        val floors = (1..100).filter { it % 7 == 1 || it % 10 == 0 }
        for (floor in floors) {
            val m = DungeonSimulator.generateMap(t.c, floor, sp, 1)
            val run = DungeonSimulator.RunState(floor, 1e9f, m, m.startX, m.startY, runStartXp = PetState.xpForLevel(t.c, 100, sp)).also { it.applyRealXp = false }
            var steps = 0; var arrived = false
            while (steps < 6000) {
                steps++
                val res = DungeonSimulator.stepOnce(t.c, sp, false, run)
                if (res.outcome is DungeonSimulator.Outcome.Stairs || res.outcome is DungeonSimulator.Outcome.Finished) { arrived = true; break }
                if (res.outcome is DungeonSimulator.Outcome.Defeated) { arrived = true; break }
            }
            if (!arrived) { stuck++; t.r.problem("GRAVE", S, "piso $floor: tras 6000 pasos el lider invencible NO llega a la escalera (carrera atascada)") }
            else { worst = maxOf(worst, steps); sum += steps; samples++ }
        }
        if (stuck == 0) t.r.ok(S, "en ${floors.size} pisos (1-100) el lider llega siempre a la escalera: media ${sum / maxOf(samples, 1)} pasos, peor $worst")
    }

    private fun persisted(t: T, n: Int) {
        val runs = maxOf(6, n / 20)
        var defeats = 0; var finished = 0; var floorSum = 0; var bad = 0
        for (r in 0 until runs) {
            t.wipe()
            val trainerLevel = listOf(1, 10, 15, 30)[t.rnd.nextInt(4)]
            PetState.debugSetTrainerLevelMax(t.c, trainerLevel)
            val pool = t.dex.map { it.name }.filter { t.evo[it]?.to?.isEmpty() == true && t.evo[it]?.from != null }.shuffled(t.rnd).take(4)
            pool.forEachIndexed { i, nm -> t.give(nm, false, listOf(5, 20, 45, 80, 100)[t.rnd.nextInt(5)]) }
            val leader = pool[0]
            val comps = DungeonState.usableCompanions(t.c, leader to false, pool.drop(1).map { it to false })
            val auto = t.rnd.nextBoolean()
            DungeonState.assign(t.c, leader, false, DungeonSimulator.maxHp(t.c, leader, false), t.xp(leader) ?: 0f, autoRepeat = auto, companions = comps)
            var lastXp = t.xp(leader) ?: 0f
            var ticks = 0; var runsEnded = 0
            while (ticks < 400 && runsEnded < 3) {
                ticks++
                val statusBefore = DungeonState.status(t.c)
                if (statusBefore == DungeonState.STATUS_COOLDOWN) {
                    DungeonState.rewriteCooldownUntil(t.c, System.currentTimeMillis() - 1)
                    DungeonSimulator.resolveCooldownIfReady(t.c); runsEnded++
                } else if (statusBefore == DungeonState.STATUS_IDLE) break
                else DungeonSimulator.runTick(t.c)
                // invariantes de la carrera guardada
                val st = DungeonState.status(t.c)
                val floor = DungeonState.currentFloor(t.c)
                if (floor !in 1..DungeonState.MAX_FLOOR) { bad++; t.r.problem("GRAVE", S, "piso guardado $floor fuera de 1..100 (run $r, tick $ticks)") }
                if (st == DungeonState.STATUS_EXPLORING) {
                    val comp = DungeonState.companions(t.c).size
                    val hps = DungeonState.companionHps(t.c)
                    if (hps.size > comp) { bad++; t.r.problem("MEDIA", S, "mas vidas de compañero (${hps.size}) que compañeros ($comp)") }
                    if (DungeonState.frontMember(t.c) !in 0..comp) { bad++; t.r.problem("MEDIA", S, "delantero ${DungeonState.frontMember(t.c)} fuera del grupo de ${comp + 1}") }
                    val hp = DungeonState.currentHp(t.c)
                    if (hp.isNaN() || hp < 0f) { bad++; t.r.problem("GRAVE", S, "HP del lider invalido $hp") }
                    val rs = DungeonSimulator.loadRunState(t.c)
                    if (rs == null) { bad++; t.r.problem("GRAVE", S, "EXPLORING pero loadRunState devuelve null") }
                    else {
                        if (rs.map.isWall(rs.px, rs.py)) { bad++; t.r.problem("GRAVE", S, "el equipo esta en una pared (${rs.px},${rs.py}) piso ${rs.floor}") }
                        val alive = rs.aliveFollowers()
                        if (rs.followerTiles.size > alive) { bad++; t.r.problem("MEDIA", S, "mas casillas de seguidores (${rs.followerTiles.size}) que seguidores vivos ($alive)") }
                        val all = rs.followerTiles + (rs.px to rs.py)
                        if (all.toSet().size != all.size) { bad++; t.r.problem("MEDIA", S, "dos del equipo en la misma casilla $all") }
                    }
                }
                val x = t.xp(leader) ?: 0f
                if (x + 0.01f < lastXp) { bad++; t.r.problem("GRAVE", S, "el XP real del lider BAJA: $lastXp -> $x") }
                lastXp = x
                if (bad > 10) break
            }
            if (DungeonState.lastRunSummary(t.c)?.contains("completó") == true) finished++
            if (DungeonState.lastRunSummary(t.c)?.contains("cayó") == true) defeats++
            floorSum += DungeonState.bestFloor(t.c)
            if (!t.reportInvariants(S, "tras la carrera $r (lider $leader, ${comps.size} compañeros, auto=$auto)")) bad++
            if (bad > 10) break
        }
        t.r.info(S, "$runs carreras reales: mejor piso medio ${floorSum / runs}, derrotas $defeats, completadas $finished")
        if (bad == 0) t.r.ok(S, "carreras reales sin incoherencias")
    }

    private fun catchUp(t: T, includeMonth: Boolean) {
        for (days in if (includeMonth) listOf(0.2, 3.0, 30.0) else listOf(0.2, 3.0)) {
            t.wipe()
            t.give("garchomp", false, 80); t.give("lucario", false, 60)
            PetState.debugSetTrainerLevelMax(t.c, 30)
            DungeonState.assign(t.c, "garchomp", false, DungeonSimulator.maxHp(t.c, "garchomp", false), t.xp("garchomp") ?: 0f, autoRepeat = true, companions = listOf("lucario" to false))
            val e = t.dst().edit()
            e.putLong("last_tick_at", System.currentTimeMillis() - (days * DAY_MS).toLong()).commit()
            val xp0 = t.xp("garchomp") ?: 0f
            val t0 = System.currentTimeMillis()
            DungeonSimulator.catchUpIfStalled(t.c, 60_000L)
            val ms = System.currentTimeMillis() - t0
            t.r.info(S, "ausencia de $days dias: puesta al dia en $ms ms; estado=${DungeonState.status(t.c)}, piso=${DungeonState.currentFloor(t.c)}, mejor=${DungeonState.bestFloor(t.c)}, XP lider +${"%.0f".format((t.xp("garchomp") ?: 0f) - xp0)}, carreras de bucle=${DungeonState.loopRunsCompleted(t.c)}")
            // Tras optimizar el BFS/flood fill y las escrituras: 3 dias ~11 s, 30 dias ~2 min (antes 1 min y 10 min).
            if (ms > 240_000) t.r.problem("MEDIA", S, "la puesta al dia de $days dias tardo ${ms / 1000} s")
            t.reportInvariants(S, "tras la puesta al dia de $days dias")
        }
    }

    private fun bosses(t: T) {
        t.wipe()
        t.give("tyranitar", false, 100)
        val sp = "tyranitar"
        var checked = 0
        for (floor in listOf(10, 20, 50, 90, 100)) {
            val m = DungeonSimulator.generateMap(t.c, floor, sp, 1)
            val run = DungeonSimulator.RunState(floor, 1e9f, m, m.startX, m.startY, runStartXp = PetState.xpForLevel(t.c, 100, sp)).also { it.applyRealXp = false }
            var steps = 0; var killedBoss = false; var end: String? = null
            while (steps < 8000 && end == null) {
                steps++
                val res = DungeonSimulator.stepOnce(t.c, sp, false, run)
                val o = res.outcome
                if (o is DungeonSimulator.Outcome.Combat && o.enemy.isBoss && o.won) killedBoss = true
                if (o is DungeonSimulator.Outcome.Stairs) end = "stairs->${o.newFloor}"
                if (o is DungeonSimulator.Outcome.Finished) end = "finished"
                if (o is DungeonSimulator.Outcome.Defeated) end = "defeated"
            }
            t.r.info(S, "piso de jefe $floor: $steps pasos, jefe derrotado=$killedBoss, fin=$end")
            if (end == null) t.r.problem("GRAVE", S, "piso de jefe $floor: sin final tras 8000 pasos (carrera atascada)")
            if (floor == 100 && end != null && !end.startsWith("stairs")) t.r.info(S, "piso 100 termino con $end")
            checked++
        }
        // Piso 100 en la partida real: terminar y comprobar el cierre.
        t.wipe()
        t.give("tyranitar", false, 100)
        DungeonState.assign(t.c, sp, false, DungeonSimulator.maxHp(t.c, sp, false) * 50, t.xp(sp) ?: 0f)
        DungeonSimulator.debugJumpToFloor(t.c, 100)
        var ticks = 0
        while (DungeonState.status(t.c) == DungeonState.STATUS_EXPLORING && ticks < 3000) { ticks++; DungeonSimulator.runTick(t.c) }
        t.r.info(S, "piso 100 real: $ticks ciclos, estado final=${DungeonState.status(t.c)}, resumen='${DungeonState.lastRunSummary(t.c)}'")
        if (DungeonState.status(t.c) == DungeonState.STATUS_EXPLORING) t.r.problem("MEDIA", S, "tras 3000 ciclos en el piso 100 la carrera sigue explorando")
        t.reportInvariants(S, "tras el piso 100")
    }

    private fun roundtrip(t: T, n: Int) {
        var bad = 0
        repeat(maxOf(10, n / 10)) { i ->
            t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 30)
            val pool = listOf("garchomp", "lucario", "gengar", "dragonite").shuffled(t.rnd)
            pool.forEach { t.give(it, false, 40 + t.rnd.nextInt(50)) }
            val leader = pool[0]
            val comps = pool.drop(1).take(t.rnd.nextInt(3)).map { it to false }
            DungeonState.assign(t.c, leader, false, DungeonSimulator.maxHp(t.c, leader, false), t.xp(leader) ?: 0f, companions = comps)
            val a = DungeonSimulator.loadRunState(t.c) ?: run { bad++; t.r.problem("GRAVE", S, "loadRunState null justo tras assign"); return@repeat }
            repeat(t.rnd.nextInt(1, 120)) { DungeonSimulator.stepOnce(t.c, leader, false, a) }
            DungeonSimulator.persist(t.c, a)
            val b = DungeonSimulator.loadRunState(t.c) ?: run { bad++; t.r.problem("GRAVE", S, "loadRunState null tras persist"); return@repeat }
            fun eq(label: String, x: Any?, y: Any?) { if (x != y) { bad++; if (bad < 10) t.r.problem("MEDIA", S, "persist/load ($i): $label difiere: $x != $y") } }
            eq("piso", a.floor, b.floor); eq("posicion", a.px to a.py, b.px to b.py); eq("hp lider", a.leaderHp, b.leaderHp)
            eq("hps companeros", a.companionHps.toList(), b.companionHps.toList()); eq("delante", a.front, b.front)
            eq("enemigos", a.map.enemies.map { Triple(it.id, it.x, it.y) }.sortedBy { it.first }, b.map.enemies.map { Triple(it.id, it.x, it.y) }.sortedBy { it.first })
            eq("objetos", a.map.items.size, b.map.items.size)
            eq("casillas seguidores", a.followerTiles, b.followerTiles)
            eq("buffs", listOf(a.buffAtk, a.buffDef, a.buffSpd, a.buffAcc), listOf(b.buffAtk, b.buffDef, b.buffSpd, b.buffAcc))
            eq("xp de carrera", a.runXp, b.runXp)
        }
        if (bad == 0) t.r.ok(S, "persist/loadRunState conserva piso, posicion, vidas, delantero, enemigos, objetos, casillas y buffs")
    }

    /** Mide, sobre mapas reales, cuanto se separan los compañeros del que lidera y si dan saltos de varias casillas en un paso sin combate. */
    private fun follow(t: T, n: Int) {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 30)
        val pool = listOf("garchomp", "lucario", "gengar")
        pool.forEach { t.give(it, false, 60) }
        val runsN = maxOf(20, n / 5)
        var steps = 0; var farSteps = 0; var jumps = 0; var maxFar = 0
        val jumpCtx = HashMap<String, Int>()
        val examples = ArrayList<String>()
        repeat(runsN) { r ->
            val floor = 1 + t.rnd.nextInt(40)
            val map = DungeonSimulator.generateMap(t.c, floor, pool[0], 3)
            val run = DungeonSimulator.RunState(
                floor, DungeonSimulator.maxHp(t.c, pool[0], false) * 3f, map, map.startX, map.startY, runStartXp = t.xp(pool[0]) ?: 0f,
                companions = pool.drop(1).map { it to false }, companionHps = pool.drop(1).map { DungeonSimulator.maxHp(t.c, it, false) * 3f }
            ).also { it.applyRealXp = false; it.companionSimXp.addAll(listOf(t.xp(pool[1]) ?: 0f, t.xp(pool[2]) ?: 0f)) }
            DungeonSimulator.ensureFollowerTiles(run)
            var prevOutcome = "inicio"
            for (s in 0 until 500) {
                val before = run.followerTiles; val beforeFloor = run.floor
                val res = DungeonSimulator.stepOnce(t.c, pool[0], false, run)
                val o = res.outcome
                if (o is DungeonSimulator.Outcome.Defeated || o is DungeonSimulator.Outcome.Finished) break
                steps++
                val oc = o::class.java.simpleName
                val far = run.followerTiles.maxOfOrNull { maxOf(kotlin.math.abs(it.first - run.px), kotlin.math.abs(it.second - run.py)) } ?: 0
                maxFar = maxOf(maxFar, far); if (far > 3) farSteps++
                if (run.floor == beforeFloor && before.size == run.followerTiles.size) {
                    val jump = before.indices.maxOfOrNull { i -> maxOf(kotlin.math.abs(before[i].first - run.followerTiles[i].first), kotlin.math.abs(before[i].second - run.followerTiles[i].second)) } ?: 0
                    if (jump > 2) { jumps++; jumpCtx["$prevOutcome -> $oc"] = (jumpCtx["$prevOutcome -> $oc"] ?: 0) + 1; if (examples.size < 5) examples.add("run $r paso $s piso $floor: salto de $jump casillas ($prevOutcome -> $oc), lider (${res.fromX},${res.fromY})->(${res.toX},${res.toY}), seguidores $before -> ${run.followerTiles}") }
                }
                prevOutcome = oc
            }
        }
        t.r.info(S, "$steps pasos en $runsN carreras (equipo de 3): separacion maxima compañero-lider $maxFar casillas; pasos con alguien a >3 casillas: $farSteps; saltos de >2 casillas en un paso: $jumps $jumpCtx")
        examples.forEach { t.r.info(S, "ejemplo: $it") }
        if (jumps > 0) t.r.problem("BAJA", S, "$jumps pasos en los que un compañero 'salta' mas de 2 casillas de golpe (contexto: $jumpCtx) - visualmente se teletransporta")
        else t.r.ok(S, "los compañeros nunca saltan mas de 2 casillas en un paso")
    }

    private fun notifications(t: T) {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 30)
        listOf("garchomp", "lucario", "gengar").forEach { t.give(it, false, 70) }
        fun check(label: String) {
            try {
                val n = NotificationHelper.buildDungeonNotification(t.c)
                val title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                t.r.info(S, "notificacion ($label): '$title' / '$text'")
                if (listOf(title, text).any { it.contains("null") || it.contains("NaN") }) t.r.problem("BAJA", S, "notificacion ($label) con texto raro: '$title' / '$text'")
                NotificationHelper.refreshDungeonNotification(t.c)
            } catch (e: Throwable) { t.r.problem("GRAVE", S, "notificacion ($label): ${e.javaClass.simpleName}: ${e.message}") }
        }
        check("sin carrera")
        DungeonState.assign(t.c, "garchomp", false, DungeonSimulator.maxHp(t.c, "garchomp", false), t.xp("garchomp") ?: 0f)
        check("en solitario, piso 1")
        DungeonState.abandon(t.c)
        DungeonState.assign(t.c, "garchomp", false, DungeonSimulator.maxHp(t.c, "garchomp", false), t.xp("garchomp") ?: 0f, companions = listOf("lucario" to false, "gengar" to false))
        repeat(40) { DungeonSimulator.runTick(t.c) }
        check("con 2 companeros tras 40 ciclos")
        DungeonState.updateProgress(t.c, 7, 0f, listOf(0f, 0f))
        check("todo el equipo a 0 de vida")
        DungeonState.startCooldown(t.c, 7, 120_000L)
        check("en cooldown")
        DungeonState.abandon(t.c)
        check("tras abandonar")
    }

    private fun sims(t: T, n: Int) {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 30)
        val names = t.dex.map { it.name }.filter { t.evo[it]?.to?.isEmpty() == true }.shuffled(t.rnd).take(12)
        names.forEach { t.give(it, false, 10 + t.rnd.nextInt(90)) }
        var bad = 0; var ms = 0L
        for (i in 0 until maxOf(6, n / 30)) {
            val leader = names[i % names.size]
            val comps = names.drop((i + 1) % names.size).take(i % 3).filter { it != leader }.map { it to false }
            val t0 = System.currentTimeMillis()
            val rep = DungeonSimulator.runBalanceSimulation(t.c, leader, false, t.xp(leader) ?: 0f, 3, comps)
            ms += System.currentTimeMillis() - t0
            for (r in rep.runs) {
                if (r.floorReached !in 1..101 || r.xpGained < 0f || r.combatsWon > r.combats) { bad++; t.r.problem("MEDIA", S, "simulacion de $leader +${comps.size}: resultado imposible piso=${r.floorReached} xp=${r.xpGained} combates=${r.combats}/${r.combatsWon}") }
            }
        }
        if (bad == 0) t.r.ok(S, "simulaciones de balance variadas sin resultados imposibles (${ms} ms)")
    }
}
