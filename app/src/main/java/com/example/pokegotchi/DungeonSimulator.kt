package com.example.pokegotchi

import android.content.Context
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * Logica pura de la mazmorra (sin nada de Android UI, salvo Context para leer assets/
 * SharedPreferences via PetState/DungeonState) - separada de la vista para poder revisar/ajustar
 * los numeros de balance sin tocar dibujo. El widget de pantalla de inicio se retiro (pedido
 * explicito del usuario: "vamos a quitar el widget... vamos a intentar hacer lo mismo bien en la
 * propia aplicacion") - ahora hay DOS consumidores de esta misma logica, sin duplicarla:
 *   1. `DungeonService` (segundo plano, ~1 min mientras la app no esta abierta): llama a
 *      [runTick], que resuelve varios [stepOnce] SEGUIDOS y en silencio, igual que antes.
 *   2. `DungeonView` (primer plano, la mazmorra abierta de verdad): llama a [stepOnce] UNO A UNO,
 *      con animacion fluida entre medias, usando el mismo [StepResult] para saber que dibujar.
 * Ambos comparten exactamente la misma simulacion - nunca se bifurca el comportamiento real por
 * estar en primer o segundo plano, solo cambia la CADENCIA y si se anima o no.
 *
 * El mapa (varias SALAS rectangulares SEPARADAS, unidas por pasillos rectos - pedido explicito
 * del usuario tras ver capturas reales de Mundo Misterioso) se genera UNA vez por piso
 * (generateMap) y se persiste en DungeonState.
 *
 * Movimiento SINCRONIZADO por turnos (pedido explicito del usuario: "cada vez que se mueva un
 * Pokemon, se mueven todos... como el ajedrez, como en Mundo Misterioso") - cada paso mueve al
 * explorador UNA casilla Y a todos los enemigos vivos UNA casilla a la vez.
 *
 * TODOS los numeros de aqui (tamaño de la rejilla, cantidad de salas/enemigos/items, casillas por
 * tick/paso, curacion de baya, potencia de ataque sintetica) son una PRIMERA ESTIMACION a ajustar
 * jugando - misma practica que el resto de la economia del proyecto.
 *
 * `base_stats.json` NO tiene dato de Ataque (solo hp/speed/defense, verificado) - el daño usa una
 * potencia de ataque SINTETICA igual para ambos bandos, mitigada por la Defensa REAL del
 * defensor; sin tabla de efectividad de tipos (no existe en el proyecto) - el "fallo de ataque"
 * pedido se cubre con probabilidad de acierto, no con tipos.
 */
object DungeonSimulator {
    // ---------------- generacion del mapa ----------------
    // Rejilla grande y hueco amplio entre salas - pedido explicito del usuario tras ver capturas
    // reales de Mundo Misterioso: "las salas estan mucho mas separadas... las columnas de
    // conexion entre salas suelen ser mucho mas grandes".
    // Piso 1: 19x19, 5 salas (numeros de siempre). Piso MAX_FLOOR: 39x39, 11 salas - pedido
    // explicito del usuario: "que las salas tambien escalen con el piso" (ya escalaban enemigos/
    // items, pero el mapa en si era identico de tamaño y complejidad en cualquier piso). Ver
    // gridSizeFor/roomCountFor, usados desde generateMap. (39x39 tras subir GRID_SIZE_MAX_BONUS
    // de 8 a 20 mas abajo - este comentario decia "27x27", desfasado del valor real.)
    // GRID_SIZE_MAX_BONUS subido de 8 a 20 - verificado con broadcast de prueba
    // (ACTION_DEBUG_DUNGEON_JUMP_FLOOR) que con solo +8 (27x27) el numero de salas realmente
    // colocadas se quedaba fijo en ~5 sin importar cuantos intentos se le dieran a placeRooms - la
    // causa NO era falta de intentos, es ROOM_CENTER_GAP (cada sala ya puesta "veta" una banda de
    // ~5 columnas/filas para el CENTRO de cualquier sala futura, pensado para evitar pasillos
    // paralelos de 2 de ancho) - con una rejilla de solo 27 de ancho, esa banda cubre casi toda la
    // rejilla despues de 5 salas, haciendo matematicamente imposible colocar mas por mucho que se
    // intente. Con una rejilla bastante mas grande en los pisos profundos SI hay sitio real de
    // sobra para acercarse al objetivo de 11 salas sin tener que aflojar esa regla (que existe por
    // un bug real ya reportado - "he visto pasillos de dos de ancho, eso no puede ser").
    private const val GRID_SIZE_MIN = 19
    private const val GRID_SIZE_MAX_BONUS = 20
    private const val ROOM_COUNT_MIN = 5
    private const val ROOM_COUNT_MAX_BONUS = 6
    private const val ROOM_MIN_SIZE = 3
    private const val ROOM_MAX_SIZE = 5
    private const val ROOM_MARGIN = 3
    // Verificado con broadcast de prueba (ACTION_DEBUG_DUNGEON_JUMP_FLOOR): con 40 intentos, el
    // numero de salas realmente colocadas se quedaba SIEMPRE alrededor de 4, sin importar el
    // objetivo real (roomCountFor pedia 8-10 en pisos profundos) - cada sala de mas tiene que
    // esquivar TODAS las anteriores (margen+separacion de centros), asi que la probabilidad de
    // exito por intento cae con cada sala ya colocada, y 40 tiradas se quedaban cortas mucho antes
    // de llegar al objetivo. Subido bastante (barato: cada intento es solo un par de comparaciones,
    // como mucho ROOM_COUNT_MAX*este numero en el peor caso, nada pesado).
    private const val ROOM_PLACEMENT_ATTEMPTS = 150
    // Bug real reportado por el usuario ("he visto pasillos de dos de ancho, eso no puede ser"):
    // cada pasillo en si SIEMPRE es de una sola casilla de ancho (una unica fila o columna fija),
    // pero dos pasillos DISTINTOS (de dos pares de salas diferentes) pueden acabar en una columna/
    // fila casi identica por pura coincidencia (los centros de dos salas distintas cayendo a 1
    // casilla de distancia) y correr en paralelo un buen tramo, dando la impresion visual de un
    // unico pasillo de 2 de ancho. Se evita de raiz obligando a que los centros (cx/cy) de CUALQUIER
    // par de salas disten al menos ROOM_CENTER_GAP, ademas de que los propios rectangulos no se
    // toquen (ROOM_MARGIN) - asi ningun pasillo recto (siempre anclado al cx o cy de una sala) puede
    // quedar a 1-2 casillas de otro.
    private const val ROOM_CENTER_GAP = 3

    // Jefes cada BOSS_FLOOR_INTERVAL pisos - pedido explicito del usuario: "quiero que cada 10
    // pisos haya un boss, que sea acorde al nivel del piso pero que sea mas potente y mas grande.
    // la sala sera solo una sala cuadrada grande y la escalera de subida solo aparecera si lo
    // derrotas". Ver generateBossMap/pickBossSpecies/bossLootTiles y el multiplicador de
    // HP/ataque en resolveEncounter (EnemyTile.isBoss).
    private const val BOSS_FLOOR_INTERVAL = 10
    private const val BOSS_ROOM_SIZE = 11
    // Recalibrado a la baja - primera prueba real (sceptile nivel 44, muy por encima del nivel
    // esperado del jefe del piso 30 - unos ~38) murio casi al instante contra el, y raichu nivel
    // 30 igual contra el del piso 20. El bonus de nivel YA hace al jefe mas fuerte via las formulas
    // normales de HP/ataque (ambas escalan con el nivel) - apilar ADEMAS un x2,5 de HP y x1,15 de
    // ataque encima disparaba el daño total recibido en todo el combate (mas HP = combate mas
    // largo = mas golpes recibidos, no solo "un enemigo mas duro") muy por encima de lo que
    // aguanta la formula de combate ya de por si sensible al nivel (ver comentario de
    // generateMap). Sin validar aun con el simulador (no hay todavia una version de
    // runBalanceSimulation para jefes) - a seguir ajustando jugando si sigue sintiendose
    // desequilibrado.
    // Rebajados de nuevo (1.6/1.1 -> 1.4/1.05 -> esto) - probando la hipotesis de que, tras cortar
    // el desgaste de los pisos normales (ver comentario de enemyCount en generateMap: media de
    // piso estancada en ~46 durante 2 rondas seguidas de recorte, aunque el minimo mejoraba), el
    // jefe (unico enemigo del piso, siempre legendario - base stats de por si mas altos que un
    // enemigo normal - mas este multiplicador extra) se ha quedado como el cuello de botella real.
    // BOSS_LEVEL_BONUS tambien bajado (5->2): un jefe ya destaca por ser legendario + mas grande,
    // no hace falta que ademas vaya varios niveles por delante del piso.
    // Verificado con runBalanceSimulation: bajar esto (1.6/1.1 -> 1.4/1.05 -> 1.15/1.0) fue lo que
    // de verdad movio la aguja (piso medio 46 -> 53 en el mismo test tras esta ultima rebaja, tras
    // dos rondas de recorte de enemyCount que se habian quedado estancadas) - el jefe, no los
    // pisos normales, era el cuello de botella real. Sin bonus de nivel ni de HP: un jefe destaca
    // por ser SIEMPRE legendario (stats base mas altos que un enemigo normal) y mas grande, no
    // hace falta ademas subirle nivel o vida por encima de un enemigo cualquiera del piso.
    private const val BOSS_LEVEL_BONUS = 0
    private const val BOSS_HP_MULT = 1.0f
    private const val BOSS_ATK_MULT = 1.0f
    // "mas grande" (visual, ver DungeonView) - factor de escala del sprite sobre el tamaño normal
    // de una celda.
    const val BOSS_SPRITE_SCALE = 1.7f

    // Ultimo ajuste de la serie de rebalanceo tras la eficacia de tipos: rebajar el enemyCount, los
    // jefes, subir la curacion y bajar el nivel del enemigo (ver comentarios de cada uno) dejo la
    // media estancada en piso ~55-57 para nivel 62 (objetivo 60-80) durante 3 rondas seguidas -
    // cada ronda anterior era SIMETRICA (afecta a los dos bandos igual, ver comentario de
    // hpFormula sobre por que eso no mueve la media) o topaba con rendimientos decrecientes. Esto
    // SI es asimetrico a proposito: reduce SOLO el daño que el jugador RECIBE, sin tocar el que
    // hace - una rebaja plana del 35% al ataque de cualquier enemigo (jefe o no).
    private const val ENEMY_ATK_DAMPING = 0.65f

    // Casillas por CICLO en segundo plano (DungeonService) - en primer plano (DungeonView)
    // no aplica: alli se hace stepOnce() uno a uno, a su propio ritmo de animacion. Subido de
    // 8-16 a 24-40 - pedido explicito del usuario ("corre muy lento en segundo plano"): los
    // mapas de piso profundo son grandes de verdad (gridSizeFor ronda 29-33 de lado en pisos
    // 50-70, con 8-9 salas que recorrer), y ahora que el rebalanceo de dificultad deja llegar
    // mucho mas hondo (antes ~piso 20-40, ahora ~piso 40-70), el jugador pasa mucho mas tiempo
    // real en esos pisos grandes con el mismo ritmo de fondo de siempre - un piso podia tardar
    // 3-8 minutos reales en cruzarse a 8-16 pasos/minuto. Con esto baja a 1-3 minutos, sin tocar
    // el ritmo del TICK_INTERVAL_MS (mismo consumo de batería/CPU, solo mas trabajo por ciclo).
    private const val TILE_MOVES_MIN = 24
    private const val TILE_MOVES_MAX_EXCLUSIVE = 41 // banda real 24..40

    // HABILITADO (pedido explicito del usuario tras validar la curva con un estudio amplio -
    // niveles 1/9/16/26/38/51/62/80 con runBalanceSimulation, ver PetState.statsWriteLock para el
    // cerrojo que evita perder XP si el mismo individuo es a la vez el explorador y el compañero
    // activo del widget - y tras bloquear esa combinacion del todo, ver DungeonActivity/
    // assignToDungeon): los caramelos SI suben el nivel real del individuo en PetState. Antes de
    // esto se dejaba en false a proposito ("de momento no va a pasar nada, estamos en un entorno
    // de pruebas... todavia no esta listo para modificar los Pokemon de verdad") mientras solo se
    // SEGUIA contando/mostrando cuanta XP habria ganado (DungeonState.addRunXp), para poder juzgar
    // el equilibrio sin arriesgar datos reales.
    private const val APPLY_REAL_CANDY_XP = true

    private data class RoomRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) { // x1,y1 exclusivos
        val cx get() = (x0 + x1 - 1) / 2
        val cy get() = (y0 + y1 - 1) / 2
        fun overlaps(o: RoomRect, margin: Int): Boolean =
            x0 - margin < o.x1 && x1 + margin > o.x0 && y0 - margin < o.y1 && y1 + margin > o.y0
    }

    /** Tamaño de rejilla (cuadrada) y numero de salas para [floor] - ver comentario de
     *  GRID_SIZE_MIN de mas arriba: crecen linealmente con el progreso del piso. */
    private fun gridSizeFor(floor: Int): Int =
        GRID_SIZE_MIN + (floor * GRID_SIZE_MAX_BONUS / DungeonState.MAX_FLOOR)
    private fun roomCountFor(floor: Int): Int =
        ROOM_COUNT_MIN + (floor * ROOM_COUNT_MAX_BONUS / DungeonState.MAX_FLOOR)

    /** Coloca hasta [roomCount] salas rectangulares SIN que se toquen entre si (con ROOM_MARGIN de
     *  hueco), reintentando cada una varias veces antes de rendirse. */
    private fun placeRooms(gridW: Int, gridH: Int, roomCount: Int): List<RoomRect> {
        val placed = ArrayList<RoomRect>()
        repeat(roomCount) {
            repeat(ROOM_PLACEMENT_ATTEMPTS) attempt@{
                val rw = Random.nextInt(ROOM_MIN_SIZE, ROOM_MAX_SIZE + 1)
                val rh = Random.nextInt(ROOM_MIN_SIZE, ROOM_MAX_SIZE + 1)
                val x0 = Random.nextInt(1, (gridW - rw - 1).coerceAtLeast(2))
                val y0 = Random.nextInt(1, (gridH - rh - 1).coerceAtLeast(2))
                val candidate = RoomRect(x0, y0, x0 + rw, y0 + rh)
                val collides = placed.any {
                    it.overlaps(candidate, ROOM_MARGIN) ||
                        abs(it.cx - candidate.cx) < ROOM_CENTER_GAP || abs(it.cy - candidate.cy) < ROOM_CENTER_GAP
                }
                if (!collides) {
                    placed.add(candidate)
                    return@attempt
                }
            }
        }
        return placed
    }

    @Volatile private var nextEnemyId = 1
    private fun freshEnemyId(): Int = synchronized(this) { nextEnemyId++ }

    /** Rejilla nueva de salas SEPARADAS conectadas por pasillos rectos en L (algoritmo clasico
     *  "random rooms and corridors") - la sala 0 es la entrada y la ULTIMA es la salida,
     *  conectadas en cadena 0->1->2->..., asi que siempre hay camino garantizado. Enemigos/items
     *  en casillas de suelo libres, ni en la entrada ni la salida.
     *
     *  Diseño de dificultad - pedido explicito del usuario tras ver que anclar el nivel del
     *  enemigo al nivel del EXPLORADOR (intento anterior, ya revertido) hacia que hubiera reto
     *  real desde el piso 1, sin importar el nivel de entrada: "si un pokemon es nivel 30 llegue
     *  entre los pisos 20 y 40... si es nivel 70 llegue a los pisos 60 u 80... es normal que haya
     *  pisos muy faciles esto es resistencia". El nivel del enemigo depende SOLO DEL PISO (mas
     *  jitter), NO del nivel de quien explora - eso es precisamente lo que hace que los pisos
     *  bajos sean un paseo para un Pokemon ya fuerte (piso 1-20 trivial para uno nivel 30, piso
     *  1-60 trivial para uno nivel 70) y que el "muro" real aparezca solo/exactamente donde el
     *  nivel del enemigo (~piso) alcanza al nivel REAL de quien explora - sin importar si ese
     *  nivel se trajo de fuera o se gano a base de caramelos DENTRO de la propia carrera. La
     *  combinacion de HP+ataque escalando juntos con el nivel ya hace el combate muy sensible a
     *  la diferencia de nivel (verificado con runBalanceSimulator: 2 niveles de diferencia mueven
     *  el % de victorias por combate mas de 20 puntos), asi que ese muro llega de forma bastante
     *  abrupta una vez el piso alcanza el nivel propio - la sensacion de dificultad "exponencial"
     *  que pedia el usuario sale de esa sensibilidad del combate, no hace falta forzar el nivel
     *  del enemigo por encima del piso. La especie en si sigue eligiendose por PISO
     *  (pickEnemySpecies) - eso es progresion/variedad, no dificultad. */
    fun generateMap(context: Context, floor: Int, playerSpecies: String, teamSize: Int = 1): DungeonState.DungeonMap {
        if (floor % BOSS_FLOOR_INTERVAL == 0) return generateBossMap(context, floor, playerSpecies)
        val gridW = gridSizeFor(floor)
        val gridH = gridW
        val walls = BooleanArray(gridW * gridH) { true }
        fun idx(x: Int, y: Int) = y * gridW + x
        fun carve(x: Int, y: Int) { if (x in 0 until gridW && y in 0 until gridH) walls[idx(x, y)] = false }

        var rooms = placeRooms(gridW, gridH, roomCountFor(floor))
        if (rooms.size < 2) {
            rooms = listOf(RoomRect(1, 1, 4, 4), RoomRect(gridW - 4, gridH - 4, gridW - 1, gridH - 1))
        }
        for (r in rooms) for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) carve(x, y)
        for (i in 0 until rooms.size - 1) {
            val a = rooms[i]; val b = rooms[i + 1]
            if (Random.nextBoolean()) {
                for (x in minOf(a.cx, b.cx)..maxOf(a.cx, b.cx)) carve(x, a.cy)
                for (y in minOf(a.cy, b.cy)..maxOf(a.cy, b.cy)) carve(b.cx, y)
            } else {
                for (y in minOf(a.cy, b.cy)..maxOf(a.cy, b.cy)) carve(a.cx, y)
                for (x in minOf(a.cx, b.cx)..maxOf(a.cx, b.cx)) carve(x, b.cy)
            }
        }
        val start = rooms.first()
        val exit = rooms.last()

        val freeCells = (0 until gridW * gridH)
            .filter { !walls[it] }
            .map { it % gridW to it / gridW }
            .filterNot { (x, y) -> (x == start.cx && y == start.cy) || (x == exit.cx && y == exit.cy) }
            .shuffled()
        var cellIdx = 0
        // Mas enemigos por piso segun se profundiza - mas encuentros por piso significa mas
        // tiradas de combate independientes, cada una un riesgo real de acabar la carrera por
        // ACUMULACION (sin curacion automatica entre combates, solo bayas/medicinas encontradas +
        // la regeneracion pasiva de 1 PS/3 pasos). Recortado varias veces durante el reajuste de
        // dificultad tras añadir la eficacia de tipos (llego a quedarse en "piso/35, tope 4", que
        // en la practica es SIEMPRE 1 enemigo entre los pisos 1-69 y como mucho 2 el resto - "no
        // tiene sentido, a mayor piso mayor dificultad" pedido explicito del usuario, con razon:
        // no escalaba de forma perceptible en casi todo el juego). Vuelto a una rampa que SI se
        // nota piso a piso (1 en el piso 1, 5 en el piso 100) apoyandose en que el ajuste que de
        // verdad soluciono la dificultad fue otro (ENEMY_ATK_DAMPING, -35% de daño recibido) - a
        // reverificar con runBalanceSimulation que el objetivo de piso alcanzado se mantiene.
        // Con equipo, mas enemigos (ExplorerPartyRules.enemyMultiplier: pedido del usuario - como con mas Pokemon se llega
        // mas lejos, el piso trae mas enemigos segun cuantos entran).
        val enemyCount = ExplorerPartyRules.scaledEnemyCount((1 + floor / 25).coerceAtMost(5), teamSize)
        val itemCount = (2 + floor / 8).coerceAtMost(10)
        val progress = (floor.toFloat() / DungeonState.MAX_FLOOR).coerceIn(0f, 1f)
        val enemies = ArrayList<DungeonState.EnemyTile>()
        repeat(enemyCount) {
            if (cellIdx >= freeCells.size) return@repeat
            val (x, y) = freeCells[cellIdx++]
            // Nivel = PISO + jitter, centrado un poco POR DEBAJO del piso (ver comentario de
            // enemyCount de arriba - el desgaste acumulado entre combates ya castiga bastante sin
            // necesidad de que el nivel medio del enemigo iguale exactamente al piso). Offset
            // bajado de -3 a -6 (ultimo ajuste de la misma serie que enemyCount/BOSS_*_MULT/
            // itemRoleWeights de arriba) - nivel 62 se quedaba en piso medio ~56 (objetivo 60-80)
            // incluso con jefes en neutro y mas curacion; un poco mas de margen de nivel en cada
            // combate normal era el ultimo tramo que faltaba.
            val level = (floor - 6 + Random.nextInt(-7, 6)).coerceIn(1, 100)
            enemies.add(DungeonState.EnemyTile(freshEnemyId(), x, y, pickEnemySpecies(context, floor, playerSpecies), level))
        }
        val items = ArrayList<DungeonState.ItemTile>()
        repeat(itemCount) {
            if (cellIdx >= freeCells.size) return@repeat
            val (x, y) = freeCells[cellIdx++]
            items.add(randomItemTile(context, x, y, floor))
        }
        val roomInfos = rooms.map { DungeonState.RoomInfo(it.x0, it.y0, it.x1, it.y1) }
        // La sala de entrada (sala 0) cuenta como ya vista desde el principio - ahi es donde
        // aparece el explorador, no hace falta "descubrirla" andando.
        val visitedRooms = mutableSetOf(0)
        return DungeonState.DungeonMap(gridW, gridH, walls, start.cx, start.cy, exit.cx, exit.cy, enemies, items, roomInfos, visitedRooms, DUNGEON_BACKGROUNDS.random(), DUNGEON_FLOOR_TILES.random())
    }

    /** Piso de jefe (cada BOSS_FLOOR_INTERVAL) - pedido explicito del usuario: "la sala sera solo
     *  una sala cuadrada grande". Sin pasillos ni salas de mas: una unica sala BOSS_ROOM_SIZE x
     *  BOSS_ROOM_SIZE centrada en la rejilla (que sigue creciendo con el piso, igual que un piso
     *  normal), el explorador aparece en una esquina, el jefe en el centro, la salida en la
     *  esquina opuesta - pero esa salida no funciona mientras el jefe siga vivo (ver stepOnce).
     *  Sin enemigos normales ni objetos previos - el botin llega SOLO al derrotar al jefe
     *  (bossLootTiles). */
    private fun generateBossMap(context: Context, floor: Int, playerSpecies: String): DungeonState.DungeonMap {
        val gridW = gridSizeFor(floor)
        val gridH = gridW
        // El .coerceAtMost es una guardia defensiva a proposito, no codigo muerto a quitar: con
        // GRID_SIZE_MIN=19 nunca llega a activarse en el rango real 1-100 (gridW-4 siempre >= 15),
        // pero protege si algun dia GRID_SIZE_MIN bajara sin recordar este calculo.
        val size = BOSS_ROOM_SIZE.coerceAtMost(gridW - 4)
        val x0 = (gridW - size) / 2
        val y0 = (gridH - size) / 2
        val room = RoomRect(x0, y0, x0 + size, y0 + size)
        val walls = BooleanArray(gridW * gridH) { true }
        fun idx(x: Int, y: Int) = y * gridW + x
        for (y in room.y0 until room.y1) for (x in room.x0 until room.x1) walls[idx(x, y)] = false

        val startX = room.x0 + 1; val startY = room.y0 + 1
        val exitX = room.x1 - 2; val exitY = room.y1 - 2
        val bossX = (room.x0 + room.x1 - 1) / 2; val bossY = (room.y0 + room.y1 - 1) / 2
        val level = (floor + BOSS_LEVEL_BONUS + Random.nextInt(-3, 4)).coerceIn(1, 100)
        val boss = DungeonState.EnemyTile(freshEnemyId(), bossX, bossY, pickBossSpecies(context, floor, playerSpecies), level, isBoss = true)

        val roomInfos = listOf(DungeonState.RoomInfo(room.x0, room.y0, room.x1, room.y1))
        return DungeonState.DungeonMap(
            gridW, gridH, walls, startX, startY, exitX, exitY,
            mutableListOf(boss), mutableListOf(), roomInfos, mutableSetOf(0),
            DUNGEON_BACKGROUNDS.random(), DUNGEON_FLOOR_TILES.random()
        )
    }

    // Lista CERRADA de jefes por piso - pedido explicito del usuario: "quiero que elijamos ya una
    // lista cerrada de legendarios por piso y se quede esos Pokemon" (sustituye a la ventana
    // deslizante anterior, que recalculaba el reparto en runtime a partir del roster ordenado por
    // poder). Repartidos a mano entre el usuario y yo: los 96 legendarios/miticos marcados "rare"
    // en evolutions.json, MENOS ursaluna-bloodmoon (confirmado con Bulbapedia que no es legendario/
    // mitico oficial - es solo una forma especial del DLC Teal Mask, dato verificado por
    // WebSearch, no una suposicion), repartidos en 10 tramos contiguos de ~9-10 especies cada uno,
    // ordenados por el MISMO "poder" que ya usa buildPowerRoster (hp+speed+defense+max(ataque,
    // ataque especial) - BaseStats.attack ya toma ese maximo al cargar base_stats.json, ver
    // baseStatsTable) - piso 10 el tramo mas flojo, piso 100 el mas fuerte. Phione y Type: Null/
    // Silvally SI se quedan (confirmado con WebSearch que ambos son legendario/mitico oficial pese
    // a la duda inicial - Phione es Mitico, Type: Null/Silvally son Legendarios).
    private val BOSS_ROSTER: Map<Int, List<String>> = mapOf(
        10 to listOf("cosmog", "meltan", "cosmoem", "kubfu", "terapagos", "phione", "regice", "calyrex", "type-null", "diancie"),
        20 to listOf("wo-chien", "registeel", "hoopa", "fezandipiti", "virizion", "tapu-fini", "articuno", "mesprit", "tapu-lele", "chi-yu"),
        30 to listOf("uxie", "silvally", "azelf", "tornadus", "thundurus", "genesect", "enamorus", "suicune", "latias", "cresselia"),
        40 to listOf("tapu-bulu", "magearna", "munkidori", "ogerpon", "moltres", "raikou", "meloetta", "zapdos", "mew", "celebi"),
        50 to listOf("latios", "jirachi", "deoxys", "manaphy", "shaymin", "victini", "volcanion", "tapu-koko", "heatran", "necrozma"),
        60 to listOf("landorus", "glastrier", "okidogi", "entei", "chien-pao", "ho-oh", "cobalion", "terrakion", "keldeo"),
        70 to listOf("zeraora", "darkrai", "marshadow", "zygarde", "pecharunt", "urshifu", "urshifu-rapid", "regirock", "kyogre"),
        80 to listOf("regieleki", "regidrago", "zarude", "spectrier", "ting-lu", "lugia", "rayquaza", "palkia", "reshiram"),
        90 to listOf("kyurem", "xerneas", "yveltal", "melmetal", "dialga", "giratina", "zekrom", "lunala", "zacian"),
        100 to listOf("zamazenta", "miraidon", "solgaleo", "mewtwo", "groudon", "regigigas", "arceus", "koraidon", "eternatus"),
    )

    /** Especie del jefe - SIEMPRE de la lista cerrada de [BOSS_ROSTER] para ESE piso exacto (nunca
     *  fuera de ella) - pedido explicito del usuario tras el rediseño de arriba. Dentro del tramo,
     *  sigue habiendo sorteo (no siempre el mismo jefe en cada visita a ese piso) y se sigue
     *  rebajando el peso de quien pegue supereficaz contra el jugador (ver [weightedSpeciesPick],
     *  mismo mecanismo que [pickEnemySpecies]). */
    private fun pickBossSpecies(context: Context, floor: Int, playerSpecies: String): String {
        val pool = BOSS_ROSTER[floor] ?: return allSpeciesNames(context).random()
        return weightedSpeciesPick(context, pool, 0, pool.size - 1, playerSpecies)
    }

    /** Botin al derrotar un jefe - pedido explicito del usuario: "cuando muere aparecera
     *  experiencia, vida y objetos varios". A diferencia del sorteo normal (randomItemTile), aqui
     *  las categorias estan GARANTIZADAS, no sujetas al azar - un jefe siempre deja recompensa de
     *  verdad: un caramelo grande (L, o XL con probabilidad creciente segun el piso), una curacion
     *  (berry o medicine) y dos objetos random (decor/buff), esparcidos en casillas libres
     *  alrededor de donde cayo (anillos concentricos, radio creciente si hace falta mas sitio). */
    // Curaciones garantizadas en el botin de jefe - pedido explicito del usuario: "tiene que haber
    // suficiente comida como para curarse entero o casi entero despues del boss" (un combate de
    // jefe desgasta mucho HP de golpe). Cada berry/medicine cura segun el tier que le toque en
    // healTierWeights(floor) (ver mas arriba) - en pisos de jefe profundos, tiers mas altos de
    // media, asi que la curacion garantizada tambien mejora con la profundidad como el resto del
    // botin.
    private const val BOSS_LOOT_HEAL_ITEMS = 5

    // Bonus de XP por jefe derrotado - pedido explicito del usuario (prioridad #1 del repaso:
    // "deberias de ganar experiencia exponencialmente" para que sea util a nivel alto sin estar
    // "roto" a nivel bajo). ADITIVO y con techo MATEMATICO fijo (como mucho MAX_FLOOR/
    // BOSS_FLOOR_INTERVAL = 10 jefes en toda la partida) - nunca puede convertirse en un producto
    // de dos series crecientes, es una suma finita y acotada, no interactua con itemCount para
    // nada. Reutiliza el MISMO xpDepthMultiplier que candy/vitamina (antes tenia su propio
    // exponente aparte - unificado para que todo el sistema de XP responda a un solo mando en vez
    // de varias curvas independientes con su propia forma).
    // Subido (400->900) tras verificar con runBalanceSimulation que XP_DEPTH_EXP=2.3 dejaba nivel
    // 62 en solo +0,9 niveles/carrera (nivel 9 en 0 - la FORMA ya es la correcta, pero el
    // "premio gordo" en si se notaba poco en terminos absolutos) - subir esto sube sobre todo a
    // los niveles altos (los bajos ya casi no llegan a ver ningun jefe con vida suficiente en el
    // multiplicador) sin tocar la forma de la curva.
    private const val BOSS_DEPTH_BONUS_MAX = 900f // valor en el jefe del piso 100 (multiplicador=1)
    private fun bossDepthBonusXp(floor: Int): Float =
        BOSS_DEPTH_BONUS_MAX * xpDepthMultiplier(floor)

    private fun bossLootTiles(context: Context, map: DungeonState.DungeonMap, bx: Int, by: Int, floor: Int): List<DungeonState.ItemTile> {
        val occupied = (map.items.map { it.x to it.y } + map.enemies.map { it.x to it.y }).toHashSet()
        val neededSpots = 1 + BOSS_LOOT_HEAL_ITEMS + 2
        val spots = ArrayList<Pair<Int, Int>>()
        var radius = 1
        while (spots.size < neededSpots && radius <= 6) {
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (maxOf(abs(dx), abs(dy)) != radius) continue
                val x = bx + dx; val y = by + dy
                val p = x to y
                if (!map.isWall(x, y) && p !in occupied && p !in spots) spots.add(p)
            }
            radius++
        }
        spots.shuffle()
        val progress = (floor.toFloat() / DungeonState.MAX_FLOOR).coerceIn(0f, 1f)
        val candyTier = if (Random.nextFloat() < lerp(0.3f, 0.8f, progress)) CANDY_XL else CANDY_L
        val loot = ArrayList<DungeonState.ItemTile>()
        fun place(kind: String, icon: String?, xp: Float) {
            if (spots.isEmpty()) return
            val (x, y) = spots.removeAt(spots.size - 1)
            loot.add(DungeonState.ItemTile(x, y, kind, icon, xp))
        }
        place("candy", candyTier.itemFile, (candyTier.xp * xpDepthMultiplier(floor)).coerceAtLeast(1f) + bossDepthBonusXp(floor))
        repeat(BOSS_LOOT_HEAL_ITEMS) {
            val healRole = if (Random.nextBoolean()) "berry" else "medicine"
            place(healRole, randomOfRole(context, healRole), randomHealFraction(floor))
        }
        repeat(2) {
            val role = listOf("decor", "buff").random()
            val icon = if (role == "decor") DungeonItemCatalog.pickRandomDecorItem() else randomOfRole(context, role)
            place(role, icon, 0f)
        }
        return loot
    }

    // Mismos fondos reales que ya usa el widget principal (PetState, un drawable por tipo) -
    // pedido explicito del usuario: "quiero tener un fondo como los fondos que tenemos en el
    // widget". La mayoria ya son cuevas/parajes que encajan bien con una mazmorra tal cual (cueva
    // de tierra/humeda/de hielo/oscura/lila, volcan...) - uno al azar por piso, no por tipo de
    // Pokemon (la mazmorra no tiene "tipo" propio, a diferencia del compañero activo).
    private val DUNGEON_BACKGROUNDS = listOf(
        "bg_route", "bg_templo", "bg_mountain", "bg_cuevalila", "bg_desert", "bg_earthycave",
        "bg_forest", "bg_dampcave", "bg_city", "bg_volcanocave", "bg_beach", "bg_meadow",
        "bg_thunderplains", "bg_mundodistorsion", "bg_icecave", "bg_deepsea", "bg_cuevaoscura",
        "bg_bosquearcoiris"
    )

    // Tiles de suelo REALES (recortados de un tileset estilo Mundo Misterioso, pedido explicito
    // del usuario: "buscar un suelo y paredes para formar toda la mazmorra... puedes sacar varios
    // suelos para que varie cada piso") - uno al azar por piso, igual que DUNGEON_BACKGROUNDS. Las
    // paredes NO varian (el usuario solo pidio variedad en el suelo) - ver DungeonView.WALL_TILE.
    private val DUNGEON_FLOOR_TILES = listOf(
        "dungeontile_floor_tan_swirl", "dungeontile_floor_pink_swirl", "dungeontile_floor_wood_tan",
        "dungeontile_floor_red_swirl", "dungeontile_floor_slate_blue", "dungeontile_floor_lavender",
        "dungeontile_floor_wood_plank", "dungeontile_floor_brown_rock", "dungeontile_floor_purple_rock",
        "dungeontile_floor_gray_rock"
    )

    // XP fija y modesta para vitaminas - mas floja que hasta el caramelo mas pequeño para que los
    // caramelos sigan siendo el objeto "de verdad bueno" de subir de nivel. Recalibrada (ver
    // comentario de CANDY_XS de mas abajo) contra el objetivo explicito del usuario: "que el
    // ultimo piso, el nivel 100, ganes seis u ocho [niveles], el piso 50 dos o cuatro".
    private const val VITAMIN_XP = 2f

    // Regeneracion pasiva por caminar - pedido explicito del usuario: "haz que el pokemon se cure
    // 1 ps cada 3 pasos". Es un chorreo pequeño y constante (no sustituye a bayas/medicinas, que
    // siguen curando un % grande de golpe) - pensado sobre todo para que explorar pasillos vacios
    // sin combates no desgaste HP "gratis" del todo, y para suavizar un poco el desgaste
    // acumulado entre combates que ya se documenta mas abajo (comentario de enemyCount).
    private const val PASSIVE_HEAL_EVERY_STEPS = 3
    private const val PASSIVE_HEAL_AMOUNT = 1f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    // Se probo un multiplicador de XP exponencial por piso encima del valor base de cada objeto,
    // pero apilado con "mas pisos = mas objetos totales" (el propio diseño de mazmorra de arriba)
    // se disparaba a +25/+32 niveles reales en UNA sola carrera - el usuario lo corto explicito:
    // "eso no tiene sentido... en una carrera subir 20 o 30 ni de coña". Revertido en su momento -
    // la profundidad ya sube la recompensa por si sola via candyTierWeights/itemRoleWeights de mas
    // abajo (mejores tiers de caramelo mas probables cuanto mas hondo), sin apilar un segundo
    // multiplicador encima.
    //
    // REPASO POSTERIOR (pedido explicito del usuario: "lo mas importante es medir la dificultad y
    // a su vez la experiencia recibida... deberias de ganar experiencia exponencialmente" para que
    // sea util a nivel alto sin estar "roto" a nivel bajo): en vez de un multiplicador NUEVO que
    // se apile con itemCount (la causa exacta del desastre de +25/+32 niveles de arriba),
    // rewardProgress cambia la FORMA de un progreso que YA se usaba dentro de tablas que siguen
    // sumando 100 (itemRoleWeights, candyTierWeights, healTierWeights) - nunca puede "dispararse",
    // solo redistribuye entre tiers ya existentes. Gamma MODERADO (1.8->1.2) - la primera version
    // de esta pasada usaba esto como UNICO mecanismo de "premia la profundidad" y el usuario
    // confirmo con datos reales que no bastaba: nivel 9 (piso~18) ganaba +2,5 niveles (el doble de
    // "casi nada"), nivel 62 (piso~55) solo +4,3 - la relacion estaba AL REVES de lo pedido
    // ("un pokemon a nivel 9 no deberia ganar nada o casi nada... si no, no incitas a jugar el
    // juego original"). rewardProgress se queda solo para variar QUE TIER sale (algo mas suave
    // ahora), la palanca de VERDAD para "cuanta XP vale" pasa a ser xpDepthMultiplier de abajo.
    private const val REWARD_GAMMA = 1.2f
    private fun rewardProgress(floor: Int): Float =
        (floor.toFloat() / DungeonState.MAX_FLOOR).coerceIn(0f, 1f).pow(REWARD_GAMMA)

    // Multiplicador de XP por profundidad ABSOLUTA - pedido explicito del usuario, con datos
    // reales de por medio (ver comentario de rewardProgress): "no tiene sentido que entre un
    // Pokemon a nivel 9 y salga con casi 3 niveles mas... para mi un Pokemon a nivel 9 no deberia
    // de ganar nada o casi nada. Porque si no al jugador no le incitas a jugar el juego original".
    // Se aplica UNA SOLA VEZ, al GENERAR cada caramelo/vitamina (nunca en stepOnce, nunca sobre un
    // total ya acumulado) - multiplica el valor de ESE objeto concreto, no "toda la carrera", asi
    // que sigue sin poder repetir el apilamiento historico (un producto de dos series que crecen
    // juntas): itemCount tambien crece con el piso, si, pero cada objeto individual vale su propio
    // xpDepthMultiplier(SU piso), no el numero total de objetos de la carrera - la suma final
    // sigue acotada por MAX_FLOOR objetos como mucho, cada uno con un valor que nunca supera el
    // valor base de su tier. Exponente alto A PROPOSITO (mucho mas agresivo que rewardProgress) -
    // en el piso 18 (techo tipico de un nivel 9) esto vale ~0,002 (caramelos/vitaminas casi sin
    // efecto), en el piso 55 (techo tipico de un nivel 62) ~0,12 (recompensa de verdad), en el
    // piso 100 vale exactamente 1 (el valor base entero, sin recortar). Verificar con
    // runBalanceSimulation en varios niveles tras cambiar XP_DEPTH_EXP - la cifra exacta es una
    // primera estimacion, igual que el resto de numeros de este fichero.
    private const val XP_DEPTH_EXP = 2.3f
    private fun xpDepthMultiplier(floor: Int): Float =
        (floor.toFloat() / DungeonState.MAX_FLOOR).coerceIn(0f, 1f).pow(XP_DEPTH_EXP)

    // Calidad de los objetos ESCALADA POR PISO - pedido explicito del usuario: "cuanto mas nivel
    // tengas mas pisos subes y mejores items te tocan, pero mejores enemigos te enfrentas". Los
    // enemigos ya escalaban con el piso (pickEnemySpecies), pero los objetos NO - el mismo caramelo
    // XS/S de siempre podia salir igual de probable en el piso 1 que en el 90. Ahora ambos pesos
    // (que categoria de objeto sale, y que tamaño de caramelo) se desplazan con el progreso
    // (floor/MAX_FLOOR): menos decorativo/vitamina y mas caramelo/potenciador segun se profundiza,
    // y los caramelos en si pasan de ser sobre todo XS/S a sobre todo M/L/XL. Primera estimacion
    // (igual que el resto de numeros de este fichero) - pensada para revisarse con
    // runBalanceSimulation en vez de a ojo.
    private fun itemRoleWeights(floor: Int): List<Pair<String, Float>> {
        val p = rewardProgress(floor)
        // berry/medicine ya no son planos (38/27 fijos en las 100 plantas) - hueco confirmado en
        // el repaso integral: la curacion no escalaba con el piso ni en probabilidad ni en
        // cantidad. Subida DELIBERADAMENTE pequeña (38->42, 27->31) y con el valor en piso 1 casi
        // intacto - berry/medicine es la variable mas fragil del sistema (afecta directamente a
        // cuantos pisos se sobrevive), asi que se valida por separado del resto de esta pasada en
        // vez de subirla de golpe. La cantidad curada por objeto SI escala mas (ver healTierWeights).
        return listOf(
            "candy" to lerp(20f, 30f, p),
            "berry" to lerp(38f, 42f, p),
            "medicine" to lerp(27f, 31f, p),
            "vitamin" to lerp(4f, 2f, p),
            "buff" to lerp(12f, 22f, p),
            "decor" to lerp(7f, 3f, p),
        )
    }

    private fun randomItemTile(context: Context, x: Int, y: Int, floor: Int): DungeonState.ItemTile {
        return when (val role = weightedPick(itemRoleWeights(floor))) {
            "candy" -> {
                val tier = weightedPick(candyTierWeights(floor))
                // La XP real se recorta por xpDepthMultiplier(floor) - ver comentario junto a su
                // declaracion: en pisos bajos (techo de un Pokemon de nivel bajo) esto vale casi
                // nada aunque salga un tier "grande", en pisos profundos vale el tier entero.
                // Minimo 1 XP SIEMPRE - pedido explicito del usuario: "porque hay caramelos que
                // dan 0 de xp... con nivel 5 no subira niveles pero si ganara xp aun que sea
                // poca". Sin este suelo, un tier XS en el piso 1 (3 XP * multiplicador ~0,002)
                // redondeaba a "+0 XP" en pantalla (itemHere.xp.toInt()) - tecnicamente sumaba
                // una fraccion minuscula, pero se veia y se sentia como un objeto roto/inutil.
                DungeonState.ItemTile(x, y, "candy", tier.itemFile, (tier.xp * xpDepthMultiplier(floor)).coerceAtLeast(1f))
            }
            "vitamin" -> DungeonState.ItemTile(x, y, "vitamin", randomOfRole(context, "vitamin"), (VITAMIN_XP * xpDepthMultiplier(floor)).coerceAtLeast(1f))
            // Sorteo PONDERADO por rareza (Comun/Poco comun/Raro/Epico), no uniforme entre los
            // 129 - pedido explicito del usuario: "habra que hacer que haya objetos mas raros
            // que otros". Ver DungeonItemCatalog.
            "decor" -> DungeonState.ItemTile(x, y, "decor", DungeonItemCatalog.pickRandomDecorItem(), 0f)
            // Tier de curacion resuelto AQUI (al generar el objeto, igual que el caramelo) - ver
            // comentario de HealTier. La fraccion 0-1 se guarda en el mismo campo "xp" que usan
            // candy/vitamin para su XP absoluta - stepOnce (rama "berry","medicine") sabe leerlo
            // como fraccion de HP en vez de XP segun el "kind" del objeto.
            "berry", "medicine" -> DungeonState.ItemTile(x, y, role, randomOfRole(context, role), randomHealFraction(floor))
            else -> DungeonState.ItemTile(x, y, role, randomOfRole(context, role), 0f)
        }
    }

    // ---------------- pesos/tablas reutilizadas ----------------
    // Recalibrado a la baja - pedido explicito del usuario tras ver +12/+14 niveles reales en UNA
    // sola carrera: "por carrera deberia ganar mucho menos nivel... que el nivel 100 ganes seis u
    // ocho, el piso 50 dos o cuatro" (una tasa de mas o menos 0,065 niveles reales por piso
    // alcanzado). Contra la curva de XP real (PetState.cumXp = 5*(nivel-1)*nivel), eso exige
    // recortar el total de XP por carrera a aprox 1/10 de lo que daban los valores anteriores
    // (5/40/150/500/1500 doblados a 10/80/300/1000/3000 la ronda pasada) - validado con
    // runBalanceSimulation (ver DungeonSimulator para el detalle del antes/despues).
    // XS/S subidos un poco (1->3, 8->12) - pedido explicito del usuario: "no tiene sentido que un
    // caramelo... te de 1 de xp" - el TAMAÑO ya decide cuanta XP da (poco M/L/XL cambiado, siguen
    // siendo el premio gordo) y el PISO decide que tan probable es que te toque uno grande
    // (candyTierWeights de abajo, sin tocar) - pero hasta el mas pequeño tiene que notarse algo.
    // Impacto en el total por carrera minimo (XS/S son una fraccion pequeña de la XP total incluso
    // en pisos bajos donde mas pesan) - no ha hecho falta re-validar con el simulador.
    private data class CandyTier(val itemFile: String, val xp: Float)
    private val CANDY_XS = CandyTier("EXPCANDYXS", 3f)
    private val CANDY_S = CandyTier("EXPCANDYS", 12f)
    private val CANDY_M = CandyTier("EXPCANDYM", 30f)
    private val CANDY_L = CandyTier("EXPCANDYL", 100f)
    private val CANDY_XL = CandyTier("EXPCANDYXL", 300f)

    /** En el piso 1, sobre todo XS/S (40/30/20/8/2%, el reparto original); en el piso MAX_FLOOR,
     *  sobre todo M/L/XL - mismo mecanismo de "mejores items cuanto mas hondo" que
     *  [itemRoleWeights], aplicado dentro de los propios caramelos. */
    private fun candyTierWeights(floor: Int): List<Pair<CandyTier, Float>> {
        val p = rewardProgress(floor)
        return listOf(
            CANDY_XS to lerp(40f, 4f, p),
            CANDY_S to lerp(30f, 16f, p),
            CANDY_M to lerp(20f, 30f, p),
            CANDY_L to lerp(8f, 35f, p),
            CANDY_XL to lerp(2f, 15f, p),
        )
    }

    // Tiers de curacion, analogo a CandyTier de arriba - hueco confirmado en el repaso integral:
    // antes CUALQUIER baya/medicina (de los 109 iconos posibles) curaba exactamente el mismo roll
    // fijo (18-38% del HP maximo) sin importar el piso, sin ningun concepto de "calidad" como el
    // que ya tenia el caramelo (XS-XL). El tier se sortea al GENERAR el objeto (igual que el
    // caramelo) y la fraccion resuelta se guarda en ItemTile.xp (verificado que ese campo vale 0f
    // fijo para berry/medicine hasta ahora y no se lee en ningun otro sitio - DungeonView/
    // DungeonActivity no tocan ItemTile.xp - reutilizarlo aqui es seguro).
    private data class HealTier(val pctMin: Float, val pctMax: Float)
    private val HEAL_S = HealTier(0.12f, 0.20f)
    private val HEAL_M = HealTier(0.20f, 0.32f) // rango parecido al 18-38% de siempre
    private val HEAL_L = HealTier(0.32f, 0.48f)
    private val HEAL_XL = HealTier(0.48f, 0.70f)

    /** En el piso 1, sobre todo S/M (heal medio ~22%, parecido al ~28% de siempre pero algo mas
     *  comedido); en el piso MAX_FLOOR, sobre todo L/XL (heal medio ~39%, claramente mejor) -
     *  mismo mecanismo que [candyTierWeights]. */
    private fun healTierWeights(floor: Int): List<Pair<HealTier, Float>> {
        val p = rewardProgress(floor)
        return listOf(
            HEAL_S to lerp(55f, 10f, p),
            HEAL_M to lerp(35f, 25f, p),
            HEAL_L to lerp(8f, 40f, p),
            HEAL_XL to lerp(2f, 25f, p),
        )
    }

    private fun randomHealFraction(floor: Int): Float {
        val tier = weightedPick(healTierWeights(floor))
        return tier.pctMin + Random.nextFloat() * (tier.pctMax - tier.pctMin)
    }

    private fun <T> weightedPick(items: List<Pair<T, Float>>): T {
        val total = items.sumOf { it.second.toDouble() }
        var r = Random.nextDouble() * total
        for ((value, weight) in items) {
            r -= weight
            if (r <= 0.0) return value
        }
        return items.last().first
    }

    // ---------------- base_stats.json (copia propia - la de PetState es privada) ----------------
    // Ataque/At. Especial REALES (traidos de PokeAPI, mismo origen que el resto de
    // base_stats.json - pedido explicito del usuario: "revisar los ataques de cada Pokemon para
    // saber la potencia con la que pegan") - antes no existian estos dos campos (solo hp/speed/
    // defense) y el daño usaba una potencia SINTETICA igual para cualquier especie. No hay dato
    // de movimientos reales en el proyecto, asi que la "potencia de ataque" de una especie es el
    // mayor entre su Ataque y su Ataque Especial real (no distinguimos movimientos fisicos/
    // especiales, igual que el resto del sistema no distingue tipos de movimiento).
    private data class BaseStats(val hp: Int, val speed: Int, val defense: Int, val attack: Int)

    @Volatile private var baseStatsCache: Map<String, BaseStats>? = null

    private fun baseStatsTable(context: Context): Map<String, BaseStats> {
        baseStatsCache?.let { return it }
        val json = context.assets.open("base_stats.json").bufferedReader().use { it.readText() }
        val obj = org.json.JSONObject(json)
        val map = HashMap<String, BaseStats>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val s = obj.getJSONObject(name)
            val atk = maxOf(s.optInt("attack", 50), s.optInt("special_attack", 50))
            map[name] = BaseStats(s.getInt("hp"), s.getInt("speed"), s.getInt("defense"), atk)
        }
        baseStatsCache = map
        return map
    }

    private fun baseHp(context: Context, name: String) = baseStatsTable(context)[name.lowercase()]?.hp ?: 50
    private fun baseSpeed(context: Context, name: String) = baseStatsTable(context)[name.lowercase()]?.speed ?: 50
    private fun baseDefense(context: Context, name: String) = baseStatsTable(context)[name.lowercase()]?.defense ?: 50
    private fun baseAttack(context: Context, name: String) = baseStatsTable(context)[name.lowercase()]?.attack ?: 50

    // Media real del roster (mayor entre Ataque/At. Especial) - usada para NORMALIZAR la potencia
    // de ataque de una especie concreta contra la media, en vez de usarla en bruto: asi se
    // conserva el mismo equilibrio de daño global que ya estaba (mas o menos) calibrado con la
    // formula sintetica anterior, pero ahora cada especie pega mas o menos segun su Ataque REAL
    // relativo al resto - un Pokemon con Ataque muy por encima de la media pegara mas fuerte que
    // uno con Ataque muy por debajo, en vez de pegar todos exactamente igual.
    @Volatile private var avgAttackCache: Float? = null
    private fun averageAttack(context: Context): Float {
        avgAttackCache?.let { return it }
        val table = baseStatsTable(context)
        val avg = if (table.isEmpty()) 75f else table.values.map { it.attack }.average().toFloat()
        avgAttackCache = avg
        return avg
    }

    @Volatile private var speciesNamesCache: List<String>? = null
    private fun allSpeciesNames(context: Context): List<String> =
        speciesNamesCache ?: baseStatsTable(context).keys.toList().also { speciesNamesCache = it }

    // ---------------- tipos y tabla de eficacia (supereficaz/poco eficaz) ----------------
    // Pedido explicito del usuario: "hay que implementar supereficaces y poco eficazes segun el
    // tipo de los pokemon, los que tienen dos el principal". Copia PROPIA de assets/types.json
    // (independiente de PetState.typesOf) - esa version resuelve formas especiales (Rotom,
    // Arceus, Wormadam...) consultando el INDIVIDUO ACTIVO del jugador (que forma decorativa
    // tiene puesta, etc.), lo cual no tiene sentido para un enemigo SALVAJE de la mazmorra que
    // nunca es un individuo real del jugador - aqui solo hace falta el tipo BASE de la especie tal
    // cual viene en el catalogo. "El principal" (pedido explicito) = el PRIMERO de la lista, que
    // es como types.json ya los guarda (types.json se genero desde PokeAPI, que siempre da el
    // tipo primario primero).
    @Volatile private var typesCache: Map<String, List<String>>? = null

    private fun typesTable(context: Context): Map<String, List<String>> {
        typesCache?.let { return it }
        val json = context.assets.open("types.json").bufferedReader().use { it.readText() }
        val obj = org.json.JSONObject(json)
        val map = HashMap<String, List<String>>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val arr = obj.getJSONArray(name)
            map[name] = (0 until arr.length()).map { arr.getString(it) }
        }
        typesCache = map
        return map
    }

    private fun primaryType(context: Context, name: String): String? =
        typesTable(context)[name.lowercase()]?.firstOrNull()

    // Tabla real de eficacia de tipos (generacion actual), CON UN CAMBIO A PROPOSITO: las
    // inmunidades reales (0 = "no afecta") se sustituyen por 0.5 (poco eficaz) - pedido explicito
    // del usuario: "quitalo porque entonces te puedes encontrar enemigos que no puedas combatir" -
    // en un juego de un solo golpe sintetico (sin variedad de movimientos donde cambiar de
    // ataque), una inmunidad de verdad dejaria una carrera con un enemigo que el jugador NUNCA
    // puede hacerle daño (mazmorra=IA autonoma, no puedes huir ni cambiar de tactica). Solo se
    // listan los pares que NO son neutros (x1); cualquier par ausente de aqui es neutro.
    // Repartida por tipo ATACANTE -> mapa de tipo DEFENSOR -> multiplicador (2 = supereficaz,
    // 0.5 = poco eficaz).
    private val TYPE_CHART: Map<String, Map<String, Float>> = mapOf(
        "normal" to mapOf("rock" to 0.5f, "steel" to 0.5f, "ghost" to 0.5f),
        "fire" to mapOf("fire" to 0.5f, "water" to 0.5f, "grass" to 2f, "ice" to 2f, "bug" to 2f, "rock" to 0.5f, "dragon" to 0.5f, "steel" to 2f),
        "water" to mapOf("fire" to 2f, "water" to 0.5f, "grass" to 0.5f, "ground" to 2f, "rock" to 2f, "dragon" to 0.5f),
        "electric" to mapOf("water" to 2f, "electric" to 0.5f, "grass" to 0.5f, "ground" to 0.5f, "flying" to 2f, "dragon" to 0.5f),
        "grass" to mapOf("fire" to 0.5f, "water" to 2f, "grass" to 0.5f, "poison" to 0.5f, "ground" to 2f, "flying" to 0.5f, "bug" to 0.5f, "rock" to 2f, "dragon" to 0.5f, "steel" to 0.5f),
        "ice" to mapOf("fire" to 0.5f, "water" to 0.5f, "grass" to 2f, "ice" to 0.5f, "ground" to 2f, "flying" to 2f, "dragon" to 2f, "steel" to 0.5f),
        "fighting" to mapOf("normal" to 2f, "ice" to 2f, "poison" to 0.5f, "flying" to 0.5f, "psychic" to 0.5f, "bug" to 0.5f, "rock" to 2f, "ghost" to 0.5f, "dark" to 2f, "steel" to 2f, "fairy" to 0.5f),
        "poison" to mapOf("grass" to 2f, "poison" to 0.5f, "ground" to 0.5f, "rock" to 0.5f, "ghost" to 0.5f, "steel" to 0.5f, "fairy" to 2f),
        "ground" to mapOf("fire" to 2f, "electric" to 2f, "grass" to 0.5f, "poison" to 2f, "flying" to 0.5f, "bug" to 0.5f, "rock" to 2f, "steel" to 2f),
        "flying" to mapOf("electric" to 0.5f, "grass" to 2f, "fighting" to 2f, "bug" to 2f, "rock" to 0.5f, "steel" to 0.5f),
        "psychic" to mapOf("fighting" to 2f, "poison" to 2f, "psychic" to 0.5f, "dark" to 0.5f, "steel" to 0.5f),
        "bug" to mapOf("fire" to 0.5f, "grass" to 2f, "fighting" to 0.5f, "poison" to 0.5f, "flying" to 0.5f, "psychic" to 2f, "ghost" to 0.5f, "dark" to 2f, "steel" to 0.5f, "fairy" to 0.5f),
        "rock" to mapOf("fire" to 2f, "ice" to 2f, "fighting" to 0.5f, "ground" to 0.5f, "flying" to 2f, "bug" to 2f, "steel" to 0.5f),
        "ghost" to mapOf("normal" to 0.5f, "psychic" to 2f, "ghost" to 2f, "dark" to 0.5f),
        "dragon" to mapOf("dragon" to 2f, "steel" to 0.5f, "fairy" to 0.5f),
        "dark" to mapOf("fighting" to 0.5f, "psychic" to 2f, "ghost" to 2f, "dark" to 0.5f, "fairy" to 0.5f),
        "steel" to mapOf("fire" to 0.5f, "water" to 0.5f, "electric" to 0.5f, "ice" to 2f, "rock" to 2f, "steel" to 0.5f, "fairy" to 2f),
        "fairy" to mapOf("fire" to 0.5f, "fighting" to 2f, "poison" to 0.5f, "dragon" to 2f, "dark" to 2f, "steel" to 0.5f),
    )

    // Peso relativo de un candidato a enemigo cuyo tipo es SUPEREFICAZ contra el tipo del
    // explorador (el "tipo opuesto") frente al resto de candidatos de la misma ventana de poder -
    // pedido explicito del usuario: "puedes tocar los tipos de los pokemon para que sea raro que
    // te toque el tipo opuesto". No es 0 (no lo hace imposible, solo raro) - un peso normal es 1,
    // asi que 0.15 significa que, entre dos candidatos igual de "fuertes" para el piso, el de tipo
    // opuesto sale unas 6-7 veces menos.
    private const val BAD_MATCHUP_WEIGHT = 0.15f

    /** Sorteo dentro de la ventana [lo,hi] de [sorted] (ya sea el roster normal o el legendario)
     *  que rebaja el peso de los candidatos cuyo tipo pega supereficaz contra [playerSpecies] -
     *  ver [BAD_MATCHUP_WEIGHT]. Compartido por [pickEnemySpecies] y [pickBossSpecies]. */
    private fun weightedSpeciesPick(context: Context, sorted: List<String>, lo: Int, hi: Int, playerSpecies: String): String {
        val playerType = primaryType(context, playerSpecies) ?: return sorted[Random.nextInt(lo, hi + 1)]
        val weighted = (lo..hi).map { idx ->
            val candidate = sorted[idx]
            val enemyType = primaryType(context, candidate)
            val incoming = if (enemyType != null) TYPE_CHART[enemyType]?.get(playerType) ?: 1f else 1f
            candidate to (if (incoming >= 2f) BAD_MATCHUP_WEIGHT else 1f)
        }
        return weightedPick(weighted)
    }

    /** Multiplicador de daño de [attackerSpecies] contra [defenderSpecies] segun su tipo
     *  PRINCIPAL (primero de la lista) de cada uno - 1 (neutro) si a cualquiera de los dos no se
     *  le conoce el tipo (no deberia pasar, types.json cubre el roster entero). */
    private fun typeMultiplier(context: Context, attackerSpecies: String, defenderSpecies: String): Float {
        val atkType = primaryType(context, attackerSpecies) ?: return 1f
        val defType = primaryType(context, defenderSpecies) ?: return 1f
        return TYPE_CHART[atkType]?.get(defType) ?: 1f
    }

    // ---------------- coherencia de enemigos por piso ----------------
    // Antes un enemigo cualquiera del roster ENTERO, sin mirar el piso - bug real reportado por
    // el usuario ("he visto legendarios en el piso 10, eso no tiene mucho sentido... que tengan
    // coherencia con el piso"). Ahora que hay Ataque real (ver BaseStats arriba), el "poder" usa
    // los 4 stats (hp+speed+defense+attack) en vez de solo 3 - suficiente para que las especies
    // claramente mas fuertes (normalmente ya evolucionadas del todo) queden hacia el final del
    // orden, sin inventar ningun dato. No hay formas Mega en base_stats.json (comprobado) - se usa
    // solo lo que hay, como acepto el propio usuario ("si no [hay megas], pues simplemente con
    // los Pokemon que hay").
    // Legendarios/miticos (evolutions.json.rare) - pedido explicito del usuario: "creo que los
    // bosses tienen que ser legendarios... que no puedan salir en pisos normales". Antes podian
    // aparecer como enemigo suelto en cualquier piso >= 40 con un 8% de probabilidad; ahora esa
    // rama desaparece del todo de [pickEnemySpecies] - la unica puerta de entrada de un legendario
    // es [pickBossSpecies].
    @Volatile private var powerSortedCache: List<String>? = null // ascendente por poder, sin legendarios/miticos

    // Legendarios/miticos ("rare"=true) EXCLUIDOS del roster normal - su unica puerta de entrada
    // ahora es la lista cerrada [BOSS_ROSTER] (ya no se ordenan/usan en runtime como antes).
    private fun buildPowerRoster(context: Context) {
        if (powerSortedCache != null) return
        val stats = baseStatsTable(context)
        val scored = ArrayList<Pair<String, Int>>(stats.size)
        for (name in stats.keys) {
            if (PetState.evolutionInfo(context, name)?.rare == true) continue
            val s = stats.getValue(name)
            val power = s.hp + s.speed + s.defense + s.attack
            scored.add(name to power)
        }
        scored.sortBy { it.second }
        powerSortedCache = scored.map { it.first }
    }

    /** Especie de un enemigo nuevo, coherente con [floor]: cuanto mas hondo, mas "poder" (suma
     *  hp+speed+defense+attack). Nunca legendarios/miticos aqui (su unica puerta de entrada es
     *  [BOSS_ROSTER]) - Primera estimacion (banda de variedad del 12% del roster alrededor del
     *  punto que toque), a tunear jugando. */
    private fun pickEnemySpecies(context: Context, floor: Int, playerSpecies: String): String {
        buildPowerRoster(context)
        val sorted = powerSortedCache
        if (sorted.isNullOrEmpty()) return allSpeciesNames(context).random()
        val progress = (floor.toFloat() / DungeonState.MAX_FLOOR).coerceIn(0f, 1f)
        val center = (progress * (sorted.size - 1)).toInt()
        // Ventana de variedad de especie mas estrecha cuanto mas hondo ("aprieta la curva") - en
        // pisos altos la especie enemiga se queda mas pegada a la parte fuerte del roster en vez
        // de poder tocar ocasionalmente una mucho mas floja por puro azar.
        val window = (sorted.size * lerp(0.12f, 0.06f, progress)).toInt().coerceAtLeast(5)
        val lo = (center - window).coerceAtLeast(0)
        val hi = (center + window).coerceAtMost(sorted.size - 1)
        return weightedSpeciesPick(context, sorted, lo, hi, playerSpecies)
    }

    // ---------------- pools de items (assets/dungeonitems + dungeonitems_catalog.json) ----------------
    // De los 911 iconos de objeto que hay en el pack, `dungeonitems_catalog.json` (generado una
    // vez revisando el pack entero, pedido explicito del usuario: "revisar... todos los objetos
    // que hay disponibles para ver cuales tienen sentido y cuales no") asigna un ROL a cada uno
    // que SI tiene sentido encontrar en la mazmorra - berry/candy (ya con efecto), medicine (cura
    // HP igual que una baya - "las medicinas como las bayas"), vitamin (un poco de XP, como un
    // caramelo pero mas flojo), buff (sube una stat de combate hasta el siguiente combate - "el
    // ataque X y los demas pueden subir stats temporales") y decor (sin efecto, solo flavor:
    // tesoros, fosiles, tablillas, correo...). Los ~342 que NO tienen sentido tal cual (piedras
    // Mega, Poke Balls, TMs/HMs, objetos clave/herramientas de historia, y un puñado de nombres en
    // español sin identificar con certeza) simplemente NO aparecen en el catalogo, y por tanto
    // nunca se sortean.
    @Volatile private var itemCatalogCache: Map<String, String>? = null // fileName -> rol

    private fun itemCatalog(context: Context): Map<String, String> {
        itemCatalogCache?.let { return it }
        val json = context.assets.open("dungeonitems_catalog.json").bufferedReader().use { it.readText() }
        val obj = org.json.JSONObject(json)
        val map = HashMap<String, String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            map[name] = obj.getString(name)
        }
        itemCatalogCache = map
        return map
    }

    @Volatile private var poolsByRoleCache: Map<String, List<String>>? = null

    private fun poolsByRole(context: Context): Map<String, List<String>> {
        poolsByRoleCache?.let { return it }
        val byRole = itemCatalog(context).entries.groupBy({ it.value }, { it.key })
        poolsByRoleCache = byRole
        return byRole
    }

    private fun randomOfRole(context: Context, role: String): String? = poolsByRole(context)[role]?.randomOrNull()

    // ---------------- formula de HP (formula real de los juegos principales, IV/EV=0) ----------------
    // PROBADO Y DESCARTADO: subir el HP de AMBOS lados por igual (un "colchon" x1.3/x1.8) para
    // absorber la varianza nueva de la eficacia de tipos - verificado con runBalanceSimulation que
    // NO cambia nada (piso medio alcanzado identico con x1.3 y con x1.8, ver mas abajo): si
    // jugador Y enemigo escalan HP igual, el combate solo dura mas asaltos, pero la exposicion
    // total tambien escala igual - se cancela. El problema real no es varianza de un solo golpe,
    // es que ciertos tipos (ej. planta - solo 3 tipos le pegan fuerte pero 5 le pegan flojo, un
    // -20% neto de resistencia media) salen perdiendo de base con la eficacia de tipos, y que la
    // dificultad ya escalaba demasiado rapido con el piso ANTES de esto (ver rebajas de
    // enemyLevel/BOSS_*_MULT de mas abajo, con los mismos numeros de runBalanceSimulation).
    private fun hpFormula(baseHpValue: Int, level: Int): Int =
        (((2 * baseHpValue + level) * level) / 100 + level + 10).coerceAtLeast(11)

    /** HP maximo REAL del explorador segun su nivel actual - nunca persistido, se recalcula cada
     *  vez. Con APPLY_REAL_CANDY_XP ya en true lee el XP real de PetState directamente; el else
     *  (DungeonState.simulatedLevel) queda como red de seguridad si algun dia se necesitara volver
     *  a desactivarlo. Version PUBLICA para la UI (HUD, pantalla de asignacion), que si lee
     *  DungeonState (la carrera REAL en curso) - ver [maxHpForLevel]/[playerLevelFor] para la
     *  version pura que usa stepOnce, que en vez de leer el nivel global recibe el de [RunState]. */
    fun maxHp(context: Context, species: String, shiny: Boolean): Int {
        val level = if (APPLY_REAL_CANDY_XP) {
            PetState.levelOf(context, PetState.rawStats(context, species, shiny)?.xp ?: 0f, species)
        } else {
            DungeonState.simulatedLevel(context)
        }
        return maxHpForLevel(context, species, level)
    }

    private fun maxHpForLevel(context: Context, species: String, level: Int): Int =
        hpFormula(baseHp(context, species), level)

    /** Nivel del explorador para ESTE [run] concreto - purito, no lee DungeonState (a diferencia
     *  de DungeonState.simulatedLevel) para que stepOnce pueda correr sobre un RunState de mentira
     *  (runBalanceSimulation) sin tocar la carrera real. */
    private fun playerLevelFor(context: Context, species: String, shiny: Boolean, run: RunState): Int =
        memberLevel(context, run, 0, species, shiny)

    /** Nivel del miembro [i] del equipo (0 = explorador, 1.. = compañeros) en ESTE [run]: el real de su individuo
     *  (PetState), salvo en un simulador de balance (applyRealXp = false), donde sale del XP de simulacion. */
    private fun memberLevel(context: Context, run: RunState, i: Int, name: String, shiny: Boolean): Int =
        if (APPLY_REAL_CANDY_XP && run.applyRealXp) PetState.levelOf(context, PetState.rawStats(context, name, shiny)?.xp ?: 0f, name)
        else if (i == 0) PetState.levelOf(context, run.runStartXp + run.runXp, name)
        else PetState.levelOf(context, run.companionSimXp.getOrElse(i - 1) { 0f }, name)

    /** Equipo en orden: el explorador y despues sus compañeros. */
    private fun teamOf(species: String, shiny: Boolean, run: RunState): List<Pair<String, Boolean>> = listOf(species to shiny) + run.companions

    /** Vida maxima ACTUAL de cada miembro del equipo (segun su nivel en este [run]), en el orden del equipo. */
    private fun teamMaxHps(context: Context, species: String, shiny: Boolean, run: RunState): List<Float> =
        teamOf(species, shiny, run).mapIndexed { i, (n, s) -> maxHpForLevel(context, n, memberLevel(context, run, i, n, s)).toFloat() }

    /** Casilla libre (transitable, sin nadie en [taken]) mas cercana a ([fromX],[fromY]) por camino real, o null si no
     *  hay ninguna. Publica: la vista la usa para apartar a un sprite que quedaria encima de otro. */
    fun nearestFreeTile(map: DungeonState.DungeonMap, fromX: Int, fromY: Int, taken: Set<Pair<Int, Int>>): Pair<Int, Int>? {
        val seen = HashSet<Pair<Int, Int>>().apply { add(fromX to fromY) }
        val queue = ArrayDeque<Pair<Int, Int>>().apply { add(fromX to fromY) }
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            for (nb in walkableNeighbors(map, cx, cy)) {
                if (!seen.add(nb)) continue
                if (nb !in taken) return nb
                queue.add(nb)
            }
        }
        return null
    }

    /** Casillas iniciales de [count] compañeros junto al explorador en ([px],[py]): una fila de casillas transitables,
     *  cada una pegada a la anterior y alejandose del explorador, sin repetir ninguna ni pisar a un enemigo (nunca dos
     *  en la misma casilla - antes arrancaban todos encima del explorador). */
    fun placeFollowers(map: DungeonState.DungeonMap, px: Int, py: Int, count: Int): List<Pair<Int, Int>> {
        if (count <= 0) return emptyList()
        val taken = HashSet<Pair<Int, Int>>().apply { add(px to py); map.enemies.forEach { add(it.x to it.y) } }
        val result = ArrayList<Pair<Int, Int>>(count)
        var cur = px to py
        repeat(count) {
            val next = walkableNeighbors(map, cur.first, cur.second).filter { it !in taken }.maxByOrNull { manhattan(it.first, it.second, px, py) }
                ?: nearestFreeTile(map, px, py, taken)
                ?: (px to py)   // mapa diminuto sin sitio: no deberia pasar
            result.add(next); taken.add(next); cur = next
        }
        return result
    }

    /** Si los compañeros de [run] aun no tienen casilla (o no cuadra con el equipo), los coloca junto al explorador. */
    fun ensureFollowerTiles(run: RunState) {
        if (run.followerTiles.size != run.aliveFollowers()) run.followerTiles = placeFollowers(run.map, run.px, run.py, run.aliveFollowers())
    }

    /** Casilla de cada miembro (por indice del equipo) al EMPEZAR un combate contra [enemy]; (-1,-1) para los caidos. El enemigo y
     *  los demas enemigos ocupan la suya, y nadie comparte casilla:
     *  - si el enemigo piso la casilla de quien lidera, este se queda en la que acaba de dejar ([from]) y los demas en las
     *    suyas de ANTES del paso ([followersBefore]);
     *  - si alcanzo a un compañero (el que lidera ya esta en la suya), ese compañero se aparta a la casilla libre mas cercana;
     *  - cualquiera que cayera en una casilla ocupada se corre a la libre mas cercana.
     *  El de delante ocupa la casilla del que lidera y los demas en pie, las de la fila en orden. Es lo que se ve en pantalla. */
    private fun combatStartTiles(run: RunState, enemy: DungeonState.EnemyTile, from: Pair<Int, Int>, followersBefore: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val map = run.map
        val enemyTile = enemy.x to enemy.y
        val taken = HashSet<Pair<Int, Int>>().apply { add(enemyTile); for (e in map.enemies) add(e.x to e.y) }
        fun place(want: Pair<Int, Int>): Pair<Int, Int> {
            val p = if (want !in taken) want else (nearestFreeTile(map, want.first, want.second, taken) ?: want)
            taken.add(p)
            return p
        }
        val leaderEngaged = enemyTile == (run.px to run.py)
        val front = run.frontIndex().coerceAtLeast(0)
        val others = (0 until run.teamSize).filter { it != front && run.hpOf(it) > 0f }
        val result = MutableList(run.teamSize) { -1 to -1 }
        result[front] = place(if (leaderEngaged) from else (run.px to run.py))
        val slots = if (leaderEngaged && followersBefore.size == others.size) followersBefore else run.followerTiles
        others.forEachIndexed { j, member -> result[member] = place(slots.getOrNull(j) ?: result[front]) }
        return result
    }

    /** Potencia de ataque de UN golpe - antes era una formula sintetica IGUAL para cualquier
     *  especie (10 + nivel*2), pedido explicito del usuario: "revisar los ataques de cada Pokemon
     *  para saber la potencia con la que pegan". Ahora escala esa misma formula por el Ataque
     *  REAL de la especie (el mayor entre Ataque y At. Especial) NORMALIZADO contra la media del
     *  roster (averageAttack) - una especie con Ataque justo en la media pega igual que antes (el
     *  equilibrio global no cambia de golpe), pero una con Ataque muy por encima/debajo de la
     *  media pega notablemente mas/menos fuerte. Sigue sin haber dato real de movimientos en el
     *  proyecto, asi que no se distingue movimiento fisico/especial ni tipo. */
    private fun attackPower(context: Context, attackerSpecies: String, level: Int, atkMult: Float = 1f): Float {
        val atkRatio = baseAttack(context, attackerSpecies) / averageAttack(context)
        return (10f + level * 2f) * atkRatio * atkMult
    }

    // Probabilidad de golpe critico - mismo 1/16 (6.25%) que los juegos principales en su nivel
    // mas basico (sin objetos/rasgos que la suban, que este proyecto no modela) - respuesta a la
    // pregunta explicita del usuario: "no se si hay probabilidad de fallar o criticos" - fallar YA
    // existia (hitChance de mas abajo), critico NO existia hasta ahora.
    private const val CRIT_CHANCE = 1.0 / 16.0
    private const val CRIT_MULTIPLIER = 1.5f

    private data class AttackOutcome(val damage: Float, val critical: Boolean, val effectiveness: Float)

    private fun attackRoll(
        context: Context, attackerSpecies: String, defenderSpecies: String, attackerLevel: Int, defenderDef: Int,
        attackerSpeed: Int, defenderSpeed: Int, atkMult: Float = 1f, accBonus: Float = 0f
    ): AttackOutcome? {
        val speedDiff = (attackerSpeed - defenderSpeed).coerceIn(-50, 50)
        val hitChance = (0.90 + speedDiff / 500.0 + accBonus).coerceIn(0.75, 0.99)
        if (Random.nextDouble() > hitChance) return null
        val critical = Random.nextDouble() < CRIT_CHANCE
        val jitter = 0.85f + Random.nextFloat() * 0.30f
        val critMult = if (critical) CRIT_MULTIPLIER else 1f
        val effectiveness = typeMultiplier(context, attackerSpecies, defenderSpecies)
        // El minimo siempre es 1 (nunca 0) - la tabla de tipos ya no tiene inmunidades de verdad
        // (ver comentario de TYPE_CHART), asi que nunca deberia hacer falta, pero se deja el tope
        // por si algun golpe con atributos muy bajos redondeara a menos de 1 por otra via.
        val dmg = (attackPower(context, attackerSpecies, attackerLevel, atkMult) * (100f / (100f + defenderDef)) * jitter * critMult * effectiveness).coerceAtLeast(1f)
        return AttackOutcome(dmg, critical, effectiveness)
    }

    /** Que stat de combate sube un objeto "buff" - pedido explicito del usuario: "cuando te subes
     *  una stat que te indique que stat" (antes el texto de recogida era el mismo generico
     *  "¡Subida de stats!" para los 4, sin decir cual). */
    private enum class BuffStat(val label: String) {
        ATTACK("Ataque"), DEFENSE("Defensa"), SPEED("Velocidad"), ACCURACY("Precisión")
    }

    /** Traduce el icono de un objeto "buff" (X Ataque/Defensa/Velocidad/Precision, Golpe Certero,
     *  Guardia Especial...) a que stat de combate sube - pedido explicito del usuario: "el ataque
     *  X y los demas pueden subir stats temporales". Mismo bonus fijo para cualquier tier del
     *  objeto (XATTACK/XATTACK2/XATTACK3/XATTACK6 pegan igual de fuerte) - primera estimacion, a
     *  tunear jugando. SE ACUMULAN entre si (pedido explicito del usuario: "deberian de
     *  acumularse, si te subes el ataque y la velocidad el siguiente ataque deberia tener los
     *  dos") - solo se toca la stat concreta de ESTE objeto, las otras 3 se quedan como estaban
     *  (si ya llevabas otro buff sin consumir, sigue activo). Recoger DOS veces la MISMA stat no
     *  suma (se queda en el mismo +30%/+10%, no hay tope mas alto que fijar). Las 4 se consumen
     *  ENTERAS juntas en el primer combate siguiente (ver resolveEncounter), gane o pierda.
     *  Muta [run] directamente (antes escribia en DungeonState) - ver comentario de [RunState]. */
    private fun applyCombatBuffItem(run: RunState, icon: String?): BuffStat? {
        val n = (icon ?: return null).uppercase()
        return when {
            n.startsWith("XSPEED") -> { run.buffSpd = 1.3f; BuffStat.SPEED }
            n.startsWith("XACCURACY") -> { run.buffAcc = 0.10f; BuffStat.ACCURACY }
            n.startsWith("XDEFENSE") || n.startsWith("XSPDEF") || n == "GUARDSPEC" -> { run.buffDef = 1.3f; BuffStat.DEFENSE }
            else -> { run.buffAtk = 1.3f; BuffStat.ATTACK } // XATTACK/XSPATK/DIREHIT* y reserva
        }
    }

    /** Un miembro del equipo en un combate: especie, nivel, vida con la que EMPIEZA (0 = ya caido, no pelea) y casilla de salida. */
    private class FighterInfo(
        val name: String, val level: Int, val startHp: Float, val maxHp: Float = startHp, val startTile: Pair<Int, Int> = -1 to -1
    )

    /** [finalHps] = vida de cada miembro al acabar (mismo orden que el equipo); [won] = queda alguien en pie;
     *  [startFront]/[endFront] = quien iba delante en el primer golpe y al acabar; [startTiles]/[endTiles] = casilla de
     *  cada miembro al empezar y al acabar. */
    private class EncounterResult(
        val finalHps: List<Float>, val won: Boolean, val rounds: List<CombatRound>, val enemyMaxHp: Float,
        val startFront: Int = 0, val endFront: Int = 0,
        val startTiles: List<Pair<Int, Int>> = emptyList(), val endTiles: List<Pair<Int, Int>> = emptyList()
    )

    /** Pura - ni lee ni escribe DungeonState (recibe nivel y buffs ya resueltos por el llamador,
     *  ver comentario de [RunState]).
     *
     *  EQUIPO POSICIONAL (pedido explicito del usuario: "si el companero no puede pegar, no pega; si esta en una sala amplia
     *  se mueve para acercarse al enemigo"): cada miembro tiene su casilla ([FighterInfo.startTile]) y el enemigo la suya.
     *  Solo golpea quien esta PEGADO al enemigo (TeamCombatGeometry.canStrike: 8 direcciones, sin pegar a traves de la
     *  esquina de una pared); el que no llega da un paso hacia el en cada asalto (TeamCombatGeometry.approachStep) y, en un
     *  pasillo de una casilla de ancho, el que va detras del primero ni llega ni puede pasar: no pega. El enemigo golpea
     *  a CUALQUIERA de los que tiene pegados (pedido del usuario: "que vayan a por cualquiera del equipo"): el primer golpe, al que
     *  ha alcanzado ([engaged]); despues, al azar entre los pegados. Si no tiene a nadie al alcance, no golpea.
     *  Si al de delante le queda menos del 25 % entra el compañero pegado a el con mas vida (se cambian de casilla); si
     *  cae, el mejor que siga en pie. El que esta por debajo del 25 % no se acerca (se queda atras) salvo que sea el
     *  unico. Con un solo miembro es exactamente el combate de siempre. El buff de objeto vale para todo el equipo.
     *  [blocked] = casillas que no se pueden pisar al moverse (otros enemigos). */
    private fun resolveEncounter(
        context: Context, team: List<FighterInfo>, enemy: DungeonState.EnemyTile,
        buffAtk: Float, buffDef: Float, buffSpd: Float, buffAcc: Float, startFront: Int = 0,
        map: DungeonState.DungeonMap, blocked: Set<Pair<Int, Int>> = emptySet(), engaged: Int = -1
    ): EncounterResult {
        val hps = FloatArray(team.size) { team[it].startHp }
        val maxHps = team.map { it.maxHp }
        val tiles = Array(team.size) { team[it].startTile }
        val enemyTile = enemy.x to enemy.y
        val isWall = { x: Int, y: Int -> map.isWall(x, y) }
        fun reaches(i: Int) = hps[i] > 0f && TeamCombatGeometry.canStrike(isWall, tiles[i].first, tiles[i].second, enemyTile.first, enemyTile.second)
        fun touching(a: Int, b: Int) = abs(tiles[a].first - tiles[b].first) <= 1 && abs(tiles[a].second - tiles[b].second) <= 1
        fun frac(i: Int) = hps[i] / maxHps[i].coerceAtLeast(1f)
        fun snap() = tiles.toList()
        // Quien va delante lo decide ExplorerPartyRules.nextFront: cede el puesto al bajar del 25 % de su vida, pero SOLO a
        // alguien pegado a el (se cambian de casilla: el de atras ocupa su sitio pegado al enemigo); si cae, entra el mejor.
        val front0 = startFront.coerceIn(0, team.size - 1)
        var front = ExplorerPartyRules.nextFront(hps.toList(), maxHps, front0) { j -> j != front0 && touching(j, front0) }
        if (front >= 0 && front != front0 && hps[front0] > 0f) { val t = tiles[front0]; tiles[front0] = tiles[front]; tiles[front] = t }
        val firstFront = front.coerceAtLeast(0)
        val startTiles = snap()
        val enemyDef = baseDefense(context, enemy.species)
        val enemySpeed = baseSpeed(context, enemy.species)
        // Jefe = mas HP y mas ataque que un enemigo normal del mismo nivel/especie - pedido
        // explicito del usuario: "que sea mas potente" (la especie/nivel ya lo hacen mas fuerte
        // vía pickBossSpecies/BOSS_LEVEL_BONUS, esto es el "extra" de jefe encima de eso).
        var enemyHp = hpFormula(baseHp(context, enemy.species), enemy.level).toFloat() * (if (enemy.isBoss) BOSS_HP_MULT else 1f)
        val enemyMaxHp = enemyHp
        // ENEMY_ATK_DAMPING (ver comentario junto a su declaracion) - a diferencia de subir el HP
        // de los dos bandos por igual (probado y descartado, ver hpFormula), esto SI es asimetrico
        // de verdad: baja el daño que recibe el jugador sin tocar el que hace el, asi que no se
        // cancela solo alargando el combate para los dos.
        val enemyAtkMult = (if (enemy.isBoss) BOSS_ATK_MULT else 1f) * ENEMY_ATK_DAMPING
        // Registro golpe a golpe (pedido explicito del usuario: "que no desaparezca
        // inmediatamente, que tengan su barra de vida, que ponga lo que le quitas y lo que te
        // quitan") - la vista en vivo anima esta lista turno a turno en vez de saltar directo al
        // resultado final.
        val roundLog = ArrayList<CombatRound>()
        var rounds = 0
        // Mas miembros = combates mas largos (relevos y acercamientos): el tope de asaltos crece con el equipo.
        val roundCap = 80 + 20 * (team.count { it.startHp > 0f } - 1).coerceAtLeast(0)
        // Orden estricto por velocidad: el mas rapido de los dos actua primero en cada asalto, y
        // si su golpe deja al otro a 0 HP, el asalto se corta ahi mismo - el que acaba de morir
        // NUNCA llega a golpear de vuelta. Pedido explicito del usuario: "si el mas rapido es
        // capaz de pegar y mata de un golpe al Pokemon, no deberia de ser capaz el otro Pokemon
        // de atacar" (revierte un fix anterior que garantizaba que los dos actuaran siempre
        // dentro de un mismo asalto - ese "intercambio real" es justo lo que ahora se reporta
        // como bug, asi que se prioriza este pedido mas reciente). Con equipo: el orden lo marca
        // la velocidad del que va DELANTE; en su turno atacan los miembros que llegan al enemigo.
        var lastFront = firstFront   // el ultimo que fue delante de verdad (por si el golpe final tumba a todos)
        var idleRounds = 0           // asaltos seguidos sin golpe ni paso (nadie llega a nadie): se corta para no quedarse en un bucle
        var firstEnemyStrike = true  // el primer golpe del enemigo va al miembro al que ha llegado ([engaged]); los demas, a cualquiera que lo toque
        while (front >= 0 && enemyHp > 0f && rounds < roundCap && idleRounds < 8) {
            rounds++
            lastFront = front
            var acted = false
            val frontSpeed = (baseSpeed(context, team[front].name) * buffSpd).toInt()
            val order = if (frontSpeed >= enemySpeed) listOf(true, false) else listOf(false, true)
            for (playerActs in order) {
                if (playerActs) {
                    val inOrder = (listOf(front) + team.indices.filter { it != front }).filter { hps[it] > 0f }
                    val strikers = inOrder.filter { reaches(it) }
                    val anyHealthy = inOrder.any { frac(it) >= ExplorerPartyRules.FRONT_SWAP_FRACTION }
                    // Quien no llega da un paso hacia el enemigo (salvo el que esta por debajo del 25 %, que se queda atras
                    // mientras quede algun compañero sano; si todos estan tocados, todos pelean).
                    var firstMover = -1
                    for (i in inOrder) {
                        if (reaches(i) || (frac(i) < ExplorerPartyRules.FRONT_SWAP_FRACTION && anyHealthy)) continue
                        val occupied = HashSet<Pair<Int, Int>>(blocked).apply { add(enemyTile); for (j in team.indices) if (j != i && hps[j] > 0f) add(tiles[j]) }
                        val step = TeamCombatGeometry.approachStep(isWall, tiles[i], enemyTile, occupied) ?: continue
                        tiles[i] = step
                        if (firstMover < 0) firstMover = i
                    }
                    if (firstMover >= 0) {
                        roundLog.add(CombatRound(true, 0f, false, false, 1f, hps[firstMover], enemyHp, actor = firstMover, memberHpAfter = hps.toList(), front = front, tiles = snap(), move = true))
                        acted = true
                    }
                    // Los que llegan al enemigo pegan: primero el que va delante y luego el resto, en el orden del grupo.
                    for (i in strikers) {
                        if (hps[i] <= 0f) continue
                        if (enemyHp <= 0f) break
                        val speed = (baseSpeed(context, team[i].name) * buffSpd).toInt()
                        val atk = attackRoll(context, team[i].name, enemy.species, team[i].level, enemyDef, speed, enemySpeed, atkMult = buffAtk, accBonus = buffAcc)
                        if (atk != null) enemyHp = (enemyHp - atk.damage).coerceAtLeast(0f)
                        roundLog.add(CombatRound(true, atk?.damage ?: 0f, atk == null, atk?.critical == true, atk?.effectiveness ?: 1f, hps[i], enemyHp, actor = i, memberHpAfter = hps.toList(), front = front, tiles = snap()))
                        acted = true
                    }
                    if (enemyHp <= 0f) break
                } else {
                    // El enemigo va a por CUALQUIERA del equipo que tenga pegado, no solo al de delante (pedido del usuario); un golpe a
                    // un compañero cuenta igual que uno al lider, y todos siguen yendo a por el.
                    val hit = ExplorerPartyRules.enemyStrikeTarget(team.indices.filter { reaches(it) }, if (firstEnemyStrike) engaged else -1)
                    if (hit < 0) continue   // nadie a su alcance: este asalto no golpea
                    firstEnemyStrike = false
                    val hitDef = (baseDefense(context, team[hit].name) * buffDef).toInt()
                    val hitSpeed = (baseSpeed(context, team[hit].name) * buffSpd).toInt()
                    val atk = attackRoll(context, enemy.species, team[hit].name, enemy.level, hitDef, enemySpeed, hitSpeed, atkMult = enemyAtkMult)
                    if (atk != null) hps[hit] = (hps[hit] - atk.damage).coerceAtLeast(0f)
                    roundLog.add(CombatRound(false, atk?.damage ?: 0f, atk == null, atk?.critical == true, atk?.effectiveness ?: 1f, hps[hit], enemyHp, actor = hit, memberHpAfter = hps.toList(), front = front, tiles = snap()))
                    acted = true
                    // Cambio de delantero: si al de delante le queda menos del 25 % (o ha caido), entra otro - solo uno pegado a el
                    // (se cambian de casilla); si el de delante cayo, el mejor que siga en pie.
                    if (hit == front) {
                        val old = front
                        val cand = ExplorerPartyRules.nextFront(hps.toList(), maxHps, old) { j -> j != old && touching(j, old) }
                        if (cand < 0) { front = -1; break }
                        if (cand != old) {
                            if (hps[old] > 0f) { val t = tiles[old]; tiles[old] = tiles[cand]; tiles[cand] = t }
                            front = cand
                        }
                    } else if (hps.none { it > 0f }) { front = -1; break }
                }
            }
            idleRounds = if (acted) 0 else idleRounds + 1
        }
        return EncounterResult(hps.toList(), hps.any { it > 0f }, roundLog, enemyMaxHp, firstFront, if (front >= 0) front else lastFront, startTiles, snap())
    }

    /** 8 direcciones (pedido explicito del usuario: "los Pokemon se pueden pegar en diagonal, creo
     *  que ahora mismo no deja") - una diagonal solo cuenta como transitable si las DOS casillas
     *  cardinales contiguas tambien lo son (si no, se estaria "cortando" la esquina de una pared,
     *  atravesandola visualmente - regla estandar en juegos de rejilla). */
    internal fun walkableNeighbors(map: DungeonState.DungeonMap, x: Int, y: Int): List<Pair<Int, Int>> {
        val n = !map.isWall(x, y - 1); val s = !map.isWall(x, y + 1)
        val w = !map.isWall(x - 1, y); val e = !map.isWall(x + 1, y)
        val result = ArrayList<Pair<Int, Int>>(8)
        if (n) result.add(x to y - 1)
        if (s) result.add(x to y + 1)
        if (w) result.add(x - 1 to y)
        if (e) result.add(x + 1 to y)
        if (n && w && !map.isWall(x - 1, y - 1)) result.add(x - 1 to y - 1)
        if (n && e && !map.isWall(x + 1, y - 1)) result.add(x + 1 to y - 1)
        if (s && w && !map.isWall(x - 1, y + 1)) result.add(x - 1 to y + 1)
        if (s && e && !map.isWall(x + 1, y + 1)) result.add(x + 1 to y + 1)
        return result
    }

    private fun manhattan(x1: Int, y1: Int, x2: Int, y2: Int) = abs(x1 - x2) + abs(y1 - y2)

    /** Primer paso real (BFS, no una heuristica de distancia recta) de (fromX,fromY) hacia
     *  (toX,toY) - a diferencia de la heuristica anterior (Manhattan + algo de azar), esto NUNCA
     *  se queda dando vueltas en un rincon: siempre hay un camino real garantizado (las salas
     *  estan conectadas en cadena) y BFS lo encuentra sin fallo. Bug real reportado por el
     *  usuario ("el Pokemon se queda atascado girando"). Si ya esta en el destino, o el destino
     *  es inalcanzable (no deberia pasar nunca), se queda quieto. */
    internal fun bfsNextStep(map: DungeonState.DungeonMap, fromX: Int, fromY: Int, toX: Int, toY: Int): Pair<Int, Int> {
        if (fromX == toX && fromY == toY) return fromX to fromY
        // Mismo recorrido (mismo orden de vecinos que [walkableNeighbors]) y mismo resultado que el BFS con HashMap/HashSet de antes,
        // pero con arrays y sin crear objetos por casilla: se llama una vez por enemigo y por paso, y en una puesta al dia de varios dias
        // (decenas de miles de pasos) era lo que mas tiempo se llevaba (QaDungeon: 3 dias = ~1 min).
        val w = map.width
        val size = w * map.height
        val startIdx = fromY * w + fromX; val targetIdx = toY * w + toX
        if (startIdx !in 0 until size || targetIdx !in 0 until size) return fromX to fromY
        val parent = IntArray(size) { UNVISITED }
        val queue = IntArray(size)
        var head = 0; var tail = 0
        parent[startIdx] = startIdx
        queue[tail++] = startIdx
        var found = false
        while (head < tail) {
            val cur = queue[head++]
            if (cur == targetIdx) { found = true; break }
            val cx = cur % w; val cy = cur / w
            val n = !map.isWall(cx, cy - 1); val s = !map.isWall(cx, cy + 1)
            val ww = !map.isWall(cx - 1, cy); val e = !map.isWall(cx + 1, cy)
            if (n && parent[cur - w] == UNVISITED) { parent[cur - w] = cur; queue[tail++] = cur - w }
            if (s && parent[cur + w] == UNVISITED) { parent[cur + w] = cur; queue[tail++] = cur + w }
            if (ww && parent[cur - 1] == UNVISITED) { parent[cur - 1] = cur; queue[tail++] = cur - 1 }
            if (e && parent[cur + 1] == UNVISITED) { parent[cur + 1] = cur; queue[tail++] = cur + 1 }
            if (n && ww && !map.isWall(cx - 1, cy - 1) && parent[cur - w - 1] == UNVISITED) { parent[cur - w - 1] = cur; queue[tail++] = cur - w - 1 }
            if (n && e && !map.isWall(cx + 1, cy - 1) && parent[cur - w + 1] == UNVISITED) { parent[cur - w + 1] = cur; queue[tail++] = cur - w + 1 }
            if (s && ww && !map.isWall(cx - 1, cy + 1) && parent[cur + w - 1] == UNVISITED) { parent[cur + w - 1] = cur; queue[tail++] = cur + w - 1 }
            if (s && e && !map.isWall(cx + 1, cy + 1) && parent[cur + w + 1] == UNVISITED) { parent[cur + w + 1] = cur; queue[tail++] = cur + w + 1 }
        }
        if (!found) return fromX to fromY
        var cur = targetIdx
        while (parent[cur] != startIdx) cur = parent[cur]
        return (cur % w) to (cur / w)
    }
    private const val UNVISITED = -1

    /** Un unico BFS "en abanico" desde (fromX,fromY) a TODO el mapa alcanzable - da la distancia
     *  REAL de camino (respetando paredes) a cualquier casilla, y de que casilla vino (para poder
     *  reconstruir el primer paso hacia cualquier destino sin repetir el BFS por cada candidato). */
    internal class FloodFill(val width: Int, val dist: IntArray, val parent: IntArray)

    /** [blockX]/[blockY] (-1 por defecto = ninguna) - casilla a tratar como NO transitable SOLO
     *  para este calculo, sin tocar el mapa real. Usado para evitar que el camino mas corto a un
     *  objeto pase por la escalera de subida (ver decidePlayerStep). */
    internal fun floodFill(map: DungeonState.DungeonMap, fromX: Int, fromY: Int, blockX: Int = -1, blockY: Int = -1): FloodFill {
        // Mismo recorrido y orden de vecinos que [walkableNeighbors], sin crear una lista de pares por casilla: se calcula una o dos veces
        // por PASO del explorador y era lo que mas pesaba en la puesta al dia (QaDungeon). El resultado (dist/parent) es identico.
        val w = map.width
        val size = w * map.height
        val dist = IntArray(size) { -1 }
        val parent = IntArray(size) { -1 }
        val startIdx = fromY * w + fromX
        dist[startIdx] = 0
        val queue = IntArray(size)
        var head = 0; var tail = 0
        queue[tail++] = startIdx
        val blockIdx = if (blockX >= 0 && blockY >= 0 && blockX < w && blockY < map.height) blockY * w + blockX else -1
        while (head < tail) {
            val cur = queue[head++]
            val cx = cur % w; val cy = cur / w
            val n = !map.isWall(cx, cy - 1); val s = !map.isWall(cx, cy + 1)
            val ww = !map.isWall(cx - 1, cy); val e = !map.isWall(cx + 1, cy)
            val d = dist[cur] + 1
            if (n) { val i = cur - w; if (i != blockIdx && dist[i] == -1) { dist[i] = d; parent[i] = cur; queue[tail++] = i } }
            if (s) { val i = cur + w; if (i != blockIdx && dist[i] == -1) { dist[i] = d; parent[i] = cur; queue[tail++] = i } }
            if (ww) { val i = cur - 1; if (i != blockIdx && dist[i] == -1) { dist[i] = d; parent[i] = cur; queue[tail++] = i } }
            if (e) { val i = cur + 1; if (i != blockIdx && dist[i] == -1) { dist[i] = d; parent[i] = cur; queue[tail++] = i } }
            if (n && ww && !map.isWall(cx - 1, cy - 1)) { val i = cur - w - 1; if (i != blockIdx && dist[i] == -1) { dist[i] = d; parent[i] = cur; queue[tail++] = i } }
            if (n && e && !map.isWall(cx + 1, cy - 1)) { val i = cur - w + 1; if (i != blockIdx && dist[i] == -1) { dist[i] = d; parent[i] = cur; queue[tail++] = i } }
            if (s && ww && !map.isWall(cx - 1, cy + 1)) { val i = cur + w - 1; if (i != blockIdx && dist[i] == -1) { dist[i] = d; parent[i] = cur; queue[tail++] = i } }
            if (s && e && !map.isWall(cx + 1, cy + 1)) { val i = cur + w + 1; if (i != blockIdx && dist[i] == -1) { dist[i] = d; parent[i] = cur; queue[tail++] = i } }
        }
        return FloodFill(w, dist, parent)
    }

    private fun realDistance(flood: FloodFill, x: Int, y: Int): Int? =
        flood.dist[y * flood.width + x].takeIf { it >= 0 }

    /** Primer paso hacia (toX,toY) reconstruido a partir de un [FloodFill] YA calculado desde la
     *  posicion actual - mismo resultado que repetir un BFS con ese destino como objetivo, pero
     *  sin recalcularlo por cada candidato. */
    private fun firstStepToward(flood: FloodFill, fromX: Int, fromY: Int, toX: Int, toY: Int): Pair<Int, Int> {
        val startIdx = fromY * flood.width + fromX
        val targetIdx = toY * flood.width + toX
        if (startIdx == targetIdx || flood.dist[targetIdx] < 0) return fromX to fromY
        var cur = targetIdx
        while (flood.parent[cur] != startIdx && flood.parent[cur] != -1) cur = flood.parent[cur]
        return (cur % flood.width) to (cur / flood.width)
    }

    /** IA del explorador (pedido explicito del usuario, "sabe que tiene que pasar de sala... si
     *  en esa sala no hay escalera lo marca como visto y pasa a la siguiente"): NO sabe de
     *  antemano donde esta la escalera de subida - va sala por sala (la mas cercana de las aun no
     *  vistas) hasta encontrarla, con un desvio a por cualquier objeto a 5 casillas o menos si
     *  aparece uno por el camino. Si un enemigo esta en la ruta calculada, el combate salta solo
     *  (misma comprobacion de colision de siempre en stepOnce) - no hace falta perseguirlo aparte.
     *
     * "5 casillas" se mide con la distancia REAL de camino (un unico floodFill, no un BFS por
     * candidato) - antes se media con manhattan() en linea recta, ignorando paredes, lo que podia
     * hacer que un objeto "entrara en rango" en una casilla y "saliera de rango" en la siguiente
     * (si el camino real hasta el hace falta rodear una pared primero), dejando al explorador
     * rebotando para siempre entre esas dos casillas - bug real reportado por el usuario ("se ha
     * quedado en bucle moviendose de un bloque a otro"). Con la distancia real, el primer paso
     * hacia CUALQUIER objetivo siempre reduce su distancia en exactamente 1, asi que la casilla de
     * la que se acaba de venir nunca puede volver a ser el "paso mas corto" hacia el nuevo
     * objetivo - la casilla anterior queda descartada del candidato por tener distancia PEOR
     * (asi es como entro/salio de rango), nunca mejor. */
    private fun decidePlayerStep(map: DungeonState.DungeonMap, px: Int, py: Int): Pair<Int, Int> {
        val flood = floodFill(map, px, py)

        // Piso de jefe: va derecho a el, por encima de cualquier otra cosa - un piso de jefe no
        // tiene objetos previos (bossLootTiles los pone SOLO al derrotarlo) y la sala es unica, asi
        // que no hay nada mas que explorar ni ninguna razon para rodearlo camino de la salida.
        val boss = map.enemies.firstOrNull { it.isBoss }
        if (boss != null) return firstStepToward(flood, px, py, boss.x, boss.y)

        // Bug real reportado por el usuario: "a veces se salta objetos porque tiene la escalera
        // delante... si ve un objeto no puede entrar en la casilla de la escalera de subida" - el
        // camino mas corto a un objeto podia pasar POR la casilla de salida, y pisarla de paso
        // (sin intencion de subir) ya dispara el cambio de piso en stepOnce, perdiendo el objeto
        // para siempre. Si hay algun objeto en rango con el flood normal, se recalcula con esa
        // casilla bloqueada - si sigue habiendo uno alcanzable sin pasar por ahi, se rodea; si el
        // unico camino posible es a traves de la escalera, se trata como inalcanzable por ahora
        // (mejor perder ese objeto que subir de piso sin querer).
        val anyItemNear = map.items.any { (realDistance(flood, it.x, it.y) ?: Int.MAX_VALUE) <= 5 }
        if (anyItemNear) {
            val floodNoExit = floodFill(map, px, py, map.exitX, map.exitY)
            val nearItem = map.items
                .mapNotNull { item -> realDistance(floodNoExit, item.x, item.y)?.let { item to it } }
                .filter { it.second <= 5 }
                .minByOrNull { it.second }
                ?.first
            if (nearItem != null) return firstStepToward(floodNoExit, px, py, nearItem.x, nearItem.y)
        }

        val unvisited = map.rooms.indices.filter { it !in map.visitedRooms }
        val targetRoomIdx = unvisited
            .mapNotNull { idx ->
                val r = map.rooms[idx]
                realDistance(flood, (r.x0 + r.x1 - 1) / 2, (r.y0 + r.y1 - 1) / 2)?.let { idx to it }
            }
            .minByOrNull { it.second }
            ?.first
        if (targetRoomIdx != null) {
            val r = map.rooms[targetRoomIdx]
            return firstStepToward(flood, px, py, (r.x0 + r.x1 - 1) / 2, (r.y0 + r.y1 - 1) / 2)
        }
        // Reserva (no deberia pasar - si todas las salas ya estan vistas, ya deberiamos haber
        // pisado la escalera de subida al entrar en su sala): ir directo a la salida.
        return firstStepToward(flood, px, py, map.exitX, map.exitY)
    }

    /** IA de los enemigos (pedido explicito del usuario: "van en direccion a mi Pokemon pero solo
     *  si estan en un radio de 3 bloques") - persiguen de verdad (BFS, no en linea recta) solo si
     *  estan cerca. Si no persiguen, YA NO deambulan al azar sin rumbo (bug real reportado por el
     *  usuario: "se quedan siempre en la misma sala" - un paso al azar entre vecinos rara vez
     *  encuentra por pura suerte el unico pasillo de salida de una sala de varias casillas de
     *  ancho) - en su lugar persiguen una sala DISTINTA elegida al azar (bfsNextStep, mismo
     *  pathfinding que el jugador), y al llegar a ella (o si el objetivo ya no es valido) eligen
     *  otra sala distinta nueva. [DungeonState.EnemyTile.targetRoomIdx] es donde se recuerda esto
     *  entre pasos - devuelve el paso Y el targetRoomIdx actualizado para que el llamador lo
     *  persista en el propio EnemyTile. */
    private fun decideEnemyStep(map: DungeonState.DungeonMap, enemy: DungeonState.EnemyTile, team: List<Pair<Int, Int>>): Pair<Pair<Int, Int>, Int> {
        // Persigue al miembro del equipo MAS CERCANO (el que lidera o cualquiera de los que van detras), no solo al que lidera
        // (ExplorerPartyRules.chaseTarget). Al alcanzar la casilla de un compañero el combate salta igual y pelean todos.
        val chased = ExplorerPartyRules.chaseTarget(enemy.x to enemy.y, team)
        if (chased != null) {
            return bfsNextStep(map, enemy.x, enemy.y, team[chased].first, team[chased].second) to enemy.targetRoomIdx
        }
        if (map.rooms.size < 2) {
            // no hay entre que sala elegir - deambular localmente como antes (reserva).
            return (walkableNeighbors(map, enemy.x, enemy.y).randomOrNull() ?: (enemy.x to enemy.y)) to enemy.targetRoomIdx
        }
        var target = enemy.targetRoomIdx
        val reachedTarget = target in map.rooms.indices && map.rooms[target].contains(enemy.x, enemy.y)
        if (target !in map.rooms.indices || reachedTarget) {
            val currentRoom = map.rooms.indexOfFirst { it.contains(enemy.x, enemy.y) }
            target = map.rooms.indices.filter { it != currentRoom }.random()
        }
        val r = map.rooms[target]
        val step = bfsNextStep(map, enemy.x, enemy.y, (r.x0 + r.x1 - 1) / 2, (r.y0 + r.y1 - 1) / 2)
        return step to target
    }

    // ==================== API compartida por segundo plano y vista en vivo ====================

    /** Estado MUTABLE de una carrera en curso - lo carga [loadRunState] (de lo persistido, o
     *  genera un mapa nuevo si hace falta) y lo va mutando [stepOnce] paso a paso. El llamador
     *  decide cuando persistirlo ([persist]).
     *
     *  [runStartXp]/[runXp]/[itemsCollected]/[buffAtk..buffAcc] viven AQUI (no se leen/escriben en
     *  DungeonState dentro de stepOnce) - pedido explicito del usuario: "añadir algun tipo de log
     *  a las mazmorras para... tirar varias veces un Pokemon... y ver estadisticas... para
     *  ajustar parametros". Con esto [stepOnce] es una funcion PURA (nunca toca SharedPreferences
     *  por su cuenta), asi que [runBalanceSimulation] puede correr cientos de intentos sobre un
     *  RunState de mentira sin arriesgar la carrera real - antes stepOnce llamaba directo a
     *  DungeonState.addRunXp/incRunItemsCollected/combatBuff* en cada paso, lo que habria
     *  corrompido la carrera real en curso si se simulaba a la vez. */
    class RunState(
        var floor: Int, hp: Float, var map: DungeonState.DungeonMap, var px: Int, var py: Int,
        var runStartXp: Float = 0f, var runXp: Float = 0f, var itemsCollected: Int = 0,
        var buffAtk: Float = 1f, var buffDef: Float = 1f, var buffSpd: Float = 1f, var buffAcc: Float = 0f,
        // Regeneracion pasiva (pedido explicito del usuario: "que el pokemon se cure 1 ps cada 3
        // pasos") - cuenta pasos desde la ultima curacion pasiva, mismo sitio/motivo que
        // runXp/itemsCollected de arriba (vive en RunState para que stepOnce siga siendo puro).
        var stepsSincePassiveHeal: Int = 0,
        // XP total de caramelos/vitaminas repartida entre todo el equipo en esta carrera (solo informativo, para
        // el simulador de balance: [runXp] es la parte del explorador).
        var teamXp: Float = 0f,
        // Compañeros que van con el explorador en ESTA carrera (ver DungeonState.companions): pelean
        // junto a el (ver resolveEncounter) y se reparten a partes iguales el XP de caramelos/vitaminas
        // entre los que siguen en pie. Vive en RunState (no se lee de DungeonState dentro de stepOnce)
        // por el mismo motivo que runXp: stepOnce sigue puro y el simulador de balance, con un RunState
        // de mentira, no toca nada real.
        var companions: List<Pair<String, Boolean>> = emptyList(),
        // Vida de cada compañero (mismo orden que [companions]); el explorador lleva la suya en [leaderHp].
        companionHps: List<Float> = emptyList(),
        // Quien va DELANTE ahora (indice del equipo: 0 = explorador, 1.. = compañeros). Cede el puesto al bajar de
        // ExplorerPartyRules.FRONT_SWAP_FRACTION de su vida (ver nextFrontFor); sigue siendo el explorador (miembro 0)
        // quien da nombre y nivel a la carrera, solo cambia quien abre el paso y recibe los golpes.
        var front: Int = 0,
        // Casilla de cada compañero (mismo orden que [companions]): van en fila detras de quien lidera (ver
        // ExplorerPartyRules.followTheLeader). Estan en la SIMULACION (no solo en la vista) porque un enemigo que
        // los alcanza tambien provoca el combate, y asi el fondo y la vista en vivo ven el mismo mundo.
        // Vacio = aun sin colocar (stepOnce los coloca junto al explorador).
        var followerTiles: List<Pair<Int, Int>> = emptyList()
    ) {
        /** Vida del explorador (miembro 0 del equipo). */
        var leaderHp: Float = hp
        val companionHps = ArrayList<Float>(companionHps).also { while (it.size < companions.size) it.add(0f) }
        /** false = el RunState es de un simulador de balance: ni lee ni reparte XP real, los niveles salen de
         *  [runStartXp]/[runXp] (explorador) y [companionSimXp] (compañeros). */
        var applyRealXp: Boolean = true
        /** XP actual de cada compañero SOLO en simulacion (applyRealXp = false). */
        val companionSimXp = ArrayList<Float>()

        // EQUIPO: miembro 0 = explorador, 1.. = compañeros. Pelea el primero que siga en pie (el "de delante");
        // si cae, entra el siguiente. La carrera solo acaba cuando caen todos ([isDefeated]).
        val teamSize: Int get() = 1 + companions.size
        fun hpOf(i: Int): Float = if (i == 0) leaderHp else companionHps.getOrElse(i - 1) { 0f }
        fun setHp(i: Int, v: Float) {
            if (i == 0) leaderHp = v else if (i - 1 in companionHps.indices) companionHps[i - 1] = v
        }
        fun teamHps(): List<Float> = List(teamSize) { hpOf(it) }
        fun setTeamHps(hps: List<Float>) { hps.forEachIndexed { i, v -> if (i < teamSize) setHp(i, v) } }
        /** Indice del miembro que va delante: [front] mientras siga en pie; si ha caido, el primero con vida; -1 si no
         *  queda ninguno. */
        fun frontIndex(): Int =
            if (front in 0 until teamSize && hpOf(front) > 0f) front
            else (0 until teamSize).firstOrNull { hpOf(it) > 0f } ?: -1
        fun isDefeated(): Boolean = frontIndex() < 0
        /** Cuantos miembros siguen en pie. */
        fun aliveCount(): Int = (0 until teamSize).count { hpOf(it) > 0f }
        /** Cuantos van DETRAS de quien lidera (los que siguen en pie menos el de delante): [followerTiles] tiene una casilla por cada uno. */
        fun aliveFollowers(): Int = (aliveCount() - 1).coerceAtLeast(0)
        /** Vida del que va delante (0 si no queda nadie): las curaciones y la regeneracion "de delante" usan esta. */
        var hp: Float
            get() { val f = frontIndex(); return if (f < 0) 0f else hpOf(f) }
            set(v) { val f = frontIndex(); if (f >= 0) setHp(f, v) }
    }

    data class EnemyMove(val id: Int, val species: String, val fromX: Int, val fromY: Int, val toX: Int, val toY: Int)

    /** Un golpe del combate - pedido explicito del usuario: "cuando se pegue con un pokemon que
     *  no desaparezca inmediatamente, que tengan su barra de vida, que ponga lo que le quitas y lo
     *  que te quitan". [playerActs]=true es el explorador atacando (damage baja HP del enemigo),
     *  false es el enemigo atacando (damage baja HP del explorador) - [playerHpAfter]/
     *  [enemyHpAfter] son instantaneas COMPLETAS tras este golpe (no solo del lado que ataco), para
     *  poder dibujar las dos barras de vida sin tener que reconstruir nada en la vista.
     *  [effectiveness] (pedido explicito del usuario: "implementar supereficaces y poco eficazes
     *  segun el tipo") - 2=supereficaz, 0.5=poco eficaz, 0=inmune, 1=neutro (ver TYPE_CHART); solo
     *  tiene sentido cuando el golpe no ha fallado. */
    data class CombatRound(
        val playerActs: Boolean, val damage: Float, val missed: Boolean, val critical: Boolean,
        val effectiveness: Float, val playerHpAfter: Float, val enemyHpAfter: Float,
        // EQUIPO (el explorador y sus compañeros pelean juntos, ver resolveEncounter): [actor] = indice del miembro
        // (0 = explorador, 1.. = compañeros) que ATACA (playerActs) o que RECIBE el golpe (el que va delante, si no);
        // [memberHpAfter] = vida de TODOS los miembros tras este golpe, en el mismo orden. [playerHpAfter] sigue siendo
        // la del miembro implicado. Con un solo miembro: actor 0 y una lista de uno.
        val actor: Int = 0, val memberHpAfter: List<Float> = listOf(playerHpAfter),
        // Quien iba DELANTE mientras se jugaba este golpe (el que recibe los golpes del enemigo). Cambia entre golpes
        // cuando el de delante baja del 25 % de su vida (ExplorerPartyRules.nextFront).
        val front: Int = 0,
        // POSICIONES: casilla de cada miembro (mismo orden que el equipo) tras este suceso, y si el suceso es solo un
        // MOVIMIENTO (los que no llegan al enemigo dan un paso hacia el: no hay golpe, daño ni texto). Un miembro caido
        // conserva su ultima casilla. Vacio = combate sin posiciones (los de antes de esta version).
        val tiles: List<Pair<Int, Int>> = emptyList(), val move: Boolean = false
    )

    sealed class Outcome {
        object Empty : Outcome()
        data class Combat(
            val enemy: DungeonState.EnemyTile, val won: Boolean, val rounds: List<CombatRound>,
            val startHp: Float, val enemyMaxHp: Float,
            // Vida de todos los miembros del equipo al EMPEZAR el combate (la vista la anima golpe a golpe).
            val startHps: List<Float> = listOf(startHp),
            // Quien iba delante en el primer golpe (ya con el cambio por poca vida aplicado) y el que queda delante al acabar.
            val startFront: Int = 0, val endFront: Int = 0,
            // Casilla de cada miembro (mismo orden que el equipo) al EMPEZAR el combate; los caidos de salida llevan (-1,-1).
            val startTiles: List<Pair<Int, Int>> = emptyList()
        ) : Outcome()
        data class Pickup(val item: DungeonState.ItemTile, val effectText: String) : Outcome()
        data class Stairs(val newFloor: Int) : Outcome()
        // [enemyAlsoDefeated]/[rounds] - bug real encontrado con el propio simulador de balance:
        // antes esto era "object Defeated" sin datos, asi que el combate FATAL (el que deja al
        // jugador a 0 HP) nunca pasaba por la rama "is Outcome.Combat" del simulador - se contaba
        // como si ese combate nunca hubiera pasado. Con enemigos debiles esto hacia salir "100% de
        // combates ganados" aunque el jugador muriera constantemente, porque el UNICO combate que
        // de verdad determinaba el final de cada carrera (un KO mutuo, ganado Y perdido a la vez)
        // quedaba fuera del recuento por completo.
        // El combate FATAL lleva tambien los datos para ANIMARLO (enemigo, vidas y casillas de salida): la vista lo reproduce
        // entero antes de cerrar la carrera, asi se ve caer a todo el equipo (antes se cerraba al instante y, con
        // compañeros, parecia que el ultimo seguia vivo).
        data class Defeated(
            val enemyAlsoDefeated: Boolean, val rounds: List<CombatRound>,
            val enemy: DungeonState.EnemyTile? = null, val enemyMaxHp: Float = 0f, val startHps: List<Float> = emptyList(),
            val startFront: Int = 0, val startTiles: List<Pair<Int, Int>> = emptyList()
        ) : Outcome()
        object Finished : Outcome() // llego al piso MAX_FLOOR - fin de la mazmorra
    }

    data class StepResult(
        val fromX: Int, val fromY: Int, val toX: Int, val toY: Int,
        val enemyMoves: List<EnemyMove>, val outcome: Outcome
    )

    /** Carga el mapa/posicion actuales desde DungeonState, generando un mapa nuevo si no hay uno
     *  valido para el piso actual (asignacion nueva, o se acaba de subir de piso) - MISMA logica
     *  para segundo plano y vista en vivo, para que ambos vean exactamente el mismo mundo. */
    fun loadRunState(context: Context): RunState? {
        if (!DungeonState.isExploring(context)) return null
        val playerSpecies = DungeonState.currentSpecies(context) ?: return null
        val floor = DungeonState.currentFloor(context)
        val hp = DungeonState.currentHp(context)
        val existingMap = DungeonState.map(context)
        val runStartXp = DungeonState.runStartXp(context)
        val runXp = DungeonState.runXpGained(context)
        val itemsCollected = DungeonState.runItemsCollected(context)
        val buffAtk = DungeonState.combatBuffAtk(context)
        val buffDef = DungeonState.combatBuffDef(context)
        val buffSpd = DungeonState.combatBuffSpd(context)
        val buffAcc = DungeonState.combatBuffAcc(context)
        val stepsSincePassiveHeal = DungeonState.stepsSincePassiveHeal(context)
        val companions = DungeonState.companions(context)
        // Vida guardada de cada compañero (si falta alguna, por una carrera guardada antes de que pelearan, se
        // da por completa).
        val savedHps = DungeonState.companionHps(context)
        val companionHps = companions.mapIndexed { i, (n, s) -> savedHps.getOrNull(i) ?: maxHp(context, n, s).toFloat() }
        val front = DungeonState.frontMember(context).coerceIn(0, companions.size)
        val savedTiles = DungeonState.followerTiles(context)
        // Los compañeros que siguen en pie detras del de delante: una casilla por cada uno (los caidos ya no ocupan).
        val followersAlive = (((if (hp > 0f) 1 else 0) + companionHps.count { it > 0f }) - 1).coerceAtLeast(0)
        return if (existingMap != null) {
            val (px, py) = DungeonState.playerPos(context)
            // Casillas guardadas: solo valen si cuadran con el equipo y siguen siendo suelo del mapa (si no, se colocan de nuevo).
            val tiles = savedTiles.takeIf { t -> t.size == followersAlive && t.all { !existingMap.isWall(it.first, it.second) } } ?: emptyList()
            RunState(floor, hp, existingMap, px, py, runStartXp, runXp, itemsCollected, buffAtk, buffDef, buffSpd, buffAcc, stepsSincePassiveHeal, companions = companions, companionHps = companionHps, front = front, followerTiles = tiles)
        } else {
            val map = generateMap(context, floor, playerSpecies, 1 + companions.size)
            DungeonState.saveMap(context, floor, map, map.startX, map.startY)
            RunState(floor, hp, map, map.startX, map.startY, runStartXp, runXp, itemsCollected, buffAtk, buffDef, buffSpd, buffAcc, stepsSincePassiveHeal, companions = companions, companionHps = companionHps, front = front, followerTiles = placeFollowers(map, map.startX, map.startY, followersAlive))
        }
    }

    /** Vuelca [run] a DungeonState - UNICO sitio donde el resultado de stepOnce llega a
     *  SharedPreferences (ver comentario de [RunState]). Nunca se llama durante una simulacion de
     *  balance (runBalanceSimulation) - por eso esta es segura de correr en paralelo sin tocar la
     *  carrera real. */
    fun persist(context: Context, run: RunState) {
        DungeonState.updateProgress(context, run.floor, run.leaderHp, run.companionHps)
        DungeonState.saveMap(context, run.floor.coerceAtMost(DungeonState.MAX_FLOOR), run.map, run.px, run.py)
        DungeonState.setRunXpGained(context, run.runXp)
        DungeonState.setRunItemsCollected(context, run.itemsCollected)
        DungeonState.setCombatBuff(context, run.buffAtk, run.buffDef, run.buffSpd, run.buffAcc)
        DungeonState.setStepsSincePassiveHeal(context, run.stepsSincePassiveHeal)
        DungeonState.saveTeamLayout(context, run.front, run.followerTiles)
    }

    /** SOLO PRUEBAS - pedido explicito del usuario: "haz una forma de que puedas forzar que mi
     *  pokemon pase de piso para testear. solo lo puedes hacer tu" (via broadcast adb, ver
     *  PokeWidgetProvider.ACTION_DEBUG_DUNGEON_JUMP_FLOOR - mismo patron que
     *  ACTION_DEBUG_REVERT, sin ningun boton en la propia app). Fuerza la carrera EN CURSO al
     *  piso pedido, regenerando un mapa nuevo para el (mismo HP/XP/objetos acumulados, solo
     *  cambia donde esta) - pensado para poder llegar a un piso de jefe (multiplo de 10) al
     *  instante sin jugar hasta el de verdad. No hace nada si no hay ninguna carrera en curso. */
    fun debugJumpToFloor(context: Context, targetFloor: Int) {
        val run = loadRunState(context) ?: return
        val playerSpecies = DungeonState.currentSpecies(context) ?: return
        val floor = targetFloor.coerceIn(1, DungeonState.MAX_FLOOR)
        run.floor = floor
        run.map = generateMap(context, floor, playerSpecies, 1 + run.companions.size)
        run.px = run.map.startX
        run.py = run.map.startY
        run.followerTiles = placeFollowers(run.map, run.px, run.py, run.aliveFollowers())
        persist(context, run)
    }

    /** Resuelve UN paso: el explorador se mueve una casilla Y todos los enemigos vivos se mueven
     *  una casilla a la vez (turno sincronizado, pedido explicito del usuario: "como el
     *  ajedrez"), y comprueba que hay en la casilla de llegada. Muta [run] in-place; el llamador
     *  decide cuando persistirlo. NO hace nada si [run.floor] ya paso del tope (Outcome.Finished). */
    fun stepOnce(context: Context, species: String, shiny: Boolean, run: RunState): StepResult {
        if (run.floor > DungeonState.MAX_FLOOR) return StepResult(run.px, run.py, run.px, run.py, emptyList(), Outcome.Finished)
        val fromX = run.px; val fromY = run.py
        ensureFollowerTiles(run)
        val followersBefore = run.followerTiles   // donde estaban los compañeros antes de este paso (para colocar el combate)
        val (nx, ny) = decidePlayerStep(run.map, run.px, run.py)
        run.px = nx; run.py = ny
        // Los compañeros van en fila detras de quien lidera: SOLO se mueven si el explorador se ha movido de verdad
        // (un paso en el sitio, p. ej. recoger un objeto que quedo bajo sus pies, no los apila encima de el).
        if (nx != fromX || ny != fromY) run.followerTiles = ExplorerPartyRules.followTheLeaderTiles(run.followerTiles, fromX to fromY, nx to ny)
        val roomIdx = run.map.rooms.indexOfFirst { it.contains(nx, ny) }
        if (roomIdx >= 0) run.map.visitedRooms.add(roomIdx)

        // Regeneracion pasiva - pedido explicito del usuario: "haz que el pokemon se cure 1 ps
        // cada 3 pasos". Cuenta pasos DADOS (no importa que haya combate/objeto/vacio ese mismo
        // paso), tope el HP maximo real de [run] (nunca resucita ni supera el maximo).
        run.stepsSincePassiveHeal++
        if (run.stepsSincePassiveHeal >= PASSIVE_HEAL_EVERY_STEPS) {
            run.stepsSincePassiveHeal = 0
            // Con equipo, cada miembro que siga en pie se regenera (los caidos no se levantan).
            val team = teamOf(species, shiny, run)
            for (i in team.indices) {
                if (run.hpOf(i) <= 0f) continue
                val level = memberLevel(context, run, i, team[i].first, team[i].second)
                val maxHpNow = maxHpForLevel(context, team[i].first, level).toFloat()
                run.setHp(i, (run.hpOf(i) + PASSIVE_HEAL_AMOUNT).coerceAtMost(maxHpNow))
            }
        }

        // Movimiento de enemigos en dos fases (pedido explicito del usuario: "evitar que los
        // pokemon acaben en el mismo punto, en el mismo cuadrado") - decidePlayerStep/
        // decideEnemyStep de cada enemigo se calculan SIN mirar a los demas enemigos (cada uno
        // persigue/deambula por su cuenta), asi que dos podian converger sobre la misma casilla
        // (sobre todo en pasillos de 1 de ancho con varios persiguiendo al jugador a la vez) -
        // bug real reportado por el usuario, y probablemente la causa de que "los pokemon
        // desaparecen y aparecen a veces" (dos sprites apilados en la misma casilla, solo se ve
        // el ultimo dibujado). 1) se calcula el destino deseado de cada uno; 2) en un punto fijo
        // iterativo, cualquier casilla reclamada por mas de un enemigo a la vez se resuelve
        // dejando SOLO al primero de la lista moverse, el resto se queda en su casilla actual -
        // esto puede a su vez liberar/ocupar casillas, asi que se repite hasta que no cambie nada
        // (como mucho tantas vueltas como enemigos haya).
        val desired = HashMap<Int, Pair<Int, Int>>()
        val desiredTargetRoom = HashMap<Int, Int>()
        // Casillas de todo el equipo en pie (el que lidera primero): los enemigos persiguen a cualquiera de ellos.
        val teamTiles = listOf(run.px to run.py) + run.followerTiles
        for (e in run.map.enemies) {
            val (step, newTargetRoom) = decideEnemyStep(run.map, e, teamTiles)
            desired[e.id] = step
            desiredTargetRoom[e.id] = newTargetRoom
        }
        var changed = true
        while (changed) {
            changed = false
            val claimants = HashMap<Pair<Int, Int>, MutableList<Int>>()
            for (e in run.map.enemies) claimants.getOrPut(desired.getValue(e.id)) { ArrayList() }.add(e.id)
            for ((square, ids) in claimants) {
                if (ids.size <= 1) continue
                // Bug real reportado por el usuario ("otra vez 2 pokemons en un mismo cuadrado"):
                // el "gana el primero de la lista" de antes no distinguia entre un enemigo que YA
                // estaba parado en esa casilla (deberia ganar SIEMPRE, este donde este en la
                // lista) y otro que solo queria MOVERSE ahi - si el que se movia salia primero en
                // la lista de enemigos (orden arbitrario), ganaba el y se plantaba encima del que
                // ya estaba ahi parado, porque "corregir" al que ya estaba parado era un no-op
                // (su deseo YA era quedarse donde estaba) que nunca marcaba `changed=true`, asi
                // que el bucle terminaba sin corregir al intruso. Ahora el que YA ocupa la
                // casilla (si hay uno) tiene prioridad absoluta sobre cualquier otro que solo
                // quiera entrar - sin importar el orden de iteracion.
                val incumbentId = ids.firstOrNull { id -> run.map.enemies.first { it.id == id }.let { it.x to it.y } == square }
                val winnerId = incumbentId ?: ids.first()
                for (id in ids) {
                    if (id == winnerId) continue
                    val e = run.map.enemies.first { it.id == id }
                    val stay = e.x to e.y
                    if (desired[id] != stay) { desired[id] = stay; changed = true }
                }
            }
        }
        val enemyMoves = ArrayList<EnemyMove>()
        for (idx in run.map.enemies.indices) {
            val e = run.map.enemies[idx]
            val (ex, ey) = desired.getValue(e.id)
            enemyMoves.add(EnemyMove(e.id, e.species, e.x, e.y, ex, ey))
            run.map.enemies[idx] = e.copy(x = ex, y = ey, targetRoomIdx = desiredTargetRoom.getValue(e.id))
        }

        // Combate solo al pisar EXACTAMENTE la casilla del enemigo - pedido explicito del usuario
        // tras probarlo: "quita los ataques en diagonal" (revierte el intento anterior de que la
        // adyacencia en las 8 direcciones tambien contase como combate).
        // Con equipo, el combate salta tambien si un enemigo ALCANZA a un compañero (casilla de uno de los que van en
        // fila): pelean todos igualmente, asi nunca hay un enemigo y un compañero andando en la misma casilla.
        val enemyHere = run.map.enemies.find { it.x == run.px && it.y == run.py }
            ?: run.map.enemies.find { (it.x to it.y) in run.followerTiles }
        val itemHere = run.map.items.find { it.x == run.px && it.y == run.py }
        val outcome: Outcome = when {
            enemyHere != null -> {
                // Equipo: pelean todos los miembros en pie (ver resolveEncounter). Los caidos entran con 0 y no actuan.
                val members = teamOf(species, shiny, run)
                val startHps = run.teamHps()
                val startHp = run.hp   // la del que va delante al empezar
                // POSICIONES: cada miembro en pie tiene su casilla (el de delante en la del que lidera, los demas en las de la
                // fila) y solo pega quien queda pegado al enemigo; el que no llega se acerca (ver resolveEncounter).
                val startTiles = combatStartTiles(run, enemyHere, fromX to fromY, followersBefore)
                val fighters = members.mapIndexed { i, (n, s) ->
                    val level = memberLevel(context, run, i, n, s)
                    FighterInfo(n, level, startHps[i], maxHpForLevel(context, n, level).toFloat(), startTiles[i])
                }
                val otherEnemyTiles = run.map.enemies.filter { it.id != enemyHere.id }.map { it.x to it.y }.toSet()
                // A quien ha alcanzado el enemigo: al que lidera si piso su casilla, o al compañero cuya casilla piso.
                val frontNow = run.frontIndex().coerceAtLeast(0)
                val engagedMember = if (enemyHere.x == run.px && enemyHere.y == run.py) frontNow
                    else (0 until run.teamSize).filter { it != frontNow && startHps[it] > 0f }.getOrNull(run.followerTiles.indexOf(enemyHere.x to enemyHere.y)) ?: -1
                val result = resolveEncounter(context, fighters, enemyHere, run.buffAtk, run.buffDef, run.buffSpd, run.buffAcc, run.front, run.map, otherEnemyTiles, engagedMember)
                run.front = result.endFront
                if (!result.endTiles.isEmpty() && result.endFront in result.endTiles.indices) {
                    // Despues del combate cada uno se queda donde acabo: el de delante es quien lidera (su casilla pasa a ser la
                    // del explorador) y los demas que siguen en pie, la fila de detras (los caidos ya no ocupan casilla).
                    val leadTile = result.endTiles[result.endFront]
                    run.px = leadTile.first; run.py = leadTile.second
                    run.followerTiles = (0 until run.teamSize).filter { it != result.endFront && result.finalHps[it] > 0f }.map { result.endTiles[it] }
                }
                // El buff se consume ENTERO en este combate, gane o pierda (nunca sobrevive a un
                // segundo combate) - antes esto lo hacia DungeonState.clearCombatBuffs dentro de
                // resolveEncounter; ahora resolveEncounter es puro, asi que el reset vive aqui.
                run.buffAtk = 1f; run.buffDef = 1f; run.buffSpd = 1f; run.buffAcc = 0f
                run.setTeamHps(result.finalHps)
                if (result.won) {
                    run.map.enemies.remove(enemyHere)
                    // Botin del jefe - pedido explicito del usuario: "cuando muere aparecera
                    // experiencia, vida y objetos varios".
                    if (enemyHere.isBoss) run.map.items.addAll(bossLootTiles(context, run.map, enemyHere.x, enemyHere.y, run.floor))
                }
                if (run.isDefeated()) Outcome.Defeated(result.won, result.rounds, enemyHere, result.enemyMaxHp, startHps, result.startFront, result.startTiles)
                else Outcome.Combat(enemyHere, result.won, result.rounds, startHp, result.enemyMaxHp, startHps, result.startFront, result.endFront, result.startTiles)
            }
            itemHere != null -> {
                val effectText: String = when (itemHere.kind) {
                    "candy", "vitamin" -> {
                        // El XP se REPARTE a partes iguales entre el explorador y sus compañeros (cada uno
                        // recibe su parte entera): el total ganado es el mismo, pero llega a mas Pokemon.
                        // runXp lleva SOLO la parte del explorador, que es la que simulatedLevel suma sobre
                        // su XP inicial para dar su nivel.
                        val share = distributeCandyXp(context, species, shiny, run, itemHere.xp)
                        // Con decimales cuando es poco (un caramelo de piso bajo vale ~1 XP y entre tres toca ~0,3: antes el texto
                        // truncaba a "+0 XP cada uno" aunque la XP sumada era real y no cero).
                        if (share < itemHere.xp) "+${formatXpAmount(share)} XP cada uno" else "+${formatXpAmount(itemHere.xp)} XP"
                    }
                    // Medicinas con el MISMO efecto que las bayas - pedido explicito del usuario:
                    // "las medicinas como las bayas".
                    "berry", "medicine" -> {
                        // Las curaciones van al miembro en pie con MENOS fraccion de vida (con el relevo por poca
                        // vida, curar solo al de delante la desperdiciaria: el tocado queda atras descansando).
                        val maxs = teamMaxHps(context, species, shiny, run)
                        val target = ExplorerPartyRules.mostHurtAlive(run.teamHps(), maxs).coerceAtLeast(0)
                        val maxHpNow = maxs[target].toInt()
                        // La fraccion 0-1 a curar ya se decidio al GENERAR el objeto (ver
                        // HealTier/healTierWeights/randomHealFraction, resuelto en randomItemTile/
                        // bossLootTiles) - aqui solo se aplica sobre el HP maximo ACTUAL (que
                        // puede haber cambiado si el nivel simulado subio desde que se genero el
                        // objeto). itemHere.xp para "candy"/"vitamin" es XP absoluta; para
                        // "berry"/"medicine" es esta fraccion - mismo campo, significado distinto
                        // segun el rol, documentado en randomItemTile.
                        val healAmount = maxHpNow * itemHere.xp
                        run.setHp(target, (run.hpOf(target) + healAmount).coerceAtMost(maxHpNow.toFloat()))
                        "+${healAmount.toInt()} HP"
                    }
                    "buff" -> {
                        val stat = applyCombatBuffItem(run, itemHere.icon)
                        if (stat != null) "¡Sube ${stat.label}!" else "¡Subida de stats!"
                    }
                    // Nombre real + rareza (antes un generico "Objeto decorativo" para los 129 -
                    // ver DungeonItemCatalog) - pedido explicito del usuario: "asi se puede ver
                    // si te ha tocado un item raro" sin ni siquiera tener que abrir la pokedex.
                    "decor" -> DungeonItemCatalog.infoFor(itemHere.icon)?.let { "${it.displayName} (${it.rarity.label})" }
                        ?: "Objeto decorativo"
                    else -> "Objeto decorativo"
                }
                run.itemsCollected++
                run.map.items.remove(itemHere)
                Outcome.Pickup(itemHere, effectText)
            }
            // La escalera de subida NO funciona mientras el jefe siga vivo - pedido explicito del
            // usuario: "la escalera de subida solo aparecera si lo derrotas" (con jefe vivo, pisar
            // esa casilla no hace nada, cae al Outcome.Empty de abajo).
            run.px == run.map.exitX && run.py == run.map.exitY && run.map.enemies.none { it.isBoss } -> {
                run.floor++
                if (run.floor <= DungeonState.MAX_FLOOR) {
                    run.map = generateMap(context, run.floor, species, 1 + run.companions.size)
                    run.px = run.map.startX; run.py = run.map.startY
                    // Piso nuevo: los compañeros se colocan junto a la entrada (cada uno en su casilla).
                    run.followerTiles = placeFollowers(run.map, run.px, run.py, run.aliveFollowers())
                }
                Outcome.Stairs(run.floor)
            }
            else -> Outcome.Empty
        }
        return StepResult(fromX, fromY, nx, ny, enemyMoves, outcome)
    }

    /** XP para enseñar en un texto (ver ExplorerPartyRules.formatXp). */
    fun formatXpAmount(xp: Float): String = ExplorerPartyRules.formatXp(xp)

    /** Marca en DungeonState.discoveredItems el objeto decorativo de [outcome], si es un Pickup
     *  de rol "decor" - se llama desde los dos sitios que resuelven una carrera DE VERDAD
     *  (aqui mismo y DungeonView.advance), NUNCA desde runBalanceSimulation (RunState aislado,
     *  no deberia poder ensuciar la coleccion real - ver comentario de KEY_DISCOVERED_ITEMS). */
    fun recordDecorPickup(context: Context, outcome: Outcome) {
        if (outcome is Outcome.Pickup && outcome.item.kind == "decor") {
            DungeonState.recordItemDiscovered(context, outcome.item.icon)
        }
    }

    // 2 min por piso alcanzado - pedido explicito del usuario: "el reintento de mazmorras debe
    // tener un cooldown que dependera de la cantidad de pisos que haya pasado anteriormente. 2
    // min por piso por ejemplo". Una carrera corta (piso 5) descansa poco (10 min); una que llego
    // muy hondo (piso 80) descansa mucho (160 min) - el "esfuerzo" de la carrera anterior decide
    // cuanto tarda la siguiente en empezar, en vez de encadenar sin pausa ninguna.
    private const val COOLDOWN_MS_PER_FLOOR = 2 * 60_000L

    /** Cierra la carrera de [species]/[shiny] (llamando a [endFn], que ya escribe el resumen y
     *  deja el slot en IDLE) y, si el modo continuo estaba activo (DungeonState.autoRepeat), entra
     *  en COOLDOWN en vez de reasignar de inmediato - pedido explicito del usuario (ver
     *  COOLDOWN_MS_PER_FLOOR). [endFn] resetea KEY_FLOOR a 1, asi que el piso alcanzado hay que
     *  leerlo ANTES de llamarlo. Unico sitio que decide esto - llamado tanto desde el avance de
     *  fondo ([runTick]) como desde la vista en vivo (DungeonView), para que el comportamiento sea
     *  identico este termine la carrera donde termine.
     *
     *  [verbClause] describe COMO termino esta carrera concreta (recibe el piso alcanzado) - solo
     *  se usa cuando el modo continuo esta activo, para construir el resumen GLOBAL de la sesion
     *  (ver DungeonState.recordLoopRun) en vez del resumen de una sola carrera que [endFn] ya
     *  escribio (pedido explicito del usuario: "el resumen no tiene que poner la ultima mazmorra,
     *  tiene que poner el resumen global"). */
    /** Texto del fin de carrera por derrota: "cayó en el piso N", y con compañeros "... junto con X" (la carrera solo acaba
     *  cuando caen TODOS; antes solo se nombraba al explorador y parecia que habia caido el solo). */
    fun defeatClause(companionNames: List<String>): (Int) -> String = { floor ->
        if (companionNames.isEmpty()) "cayó en el piso $floor"
        else "cayó en el piso $floor junto con ${companionNames.joinToString(" y ") { PetState.displayLabel(it) }}"
    }

    fun finishRun(context: Context, species: String, verbClause: (Int) -> String, endFn: (Context) -> Unit) {
        val floorReached = DungeonState.currentFloor(context).coerceAtMost(DungeonState.MAX_FLOOR)
        val repeat = DungeonState.autoRepeat(context)
        // Snapshot de ESTA carrera antes de que endFn reinicie floor/HP - recordLoopRun necesita
        // estos numeros para sumarlos al total de la sesion (ver comentario de KEY_LOOP_RUNS).
        val xpGained = DungeonState.runXpGained(context)
        val levelsGained = (DungeonState.simulatedLevel(context) - DungeonState.runStartLevel(context)).coerceAtLeast(0)
        val itemsCollected = DungeonState.runItemsCollected(context)
        DebugLog.log(context, "mazmorra: ${PetState.displayLabel(species)} ${verbClause(floorReached)} - $xpGained XP, $itemsCollected objetos")
        endFn(context)
        if (repeat) {
            DungeonState.recordLoopRun(context, species, verbClause(floorReached), floorReached, xpGained, levelsGained, itemsCollected)
            val cooldownMs = floorReached * COOLDOWN_MS_PER_FLOOR
            DebugLog.log(context, "mazmorra: cierre global iniciado, reabre en ~${cooldownMs / 60_000L} min")
            DungeonState.startCooldown(context, floorReached, cooldownMs)
        }
    }

    /** Si el cierre global (ver [finishRun]/COOLDOWN_MS_PER_FLOOR/STATUS_COOLDOWN) ya paso,
     *  reasigna a quien este pre-elegido (por defecto la MISMA especie que acaba de salir,
     *  species/shiny/autoRepeat sobreviven al fin de carrera sin tocarse - o a quien sea que
     *  [DungeonState.queueNextExplorer] haya puesto en su lugar mientras se esperaba) y vuelve a
     *  EXPLORING; si nadie quedo en cola ([DungeonState.cancelQueuedExplorer]) O si quien quedo en
     *  cola paso a ser el compañero activo del widget mientras tanto (pedido explicito del
     *  usuario, "el pokemon que estes cuidando no pueda entrar en la dungeon por obvias razones" -
     *  el mismo guardian que el selector de DungeonActivity, pero aqui hace falta ADEMAS porque el
     *  bucle continuo puede reasignar sin pasar nunca por esa pantalla: el jugador pudo cambiar de
     *  compañero activo DESPUES de encolar este mismo individuo), reabre en IDLE en su lugar. Si el
     *  cierre no ha pasado, no hace nada. Se llama desde DungeonService en cada ciclo mientras el
     *  estado sea COOLDOWN, y desde [catchUpIfStalled] para poder atravesar un cierre que ya paso
     *  de sobra mientras el fondo estaba parado.
     *
     *  continuingLoop=true (ver DungeonState.assign) - esta reasignacion es la siguiente carrera
     *  automatica de la MISMA sesion en bucle, no una nueva: no debe reiniciar los acumulados del
     *  resumen global (ver KEY_LOOP_RUNS) - [queueNextExplorer] ya los reinicio el cambiar a
     *  otro, asi que aqui siempre se continua tal cual. */
    fun resolveCooldownIfReady(context: Context) = synchronized(this) { resolveCooldownIfReadyLocked(context) }

    // Con el cerrojo de runTick: ahora se llama tambien desde el hilo de la puesta al dia y al
    // arrancar el servicio (no solo desde el Handler principal), y dos llamadas a la vez podian
    // reasignar al explorador dos veces.
    private fun resolveCooldownIfReadyLocked(context: Context) {
        if (!DungeonState.isInCooldown(context)) return
        if (System.currentTimeMillis() < DungeonState.cooldownUntil(context)) return
        val species = DungeonState.currentSpecies(context)
        val shiny = DungeonState.currentShiny(context)
        val isActiveCompanion = species != null && species == PetState.currentPokemon(context) && shiny == PetState.isActiveShiny(context)
        if (species == null) {
            DebugLog.log(context, "mazmorra: cierre resuelto - reabre en IDLE (nadie en cola)")
            DungeonState.reopenIdle(context)
            return
        }
        if (isActiveCompanion) {
            DebugLog.log(context, "mazmorra: cierre resuelto - reabre en IDLE (${PetState.displayLabel(species)} es el compañero activo)")
            DungeonState.reopenIdle(context)
            return
        }
        // El pre-elegido pudo dejar de existir (borrado) o ser ya otra especie (evoluciono por un camino que no pasa por
        // DungeonState.renameMember): reasignarlo dejaba un explorador fantasma que ni gana XP ni existe (QaConflicts).
        if (!PetState.hasIndividual(context, species, shiny) || PetState.hasEvolvedAway(context, species, shiny)) {
            DebugLog.log(context, "mazmorra: cierre resuelto - reabre en IDLE (${PetState.displayLabel(species)} ya no existe como tal)")
            DungeonState.reopenIdle(context)
            return
        }
        // Mismo motivo que arriba: el bucle puede reasignar sin pasar por el selector, y entretanto el
        // pre-elegido pudo meterse en la guarderia.
        if (PetState.isInNursery(context, species, shiny)) {
            DebugLog.log(context, "mazmorra: cierre resuelto - reabre en IDLE (${PetState.displayLabel(species)} está en la guardería)")
            DungeonState.reopenIdle(context)
            return
        }
        // Los compañeros pre-elegidos tambien pudieron dejar de valer (ahora son el activo, estan en la
        // guarderia...): se quitan sin mas y el resto del grupo entra igual.
        val wanted = DungeonState.companions(context)
        val companions = DungeonState.usableCompanions(context, species to shiny, wanted)
        DebugLog.log(
            context, "mazmorra: cierre resuelto - reasigna a ${PetState.displayLabel(species)}" +
                if (wanted.isEmpty()) "" else " con ${companions.size}/${wanted.size} compañero(s)"
        )
        val startXp = PetState.rawStats(context, species, shiny)?.xp ?: 0f
        val hp = maxHp(context, species, shiny)
        DungeonState.assign(context, species, shiny, hp, startXp, autoRepeat = DungeonState.autoRepeat(context), continuingLoop = true, companions = companions)
    }

    /** Segundo plano (DungeonService, ~1 min mientras la app no esta abierta): resuelve
     *  varios [stepOnce] SEGUIDOS y en silencio (sin animar nada, nadie esta mirando) y persiste
     *  el resultado final. La vista en vivo (DungeonView) NO llama a esto - llama a [stepOnce]
     *  directamente, uno a uno, con animacion de por medio. */
    // synchronized(this) - pedido explicito del usuario al preguntar por la puesta al dia: antes
    // de esto, un [catchUpIfStalled] largo (corre en su propio hilo) podia solaparse con el
    // ciclo NORMAL de 1 minuto (hilo principal del Handler de DungeonService) si la puesta al
    // dia tardaba mas de 60s en resolverse (caso extremo, muchas horas perdidas) - dos runTick a
    // la vez mutando el mismo RunState/SharedPreferences desde dos hilos es una condicion de
    // carrera real (persist() de uno pisando al del otro, o un mismo objeto "recogido" dos veces
    // porque cada hilo cargo su propia copia de RunState antes de que el otro terminara). Con el
    // lock, el segundo runTick que llegue simplemente espera a que el primero termine y persista
    // antes de cargar su propio RunState - nunca se pisan.
    /** Un ciclo de la mazmorra (DungeonService.TICK_INTERVAL_MS) en horas - lo que suma al tiempo
     *  "cuidandolo" del explorador por cada ciclo que pasa EN la mazmorra. */
    private const val CARE_HOURS_PER_TICK = DungeonService.TICK_INTERVAL_MS / 3_600_000f

    /** Pedido explicito del usuario: "si el Pokemon esta en la mazmorra tambien suba el timer
     *  cuidandolo, porque en parte esta activo tambien". Cada ciclo EXPLORANDO suma su duracion al
     *  tiempo de cuidado ("⏱️ ... cuidandolo") del explorador. Se cuenta por CICLOS simulados, no
     *  por reloj real, igual que sus recompensas: el servicio lo llama en cada ciclo (runTick, y
     *  la vista en vivo aparte) y la puesta al dia de un paron lo repite por cada ciclo perdido.
     *  El Pokemon activo del widget ya suma por su cuenta (ver PetState.addCareHours: no cuenta
     *  dos veces), y el cooldown (nadie explorando) no suma. */
    fun accrueCareTick(context: Context) {
        if (!DungeonState.isExploring(context)) return
        // Todo el grupo (explorador + compañeros) esta en la mazmorra, asi que a todos les cuenta.
        for ((n, s) in DungeonState.party(context)) PetState.addCareHours(context, n, s, CARE_HOURS_PER_TICK)
    }

    /** Reparte [xp] (de un caramelo/vitamina) a partes iguales entre el explorador [species]/[shiny] y los
     *  compañeros de [run] (ExplorerPartyRules.sharePerMember): a cada uno le llega su parte como XP real
     *  y permanente, y [RunState.runXp] suma solo la parte del explorador (la que simulatedLevel usa para
     *  su nivel). Devuelve la parte de cada miembro. Publica para que stepOnce y la autocomprobacion usen
     *  EXACTAMENTE el mismo camino. */
    fun distributeCandyXp(context: Context, species: String, shiny: Boolean, run: RunState, xp: Float): Float {
        // Solo cobran los miembros que siguen EN PIE (un caido ya no recibe XP); si por lo que sea no queda
        // nadie, se cuenta al explorador para no perder el XP.
        val team = teamOf(species, shiny, run)
        val alive = team.indices.filter { run.hpOf(it) > 0f }.ifEmpty { listOf(0) }
        val share = ExplorerPartyRules.sharePerMember(xp, alive.size)
        run.teamXp += xp
        for (i in alive) {
            val (n, s) = team[i]
            if (APPLY_REAL_CANDY_XP && run.applyRealXp) grantDungeonXp(context, n, s, share)
            if (i == 0) run.runXp += share
            else {
                while (run.companionSimXp.size < i) run.companionSimXp.add(0f)
                run.companionSimXp[i - 1] = run.companionSimXp[i - 1] + share
            }
        }
        return share
    }

    /** XP real y permanente para [name]/[shiny] por lo que se gana en la mazmorra. Si ese Pokemon es a
     *  la vez el compañero ACTIVO del widget (puede pasar si lo pones activo mientras explora), se usa
     *  la ruta del activo: sumarle XP "de golpe" como a cualquier otro le borraria el XP por tiempo
     *  que lleva pendiente (ver PetState.grantXpToActive). */
    private fun grantDungeonXp(context: Context, name: String, shiny: Boolean, amount: Float) {
        if (name == PetState.currentPokemon(context) && shiny == PetState.isActiveShiny(context)) PetState.grantXpToActive(context, amount)
        else PetState.grantXpToIndividual(context, name, shiny, amount, durable = !batchWrites)
    }

    /** true mientras corre una puesta al dia: el XP real que concede la mazmorra se escribe sin esperar al disco en cada caramelo (miles
     *  seguidos) y se vuelca de una vez al terminar (ver [catchUpIfStalled]). */
    @Volatile private var batchWrites = false

    // Tiempo (ns) que se va en cada parte de runTick, para saber donde se gasta la puesta al dia (se vuelca en su log).
    private var profLoadNs = 0L
    private var profCareNs = 0L
    private var profStepsNs = 0L
    private var profPersistNs = 0L

    fun runTick(context: Context) = synchronized(this) {
        val species = DungeonState.currentSpecies(context) ?: return@synchronized
        val shiny = DungeonState.currentShiny(context)
        var t = System.nanoTime()
        val run = loadRunState(context) ?: return@synchronized
        profLoadNs += System.nanoTime() - t; t = System.nanoTime()
        accrueCareTick(context)
        profCareNs += System.nanoTime() - t; t = System.nanoTime()
        val steps = Random.nextInt(TILE_MOVES_MIN, TILE_MOVES_MAX_EXCLUSIVE)
        for (i in 0 until steps) {
            val result = stepOnce(context, species, shiny, run)
            recordDecorPickup(context, result.outcome)
            if (result.outcome is Outcome.Defeated) { finishRun(context, species, defeatClause(run.companions.map { it.first }), DungeonState::returnDefeated); return@synchronized }
            // Bug real (ya existia antes del modo continuo): llegar al piso 100 en SEGUNDO PLANO
            // antes solo hacia "break" y persistia, sin llamar nunca a returnFinished - el slot se
            // quedaba en EXPLORING con floor>MAX_FLOOR para siempre (stepOnce solo devuelve
            // Outcome.Finished en bucle, sin avanzar nada mas). Con el modo continuo esto
            // importa de verdad (una carrera "ganada" en fondo debe poder reiniciar), asi que se
            // arregla aqui de paso.
            if (result.outcome is Outcome.Finished) { finishRun(context, species, { "completó la mazmorra entera" }, DungeonState::returnFinished); return@synchronized }
        }
        profStepsNs += System.nanoTime() - t; t = System.nanoTime()
        persist(context, run)
        profPersistNs += System.nanoTime() - t
    }

    /** Vuelca (y pone a cero) lo que cuesta cada parte de runTick desde la ultima vez - para el log de la puesta al dia. */
    private fun takeProfile(): String = synchronized(this) {
        val s = "carga ${profLoadNs / 1_000_000} ms, cuidado ${profCareNs / 1_000_000} ms, pasos ${profStepsNs / 1_000_000} ms, guardado ${profPersistNs / 1_000_000} ms"
        profLoadNs = 0L; profCareNs = 0L; profStepsNs = 0L; profPersistNs = 0L
        s
    }

    /** Pedido explicito del usuario: "si por lo que sea no corre en segundo plano, detecte cuando
     *  empezo [el parón] para saber si va bien y si no va bien calcule automaticamente las
     *  recompensas" - compara [DungeonState.lastTickAt] con la hora actual: si ha pasado de sobra
     *  mas de UN ciclo (el movil lo mato, force-stop, MIUI le quito el permiso de autoarranque...)
     *  resuelve TODOS los ciclos perdidos de golpe (mismo [runTick] de siempre, repetido), en vez
     *  de dejar ese tiempo perdido para siempre. Menos de 2 ciclos de diferencia se considera
     *  normal (holgura de programacion del propio Handler) y no hace nada.
     *
     *  Si una carrera termina a mitad de la puesta al dia (derrota o piso 100), [finishRun] ya se
     *  encarga (resumen + reasignacion si el modo continuo esta activo) exactamente igual que en
     *  tiempo real - con el modo continuo activo, la puesta al dia sigue sola con la carrera
     *  nueva en los ciclos que queden; sin el, para en cuanto deja de haber carrera que avanzar.
     *
     *  Se llama desde [DungeonService.onStartCommand] (el servicio siempre pasa por ahi al
     *  arrancar o reiniciarse, la señal mas fiable de "puede que llevara un rato sin correr") -
     *  en un hilo aparte, nunca en el principal (recuperar cientos de ciclos podria tardar mas de
     *  lo que un Service.onStartCommand puede bloquear sin arriesgar un ANR).
     *
     *  Si el estado es COOLDOWN (ver [finishRun]) en vez de EXPLORING, esto tambien puede
     *  atravesarlo: si el cooldown ya paso de sobra mientras el fondo estaba parado, lo resuelve
     *  ([resolveCooldownIfReady]) y sigue recuperando ciclos con la carrera nueva ya en marcha.
     *
     *  Modo continuo (bucle) - pedido explicito del usuario: "la proteccion anti cuelgues tiene
     *  que tener en cuenta los bucles". Un cooldown que YA estaba en marcha antes del parón usa su
     *  hora de fin real tal cual (es un timestamp absoluto valido). Pero si una carrera TERMINA
     *  dentro de esta misma puesta al dia y el modo continuo la manda a COOLDOWN ([finishRun]),
     *  [DungeonState.startCooldown] fija esa hora de fin respecto al reloj REAL de este instante -
     *  que sigue siendo "ahora mismo" aunque estemos recreando un hueco de horas ya pasado. Sin
     *  corregirlo, ese cooldown exige esperar su duracion entera de tiempo real ADICIONAL, y el
     *  bucle solo consigue recuperar UNA carrera por larga que sea la ausencia (el resto del hueco
     *  ya perdido se tira). Se corrige llevando esa hora de fin hacia atras la misma distancia que
     *  el reloj virtual [virtualNow] ya lleva recuperada respecto al real - equivale a decir "el
     *  cooldown empieza AHORA MISMO en la linea de tiempo que se esta recreando", y las vueltas
     *  siguientes de este mismo bucle lo atraviesan con normalidad si el hueco perdido daba para
     *  mas de una carrera.
     *
     *  Sin tope de ciclos (pedido explicito del usuario: "poder simular segun el tiempo que ha
     *  estado activo... una recompensa que tenga sentido", tras confirmar que un tope de 24h
     *  dejaba sin recuperar cualquier ausencia mas larga) - se recupera el hueco COMPLETO por
     *  grande que sea. El coste de CPU sigue siendo trivial incluso con miles de ciclos (cada uno
     *  es una operacion muy barata), y corre en su propio hilo (ver onStartCommand), nunca en el
     *  principal. */
    fun catchUpIfStalled(context: Context, tickIntervalMs: Long) {
        // Una sola puesta al dia a la vez: dos arranques casi simultaneos del servicio (widget +
        // abrir la app, o volver de la vista en vivo) lanzaban dos hilos que recuperaban el MISMO
        // hueco cada uno - con la carrera en EXPLORING, los ciclos perdidos se contaban doble.
        if (!catchUpRunning.compareAndSet(false, true)) return
        batchWrites = true
        try { catchUpIfStalledInner(context, tickIntervalMs) } finally {
            batchWrites = false
            PetState.flushWrites(context)
            catchUpRunning.set(false)
        }
    }

    private val catchUpRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun catchUpIfStalledInner(context: Context, tickIntervalMs: Long) {
        if (DungeonState.status(context) == DungeonState.STATUS_IDLE) return
        val lastTick = DungeonState.lastTickAt(context)
        if (lastTick <= 0L) return
        val realNow = System.currentTimeMillis()
        // Un cooldown que SIGUE en marcha no es un parón que recuperar (lastTick es el momento en
        // que empezo, y se queda ahi todo el cierre): antes cada vez que se abria la app a mitad de
        // un cooldown salia "N ciclos perdidos" sin que hubiera ninguno.
        if (DungeonState.isInCooldown(context) && realNow < DungeonState.cooldownUntil(context)) return
        val missedTicks = ((realNow - lastTick) / tickIntervalMs).toInt()
        if (missedTicks < 2) return
        // Log persistente (pedido explicito del usuario: "si agregas logs para que quede
        // registrado y ya lo veremos" - sin esto, un parón real solo se notaba de refilón por el
        // resultado final, sin forma de confirmar despues cuantos ciclos/carreras de bucle se
        // recuperaron de verdad). Ver DebugLog.kt - "Log de mazmorra" en Ajustes.
        DebugLog.log(context, "mazmorra: puesta al día - $missedTicks ciclos perdidos, recuperando todos")
        takeProfile()
        val catchUpStart = System.currentTimeMillis()
        var virtualNow = lastTick
        var runsFinishedIntoCooldown = 0
        var cooldownsResolvedIntoNewRun = 0
        for (i in 0 until missedTicks) {
            virtualNow += tickIntervalMs
            when (DungeonState.status(context)) {
                DungeonState.STATUS_IDLE -> {
                    DebugLog.log(context, "mazmorra: puesta al día terminada (carrera sin modo continuo, nada más que recuperar)")
                    return
                }
                DungeonState.STATUS_COOLDOWN -> {
                    if (virtualNow >= DungeonState.cooldownUntil(context)) {
                        resolveCooldownIfReady(context)
                        cooldownsResolvedIntoNewRun++
                    }
                    // Si no: este ciclo virtual aun cae DENTRO del cierre - se salta y se sigue con
                    // el siguiente (antes aqui se hacia `return`, y como lastTick es el INICIO del
                    // cooldown, el primer ciclo siempre caia dentro: la puesta al dia nunca
                    // resolvia un cooldown ya vencido y dependia solo del ciclo normal de 60 s).
                }
                else -> {
                    runTick(context) // EXPLORING
                    if (DungeonState.isInCooldown(context)) {
                        // Acaba de terminar una carrera y cerrar la mazmorra DENTRO de esta puesta
                        // al dia (ver comentario de arriba) - corregir su hora de fin al reloj
                        // virtual antes de que la siguiente vuelta la compare.
                        val corrected = DungeonState.cooldownUntil(context) - (realNow - virtualNow)
                        DungeonState.rewriteCooldownUntil(context, corrected)
                        runsFinishedIntoCooldown++
                    }
                }
            }
        }
        DebugLog.log(context, "mazmorra: puesta al día - $missedTicks ciclos en ${System.currentTimeMillis() - catchUpStart} ms (${takeProfile()})")
        if (runsFinishedIntoCooldown > 0 || cooldownsResolvedIntoNewRun > 0) {
            DebugLog.log(context, "mazmorra: puesta al día completó los $missedTicks ciclos - bucle recuperó $runsFinishedIntoCooldown carrera(s) terminada(s) y $cooldownsResolvedIntoNewRun cooldown(s) resuelto(s) hacia una carrera nueva")
        }
    }

    // ==================== Simulador de balance (debug) ====================
    // Pedido explicito del usuario: "añadir algun tipo de log a las mazmorras para poder tirar
    // varias veces un Pokemon, dejarlo ahi farmeando, y ver estadisticas... la probabilidad de
    // criticos, el nivel que va a obtener de media, los items que aparecen, los enemigos que
    // aparecen... para validar que esta todo bien y ajustar parametros". Corre [attempts] carreras
    // COMPLETAS (piso 1 hasta caer derrotado, o hasta MAX_FLOOR) sobre un RunState AISLADO -
    // gracias a que stepOnce ya no toca DungeonState (ver comentario de RunState), esto puede
    // correr cientos de veces sin arriesgar ni tocar una carrera real que este en curso a la vez.

    /** Resultado de UN intento (una carrera completa hasta morir o terminar la mazmorra). */
    data class BalanceRunResult(
        val floorReached: Int, val xpGained: Float, val levelFinal: Int, val itemsCollected: Int,
        val itemCounts: Map<String, Int>, val enemySpeciesFought: Map<String, Int>,
        val attacksTotal: Int, val attacksMissed: Int, val attacksCritical: Int,
        // [combats]/[combatsWon] ya cuentan TODOS los combates, incluido el que termina la
        // carrera (antes ese se perdia del recuento por completo - ver comentario de
        // Outcome.Defeated). [fatalWasMutualKill] distingue como termino ESTA carrera concreta:
        // true = muerto justo al matar tambien al enemigo (KO mutuo), false = derrota clara (el
        // enemigo sigue vivo).
        val combats: Int, val combatsWon: Int, val fatalWasMutualKill: Boolean,
        // XP TOTAL repartida entre todo el equipo en esta carrera (la suma de las partes de cada miembro);
        // [xpGained] es solo la parte del explorador. Con un solo miembro son iguales.
        val teamXpGained: Float = xpGained,
        // Pasos que duro la carrera (para estimar su duracion en segundo plano).
        val steps: Int = 0
    )

    data class BalanceReport(
        val species: String, val shiny: Boolean, val startLevel: Int, val runs: List<BalanceRunResult>,
        val companions: List<Pair<String, Boolean>> = emptyList()
    )

    /** Tope de pasos por intento (red de seguridad - no deberia hacer falta nunca en la practica,
     *  una carrera real siempre termina en Defeated/Finished, pero evita un bucle infinito si
     *  algun dia un cambio futuro rompe esa garantia). */
    private const val BALANCE_MAX_STEPS_PER_RUN = 8000

    /** [companions]: el equipo que acompaña al explorador (pelean con el, ver resolveEncounter); sus niveles de
     *  partida son los reales de cada individuo. La simulacion NO toca nada real: los niveles salen de un XP de
     *  simulacion (RunState.applyRealXp = false) - antes este simulador repartia XP REAL a los Pokemon al recoger
     *  caramelos simulados. */
    fun runBalanceSimulation(
        context: Context, species: String, shiny: Boolean, startXp: Float, attempts: Int,
        companions: List<Pair<String, Boolean>> = emptyList()
    ): BalanceReport {
        val startLevel = PetState.levelOf(context, startXp, species)
        val results = ArrayList<BalanceRunResult>(attempts)
        val companionXps = companions.map { (n, s) -> PetState.rawStats(context, n, s)?.xp ?: 0f }
        val companionHps = companions.mapIndexed { i, (n, _) ->
            maxHpForLevel(context, n, PetState.levelOf(context, companionXps[i], n)).toFloat()
        }
        repeat(attempts) {
            val startHp = maxHpForLevel(context, species, startLevel).toFloat()
            val map = generateMap(context, 1, species, 1 + companions.size)
            val run = RunState(1, startHp, map, map.startX, map.startY, runStartXp = startXp, companions = companions, companionHps = companionHps)
                .also { it.applyRealXp = false; it.companionSimXp.addAll(companionXps) }
            val itemCounts = HashMap<String, Int>()
            val enemyCounts = HashMap<String, Int>()
            var attacksTotal = 0; var attacksMissed = 0; var attacksCritical = 0
            var combats = 0; var combatsWon = 0
            var fatalWasMutualKill = false
            var steps = 0
            var ended = false
            while (steps < BALANCE_MAX_STEPS_PER_RUN && !ended) {
                steps++
                val step = stepOnce(context, species, shiny, run)
                when (val o = step.outcome) {
                    is Outcome.Pickup -> itemCounts.merge(o.item.kind, 1, Int::plus)
                    is Outcome.Combat -> {
                        combats++
                        if (o.won) combatsWon++
                        enemyCounts.merge(o.enemy.species, 1, Int::plus)
                        for (r in o.rounds) {
                            if (r.move) continue
                            attacksTotal++
                            if (r.missed) attacksMissed++
                            if (r.critical) attacksCritical++
                        }
                    }
                    is Outcome.Defeated -> {
                        // El combate FATAL tambien cuenta (ver comentario de Outcome.Defeated) -
                        // sin esto, precisamente el combate que decide cada carrera quedaba fuera
                        // de "combates"/"combatesGanados", inflando artificialmente el % de
                        // victorias.
                        combats++
                        if (o.enemyAlsoDefeated) combatsWon++
                        fatalWasMutualKill = o.enemyAlsoDefeated
                        for (r in o.rounds) {
                            if (r.move) continue
                            attacksTotal++
                            if (r.missed) attacksMissed++
                            if (r.critical) attacksCritical++
                        }
                        ended = true
                    }
                    Outcome.Finished -> ended = true
                    else -> {}
                }
            }
            val levelFinal = PetState.levelOf(context, startXp + run.runXp, species)
            results.add(BalanceRunResult(
                run.floor, run.runXp, levelFinal, run.itemsCollected, itemCounts, enemyCounts,
                attacksTotal, attacksMissed, attacksCritical, combats, combatsWon, fatalWasMutualKill, run.teamXp, steps
            ))
        }
        return BalanceReport(species, shiny, startLevel, results, companions)
    }

    /** SOLO PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_TEAM_FIGHT_CHECK): lanza [encounters] combates de equipo con
     *  equipos, vidas y enemigos al azar (con tus Pokemon) sobre mapas SINTETICOS (una sala abierta y un pasillo de una
     *  casilla de ancho) y comprueba, golpe a golpe, las reglas del combate: quien ataca esta pegado al enemigo (en el
     *  pasillo el segundo de la fila NO pega); el enemigo solo golpea a alguien pegado a el (y al de delante si lo tiene
     *  pegado) y solo le quita vida a el; los que no llegan solo dan UN paso por asalto, sin pisar a nadie; la vida de nadie
     *  sube; un miembro caido de salida nunca ataca; el delantero cambia solo por la regla del 25 % y solo con alguien pegado
     *  a el (se cambian de casilla); "ganado" es lo mismo que "queda alguien en pie". No toca nada real. */
    fun debugTeamFightCheck(context: Context, encounters: Int = 400): String {
        val pool = PetState.ownedIndividuals(context).map { it.first }.distinct()
        if (pool.size < 4) return "no hay suficientes Pokemon distintos para comprobar"
        val rnd = Random(20261017)
        var problems = 0; var relays = 0; var fought = 0; var teamWon = 0; var teamLost = 0; var capped = 0; var rounds = 0
        var moveEvents = 0; var swapEvents = 0; var corridorFights = 0; var corridorBlocked = 0; var idleStandoffs = 0
        var hitsOnOthers = 0   // golpes del enemigo a alguien que NO iba delante (ahora puede golpear a cualquiera que lo toque)
        val notes = ArrayList<String>()
        fun bad(msg: String) { problems++; if (notes.size < 8) notes.add(msg) }

        // Sala abierta (15 x 13) y pasillo (25 x 3, solo la fila del medio es suelo).
        fun makeMap(w: Int, h: Int, open: (Int, Int) -> Boolean) = DungeonState.DungeonMap(
            w, h, BooleanArray(w * h) { !open(it % w, it / w) }, 1, 1, w - 2, h - 2, mutableListOf(), mutableListOf()
        )
        val roomMap = makeMap(15, 13) { x, y -> x in 1..13 && y in 1..11 }
        val corridorMap = makeMap(25, 3) { x, y -> y == 1 && x in 1..23 }

        repeat(encounters) { n ->
            val corridor = rnd.nextBoolean()
            val map = if (corridor) corridorMap else roomMap
            val size = 1 + rnd.nextInt(3)
            // Casillas: en el pasillo, el enemigo en un sitio y el equipo en fila contigua a un lado (o a los dos lados);
            // en la sala, todos en casillas libres al azar y distintas.
            val enemyTile: Pair<Int, Int>
            val tilesIn = ArrayList<Pair<Int, Int>>()
            if (corridor) {
                val ex = 8 + rnd.nextInt(9)
                enemyTile = ex to 1
                val dir = if (rnd.nextBoolean()) -1 else 1
                for (k in 0 until size) tilesIn.add((ex + dir * (k + 1)) to 1)
            } else {
                val free = ArrayList<Pair<Int, Int>>()
                for (y in 1..11) for (x in 1..13) free.add(x to y)
                free.shuffle(rnd)
                enemyTile = free.removeAt(0)
                for (k in 0 until size) tilesIn.add(free.removeAt(0))
            }
            val team = List(size) {
                val name = pool.random(rnd); val level = 5 + rnd.nextInt(70)
                val max = maxHpForLevel(context, name, level).toFloat()
                // Vidas variadas: llenas, a medias, casi caidas, y algun miembro caido de salida (nunca el primero).
                val frac = when (rnd.nextInt(5)) { 0 -> 1f; 1 -> 0.5f; 2 -> 0.1f; 3 -> 0.02f; else -> if (it == 0) 1f else 0f }
                FighterInfo(name, level, max * frac, max, if (max * frac > 0f) tilesIn[it] else (-1 to -1))
            }
            if (team.none { it.startHp > 0f }) return@repeat   // sin nadie en pie no hay combate
            val enemy = DungeonState.EnemyTile(n, enemyTile.first, enemyTile.second, pool.random(rnd), 5 + rnd.nextInt(80), isBoss = rnd.nextInt(5) == 0)
            // El que iba delante al empezar: a veces el primero, a veces otro (incluso uno caido: debe corregirse solo).
            val startFrontIn = rnd.nextInt(size)
            val res = resolveEncounter(context, team, enemy, 1f, 1f, 1f, 0f, startFrontIn, map)
            fought++; rounds += res.rounds.size
            if (corridor) corridorFights++
            val start = team.map { it.startHp }
            val maxs = team.map { it.maxHp }
            if (res.finalHps.size != team.size) { bad("combate $n: ${res.finalHps.size} vidas finales para ${team.size} miembros"); return@repeat }
            if (res.won != res.finalHps.any { it > 0f }) bad("combate $n: ganado=${res.won} pero vidas finales ${res.finalHps}")
            if (res.won) teamWon++ else teamLost++
            if (res.won && res.rounds.lastOrNull()?.enemyHpAfter?.let { it > 0f } == true) capped++
            if (res.rounds.none { !it.move }) idleStandoffs++
            val isWall = { x: Int, y: Int -> map.isWall(x, y) }
            fun reach(t: Pair<Int, Int>) = TeamCombatGeometry.canStrike(isWall, t.first, t.second, enemyTile.first, enemyTile.second)
            fun touch(a: Pair<Int, Int>, b: Pair<Int, Int>) = abs(a.first - b.first) <= 1 && abs(a.second - b.second) <= 1

            var prev = start
            var prevTiles: List<Pair<Int, Int>> = res.startTiles
            var lastTarget = -1
            // El delantero esperado en cada golpe lo marca la regla pura (ExplorerPartyRules.nextFront): se vigila que el
            // combate la cumpla en el arranque y despues de cada golpe recibido, y que nunca cambie "por su cuenta".
            val tilesStart = team.map { it.startTile }
            var expectedFront = ExplorerPartyRules.nextFront(start, maxs, startFrontIn) { j -> j != startFrontIn && touch(tilesStart[j], tilesStart[startFrontIn]) }
            if (res.startFront != expectedFront) bad("combate $n: empieza delante el ${res.startFront} y la regla dice $expectedFront")
            if (res.startTiles.size != team.size) { bad("combate $n: ${res.startTiles.size} casillas de salida para ${team.size} miembros"); return@repeat }
            var pendingSwap: Pair<Int, Int>? = null   // relevo por poca vida tras el golpe anterior: se refleja en las casillas del suceso siguiente
            for ((k, r) in res.rounds.withIndex()) {
                val now = r.memberHpAfter
                val tl = r.tiles
                if (now.size != team.size || tl.size != team.size) { bad("combate $n golpe $k: listas de tamaño ${now.size}/${tl.size}"); break }
                pendingSwap?.let { (a, b) ->
                    prevTiles = prevTiles.toMutableList().also { t -> val x = t[a]; t[a] = t[b]; t[b] = x }
                    swapEvents++; pendingSwap = null
                }
                for (i in team.indices) if (now[i] > prev[i] + 0.001f) bad("combate $n golpe $k: la vida del miembro $i SUBE (${prev[i]} -> ${now[i]})")
                if (r.front != expectedFront) bad("combate $n golpe $k: va delante el ${r.front} y la regla dice $expectedFront")
                // Casillas: los que siguen en pie no se pisan entre si ni pisan al enemigo, y no estan en una pared.
                val alivePos = team.indices.filter { now[it] > 0f }.map { tl[it] }
                if (alivePos.toSet().size != alivePos.size) bad("combate $n golpe $k: dos miembros en la misma casilla $tl")
                if (alivePos.any { it == enemyTile }) bad("combate $n golpe $k: un miembro pisa al enemigo $tl")
                if (alivePos.any { isWall(it.first, it.second) }) bad("combate $n golpe $k: un miembro en una pared $tl")
                if (r.move) {
                    moveEvents++
                    for (i in team.indices) if (tl[i] != prevTiles[i]) {
                        if (now[i] <= 0f) bad("combate $n golpe $k: se mueve el miembro $i, caido")
                        if (abs(tl[i].first - prevTiles[i].first) > 1 || abs(tl[i].second - prevTiles[i].second) > 1) bad("combate $n golpe $k: el miembro $i salta de ${prevTiles[i]} a ${tl[i]}")
                        if (reach(prevTiles[i])) bad("combate $n golpe $k: se mueve el miembro $i aunque ya llegaba al enemigo")
                    }
                } else if (tl != prevTiles) {
                    bad("combate $n golpe $k: las casillas cambian sin ser un movimiento ni un relevo: $prevTiles -> $tl")
                }
                if (r.playerActs && !r.move) {
                    if (start[r.actor] <= 0f) bad("combate $n golpe $k: ataca el miembro ${r.actor}, caido de salida")
                    if (prev[r.actor] <= 0f) bad("combate $n golpe $k: ataca el miembro ${r.actor} con 0 de vida")
                    if (!reach(tl[r.actor])) bad("combate $n golpe $k: ataca el miembro ${r.actor} desde ${tl[r.actor]} sin llegar al enemigo en $enemyTile")
                    if (now != prev) bad("combate $n golpe $k: un ataque nuestro ha cambiado la vida de alguien")
                } else if (!r.playerActs) {
                    if (prev[r.actor] <= 0f) bad("combate $n golpe $k: el enemigo golpea a un caido (${r.actor})")
                    if (!reach(tl[r.actor])) bad("combate $n golpe $k: el enemigo golpea al miembro ${r.actor} que no esta pegado a el")
                    if (r.actor != r.front) hitsOnOthers++
                    for (i in team.indices) if (i != r.actor && now[i] != prev[i]) bad("combate $n golpe $k: el golpe a ${r.actor} ha tocado la vida del miembro $i")
                    if (lastTarget >= 0 && r.actor != lastTarget) relays++
                    lastTarget = r.actor
                    // Tras el golpe puede cambiar quien va delante (poca vida o caido): solo con alguien pegado a el.
                    if (r.actor == r.front) {
                        val after = ExplorerPartyRules.nextFront(now, maxs, r.front) { j -> j != r.front && touch(tl[j], tl[r.front]) }
                        if (after >= 0) {
                            if (after != r.front && now[r.front] > 0f) pendingSwap = r.front to after
                            expectedFront = after
                        }
                    }
                }
                // Pasillo: el segundo de la fila no llega al enemigo mientras el primero esta delante y nadie ha cambiado de sitio.
                if (corridor && r.playerActs && !r.move && abs(tl[r.actor].first - enemyTile.first) > 1) corridorBlocked++
                prev = now
                prevTiles = tl
            }
            if (prev != res.finalHps) bad("combate $n: la ultima instantanea ${prev} no coincide con las vidas finales ${res.finalHps}")
            if (res.rounds.isNotEmpty() && res.endFront != expectedFront) bad("combate $n: acaba delante el ${res.endFront} y la regla dice $expectedFront")
        }
        // Persecucion (sala abierta): el enemigo va a por el miembro MAS CERCANO, sea quien lidere o uno de los que van detras.
        fun chase(enemyAt: Pair<Int, Int>, team: List<Pair<Int, Int>>): Pair<Int, Int> =
            decideEnemyStep(roomMap, DungeonState.EnemyTile(900, enemyAt.first, enemyAt.second, pool[0], 10), team).first
        var chaseChecks = 0
        fun expectChase(label: String, enemyAt: Pair<Int, Int>, team: List<Pair<Int, Int>>, want: Pair<Int, Int>) {
            chaseChecks++
            val got = chase(enemyAt, team)
            if (got != want) bad("persecucion ($label): el enemigo en $enemyAt con el equipo en $team da el paso $got y deberia ser $want")
        }
        // Compañero a 2 casillas y lider a 8: va a por el compañero.
        expectChase("compañero cerca, lider lejos", 10 to 6, listOf(2 to 6, 8 to 6), 9 to 6)
        // Lider a 2 y compañero a 8: va a por el lider.
        expectChase("lider cerca, compañero lejos", 4 to 6, listOf(2 to 6, 12 to 6), 3 to 6)
        // Los dos a 3 casillas (empate): gana el primero de la lista (el lider).
        expectChase("empate", 6 to 6, listOf(3 to 6, 9 to 6), 5 to 6)
        // Un tercero mas cerca que los demas.
        expectChase("tercero mas cerca", 6 to 3, listOf(2 to 3, 12 to 3, 6 to 5), 6 to 4)
        // Nadie a menos de 4 casillas: no persigue a nadie (deambula; en esta sala sin mas salas, un paso cualquiera a una vecina).
        chaseChecks++
        if (ExplorerPartyRules.chaseTarget(6 to 6, listOf(1 to 6, 13 to 6)) != null) bad("persecucion: el enemigo persigue a alguien a mas de ${ExplorerPartyRules.CHASE_RADIUS} casillas")
        return "$fought combates de equipo comprobados ($rounds sucesos) en sala y pasillo: ${if (problems == 0) "TODO CORRECTO" else "$problems PROBLEMAS"} - persecucion comprobada $chaseChecks veces, " +
            "relevos de objetivo $relays, golpes del enemigo a quien no iba delante $hitsOnOthers, cambios de casilla por poca vida $swapEvents, pasos de acercamiento $moveEvents, " +
            "combates en pasillo $corridorFights (golpes dados desde 2 o mas casillas: $corridorBlocked), sin que nadie llegara a pegar $idleStandoffs, " +
            "el equipo gana $teamWon y pierde $teamLost (tope de asaltos en $capped)" +
            notes.joinToString("") { System.lineSeparator() + "  ! " + it }
    }

    /** SOLO PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_TEAM_FIGHT_CHECK): recorre mapas REALES con un equipo de tres
     *  ([runs] carreras, hasta [stepsPerRun] pasos cada una, sobre un RunState de mentira - no toca la partida) y
     *  comprueba tras cada paso que el explorador y sus compañeros estan en casillas DISTINTAS y transitables, y mide
     *  cuantas veces ocurre lo que antes los apilaba (volver sobre los pasos, combates por un compañero alcanzado) y si
     *  un enemigo vivo queda encima de alguien del equipo. */
    fun debugFollowCheck(context: Context, runs: Int = 24, stepsPerRun: Int = 500): String {
        val pool = PetState.ownedIndividuals(context)
        if (pool.size < 3) return "no hay tres Pokemon para comprobar"
        val rnd = Random(424242)
        var problems = 0; var steps = 0; var swaps = 0; var stayPutSteps = 0; var combats = 0; var behindCombats = 0
        var enemyOnTeam = 0; var runsDone = 0; var realMoves = 0; var realStrikes = 0; var cappedReal = 0
        val notes = ArrayList<String>()
        fun bad(msg: String) { problems++; if (notes.size < 8) notes.add(msg) }
        repeat(runs) { n ->
            val picked = pool.shuffled(rnd).take(3)
            val (leader, lShiny) = picked[0]
            val comps = picked.drop(1)
            val floor = 1 + rnd.nextInt(12)
            val map = generateMap(context, floor, leader, 3)
            val startXp = PetState.rawStats(context, leader, lShiny)?.xp ?: 0f
            val compXps = comps.map { (nm, sh) -> PetState.rawStats(context, nm, sh)?.xp ?: 0f }
            val startLevel = PetState.levelOf(context, startXp, leader)
            val run = RunState(
                floor, maxHpForLevel(context, leader, startLevel).toFloat(), map, map.startX, map.startY, runStartXp = startXp,
                companions = comps, companionHps = comps.mapIndexed { i, (nm, _) -> maxHpForLevel(context, nm, PetState.levelOf(context, compXps[i], nm)).toFloat() }
            ).also { it.applyRealXp = false; it.companionSimXp.addAll(compXps) }
            runsDone++
            ensureFollowerTiles(run)
            var s = 0
            while (s < stepsPerRun) {
                s++; steps++
                val before = run.followerTiles
                val res = stepOnce(context, leader, lShiny, run)
                val o = res.outcome
                if (o is Outcome.Defeated || o is Outcome.Finished) break
                val tiles = run.followerTiles
                val leaderTile = run.px to run.py
                val all = tiles + leaderTile
                val moved = res.fromX != res.toX || res.fromY != res.toY
                if (!moved) stayPutSteps++
                if (moved && (res.toX to res.toY) in before) swaps++
                if (o !is Outcome.Combat && !moved && tiles != before) bad("carrera $n paso $s: el explorador no se movio pero los compañeros si ($before -> $tiles)")
                if (tiles.size != run.aliveFollowers()) bad("carrera $n paso $s: ${tiles.size} casillas para ${run.aliveFollowers()} compañeros en pie")
                if (all.size != all.toSet().size) bad("carrera $n paso $s: dos del equipo en la misma casilla $all (${res.fromX},${res.fromY} -> ${res.toX},${res.toY})")
                if (all.any { run.map.isWall(it.first, it.second) }) bad("carrera $n paso $s: alguien del equipo en una pared $all")
                if (o is Outcome.Combat) {
                    combats++
                    if ((o.enemy.x to o.enemy.y) != (res.toX to res.toY)) behindCombats++
                    // Combate en mapas reales: solo pega quien esta pegado al enemigo, y los demas dan pasos de uno en uno.
                    val ex = o.enemy.x; val ey = o.enemy.y
                    // Un combate que acaba con el enemigo AUN vivo (tope de asaltos) se da por ganado: se cuenta para ver si pasa en partidas reales.
                    if (o.won && (o.rounds.lastOrNull()?.enemyHpAfter ?: 0f) > 0f) cappedReal++
                    var prevTiles = o.startTiles
                    for ((k, r) in o.rounds.withIndex()) {
                        if (r.tiles.size != prevTiles.size) { bad("carrera $n paso $s golpe $k: casillas de tamaño ${r.tiles.size}"); break }
                        val a = r.tiles[r.actor]
                        if (r.move) {
                            realMoves++
                            for (i in r.tiles.indices) if (r.tiles[i] != prevTiles[i] &&
                                (abs(r.tiles[i].first - prevTiles[i].first) > 1 || abs(r.tiles[i].second - prevTiles[i].second) > 1)) bad("carrera $n paso $s golpe $k: el miembro $i salta de ${prevTiles[i]} a ${r.tiles[i]}")
                        } else if (!TeamCombatGeometry.canStrike({ x, y -> run.map.isWall(x, y) }, a.first, a.second, ex, ey)) {
                            bad("carrera $n paso $s golpe $k: ${if (r.playerActs) "ataca" else "es golpeado"} el miembro ${r.actor} desde $a sin estar pegado al enemigo en ($ex,$ey)")
                        } else realStrikes++
                        prevTiles = r.tiles
                    }
                }
                if (run.map.enemies.any { (it.x to it.y) in all }) enemyOnTeam++
            }
        }
        return "$steps pasos en $runsDone carreras con equipo de tres sobre mapas reales: ${if (problems == 0) "TODO CORRECTO" else "$problems PROBLEMAS"} - " +
            "vueltas sobre los propios pasos (cambio de sitio con un compañero) $swaps, pasos sin moverse $stayPutSteps, " +
            "combates $combats (de ellos por alcanzar a un compañero: $behindCombats; golpes dados/recibidos $realStrikes, pasos de acercamiento $realMoves), " +
            "enemigo vivo encima del equipo al acabar un paso: $enemyOnTeam, combates que acaban con el enemigo aun vivo (tope de asaltos): $cappedReal" +
            notes.joinToString("") { System.lineSeparator() + "  ! " + it }
    }

    /** SOLO PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_TEAM_BALANCE): compara en simulacion el explorador SOLO con
     *  el explorador + 1 y + 2 compañeros (su piso medio, XP y combates) para dos equipos de tus Pokemon: los tres de
     *  mayor nivel y tres de nivel medio (los mas cercanos al 30). No toca nada real. */
    fun debugTeamBalance(context: Context, attempts: Int): String {
        val sb = StringBuilder()
        val nl = System.lineSeparator()
        val activeName = PetState.currentPokemon(context)
        val owned = PetState.ownedIndividuals(context)
            .filter { !(it.first == activeName && it.second == PetState.isActiveShiny(context)) }
            .mapNotNull { (n, s) -> PetState.levelForPokemon(context, n, s)?.let { Triple(n, s, it) } }
        if (owned.size < 3) return "no hay tres Pokemon para comparar"
        fun describe(c: List<Triple<String, Boolean, Int>>) = c.joinToString(", ") { "${it.first} Nv${it.third}" }
        val scenarios = listOf(
            "equipo fuerte" to owned.sortedByDescending { it.third }.take(3),
            "equipo medio" to owned.sortedBy { abs(it.third - 30) }.take(3)
        )
        for ((label, members) in scenarios) {
            sb.append("[$label] ${describe(members)} - $attempts carreras por configuracion").append(nl)
            for (size in 1..3) {
                val team = members.take(size)
                val leader = team[0]
                val report = runBalanceSimulation(
                    context, leader.first, leader.second, PetState.rawStats(context, leader.first, leader.second)?.xp ?: 0f, attempts,
                    team.drop(1).map { it.first to it.second }
                )
                val floors = report.runs.map { it.floorReached }
                val combats = report.runs.sumOf { it.combats }; val won = report.runs.sumOf { it.combatsWon }
                val xp = report.runs.map { it.xpGained }
                val teamXp = report.runs.map { it.teamXpGained }
                sb.append(
                    "  ${if (size == 1) "solo" else "+${size - 1} compañero(s)"}: piso medio %.1f (min %d, max %d, %d de %d llegan al piso %d), XP del explorador %.0f/carrera, XP del equipo entero %.0f/carrera, combates ganados %.1f%%"
                        .format(floors.average(), floors.min(), floors.max(), floors.count { it >= DungeonState.MAX_FLOOR }, floors.size, DungeonState.MAX_FLOOR,
                            xp.average(), teamXp.average(), if (combats > 0) won * 100.0 / combats else 0.0)
                ).append(nl)
            }
        }
        return sb.toString()
    }

    /** Informe en texto plano, listo para ensenar en un dialogo o volcar a DebugLog - agrega los
     *  [BalanceReport.runs] (medias, min/max, repartos) en vez de dejar que quien lo llame tenga
     *  que recalcular nada. */
    fun formatBalanceReport(report: BalanceReport): String {
        val runs = report.runs
        if (runs.isEmpty()) return "Sin datos (0 intentos)."
        val n = runs.size
        val sb = StringBuilder()
        sb.append("Simulación de ${PetState.displayLabel(report.species)}")
        if (report.shiny) sb.append(" ✨")
        if (report.companions.isNotEmpty()) sb.append(" + ${report.companions.joinToString(" + ") { PetState.displayLabel(it.first) }}")
        sb.append(" — $n intentos (nivel inicial ${report.startLevel})\n\n")

        val floors = runs.map { it.floorReached }
        sb.append("Piso alcanzado: media %.1f, min %d, max %d\n".format(floors.average(), floors.min(), floors.max()))

        val xpGains = runs.map { it.xpGained }
        val levels = runs.map { it.levelFinal }
        sb.append("XP media ganada: %.0f (nivel final medio %.1f, partiendo de %d)\n"
            .format(xpGains.average(), levels.average(), report.startLevel))

        val itemsPerRun = runs.map { it.itemsCollected }
        sb.append("Objetos recogidos: media %.1f por intento\n".format(itemsPerRun.average()))
        val itemTotals = HashMap<String, Int>()
        for (r in runs) for ((k, v) in r.itemCounts) itemTotals.merge(k, v, Int::plus)
        val totalItems = itemTotals.values.sum().coerceAtLeast(1)
        for ((kind, count) in itemTotals.entries.sortedByDescending { it.value }) {
            sb.append("  - %-10s %6.1f / intento  (%.0f%% de los objetos)\n".format(kind, count.toFloat() / n, count * 100f / totalItems))
        }
        // Calculado en runtime a partir de itemRoleWeights real (piso 1 y piso MAX_FLOOR) en vez
        // de una cadena escrita a mano - la version anterior se desincronizo varias rondas de
        // ajuste seguidas (quedo anunciando pesos de hace tiempo, ver hallazgo del repaso
        // integral) precisamente por ser un texto fijo que nadie se acuerda de actualizar.
        fun weightsLabel(floor: Int) = itemRoleWeights(floor).joinToString("/") { (role, w) -> "$role %.0f".format(w) }
        sb.append("  [pesos ESCALAN con el piso - piso 1: ${weightsLabel(1)}%; " +
            "piso ${DungeonState.MAX_FLOOR}: ${weightsLabel(DungeonState.MAX_FLOOR)}%]\n\n")

        val combatsTotal = runs.sumOf { it.combats }
        val combatsWon = runs.sumOf { it.combatsWon }
        sb.append("Combates: $combatsTotal totales, $combatsWon ganados (%.1f%%)\n"
            .format(if (combatsTotal > 0) combatsWon * 100.0 / combatsTotal else 0.0))
        val mutualKills = runs.count { it.fatalWasMutualKill }
        val cleanLosses = n - mutualKills
        sb.append("Cómo terminan las $n carreras: %d por KO mutuo (ganas el combate justo al morir), %d por derrota clara (el enemigo sigue vivo)\n"
            .format(mutualKills, cleanLosses))

        val attacksTotal = runs.sumOf { it.attacksTotal }
        val attacksMissed = runs.sumOf { it.attacksMissed }
        val attacksCritical = runs.sumOf { it.attacksCritical }
        sb.append("Golpes: $attacksTotal totales — fallos %d (%.1f%%), críticos %d (%.1f%%)\n".format(
            attacksMissed, if (attacksTotal > 0) attacksMissed * 100.0 / attacksTotal else 0.0,
            attacksCritical, if (attacksTotal > 0) attacksCritical * 100.0 / attacksTotal else 0.0
        ))
        sb.append("  [objetivo configurado: críticos 6.25% fijo; fallos ~10-25% según velocidad relativa]\n\n")

        val enemyTotals = HashMap<String, Int>()
        for (r in runs) for ((k, v) in r.enemySpeciesFought) enemyTotals.merge(k, v, Int::plus)
        sb.append("Enemigos más enfrentados:\n")
        for ((name, count) in enemyTotals.entries.sortedByDescending { it.value }.take(10)) {
            sb.append("  %-14s x%d\n".format(name, count))
        }
        return sb.toString()
    }
}
