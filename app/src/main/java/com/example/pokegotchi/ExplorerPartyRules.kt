package com.example.pokegotchi

/**
 * Compañeros de exploración (lógica pura, con tests JVM: ExplorerPartyRulesTest). El explorador de
 * la mazmorra es el LÍDER (el que pelea, como siempre); desde el nivel de entrenador
 * [FIRST_COMPANION_LEVEL] puede llevar un compañero y desde el [SECOND_COMPANION_LEVEL] dos. Los
 * compañeros van en la MISMA carrera (mismo piso, mismo cierre) y se REPARTEN a partes iguales el
 * XP que se gana (ver DungeonSimulator.stepOnce). Escalera de mecánicas por nivel de entrenador,
 * ver PetState.unlocksAtTrainerLevel.
 */
object ExplorerPartyRules {
    const val FIRST_COMPANION_LEVEL = 10
    const val SECOND_COMPANION_LEVEL = 15   // antes 25: el usuario lo bajo a 15 el 2026-10-17
    const val MAX_COMPANIONS = 2

    /** Cuántos compañeros puede llevar el líder con este nivel de entrenador. */
    fun maxCompanions(trainerLevel: Int): Int =
        (if (trainerLevel >= FIRST_COMPANION_LEVEL) 1 else 0) + (if (trainerLevel >= SECOND_COMPANION_LEVEL) 1 else 0)

    /** Nivel de entrenador en el que se desbloquea el compañero número [n] (1 = primero), o null si no existe. */
    fun levelForCompanion(n: Int): Int? = when (n) {
        1 -> FIRST_COMPANION_LEVEL
        2 -> SECOND_COMPANION_LEVEL
        else -> null
    }

    /** XP que le toca a CADA miembro del grupo (líder incluido) de [totalXp] ganados: reparto a partes iguales. */
    fun sharePerMember(totalXp: Float, members: Int): Float = totalXp / members.coerceAtLeast(1)

    /**
     * Multiplicador de la cantidad de enemigos de cada piso según cuántos Pokémon entran en la mazmorra (el líder y sus
     * compañeros). Pedido del usuario: como con más Pokémon se llega más lejos, el piso trae más enemigos. Con uno solo,
     * nada cambia. Se ajusta aquí (y se mide con la simulación de balance).
     */
    fun enemyMultiplier(teamSize: Int): Float = when {
        teamSize <= 1 -> 1f
        teamSize == 2 -> debugMultiplier2 ?: MULTIPLIER_2
        else -> debugMultiplier3 ?: MULTIPLIER_3
    }

    // Valores elegidos con la simulacion de balance (ver la memoria del proyecto): el combate posicional ya recorta lo que
    // aporta el equipo, asi que un multiplicador alto lo dejaria igual o peor que ir solo y cada Pokemon ganaria menos XP
    // (se reparte). Suave: el equipo llega algo mas lejos y entre todos gana mas XP que uno solo.
    const val MULTIPLIER_2 = 1.15f
    const val MULTIPLIER_3 = 1.3f

    /** SOLO PRUEBAS (ACTION_DEBUG_TEAM_BALANCE, extras m2/m3): sustituyen el multiplicador para medir varios valores sin recompilar. */
    @Volatile var debugMultiplier2: Float? = null
    @Volatile var debugMultiplier3: Float? = null

    /**
     * Enemigos de un piso con [base] enemigos para un solo Pokémon, ya multiplicados por el tamaño del equipo. El redondeo es
     * PROBABILÍSTICO ([roll] en 0..1): la parte entera siempre y un enemigo más con la probabilidad de la parte decimal, así
     * el multiplicador cuenta también con pocos enemigos (1,3 × 1 enemigo = 1, y un 30 % de las veces 2). Nunca menos que [base].
     */
    fun scaledEnemyCount(base: Int, teamSize: Int, roll: Float = kotlin.random.Random.nextFloat()): Int {
        val x = base * enemyMultiplier(teamSize)
        val whole = x.toInt()
        return (whole + if (roll < x - whole) 1 else 0).coerceAtLeast(base)
    }

