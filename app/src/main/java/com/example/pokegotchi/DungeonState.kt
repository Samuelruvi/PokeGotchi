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
    // Compañeros del explorador (pedido explicito del usuario: "el explorador extra... que sea un
    // acompañante, no una mazmorra extra... que esten todos en el mismo sitio y que todo lo que ganen
    // se lo repartan"): JSON [{"n":"pikachu","s":false}, ...]. Viajan en la MISMA carrera que el
    // lider (species/shiny), con su mismo ciclo de vida: se fijan al asignar, sobreviven al fin de
    // carrera con el modo continuo igual que species/shiny/autoRepeat, y se pueden cambiar durante
    // el cierre (queueNextExplorer). Solo cuentan mientras la mazmorra NO esta IDLE (ver isInParty).
    private const val KEY_COMPANIONS = "companions"
    // Vida de cada compañero en la carrera en curso (JSON [120.0, 96.5], mismo orden que KEY_COMPANIONS): los
    // compañeros PELEAN junto al explorador (ver DungeonSimulator.resolveEncounter), cada uno con su vida.
    private const val KEY_COMPANION_HPS = "companion_hps"
    // Quien va delante en el equipo (indice) y la casilla de cada compañero (JSON [[x,y],...]) en la carrera en curso.
    private const val KEY_FRONT = "front_member"
    private const val KEY_FOLLOWER_TILES = "follower_tiles"

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
    fun currentHp(context: Context): Float = prefs(context).getFloatSafe(KEY_HP, 0f)
    fun bestFloor(context: Context): Int = prefs(context).getInt(KEY_BEST_FLOOR, 0)
    fun lastTickAt(context: Context): Long = prefs(context).getLong(KEY_LAST_TICK_AT, 0L)

    /** true si hay una carrera/cooldown que DEBERIA estar avanzando en segundo plano y el servicio
     *  NO esta corriendo (ver BackgroundHealth). Se mide con [DungeonService.isRunning], no con el
     *  tiempo desde el ultimo tick: con Doze el Handler del servicio se duerme (en 126 h de log
     *  real, el 54 % del tiempo el ultimo tick tenia mas de 4 min con el proceso VIVO) y eso no
     *  es un servicio parado. Con la mazmorra en IDLE no hay nada que avanzar: nunca. */
    fun isBackgroundStalled(context: Context): Boolean =
        status(context) != STATUS_IDLE && !DungeonService.isRunning

    fun playerPos(context: Context): Pair<Int, Int> =
        prefs(context).getInt(KEY_PLAYER_X, 0) to prefs(context).getInt(KEY_PLAYER_Y, 0)

    fun autoRepeat(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_REPEAT, false)

    // ==================== GRUPO: LIDER + COMPAÑEROS ====================
    // El LIDER es el explorador de siempre (species/shiny): el que pelea y el que pone la vida. Los
    // COMPAÑEROS van en la misma carrera y se reparten el XP (ver ExplorerPartyRules y
    // DungeonSimulator.stepOnce). Cuantos puede llevar depende del nivel de entrenador.

    /** Vida de cada compañero en la carrera en curso (puede ser mas corta que [companions] en una carrera guardada
     *  antes de que los compañeros pelearan: quien falte se da por completo, ver DungeonSimulator.loadRunState). */
    fun companionHps(context: Context): List<Float> {
        val arr = try { JSONArray(prefs(context).getString(KEY_COMPANION_HPS, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optDouble(it, 0.0).toFloat() }
    }

    private fun hpsJson(list: List<Float>): String = JSONArray().apply { list.forEach { put(it.toDouble()) } }.toString()

    /** Quien va delante (indice del grupo: 0 = lider) en la carrera en curso. */
    fun frontMember(context: Context): Int = prefs(context).getInt(KEY_FRONT, 0)

    /** Casilla de cada compañero en la carrera en curso (vacio si aun no se han colocado). */
    fun followerTiles(context: Context): List<Pair<Int, Int>> {
        val arr = try { JSONArray(prefs(context).getString(KEY_FOLLOWER_TILES, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONArray(i) ?: return@mapNotNull null
            if (t.length() < 2) null else t.optInt(0) to t.optInt(1)
        }
    }

    fun saveTeamLayout(context: Context, front: Int, followerTiles: List<Pair<Int, Int>>) {
        val tiles = JSONArray().apply { followerTiles.forEach { (x, y) -> put(JSONArray().put(x).put(y)) } }.toString()
        prefs(context).edit().putInt(KEY_FRONT, front).putString(KEY_FOLLOWER_TILES, tiles).apply()
    }

    /** Compañeros guardados (de la carrera en curso, o la pre-eleccion de la siguiente durante el cierre). */
    fun companions(context: Context): List<Pair<String, Boolean>> {
        val arr = try { JSONArray(prefs(context).getString(KEY_COMPANIONS, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("n").takeIf { it.isNotEmpty() }?.let { it to o.optBoolean("s", false) }
        }
    }

    private fun companionsJson(list: List<Pair<String, Boolean>>): String =
        JSONArray().apply { list.forEach { (n, s) -> put(JSONObject().put("n", n).put("s", s)) } }.toString()

    /** Lider + compañeros, en ese orden (vacio si no hay lider). */
    fun party(context: Context): List<Pair<String, Boolean>> {
        val leader = currentSpecies(context) ?: return emptyList()
        return listOf(leader to currentShiny(context)) + companions(context)
    }

    /** ¿[name]/[shiny] forma parte del grupo de una mazmorra que NO esta en IDLE (lider, compañero, o
     *  pre-elegido durante el cierre)? Mientras tanto no puede ir a la guarderia ni entrar de nuevo. */
    fun isInParty(context: Context, name: String, shiny: Boolean): Boolean =
        status(context) != STATUS_IDLE && party(context).any { (n, s) -> n.equals(name, ignoreCase = true) && s == shiny }

    /** [from]/[shiny] acaba de evolucionar a [to] (ver PetState.evolveTo): el MISMO individuo sigue en
     *  el grupo con su especie nueva - lider o compañero, en la carrera en curso o pre-elegido durante el
     *  cierre. Sin esto la mazmorra seguia explorando con la forma vieja, que ya no existe (ni gana XP) a
     *  la vez que la nueva pasaba a ser el activo del widget (QaConflicts). Si [to] ya iba en el grupo (era
     *  otro miembro, al que el que evoluciona sustituye: solo puede haber uno), se queda UNA sola entrada,
     *  la del lider si lo era, y se descarta su vida guardada. */
    fun renameMember(context: Context, from: String, to: String, shiny: Boolean) {
        val f = from.lowercase(); val t = to.lowercase()
        val leader = currentSpecies(context)?.let { it to currentShiny(context) }
        val comps = companions(context)
        val hps = companionHps(context)
        fun mapName(m: Pair<String, Boolean>) = if (m.first.equals(f, true) && m.second == shiny) t to shiny else m
        val seen = HashSet<Pair<String, Boolean>>()
        val newLeader = leader?.let { mapName(it) }
        if (newLeader != null) seen.add(newLeader)
        val keptComps = ArrayList<Pair<String, Boolean>>()
        val keptHps = ArrayList<Float>()
        comps.forEachIndexed { i, c ->
            val m = mapName(c)
            if (seen.add(m)) { keptComps.add(m); hps.getOrNull(i)?.let { keptHps.add(it) } }
        }
        val e = prefs(context).edit()
        if (newLeader != null) e.putString(KEY_SPECIES, newLeader.first).putBoolean(KEY_SHINY, newLeader.second)
        e.putString(KEY_COMPANIONS, companionsJson(keptComps))
        if (hps.isNotEmpty() || keptHps.isNotEmpty()) e.putString(KEY_COMPANION_HPS, hpsJson(keptHps))
        if (keptComps.size != comps.size) e.putInt(KEY_FRONT, 0).putString(KEY_FOLLOWER_TILES, "[]")
        e.commit()
    }

    /** [name]/[shiny] deja de existir (solo el borrado de Debug): si era el explorador de la carrera en curso se abandona, si era el
     *  pre-elegido durante el cierre se quita de la cola, y si era un compañero sale del grupo con su vida guardada. */
    fun dropMember(context: Context, name: String, shiny: Boolean) {
        if (status(context) == STATUS_IDLE) return
        val leader = currentSpecies(context)
        if (leader != null && leader.equals(name, true) && currentShiny(context) == shiny) {
            if (isExploring(context)) abandon(context) else cancelQueuedExplorer(context)
            return
        }
        val comps = companions(context)
        val i = comps.indexOfFirst { it.first.equals(name, true) && it.second == shiny }
        if (i < 0) return
        val hps = companionHps(context).toMutableList()
        if (i < hps.size) hps.removeAt(i)
        prefs(context).edit()
            .putString(KEY_COMPANIONS, companionsJson(comps.filterIndexed { j, _ -> j != i }))
            .putString(KEY_COMPANION_HPS, hpsJson(hps))
            .putInt(KEY_FRONT, 0).putString(KEY_FOLLOWER_TILES, "[]")
            .commit()
    }

    /** Cuantos compañeros puede llevar el lider segun el nivel de entrenador (maximo guardado). */
    fun maxCompanions(context: Context): Int = ExplorerPartyRules.maxCompanions(PetState.trainerLevelMaxFresh(context))

    /** De los compañeros [wanted], los que SIGUEN pudiendo ir con [leader] (sin repetidos, y como mucho
     *  los que permite el nivel de entrenador). Para reasignar sin pasar por el selector. */
    fun usableCompanions(context: Context, leader: Pair<String, Boolean>, wanted: List<Pair<String, Boolean>>): List<Pair<String, Boolean>> =
        wanted.map { it.first.lowercase() to it.second }.distinct()
            .filter { (n, s) -> companionBlockReason(context, n, s, leader) == null }
            .take(maxCompanions(context))

    /** Por que [name]/[shiny] NO puede ir de compañero con [leader] (null = si puede). Un Pokemon solo
     *  puede estar en un sitio a la vez: no puede ser el lider, ni el compañero activo del widget (ya
     *  gana XP cuidandolo), ni estar en la guarderia. */
    fun companionBlockReason(context: Context, name: String, shiny: Boolean, leader: Pair<String, Boolean>?): String? {
        if (leader != null && leader.first.equals(name, ignoreCase = true) && leader.second == shiny) return "Es el explorador"
        val base = name.lowercase()
        if (!PetState.hasIndividual(context, base, shiny) || PetState.hasEvolvedAway(context, base, shiny)) return "No disponible"
        if (base == PetState.currentPokemon(context) && shiny == PetState.isActiveShiny(context)) return "Es tu compañero activo"
        if (PetState.isInNursery(context, base, shiny)) return "Está en la guardería"
        return null
    }

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
    fun queueNextExplorer(context: Context, name: String, shiny: Boolean, autoRepeat: Boolean, companions: List<Pair<String, Boolean>> = emptyList()) {
        val editor = prefs(context).edit()
            .putString(KEY_SPECIES, name)
            .putBoolean(KEY_SHINY, shiny)
            .putBoolean(KEY_AUTO_REPEAT, autoRepeat)
            .putString(KEY_COMPANIONS, companionsJson(companions))
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
            val xpTotal = p.getFloatSafe(KEY_LOOP_XP_TOTAL, 0f)
            val levelsTotal = p.getInt(KEY_LOOP_LEVELS_TOTAL, 0)
            val itemsTotal = p.getInt(KEY_LOOP_ITEMS_TOTAL, 0)
            val recordFloor = maxOf(bestFloor(context), p.getInt(KEY_LOOP_MAX_FLOOR, 0))
            val summary = "$label — modo continuo: $runs ${if (runs == 1) "carrera" else "carreras"}, no vuelve a entrar por decisión propia " +
                "(récord: $recordFloor) · +${DungeonSimulator.formatXpAmount(xpTotal)} XP total · " +
                "$levelsTotal ${if (levelsTotal == 1) "nivel" else "niveles"} · $itemsTotal objetos recogidos"
            p.edit().putString(KEY_LAST_RUN_SUMMARY, summary).apply()
        }
        prefs(context).edit()
            .putString(KEY_SPECIES, "")
            .putBoolean(KEY_AUTO_REPEAT, false)
            .putString(KEY_COMPANIONS, "[]")
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
        val xpTotal = p.getFloatSafe(KEY_LOOP_XP_TOTAL, 0f) + xpGained
        val levelsTotal = p.getInt(KEY_LOOP_LEVELS_TOTAL, 0) + levelsGained
        val itemsTotal = p.getInt(KEY_LOOP_ITEMS_TOTAL, 0) + itemsCollected
        val maxFloor = maxOf(p.getInt(KEY_LOOP_MAX_FLOOR, 0), floorReached)
        val recordFloor = maxOf(bestFloor(context), maxFloor)
        val summary = "$label — modo continuo: $runs ${if (runs == 1) "carrera" else "carreras"}, $verbClause " +
            "(récord: $recordFloor) · +${DungeonSimulator.formatXpAmount(xpTotal)} XP total · " +
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
    fun runStartXp(context: Context): Float = prefs(context).getFloatSafe(KEY_RUN_START_XP, 0f)
    fun runXpGained(context: Context): Float = prefs(context).getFloatSafe(KEY_RUN_XP_GAINED, 0f)
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

    fun combatBuffAtk(context: Context): Float = prefs(context).getFloatSafe(KEY_BUFF_ATK, 1f)
    fun combatBuffDef(context: Context): Float = prefs(context).getFloatSafe(KEY_BUFF_DEF, 1f)
    fun combatBuffSpd(context: Context): Float = prefs(context).getFloatSafe(KEY_BUFF_SPD, 1f)
    fun combatBuffAcc(context: Context): Float = prefs(context).getFloatSafe(KEY_BUFF_ACC, 0f)

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
    fun assign(
        context: Context, name: String, shiny: Boolean, hp: Int, startXp: Float, autoRepeat: Boolean = false, continuingLoop: Boolean = false,
        companions: List<Pair<String, Boolean>> = emptyList()
    ) {
        // Log de diagnostico - unico sitio donde arranca una carrera (picker manual en
        // DungeonActivity y la reasignacion automatica de resolveCooldownIfReady pasan los dos
        // por aqui), asi que basta un log aqui para cubrir ambos caminos sin duplicar nada.
        val level = PetState.levelOf(context, startXp, name)
        DebugLog.log(context, "mazmorra: entra ${PetState.displayLabel(name)}${if (shiny) " ✨" else ""} nivel $level - modo ${if (autoRepeat) "continuo" else "único"}" +
            if (companions.isEmpty()) "" else " con ${companions.size} compañero(s): ${companions.joinToString { PetState.displayLabel(it.first) }}")
        val editor = prefs(context).edit()
            .putString(KEY_SPECIES, name)
            .putBoolean(KEY_SHINY, shiny)
            .putString(KEY_COMPANIONS, companionsJson(companions))
            // Cada compañero empieza con su vida completa (segun su propio nivel), igual que el explorador.
            .putString(KEY_COMPANION_HPS, hpsJson(companions.map { DungeonSimulator.maxHp(context, it.first, it.second).toFloat() }))
            // Carrera nueva: delante va el lider y los compañeros aun sin colocar (el simulador los coloca al empezar).
            .putInt(KEY_FRONT, 0)
            .putString(KEY_FOLLOWER_TILES, "[]")
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
            // La carrera empieza AHORA: sin esto, KEY_LAST_TICK_AT se quedaba con el valor del
            // cooldown anterior (era el momento en que EMPEZO el cierre), y cualquier puesta al dia
            // lanzada antes del primer ciclo (abrir la app, tocar el widget...) tomaba todo ese
            // cierre por tiempo explorando perdido y simulaba la carrera entera de golpe - bug real
            // visto el 2026-10-15: Charcadet cayo en el piso 6 al instante y empezo otro cooldown.
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
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
            "+${DungeonSimulator.formatXpAmount(runXpGained(context))} XP · $levelsGained ${if (levelsGained == 1) "nivel" else "niveles"} · " +
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

    fun updateProgress(context: Context, floor: Int, hp: Float, companionHps: List<Float>? = null) {
        val clampedFloor = floor.coerceIn(1, MAX_FLOOR)
        // Log de diagnostico (pedido explicito del usuario: "logs solo de la dungeon... los
        // pisos, cuanto tarda en completarlos") - unico sitio donde se persiste un cambio de piso
        // (stepOnce, tanto en vivo como en fondo, siempre pasa por aqui), asi que basta comparar
        // contra el piso ya guardado. DebugLog ya pone su propio timestamp en cada linea, asi que
        // el tiempo por piso se lee restando dos lineas consecutivas sin guardar ningun reloj
        // nuevo aqui.
        if (clampedFloor > currentFloor(context)) DebugLog.log(context, "mazmorra: piso $clampedFloor")
        val editor = prefs(context).edit()
            .putInt(KEY_FLOOR, clampedFloor)
            .putFloat(KEY_HP, hp.coerceAtLeast(0f))
            .putInt(KEY_BEST_FLOOR, maxOf(bestFloor(context), clampedFloor))
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
        if (companionHps != null) editor.putString(KEY_COMPANION_HPS, hpsJson(companionHps.map { it.coerceAtLeast(0f) }))
        editor.apply()
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
            "+${DungeonSimulator.formatXpAmount(runXpGained(context))} XP · $levelsGained ${if (levelsGained == 1) "nivel" else "niveles"} · " +
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
            "+${DungeonSimulator.formatXpAmount(runXpGained(context))} XP · $levelsGained ${if (levelsGained == 1) "nivel" else "niveles"} · " +
            "${runItemsCollected(context)} objetos recogidos"
        prefs(context).edit()
            .putInt(KEY_FLOOR, 1)
            .putFloat(KEY_HP, 0f)
            .putString(KEY_STATUS, STATUS_IDLE)
            .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RUN_SUMMARY, summary)
            .apply()
    }

    // ==================== AUTOCOMPROBACION (adb) ====================

    private fun restorePrefs(p: android.content.SharedPreferences, snap: Map<String, Any?>) {
        val e = p.edit().clear()
        for ((key, v) in snap) when (v) {
            is String -> e.putString(key, v)
            is Int -> e.putInt(key, v)
            is Long -> e.putLong(key, v)
            is Float -> e.putFloat(key, v)
            is Boolean -> e.putBoolean(key, v)
            is Set<*> -> @Suppress("UNCHECKED_CAST") e.putStringSet(key, v as Set<String>)
        }
        e.commit()
    }

    /** SOLO PARA ADB (ver PokeWidgetProvider.ACTION_DEBUG_PARTY_CHECK): prueba con datos reales los
     *  compañeros de la mazmorra - bloqueos, reparto de XP a partes iguales por el MISMO camino que
     *  stepOnce, asignacion con grupo, cierre + bucle conservando/filtrando al grupo, cancelacion - y lo
     *  deja TODO como estaba. Si hay una carrera REAL en curso, la sustituye unos segundos por la de
     *  prueba (con el cerrojo del simulador cogido, para que el servicio no la toque) y al final
     *  restaura el estado entero de la mazmorra tal cual estaba. El nivel de entrenador se fuerza a 25
     *  durante la prueba para tener los dos compañeros. */
    fun debugPartySelfTest(context: Context): String = synchronized(DungeonSimulator) {
        val nl = System.lineSeparator()
        val sb = StringBuilder()
        fun check(label: String, ok: Boolean, detail: String = "") {
            sb.append("$label: ${if (ok) "OK" else "FALLO"}${if (detail.isNotEmpty()) " ($detail)" else ""}").append(nl)
        }
        val p = prefs(context)
        val snapshot = HashMap(p.all)
        val rawLevelBefore = PetState.debugRawTrainerLevelMax(context)
        PetState.debugSetTrainerLevelMax(context, maxOf(rawLevelBefore ?: 1, ExplorerPartyRules.SECOND_COMPANION_LEVEL))
        sb.append("estado inicial: ${status(context)} (lider real: ${currentSpecies(context)}, compañeros reales: ${companions(context).size}); compañeros permitidos durante la prueba: ${maxCompanions(context)}").append(nl)

        val active = PetState.currentPokemon(context) to PetState.isActiveShiny(context)
        val realParty = party(context).map { it.first.lowercase() to it.second }
        val candidates = PetState.ownedIndividuals(context).filter { (n, sh) ->
            (n to sh) != active && (n to sh) !in realParty && !PetState.isInNursery(context, n, sh) &&
                (PetState.levelForPokemon(context, n, sh) ?: 100) < 100
        }
        if (candidates.size < 3) {
            PetState.debugSetTrainerLevelMax(context, rawLevelBefore)
            return@synchronized sb.append("no hay tres Pokemon libres para probar").toString()
        }
        val (a, b, c) = candidates
        val xps = listOf(a, b, c).associateWith { PetState.rawStats(context, it.first, it.second)?.xp ?: 0f }
        sb.append("prueba con lider ${a.first}, compañeros ${b.first} y ${c.first}").append(nl)
        try {
            // --- bloqueos
            check("no puede acompañarse a si mismo", companionBlockReason(context, a.first, a.second, a) == "Es el explorador")
            check("el compañero activo no puede ir", companionBlockReason(context, active.first, active.second, a)?.contains("activo") == true)
            check("un Pokemon libre puede ir", companionBlockReason(context, b.first, b.second, a) == null)
            val usable = usableCompanions(context, a, listOf(b, b, a, c, active))
            check("usableCompanions filtra repetidos, lider y activo, y respeta el tope", usable == listOf(b, c), usable.toString())

            // --- asignar con grupo
            assign(context, a.first, a.second, DungeonSimulator.maxHp(context, a.first, a.second), xps.getValue(a), companions = usable)
            check("asignar con grupo", status(context) == STATUS_EXPLORING && party(context) == listOf(a, b, c), party(context).toString())
            check("isInParty (lider y compañeros)", isInParty(context, a.first, a.second) && isInParty(context, b.first, b.second) && isInParty(context, c.first, c.second))
            val reason = PetState.nurseryBlockReason(context, c.first, c.second)
            check("la guarderia no admite a un compañero de mazmorra", reason == "Está en la mazmorra", reason.toString())

            // --- reparto de XP (el mismo camino que stepOnce)
            val run = DungeonSimulator.loadRunState(context)
            check("el RunState lleva a los compañeros", run != null && run.companions == listOf(b, c))
            if (run != null) {
                fun xp(x: Pair<String, Boolean>) = PetState.rawStats(context, x.first, x.second)?.xp ?: 0f
                val before = listOf(a, b, c).map { xp(it) }
                val runXpBefore = run.runXp
                val share = DungeonSimulator.distributeCandyXp(context, a.first, a.second, run, 30f)
                val after = listOf(a, b, c).map { xp(it) }
                val gains = after.zip(before) { x, y -> x - y }
                check("30 XP entre tres: 10 a cada uno", share == 10f && gains.all { Math.abs(it - 10f) < 0.05f }, "ganancias=$gains")
                check("el XP de la carrera suma solo la parte del lider", Math.abs(run.runXp - runXpBefore - 10f) < 1e-4f)
                // Sin compañeros, todo para el lider.
                val solo = DungeonSimulator.RunState(run.floor, run.hp, run.map, run.px, run.py)
                val leaderBefore = xp(a)
                val soloShare = DungeonSimulator.distributeCandyXp(context, a.first, a.second, solo, 30f)
                check("sin compañeros no se reparte nada", soloShare == 30f && Math.abs(xp(a) - leaderBefore - 30f) < 0.05f)
                // Un compañero caido no cobra: el reparto es solo entre los que siguen en pie (la copia en memoria
                // del RunState no se guarda, asi que la carrera real no se toca).
                run.setHp(2, 0f)
                val b4 = listOf(a, b, c).map { xp(it) }
                val deadShare = DungeonSimulator.distributeCandyXp(context, a.first, a.second, run, 30f)
                val g2 = listOf(a, b, c).map { xp(it) }.zip(b4) { x, y -> x - y }
                check("un compañero caido no cobra XP", deadShare == 15f && Math.abs(g2[0] - 15f) < 0.05f && Math.abs(g2[1] - 15f) < 0.05f && Math.abs(g2[2]) < 0.05f, "ganancias=$g2")
            }

            // --- un ciclo de fondo con grupo
            DungeonSimulator.runTick(context)
            check("runTick con grupo sigue explorando o cierra sin romperse", status(context) != STATUS_IDLE || lastRunSummary(context) != null)

            // --- cierre: el grupo sobrevive y se puede cambiar durante la espera
            if (!isInCooldown(context)) {
                if (!isExploring(context)) assign(context, a.first, a.second, DungeonSimulator.maxHp(context, a.first, a.second), xps.getValue(a), companions = usable)
                startCooldown(context, 5, 600_000L)
            }
            check("cierre conserva al grupo", isInCooldown(context) && companions(context) == listOf(b, c) && isInParty(context, c.first, c.second))
            queueNextExplorer(context, a.first, a.second, false, listOf(b))
            check("queueNextExplorer cambia los compañeros", companions(context) == listOf(b))
            rewriteCooldownUntil(context, System.currentTimeMillis() - 1000L)
            DungeonSimulator.resolveCooldownIfReady(context)
            check("el cierre vencido reasigna con el grupo elegido", isExploring(context) && party(context) == listOf(a, b), party(context).toString())

            // --- cancelar la entrada durante un cierre limpia al grupo
            startCooldown(context, 5, 600_000L)
            cancelQueuedExplorer(context)
            check("cancelar la entrada quita al grupo", companions(context).isEmpty() && currentSpecies(context) == null)
            rewriteCooldownUntil(context, System.currentTimeMillis() - 1000L)
            DungeonSimulator.resolveCooldownIfReady(context)
            check("sin nadie en cola reabre en IDLE", status(context) == STATUS_IDLE && !isInParty(context, b.first, b.second))
        } finally {
            // Restaurar: toda la mazmorra tal cual estaba, XP de los tres, nivel de entrenador guardado.
            restorePrefs(p, snapshot)
            for ((who, xp) in xps) PetState.debugSetIndividualStats(context, who.first, who.second, xp = xp)
            PetState.debugSetTrainerLevelMax(context, rawLevelBefore)
            NotificationHelper.refreshDungeonNotification(context)
        }
        sb.append("restaurado: ${status(context)}, lider ${currentSpecies(context)}, compañeros reales ${companions(context).size}, nivel guardado ${PetState.debugRawTrainerLevelMax(context)} (antes $rawLevelBefore)")
        sb.toString()
    }
}
