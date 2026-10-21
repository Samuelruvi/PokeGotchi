package com.example.pokegotchi

import kotlin.random.Random

/**
 * Reglas (puras, sin Android) de los VISITANTES de la guarderia: Pokemon que NO son del jugador y que andan por el
 * jardin un rato (pedido explicito del usuario: "mas pokemons que no sean mios y que cambien cada 15 minutos, ni
 * legendarios ni miticos y de nivel parecido a los mios: si meto un pichu al 10 no tiene sentido que aparezca un
 * charizard"). Son solo ambiente: no tienen estadisticas ni dan XP, y el jugador no puede cogerlos.
 *
 *  - CAMBIAN cada [SLOT_MS] (15 min): el visitante depende solo de (franja, niveles de los Pokemon del jugador), asi que sin
 *    guardar nada se ven los mismos todo el cuarto de hora aunque se cierre y se abra la escena.
 *  - NIVEL PARECIDO: cada especie tiene una "etapa natural" (de que nivel a que nivel es esa forma, segun la cadena
 *    evolutiva del propio juego, ver [naturalRanges]); sale un visitante si esa etapa cae a +-[LEVEL_TOLERANCE] niveles del
 *    de uno de los Pokemon del jugador. Un Pichu Nv10 se encuentra con crias y formas medias, nunca con un Charizard.
 *  - Nunca legendarios ni miticos (la marca `rare` de evolutions.json), ni la especie de ningun Pokemon que ya este dentro.
 */
object NurseryVisitors {
    /** Cada cuanto cambian los visitantes. */
    const val SLOT_MS = 15 * 60_000L
    /** Un visitante vale si su etapa natural queda a esta distancia (en niveles) del nivel de referencia. */
    const val LEVEL_TOLERANCE = 5
    /** El nivel de referencia se agrupa de 5 en 5: asi un Pokemon que sube un nivel no cambia de golpe a los visitantes. */
    const val LEVEL_BUCKET = 5
    private const val MAX_VISITORS = 3
    private const val CROWD_TARGET = 8     // con los Pokemon del jugador, el jardin no pasa de ~8 en pantalla

    /** Lo que hace falta saber de una especie. [strength] = suma de ataque+defensa+ataque esp.+vida+velocidad (base_stats.json). */
    class Sp(val name: String, val from: String?, val evolvesTo: List<Pair<String, Int>>, val rare: Boolean, val strength: Int)

    /** "Etapa natural": desde el nivel [min] hasta justo antes de [max] (101 = hasta el final). */
    class Range(val min: Int, val max: Int)

    class Catalog(val ranges: Map<String, Range>, val eligible: List<String>)

    data class Visitor(val name: String, val level: Int)

    fun slotOf(nowMs: Long): Long = Math.floorDiv(nowMs, SLOT_MS)

    /** Milisegundos que quedan para que cambien los visitantes (entre 1 y [SLOT_MS]). */
    fun msLeftInSlot(nowMs: Long): Long = SLOT_MS - Math.floorMod(nowMs, SLOT_MS)

    /** Cuantos visitantes: 3 con el jardin vacio, menos cuanta mas gente del jugador haya (minimo 1). */
    fun countFor(memberCount: Int): Int = (CROWD_TARGET - memberCount).coerceIn(1, MAX_VISITORS)

    /** Nivel desde el que una especie SIN evolucion ni preevolucion (Tauros, Lapras...) se considera "natural": mas fuerte, mas
     *  nivel. Con las 124 especies asi (sin contar legendarios) da de 1 (Ditto) a 60 (las mas fuertes). */
    internal fun loneMin(strength: Int): Int = ((strength - 250) / 4).coerceIn(1, 60)