    /** Multiplicador para enseñar en un texto: hasta 2 decimales y con coma (1,15 / 1,3). Un "%.1f" dejaba 1,15 en "1,1". */
    fun formatMultiplier(x: Float): String =
        java.lang.String.format(java.util.Locale.US, "%.2f", x).trimEnd('0').trimEnd('.').replace('.', ',')

    /** XP para enseñar en un texto: entera si es de 10 para arriba; con un decimal (y coma) si es menos, para que 0,3 no salga como "0". */
    fun formatXp(xp: Float): String =
        if (xp >= 10f) xp.toInt().toString() else java.lang.String.format(java.util.Locale.US, "%.1f", xp).replace('.', ',').removeSuffix(",0")

    /** Con menos de esta fracción de su vida máxima, el que va delante cede el puesto al siguiente. */
    const val FRONT_SWAP_FRACTION = 0.25f

    private fun fraction(hps: List<Float>, maxHps: List<Float>, i: Int): Float =
        hps[i] / maxHps[i].coerceAtLeast(1f)

    /**
     * Quién va DELANTE (el que recibe los golpes y abre el paso) dados la vida [hps] y la vida máxima [maxHps] de cada
     * miembro y el que iba delante hasta ahora ([current]). Reglas: el que va delante sigue mientras le quede al menos
     * [FRONT_SWAP_FRACTION] de su vida; si baja de ahí, entra el compañero en pie con MÁS fracción de vida que esté por
     * encima de ese mínimo; si ninguno lo está, se queda el actual mientras viva (cambiar a otro igual de tocado no
     * arregla nada) y, si cae, entra el que tenga más fracción de vida. Devuelve -1 si no queda nadie en pie. Nunca
     * devuelve a un caído. El orden de empate es el del grupo (el líder primero).
     */
    fun nextFront(hps: List<Float>, maxHps: List<Float>, current: Int, eligible: (Int) -> Boolean = { true }): Int {
        val alive = hps.indices.filter { hps[it] > 0f }
        if (alive.isEmpty()) return -1
        val cur = current.takeIf { it in alive }
        if (cur != null && fraction(hps, maxHps, cur) >= FRONT_SWAP_FRACTION) return cur
        // [eligible] limita a quien puede ocupar el sitio del que cede el puesto estando AUN en pie (en el combate, solo los
        // que estan pegados a el: se cambian de casilla); si el de delante ya cayo, vale cualquiera que siga en pie.
        val canTake: (Int) -> Boolean = if (cur == null) { _ -> true } else eligible
        val healthy = alive.filter { it != cur && canTake(it) && fraction(hps, maxHps, it) >= FRONT_SWAP_FRACTION }
        if (healthy.isNotEmpty()) return healthy.maxByOrNull { fraction(hps, maxHps, it) }!!
        if (cur != null) return cur
        return alive.maxByOrNull { fraction(hps, maxHps, it) }!!
    }

    /** A cuántas casillas (distancia Manhattan) empieza un enemigo a perseguir a alguien del equipo. */
    const val CHASE_RADIUS = 3

    /**
     * A QUIÉN persigue un enemigo que está en [enemy]: el miembro del equipo MÁS CERCANO dentro de [CHASE_RADIUS] casillas
     * (cualquiera, no solo el que lidera: pedido del usuario "que los enemigos vayan a por cualquiera del equipo"). [team] son
     * las casillas de los miembros EN PIE, el que lidera primero (en un empate de distancia gana el primero de la lista).
     * Devuelve su índice en [team], o null si nadie está a tiro.
     */
    fun chaseTarget(enemy: Pair<Int, Int>, team: List<Pair<Int, Int>>): Int? {
        var best = -1; var bestDist = Int.MAX_VALUE
        for (i in team.indices) {
            val d = kotlin.math.abs(team[i].first - enemy.first) + kotlin.math.abs(team[i].second - enemy.second)
            if (d <= CHASE_RADIUS && d < bestDist) { best = i; bestDist = d }
        }
        return if (best >= 0) best else null
    }

