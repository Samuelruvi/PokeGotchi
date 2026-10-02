package com.example.pokegotchi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Estado del segundo widget de mazmorra (roguelike autonomo, ver plan) - SharedPreferences
 * PROPIO ("pokegotchi_dungeon_state"), completamente aparte del estado del widget principal
 * (PetState). Un UNICO slot global (pedido explicito del usuario: un Pokemon explorando a la
 * vez, aunque el widget se añada varias veces a la pantalla de inicio) - claves planas, no un
 * mapa por appWidgetId.
 *
 * La vida "EN LA MAZMORRA" (KEY_HP) es un concepto TOTALMENTE APARTE de health/hygiene/happiness
 * de PetState - no decae, no se cura fuera de la mazmorra, y cuidar al compañero activo del
 * widget principal no la afecta para nada (ni al reves). Solo las fórmulas de nivel y XP son
 * compartidas de verdad (mismo individuo real, ver PetState.grantXpToIndividual).
 *
 * El mapa de cada piso (rejilla + escaleras + enemigos/items colocados, ver DungeonMap) se
 * genera UNA vez por piso (DungeonSimulator.generateMap) y se persiste tal cual aqui - pedido
 * explicito del usuario: "cada vez que pase un piso se vea diferente el mapa" (nuevo por piso,
 * no en cada tick) y "el mapa... solo se actualiza por piso puede quedar bien" (no hace falta
 * regenerarlo mas a menudo que eso).
 *
 * Esta clase es SOLO almacenamiento (leer/escribir el slot) - toda la logica de simulacion,
 * generacion de mapas y combate viven en DungeonSimulator.kt, que llama a estas funciones
 * publicas en vez de tocar SharedPreferences directamente.
 */
object DungeonState {
    private const val PREFS = "pokegotchi_dungeon_state"
    private const val KEY_SPECIES = "species"
    private const val KEY_SHINY = "shiny"
    private const val KEY_FLOOR = "floor"
    private const val KEY_HP = "hp"
    private const val KEY_STATUS = "status"
    private const val KEY_BEST_FLOOR = "best_floor"
    private const val KEY_LAST_TICK_AT = "last_tick_at"
    // Resumen agregado de la carrera actual (sustituye al registro linea-a-linea de texto -
    // pedido explicito del usuario: "quita los textos de... pasa al piso... y los mensajes de
    // Sceptile" ahora que el propio mapa ya enseña visualmente que pasa - "puedes poner algun
    // mensaje de cuanta experiencia ha ganado, cuantos niveles ha subido y cuantos objetos ha
    // recogido" en su lugar).
    private const val KEY_RUN_START_LEVEL = "run_start_level"
    private const val KEY_RUN_START_XP = "run_start_xp"
    private const val KEY_RUN_XP_GAINED = "run_xp_gained"
    private const val KEY_RUN_ITEMS_COLLECTED = "run_items_collected"
    private const val KEY_LAST_RUN_SUMMARY = "last_run_summary"
    // Modo continuo - pedido explicito del usuario: "puedas elegir un pokemon y te de a elegir si
    // lo mandas una vez o quieres que lo haga continuamente. cuando acabe una empieza otra". Se
    // elige al asignar (ver assign) y se conserva mientras la carrera siga en marcha - lo lee
    // DungeonSimulator justo despues de returnDefeated/returnFinished para decidir si reasigna
    // la MISMA especie de inmediato en vez de dejar el slot en IDLE.
    private const val KEY_AUTO_REPEAT = "auto_repeat"

    private const val KEY_MAP_FLOOR = "map_floor" // a que piso pertenece el mapa guardado - si no coincide con KEY_FLOOR, esta obsoleto
    private const val KEY_MAP_W = "map_w"
    private const val KEY_MAP_H = "map_h"
    private const val KEY_MAP_WALLS = "map_walls" // un char por celda, fila a fila: 'W'=pared, '.'=suelo
    private const val KEY_MAP_START_X = "map_start_x"
    private const val KEY_MAP_START_Y = "map_start_y"
    private const val KEY_MAP_EXIT_X = "map_exit_x"
    private const val KEY_MAP_EXIT_Y = "map_exit_y"
    private const val KEY_MAP_ENEMIES = "map_enemies" // JSON: [{x,y,species,level}]
    private const val KEY_MAP_ITEMS = "map_items"     // JSON: [{x,y,kind,icon,xp}]
    private const val KEY_MAP_ROOMS = "map_rooms"     // JSON: [{x0,y0,x1,y1}]
    private const val KEY_MAP_VISITED_ROOMS = "map_visited_rooms" // JSON: [indices]
    private const val KEY_MAP_BG = "map_bg" // nombre de drawable (ej. "bg_earthycave") - uno por piso, ver DungeonSimulator.generateMap
    private const val KEY_MAP_FLOOR_TILE = "map_floor_tile" // nombre de drawable del tile de suelo (ej. "dungeontile_floor_tan_swirl") - uno por piso, ver DungeonSimulator.generateMap
    private const val KEY_PLAYER_X = "player_x"
    private const val KEY_PLAYER_Y = "player_y"
    // Buff de combate TEMPORAL (pedido explicito del usuario: "el ataque X y los demas pueden
    // subir stats temporales") - se activa al recoger un objeto de la familia X Ataque/Guardia
    // Especial/etc. y se consume entero (vuelve a neutro) tras el PRIMER combate siguiente, gane
    // o pierda - un solo uso, nunca se acumulan varios a la vez (recoger otro antes de combatir
    // simplemente reemplaza al anterior). Vive aqui (no en RunState) y se lee/escribe directo,
    // igual que runXpGained/runItemsCollected, para que sobreviva sin problema el salto entre
    // tandas de fondo (cada una crea un RunState nuevo).
    private const val KEY_BUFF_ATK = "buff_atk"
    private const val KEY_BUFF_DEF = "buff_def"
    private const val KEY_BUFF_SPD = "buff_spd"
    private const val KEY_BUFF_ACC = "buff_acc"
    // Regeneracion pasiva por caminar (pedido explicito del usuario: "que se cure 1 ps cada 3
    // pasos") - mismo motivo/patron que KEY_BUFF_* de arriba: vive en RunState durante stepOnce
    // (puro) y solo llega aqui via DungeonSimulator.persist().
    private const val KEY_STEPS_SINCE_PASSIVE_HEAL = "steps_since_passive_heal"