    /** Etapa natural de cada especie: empieza en el nivel al que se llega a ella (el de la evolucion que la origina, siempre
     *  creciente a lo largo de la cadena) y acaba justo donde evoluciona (la rama mas temprana). Una cria sin evolucion
     *  empieza en 1; una especie suelta, segun su fuerza. Si los datos dejan una etapa vacia (p. ej. un Pikachu que se
     *  alcanza a Nv25 y evoluciona a Nv25) se le da un tramo de 10 niveles. */
    fun naturalRanges(table: Map<String, Sp>): Map<String, Range> {
        val mins = HashMap<String, Int>()
        fun minOf(name: String, depth: Int): Int {
            mins[name]?.let { return it }
            val sp = table[name] ?: return 1
            val parent = sp.from?.let { table[it] }
            val v = if (parent == null || depth > 8) {
                if (sp.from == null && sp.evolvesTo.isEmpty()) loneMin(sp.strength) else 1
            } else {
                val parentMin = minOf(parent.name, depth + 1)
                val atLevel = parent.evolvesTo.firstOrNull { it.first == name }?.second ?: parentMin
                maxOf(parentMin, atLevel)
            }
            mins[name] = v
            return v
        }
        val out = HashMap<String, Range>(table.size)
        for ((name, sp) in table) {
            val min = minOf(name, 0).coerceIn(1, 100)
            val own = sp.evolvesTo.minOfOrNull { it.second } ?: 101
            out[name] = Range(min, if (own <= min) min + 10 else own)
        }
        return out
    }

    fun catalog(table: Map<String, Sp>): Catalog =
        Catalog(naturalRanges(table), table.values.filter { !it.rare }.map { it.name }.sorted())

    /** Las especies que encajan con el nivel [center]: su etapa natural cae a +-[LEVEL_TOLERANCE] de el. */
    fun poolFor(catalog: Catalog, center: Int): List<String> =
        catalog.eligible.filter { n -> catalog.ranges[n]?.let { it.min <= center + LEVEL_TOLERANCE && it.max > center - LEVEL_TOLERANCE } == true }

    /**
     * Los visitantes de la franja [slot]: [count] como mucho, cada uno con el nivel de referencia de uno de los Pokemon del
     * jugador ([refLevels], en orden de nivel para que no dependa de como esten guardados). [exclude]: especies que no pueden
     * salir (las que el jugador ya tiene dentro). [accept] deja descartar especies que no se puedan dibujar. Determinista:
     * misma franja y mismos niveles -> mismos visitantes; y cada uno usa su propio sorteo, asi que cambiar el nivel de un
     * Pokemon del jugador solo puede cambiar al visitante que seguia SU nivel.
     */
    fun pick(
        catalog: Catalog, refLevels: List<Int>, exclude: Set<String>, slot: Long, count: Int,
        accept: (String) -> Boolean = { true },
    ): List<Visitor> {
        if (refLevels.isEmpty() || count <= 0) return emptyList()
        val refs = refLevels.sorted()
        val used = HashSet(exclude)
        val result = ArrayList<Visitor>(count)
        for (i in 0 until count) {
            val center = (refs[i % refs.size].coerceIn(1, 100) / LEVEL_BUCKET) * LEVEL_BUCKET + LEVEL_BUCKET / 2
            val pool = poolFor(catalog, center)
            if (pool.isEmpty()) continue
            val rnd = Random(slot * 7919L + i * 104729L + 17L)
            var chosen: String? = null
            for (attempt in 0 until 16) {
                val n = pool[rnd.nextInt(pool.size)]
                if (n !in used && accept(n)) { chosen = n; break }
            }
            val name = chosen ?: continue
            used.add(name)
            val r = catalog.ranges.getValue(name)
            val lo = maxOf(1, r.min, center - 3); val hi = minOf(100, r.max - 1, center + 3)
            // Si la etapa solo ROZA la ventana (Psyduck a Nv36: su etapa acaba en el 32) el nivel es el mas cercano dentro de ella.
            result.add(Visitor(name, if (lo <= hi) rnd.nextInt(lo, hi + 1) else center.coerceIn(r.min, r.max - 1).coerceIn(1, 100)))
        }
        return result
    }
}