    /**
     * A quién golpea el enemigo en su turno de combate: cualquiera de los miembros que lo tocan ([reaching]), no solo el que
     * va delante. Un golpe a un compañero cuenta igual que uno al líder (el combate ya es de todo el equipo). En el primer
     * golpe, si el enemigo ha llegado a alguien en concreto ([preferred], el miembro cuya casilla pisó) y ese lo toca, es a
     * él; en los demás, al azar entre los que lo tocan ([pick] devuelve un número en 0 until n). -1 si nadie lo toca.
     */
    fun enemyStrikeTarget(reaching: List<Int>, preferred: Int = -1, pick: (Int) -> Int = { kotlin.random.Random.nextInt(it) }): Int {
        if (reaching.isEmpty()) return -1
        if (preferred in reaching) return preferred
        return reaching[pick(reaching.size).coerceIn(0, reaching.size - 1)]
    }

    /** El miembro en pie con MENOS fracción de vida (al que van las bayas y medicinas), o -1 si no queda nadie. */
    fun mostHurtAlive(hps: List<Float>, maxHps: List<Float>): Int =
        hps.indices.filter { hps[it] > 0f }.minByOrNull { fraction(hps, maxHps, it) } ?: -1

    /**
     * Los compañeros (en [tiles], de delante hacia atrás: el primero es el que va pegado al que lidera) se desplazan
     * un paso cuando el que lidera sale de [from] hacia [to]: el primero ocupa [from], el segundo la casilla que dejó
     * el primero, y así; la última casilla se descarta. Es la misma "serpiente" que ve el jugador.
     *
     * Si el líder da la vuelta y pisa la casilla de un compañero ([to] está en [tiles]), se cambian de sitio: ese
     * compañero pasa a [from] y los demás no se mueven (así nunca hay dos en la misma casilla, que era lo que pasaba
     * al volver sobre sus pasos, p. ej. tras recoger un objeto en un callejón).
     */
    fun <T> followTheLeader(tiles: List<T>, from: T, to: T? = null): List<T> {
        if (tiles.isEmpty()) return tiles
        val hit = if (to == null) -1 else tiles.indexOf(to)
        return if (hit >= 0) tiles.toMutableList().also { it[hit] = from } else listOf(from) + tiles.dropLast(1)
    }

    /**
     * [followTheLeader] para casillas del mapa, sin el salto de varias casillas que daba tras un combate: alli los compañeros se
     * quedan donde acabaron, y uno puede haber quedado DELANTE del que lidera (a un lado de [to]); la "serpiente" lo mandaba de
     * golpe a la casilla del anterior, 3 o mas casillas atras (el compañero parecia teletransportarse). Un compañero asi, que ya
     * queda pegado al lider tras su paso y que la fila llevaria a mas de 2 casillas, se queda donde esta - el lider lo encuentra
     * en su camino y se cambian de sitio, como ya hace [followTheLeader]. Para una fila normal (contigua) no cambia nada.
     */
    fun followTheLeaderTiles(tiles: List<Pair<Int, Int>>, from: Pair<Int, Int>, to: Pair<Int, Int>? = null): List<Pair<Int, Int>> {
        val shifted = followTheLeader(tiles, from, to)
        if (to == null || tiles.isEmpty() || to in tiles) return shifted
        fun dist(a: Pair<Int, Int>, b: Pair<Int, Int>) = maxOf(kotlin.math.abs(a.first - b.first), kotlin.math.abs(a.second - b.second))
        val out = shifted.toMutableList()
        for (i in tiles.indices) {
            val old = tiles[i]
            if (dist(old, shifted[i]) > 2 && dist(old, to) <= 1 && old != to && old !in out) out[i] = old
        }
        return out
    }
}