    // Cooldown del modo continuo - pedido explicito del usuario: "el reintento de mazmorras debe
    // tener un cooldown que dependera de la cantidad de pisos que haya pasado anteriormente. 2 min
    // por piso por ejemplo". Estado NUEVO, intermedio entre IDLE y EXPLORING.
    //
    // GLOBAL, no por individuo - pedido explicito del usuario, corrigiendo un diseño anterior de
    // esta misma sesion: "el cooldown tu piensas que es como que la mazmorra esta cerrada.
    // Entonces no puede entrar ningun Pokemon. No es que el Pokemon tenga que descansar... todos
    // los Pokemon cuando salen de la mazmorra van a descansar porque la mazmorra esta cerrada, no
    // porque tengan que descansar". Antes existia un registro POR ESPECIE+SHINY que permitia mandar
    // a OTRO Pokemon mientras el primero seguia "descansando" en paralelo - se quita del todo: solo
    // hay UN cierre, atado al slot, y mientras dure NADIE explora, sea quien sea.
    //
    // species/shiny/autoRepeat de la carrera que acaba de terminar se quedan tal cual (no los toca
    // returnDefeated/returnFinished) mientras dura el cierre - son la pre-eleccion de "quien entra
    // en cuanto reabra" (por defecto, el mismo de antes). [queueNextExplorer] permite cambiar esa
    // pre-eleccion SIN abrir la mazmorra antes de tiempo (pedido explicito: "el boton es para
    // sustituir el pokemon del bucle o hacer la siguiente carrera con otro pokemon") -
    // [DungeonSimulator.resolveCooldownIfReady] lee de aqui en cuanto el cierre termina.
    const val STATUS_COOLDOWN = "COOLDOWN"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
    private const val KEY_COOLDOWN_FLOOR = "cooldown_floor" // piso al que llego la carrera que cerro la mazmorra (para el mensaje, no cambia aunque se pre-elija a otro)

    // Acumulados de TODA la sesion en modo continuo (bucle) - pedido explicito del usuario:
    // "cuando mandes un pokemon en bucle a la dungeon el resumen no tiene que poner la ultima
    // mazmorra. tiene que poner el resumen global". A diferencia de KEY_RUN_XP_GAINED/
    // KEY_RUN_ITEMS_COLLECTED (que SI se reinician en CADA carrera, ver assign), estos solo se
    // reinician al iniciar una sesion NUEVA desde el selector (continuingLoop=false) - cada
    // carrera automatica de la MISMA sesion (continuingLoop=true, ver
    // DungeonSimulator.resolveCooldownIfReady) los va SUMANDO en vez de reemplazarlos.
    private const val KEY_LOOP_RUNS = "loop_runs_completed"
    private const val KEY_LOOP_XP_TOTAL = "loop_xp_total"
    private const val KEY_LOOP_LEVELS_TOTAL = "loop_levels_total"
    private const val KEY_LOOP_ITEMS_TOTAL = "loop_items_total"
    private const val KEY_LOOP_MAX_FLOOR = "loop_max_floor"

    // Coleccion PERMANENTE (nunca se reinicia con la carrera, a diferencia de runXpGained/
    // runItemsCollected de arriba) de que objetos decorativos ha encontrado ALGUNA VEZ el
    // jugador, en CUALQUIER carrera - pedido explicito del usuario: "quiero que los objetos
    // decorativos se puedan coleccionar... como si fuera una pokedex de objetos". Un simple
    // Set<String> (nombre de fichero del icono, ver DungeonItemCatalog) es suficiente - no hace
    // falta contar cuantas veces, solo si se ha visto alguna vez. Escrita SOLO desde fuera de
    // stepOnce (DungeonView para la vista en vivo, DungeonSimulator.runTick para el fondo) - NUNCA
    // desde runBalanceSimulation, que corre cientos de carreras de mentira sobre un RunState
    // aislado y no deberia poder ensuciar la coleccion real (mismo motivo por el que runXp/
    // itemsCollected viven en RunState en vez de escribirse aqui directamente, ver comentario de
    // RunState en DungeonSimulator).
    private const val KEY_DISCOVERED_ITEMS = "discovered_decor_items"

    const val STATUS_IDLE = "IDLE"
    const val STATUS_EXPLORING = "EXPLORING"
    const val MAX_FLOOR = 100 // pedido explicito del usuario: "quiero subir los pisos a 100 para redondearlo"

