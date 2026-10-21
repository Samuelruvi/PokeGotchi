package com.example.pokegotchi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * Vista EN VIVO de la mazmorra (sustituye al widget de pantalla de inicio, retirado - pedido
 * explicito del usuario: "vamos a intentar hacer lo mismo bien en la propia aplicacion... que
 * vaya fluido, que se puedan poner los sprites... se vea en vivo todo lo que esta pasando").
 * Dentro de una Activity normal no hay ninguna de las limitaciones de RemoteViews: bucle de
 * dibujo propio a ~60fps, movimiento interpolado tile a tile, sprites reales (DungeonSpriteRepository)
 * para el explorador Y los enemigos (no puntos de color), camara que sigue al jugador.
 *
 * DOS paneles apilados (pedido explicito del usuario: "arriba en un cuadrado la ventana en vivo...
 * con el zoom, los sprites y todo, y debajo el mapa general con los puntitos de colores... en
 * vivo"): arriba la camara de cerca de siempre (sprites reales); abajo el mapa COMPLETO del piso
 * sin camara (todo escalado para que quepa entero), con puntos de color (verde=explorador,
 * rojo=enemigos, amarillo=items) - mismo estilo que el mapa del widget retirado
 * (DungeonMapRenderer), pero AQUI SI en vivo: las mismas posiciones interpoladas que dibuja el
 * panel de arriba, no una foto fija por piso.
 *
 * Sigue usando exactamente la MISMA logica de simulacion que el avance en segundo plano
 * (DungeonSimulator.stepOnce) - solo cambia que aqui se llama UNO A UNO, con una animacion entre
 * medias, en vez de en bloque y en silencio (ver DungeonSimulator.runTick).
 */
class DungeonView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    fun interface Listener {
        /** Se llama al terminar de resolver cada paso (tras la animacion de movimiento) - la
         *  Activity la usa para refrescar su propio HUD/toasts puntuales. */
        fun onStepResolved(outcome: DungeonSimulator.Outcome)
    }
    var listener: Listener? = null
    /** Derrota o mazmorra completada - la Activity muestra el resumen y vuelve al selector. */
    var onRunEnded: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val tilePx = 56f * density
    // Cuanto del alto disponible (bajo el HUD) se lleva el panel de arriba (camara de cerca) - el
    // resto, el mapa general de abajo. Antes 0.6 - pedido explicito del usuario: "haz un poco mas
    // pequeño [el panel de arriba]... sube [de tamaño] la previsualizacion del mapa".
    private val topPanelFraction = 0.45f

    private var species: String? = null
    private var shiny: Boolean = false
    // Compañeros que van con el explorador en esta carrera (ver DungeonState.companions). Su casilla
    // vive en la SIMULACION (RunState.followerTiles: van en fila detras de quien lidera y un enemigo
    // que los alcanza provoca el combate); aqui solo se guarda donde estaban ANTES del ultimo paso
    // ([followersBefore]) para animar su camino hasta las casillas nuevas (ver followerTiles).
    private var companions: List<Pair<String, Boolean>> = emptyList()
    private var followersBefore: List<Pair<Int, Int>> = emptyList()
    // Donde se DIBUJA cada cosa mientras dura la animacion de un combate (ver combatLayout); null fuera de ella.
    // [members] = casilla (interpolada) de cada miembro del equipo, por indice; [enemies] = casilla de los DEMAS enemigos.
    private class CombatLayout(val members: List<Pair<Float, Float>>, val enemies: Map<Int, Pair<Int, Int>>)
    private var layout: CombatLayout? = null
    private var runState: DungeonSimulator.RunState? = null
    // Mapa a DIBUJAR ahora mismo - normalmente el mismo que runState.map, pero durante la
    // animacion de un cambio de piso, stepOnce YA ha sustituido runState.map por el nuevo antes
    // de que la animacion del ultimo paso del piso anterior haya terminado de reproducirse.
    private var renderMap: DungeonState.DungeonMap? = null

    private var currentStep: DungeonSimulator.StepResult? = null
    private var phase = Phase.IDLE
    private var phaseStartAt = 0L
    private var running = false
    // Duracion real del paso EN CURSO - MOVE_MS normalmente, MOVE_MS_CORRIDOR si tanto la casilla
    // de origen como la de destino son pasillo estrecho (ver isCorridorTile). Se fija una vez al
    // arrancar Phase.MOVING (Phase.IDLE en advance()) y no cambia hasta el siguiente paso.
    private var currentMoveMs = MOVE_MS

    private val enemyDirections = HashMap<Int, DungeonSpriteRepository.Direction>()
    private var playerDirection = DungeonSpriteRepository.Direction.DOWN

    // ---- combate animado golpe a golpe (pedido explicito del usuario: "que no desaparezca
    // inmediatamente, que tengan su barra de vida, que ponga lo que le quitas y lo que te
    // quitan") - se rellenan al pasar de MOVING a RESOLVING con Outcome.Combat (ver advance()) y
    // se usan solo mientras esa fase sigue activa (isCombatActive()). El enemigo se dibuja SIEMPRE
    // a partir de este snapshot mientras dura el combate (no de map.enemies, que ya lo ha quitado
    // de la lista en el mismo instante en que se gana, antes incluso de que la animacion arranque).
    private var combatEnemyId = -1
    private var combatEnemySnapshot: DungeonState.EnemyTile? = null
    private var combatRounds: List<DungeonSimulator.CombatRound> = emptyList()
    private var combatEnemyMaxHp = 1f
    private var combatRoundMs = 260L
    private var combatWon = false
    // Botin de jefe recien caido (bossLootTiles) - bug real reportado por el usuario: "he visto
    // que solto los items antes de matarlo". Causa: renderMap es la MISMA referencia mutable que
    // run.map (items/enemies son listas mutables compartidas, no una copia), asi que en cuanto
    // stepOnce añade el botin a map.items ya se ve en el frame siguiente, aunque la animacion de
    // combate/muerte del jefe siga en marcha - stepOnce resuelve el combate entero de golpe, la
    // animacion es solo un REPLAY a camara lenta de algo que ya paso. Mismo tratamiento que ya
    // tiene combatEnemySnapshot: estos objetos concretos se ocultan del dibujado normal mientras
    // isCombatActive(), y aparecen solos en cuanto la animacion termina (deja de estar activa).
    private var pendingLootItems: List<DungeonState.ItemTile> = emptyList()

    private enum class Phase { IDLE, MOVING, RESOLVING }

    private val handler = Handler(Looper.getMainLooper())
    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            advance()
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    companion object {
        private const val MOVE_MS = 350L
        // Pasillo estrecho (1 casilla de ancho, recto) - pedido explicito del usuario: "si estas
        // por un pasillo de estos de uno de ancho... el personaje vaya mas rapido... como en
        // Mundo Misterioso... no lo hagas demasiado rapido porque puede quedar feo". ~1.6x, un
        // salto notable pero comedido (ver isCorridorTile/currentMoveMs).
        private const val MOVE_MS_CORRIDOR = 220L
        // 0 (antes 80ms) - pedido explicito del usuario: "en vez de que vaya pasito a pasito, que
        // vaya mas fluido". Un paso sin nada especial (sin objeto/escalera/combate) no necesita
        // pausa de lectura como los demas (PAUSE_PICKUP_MS/PAUSE_STAIRS_MS, que SI se quedan igual
        // para poder leer el efecto) - con esto a 0, Phase.RESOLVING pasa a Phase.IDLE en el
        // siguiente fotograma (~16ms) y el siguiente paso arranca su propia interpolacion casi sin
        // corte, dando una caminata continua en vez de avanzar-parar-avanzar.
        private const val PAUSE_EMPTY_MS = 0L
        private const val PAUSE_PICKUP_MS = 700L
        private const val PAUSE_STAIRS_MS = 550L
        // Duracion TOTAL del combate animado repartida entre todos sus golpes (min/max por golpe
        // para que un combate de 2 golpes no se sienta instantaneo ni uno de 20 se eternice) -
        // ver combatRoundMs, calculado por combate en advance(). Subidos (pedido explicito del
        // usuario: "ahora se pegan muy rapido y no esta claro quien pega a quien... me gustaria
        // que quedara mas claro quien esta pegando a quien") tras acelerar el andar por pasillo -
        // el combate no tenia por que notarse mas rapido, pero al ir el resto mas fluido se notaba
        // mas el contraste.
        private const val COMBAT_TOTAL_MS = 4200L
        private const val COMBAT_ROUND_MIN_MS = 150L
        private const val COMBAT_ROUND_MAX_MS = 480L
        private const val DEATH_FADE_MS = 480L
        // Cuando cae UNO DE TUS Pokemon en un combate, su sprite se encoge hasta desaparecer (el enemigo solo se desvanece, ver
        // DEATH_FADE_MS): empieza al acabar el destello rojo del golpe que lo tumba (DEATH_SHRINK_START = fraccion de ese golpe)
        // y dura DEATH_SHRINK_MS. Pedido del usuario (2026-10-17): antes simplemente desaparecia.
        private const val DEATH_SHRINK_MS = 600L
        private const val DEATH_SHRINK_START = 0.6f
        // Pausa "se miran antes de atacar" (pedido explicito del usuario) antes de que arranque el
        // primer golpe - los dos ya estan mirandose (direcciones puestas en Phase.IDLE) pero
        // todavia no pierden vida.
        private const val COMBAT_FACEOFF_MS = 320L
        // Tras los golpes de un combate FATAL, cuanto se queda el equipo caido en pantalla antes de cerrar la carrera.
        private const val FATAL_HOLD_MS = 900L
        // Golpe hacia delante (va y vuelve) al atacar, como fraccion de tilePx - pedido explicito
        // del usuario tras separar a cada combatiente en su propia casilla ("quiero que cada uno
        // este en un cuadrado... se peguen cada uno desde su cuadrado"): ya no hace falta ningun
        // paso atras (las casillas ya estan separadas de verdad), solo este empujon al golpear.
        private const val COMBAT_LUNGE_FRACTION = 0.30f
        // Tope defensivo de vueltas encadenadas dentro de UNA sola llamada a advance() - nunca
        // deberia hacer falta en la practica (cada vuelta o bien avanza de fase o bien sale con
        // `return`), red de seguridad para no congelar el hilo principal si algun caso futuro
        // rompiera esa garantia.
        private const val ADVANCE_MAX_CHAINED_PHASES = 25
    }

    // Paints de reserva SOLO por si el tile bitmap no se pudiera decodificar (ver
    // ensureDungeonTileBitmaps) - en uso normal las texturas reales los tapan por completo.
    private val wallPaint = Paint().apply { color = 0xFF232028.toInt() }
    private val floorPaint = Paint().apply { color = 0xFF716C7A.toInt(); alpha = 130 }
    private val startPaint = Paint().apply { color = 0xFF3E7CB1.toInt() }
    private val exitPaint = Paint().apply { color = 0xFFC9A227.toInt() }
    private val stairStepPaint = Paint().apply { isAntiAlias = true }
    private val stairPitPaint = Paint().apply { isAntiAlias = true }
    private val stairRimPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; color = 0x99CFC9DA.toInt(); strokeWidth = 2f * density
    }
    private val stairGlowPaint = Paint().apply { color = 0xFFF3E9C8.toInt() }
    private val hudBg = Paint().apply { color = 0xAA000000.toInt() }
    private val hudBarBack = Paint().apply { color = 0x55FFFFFF.toInt() }
    private val hudBarFront = Paint().apply { color = 0xFF3CD34C.toInt() }
    private val hudText = Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 14f * density; isAntiAlias = true }
    private val spriteRect = RectF()
    // Destello rojo al recibir un golpe ("como en Minecraft", pedido explicito del usuario) - un
    // PorterDuffColorFilter en modo SRC_ATOP sustituye todo pixel opaco del sprite por rojo solido
    // conservando su alpha (la silueta exacta del sprite), en vez de teñir/mezclar el color
    // original - efecto de "flash" limpio, no un tinte translucido.
    private val hitFlashPaint = Paint().apply {
        colorFilter = android.graphics.PorterDuffColorFilter(0xFFFF3B30.toInt(), android.graphics.PorterDuff.Mode.SRC_ATOP)
    }

    // ---- barra de vida del enemigo en combate + texto flotante de daño/objetos ----
    private val enemyHpBack = Paint().apply { color = 0x99000000.toInt() }
    private val enemyHpFront = Paint().apply { color = 0xFFE0393E.toInt() }
    private val deathFadePaint = Paint().apply { isAntiAlias = true }
    private val damageTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); textSize = 13f * density; isAntiAlias = true; isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 1f, 0xFF000000.toInt())
    }
    // Golpe critico - respuesta a la pregunta explicita del usuario: "no se si hay probabilidad
    // de fallar o criticos" (fallar ya existia, critico no) - texto mas grande y en dorado para
    // que se note claramente que ese golpe fue especial.
    private val criticalTextPaint = Paint().apply {
        color = 0xFFFFD84D.toInt(); textSize = 15.5f * density; isAntiAlias = true; isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 1f, 0xFF000000.toInt())
    }
    private val pickupTextPaint = Paint().apply {
        color = 0xFF7CF29C.toInt(); textSize = 14f * density; isAntiAlias = true; isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 1f, 0xFF000000.toInt())
    }

    // ---- panel de abajo (mapa general) ----
    private val miniBgPaint = Paint().apply { color = 0xFF0E0D12.toInt() }
    private val miniWallPaint = Paint().apply { color = 0xFF1B191F.toInt() }
    private val miniFloorPaint = Paint().apply { color = 0xFF4A4650.toInt() }
    private val miniPlayerPaint = Paint().apply { color = 0xFF3CD34C.toInt() }
    private val miniEnemyPaint = Paint().apply { color = 0xFFE0393E.toInt() }
    private val miniItemPaint = Paint().apply { color = 0xFFF4D93E.toInt() }
    private val dividerPaint = Paint().apply { color = 0xFF000000.toInt() }

    // Fondo real de tipo (mismo catalogo que el widget principal, ver DungeonSimulator.
    // DUNGEON_BACKGROUNDS) detras del suelo del panel de arriba - pedido explicito del usuario
    // ("un fondo como los que tenemos en el widget"). Escalado UNA vez al tamaño entero del mapa
    // (se dibuja en coordenadas de mundo, se mueve con la camara como el resto) y solo se
    // recalcula si cambia de piso (bgName distinto).
    private var bgBitmap: Bitmap? = null
    private var bgBitmapKey: String? = null

    private fun ensureBgBitmap(map: DungeonState.DungeonMap) {
        if (bgBitmapKey == map.bgName && bgBitmap != null) return
        bgBitmapKey = map.bgName
        val resId = resources.getIdentifier(map.bgName, "drawable", context.packageName)
        if (resId == 0) { bgBitmap = null; return }
        val raw = try { BitmapFactory.decodeResource(resources, resId) } catch (_: Exception) { null }
        if (raw == null) { bgBitmap = null; return }
        val mapPxW = (map.width * tilePx).toInt().coerceAtLeast(1)
        val mapPxH = (map.height * tilePx).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, mapPxW, mapPxH, true)
        if (scaled !== raw) raw.recycle()
        bgBitmap = scaled
    }

    // Tiles REALES de suelo/pared (recortados de un tileset estilo Mundo Misterioso) - pedido
    // explicito del usuario: "buscar un suelo y paredes para formar toda la mazmorra... puedes
    // sacar varios suelos para que varie cada piso". La pared NO varia por piso (una sola textura,
    // el usuario solo pidio variedad en el suelo); el suelo si, vía [DungeonState.DungeonMap.
    // floorTileName] (elegido al azar por DungeonSimulator.generateMap, igual que bgName). Cada
    // bitmap se decodifica UNA vez (48x48 origen) y se dibuja escalado a tilePx por celda - al
    // ser opacos, tapan por completo el fondo de escena (bgBitmap) que habia antes; se deja ese
    // codigo tal cual por si se quiere recuperar el aspecto anterior.
    private val tileArt = DungeonTileArt(context.assets)
    private var wallTileBitmap: Bitmap? = null
    private var floorTileBitmap: Bitmap? = null
    private var floorTileBitmapKey: String? = null
    private val tileDestRect = RectF()

    private fun ensureDungeonTileBitmaps(map: DungeonState.DungeonMap) {
        if (wallTileBitmap == null) {
            val resId = resources.getIdentifier("dungeontile_wall_cave", "drawable", context.packageName)
            wallTileBitmap = if (resId != 0) try { BitmapFactory.decodeResource(resources, resId) } catch (_: Exception) { null } else null
        }
        if (floorTileBitmapKey != map.floorTileName) {
            floorTileBitmapKey = map.floorTileName
            val resId = resources.getIdentifier(map.floorTileName, "drawable", context.packageName)
            floorTileBitmap = if (resId != 0) try { BitmapFactory.decodeResource(resources, resId) } catch (_: Exception) { null } else null
        }
    }

    /** Escalera de bajada (donde apareces en el piso): un agujero/hueco real en el suelo - pedido
     *  explicito del usuario, con DOS partes que la primera version se dejaba (quedaba "solo un
     *  agujero" liso, sin peldaños): (1) un hueco con degradado radial (mas claro en el borde,
     *  negro puro en el fondo) simulando profundidad, Y (2) varios peldaños simulados DENTRO del
     *  hueco (lineas horizontales que se acortan y se desvanecen segun bajan) - "un agujero
     *  simulando algunos peldaños y un fondo negro, como que hay algo mas abajo". */
    private fun drawDescendingHole(canvas: Canvas, l: Float, t: Float, size: Float) {
        if (!terrainTextured) canvas.drawRect(l, t, l + size, t + size, floorPaint)
        val pitL = l + size * 0.12f; val pitR = l + size * 0.88f
        val pitT = t + size * 0.28f; val pitB = t + size * 0.92f
        val rad = size * 0.08f
        stairPitPaint.shader = RadialGradient(
            (pitL + pitR) / 2f, pitT + (pitB - pitT) * 0.2f, size * 0.68f,
            intArrayOf(0xFF4E4856.toInt(), 0xFF1C1922.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(pitL, pitT, pitR, pitB, rad, rad, stairPitPaint)
        stairPitPaint.shader = null

        // Peldaños simulados bajando hacia la oscuridad - se acortan y se desvanecen segun se
        // acercan al fondo negro, dando la sensacion de que la escalera sigue mas alla de lo que
        // se ve.
        val steps = 3
        for (i in 0 until steps) {
            val fy = pitT + (pitB - pitT) * (0.20f + i * 0.19f)
            val shrink = i * size * 0.06f
            stairRimPaint.alpha = (175 - i * 55).coerceAtLeast(35)
            canvas.drawLine(pitL + shrink, fy, pitR - shrink, fy, stairRimPaint)
        }
        stairRimPaint.alpha = 255
        // Labio superior con luz (borde de la abertura, donde el suelo real se rompe).
        canvas.drawArc(pitL, pitT - size * 0.05f, pitR, pitT + size * 0.16f, 195f, 150f, false, stairRimPaint)
    }

    /** Escalera de subida (donde subes al siguiente piso): DE LADO, en 3D, como en Mundo
     *  Misterioso - pedido explicito del usuario: "que sean como en 3D y de lado, para que asi se
     *  vea la subida... no es como ver la escalera de frente, sino de lado". Cada peldaño es un
     *  bloque con cara superior clara (donde pisas) y cara frontal oscura (el canto, da volumen),
     *  ascendiendo en diagonal hacia el fondo del tile; por encima del ultimo peldaño se ve un
     *  hueco mas claro - "que tenga un fondo, que se vea lo que hay del otro lado". */
    private fun drawAscendingSteps(canvas: Canvas, l: Float, t: Float, size: Float) {
        if (!terrainTextured) canvas.drawRect(l, t, l + size, t + size, floorPaint)
        val steps = 3
        val stepH = size * 0.22f
        val riserH = size * 0.095f
        val baseW = size * 0.76f
        val shrink = size * 0.065f
        val creep = size * 0.045f
        val startX = l + size * 0.10f

        // Apertura mas alla del ultimo peldaño ("se vea lo que hay del otro lado").
        val openingBottom = t + size - steps * stepH
        canvas.drawRect(l + size * 0.05f, t + size * 0.04f, l + size * 0.95f, openingBottom, stairGlowPaint)

        for (i in 0 until steps) {
            val bottom = t + size - i * stepH
            val top = bottom - stepH
            val riserTop = top + (stepH - riserH)
            val w = baseW - i * shrink
            val x0 = startX + i * creep
            // Canto frontal (riser) bien oscuro y la cara superior (tread) bien clara - mas
            // contraste que la primera version (demasiado plana, "muy fea" segun el usuario) para
            // que el volumen 3D se lea claramente aunque el tile sea pequeño.
            val riserShade = (0x1C + i * 0x10).coerceAtMost(0x50)
            stairStepPaint.style = Paint.Style.FILL
            stairStepPaint.color = (0xFF shl 24) or (riserShade shl 16) or (riserShade shl 8) or (riserShade + 0x08)
            canvas.drawRect(x0, riserTop, x0 + w, bottom, stairStepPaint)
            val treadShade = (0x8C + i * 0x1E).coerceAtMost(0xE8)
            stairStepPaint.color = (0xFF shl 24) or (treadShade shl 16) or (treadShade shl 8) or (treadShade + 0x18).coerceAtMost(0xFF)
            canvas.drawRect(x0, top, x0 + w, riserTop, stairStepPaint)
            // Contorno fino para separar cada peldaño del siguiente - sin esto los peldaños se
            // fundian unos con otros a tamaño de tile pequeño.
            stairStepPaint.style = Paint.Style.STROKE
            stairStepPaint.strokeWidth = 1f * density
            stairStepPaint.color = 0x66000000.toInt()
            canvas.drawRect(x0, top, x0 + w, bottom, stairStepPaint)
            stairStepPaint.style = Paint.Style.FILL
        }
    }

    /** Empieza (o retoma) la vista en vivo para el explorador actualmente asignado - no hace nada
     *  si de verdad no hay ninguno (DungeonState.isExploring == false), la Activity ya comprueba
     *  esto antes de mostrar la vista. */
    fun start(species: String, shiny: Boolean) {
        this.species = species
        this.shiny = shiny
        val existing = DungeonSimulator.loadRunState(context) ?: return
        runState = existing
        renderMap = existing.map
        companions = existing.companions
        DungeonSimulator.ensureFollowerTiles(existing)
        followersBefore = existing.followerTiles
        phase = Phase.IDLE
        running = true
        handler.removeCallbacks(frameTick)
        handler.post(frameTick)
    }

    /** SOLO PARA PRUEBAS (volcado de DungeonActivity): ¿tiene ya una carrera cargada que dibujar? */
    fun debugHasRun(): Boolean = runState != null && renderMap != null && species != null

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_DUMP_DUNGEON_VIEW): dibuja la vista
     *  ENTERA (panel de arriba + minimapa) de un piso [floor] GENERADO AL AZAR con el explorador
     *  [sp] en su casilla de salida, en un bitmap de [w]x[h], fuera de pantalla. Carrera sintetica:
     *  no lee ni escribe la partida real (ni arranca el bucle de avance). */
    fun debugSnapshot(sp: String, shinyFlag: Boolean, floor: Int, w: Int, h: Int, companionsPreview: List<Pair<String, Boolean>> = emptyList()): Bitmap {
        val map = DungeonSimulator.generateMap(context, floor, sp)
        val synthetic = DungeonSimulator.RunState(floor, DungeonSimulator.maxHp(context, sp, shinyFlag).toFloat(), map, map.startX, map.startY)
        species = sp; shiny = shinyFlag
        runState = synthetic; renderMap = map
        // Compañeros de muestra: casillas de suelo en fila detras de la salida (para ver como se dibujan).
        companions = companionsPreview
        // Con equipo, cada compañero lleva su vida: aqui, completa (la vista previa los quiere en pie).
        synthetic.companions = companionsPreview
        companionsPreview.forEach { (n, s) -> synthetic.companionHps.add(DungeonSimulator.maxHp(context, n, s).toFloat()) }
        DungeonSimulator.ensureFollowerTiles(synthetic)
        followersBefore = synthetic.followerTiles
        currentStep = null; phase = Phase.IDLE; layout = null
        measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        return bmp
    }

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_DUMP_DUNGEON_VIEW, extra "combat"): busca, recorriendo mapas
     *  generados al azar con un equipo [members] (el primero lidera) sobre un RunState de mentira, un combate que cumpla
     *  [wantBehind] (el enemigo alcanzo a un compañero, no al que lidera) y/o [wantSwap] (cambia quien va delante por
     *  poca vida), y dibuja la vista (a [w]x[h], fuera de pantalla) justo en el golpe [pick] de ese combate. Devuelve el
     *  bitmap y un texto con lo que se ve, o null si no lo encuentra. No toca la partida real. */
    fun debugCombatSnapshot(
        members: List<Pair<String, Boolean>>, w: Int, h: Int, wantBehind: Boolean, wantSwap: Boolean,
        leaderHpFraction: Float, wantMove: Boolean = false, wantBlocked: Boolean = false, wantDefeat: Boolean = false,
        wantDeath: Boolean = false, shrinkProgress: Float = 0.5f,
        pick: (List<DungeonSimulator.CombatRound>) -> Int
    ): Pair<Bitmap, String>? {
        val (sp, shinyFlag) = members[0]
        val comps = members.drop(1)
        fun xpOf(n: String, s: Boolean) = PetState.rawStats(context, n, s)?.xp?.takeIf { it > 0f } ?: 2500f
        for (attempt in 0 until 60) {
            val floor = if (wantDefeat || wantDeath) 25 + attempt % 10 else 1 + attempt % 8   // para el combate fatal, pisos con enemigos que de verdad hacen daño
            val map = DungeonSimulator.generateMap(context, floor, sp, 1 + comps.size)
            val startXp = xpOf(sp, shinyFlag)
            val run = DungeonSimulator.RunState(
                floor, DungeonSimulator.maxHp(context, sp, shinyFlag) * leaderHpFraction, map, map.startX, map.startY,
                runStartXp = startXp, companions = comps,
                companionHps = comps.map { (n, s) -> DungeonSimulator.maxHp(context, n, s) * (if (wantDefeat || wantDeath) leaderHpFraction else 1f) }
            ).also { it.applyRealXp = false; it.companionSimXp.addAll(comps.map { (n, s) -> xpOf(n, s) }) }
            DungeonSimulator.ensureFollowerTiles(run)
            var s = 0
            while (s++ < 600) {
                val before = run.followerTiles.toList()
                val res = DungeonSimulator.stepOnce(context, sp, shinyFlag, run)
                val raw = res.outcome
                // wantDefeat: se busca el combate FATAL (todo el equipo cae); si no, un combate normal.
                if (raw is DungeonSimulator.Outcome.Finished || (raw is DungeonSimulator.Outcome.Defeated && (!wantDefeat || raw.enemy == null))) break
                if (wantDefeat != (raw is DungeonSimulator.Outcome.Defeated)) continue
                val o = combatData(raw) ?: continue
                // wantDeath: un combate que el equipo gana pero en el que cae alguno de los suyos (no el fatal).
                if (wantDeath && (raw !is DungeonSimulator.Outcome.Combat || o.startHps.indices.none { m -> o.startHps[m] > 0f && o.rounds.any { it.memberHpAfter[m] <= 0f } })) continue
                val behind = (o.enemy.x to o.enemy.y) != (res.toX to res.toY)
                val swap = o.rounds.map { it.front }.distinct().size > 1 || o.startFront != 0
                // Posiciones: alguien que se acerca (hay pasos de acercamiento) o alguien que NO llega y no puede acercarse
                // (en un pasillo, el segundo de la fila: no hay pasos y al empezar alguien en pie no esta pegado al enemigo).
                val hasMove = o.rounds.any { it.move }
                val notReaching = o.startTiles.indices.any { i ->
                    o.startHps.getOrElse(i) { 0f } > 0f &&
                        !TeamCombatGeometry.canStrike({ x, y -> run.map.isWall(x, y) }, o.startTiles[i].first, o.startTiles[i].second, o.enemy.x, o.enemy.y)
                }
                val blockedOne = notReaching && !hasMove
                if ((wantBehind && !behind) || (wantSwap && !swap) || (wantMove && !hasMove) || (wantBlocked && !blockedOne) ||
                    (!wantDefeat && (o.rounds.size < 3 || o.rounds.none { !it.playerActs && !it.missed })) || o.rounds.isEmpty()) continue
                // Montar la vista como si este combate se estuviera animando ahora mismo, en el golpe elegido.
                species = sp; shiny = shinyFlag
                runState = run; renderMap = run.map; companions = comps
                followersBefore = before
                currentStep = res
                combatEnemyId = o.enemy.id; combatEnemySnapshot = o.enemy; combatRounds = o.rounds
                combatEnemyMaxHp = o.enemyMaxHp.coerceAtLeast(1f); combatWon = o.won
                combatRoundMs = 450L
                val idx = pick(o.rounds).coerceIn(0, o.rounds.size - 1)
                phase = Phase.RESOLVING
                // En la vista de la caida de uno de los tuyos, el instante es el de [shrinkProgress] (0..1) de su encogimiento.
                val shrinkShiftMs = if (wantDeath) ((DEATH_SHRINK_START - 0.4f) * combatRoundMs + shrinkProgress * DEATH_SHRINK_MS).toLong() else 0L
                phaseStartAt = System.currentTimeMillis() - COMBAT_FACEOFF_MS - idx * combatRoundMs - combatRoundMs * 2 / 5 - shrinkShiftMs
                playerDirection = DungeonSpriteRepository.Direction.DOWN
                layout = null
                measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                layout(0, 0, w, h)
                if (wantDeath) {
                    // El primer dibujado (texturas, sprites) tarda mas que el propio encogimiento: se hace uno de calentamiento
                    // y el reloj del combate se fija justo antes del dibujado bueno.
                    val warm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    draw(Canvas(warm)); warm.recycle()
                    phaseStartAt = System.currentTimeMillis() - COMBAT_FACEOFF_MS - idx * combatRoundMs - combatRoundMs * 2 / 5 - shrinkShiftMs
                }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                draw(Canvas(bmp))
                val r = o.rounds[idx]
                // Control del "rojo al curarse": en este mismo instante, con el combate en curso el sprite que recibe el golpe
                // se pone rojo; si el paso fuera una RECOGIDA (con los golpes del combate anterior aun guardados) no debe.
                phaseStartAt = System.currentTimeMillis() - COMBAT_FACEOFF_MS - idx * combatRoundMs - combatRoundMs * 2 / 5 - shrinkShiftMs   // el dibujo tarda: se rehace el instante
                val redInCombat = isReceivingHit(playerSide = true, memberIdx = r.actor)
                currentStep = DungeonSimulator.StepResult(
                    res.fromX, res.fromY, res.toX, res.toY, emptyList(),
                    DungeonSimulator.Outcome.Pickup(DungeonState.ItemTile(run.px, run.py, "berry", null, 0.2f), "+10 HP")
                )
                val redInPickup = isReceivingHit(playerSide = true, memberIdx = r.actor)
                currentStep = res
                val redCheck = if (!r.playerActs && !r.missed) "rojo en combate=$redInCombat, rojo en una recogida con golpes viejos guardados=$redInPickup"
                    else "(este golpe no es un impacto al equipo: sin comprobacion del rojo)"
                val text = "$redCheck; piso $floor, golpe ${idx + 1}/${o.rounds.size} (${if (r.playerActs) "ataca el miembro ${r.actor}" else "el enemigo golpea al miembro ${r.actor}"}), " +
                    "delante: ${r.front} (empezo ${o.startFront}, acaba ${o.rounds.last().front}), vidas ${r.memberHpAfter.map { it.toInt() }}, " +
                    "enemigo en ${o.enemy.x},${o.enemy.y}, casillas de salida ${o.startTiles}, " +
                    (if (hasMove) "hay compañeros que se acercan, " else "") + (if (blockedOne) "alguien en pie no llega al enemigo ni puede acercarse, " else "") +
                    if (behind) "el enemigo alcanzo a un compañero" else "el enemigo piso al que lidera"
                // Dejar la vista en reposo (sin combate) para no arrastrar este estado de mentira.
                currentStep = null; phase = Phase.IDLE; layout = null; runState = null; renderMap = null
                return bmp to text
            }
        }
        return null
    }

    /** Para el bucle y persiste el estado actual (misma forma que el avance de fondo) - llamado
     *  al salir de la pantalla, para que DungeonTickReceiver retome exactamente donde se quedo. */
    fun stop() {
        running = false
        handler.removeCallbacks(frameTick)
        // Si se sale de la pantalla en mitad de la animacion del combate fatal, la carrera tiene que cerrarse igualmente
        // (si no, quedaria "explorando" con todo el equipo a 0 de vida).
        if (runState != null && currentStep?.outcome is DungeonSimulator.Outcome.Defeated) finishDefeat(notifyActivity = false)
        runState?.let { DungeonSimulator.persist(context, it) }
        // Ya guardado: soltarlo. Con varias plazas, un stop() posterior con OTRA plaza seleccionada
        // (o con la carrera ya terminada) volcaria este RunState viejo encima de un estado ajeno.
        runState = null
        renderMap = null
    }

    /** Un tile cuenta como "pasillo estrecho" si, de sus 4 vecinos ortogonales, solo los DOS
     *  opuestos en UN eje son transitables (izquierda+derecha sin arriba/abajo, o arriba/abajo
     *  sin izquierda/derecha) - el patron geometrico exacto de un pasillo recto de 1 casilla de
     *  ancho, distinto de una sala (se abre en mas de una direccion, o en una esquina/cruce) o de
     *  un extremo sin salida. Pedido explicito del usuario: "un pasillo de estos de uno de
     *  ancho... para no estar yendo a la misma velocidad que siempre cuando es una linea recta,
     *  como hacen en Mundo Misterioso". */
    private fun isCorridorTile(map: DungeonState.DungeonMap, x: Int, y: Int): Boolean {
        val left = !map.isWall(x - 1, y)
        val right = !map.isWall(x + 1, y)
        val up = !map.isWall(x, y - 1)
        val down = !map.isWall(x, y + 1)
        return (left && right && !up && !down) || (up && down && !left && !right)
    }

    /** Pedido explicito del usuario: "que vaya caminando a la misma velocidad hasta que se
     *  encuentra el enemigo o cualquier item" - un paso sin nada especial encadena DIRECTAMENTE
     *  con el siguiente DENTRO de esta misma llamada (bucle) en vez de esperar a la vuelta
     *  siguiente del frameTick: antes, incluso con PAUSE_EMPTY_MS=0, pasar por Phase.RESOLVING
     *  para volver a Phase.IDLE costaba fotogramas aparte en los que el sprite se dibujaba
     *  parado (moveProgress() devuelve 1f fuera de Phase.MOVING) - un "parón" breve pero real en
     *  cada casilla. Con el bucle, esa transicion ocurre entera en la misma llamada y el siguiente
     *  Phase.MOVING arranca con el mismo `now`, sin ningun fotograma parado entre medias. Pickup/
     *  Stairs/Combat siguen pausando exactamente igual que antes (sus `return` cortan el bucle). */
    private fun advance() {
        val r = runState ?: return
        val sp = species ?: return
        var guard = 0
        while (guard++ < ADVANCE_MAX_CHAINED_PHASES) {
            val now = System.currentTimeMillis()
            when (phase) {
                Phase.IDLE -> {
                    val mapBeforeStep = r.map
                    val itemsBefore = mapBeforeStep.items.toList()
                    DungeonSimulator.ensureFollowerTiles(r)
                    followersBefore = r.followerTiles.toList()   // donde estaban los compañeros antes de este paso
                    val step = DungeonSimulator.stepOnce(context, sp, shiny, r)
                    currentStep = step
                    renderMap = mapBeforeStep
                    updateDirections(step)
                    val outcome = step.outcome
                    // Cualquier objeto que no estuviera antes de este paso (botin de jefe) se
                    // queda fuera del dibujado normal hasta que la animacion de combate termine -
                    // ver comentario de pendingLootItems.
                    pendingLootItems = r.map.items.filterNot { it in itemsBefore }
                    DungeonSimulator.recordDecorPickup(context, outcome)
                    // Un paso sin combate no deja datos del combate anterior (los golpes viejos no deben poder pintarse).
                    val cd = combatData(outcome)
                    if (cd == null) { combatRounds = emptyList(); combatEnemySnapshot = null; combatEnemyId = -1 }
                    else {
                        // Snapshot del enemigo AQUI MISMO (no al terminar la animacion de
                        // acercamiento) - stepOnce ya ha resuelto el combate entero y, si se gana,
                        // ya ha quitado al enemigo de map.enemies en este mismo instante;
                        // capturarlo mas tarde dejaba un hueco visible sin el enemigo antes de que
                        // arrancase su propia animacion de muerte - bug real reportado por el
                        // usuario ("veo que primero el pokemon desaparece y luego salta la
                        // animacion de morir").
                        combatEnemyId = cd.enemy.id
                        combatEnemySnapshot = cd.enemy
                        combatRounds = cd.rounds
                        combatEnemyMaxHp = cd.enemyMaxHp.coerceAtLeast(1f)
                        combatWon = cd.won
                        combatRoundMs = (COMBAT_TOTAL_MS / cd.rounds.size.coerceAtLeast(1))
                            .coerceIn(COMBAT_ROUND_MIN_MS, COMBAT_ROUND_MAX_MS)
                        // Se miran de frente antes de golpearse - pedido explicito del usuario:
                        // "que los pokemon se miren antes de atacar". El jugador ya queda mirando
                        // hacia el enemigo (updateDirections, misma direccion en la que se movio
                        // para llegar hasta el), el enemigo se gira a mirarlo a EL.
                        val dx = step.toX - step.fromX; val dy = step.toY - step.fromY
                        if (dx != 0 || dy != 0) enemyDirections[cd.enemy.id] = directionFor(-dx, -dy)
                    }
                    DungeonSimulator.persist(context, r)
                    if (outcome is DungeonSimulator.Outcome.Finished) {
                        running = false
                        DungeonSimulator.finishRun(context, sp, { "completó la mazmorra entera" }, DungeonState::returnFinished)
                        runState = null   // carrera cerrada: un stop() posterior no debe volver a volcarla
                        onRunEnded?.invoke()
                        return
                    }
                    // Pasillo estrecho solo si ORIGEN Y DESTINO de este paso concreto son los dos
                    // pasillo (ver isCorridorTile) - si el paso entra o sale de una sala, se queda
                    // a velocidad normal, evitando un cambio de ritmo brusco justo en el umbral.
                    currentMoveMs = if (isCorridorTile(mapBeforeStep, step.fromX, step.fromY) &&
                        isCorridorTile(mapBeforeStep, step.toX, step.toY)) MOVE_MS_CORRIDOR else MOVE_MS
                    phase = Phase.MOVING
                    phaseStartAt = now
                    // Sigue en el bucle: Phase.MOVING comprueba el tiempo real ya mismo (0ms
                    // transcurridos) y sale con `return` a esperar al fotograma siguiente.
                }
                Phase.MOVING -> {
                    if (now - phaseStartAt >= currentMoveMs) {
                        val outcome = currentStep?.outcome
                        if (outcome is DungeonSimulator.Outcome.Stairs) renderMap = r.map
                        phase = Phase.RESOLVING
                        phaseStartAt = now
                        if (outcome != null) listener?.onStepResolved(outcome)
                        // Con los datos del combate se ENSEÑA entero (Phase.RESOLVING) y la carrera se cierra al acabar; sin ellos, al instante.
                        if (outcome is DungeonSimulator.Outcome.Defeated && combatData(outcome) == null) {
                            finishDefeat(notifyActivity = true)
                            return
                        }
                        // Sigue en el bucle: Phase.RESOLVING decide si hace falta pausa de verdad
                        // o si puede volver a Phase.IDLE ya mismo (paso vacio, PAUSE_EMPTY_MS=0).
                    } else {
                        return
                    }
                }
                Phase.RESOLVING -> {
                    val outcome = currentStep?.outcome
                    if (combatData(outcome) != null) {
                        val roundsMs = combatRounds.size * combatRoundMs
                        // En el combate fatal se deja un momento mas con el equipo ya caido antes de cerrar.
                        val after = if (combatWon) DEATH_FADE_MS else if (outcome is DungeonSimulator.Outcome.Defeated) FATAL_HOLD_MS else 0L
                        // Si uno de los tuyos cae en los ultimos golpes, se deja acabar su encogimiento antes de pasar al siguiente paso.
                        val total = COMBAT_FACEOFF_MS + roundsMs + maxOf(after, ownDeathTailMs(combatData(outcome)))
                        if (now - phaseStartAt >= total) {
                            if (outcome is DungeonSimulator.Outcome.Defeated) { finishDefeat(notifyActivity = true); return }
                            phase = Phase.IDLE
                        } else return
                    } else {
                        val pause = when (outcome) {
                            is DungeonSimulator.Outcome.Pickup -> PAUSE_PICKUP_MS
                            is DungeonSimulator.Outcome.Stairs -> PAUSE_STAIRS_MS
                            else -> PAUSE_EMPTY_MS
                        }
                        if (now - phaseStartAt >= pause) phase = Phase.IDLE else return
                    }
                }
            }
        }
    }

    private fun updateDirections(step: DungeonSimulator.StepResult) {
        val dx = step.toX - step.fromX; val dy = step.toY - step.fromY
        if (dx != 0 || dy != 0) playerDirection = directionFor(dx, dy)
        for (m in step.enemyMoves) {
            val edx = m.toX - m.fromX; val edy = m.toY - m.fromY
            if (edx != 0 || edy != 0) enemyDirections[m.id] = directionFor(edx, edy)
        }
    }

    private fun dirToward(fromX: Int, fromY: Int, toX: Int, toY: Int): DungeonSpriteRepository.Direction? =
        if (toX == fromX && toY == fromY) null else directionFor(toX - fromX, toY - fromY)

    /** Donde se DIBUJA cada miembro del equipo mientras dura la animacion de un combate. El simulador ya ha resuelto el
     *  combate con POSICIONES (solo pega quien esta pegado al enemigo; el que no llega se acerca): aqui solo se ensenan esas
     *  casillas, las de salida antes de los golpes y las de cada suceso despues, deslizandose de una a otra durante el
     *  suceso (un paso de acercamiento o un cambio de sitio por poca vida). Los demas enemigos, en la suya (el simulador
     *  nunca pone a nadie encima de ellos). */
    private fun combatLayout(r: DungeonSimulator.RunState, step: DungeonSimulator.StepResult): CombatLayout? {
        val o = combatData(step.outcome) ?: return null
        val e = combatEnemySnapshot ?: return null
        if (o.startTiles.isEmpty()) return null
        val idx = currentCombatRoundIdx()
        val members: List<Pair<Float, Float>> =
            if (idx < 0) o.startTiles.map { it.first.toFloat() to it.second.toFloat() }
            else {
                val cur = combatRounds[idx].tiles.ifEmpty { o.startTiles }
                val prev = if (idx > 0) combatRounds[idx - 1].tiles.ifEmpty { o.startTiles } else o.startTiles
                // Llega a su casilla un poco antes de acabar el suceso, para que se vea quieto un instante.
                val p = (combatRoundProgress(idx) / 0.8f).coerceIn(0f, 1f)
                cur.indices.map { i ->
                    val a = prev.getOrElse(i) { cur[i] }; val b = cur[i]
                    (a.first + (b.first - a.first) * p) to (a.second + (b.second - a.second) * p)
                }
            }
        val enemies = HashMap<Int, Pair<Int, Int>>()
        for (other in r.map.enemies) if (other.id != e.id) enemies[other.id] = other.x to other.y
        return CombatLayout(members, enemies)
    }

    /** Casilla (interpolada, en casillas) y direccion de cada compañero en ESTE fotograma, en el orden de los
     *  huecos de la fila (RunState.followerTiles). Mientras el explorador anda cada uno va de su casilla de antes del
     *  paso a la de despues (la "serpiente": el primero ocupa la que dejo quien lidera); con el paso resuelto estan
     *  en las de la simulacion; durante un combate, donde diga [combatLayout]. */
    private fun followerTiles(r: DungeonSimulator.RunState, step: DungeonSimulator.StepResult?, t: Float, count: Int = companions.size): List<Triple<Float, Float, DungeonSpriteRepository.Direction>> {
        if (count <= 0) return emptyList()
        layout?.let { lay ->
            // En combate cada miembro tiene SU casilla: los que no lideran (en el orden de teamView) donde estan.
            val e = combatEnemySnapshot
            return teamView(r).followers.take(count).map { m ->
                val (x, y) = lay.members.getOrElse(m) { r.px.toFloat() to r.py.toFloat() }
                Triple(x, y, (if (e != null) dirToward(Math.round(x), Math.round(y), e.x, e.y) else null) ?: playerDirection)
            }
        }
        val after = r.followerTiles
        val before = followersBefore.takeIf { it.size == after.size } ?: after
        val moved = step != null && (step.fromX != step.toX || step.fromY != step.toY)
        val ordinary = if (moved && step != null) ExplorerPartyRules.followTheLeaderTiles(before, step.fromX to step.fromY, step.toX to step.toY) else before
        val moving = step != null && phase == Phase.MOVING
        return (0 until count).map { j ->
            val a = before.getOrNull(j) ?: (r.px to r.py)
            val b = ordinary.getOrNull(j) ?: a
            val fin = after.getOrNull(j) ?: b
            val dir = dirToward(a.first, a.second, b.first, b.second) ?: playerDirection
            if (!moving) return@map Triple(fin.first.toFloat(), fin.second.toFloat(), dir)
            var x = a.first + (b.first - a.first) * t
            var y = a.second + (b.second - a.second) * t
            // El que lidera vuelve sobre sus pasos y se cruza con este compañero (se cambian de casilla): este se aparta
            // de lado a mitad de camino para que no pasen uno por encima del otro.
            if (step != null && b == (step.fromX to step.fromY) && a == (step.toX to step.toY)) {
                val bulge = kotlin.math.sin(Math.PI * t).toFloat() * 0.55f
                x += -(b.second - a.second) * bulge
                y += (b.first - a.first) * bulge
            }
            Triple(x, y, dir)
        }
    }

    /** El equipo tal como se ve AHORA: [party] (explorador + compañeros), la vida mostrada de cada uno ([hps], golpe a
     *  golpe en un combate) y [vis] = indices de quienes se dibujan: primero el que va DELANTE (puede ser un compañero:
     *  cede el puesto al bajar del 25 % de su vida) y despues los demas que siguen en pie. */
    private class TeamView(val party: List<Pair<String, Boolean>>, val hps: List<Float>, val vis: List<Int>) {
        val lead: Int get() = vis.first()
        val followers: List<Int> get() = vis.drop(1)
    }

    private fun teamView(r: DungeonSimulator.RunState): TeamView {
        val party = species?.let { listOf(it to shiny) + companions } ?: emptyList()
        val n = party.size
        val outcome = combatData(currentStep?.outcome)
        val idx = if (outcome != null) currentCombatRoundIdx() else -1
        val raw = if (outcome == null) r.teamHps() else if (idx >= 0) combatRounds[idx].memberHpAfter else outcome.startHps
        val hps = List(n) { raw.getOrElse(it) { 0f } }
        // Delantero: el de la simulacion; en un combate, el que iba delante EN ESE GOLPE (cambia entre golpes). El que
        // recibe un golpe que lo tumba aun se ve durante ese golpe (es el delantero de ese golpe).
        val front = (if (outcome == null) r.frontIndex() else if (idx >= 0) combatRounds[idx].front else outcome.startFront)
            .coerceIn(0, (n - 1).coerceAtLeast(0))
        val vis = ArrayList<Int>().apply {
            add(front)
            // Los que siguen en pie y los que acaban de caer en este combate y aun se estan encogiendo.
            hps.indices.filter { it != front && (hps[it] > 0f || (outcome != null && idx >= deathRoundOf(it, outcome) && deathRoundOf(it, outcome) >= 0 && memberDeathScale(it) > 0f)) }
                .forEach { add(it) }
        }
        return TeamView(party, hps, vis)
    }

    /** Golpe (indice en la lista del combate) en el que cae el miembro [m] de TU equipo, o -1 si no cae en este combate (o ya
     *  empezo caido). */
    private fun deathRoundOf(m: Int, o: CombatData): Int {
        if (o.startHps.getOrElse(m) { 0f } <= 0f) return -1
        return o.rounds.indexOfFirst { it.memberHpAfter.getOrElse(m) { 1f } <= 0f }
    }

    /** Tamaño con el que se dibuja al miembro [m] de tu equipo: 1 = normal, 0 = ya desaparecido. Cuando cae en el combate que se
     *  anima, tras el destello rojo del golpe que lo tumba se va encogiendo hasta desaparecer (pedido del usuario: "tiene que
     *  hacerse cada vez mas pequeño, desaparecer, y ya pasaria al compañero"). */
    private fun memberDeathScale(m: Int): Float {
        if (phase != Phase.RESOLVING) return 1f
        val o = combatData(currentStep?.outcome) ?: return 1f
        val d = deathRoundOf(m, o)
        if (d < 0) return 1f
        val since = elapsedSinceFaceoff()
        val startAt = ((d + DEATH_SHRINK_START) * combatRoundMs).toLong()
        if (since < startAt) return 1f
        val p = ((since - startAt).toFloat() / DEATH_SHRINK_MS).coerceIn(0f, 1f)
        return 1f - p
    }

    /** Cuanto falta, DESPUES del ultimo golpe, para que acabe de encogerse el ultimo de los tuyos que cae (0 si ninguno o ya acabo). */
    private fun ownDeathTailMs(o: CombatData?): Long {
        if (o == null) return 0L
        var end = 0L
        for (m in o.startHps.indices) {
            val d = deathRoundOf(m, o)
            if (d >= 0) end = maxOf(end, ((d + DEATH_SHRINK_START) * combatRoundMs).toLong() + DEATH_SHRINK_MS)
        }
        return (end - o.rounds.size * combatRoundMs).coerceAtLeast(0L)
    }

    private fun directionFor(dx: Int, dy: Int): DungeonSpriteRepository.Direction = when {
        dx > 0 -> DungeonSpriteRepository.Direction.RIGHT
        dx < 0 -> DungeonSpriteRepository.Direction.LEFT
        dy > 0 -> DungeonSpriteRepository.Direction.DOWN
        else -> DungeonSpriteRepository.Direction.UP
    }

    private fun moveProgress(): Float {
        if (phase != Phase.MOVING) return 1f
        val now = System.currentTimeMillis()
        return ((now - phaseStartAt).toFloat() / currentMoveMs).coerceIn(0f, 1f)
    }

    /** Posicion interpolada de un enemigo concreto para ESTE fotograma - compartida por los dos
     *  paneles para que se muevan exactamente en sincronia. */
    private fun enemyPos(step: DungeonSimulator.StepResult?, t: Float, enemy: DungeonState.EnemyTile): Pair<Float, Float> {
        // Durante la animacion de un combate, cada enemigo se ve en la casilla que le toca (sin pisar a nadie).
        layout?.enemies?.get(enemy.id)?.let { return it.first.toFloat() to it.second.toFloat() }
        val move = step?.enemyMoves?.find { it.id == enemy.id }
        return if (move != null && phase == Phase.MOVING) {
            (move.fromX + (move.toX - move.fromX) * t) to (move.fromY + (move.toY - move.fromY) * t)
        } else enemy.x.toFloat() to enemy.y.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF15131A.toInt())
        val map = renderMap ?: return
        val r = runState ?: return
        val sp = species ?: return
        val t = moveProgress()
        val step = currentStep

        // Combate: jugador y enemigo se quedan CADA UNO en su propia casilla (pedido explicito
        // del usuario: "quiero que cada uno este en un cuadrado... no que se queden dentro de un
        // mismo cuadrado y se peguen ahi, da incita a bugs visuales") - el modelo de simulacion
        // SI mueve al jugador a la casilla del enemigo al resolver el paso (stepOnce, run.px/py),
        // pero mientras dure la animacion de combate (acercamiento Y golpes) se dibuja al
        // jugador quieto en la casilla de ORIGEN (step.fromX/fromY) en vez de interpolar hacia
        // la del enemigo - el enemigo ya se dibuja en la suya propia (enemy.x/y = step.toX/toY,
        // ver drawCombatEnemy). Una casilla entera de separacion real, no un empujon en pixeles
        // dentro de la misma casilla compartida.
        val inCombatAnim = combatData(step?.outcome) != null && phase != Phase.IDLE
        // Donde se dibuja cada uno durante el combate (ver combatLayout); null fuera de la animacion de un combate.
        layout = if (inCombatAnim && step != null) combatLayout(r, step) else null
        val lay = layout
        val playerTx: Float; val playerTy: Float
        if (lay != null) {
            val leadPos = lay.members.getOrNull(teamView(r).lead) ?: (r.px.toFloat() to r.py.toFloat())
            playerTx = leadPos.first; playerTy = leadPos.second
        } else if (inCombatAnim) {
            playerTx = step!!.fromX.toFloat(); playerTy = step.fromY.toFloat()
        } else if (step != null && phase == Phase.MOVING) {
            playerTx = step.fromX + (step.toX - step.fromX) * t
            playerTy = step.fromY + (step.toY - step.fromY) * t
        } else {
            playerTx = r.px.toFloat(); playerTy = r.py.toFloat()
        }

        val viewW = width.toFloat()
        val hudH = 30f * density
        val dividerH = 5f * density
        val remaining = (height - hudH - dividerH).coerceAtLeast(0f)
        val topH = remaining * topPanelFraction
        val bottomH = remaining - topH

        drawTopPanel(canvas, map, sp, r, step, t, playerTx, playerTy, hudH, viewW, topH)
        canvas.drawRect(0f, hudH + topH, viewW, hudH + topH + dividerH, dividerPaint)
        drawMinimapPanel(canvas, map, step, t, playerTx, playerTy, hudH + topH + dividerH, viewW, bottomH)
        drawHud(canvas, sp, r, viewW, hudH)
    }

    // true cuando el terreno del ultimo drawTerrain se pinto con las texturas de Mundo Misterioso:
    // entonces las escaleras no ponen su cuadrado gris translucido debajo (tapaba la textura).
    private var terrainTextured = false

    /** Terreno (suelo y paredes) + escaleras de las casillas [firstCol]..[lastCol] x
     *  [firstRow]..[lastRow], en coordenadas de mapa (la camara ya esta aplicada al lienzo).
     *  Compartido por el panel de arriba y por la comprobacion de depuracion ([debugDrawTerrain]). */
    private fun drawTerrain(canvas: Canvas, map: DungeonState.DungeonMap, firstCol: Int, lastCol: Int, firstRow: Int, lastRow: Int) {
        ensureDungeonTileBitmaps(map)
        val wallBmp = wallTileBitmap
        val floorBmp = floorTileBitmap
        // Terreno con las texturas de Mundo Misterioso (bordes entre pared y suelo, esquinas...) -
        // pedido del usuario, solo estetica. Si no hay texturas para este piso, el dibujo de
        // siempre (una textura repetida por tipo de casilla, o colores planos).
        terrainTextured = tileArt.drawCells(canvas, map, tilePx, firstCol, lastCol, firstRow, lastRow)
        if (!terrainTextured) {
            for (ty in firstRow..lastRow) for (tx in firstCol..lastCol) {
                val l = tx * tilePx; val tp = ty * tilePx
                val isWall = map.isWall(tx, ty)
                val bmp = if (isWall) wallBmp else floorBmp
                if (bmp != null) {
                    tileDestRect.set(l, tp, l + tilePx, tp + tilePx)
                    canvas.drawBitmap(bmp, null, tileDestRect, null)
                } else {
                    canvas.drawRect(l, tp, l + tilePx, tp + tilePx, if (isWall) wallPaint else floorPaint)
                }
            }
        }
        // Escalera de bajada (por donde apareces en el piso: un agujero con peldaños y fondo
        // negro, "como que hay algo mas abajo") y de subida (3 peldaños claros hacia arriba) -
        // pedido explicito del usuario, solo en este panel ("la vista en vivo... el resumen
        // [minimapa] sigue con los iconos de colores").
        drawDescendingHole(canvas, map.startX * tilePx, map.startY * tilePx, tilePx)
        // La escalera de subida no se dibuja mientras el jefe siga vivo - pedido explicito del
        // usuario: "la escalera de subida solo aparecera si lo derrotas" (stepOnce ya bloquea que
        // funcione; aqui se oculta tambien visualmente para que no parezca usable).
        if (map.enemies.none { it.isBoss }) drawAscendingSteps(canvas, map.exitX * tilePx, map.exitY * tilePx, tilePx)
    }

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_DUMP_TERRAIN): pinta el mapa ENTERO
     *  con el mismo codigo que el panel de arriba, a tamaño real de casilla (tilePx). */
    fun debugDrawTerrain(canvas: Canvas, map: DungeonState.DungeonMap) {
        drawTerrain(canvas, map, 0, map.width - 1, 0, map.height - 1)
    }

    /** Lado de una casilla en pantalla, en px - para que la comprobacion de depuracion escale. */
    fun debugTilePx(): Float = tilePx

    /** Panel de arriba: camara de cerca siguiendo al explorador, sprites reales - igual que la
     *  version de un solo panel de antes, solo que acotada a la region [top, top+panelH]. */
    private fun drawTopPanel(
        canvas: Canvas, map: DungeonState.DungeonMap, sp: String, r: DungeonSimulator.RunState, step: DungeonSimulator.StepResult?,
        t: Float, playerTx: Float, playerTy: Float, top: Float, panelW: Float, panelH: Float
    ) {
        canvas.save()
        canvas.clipRect(0f, top, panelW, top + panelH)
        val mapPxW = map.width * tilePx; val mapPxH = map.height * tilePx
        var camX = playerTx * tilePx + tilePx / 2f - panelW / 2f
        var camY = playerTy * tilePx + tilePx / 2f - panelH / 2f
        camX = camX.coerceIn(0f, (mapPxW - panelW).coerceAtLeast(0f))
        camY = camY.coerceIn(0f, (mapPxH - panelH).coerceAtLeast(0f))
        canvas.translate(-camX, top - camY)

        ensureBgBitmap(map)
        bgBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val firstCol = (camX / tilePx).toInt().coerceAtLeast(0)
        val lastCol = ((camX + panelW) / tilePx).toInt().coerceAtMost(map.width - 1)
        val firstRow = (camY / tilePx).toInt().coerceAtLeast(0)
        val lastRow = ((camY + panelH) / tilePx).toInt().coerceAtMost(map.height - 1)
        drawTerrain(canvas, map, firstCol, lastCol, firstRow, lastRow)

        val hideLoot = isCombatActive()
        for (item in map.items) {
            if (hideLoot && item in pendingLootItems) continue
            val icon = item.icon?.let { DungeonSpriteRepository.itemIcon(context, it) } ?: continue
            val cx = item.x * tilePx + tilePx / 2f; val cy = item.y * tilePx + tilePx / 2f
            val half = tilePx * 0.28f
            spriteRect.set(cx - half, cy - half, cx + half, cy + half)
            canvas.drawBitmap(icon, null, spriteRect, null)
        }
        val combatActive = isCombatActive()
        for (enemy in map.enemies) {
            // El enemigo EN COMBATE se dibuja aparte (drawCombatEnemy) - con barra de vida o
            // animacion de muerte segun toque; dibujarlo tambien aqui lo duplicaria.
            if (combatActive && enemy.id == combatEnemyId) continue
            val (ex, ey) = enemyPos(step, t, enemy)
            val dir = enemyDirections[enemy.id] ?: DungeonSpriteRepository.Direction.DOWN
            val scale = if (enemy.isBoss) DungeonSimulator.BOSS_SPRITE_SCALE else 1f
            drawSpriteAt(canvas, DungeonSpriteRepository.frames(context, enemy.species, false, dir), t, ex, ey, scale = scale)
        }
        // EQUIPO: quien va delante (el primero en pie) se dibuja como el explorador de siempre; los demas
        // miembros en pie, detras. Un miembro caido desaparece (ver teamView).
        val team = teamView(r)
        val lead = team.party[team.lead]
        // Cada miembro que ATACA en el golpe que se esta mostrando da su estocada hacia el enemigo.
        fun lungeOf(memberIdx: Int, mx: Float, my: Float): Pair<Float, Float> {
            if (!combatActive) return 0f to 0f
            val idx = currentCombatRoundIdx()
            val isActing = idx >= 0 && combatRounds[idx].playerActs && !combatRounds[idx].move && combatRounds[idx].actor == memberIdx
            val stanceScale = if (combatEnemySnapshot?.isBoss == true) DungeonSimulator.BOSS_SPRITE_SCALE else 1f
            val enemy = combatEnemySnapshot
            val (dx, dy) = if (enemy != null) (enemy.x - mx) to (enemy.y - my) else 0f to 0f
            return combatLungeOffset(dx, dy, isActing, combatRoundProgress(idx), stanceScale)
        }
        val (playerOffX, playerOffY) = lungeOf(team.lead, playerTx, playerTy)
        // El enemigo en combate se dibuja ANTES que el jugador (no despues, como hasta ahora) -
        // bug real de un jefe (sprite mucho mas grande, DungeonSimulator.BOSS_SPRITE_SCALE): con
        // el enemigo encima, su silueta enorme tapaba por completo al jugador, que quedaba
        // invisible durante todo el combate. Con enemigos normales (mismo tamaño que el jugador,
        // ya bien separados por combatantOffset) el orden no se nota.
        if (combatActive) drawCombatEnemy(canvas, playerTx, playerTy)
        // Los compañeros en pie van detras del que lidera (se dibujan antes, de atras hacia delante) - ver followerTiles.
        val followerPos = followerTiles(r, step, t, team.followers.size)
        for ((j, memberIdx) in team.followers.withIndex().reversed().map { it.index to it.value }) {
            val (fx, fy, fdir) = followerPos[j]
            val (fox, foy) = lungeOf(memberIdx, fx, fy)
            val m = team.party[memberIdx]
            // El enemigo golpea al miembro que tiene pegado: si es un compañero, es el quien se pone rojo. Si cae, se encoge hasta desaparecer.
            drawSpriteAt(canvas, DungeonSpriteRepository.frames(context, m.first, m.second, fdir), t, fx, fy, fox, foy, scale = memberDeathScale(memberIdx), hitFlash = isReceivingHit(playerSide = true, memberIdx = memberIdx))
        }
        // En combate mira al enemigo (puede estar detras si alcanzo a un compañero); si no, hacia donde anda.
        val leadDir = combatEnemySnapshot?.takeIf { combatActive }?.let { dirToward(Math.round(playerTx), Math.round(playerTy), it.x, it.y) } ?: playerDirection
        drawSpriteAt(canvas, DungeonSpriteRepository.frames(context, lead.first, lead.second, leadDir), t, playerTx, playerTy, playerOffX, playerOffY, scale = memberDeathScale(team.lead), hitFlash = isReceivingHit(playerSide = true, memberIdx = team.lead))
        // Barra de vida y texto del enemigo del combate: por encima de todos los sprites.
        if (combatActive) drawCombatEnemyOverlay(canvas, playerTx, playerTy)
        // Barra de vida del jugador SIEMPRE visible (pedido explicito del usuario - primero pidio
        // que solo apareciera en combate, como el enemigo, pero luego cambio de opinion: "prefiero
        // que este siempre visible... asi puedo ver si esta subiendo la vida con el tiempo o
        // cuanto sube cuando recojo un item de curacion" - fuera de combate, displayedPlayerHp ya
        // devuelve r.hp tal cual, que SI refleja la curacion pasiva cada 3 pasos y la de objetos en
        // cuanto stepOnce los aplica) - flota sobre su propia casilla igual que la del enemigo
        // (drawHpBarAbove), en verde (hudBarFront) en vez del rojo del enemigo (enemyHpFront) para
        // distinguir de un vistazo quien es quien. El jugador se dibuja SIEMPRE el ultimo (ver
        // comentario de arriba), asi que su propia barra nunca puede quedar tapada por el enemigo -
        // no hace falta el mismo ajuste de "debajo" que tiene la del enemigo.
        // Con equipo, cada miembro en pie lleva su propia barra sobre su sprite (la del que lidera, la de siempre).
        run {
            val maxHp = DungeonSimulator.maxHp(context, lead.first, lead.second).coerceAtLeast(1)
            if (memberDeathScale(team.lead) >= 1f) drawHpBarAbove(canvas, playerTx, playerTy, (team.hps[team.lead] / maxHp.toFloat()).coerceIn(0f, 1f), front = hudBarFront)
            for ((j, memberIdx) in team.followers.withIndex()) {
                if (memberDeathScale(memberIdx) < 1f) continue   // el que se esta encogiendo ya no lleva barra
                val m = team.party[memberIdx]
                val mMax = DungeonSimulator.maxHp(context, m.first, m.second).coerceAtLeast(1)
                drawHpBarAbove(canvas, followerPos[j].first, followerPos[j].second, (team.hps[memberIdx] / mMax.toFloat()).coerceIn(0f, 1f), front = hudBarFront)
            }
        }
        (currentStep?.outcome as? DungeonSimulator.Outcome.Pickup)?.let { outcome ->
            if (phase == Phase.RESOLVING) drawFloatingText(canvas, outcome.effectText, playerTx, playerTy, pickupTextPaint)
        }
        canvas.restore()
    }

    // Los datos de un combate para ANIMARLO: salen de un combate normal y tambien del combate FATAL (Defeated con enemigo),
    // asi se ve caer al equipo entero antes de cerrar la carrera.
    private class CombatData(
        val enemy: DungeonState.EnemyTile, val rounds: List<DungeonSimulator.CombatRound>, val enemyMaxHp: Float, val won: Boolean,
        val startHps: List<Float>, val startFront: Int, val startTiles: List<Pair<Int, Int>>
    )

    private fun combatData(o: DungeonSimulator.Outcome?): CombatData? = when (o) {
        is DungeonSimulator.Outcome.Combat -> CombatData(o.enemy, o.rounds, o.enemyMaxHp, o.won, o.startHps, o.startFront, o.startTiles)
        is DungeonSimulator.Outcome.Defeated -> o.enemy?.let { CombatData(it, o.rounds, o.enemyMaxHp, o.enemyAlsoDefeated, o.startHps, o.startFront, o.startTiles) }
        else -> null
    }

    /** Cierra la carrera por derrota (todo el equipo caido): resumen, vuelta al selector. Con el nombre de los compañeros. */
    private fun finishDefeat(notifyActivity: Boolean) {
        val sp = species ?: return
        running = false
        DungeonSimulator.finishRun(context, sp, DungeonSimulator.defeatClause(companions.map { it.first }), DungeonState::returnDefeated)
        runState = null   // carrera cerrada: un stop() posterior no debe volver a volcarla
        if (notifyActivity) onRunEnded?.invoke()
    }

    /** Cierto durante TODA la secuencia de combate (acercamiento, cara a cara, golpes y animacion
     *  de muerte) - antes solo cubria Phase.RESOLVING, dejando un hueco visible sin el enemigo
     *  durante el acercamiento (ver Phase.IDLE en advance()). */
    private fun isCombatActive(): Boolean = combatData(currentStep?.outcome) != null

    /** Milisegundos transcurridos desde que arranco el PRIMER golpe (tras la pausa de "se miran
     *  antes de atacar") - negativo mientras todavia estan en esa pausa. Solo tiene sentido en
     *  Phase.RESOLVING (antes de eso el combate ni ha empezado a animarse golpe a golpe). */
    private fun elapsedSinceFaceoff(): Long =
        if (phase == Phase.RESOLVING) now() - phaseStartAt - COMBAT_FACEOFF_MS else Long.MIN_VALUE
    private fun now() = System.currentTimeMillis()

    /** Indice del golpe que toca mostrar AHORA MISMO - -1 mientras todavia se estan mirando antes
     *  de golpear (o si el combate no ha empezado su fase de golpes todavia). Cada golpe se queda
     *  en pantalla [combatRoundMs] antes de pasar al siguiente. */
    private fun currentCombatRoundIdx(): Int {
        // Solo hay "golpe actual" durante un combate de verdad: antes, tras una recogida o unas escaleras, esto seguia
        // leyendo los golpes del ULTIMO combate (la lista no se vacia) y el sprite se ponia rojo como si recibiera
        // daño al curarse o al hacer cualquier otra cosa.
        if (!isCombatActive() || combatRounds.isEmpty()) return -1
        val since = elapsedSinceFaceoff()
        if (since < 0) return -1
        return (since / combatRoundMs).toInt().coerceIn(0, combatRounds.size - 1)
    }

    /** 0..1 segun cuanto lleva mostrandose EL golpe [idx] concreto (0 al empezar, 1 justo antes de
     *  pasar al siguiente) - controla la animacion de ataque (lunge). */
    private fun combatRoundProgress(idx: Int): Float {
        if (idx < 0) return 0f
        val intoRound = elapsedSinceFaceoff() - idx * combatRoundMs
        return (intoRound.toFloat() / combatRoundMs).coerceIn(0f, 1f)
    }

    /** Curva "avanza y retrocede" para el golpe: sube a maximo a los 35% del golpe y ya ha vuelto
     *  a 0 a los 70% (pedido explicito del usuario: "haya una animacion de atacar"). */
    private fun lungeFactor(progress: Float): Float = when {
        progress < 0.35f -> progress / 0.35f
        progress < 0.7f -> 1f - (progress - 0.35f) / 0.35f
        else -> 0f
    }

    /** Desplazamiento visual de un combatiente respecto al centro de SU PROPIA casilla - ya NO
     *  hace falta un "paso atras" constante (pedido explicito del usuario: "quiero que cada uno
     *  este en un cuadrado... no que se queden dentro de un mismo cuadrado", ver [inCombatAnim]
     *  en onDraw - jugador y enemigo ya se dibujan en dos casillas distintas de verdad, una
     *  separacion real, no un empujon en pixeles). Solo queda el golpe hacia delante que va y
     *  vuelve cuando le toca atacar ([lungeFactor]).
     *
     *  [dxToTarget]/[dyToTarget] - VECTOR REAL (en casillas) hacia el objetivo, no una direccion
     *  de 4 vias - bug real reportado por el usuario: "se han posicionado como en diagonal... y
     *  pegaban en el aire" (y, mas notorio con jefes por su sprite mas grande, "se suelen poner en
     *  las esquinas"). Causa: walkableNeighbors permite movimiento en diagonal (corta esquinas si
     *  las dos casillas ortogonales de al lado estan libres), asi que el paso que dispara el
     *  combate puede aterrizar en una casilla diagonal, no solo N/S/E/O - con la version anterior
     *  (un Direction de 4 vias, la MISMA que ya se usaba para elegir el sprite de cara) el lunge
     *  solo se movia por UN eje aunque el objetivo estuviera en diagonal, fallando el golpe a la
     *  vista. Aqui se normaliza el vector real (que puede tener las dos componentes a la vez) en
     *  vez de forzarlo a un solo eje - la cara del sprite (Direction, en drawSpriteAt) se queda
     *  igual, es solo una aproximacion visual sin sprites diagonales de verdad, pero el LUNGE
     *  ahora apunta exactamente donde esta el objetivo. [stanceScale] (jefe = mas grande) da un
     *  golpe un poco mas largo, a juego con su silueta mayor. */
    private fun combatLungeOffset(dxToTarget: Float, dyToTarget: Float, isActing: Boolean, roundProgress: Float, stanceScale: Float = 1f): Pair<Float, Float> {
        if (!isActing) return 0f to 0f
        val dist = kotlin.math.hypot(dxToTarget, dyToTarget)
        if (dist < 0.0001f) return 0f to 0f
        val lungePx = lungeFactor(roundProgress) * tilePx * COMBAT_LUNGE_FRACTION * (0.85f + stanceScale * 0.15f)
        return (dxToTarget / dist * lungePx) to (dyToTarget / dist * lungePx)
    }

    /** El enemigo en combate SIEMPRE se dibuja desde [combatEnemySnapshot] (nunca desde
     *  map.enemies) - pedido explicito del usuario: "que no desaparezca inmediatamente, que
     *  tengan su barra de vida, que ponga lo que le quitas y lo que te quitan". Si se gana el
     *  combate, tras el ultimo golpe se anima encogiendose y desvaneciendose en vez de
     *  desaparecer de golpe (pedido explicito: "hacer una animacion de muerte a los pokemon que
     *  matas"). */
    private fun drawCombatEnemy(canvas: Canvas, playerTx: Float, playerTy: Float) {
        val enemy = combatEnemySnapshot ?: return
        val ex = enemy.x.toFloat(); val ey = enemy.y.toFloat()
        val dir = enemyDirections[enemy.id] ?: DungeonSpriteRepository.Direction.DOWN
        val frames = DungeonSpriteRepository.frames(context, enemy.species, false, dir)
        val scale = if (enemy.isBoss) DungeonSimulator.BOSS_SPRITE_SCALE else 1f

        if (phase != Phase.RESOLVING) {
            // Todavia acercandose (Phase.MOVING) - el enemigo ya mira hacia el jugador (puesto en
            // Phase.IDLE); sin lunge (no esta actuando todavia), nada que desplazar.
            drawSpriteAt(canvas, frames, 0f, ex, ey, scale = scale)
            return
        }

        val roundsMs = combatRounds.size * combatRoundMs
        val since = elapsedSinceFaceoff()
        if (since >= roundsMs && combatWon) {
            val fadeT = ((since - roundsMs).toFloat() / DEATH_FADE_MS).coerceIn(0f, 1f)
            drawDyingSpriteAt(canvas, frames, ex, ey, fadeT, scale = scale)
            return
        }

        val idx = currentCombatRoundIdx()
        val isActing = idx >= 0 && !combatRounds[idx].playerActs
        val (offX, offY) = combatLungeOffset(playerTx - ex, playerTy - ey, isActing, combatRoundProgress(idx), scale)
        drawSpriteAt(canvas, frames, 0f, ex, ey, offX, offY, scale, hitFlash = isReceivingHit(playerSide = false))
        // La barra de vida y el texto flotante del enemigo se dibujan DESPUES de todos los sprites (ver
        // drawCombatEnemyOverlay): con equipo, un compañero justo encima del enemigo los tapaba.
    }

    /** Barra de vida y texto flotante del enemigo del combate, por ENCIMA de todos los sprites (con equipo, un
     *  compañero que quedaba justo encima del enemigo le tapaba la barra). Solo durante los golpes. */
    private fun drawCombatEnemyOverlay(canvas: Canvas, playerTx: Float, playerTy: Float) {
        val enemy = combatEnemySnapshot ?: return
        if (phase != Phase.RESOLVING) return
        val roundsMs = combatRounds.size * combatRoundMs
        if (elapsedSinceFaceoff() >= roundsMs && combatWon) return   // ya se esta desvaneciendo
        val ex = enemy.x.toFloat(); val ey = enemy.y.toFloat()
        val scale = if (enemy.isBoss) DungeonSimulator.BOSS_SPRITE_SCALE else 1f
        val idx = currentCombatRoundIdx()

        // El enemigo esta al SUR del jugador (una casilla justo debajo) - "encima del enemigo"
        // caeria sobre la casilla del jugador, que se dibuja despues y la taparia (bug real
        // reportado por el usuario). En ese caso, la barra/texto del enemigo van DEBAJO en su
        // lugar.
        val enemyBelowPlayer = ey > playerTy
        val hpNow = if (idx >= 0) combatRounds[idx].enemyHpAfter else combatEnemyMaxHp
        drawHpBarAbove(canvas, ex, ey, hpNow / combatEnemyMaxHp, scale, below = enemyBelowPlayer)
        if (idx >= 0 && !combatRounds[idx].move) {   // un paso de acercamiento no tiene golpe ni texto
            val round = combatRounds[idx]
            // "¡Te falla!" sonaba raro - pedido explicito del usuario: "cuando falla en vez de
            // poner te falla pon fallo o fallaste".
            // Supereficaz/poco eficaz (pedido explicito del usuario) se enseña ANTES que el
            // critico si coinciden los dos (raro, pero puede pasar) - un mensaje a la vez, no se
            // concatenan para no atestar el texto flotante.
            val text = if (round.missed) (if (round.playerActs) "¡Falla!" else "¡Fallo!")
                else if (round.effectiveness >= 2f) "¡Súper eficaz! -${round.damage.toInt()}"
                else if (round.effectiveness < 1f) "Poco eficaz... -${round.damage.toInt()}"
                else if (round.critical) "¡Crítico! -${round.damage.toInt()}"
                else "-${round.damage.toInt()}"
            // El texto del GOLPE DEL JUGADOR (recibido por el enemigo) tambien va debajo si el
            // enemigo esta al sur, mismo motivo que la barra - debajo de ESA barra, no a la misma
            // altura, para que no se solapen entre si. El texto de un golpe del ENEMIGO (recibido
            // por el jugador) no necesita este ajuste - "encima del jugador" nunca cae sobre el
            // enemigo (queda mas lejos, hacia el norte).
            if (round.playerActs) {
                drawFloatingText(canvas, text, ex, ey, if (round.critical) criticalTextPaint else damageTextPaint, below = enemyBelowPlayer)
            } else {
                // El golpe del enemigo lo recibe el miembro que tenia pegado (no siempre el de delante): el texto sale sobre el.
                val victim = layout?.members?.getOrNull(round.actor)
                drawFloatingText(canvas, text, victim?.first ?: playerTx, victim?.second ?: playerTy, if (round.critical) criticalTextPaint else damageTextPaint)
            }
        }
    }

    // [below] - pedido explicito del usuario: "cuando el enemigo esta debajo del Pokemon... mi
    // Pokemon tapa la barra" - jugador y enemigo estan una casilla separados (ver isCombatAnim en
    // onDraw), asi que "encima del enemigo" cae justo sobre la casilla del jugador cuando el
    // enemigo esta al SUR (el jugador se dibuja despues, tapando la barra/texto) - en ese caso se
    // dibuja DEBAJO del enemigo en su lugar (lejos del jugador). Ver drawCombatEnemy, que decide
    // cuando hace falta.
    private fun drawHpBarAbove(canvas: Canvas, tx: Float, ty: Float, frac: Float, scale: Float = 1f, below: Boolean = false, front: Paint = enemyHpFront) {
        val cx = tx * tilePx + tilePx / 2f
        val w = tilePx * 0.7f * scale; val h = 5f * density
        val l = cx - w / 2f
        val top = if (below) (ty + 1f) * tilePx + 6f * density * scale else ty * tilePx - 6f * density * scale - h
        canvas.drawRect(l, top, l + w, top + h, enemyHpBack)
        canvas.drawRect(l, top, l + w * frac.coerceIn(0f, 1f), top + h, front)
    }

    private fun drawFloatingText(canvas: Canvas, text: String, tx: Float, ty: Float, paint: Paint, below: Boolean = false) {
        val cx = tx * tilePx + tilePx / 2f
        // 32dp (antes 20dp) en el caso "debajo" - pedido explicito del usuario: "el texto que
        // aparece en la barra del enemigo esta muy pegada a la barra... distanciala un poco mas".
        // El caso "encima" (por defecto, enemigo al norte o en el mismo eje) no se toca, no se
        // reporto el mismo problema ahi.
        val cy = if (below) (ty + 1f) * tilePx + 32f * density else ty * tilePx - 14f * density
        canvas.drawText(text, cx - paint.measureText(text) / 2f, cy, paint)
    }

    private fun drawDyingSpriteAt(canvas: Canvas, frames: List<Bitmap>?, tx: Float, ty: Float, fadeT: Float, offsetX: Float = 0f, offsetY: Float = 0f, scale: Float = 1f) {
        if (frames.isNullOrEmpty()) return
        val bmp = frames[0]
        val cx = tx * tilePx + tilePx / 2f + offsetX
        val cy = ty * tilePx + tilePx / 2f + offsetY
        val half = tilePx * 0.55f * scale * (1f - fadeT * 0.6f)
        deathFadePaint.alpha = ((1f - fadeT) * 255).toInt().coerceIn(0, 255)
        spriteRect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawBitmap(bmp, null, spriteRect, deathFadePaint)
    }

    /** Panel de abajo: el mapa ENTERO del piso, sin camara (todo escalado para que quepa) - los
     *  mismos puntos de color que el mapa del widget retirado (DungeonMapRenderer), pero EN VIVO
     *  (mismas posiciones interpoladas que el panel de arriba, no una foto fija) - pedido
     *  explicito del usuario: "el mapa general con los puntitos de colores... esta vez si en
     *  vivo... asi puedes ver donde esta todo". */
    private fun drawMinimapPanel(
        canvas: Canvas, map: DungeonState.DungeonMap, step: DungeonSimulator.StepResult?, t: Float,
        playerTx: Float, playerTy: Float, top: Float, panelW: Float, panelH: Float
    ) {
        canvas.save()
        canvas.clipRect(0f, top, panelW, top + panelH)
        canvas.drawRect(0f, top, panelW, top + panelH, miniBgPaint)

        val miniTile = minOf(panelW / map.width, panelH / map.height)
        val gridW = map.width * miniTile; val gridH = map.height * miniTile
        val offsetX = (panelW - gridW) / 2f
        val offsetY = top + (panelH - gridH) / 2f

        // Mismos tiles reales que el panel de arriba (pedido explicito del usuario: "pon en el
        // mapa tambien los tileset") en vez de los colores planos de antes - ya decodificados por
        // ensureDungeonTileBitmaps (llamado desde drawTopPanel en el mismo ciclo de dibujo).
        ensureDungeonTileBitmaps(map)
        // Terreno con las mismas texturas de Mundo Misterioso que el panel de arriba (bordes entre
        // pared y suelo) - si no hay, el dibujo de antes.
        canvas.save(); canvas.translate(offsetX, offsetY)
        val textured = tileArt.drawCells(canvas, map, miniTile, 0, map.width - 1, 0, map.height - 1)
        canvas.restore()
        terrainTextured = textured
        if (!textured) {
            val wallBmp = wallTileBitmap
            val floorBmp = floorTileBitmap
            for (ty in 0 until map.height) for (tx in 0 until map.width) {
                val l = offsetX + tx * miniTile; val tp = offsetY + ty * miniTile
                val isWall = map.isWall(tx, ty)
                val bmp = if (isWall) wallBmp else floorBmp
                if (bmp != null) {
                    tileDestRect.set(l, tp, l + miniTile, tp + miniTile)
                    canvas.drawBitmap(bmp, null, tileDestRect, null)
                } else {
                    canvas.drawRect(l, tp, l + miniTile, tp + miniTile, if (isWall) miniWallPaint else miniFloorPaint)
                }
            }
            canvas.drawRect(
                offsetX + map.startX * miniTile, offsetY + map.startY * miniTile,
                offsetX + (map.startX + 1) * miniTile, offsetY + (map.startY + 1) * miniTile, startPaint
            )
            canvas.drawRect(
                offsetX + map.exitX * miniTile, offsetY + map.exitY * miniTile,
                offsetX + (map.exitX + 1) * miniTile, offsetY + (map.exitY + 1) * miniTile, exitPaint
            )
        } else {
            // Las mismas escaleras que el panel de arriba, a tamaño de casilla del minimapa.
            drawDescendingHole(canvas, offsetX + map.startX * miniTile, offsetY + map.startY * miniTile, miniTile)
            if (map.enemies.none { it.isBoss }) drawAscendingSteps(canvas, offsetX + map.exitX * miniTile, offsetY + map.exitY * miniTile, miniTile)
        }

        // Los MISMOS sprites que el panel de arriba (objetos, enemigos y el explorador) en vez de
        // puntos de color - pedido explicito del usuario: "en la parte de abajo donde se ve el mapa
        // completo deberias poner tambien los sprites de arriba". Con un minimo de tamaño en pantalla
        // para que se distingan en los pisos grandes, donde la casilla del minimapa es muy pequeña.
        val minHalf = 8f * density
        val hideLootMini = isCombatActive()
        for (item in map.items) {
            if (hideLootMini && item in pendingLootItems) continue
            val icon = item.icon?.let { DungeonSpriteRepository.itemIcon(context, it) }
            val cx = offsetX + (item.x + 0.5f) * miniTile; val cy = offsetY + (item.y + 0.5f) * miniTile
            if (icon != null) {
                val half = maxOf(miniTile * 0.42f, minHalf * 0.75f)
                spriteRect.set(cx - half, cy - half, cx + half, cy + half)
                canvas.drawBitmap(icon, null, spriteRect, null)
            } else {
                canvas.drawCircle(cx, cy, miniTile * 0.35f, miniItemPaint)
            }
        }
        for (enemy in map.enemies) {
            val (ex, ey) = enemyPos(step, t, enemy)
            val dir = enemyDirections[enemy.id] ?: DungeonSpriteRepository.Direction.DOWN
            val scale = if (enemy.isBoss) DungeonSimulator.BOSS_SPRITE_SCALE else 1f
            drawSpriteInMinimap(canvas, DungeonSpriteRepository.frames(context, enemy.species, false, dir), t, ex, ey, offsetX, offsetY, miniTile, minHalf, scale, miniEnemyPaint)
        }
        runState?.let { r ->
            // Los mismos miembros en pie que el panel de arriba: los compañeros detras y quien lidera delante.
            val team = teamView(r)
            if (team.party.isEmpty()) return@let
            val ft = followerTiles(r, step, t, team.followers.size)
            team.followers.withIndex().reversed().forEach { (j, memberIdx) ->
                val m = team.party[memberIdx]
                drawSpriteInMinimap(canvas, DungeonSpriteRepository.frames(context, m.first, m.second, ft[j].third), t, ft[j].first, ft[j].second, offsetX, offsetY, miniTile, minHalf, memberDeathScale(memberIdx), miniPlayerPaint)
            }
            val lead = team.party[team.lead]
            drawSpriteInMinimap(canvas, DungeonSpriteRepository.frames(context, lead.first, lead.second, playerDirection), t, playerTx, playerTy, offsetX, offsetY, miniTile, minHalf, memberDeathScale(team.lead), miniPlayerPaint)
        }
        canvas.restore()
    }

    /** Sprite en el minimapa, centrado en la casilla ([gx], [gy]) (en casillas, interpolada). Si el
     *  sprite no esta disponible dibuja un punto del color de [fallback], como antes. */
    private fun drawSpriteInMinimap(
        canvas: Canvas, frames: List<Bitmap>?, t: Float, gx: Float, gy: Float,
        offsetX: Float, offsetY: Float, miniTile: Float, minHalf: Float, scale: Float, fallback: Paint
    ) {
        val cx = offsetX + (gx + 0.5f) * miniTile; val cy = offsetY + (gy + 0.5f) * miniTile
        if (frames.isNullOrEmpty()) {
            canvas.drawCircle(cx, cy, miniTile * 0.45f, fallback)
            return
        }
        val idx = if (phase == Phase.MOVING) (t * frames.size).toInt().coerceIn(0, frames.size - 1) else 0
        val half = maxOf(miniTile * 0.75f, minHalf) * scale
        spriteRect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawBitmap(frames[idx], null, spriteRect, null)
    }

    /** HP del explorador a mostrar EN EL HUD - durante un combate animado, va golpe a golpe con
     *  la animacion (ver drawCombatEnemy) en vez de saltar directo al resultado final (que ya
     *  esta en r.hp desde antes incluso de que arranque la animacion, porque stepOnce resuelve el
     *  combate entero de una vez). */
    // (La vida mostrada de cada miembro, golpe a golpe, sale de teamView.)

    // Pedido explicito del usuario: "pon la barra de vida de mi Pokemon como las de los enemigos,
    // pero en verde, y asi lo quitas de la parte de arriba a la derecha y pones ahi la X" - la
    // barra ya NO vive en el HUD (dejaba muy poco sitio a la X, que se salia del HUD estrecho y
    // tapaba mitad barra/mitad gameplay); ahora flota sobre el propio sprite del jugador en el
    // panel de arriba, igual que hace drawCombatEnemy con la del enemigo (ver drawTopPanel).
    private fun drawHud(canvas: Canvas, sp: String, r: DungeonSimulator.RunState, viewW: Float, hudH: Float) {
        canvas.drawRect(0f, 0f, viewW, hudH, hudBg)
        // Quien lidera ahora (el primero en pie) y cuantos compañeros siguen con el: "+N".
        val team = teamView(r)
        val lead = team.party.getOrNull(team.lead)?.first ?: sp
        val party = if (team.followers.isEmpty()) "" else " +${team.followers.size}"
        canvas.drawText("${PetState.displayLabel(lead)}$party · Piso ${r.floor}/${DungeonState.MAX_FLOOR}", 8f * density, hudH * 0.65f, hudText)
    }

    // [scale] - "mas grande" pedido explicito del usuario para los jefes (DungeonSimulator.
    // BOSS_SPRITE_SCALE) sobre el tamaño normal de sprite (0.55 de tilePx de radio); 1f para
    // cualquier sprite normal (jugador, enemigo raso). [hitFlash] - pedido explicito del usuario
    // ("que el sprite se vuelva completamente rojo cuando recibe daño, como en Minecraft"), ver
    // [isReceivingHit].
    private fun drawSpriteAt(canvas: Canvas, frames: List<Bitmap>?, t: Float, tx: Float, ty: Float, offsetX: Float = 0f, offsetY: Float = 0f, scale: Float = 1f, hitFlash: Boolean = false) {
        if (frames.isNullOrEmpty()) return
        val idx = if (phase == Phase.MOVING) (t * frames.size).toInt().coerceIn(0, frames.size - 1) else 0
        val bmp = frames[idx]
        val cx = tx * tilePx + tilePx / 2f + offsetX
        val cy = ty * tilePx + tilePx / 2f + offsetY
        val half = tilePx * 0.55f * scale
        spriteRect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawBitmap(bmp, null, spriteRect, if (hitFlash) hitFlashPaint else null)
    }

    /** true si [playerSide] (jugador si true, enemigo si false) es quien esta recibiendo el golpe
     *  ACTUAL de combate ahora mismo - pedido explicito del usuario: "no esta claro quien pega a
     *  quien... que el sprite se vuelva completamente rojo cuando recibe daño". Solo durante la
     *  franja central del golpe (coincide con el pico del lunge, ver [lungeFactor]) y nunca si el
     *  golpe fallo (round.missed) - fallar no reparte daño, un flash ahi seria enganoso. */
    private fun isReceivingHit(playerSide: Boolean, memberIdx: Int? = null): Boolean {
        if (phase != Phase.RESOLVING || !isCombatActive()) return false
        val idx = currentCombatRoundIdx()
        if (idx < 0) return false
        val round = combatRounds[idx]
        if (round.missed || round.move) return false   // fallar no reparte daño y un paso de acercamiento no es un golpe
        val hitIsPlayer = !round.playerActs
        if (hitIsPlayer != playerSide) return false
        // Con equipo, el golpe lo recibe UN miembro concreto (el que iba delante en ese momento).
        if (playerSide && memberIdx != null && round.actor != memberIdx) return false
        val progress = combatRoundProgress(idx)
        return progress in 0.25f..0.6f
    }
}