    // [id] estable por enemigo (no cambia mientras viva) - hace falta para que la vista EN VIVO
    // (DungeonView) pueda interpolar el movimiento del enemigo CORRECTO fotograma a fotograma
    // (un data class nuevo con las mismas coordenadas no basta para saber "es el mismo bicho que
    // antes, solo que se ha movido").
    /** [targetRoomIdx] es la sala hacia la que el enemigo esta deambulando ahora mismo cuando no
     *  persigue al jugador (-1 = todavia sin elegir/desconocida) - pedido explicito del usuario:
     *  "haz que los enemigos tengan interes de moverse entre salas, que veo que se quedan siempre
     *  en la misma sala". Sin esto, cada paso elegia un vecino al azar sin memoria, y en salas de
     *  varias casillas de ancho eso rara vez encuentra el unico pasillo de salida por puro azar -
     *  se quedaban dando vueltas dentro de la misma sala indefinidamente. Se actualiza en
     *  DungeonSimulator.decideEnemyStep y se persiste igual que x/y (ver saveMap/map). */
    data class EnemyTile(val id: Int, val x: Int, val y: Int, val species: String, val level: Int, val targetRoomIdx: Int = -1, val isBoss: Boolean = false)
    data class ItemTile(val x: Int, val y: Int, val kind: String, val icon: String?, val xp: Float)
    // Limites de una sala (x1,y1 exclusivos) - se persisten para que la IA de exploracion sepa
    // "que salas existen" y "cuales ya he visitado", ver DungeonSimulator.decidePlayerTarget.
    data class RoomInfo(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        fun contains(x: Int, y: Int): Boolean = x in x0 until x1 && y in y0 until y1
    }

    /** Rejilla de un piso: [walls] es un array plano fila-a-fila (true=pared). Las listas de
     *  enemigos/items son MUTABLES a proposito: DungeonSimulator carga el mapa una vez por tick,
     *  va quitando el enemigo/item de la casilla que se resuelve segun avanza, y lo vuelve a
     *  guardar entero al final del tick (ver saveMap) - mas simple que ir tocando
     *  SharedPreferences celda a celda. [visitedRooms] (indices de [rooms]) tambien es MUTABLE
     *  por el mismo motivo - la IA de exploracion la va marcando sala a sala segun camina (ver
     *  DungeonSimulator.stepOnce). */
    data class DungeonMap(
        val width: Int, val height: Int, val walls: BooleanArray,
        val startX: Int, val startY: Int, val exitX: Int, val exitY: Int,
        val enemies: MutableList<EnemyTile>, val items: MutableList<ItemTile>,
        val rooms: List<RoomInfo> = emptyList(), val visitedRooms: MutableSet<Int> = mutableSetOf(),
        val bgName: String = "bg_meadow", val floorTileName: String = "dungeontile_floor_tan_swirl"
    ) {
        fun isWall(x: Int, y: Int): Boolean =
            x !in 0 until width || y !in 0 until height || walls[y * width + x]
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(context: Context): String = prefs(context).getString(KEY_STATUS, STATUS_IDLE) ?: STATUS_IDLE
    fun isExploring(context: Context): Boolean = status(context) == STATUS_EXPLORING

    fun currentSpecies(context: Context): String? =
        prefs(context).getString(KEY_SPECIES, null)?.takeIf { it.isNotEmpty() }
    fun currentShiny(context: Context): Boolean = prefs(context).getBoolean(KEY_SHINY, false)
    fun currentFloor(context: Context): Int = prefs(context).getInt(KEY_FLOOR, 1)
    fun currentHp(context: Context): Float = prefs(context).getFloat(KEY_HP, 0f)
    fun bestFloor(context: Context): Int = prefs(context).getInt(KEY_BEST_FLOOR, 0)
    fun lastTickAt(context: Context): Long = prefs(context).getLong(KEY_LAST_TICK_AT, 0L)

    // Latido del servicio de la mazmorra (ver BackgroundHealth): lastTickAt NO sirve de latido,
    // porque durante un COOLDOWN no se refresca en cada ciclo (solo al empezarlo) - asi que el
    // servicio escribe su PROPIO latido en cada vuelta. Un widget no ejecuta codigo y no puede
    // calcular "hace cuanto": lo mira el codigo que si corre (tick del widget, toque al Pokemon).
    private const val KEY_HEARTBEAT_AT = "service_heartbeat_at"
    // 4 ciclos del servicio (60s cada uno) sin latir.
    private const val STALL_AFTER_MS = 4 * 60_000L

    fun markHeartbeat(context: Context) {
        prefs(context).edit().putLong(KEY_HEARTBEAT_AT, System.currentTimeMillis()).apply()
    }

    /** Ultimo latido del servicio. Si nunca se ha registrado ninguno (carrera empezada con una
     *  version anterior) se usa lastTickAt como respaldo - una vez el servicio late por primera
     *  vez, manda siempre el latido propio. */
    private fun lastBeatAt(context: Context): Long =
        prefs(context).getLong(KEY_HEARTBEAT_AT, 0L).takeIf { it > 0L } ?: lastTickAt(context)

    /** true si hay una carrera/cooldown que DEBERIA estar avanzando en segundo plano y el servicio
     *  lleva demasiado sin latir. Con la mazmorra en IDLE no hay nada que avanzar: nunca. */
    fun isBackgroundStalled(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (status(context) == STATUS_IDLE) return false
        val beat = lastBeatAt(context)
        return beat > 0L && now - beat > STALL_AFTER_MS
    }

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_BG_STALL). */
    fun debugForceStale(context: Context, minutesAgo: Long) {
        prefs(context).edit()
            .putLong(KEY_HEARTBEAT_AT, System.currentTimeMillis() - minutesAgo * 60_000L)
            .apply()
    }

    fun playerPos(context: Context): Pair<Int, Int> =
        prefs(context).getInt(KEY_PLAYER_X, 0) to prefs(context).getInt(KEY_PLAYER_Y, 0)

    fun autoRepeat(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_REPEAT, false)

    fun isInCooldown(context: Context): Boolean = status(context) == STATUS_COOLDOWN
    fun cooldownUntil(context: Context): Long = prefs(context).getLong(KEY_COOLDOWN_UNTIL, 0L)
    fun cooldownFloor(context: Context): Int = prefs(context).getInt(KEY_COOLDOWN_FLOOR, 1)

    /** Entra en cooldown tras una carrera con modo continuo activo - species/shiny/autoRepeat NO
     *  se tocan (ya los dejo tal cual returnDefeated/returnFinished), solo cambia el status y se
     *  guarda hasta cuando esperar. Llamado desde DungeonSimulator.finishRun. */
    fun startCooldown(context: Context, floorReached: Int, durationMs: Long) {
        prefs(context).edit()
            .putString(KEY_STATUS, STATUS_COOLDOWN)
            .putLong(KEY_COOLDOWN_UNTIL, System.currentTimeMillis() + durationMs)
            .putInt(KEY_COOLDOWN_FLOOR, floorReached)
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis()) // para que catchUpIfStalled mida el hueco desde AQUI, no desde antes de terminar la carrera anterior
            .apply()
    }

    /** Reescribe SOLO la hora de fin del cierre GLOBAL que [DungeonSimulator.finishRun] acaba de
     *  fijar contra el reloj de pared REAL - usado por [DungeonSimulator.catchUpIfStalled] cuando
     *  ese cierre en realidad nace DENTRO de un hueco ya pasado (el movil llevaba horas parado):
     *  sin corregirlo, cada cierre que arranca a mitad de una puesta al dia larga "roba" su
     *  duracion entera de tiempo real ADICIONAL, en vez de contarla dentro del hueco que ya se
     *  habia perdido, y el modo continuo solo consigue recuperar UNA carrera por larga que sea la
     *  ausencia. */
    fun rewriteCooldownUntil(context: Context, until: Long) {
        prefs(context).edit().putLong(KEY_COOLDOWN_UNTIL, until).apply()
    }

    fun loopRunsCompleted(context: Context): Int = prefs(context).getInt(KEY_LOOP_RUNS, 0)

    /** Pre-elige quien entrara CUANDO la mazmorra reabra - pedido explicito del usuario: "el boton
     *  es para sustituir el pokemon del bucle o hacer la siguiente carrera con otro pokemon". A
     *  diferencia de [assign], NO toca status/floor/hp: el cierre sigue su curso igual (nadie
     *  explora hasta que pase KEY_COOLDOWN_UNTIL, sea quien sea el elegido). Si no se llama nunca,
     *  el comportamiento por defecto (species/shiny/autoRepeat de quien acaba de salir, intactos
     *  desde returnDefeated/returnFinished) sigue el bucle solo, exactamente como antes. */
    fun queueNextExplorer(context: Context, name: String, shiny: Boolean, autoRepeat: Boolean) {
        val editor = prefs(context).edit()
            .putString(KEY_SPECIES, name)
            .putBoolean(KEY_SHINY, shiny)
            .putBoolean(KEY_AUTO_REPEAT, autoRepeat)
        resetLoopAggregate(editor) // eleccion nueva explicita - no continua el resumen de quien estaba antes
        editor.apply()
    }

    /** "Abandonar" durante el CIERRE (pedido explicito del usuario, ver comentario de
     *  STATUS_COOLDOWN) - NO reabre la mazmorra antes de tiempo, solo cancela a quien se le habia
     *  pre-elegido para cuando reabra. [DungeonSimulator.resolveCooldownIfReady] ve el hueco y
     *  llama a [reopenIdle] en su lugar. Reescribe el resumen global ya acumulado con un cierre
     *  distinto ("no vuelve a entrar") SIN contar una carrera nueva (no ha pasado ninguna, solo se
     *  cancela la siguiente). */
    fun cancelQueuedExplorer(context: Context) {
        val name = currentSpecies(context)
        if (autoRepeat(context) || loopRunsCompleted(context) > 0) {
            val label = name?.let { PetState.displayLabel(it) } ?: "Tu Pokémon"
            val p = prefs(context)
            val runs = p.getInt(KEY_LOOP_RUNS, 0)
            val xpTotal = p.getFloat(KEY_LOOP_XP_TOTAL, 0f)
            val levelsTotal = p.getInt(KEY_LOOP_LEVELS_TOTAL, 0)
            val itemsTotal = p.getInt(KEY_LOOP_ITEMS_TOTAL, 0)
            val recordFloor = maxOf(bestFloor(context), p.getInt(KEY_LOOP_MAX_FLOOR, 0))
            val summary = "$label — modo continuo: $runs ${if (runs == 1) "carrera" else "carreras"}, no vuelve a entrar por decisión propia " +
                "(récord: $recordFloor) · +${xpTotal.toInt()} XP total · " +
                "$levelsTotal ${if (levelsTotal == 1) "nivel" else "niveles"} · $itemsTotal objetos recogidos"
            p.edit().putString(KEY_LAST_RUN_SUMMARY, summary).apply()
        }
        prefs(context).edit()
            .putString(KEY_SPECIES, "")
            .putBoolean(KEY_AUTO_REPEAT, false)
            .apply()
    }

    /** El cierre termina de pasar sin nadie pre-elegido (ver [cancelQueuedExplorer]) - deja el
     *  slot en IDLE normal, listo para el selector de siempre. Llamado desde
     *  [DungeonSimulator.resolveCooldownIfReady]. */
    fun reopenIdle(context: Context) {
        prefs(context).edit()
            .putString(KEY_STATUS, STATUS_IDLE)
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
            .apply()
    }

    private fun resetLoopAggregate(editor: android.content.SharedPreferences.Editor) {
        editor.putInt(KEY_LOOP_RUNS, 0)
            .putFloat(KEY_LOOP_XP_TOTAL, 0f)
            .putInt(KEY_LOOP_LEVELS_TOTAL, 0)
            .putInt(KEY_LOOP_ITEMS_TOTAL, 0)
            .putInt(KEY_LOOP_MAX_FLOOR, 0)
    }

    /** Suma esta carrera (parte de una sesion en modo continuo) a los totales de la sesion y
     *  escribe el resumen GLOBAL resultante como KEY_LAST_RUN_SUMMARY, sustituyendo al resumen de
     *  una sola carrera que [endFn] (returnDefeated/returnFinished) ya habia escrito - pedido
     *  explicito del usuario, ver comentario de KEY_LOOP_RUNS. Llamado desde
     *  [DungeonSimulator.finishRun] (repeat=true) y desde [abandon] si la sesion que se abandona
     *  era un bucle. */
    fun recordLoopRun(context: Context, name: String?, verbClause: String, floorReached: Int, xpGained: Float, levelsGained: Int, itemsCollected: Int) {
        val label = name?.let { PetState.displayLabel(it) } ?: "Tu Pokémon"
        val p = prefs(context)
        val runs = p.getInt(KEY_LOOP_RUNS, 0) + 1
        val xpTotal = p.getFloat(KEY_LOOP_XP_TOTAL, 0f) + xpGained
        val levelsTotal = p.getInt(KEY_LOOP_LEVELS_TOTAL, 0) + levelsGained
        val itemsTotal = p.getInt(KEY_LOOP_ITEMS_TOTAL, 0) + itemsCollected
        val maxFloor = maxOf(p.getInt(KEY_LOOP_MAX_FLOOR, 0), floorReached)
        val recordFloor = maxOf(bestFloor(context), maxFloor)
        val summary = "$label — modo continuo: $runs ${if (runs == 1) "carrera" else "carreras"}, $verbClause " +
            "(récord: $recordFloor) · +${xpTotal.toInt()} XP total · " +
            "$levelsTotal ${if (levelsTotal == 1) "nivel" else "niveles"} · $itemsTotal objetos recogidos"
        p.edit()
            .putInt(KEY_LOOP_RUNS, runs)
            .putFloat(KEY_LOOP_XP_TOTAL, xpTotal)
            .putInt(KEY_LOOP_LEVELS_TOTAL, levelsTotal)
            .putInt(KEY_LOOP_ITEMS_TOTAL, itemsTotal)
            .putInt(KEY_LOOP_MAX_FLOOR, maxFloor)
            .putString(KEY_LAST_RUN_SUMMARY, summary)
            .apply()
    }

    fun runStartLevel(context: Context): Int = prefs(context).getInt(KEY_RUN_START_LEVEL, 1)
    fun runStartXp(context: Context): Float = prefs(context).getFloat(KEY_RUN_START_XP, 0f)
    fun runXpGained(context: Context): Float = prefs(context).getFloat(KEY_RUN_XP_GAINED, 0f)
    fun runItemsCollected(context: Context): Int = prefs(context).getInt(KEY_RUN_ITEMS_COLLECTED, 0)

    /** Nivel resultante de aplicar el XP acumulado en esta carrera (runXpGained) sobre el XP con
     *  el que empezo (runStartXp) - con DungeonSimulator.APPLY_REAL_CANDY_XP ya en true, esto
     *  coincide con el nivel real en PetState (los caramelos lo aplican en el mismo momento en que
     *  se recogen), pero se sigue calculando aqui en vez de leer PetState directamente para que el
     *  resumen de la carrera EN CURSO no dependa de que ningun otro sitio haya escrito ya el nivel
     *  final - y para que runBalanceSimulation (que nunca toca PetState de verdad) siga pudiendo
     *  usar esta misma funcion sin tocar datos reales. */
    fun simulatedLevel(context: Context): Int {
        val species = currentSpecies(context) ?: return runStartLevel(context)
        return PetState.levelOf(context, runStartXp(context) + runXpGained(context), species)
    }
    /** Resumen de la ultima carrera (solo tiene sentido mientras el slot esta IDLE tras una
     *  derrota) - null si nunca ha habido ninguna todavia. */
    fun lastRunSummary(context: Context): String? = prefs(context).getString(KEY_LAST_RUN_SUMMARY, null)

    /** Escritura ABSOLUTA (no incremental) - pedido explicito del usuario: "añadir algun tipo de
     *  log a las mazmorras para... verificar y ajustar parametros". DungeonSimulator.RunState
     *  ahora lleva su PROPIA copia de runXp/itemsCollected/buffs (antes leia y escribia estas
     *  mismas claves directamente en cada paso) para que stepOnce sea puro - no toque
     *  SharedPreferences para nada - y asi un simulador de balance pueda correr cientos de
     *  intentos aislados sobre un RunState de mentira sin arriesgar la carrera real en curso.
     *  DungeonSimulator.persist() es el UNICO sitio que vuelca el RunState aqui. */
    fun setRunXpGained(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_RUN_XP_GAINED, value).apply()
    }

    fun setRunItemsCollected(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_RUN_ITEMS_COLLECTED, value).apply()
    }

    fun combatBuffAtk(context: Context): Float = prefs(context).getFloat(KEY_BUFF_ATK, 1f)
    fun combatBuffDef(context: Context): Float = prefs(context).getFloat(KEY_BUFF_DEF, 1f)
    fun combatBuffSpd(context: Context): Float = prefs(context).getFloat(KEY_BUFF_SPD, 1f)
    fun combatBuffAcc(context: Context): Float = prefs(context).getFloat(KEY_BUFF_ACC, 0f)

    /** Sustituye el buff activo (nunca se acumula con uno anterior sin consumir). */
    fun setCombatBuff(context: Context, atk: Float = 1f, def: Float = 1f, spd: Float = 1f, acc: Float = 0f) {
        prefs(context).edit()
            .putFloat(KEY_BUFF_ATK, atk).putFloat(KEY_BUFF_DEF, def)
            .putFloat(KEY_BUFF_SPD, spd).putFloat(KEY_BUFF_ACC, acc)
            .apply()
    }

    fun clearCombatBuffs(context: Context) = setCombatBuff(context)

    fun stepsSincePassiveHeal(context: Context): Int = prefs(context).getInt(KEY_STEPS_SINCE_PASSIVE_HEAL, 0)
    fun setStepsSincePassiveHeal(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_STEPS_SINCE_PASSIVE_HEAL, value).apply()
    }

    /** Nombres de fichero (DungeonItemCatalog.DecorItemInfo.fileName) de todo objeto decorativo
     *  encontrado alguna vez, en cualquier carrera - ver comentario de KEY_DISCOVERED_ITEMS. */
    fun discoveredItems(context: Context): Set<String> {
        val arr = try { JSONArray(prefs(context).getString(KEY_DISCOVERED_ITEMS, "[]") ?: "[]") }
            catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optString(it, null) }.toSet()
    }

    /** Marca [fileName] como encontrado para siempre - devuelve true si es la PRIMERA vez (para
     *  poder distinguir "¡nuevo!" de "ya lo tenias" en el texto de recogida). No hace nada si
     *  [fileName] es null (objetos sin icono, no deberia pasar nunca en la practica) o ya estaba. */
    fun recordItemDiscovered(context: Context, fileName: String?): Boolean {
        if (fileName == null) return false
        val current = discoveredItems(context)
        if (fileName in current) return false
        val updated = JSONArray().apply { (current + fileName).forEach { put(it) } }
        prefs(context).edit().putString(KEY_DISCOVERED_ITEMS, updated.toString()).apply()
        return true
    }

    /** El mapa guardado, SOLO si de verdad pertenece al piso actual (KEY_MAP_FLOOR coincide) -
     *  null en cualquier otro caso (recien asignado, o el guardado quedo obsoleto), para que
     *  DungeonSimulator sepa que tiene que generar uno nuevo. */
    fun map(context: Context): DungeonMap? {
        val p = prefs(context)
        if (p.getInt(KEY_MAP_FLOOR, -1) != currentFloor(context)) return null
        val w = p.getInt(KEY_MAP_W, 0); val h = p.getInt(KEY_MAP_H, 0)
        if (w <= 0 || h <= 0) return null
        val wallsStr = p.getString(KEY_MAP_WALLS, null) ?: return null
        if (wallsStr.length != w * h) return null
        val walls = BooleanArray(w * h) { wallsStr[it] == 'W' }
        val enemies = try {
            val arr = JSONArray(p.getString(KEY_MAP_ENEMIES, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EnemyTile(o.optInt("id", i), o.getInt("x"), o.getInt("y"), o.getString("species"), o.getInt("level"), o.optInt("target_room", -1), o.optBoolean("boss", false))
            }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
        val items = try {
            val arr = JSONArray(p.getString(KEY_MAP_ITEMS, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ItemTile(o.getInt("x"), o.getInt("y"), o.getString("kind"), if (o.has("icon")) o.getString("icon") else null, o.optDouble("xp", 0.0).toFloat())
            }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
        val rooms = try {
            val arr = JSONArray(p.getString(KEY_MAP_ROOMS, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RoomInfo(o.getInt("x0"), o.getInt("y0"), o.getInt("x1"), o.getInt("y1"))
            }
        } catch (_: Exception) { emptyList() }
        val visitedRooms = try {
            val arr = JSONArray(p.getString(KEY_MAP_VISITED_ROOMS, "[]") ?: "[]")
            (0 until arr.length()).map { arr.getInt(it) }.toMutableSet()
        } catch (_: Exception) { mutableSetOf() }
        return DungeonMap(
            w, h, walls,
            p.getInt(KEY_MAP_START_X, 0), p.getInt(KEY_MAP_START_Y, 0),
            p.getInt(KEY_MAP_EXIT_X, 0), p.getInt(KEY_MAP_EXIT_Y, 0),
            enemies, items, rooms, visitedRooms, p.getString(KEY_MAP_BG, "bg_meadow") ?: "bg_meadow",
            p.getString(KEY_MAP_FLOOR_TILE, "dungeontile_floor_tan_swirl") ?: "dungeontile_floor_tan_swirl"
        )
    }

    /** Guarda [map] como el mapa del piso [floor], y la posicion del explorador dentro de el -
     *  llamado una vez al generar un mapa nuevo, y otra vez al final de cada tick (para reflejar
     *  los enemigos/items ya resueltos y la posicion final). */
    fun saveMap(context: Context, floor: Int, map: DungeonMap, playerX: Int, playerY: Int) {
        val wallsStr = CharArray(map.width * map.height) { if (map.walls[it]) 'W' else '.' }.concatToString()
        val enemiesArr = JSONArray().apply {
            for (e in map.enemies) put(JSONObject().apply {
                put("id", e.id); put("x", e.x); put("y", e.y); put("species", e.species); put("level", e.level)
                put("target_room", e.targetRoomIdx); put("boss", e.isBoss)
            })
        }
        val itemsArr = JSONArray().apply {
            for (it2 in map.items) put(JSONObject().apply {
                put("x", it2.x); put("y", it2.y); put("kind", it2.kind)
                if (it2.icon != null) put("icon", it2.icon)
                put("xp", it2.xp)
            })
        }
        val roomsArr = JSONArray().apply {
            for (room in map.rooms) put(JSONObject().apply { put("x0", room.x0); put("y0", room.y0); put("x1", room.x1); put("y1", room.y1) })
        }
        val visitedArr = JSONArray().apply { for (idx in map.visitedRooms) put(idx) }
        prefs(context).edit()
            .putInt(KEY_MAP_FLOOR, floor)
            .putInt(KEY_MAP_W, map.width).putInt(KEY_MAP_H, map.height)
            .putString(KEY_MAP_WALLS, wallsStr)
            .putInt(KEY_MAP_START_X, map.startX).putInt(KEY_MAP_START_Y, map.startY)
            .putInt(KEY_MAP_EXIT_X, map.exitX).putInt(KEY_MAP_EXIT_Y, map.exitY)
            .putString(KEY_MAP_ENEMIES, enemiesArr.toString())
            .putString(KEY_MAP_ITEMS, itemsArr.toString())
            .putString(KEY_MAP_ROOMS, roomsArr.toString())
            .putString(KEY_MAP_VISITED_ROOMS, visitedArr.toString())
            .putString(KEY_MAP_BG, map.bgName)
            .putString(KEY_MAP_FLOOR_TILE, map.floorTileName)
            .putInt(KEY_PLAYER_X, playerX).putInt(KEY_PLAYER_Y, playerY)
            .apply()
    }

    /** Asigna [name]/[shiny] como explorador, empezando siempre desde el piso 1 a [hp] completo
     *  (el llamador calcula ese maximo real via DungeonSimulator.maxHp antes de llamar aqui) -
     *  pedido explicito del usuario: tras una derrota el slot se queda inactivo hasta reasignar
     *  a mano, nunca reintenta solo, así que CADA asignacion es siempre un piso 1 nuevo. NO
     *  genera mapa aqui (KEY_MAP_FLOOR se deja sin tocar/obsoleto a proposito) - lo genera
     *  DungeonSimulator en el primer tick, que ya sabe como hacerlo.
     *
     *  [autoRepeat] (pedido explicito del usuario: "que te de a elegir si lo mandas una vez o
     *  quieres que lo haga continuamente. cuando acabe una empieza otra") - elegido al asignar,
     *  se lee luego en DungeonSimulator justo despues de que la carrera termine (derrota o piso
     *  100) para decidir si reasigna la MISMA especie de inmediato en vez de dejar el slot en
     *  IDLE. Abandonar (boton "Abandonar") NUNCA reasigna, sea cual sea este valor - es una
     *  decision explicita del jugador de parar, no un fin de carrera "natural".
     *
     *  [continuingLoop] (pedido explicito del usuario: el resumen en modo continuo tiene que ser
     *  el GLOBAL de toda la sesion, no el de la ultima carrera, ver KEY_LOOP_RUNS) - true SOLO
     *  cuando esta asignacion es la siguiente carrera automatica de un bucle YA en marcha (ver
     *  DungeonSimulator.resolveCooldownIfReady): entonces NO se reinician los acumulados de la
     *  sesion. Una asignacion manual desde el selector (valor por defecto, false) SIEMPRE empieza
     *  una sesion nueva, aunque autoRepeat sea true. */
    fun assign(context: Context, name: String, shiny: Boolean, hp: Int, startXp: Float, autoRepeat: Boolean = false, continuingLoop: Boolean = false) {
        // Log de diagnostico - unico sitio donde arranca una carrera (picker manual en
        // DungeonActivity y la reasignacion automatica de resolveCooldownIfReady pasan los dos
        // por aqui), asi que basta un log aqui para cubrir ambos caminos sin duplicar nada.
        val level = PetState.levelOf(context, startXp, name)
        DebugLog.log(context, "mazmorra: entra ${PetState.displayLabel(name)}${if (shiny) " ✨" else ""} nivel $level - modo ${if (autoRepeat) "continuo" else "único"}")
        val editor = prefs(context).edit()
            .putString(KEY_SPECIES, name)
            .putBoolean(KEY_SHINY, shiny)
            .putInt(KEY_FLOOR, 1)
            .putFloat(KEY_HP, hp.toFloat())
            .putString(KEY_STATUS, STATUS_EXPLORING)
            .putInt(KEY_MAP_FLOOR, -1) // fuerza a generar un mapa nuevo (ver map())
            .putInt(KEY_RUN_START_LEVEL, PetState.levelOf(context, startXp, name))
            .putFloat(KEY_RUN_START_XP, startXp)
            .putFloat(KEY_RUN_XP_GAINED, 0f)
            .putInt(KEY_RUN_ITEMS_COLLECTED, 0)
            .putInt(KEY_STEPS_SINCE_PASSIVE_HEAL, 0)
            .putBoolean(KEY_AUTO_REPEAT, autoRepeat)
        if (!continuingLoop) resetLoopAggregate(editor)
        editor.apply()
        clearCombatBuffs(context)
    }

    /** Abandonar por decision propia (pedido explicito del usuario: "todavia no hay forma de
     *  salir de la mazmorra... no como mecanica, sino como forma de decir oye quiero abandonar")
     *  - NO es una derrota (no hace falta que el HP llegue a 0), pero el reinicio es identico:
     *  el piso vuelve a 1 para la proxima carrera y el slot queda inactivo hasta reasignar a
     *  mano. Lo ya ganado de forma permanente no se toca (vive en PetState, no aqui). */
    fun abandon(context: Context) {
        val floor = currentFloor(context)
        val name = currentSpecies(context)
        val label = name?.let { PetState.displayLabel(it) } ?: "Tu Pokémon"
        val levelsGained = (simulatedLevel(context) - runStartLevel(context)).coerceAtLeast(0)
        // Si esto era un bucle (en marcha, o parado en cooldown esperando la siguiente), el
        // resumen tiene que ser el GLOBAL de la sesion entera, no el de esta ultima carrera a
        // medias - mismo pedido explicito que en finishRun (ver KEY_LOOP_RUNS).
        val wasLooping = autoRepeat(context) || loopRunsCompleted(context) > 0
        if (wasLooping) {
            recordLoopRun(context, name, "se detiene en el piso $floor por decisión propia", floor, runXpGained(context), levelsGained, runItemsCollected(context))
            prefs(context).edit()
                .putInt(KEY_FLOOR, 1)
                .putFloat(KEY_HP, 0f)
                .putString(KEY_STATUS, STATUS_IDLE)
                .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
                .putBoolean(KEY_AUTO_REPEAT, false) // abandonar es SIEMPRE una decision de parar, nunca reasigna
                .apply()
            return
        }
        val summary = "$label vuelve de la mazmorra por decisión propia (piso $floor, mejor: ${maxOf(bestFloor(context), floor)}) · " +
            "+${runXpGained(context).toInt()} XP · $levelsGained ${if (levelsGained == 1) "nivel" else "niveles"} · " +
            "${runItemsCollected(context)} objetos recogidos"
        prefs(context).edit()
            .putInt(KEY_FLOOR, 1)
            .putFloat(KEY_HP, 0f)
            .putString(KEY_STATUS, STATUS_IDLE)
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RUN_SUMMARY, summary)
            .putBoolean(KEY_AUTO_REPEAT, false) // abandonar es SIEMPRE una decision de parar, nunca reasigna
            .apply()
    }

    fun updateProgress(context: Context, floor: Int, hp: Float) {
        val clampedFloor = floor.coerceIn(1, MAX_FLOOR)
        // Log de diagnostico (pedido explicito del usuario: "logs solo de la dungeon... los
        // pisos, cuanto tarda en completarlos") - unico sitio donde se persiste un cambio de piso
        // (stepOnce, tanto en vivo como en fondo, siempre pasa por aqui), asi que basta comparar
        // contra el piso ya guardado. DebugLog ya pone su propio timestamp en cada linea, asi que
        // el tiempo por piso se lee restando dos lineas consecutivas sin guardar ningun reloj
        // nuevo aqui.
        if (clampedFloor > currentFloor(context)) DebugLog.log(context, "mazmorra: piso $clampedFloor")
        prefs(context).edit()
            .putInt(KEY_FLOOR, clampedFloor)
            .putFloat(KEY_HP, hp.coerceAtLeast(0f))
            .putInt(KEY_BEST_FLOOR, maxOf(bestFloor(context), clampedFloor))
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
            .apply()
    }

    /** Derrota: vuelve sano y salvo (nada de lo ya ganado de forma permanente - niveles reales
     *  por caramelos consumidos - se pierde, eso vive en el individuo de PetState, no aqui), el
     *  PISO se reinicia para la proxima carrera, y el slot queda INACTIVO hasta que el usuario
     *  reasigne un Pokemon a mano - pedido explicito del usuario, nada de reintentos automaticos
     *  silenciosos. */
    fun returnDefeated(context: Context) {
        val floor = currentFloor(context)
        val name = currentSpecies(context)
        val label = name?.let { PetState.displayLabel(it) } ?: "Tu Pokémon"
        val levelsGained = (simulatedLevel(context) - runStartLevel(context)).coerceAtLeast(0)
        val summary = "$label cayó en el piso $floor (mejor: ${maxOf(bestFloor(context), floor)}) · " +
            "+${runXpGained(context).toInt()} XP · $levelsGained ${if (levelsGained == 1) "nivel" else "niveles"} · " +
            "${runItemsCollected(context)} objetos recogidos"
        prefs(context).edit()
            .putInt(KEY_FLOOR, 1)
            .putFloat(KEY_HP, 0f)
            .putString(KEY_STATUS, STATUS_IDLE)
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RUN_SUMMARY, summary)
            .apply()
    }

    /** Ha llegado al final de la mazmorra (piso MAX_FLOOR superado) - caso raro, pero a
     *  diferencia de returnDefeated es una VICTORIA, no una caida (mismo resumen de datos, texto
     *  distinto). Mismo reinicio de piso/slot inactivo que una derrota. */
    fun returnFinished(context: Context) {
        val name = currentSpecies(context)
        val label = name?.let { PetState.displayLabel(it) } ?: "Tu Pokémon"
        val levelsGained = (simulatedLevel(context) - runStartLevel(context)).coerceAtLeast(0)
        val summary = "¡$label completó la mazmorra entera! · " +
            "+${runXpGained(context).toInt()} XP · $levelsGained ${if (levelsGained == 1) "nivel" else "niveles"} · " +
            "${runItemsCollected(context)} objetos recogidos"
        prefs(context).edit()
            .putInt(KEY_FLOOR, 1)
            .putFloat(KEY_HP, 0f)
            .putString(KEY_STATUS, STATUS_IDLE)
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RUN_SUMMARY, summary)
            .apply()
    }
}
