package com.example.pokegotchi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Escena de la GUARDERIA (pedido explicito del usuario: "que se vean los pokemon sueltos jugando por
 * ahi cuando los metes... un bebedero, una cesta con varias manzanas y una pelota"): un jardin a
 * pantalla completa donde cada Pokemon de la guarderia anda por su cuenta, con un bebedero, una cesta
 * de manzanas y una pelota. Es SOLO la parte visual - las necesidades y el XP de verdad viven en
 * PetState (settleNursery / simulateNurseryCare), y esta vista las representa:
 *  - cuando la guarderia liquida una necesidad REAL ([queueCare]: comer = vida, jugar = felicidad,
 *    beber = higiene), ese Pokemon va a la cesta / la pelota / el bebedero y al terminar sale "+5 XP";
 *  - el resto del tiempo se pasea y de vez en cuando juega, come o bebe SOLO DE ADORNO (sin XP).
 * El bebedero, la cesta y la pelota se ARRASTRAN con el dedo (la posicion de los dos muebles se guarda,
 * ver [onFurnitureMoved]); la pelota ademas se LANZA: al soltarla con impulso, los Pokemon de al lado
 * van a por ella. El humo de la chimenea del fondo se anima aqui (no esta en la imagen).
 * La cesta tiene [BASKET_MAX_APPLES] manzanas: cada vez que alguien come baja una, y se repone sola.
 */
class NurseryView(context: Context) : View(context) {

    private enum class Task { WANDER, EAT, DRINK, PLAY, TAG }
    private enum class Phase { IDLE, WALK, DO }
    // Visitantes: ENTER = sale de la casa por la puerta y baja por el camino hasta el jardin; EXIT = sube por el camino hasta la puerta y entra.
    // Mientras dura, el Pokemon esta fuera del jardin (en el camino) y no es solido: nadie lo esquiva ni lo empuja.
    private enum class Script { NONE, ENTER, EXIT }
    private enum class Drag { NONE, BALL, FURNITURE, CRITTER, GROUND }
    private enum class Kind { TROUGH, BASKET }
    // Por que lado de un mueble se acerca un Pokemon a comer o beber (FRONT = por abajo, el lado que da a la camara).
    private enum class Side { FRONT, BACK, LEFT, RIGHT }

    /** Un mueble del jardin: un bebedero o una cesta de manzanas. Segun cuanta gente hay en el jardin salen mas (ver
     *  [stationSets]); [index] 0 es el de siempre y los demas se añaden (con un fundido) y se retiran solos. */
    private inner class Piece(val kind: Kind, val index: Int) {
        var base: Pair<Float, Float> = defaultBase(kind, index)   // punto de apoyo en el suelo (centro-abajo) como fraccion del ancho/alto
        val rect = RectF()                                         // el dibujo entero
        val foot = RectF()                                         // su huella en el suelo (lo que nadie atraviesa)
        var apples = BASKET_MAX_APPLES                             // solo las cestas
        var nextApple = 0L
        var spawnAt = 0L
        var leaveAt = 0L                                           // != 0: se esta retirando (fundido de salida) y ya no es solido
        /** Nombre con el que se guarda su posicion: "trough", "basket", "trough2", "basket2"... */
        val key: String get() = (if (kind == Kind.TROUGH) "trough" else "basket") + (if (index == 0) "" else "${index + 1}")
        val w: Float get() = if (kind == Kind.TROUGH) troughW() else basketW()
        val h: Float get() = if (kind == Kind.TROUGH) troughH() else basketH()
    }

    private inner class Critter(val name: String, val shiny: Boolean) {
        var x = -1f; var y = -1f                       // pies del sprite; -1 = aun sin colocar (tamaño de la vista desconocido)
        var dir = DungeonSpriteRepository.Direction.DOWN
        var phase = Phase.IDLE
        var until = 0L
        var task = Task.WANDER
        var real: String? = null                        // accion REAL (PokeWidgetProvider.ACTION_*) que cumple ahora, o null si es de adorno
        var tx = 0f; var ty = 0f
        var jit = 0f                                    // desvio fijo (-0.5..0.5) para no apelotonarse en el mismo punto del mueble
        var side = Side.FRONT                           // lado del mueble por el que come/bebe
        var bestDist = Float.MAX_VALUE; var progressAt = 0L; var lastGx = -1f; var lastGy = -1f   // detector de "no avanza"
        var giveUps = 0
        var walked = 0f
        var kicks = 0
        var doStart = 0L
        var lastHeartAt = 0L                            // ultimo corazoncito que solto mientras hacia una accion
        var blockedSince = 0L                           // desde cuando otro Pokemon le tapa el paso (0 = no esta bloqueado)
        var blockedDist = 0f                            // lo lejos que estaba de su meta cuando empezo a rodear/esperar
        var dirChangedAt = 0L                           // cuando cambio de cara por ultima vez (para no cambiar a cada fotograma)
        var moveX = 0f; var moveY = 0f                  // movimiento reciente suavizado (de ahi sale hacia donde mira al andar)
        var headX = 0f; var headY = 0f                  // rumbo actual (unitario, con inercia); 0,0 = aun sin rumbo
        var ditherAt = 0L; var ditherX = 0f; var ditherY = 0f   // referencia para detectar que anda sin moverse del sitio
        val faceLog = ArrayList<String>()               // solo pruebas: ultimos cambios de cara
        val avoidSign = if (Random.nextBoolean()) 1f else -1f   // lado por el que prefiere esquivar a quien tiene justo delante
        var piece: Piece? = null                        // el bebedero o la cesta al que va (o en el que esta) a beber / comer
        var ball: Ball? = null                          // la pelota con la que juega
        var speedMul = 1f                               // multiplicador de velocidad (corre mas en el pilla-pilla)
        var fleeAt = 0L                                 // pilla-pilla: cuando hay que elegir otro sitio al que huir
        var tagRefAt = 0L; var tagRefX = 0f; var tagRefY = 0f   // pilla-pilla: referencia para saber si lleva rato sin moverse (atrapado)
        var yieldAt = 0L                                // pilla-pilla: cuando puede volver a apartarse para dejar paso a los que corren
        var freezeUntil = 0L                            // pilla-pilla: parado hasta aqui (el que acaba de ser pillado da ventaja al otro)
        var exclaimUntil = 0L                           // pilla-pilla: hasta cuando lleva el "!" de recien pillado
        var called = false                              // el jugador lo ha llamado con varios toques: al llegar se queda un rato mirando al punto
        var calledX = 0f; var calledY = 0f
        var callRetries = 0                             // cuantas veces ha cambiado de sitio por no poder llegar al que le tocaba
        val pending = ArrayDeque<String>()
        // Visitante: Pokemon de paso que NO es del jugador (ver NurseryVisitors): sin estadisticas ni necesidades reales; entra y
        // sale con un fundido. [level] es su nivel de adorno (lo dice su tarjeta).
        var visitor = false
        var level = 0
        var spawnAt = 0L
        var leaveAt = 0L                                // != 0: se esta despidiendo (fundido de salida)
        var leaving = false                             // visitante al que ya le toca irse: anda hacia la puerta de la casa
        var leavingSince = 0L
        var script = Script.NONE                        // visitante entrando por la puerta / saliendo por ella (ver Script)
        var emerged = false                             // visitante que llega: ya ha salido de la casa (antes espera dentro su turno)
        var ghost = false                               // visitante que se va y lleva rato sin poder pasar entre los demas: ya no es solido
        /** ¿Ocupa sitio en el jardin? (no si se esta desvaneciendo, anda por el camino de la puerta o es un visitante que se va y se ha atascado). */
        val solid: Boolean get() = leaveAt == 0L && script == Script.NONE && !ghost
    }

    private class FloatText(val text: String, val x: Float, val y: Float, val start: Long, val until: Long)
    private class Heart(val x: Float, val y: Float, val born: Long, val life: Long, val phase: Float, val scale: Float)
    private class Tap(val x: Float, val y: Float, val at: Long)
    private class Ripple(val x: Float, val y: Float, val born: Long, val big: Boolean)
    private class Puff(val born: Long, val seed: Float)

    private val critters = ArrayList<Critter>()
    private val hearts = ArrayList<Heart>()
    private val taps = ArrayList<Tap>()          // toques recientes sobre el suelo vacio (varios seguidos en un punto = llamar a los Pokemon)
    private val ripples = ArrayList<Ripple>()
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val floats = ArrayList<FloatText>()
    private val puffs = ArrayList<Puff>()
    private var lastPuffAt = 0L

    // Muebles: un bebedero y una cesta de siempre (index 0) y, con mas gente, otro de cada (ver stationSets).
    private val pieces = ArrayList<Piece>().also { it.add(Piece(Kind.TROUGH, 0)); it.add(Piece(Kind.BASKET, 0)) }
    // Huella en el suelo de cada mueble solido (la parte de abajo del dibujo): lo que los Pokemon y la pelota NO atraviesan.
    private val feet = ArrayList<RectF>().also { l -> pieces.forEach { l.add(it.foot) } }
    private val scratch = FloatArray(2)
    // Cuantas veces se ha comido (0..3) y bebido (4..7) por cada lado (Side.ordinal); solo para la autocomprobacion.
    private val sideUse = IntArray(8)
    private var debugFaceLog = false             // solo pruebas: guarda los ultimos cambios de cara de cada Pokemon
    /** Una pelota del jardin (hay dos): se arrastra, se lanza, rebota y los Pokemon van a darle patadas. */
    private inner class Ball { var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var held = false }
    private val balls = listOf(Ball(), Ball())
    private var dragBall: Ball? = null           // la pelota que se esta arrastrando

    /** Pilla-pilla de UNO CONTRA UNO: uno persigue al otro; al pillarlo, ese persigue al primero; al pillarle de vuelta, se acaba. */
    private class TagGame(val players: ArrayList<Critter>, var chaser: Critter, val endsAt: Long) {
        var tags = 0                             // pillados hasta ahora (la partida acaba al segundo: uno a cada uno)
        var immune: Critter? = null              // el que acaba de pillar no puede ser pillado de vuelta al instante
        var immuneUntil = 0L
    }
    private var tag: TagGame? = null
    private var nextTagCheckAt = 0L
    // solo pruebas: visitantes que han salido de la casa hasta el jardin y que han vuelto a entrar en ella
    private var debugEntered = 0; private var debugExited = 0
    var debugNoChurn = false
    // solo pruebas: partidas empezadas, veces que se ha pillado a alguien y partidas que acabaron SIN los dos pillados
    private var debugTagGames = 0; private var debugTags = 0; private var debugTagUnfinished = 0

    // Arrastre en curso.
    private var drag = Drag.NONE
    private var dragDx = 0f; private var dragDy = 0f
    private var downX = 0f; private var downY = 0f
    private var downCritter: Critter? = null
    private var dragPiece: Piece? = null          // mueble que se esta arrastrando
    private var pendingPiece: Piece? = null       // mueble que se arrastra si el toque sobre un Pokemon se convierte en arrastre
    private var tracker: VelocityTracker? = null

    // Fondo propio de la guardería (pixel art 135x300 generado para esta escena: casa con el huevo del juego en
    // el tejado, jardin vallado con claro de tierra, flores y arboles). A pantalla completa, sin suavizar.
    private val bg: Bitmap? = BitmapFactory.decodeResource(resources, R.drawable.bg_guarderia, BitmapFactory.Options().apply { inScaled = false })
    private var bgSc = 1f; private var bgOffX = 0f; private var bgOffY = 0f
    private val troughBmp = makeTroughBitmap()
    private val ballBmp = makeBallBitmap()
    private val heartBmp = makeHeartBitmap()
    private val heartPaint = Paint().apply { isFilterBitmap = false }
    private val bangPaint = Paint().apply { isAntiAlias = false }   // el "!" del pilla-pilla
    private val basketBackBmp = makeBasketBitmap(front = false)
    private val basketLipBmp = makeBasketBitmap(front = true)

    private val density = resources.displayMetrics.density
    private val pixelPaint = Paint().apply { isFilterBitmap = false }       // pixel art nitido (sin suavizar)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7CCBF2.toInt() }
    private val smokePaint = Paint().apply { isAntiAlias = false }
    private val xpFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54A.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val xpEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A2A00.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeWidth = 3f * density }

    /** Se llama al tocar un Pokemon de la escena (especie, shiny). */
    var onCritterTap: ((String, Boolean) -> Unit)? = null

    /** Se llama al tocar un VISITANTE (especie, nivel de adorno): no tiene barras ni cuidados, solo una tarjeta. */
    var onVisitorTap: ((String, Int) -> Unit)? = null

    /** Se llama al soltar un mueble arrastrado: su clave ("trough", "basket", "trough2", "basket2"...) y su nueva posicion
     *  (fracciones del ancho/alto). */
    var onFurnitureMoved: ((String, Float, Float) -> Unit)? = null

    /** Posicion guardada de un mueble por su clave (null = la de por defecto): se usa al aparecer un mueble nuevo. */
    var furnitureSaved: ((String) -> Pair<Float, Float>?)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastTickAt = 0L
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val dt = ((now - lastTickAt).coerceIn(1L, 100L)) / 1000f
            lastTickAt = now
            update(dt, now)
            invalidate()
            handler.postDelayed(this, 33L)
        }
    }

    // ---------------------------------------------------------------- API

    /** Pone en la escena a [members] (los que ya estaban conservan su sitio; los que salen desaparecen). */
    fun setMembers(members: List<Pair<String, Boolean>>) {
        critters.removeAll { c -> !c.visitor && members.none { it.first.equals(c.name, true) && it.second == c.shiny } }
        for ((n, s) in members) {
            if (critters.none { !it.visitor && it.name.equals(n, true) && it.shiny == s }) {
                critters.add(Critter(n.lowercase(), s).also { placeRandomly(it) })
            }
        }
        syncPieces()
        invalidate()
    }

    /** Pone en la escena a los VISITANTES (especie, nivel de adorno). La primera vez los deja ya dentro del jardin; despues, los que
     *  ya no tocan andan hasta la puerta de la casa y entran, y los nuevos salen por ella y bajan por el camino hasta el jardin (nada de
     *  aparecer y desaparecer de golpe). No son del jugador: no se cuidan ni dan XP. [now] solo lo cambian las pruebas (reloj simulado). */
    fun setVisitors(visitors: List<Pair<String, Int>>, now: Long = System.currentTimeMillis()) {
        val wanted = visitors.associate { it.first.lowercase() to it.second }
        val seeding = !visitorsSeeded
        visitorsSeeded = true
        for (c in critters) if (c.visitor && c.leaveAt == 0L && !c.leaving && c.script != Script.EXIT && c.name !in wanted) startLeaving(c, now)
        for ((n, lvl) in wanted) {
            val here = critters.firstOrNull { it.visitor && it.leaveAt == 0L && it.script != Script.EXIT && it.name == n }
            if (here != null) {
                here.level = lvl
                if (here.leaving) { here.leaving = false; here.ghost = false; if (here.script == Script.NONE) { here.phase = Phase.IDLE; here.until = now } }   // se queda
                continue
            }
            critters.add(Critter(n, false).also {
                it.visitor = true; it.level = lvl
                if (seeding || width <= 0) { it.spawnAt = if (seeding) 0L else now; placeRandomly(it) }   // los que ya estaban al abrir: ya dentro
                else startEnter(it, now + ENTER_DELAY_MS)                                                   // los que llegan: salen por la puerta
            })
        }
        syncPieces()
        invalidate()
    }
    private var visitorsSeeded = false
    private var lastEmergeAt = 0L     // cuando salio de la casa el ultimo visitante que llega

    /** Posiciones guardadas del bebedero y de la cesta de siempre (null = las de por defecto). */
    fun setFurniturePositions(troughPos: Pair<Float, Float>?, basketPos: Pair<Float, Float>?) {
        troughPos?.let { p -> pieces.first { it.kind == Kind.TROUGH && it.index == 0 }.base = p }
        basketPos?.let { p -> pieces.first { it.kind == Kind.BASKET && it.index == 0 }.base = p }
        layoutFurniture()
        pieces.forEach { separateFurniture(it) }   // posiciones guardadas de antes pueden solaparse
        invalidate()
    }

    // ---------------------------------------------------------------- mas muebles segun la gente

    /** Cuantos juegos de muebles (un bebedero + una cesta) hacen falta para [n] Pokemon en el jardin: uno hasta 4 y otro mas (dos en
     *  total) con 5 o mas. Nunca mas de dos: el jardin tiene como mucho 8 (6 del jugador + 2 visitantes). */
    private fun stationSets(n: Int): Int = (1 + (n - 1) / STATION_EVERY).coerceIn(1, 2)

    /** Posicion por defecto de un mueble nuevo (fracciones del ancho/alto), repartidos por el jardin para no amontonarse. */
    private fun defaultBase(kind: Kind, index: Int): Pair<Float, Float> = when (kind) {
        Kind.TROUGH -> if (index == 0) 0.265f to 0.472f else 0.73f to 0.84f
        Kind.BASKET -> if (index == 0) 0.76f to 0.581f else 0.27f to 0.78f
    }

    private fun rebuildFeet() {
        feet.clear()
        for (p in pieces) if (p.leaveAt == 0L) feet.add(p.foot)
    }

    /** Pone los muebles que corresponden a la gente que hay ahora: con mas Pokemon, una cesta y un bebedero mas (aparecen con un
     *  fundido); al quedar menos, los de sobra se retiran con otro. Los de siempre nunca se quitan. */
    private fun syncPieces() {
        val now = System.currentTimeMillis()
        val sets = stationSets(critters.count { it.leaveAt == 0L })
        var changed = false
        val added = ArrayList<Piece>()
        for (kind in Kind.values()) {
            val live = pieces.filter { it.kind == kind && it.leaveAt == 0L }
            for (i in live.size until sets) {
                val p = Piece(kind, i)
                p.base = furnitureSaved?.invoke(p.key) ?: p.base
                p.spawnAt = now
                pieces.add(p); added.add(p); changed = true
            }
            for (p in live) if (p.index >= sets) { p.leaveAt = now; changed = true }
        }
        if (!changed) return
        rebuildFeet()
        if (width > 0) {
            layoutFurniture()
            added.forEach { separateFurniture(it) }
        }
    }

    /** Una necesidad REAL que [name]/[shiny] acaba de cubrir (PokeWidgetProvider.ACTION_FEED/PET/WASH): la
     *  escena la representa yendo a la cesta / la pelota / el bebedero. Se encolan como mucho 2 por Pokemon
     *  (tras una ausencia larga habria decenas y no tiene sentido animarlas todas). */
    fun queueCare(name: String, shiny: Boolean, action: String) {
        val c = critters.firstOrNull { !it.visitor && it.name.equals(name, true) && it.shiny == shiny } ?: return
        if (c.pending.size < 2) c.pending.addLast(action)
    }

    fun start() {
        if (running) return
        running = true
        lastTickAt = System.currentTimeMillis()
        // Humo ya en marcha al abrir (varias bocanadas a distintas alturas) en vez de empezar vacio.
        if (puffs.isEmpty()) for (i in 0..6) puffs.add(Puff(lastTickAt - i * PUFF_EVERY_MS, Random.nextFloat()))
        lastPuffAt = lastTickAt
        handler.post(loop)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(loop)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    /** SOLO PARA PRUEBAS: pone en marcha ya una necesidad real de cada tipo en los Pokemon que haya. */
    fun debugQueueSamples() {
        val acts = listOf(PokeWidgetProvider.ACTION_FEED, PokeWidgetProvider.ACTION_WASH, PokeWidgetProvider.ACTION_PET)
        critters.filter { !it.visitor }.forEachIndexed { i, c -> c.pending.addLast(acts[i % acts.size]) }
    }

    /** SOLO PARA PRUEBAS: cambia a los visitantes (los de ahora se van por la puerta y llegan otros distintos). Devuelve cuantos llegan. */
    fun debugChurnVisitors(now: Long = System.currentTimeMillis()): Int {
        val current = critters.filter { it.visitor && it.leaveAt == 0L && !it.leaving && it.script != Script.EXIT }
        if (current.isEmpty()) return 0
        val pool = listOf("eevee", "snorlax", "bulbasaur", "squirtle", "charmander", "pikachu", "mareep", "psyduck", "litten")
        val fresh = pool.filter { n -> current.none { it.name == n } && !DungeonSpriteRepository.frames(context, n, false, DungeonSpriteRepository.Direction.DOWN).isNullOrEmpty() }
            .shuffled().take(current.size)
        setVisitors(fresh.map { it to 10 }, now)
        return fresh.size
    }

    /** SOLO PARA PRUEBAS: estado de los visitantes (donde estan, si se van, si andan por el camino de la puerta) y cuantos han salido y entrado. */
    fun debugVisitorState(): String {
        val v = critters.filter { it.visitor }
        return "visitantes: " + (if (v.isEmpty()) "ninguno" else v.joinToString { "${it.name}(${it.x.toInt()},${it.y.toInt()}) ${it.script}${if (it.leaving) " yendose" else ""}${if (it.leaveAt != 0L) " desvaneciendo" else ""}" }) +
            "; puerta (${doorX().toInt()},${doorY().toInt()}), jardin desde y=${floorTop().toInt()}; salieron de la casa $debugEntered, entraron en ella $debugExited"
    }

    /** SOLO PARA PRUEBAS: arrastra "trough" / "basket" / "ball" hasta (toFx, toFy) (fracciones del ancho/alto) con eventos
     *  tactiles SINTETICOS entregados solo a esta vista (nada del sistema): el mismo camino que un dedo de verdad, asi se
     *  prueba el arrastre, el guardado de la posicion y, con la pelota, el lanzamiento y la carrera de los Pokemon. */
    fun debugDrag(kind: String, toFx: Float, toFy: Float) {
        if (width <= 0) return
        val (sx, sy) = when (kind) {
            "trough" -> pieces.first { it.kind == Kind.TROUGH && it.index == 0 }.rect.let { it.centerX() to it.centerY() }
            "basket" -> pieces.first { it.kind == Kind.BASKET && it.index == 0 }.rect.let { it.centerX() to it.centerY() }
            "ball2" -> balls[1].let { it.x to (it.y - ballSize() * 0.4f) }
            else -> balls[0].let { it.x to (it.y - ballSize() * 0.4f) }
        }
        val ex = toFx * width; val ey = toFy * height
        val t0 = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, t: Long, x: Float, y: Float) {
            val ev = MotionEvent.obtain(t0, t, action, x, y, 0)
            dispatchTouchEvent(ev); ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, t0, sx, sy)
        val steps = 8
        for (i in 1..steps) { val f = i / steps.toFloat(); send(MotionEvent.ACTION_MOVE, t0 + i * 16L, sx + (ex - sx) * f, sy + (ey - sy) * f) }
        send(MotionEvent.ACTION_UP, t0 + steps * 16L + 8L, ex, ey)
    }

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_NURSERY_COLLISION): simula [seconds] segundos de escena SIN
     *  dibujar (reloj simulado, lanzando la pelota de vez en cuando) y comprueba que ningun Pokemon ni la pelota entra en la
     *  huella de un mueble, que nadie se queda atascado andando y por que lados se come y se bebe. [onSideFrame] recibe una
     *  captura (etiqueta, imagen) las primeras veces que alguien come o bebe por un lado que no es el de abajo. La vista
     *  debe tener ya tamaño y Pokemon. */
    fun debugCollisionCheck(seconds: Int, onSideFrame: (String, Bitmap) -> Unit): String {
        if (width <= 0 || critters.isEmpty()) return "vista sin tamaño o sin Pokemon"
        java.util.Arrays.fill(sideUse, 0)
        debugFaceLog = true
        var simNow = System.currentTimeMillis()
        val dt = 0.033f
        val steps = (seconds / dt).toInt()
        var insideCritter = 0; var insideBall = 0; var stuck = 0; var throws = 0; var shots = 0
        // Estado limpio: cesta llena (con el reloj simulado el rellenado de una prueba anterior no cuenta) y todos parados.
        for (p in pieces) { p.apples = BASKET_MAX_APPLES; p.nextApple = 0L }
        for (c in critters) { c.phase = Phase.IDLE; c.until = 0L; c.pending.clear(); c.real = null; c.giveUps = 0; c.piece = null; c.task = Task.WANDER; c.speedMul = 1f }
        tag = null; nextTagCheckAt = 0L; debugTagGames = 0; debugTags = 0; debugTagUnfinished = 0
        debugEntered = 0; debugExited = 0
        var churns = 0
        class Smp(var x: Float, var y: Float, var path: Float, val at: Long, val tx: Float, val ty: Float, var lx: Float, var ly: Float) { val trace = StringBuilder(); var lastTrace = 0L; val pts = ArrayList<Float>() }
        val sample = HashMap<Critter, Smp>()
        var oscillating = 0
        var overlapping = 0
        var briefOverlaps = 0
        val overlapRun = HashMap<Long, Int>()
        var flicker = 0                                  // veces que alguien cambia de cara 6 o mas veces en 2 s (el "izquierda-derecha muy rapido")
        var faceChanges = 0
        val lastDir = HashMap<Critter, DungeonSpriteRepository.Direction>()
        val dirChanges = HashMap<Critter, ArrayList<Long>>()
        val overlapNotes = ArrayList<String>()
        val stuckNotes = ArrayList<String>()
        val shot = BooleanArray(8)
        val rx = critterRx() - 1.5f; val ry = critterRy() - 1.5f; val rb = ballR() - 1.5f
        for (i in 0 until steps) {
            simNow += 33L
            if (i % 270 == 0) {   // cada ~9 s una de las dos pelotas (por turnos) sale lanzada con fuerza hacia una direccion al azar
                val a = Random.nextFloat() * 2f * PI.toFloat()
                val b = balls[(i / 270) % balls.size]
                b.vx = cos(a) * width * 1.6f; b.vy = sin(a) * width * 1.6f; throws++
                sendFetchers(b)
            }
            // Cada ~50 s (tiempo simulado) cambian los visitantes: los de ahora se van por la puerta y llegan otros (ver setVisitors).
            if (!debugNoChurn && i % 1500 == 600 && debugChurnVisitors(simNow) > 0) churns++
            update(dt, simNow)
            // Hitbox entre Pokemon: ningun par puede quedar solapado de forma que se vea. Dos que andan a la vez pueden rozarse UN
            // fotograma (cada uno comprueba el paso contra la posicion anterior del otro) y se separan en el siguiente: eso no cuenta;
            // cuenta un solape que dura mas de 6 fotogramas seguidos (~0,2 s).
            val touching = HashSet<Long>()
            for (ia in 0 until critters.size) {
                val a = critters[ia]; if (a.x < 0f || !a.solid) continue
                for (ib in ia + 1 until critters.size) {
                    val b = critters[ib]; if (b.x < 0f || !b.solid) continue
                    val dd = bodyDist(b.x - a.x, b.y - a.y)
                    if (dd >= 0.92f) continue
                    briefOverlaps++
                    val key = ia * 1000L + ib
                    touching.add(key)
                    val run = (overlapRun[key] ?: 0) + 1
                    overlapRun[key] = run
                    if (run == 6) { overlapping++; if (overlapNotes.size < 3) overlapNotes.add("${a.name} y ${b.name} solapados 6 fotogramas seguidos, a $dd (${a.phase}/${b.phase})") }
                }
            }
            overlapRun.keys.retainAll(touching)
            for (c in critters) {
                if (c.x < 0f || c.script != Script.NONE) { sample.remove(c); continue }   // el que anda por el camino de la puerta va en linea recta fuera del jardin
                val prevDir = lastDir.put(c, c.dir)
                if (prevDir != null && prevDir != c.dir) {
                    faceChanges++
                    val q = dirChanges.getOrPut(c) { ArrayList() }
                    q.add(simNow); q.removeAll { simNow - it > 2000L }
                    if (q.size >= 6) { flicker++; q.clear(); if (overlapNotes.size < 6) overlapNotes.add("${c.name} cambia de cara sin parar (${c.phase}, ${c.task}) en (${c.x.toInt()},${c.y.toInt()}) meta (${c.tx.toInt()},${c.ty.toInt()}) a ${hypot(c.tx - c.x, c.ty - c.y).toInt()} px, bloqueado=${c.blockedSince != 0L}, llamado=${c.called}, cara ${c.dir}; ultimos cambios: ${c.faceLog.joinToString(" | ")}; vecinos ${critters.filter { it !== c && it.x >= 0f }.filter { hypot(it.x - c.x, it.y - c.y) < 400f }.joinToString { "${it.name}(${it.x.toInt()},${it.y.toInt()},${it.phase})" }}") }
                }
                if (feet.any { insideFoot(it, rx, ry, c.x, c.y) }) insideCritter++
                if (c.phase == Phase.WALK) {
                    // Ir y venir: en 6 s andando recorre mucho camino pero acaba casi donde estaba (lo que se vio con Amaura).
                    val smp = sample.getOrPut(c) { Smp(c.x, c.y, 0f, simNow, c.tx, c.ty, c.x, c.y) }
                    smp.path += hypot(c.x - smp.lx, c.y - smp.ly); smp.lx = c.x; smp.ly = c.y   // camino REAL recorrido (no el intentado)
                    if (simNow - smp.lastTrace >= 400L) { smp.lastTrace = simNow; smp.trace.append("(${c.x.toInt()},${c.y.toInt()}) "); smp.pts.add(c.x); smp.pts.add(c.y) }
                    if (simNow - smp.at >= 6000L) {
                        val path = smp.path; val disp = hypot(c.x - smp.x, c.y - smp.y)
                        // Solo cuenta si la meta no se ha movido (la pelota rodando si: perseguirla es ir y venir de verdad).
                        val staticTarget = hypot(c.tx - smp.tx, c.ty - smp.ty) < 60f * density
                        val detail = "${c.name} ${c.task} en (${c.x.toInt()},${c.y.toInt()}) objetivo (${c.tx.toInt()},${c.ty.toInt()}) recorrio ${path.toInt()} px y acabo a ${disp.toInt()} px de donde estaba; " +
                            "pelotas ${balls.joinToString { "(${it.x.toInt()},${it.y.toInt()})" }} muebles ${pieces.filter { it.leaveAt == 0L }.joinToString { "${it.key} ${it.foot.toShortString()}" }}"
                        // Ir y venir de verdad: la direccion se invierte (mas de 120 grados) varias veces seguidas; dar una vuelta
                        // entera a un mueble son giros de 90 grados y no cuenta.
                        var flips = 0
                        for (q in 2 until smp.pts.size / 2) {
                            val ax = smp.pts[q * 2 - 2] - smp.pts[q * 2 - 4]; val ay = smp.pts[q * 2 - 1] - smp.pts[q * 2 - 3]
                            val bx = smp.pts[q * 2] - smp.pts[q * 2 - 2]; val by = smp.pts[q * 2 + 1] - smp.pts[q * 2 - 1]
                            val la = hypot(ax, ay); val lb = hypot(bx, by)
                            if (la > 15f * density && lb > 15f * density && (ax * bx + ay * by) / (la * lb) < -0.5f) flips++
                        }
                        // (En el pilla-pilla la meta cambia por diseño y quien esta acorralado corre de un lado a otro: no cuenta; los atrapados de
                        // verdad ya salen solos de la partida.)
                        if (staticTarget && c.task != Task.TAG && flips >= 2) {
                            oscillating++
                            if (stuckNotes.size < 4) stuckNotes.add("va y viene: $detail trayectoria ${smp.trace}")
                        } else if (staticTarget && c.blockedSince == 0L && path < width * 0.1f && hypot(c.tx - c.x, c.ty - c.y) > width * 0.12f) {
                            // (si esta esperando a que otro Pokemon le deje el sitio no es "pillado": es no atravesarlo)
                            stuck++   // andando seis segundos hacia una meta fija sin moverse del sitio: "caminando pillado"
                            if (stuckNotes.size < 4) stuckNotes.add("pillado: $detail")
                        }
                        sample.remove(c)
                    }
                } else sample.remove(c)
                if (c.phase == Phase.DO && (c.task == Task.EAT || c.task == Task.DRINK) && c.side != Side.FRONT) {
                    val k = (if (c.task == Task.EAT) 0 else 4) + c.side.ordinal
                    if (!shot[k] && shots < 3 && simNow - c.doStart > 700L) {
                        shot[k] = true; shots++
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        draw(Canvas(bmp))
                        onSideFrame("${if (c.task == Task.EAT) "come" else "bebe"}_${c.side.name.lowercase()}", bmp)
                    }
                }
            }
            for (b in balls) if (feet.any { insideFoot(it, rb, rb, b.x, b.y) }) insideBall++
        }
        fun sides(off: Int) = Side.values().joinToString(" ") { "${it.name.lowercase()}=${sideUse[off + it.ordinal]}" }
        val ok = insideCritter == 0 && insideBall == 0 && stuck == 0 && oscillating == 0 && overlapping == 0 && flicker == 0
        return "${steps} pasos (${seconds} s simulados, $throws lanzamientos de pelota, $debugTagGames partidas de pilla-pilla con $debugTags pillados, $debugTagUnfinished sin acabar):${if (ok) "TODO CORRECTO" else "PROBLEMAS"} - " +
            "Pokemon dentro de un mueble: $insideCritter, pelota dentro de un mueble: $insideBall, andando sin moverse del sitio: $stuck, " +
            "yendo y viniendo sin avanzar: $oscillating, Pokemon solapados entre si: $overlapping (roces de un solo fotograma: $briefOverlaps), cambios de cara sin parar: $flicker (en total ${"%.1f".format(faceChanges * 60.0 / (seconds * critters.size.coerceAtLeast(1)))} cambios por Pokemon y minuto); " +
            "comer por lado: ${sides(0)}; beber por lado: ${sides(4)}; cambios de visitantes: $churns (salieron de la casa $debugEntered, entraron en ella $debugExited, siguen yendo o viniendo ${critters.count { it.visitor && (it.leaving || it.script != Script.NONE) }})" +
            (if (!ok) System.lineSeparator() + "  " + debugVisitorState() else "") +
            overlapNotes.joinToString("") { System.lineSeparator() + "  ! solape: " + it } +
            stuckNotes.joinToString("") { System.lineSeparator() + "  ! atascado: " + it }
    }

    // ---------------------------------------------------------------- puerta de la casa (entrada y salida de los visitantes)

    // La puerta de la casa de bg_guarderia (pixeles del dibujo, 135x300) y el camino de grava que baja hasta el jardin por el hueco de la valla.
    private fun doorX() = if (bg != null) bgOffX + DOOR_X * bgSc else width * 0.5f
    private fun doorY() = min(if (bg != null) bgOffY + DOOR_Y * bgSc else height * 0.26f, floorTop() - height * 0.04f)

    /** Donde el camino llega al jardin: un sitio de pie, lo mas cerca posible del centro del camino. */
    private fun gardenGate(out: FloatArray) {
        val x = doorX().coerceIn(floorLeft(), floorRight()); val y = floorTop() + critterRy() * 2f
        out[0] = x; out[1] = y
        if (standable(x, y)) return
        val step = critterSize() * 0.12f
        for (ring in 1..25) for (k in 0 until 8 * ring) {
            val ang = k * (2f * PI.toFloat() / (8 * ring))
            val px = x + cos(ang) * ring * step; val py = y + sin(ang) * ring * step * 0.6f
            if (standable(px, py)) { out[0] = px; out[1] = py; return }
        }
    }

    /** Un visitante nuevo sale de la casa: aparece en la puerta (tras [at], para que dos no salgan a la vez) y baja por el camino. */
    private fun startEnter(c: Critter, at: Long) {
        c.x = doorX(); c.y = doorY()
        c.script = Script.ENTER; c.phase = Phase.WALK; c.task = Task.WANDER
        c.dir = DungeonSpriteRepository.Direction.DOWN
        c.emerged = false
        c.spawnAt = at
    }

    /** A un visitante le toca irse: deja lo que hacia y anda hasta el camino; alli sube por el y entra en la casa. */
    private fun startLeaving(c: Critter, now: Long) {
        c.leaving = true; c.leavingSince = now
        if (c.script == Script.ENTER) return   // aun esta saliendo de la casa: al llegar al jardin dara la vuelta (ver stepScript)
        c.real = null; c.called = false; c.pending.clear(); c.giveUps = 0; c.piece = null
        c.task = Task.WANDER; c.speedMul = 1f      // (si jugaba al pilla-pilla, sale de la partida)
        headToDoor(c, now)
    }

    private fun headToDoor(c: Critter, now: Long) {
        gardenGate(scratch)
        c.tx = scratch[0]; c.ty = scratch[1]
        c.task = Task.WANDER; c.phase = Phase.WALK
        c.bestDist = Float.MAX_VALUE; c.progressAt = now; c.blockedSince = 0L; c.headX = 0f; c.headY = 0f
    }

    /** Un fotograma de un visitante que anda por el camino de la puerta (en linea recta, sin esquivar a nadie: esta fuera del jardin). */
    private fun stepScript(c: Critter, dt: Float, now: Long) {
        if (c.script == Script.ENTER && !c.emerged) {
            // Dentro de la casa: no sale hasta que se hayan ido los que se van (primero se despiden y "al rato" llegan los nuevos), y de uno en uno.
            if (c.leaving) { c.script = Script.NONE; c.leaveAt = now - VISITOR_FADE_MS; return }   // ya no hace falta: ni llego a salir
            if (now < c.spawnAt || now - lastEmergeAt < ENTER_STAGGER_MS || critters.any { it !== c && it.visitor && it.leaving && it.script != Script.ENTER }) return
            c.emerged = true; c.spawnAt = now; lastEmergeAt = now
        }
        val toGarden = c.script == Script.ENTER
        gardenGate(scratch)
        val tx = if (toGarden) scratch[0] else doorX()
        val ty = if (toGarden) floorTop() + 2f else doorY()
        val dx = tx - c.x; val dy = ty - c.y
        val d = hypot(dx, dy)
        val step = speed() * dt
        if (d > step) {
            c.x += dx / d * step; c.y += dy / d * step
            c.walked += step
            c.dir = if (abs(dx) > abs(dy) * 1.25f) { if (dx > 0f) DungeonSpriteRepository.Direction.RIGHT else DungeonSpriteRepository.Direction.LEFT }
            else { if (dy > 0f) DungeonSpriteRepository.Direction.DOWN else DungeonSpriteRepository.Direction.UP }
            c.phase = Phase.WALK
            return
        }
        c.x = tx; c.y = ty
        if (toGarden) {
            c.script = Script.NONE; debugEntered++
            if (c.leaving) { startLeaving(c, now); return }   // le tocaba irse antes de llegar: da la vuelta
            c.phase = Phase.IDLE; c.until = now + Random.nextLong(500L, 1500L)
            ensureStandable(c)
        } else {
            c.script = Script.NONE; debugExited++
            c.leaveAt = now - VISITOR_FADE_MS   // ya dentro de la casa: se quita en este mismo fotograma
        }
    }

    // ---------------------------------------------------------------- geometria

    // El jardin vallado de bg_guarderia (pantalla completa): lo que queda dentro de las vallas y por
    // debajo de la puerta de la casa. Fracciones del alto/ancho de la vista.
    private fun floorLeft() = width * 0.115f
    private fun floorRight() = width * 0.885f
    private fun floorTop() = height * 0.385f
    private fun floorBottom() = height * 0.915f
    private fun critterSize() = width * 0.23f
    private fun ballSize() = width * 0.07f
    private fun speed() = width * 0.15f   // px/s

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bg?.let { b ->
            bgSc = max(w / b.width.toFloat(), h / b.height.toFloat())
            bgOffX = (w - b.width * bgSc) / 2f
            bgOffY = (h - b.height * bgSc) / 2f
        }
        layoutFurniture()
        pieces.forEach { separateFurniture(it) }
        // Las dos pelotas empiezan en sitios distintos del claro de tierra.
        if (balls[0].x == 0f && balls[0].y == 0f) { balls[0].x = w * 0.45f; balls[0].y = h * 0.66f }
        if (balls[1].x == 0f && balls[1].y == 0f) { balls[1].x = w * 0.62f; balls[1].y = h * 0.74f }
        critters.forEach { c -> if (c.x < 0f) placeRandomly(c) else { c.x = c.x.coerceIn(floorLeft(), floorRight()); c.y = c.y.coerceIn(floorTop(), floorBottom()) } }
    }

    // Muebles mas pequeños (pedido del usuario: antes 0,27 y 0,24 del ancho): dejan mas sitio libre a los Pokemon y a la pelota.
    private fun troughW() = width * 0.19f
    private fun troughH() = troughW() * troughBmp.height / troughBmp.width
    private fun basketW() = width * 0.17f
    private fun basketH() = basketW() * basketBackBmp.height / basketBackBmp.width

    /** Un mueble no puede salirse del jardin: el punto de apoyo se queda donde el rectangulo cabe dentro. */
    private fun clampBase(base: Pair<Float, Float>, w: Float, h: Float): Pair<Float, Float> {
        if (width <= 0) return base
        val minX = (floorLeft() + w / 2f) / width; val maxX = (floorRight() - w / 2f) / width
        val minY = (floorTop() + h) / height; val maxY = floorBottom() / height
        return base.first.coerceIn(minX, max(minX, maxX)) to base.second.coerceIn(minY, max(minY, maxY))
    }

    private fun layoutFurniture() {
        if (width <= 0) return
        for (p in pieces) {
            val w = p.w; val h = p.h
            p.base = clampBase(p.base, w, h)
            p.rect.set(p.base.first * width - w / 2f, p.base.second * height - h, p.base.first * width + w / 2f, p.base.second * height)
            if (p.kind == Kind.TROUGH) p.foot.set(p.rect.left + p.rect.width() * 0.04f, p.rect.top + p.rect.height() * 0.42f, p.rect.right - p.rect.width() * 0.04f, p.rect.bottom)
            else p.foot.set(p.rect.left + p.rect.width() * 0.07f, p.rect.top + p.rect.height() * 0.50f, p.rect.right - p.rect.width() * 0.07f, p.rect.bottom)
        }
    }

    private fun placeRandomly(c: Critter) {
        if (width <= 0) return
        // Un sitio libre: fuera de los muebles (y no en un hueco entre dos donde no cabe) y sin solaparse con nadie (hasta 40
        // intentos; si no hay hueco, el ultimo).
        for (attempt in 0 until 40) {
            c.x = floorLeft() + Random.nextFloat() * (floorRight() - floorLeft())
            c.y = floorTop() + Random.nextFloat() * (floorBottom() - floorTop())
            scratch[0] = c.x; scratch[1] = c.y
            clearOfFurniture(scratch, critterRx(), critterRy())
            c.x = scratch[0]; c.y = scratch[1]
            if (standable(c.x, c.y) && critters.none { o -> o !== c && o.x >= 0f && o.solid && bodyDist(o.x - c.x, o.y - c.y) < 1.15f }) break
        }
        c.until = System.currentTimeMillis() + Random.nextLong(300L, 1500L)
    }

    /** Si [c] ha quedado donde no se puede estar de pie (p. ej. en el hueco que dejan dos muebles muy juntos, del que "empujarlo
     *  fuera" de uno lo mete en el otro), lo lleva al sitio libre mas cercano (busqueda en espiral). */
    private fun ensureStandable(c: Critter) {
        if (c.x < 0f || standable(c.x, c.y)) return
        val step = critterSize() * 0.12f
        for (ring in 1..25) for (k in 0 until 8 * ring) {
            val ang = k * (2f * PI.toFloat() / (8 * ring))
            val x = c.x + cos(ang) * ring * step; val y = c.y + sin(ang) * ring * step * 0.6f
            if (standable(x, y)) { c.x = x; c.y = y; return }
        }
    }

    // ---------------------------------------------------------------- hitbox entre Pokemon

    // Huella en el suelo de cada Pokemon (elipse: ancha y poco profunda, como se ve en esta perspectiva): dos Pokemon se solapan
    // cuando sus huellas se tocan. Nadie atraviesa a nadie: ni al andar (rodea) ni al aparecer o al mover un mueble (se les separa).
    // (El sprite se dibuja alto: con una huella poco profunda dos Pokemon "separados" se veian uno encima del otro, asi que la huella
    // es bastante honda: ~0,4 de alto de Pokemon de separacion minima en vertical y ~0,56 de ancho en horizontal.)
    private fun bodyRx() = critterSize() * 0.28f
    private fun bodyRy() = critterSize() * 0.20f

    /** Distancia normalizada entre las huellas de dos Pokemon cuyos pies estan a ([dx], [dy]): menos de 1 = solapados. */
    private fun bodyDist(dx: Float, dy: Float): Float = hypot(dx / (2f * bodyRx()), dy / (2f * bodyRy()))

    /** ¿Puede [c] pasar a ([nx], [ny]) sin meterse mas en la huella de otro Pokemon? (Si ya estaban solapados, moverse para
     *  separarse esta permitido: asi nunca se queda atrapado dentro de otro.) */
    private fun bodyFree(c: Critter, nx: Float, ny: Float): Boolean = bodyBlocker(c, nx, ny) == null

    /** El Pokemon que le impide a [c] pasar a ([nx], [ny]) (se meteria mas en su huella), o null si el paso esta libre. */
    private fun bodyBlocker(c: Critter, nx: Float, ny: Float): Critter? {
        for (o in critters) {
            if (o === c || o.x < 0f || !o.solid) continue
            val after = bodyDist(o.x - nx, o.y - ny)
            if (after >= 1f) continue
            if (after >= bodyDist(o.x - c.x, o.y - c.y)) continue
            return o
        }
        return null
    }

    /** Cambia la cara de [c] hacia donde se mueve ([hx], [hy]) con histeresis (cambiar de eje exige que el nuevo domine claramente) y un
     *  tiempo minimo entre cambios: sin esto, un Pokemon que esquiva a otro o pelea con un mueble giraba de izquierda a derecha cada
     *  fotograma. */
    private fun faceMove(c: Critter, hx: Float, hy: Float, now: Long, minLen: Float) {
        if (hypot(hx, hy) < minLen) return   // sin un movimiento claro (esperando, o yendo y viniendo) no cambia de cara
        val horizontal = c.dir == DungeonSpriteRepository.Direction.LEFT || c.dir == DungeonSpriteRepository.Direction.RIGHT
        val wantsHorizontal = if (horizontal) abs(hx) > abs(hy) * 0.8f else abs(hx) > abs(hy) * 1.25f
        val want = if (wantsHorizontal) { if (hx > 0f) DungeonSpriteRepository.Direction.RIGHT else DungeonSpriteRepository.Direction.LEFT }
        else { if (hy > 0f) DungeonSpriteRepository.Direction.DOWN else DungeonSpriteRepository.Direction.UP }
        if (want == c.dir || now - c.dirChangedAt < FACE_DWELL_MS) return
        if (debugFaceLog) { c.faceLog.add("${c.dir}->$want ema=(${hx.toInt()},${hy.toInt()}) pos=(${c.x.toInt()},${c.y.toInt()}) meta=(${c.tx.toInt()},${c.ty.toInt()})"); if (c.faceLog.size > 8) c.faceLog.removeAt(0) }
        c.dir = want; c.dirChangedAt = now
    }

    /** Separa a los Pokemon solapados: cada par se aparta a lo largo de la linea que los une; el que anda cede mas que el
     *  que esta quieto (comiendo, bebiendo o parado). Se repite cada fotograma, asi que un solape (alguien que aparece, un
     *  mueble movido encima) se deshace casi al instante. */
    private fun separateCritters() {
        val sx = 2f * bodyRx(); val sy = 2f * bodyRy()
        for (i in 0 until critters.size) {
            val a = critters[i]; if (a.x < 0f || !a.solid) continue
            for (j in i + 1 until critters.size) {
                val b = critters[j]; if (b.x < 0f || !b.solid) continue
                var ux = (b.x - a.x) / sx; var uy = (b.y - a.y) / sy
                val d = hypot(ux, uy)
                if (d >= 1f) continue
                if (d < 0.001f) { val ang = Random.nextFloat() * 2f * PI.toFloat(); ux = cos(ang); uy = sin(ang) } else { ux /= d; uy /= d }
                val overlap = 1f - d
                val ma = if (a.phase == Phase.WALK) 1f else 0.15f; val mb = if (b.phase == Phase.WALK) 1f else 0.15f
                val shareA = ma / (ma + mb)
                val px = ux * sx * overlap; val py = uy * sy * overlap
                a.x -= px * shareA; a.y -= py * shareA
                b.x += px * (1f - shareA); b.y += py * (1f - shareA)
            }
        }
        for (c in critters) if (c.x >= 0f && c.script == Script.NONE) { c.x = c.x.coerceIn(floorLeft(), floorRight()); c.y = c.y.coerceIn(floorTop(), floorBottom()) }
    }

    // ---------------------------------------------------------------- colisiones con los muebles

    // "Cuerpo" de quien camina sobre la huella de un mueble: el punto de los pies se ensancha en (rx, ry).
    private fun critterRx() = critterSize() * 0.38f
    private fun critterRy() = critterSize() * 0.05f
    private fun ballR() = ballSize() * 0.45f

    /** Si el punto [p] (x, y) cae dentro de [foot] ensanchada en ([rx], [ry]), lo saca por el lado mas cercano que siga
     *  dentro del jardin y devuelve ese lado (1 izquierda, 2 derecha, 3 arriba, 4 abajo); 0 si ya estaba fuera. */
    private fun pushOutOf(foot: RectF, rx: Float, ry: Float, p: FloatArray): Int {
        val l = foot.left - rx; val r = foot.right + rx; val t = foot.top - ry; val b = foot.bottom + ry
        val x = p[0]; val y = p[1]
        if (x <= l || x >= r || y <= t || y >= b) return 0
        // Distancia que hay que recorrer por cada lado; los que sacan al punto del jardin se descartan (salvo que no quede otro).
        val cost = floatArrayOf(x - l, r - x, y - t, b - y)
        val ok = booleanArrayOf(l >= floorLeft(), r <= floorRight(), t >= floorTop(), b <= floorBottom())
        var best = -1
        for (i in 0..3) if (ok[i] && (best < 0 || cost[i] < cost[best])) best = i
        if (best < 0) { best = 0; for (i in 1..3) if (cost[i] < cost[best]) best = i }
        when (best) { 0 -> p[0] = l; 1 -> p[0] = r; 2 -> p[1] = t; else -> p[1] = b }
        return best + 1
    }

    /** Saca [p] de todos los muebles (dos pasadas por si al salir de uno cae dentro del otro). Devuelve el ultimo lado usado. */
    private fun clearOfFurniture(p: FloatArray, rx: Float, ry: Float): Int {
        var side = 0
        repeat(2) { for (foot in feet) { val s = pushOutOf(foot, rx, ry, p); if (s != 0) side = s } }
        return side
    }

    private fun insideFoot(foot: RectF, rx: Float, ry: Float, x: Float, y: Float) =
        x > foot.left - rx && x < foot.right + rx && y > foot.top - ry && y < foot.bottom + ry

    /** ¿El segmento (x0,y0)-(x1,y1) atraviesa de verdad [foot] ensanchada? (rozar el borde no cuenta). Liang-Barsky. */
    private fun segmentHits(foot: RectF, rx: Float, ry: Float, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val l = foot.left - rx + 1f; val r = foot.right + rx - 1f; val t = foot.top - ry + 1f; val b = foot.bottom + ry - 1f
        if (l >= r || t >= b) return false
        var t0 = 0f; var t1 = 1f
        val dx = x1 - x0; val dy = y1 - y0
        fun clip(p: Float, q: Float): Boolean {
            if (p == 0f) return q > 0f
            val k = q / p
            if (p < 0f) { if (k > t1) return false; if (k > t0) t0 = k } else { if (k < t0) return false; if (k < t1) t1 = k }
            return true
        }
        return clip(-dx, x0 - l) && clip(dx, r - x0) && clip(-dy, y0 - t) && clip(dy, b - y0) && t0 < t1
    }

    private fun pathBlocked(rx: Float, ry: Float, x0: Float, y0: Float, x1: Float, y1: Float) =
        feet.any { segmentHits(it, rx, ry, x0, y0, x1, y1) }

    // Grafo de visibilidad para rodear los muebles: inicio, meta y las 8 esquinas (2 muebles x 4). Fijos para no crear basura.
    // (inicio + meta + 4 esquinas por mueble: con hasta 3 juegos de muebles son 14; se deja margen)
    private val nodeX = FloatArray(32); private val nodeY = FloatArray(32)
    private val nodeDist = FloatArray(32); private val nodePrev = IntArray(32); private val nodeDone = BooleanArray(32)
    private val nodePath = IntArray(32)
    private val hop = FloatArray(2)

    private var pathDirect = true
    private var pathLen = 0f

    /** Camino mas corto de ([x],[y]) a ([gx],[gy]) rodeando los muebles (ver [nextHop]). Deja en [pathLen] su longitud y en
     *  los nodos el camino (si [pathDirect] es false); devuelve false si la meta no se puede alcanzar. */
    private fun shortestPath(x: Float, y: Float, gx: Float, gy: Float): Boolean {
        val rx = critterRx(); val ry = critterRy()
        if (!pathBlocked(rx, ry, x, y, gx, gy)) { pathDirect = true; pathLen = hypot(gx - x, gy - y); return true }
        pathDirect = false
        val eps = 2.5f * density
        nodeX[0] = x; nodeY[0] = y; nodeX[1] = gx; nodeY[1] = gy
        var n = 2
        for (foot in feet) {
            val l = foot.left - rx - eps; val r = foot.right + rx + eps; val t = foot.top - ry - eps; val b = foot.bottom + ry + eps
            for (k in 0 until 4) {
                val cx = if (k % 2 == 0) l else r; val cy = if (k < 2) t else b
                if (cx < floorLeft() || cx > floorRight() || cy < floorTop() || cy > floorBottom()) continue   // fuera del jardin
                if (feet.any { insideFoot(it, rx, ry, cx, cy) }) continue                                      // dentro del OTRO mueble
                nodeX[n] = cx; nodeY[n] = cy; n++
            }
        }
        java.util.Arrays.fill(nodeDist, 0, n, Float.MAX_VALUE); java.util.Arrays.fill(nodeDone, 0, n, false)
        nodeDist[0] = 0f
        while (true) {
            var u = -1
            for (i in 0 until n) if (!nodeDone[i] && nodeDist[i] < Float.MAX_VALUE && (u < 0 || nodeDist[i] < nodeDist[u])) u = i
            if (u < 0 || u == 1) break
            nodeDone[u] = true
            for (v in 0 until n) {
                if (nodeDone[v]) continue
                val d = nodeDist[u] + hypot(nodeX[v] - nodeX[u], nodeY[v] - nodeY[u])
                if (d >= nodeDist[v]) continue
                if (pathBlocked(rx, ry, nodeX[u], nodeY[u], nodeX[v], nodeY[v])) continue
                nodeDist[v] = d; nodePrev[v] = u
            }
        }
        if (nodeDist[1] == Float.MAX_VALUE) return false
        pathLen = nodeDist[1]
        return true
    }

    /** Siguiente punto al que ir desde ([x],[y]) para llegar a ([gx],[gy]) sin atravesar los muebles: si el camino recto esta
     *  libre, la propia meta; si no, el primer tramo del CAMINO MAS CORTO que rodea los muebles por sus esquinas (Dijkstra
     *  sobre inicio, meta y las 8 esquinas, que caen algo por fuera de la huella). Se recalcula en cada fotograma y no
     *  guarda nada: seguir el primer tramo de un camino mas corto sigue siendo un camino mas corto, asi que no puede ir y
     *  venir entre dos rodeos. Resultado en [hop]. Devuelve false si la meta no se puede alcanzar (encerrada entre los
     *  muebles o dentro de uno): quien camina debe dejarla en vez de empujar contra el mueble. */
    private fun nextHop(x: Float, y: Float, gx: Float, gy: Float): Boolean {
        hop[0] = gx; hop[1] = gy
        if (!shortestPath(x, y, gx, gy)) return false
        if (pathDirect) return true
        // Camino de la meta hacia atras; se avanza desde el inicio hasta el primer nodo que NO este ya debajo de los pies
        // (una esquina en la que ya esta sigue formando parte del camino, pero hay que ir a la siguiente: si se descartara
        // el nodo del grafo, cerca de la esquina saldria otro camino mas largo y al alejarse volveria el corto - ir y venir).
        var len = 0
        var v = 1
        while (v != 0) { nodePath[len++] = v; v = nodePrev[v] }
        val arrived = max(4f, 3f * density)
        var k = len - 1
        while (k > 0 && hypot(nodeX[nodePath[k]] - x, nodeY[nodePath[k]] - y) <= arrived) k--
        v = nodePath[k]
        hop[0] = nodeX[v]; hop[1] = nodeY[v]
        return true
    }

    /** Punto donde se coloca [c] para usar el mueble [foot] por su lado [Critter.side] (justo fuera de la huella). */
    private fun spotFor(foot: RectF, c: Critter, out: FloatArray) {
        val gap = width * 0.02f
        when (c.side) {
            Side.FRONT -> { out[0] = foot.centerX() + c.jit * foot.width() * 0.7f; out[1] = foot.bottom + critterRy() + gap }
            Side.BACK -> { out[0] = foot.centerX() + c.jit * foot.width() * 0.7f; out[1] = foot.top - critterRy() - gap }
            Side.LEFT -> { out[0] = foot.left - critterRx() - gap; out[1] = foot.centerY() + c.jit * foot.height() * 0.8f }
            Side.RIGHT -> { out[0] = foot.right + critterRx() + gap; out[1] = foot.centerY() + c.jit * foot.height() * 0.8f }
        }
    }

    /** ¿Se puede estar de pie en (x, y)? Dentro del jardin y fuera de la huella de los dos muebles. */
    private fun standable(x: Float, y: Float): Boolean =
        x >= floorLeft() && x <= floorRight() && y >= floorTop() && y <= floorBottom() &&
            feet.none { insideFoot(it, critterRx(), critterRy(), x, y) }

    /** Elige (al azar, mas veces por delante) un lado del mueble [foot] desde el que se pueda comer/beber; si el
     *  actual de [c] sigue valiendo (p. ej. tras mover el mueble) lo conserva cuando [keep]. */
    private fun chooseSide(foot: RectF, c: Critter, keep: Boolean) {
        val p = FloatArray(2)
        fun valid(s: Side): Boolean { val old = c.side; c.side = s; spotFor(foot, c, p); c.side = old; return standable(p[0], p[1]) }
        if (keep && valid(c.side)) return
        // Solo lados a los que se pueda LLEGAR y sin un rodeo enorme (p. ej. un hueco al otro lado del mueble, que obligaria
        // a dar la vuelta entera): se descartan los que cuestan mas del doble del mas cercano (+ un cuarto de pantalla).
        val options = ArrayList<Triple<Side, Int, Float>>()   // lado, peso, longitud del camino
        for ((s, w) in listOf(Side.FRONT to 3, Side.LEFT to 2, Side.RIGHT to 2, Side.BACK to 2)) {
            if (!valid(s)) continue
            val old = c.side; c.side = s; spotFor(foot, c, p); c.side = old
            if (shortestPath(c.x, c.y, p[0], p[1])) options.add(Triple(s, w, pathLen))
        }
        if (options.isEmpty()) {
            // Ninguno alcanzable desde donde esta: cualquiera valido (ya se rendira si de verdad no puede llegar).
            c.side = listOf(Side.FRONT, Side.LEFT, Side.RIGHT, Side.BACK).firstOrNull { valid(it) } ?: Side.FRONT
            return
        }
        val limit = options.minOf { it.third } * 2f + width * 0.25f
        val near = options.filter { it.third <= limit }
        var roll = Random.nextInt(near.sumOf { it.second })
        for ((s, w, _) in near) { if (roll < w) { c.side = s; return }; roll -= w }
    }

    /** Hacia donde mira un Pokemon que usa un mueble por el lado [s]: hacia el mueble. */
    private fun faceFurniture(s: Side) = when (s) {
        Side.FRONT -> DungeonSpriteRepository.Direction.UP
        Side.BACK -> DungeonSpriteRepository.Direction.DOWN
        Side.LEFT -> DungeonSpriteRepository.Direction.RIGHT
        Side.RIGHT -> DungeonSpriteRepository.Direction.LEFT
    }

    // ---------------------------------------------------------------- simulacion

    private fun update(dt: Float, now: Long) {
        if (width <= 0) return
        for (b in balls) if (!b.held) updateBall(b, dt)
        updateTag(now)
        // Cada cesta se repone sola, rapido: una manzana cada BASKET_REFILL_MS hasta llenarla.
        for (p in pieces) if (p.kind == Kind.BASKET && p.apples < BASKET_MAX_APPLES && now >= p.nextApple) {
            p.apples++
            p.nextApple = now + BASKET_REFILL_MS
        }
        // Los muebles que se han retirado del todo (ya sin fundido) se quitan de la lista.
        if (pieces.any { it.leaveAt != 0L && now - it.leaveAt >= PIECE_FADE_MS }) {
            pieces.removeAll { it.leaveAt != 0L && now - it.leaveAt >= PIECE_FADE_MS }
            rebuildFeet()
        }
        // Humo: una bocanada nueva cada rato, las viejas se retiran.
        if (now - lastPuffAt >= PUFF_EVERY_MS) { puffs.add(Puff(now, Random.nextFloat())); lastPuffAt = now }
        puffs.removeAll { now - it.born > PUFF_LIFE_MS }
        for (c in critters) updateCritter(c, dt, now)
        // Hitbox: nadie atraviesa a nadie ni a un mueble. Separar a los Pokemon solapados puede meter a alguno en un mueble, y
        // sacarlo del mueble puede volver a solaparlo: se alterna dos veces (el mueble manda en la ultima) y, si alguien sigue
        // donde no cabe, se le lleva al sitio libre mas cercano.
        repeat(2) {
            separateCritters()
            // Si un mueble acaba de moverse (o se arrastra) encima de alguien, se les aparta.
            for (c in critters) if (c.x >= 0f && c.script == Script.NONE) {
                scratch[0] = c.x; scratch[1] = c.y
                if (clearOfFurniture(scratch, critterRx(), critterRy()) != 0) { c.x = scratch[0]; c.y = scratch[1] }
            }
        }
        for (c in critters) if (c.script == Script.NONE) ensureStandable(c)
        for (b in balls) if (!b.held) { scratch[0] = b.x; scratch[1] = b.y; if (clearOfFurniture(scratch, ballR(), ballR()) != 0) { b.x = scratch[0]; b.y = scratch[1] } }
        floats.removeAll { it.until < now }
        hearts.removeAll { now - it.born > it.life }
        ripples.removeAll { now - it.born > RIPPLE_MS + 300L }
        critters.removeAll { it.leaveAt != 0L && now - it.leaveAt >= VISITOR_FADE_MS }   // visitantes que ya se han ido del todo
    }

    private fun updateBall(ball: Ball, dt: Float) {
        val damp = max(0f, 1f - 1.7f * dt)
        val l = floorLeft(); val r = floorRight(); val t = floorTop(); val b = floorBottom()
        val rb = ballR()
        // Se avanza en trozos pequeños para que una pelota lanzada rapido no atraviese un mueble de un salto.
        val total = hypot(ball.vx, ball.vy) * dt
        val n = max(1, ceil(total / (rb * 0.8f)).toInt())
        val sdt = dt / n
        repeat(n) {
            ball.x += ball.vx * sdt; ball.y += ball.vy * sdt
            if (ball.x < l) { ball.x = l; ball.vx = abs(ball.vx) } else if (ball.x > r) { ball.x = r; ball.vx = -abs(ball.vx) }
            if (ball.y < t) { ball.y = t; ball.vy = abs(ball.vy) } else if (ball.y > b) { ball.y = b; ball.vy = -abs(ball.vy) }
            // Rebota contra la huella de los muebles (pierde algo de fuerza).
            scratch[0] = ball.x; scratch[1] = ball.y
            for (foot in feet) when (pushOutOf(foot, rb, rb, scratch)) {
                1 -> ball.vx = -abs(ball.vx) * 0.6f
                2 -> ball.vx = abs(ball.vx) * 0.6f
                3 -> ball.vy = -abs(ball.vy) * 0.6f
                4 -> ball.vy = abs(ball.vy) * 0.6f
            }
            clearOfFurniture(scratch, rb, rb)   // sacarla de un mueble puede meterla en el de al lado: se repasa
            ball.x = scratch[0]; ball.y = scratch[1]
        }
        ball.vx *= damp; ball.vy *= damp
    }

    private fun updateCritter(c: Critter, dt: Float, now: Long) {
        if (c.x < 0f) { placeRandomly(c); return }
        if (c.script != Script.NONE) { stepScript(c, dt, now); return }
        // Un visitante que lleva demasiado intentando llegar al camino se desvanece donde esta (nunca se queda para siempre).
        if (c.leaving && now - c.leavingSince > LEAVE_TIMEOUT_MS) { c.leaving = false; c.leaveAt = now; return }
        // Y si en unos segundos no ha conseguido pasar entre los demas (un grupo apretado cerca de la puerta), deja de ser solido y pasa.
        if (c.leaving && !c.ghost && now - c.leavingSince > LEAVE_GHOST_MS) c.ghost = true
        if (c.task != Task.TAG) c.speedMul = 1f   // solo corre mas mientras juega al pilla-pilla
        if (c.phase != Phase.WALK) { c.moveX *= 0.8f; c.moveY *= 0.8f; c.headX = 0f; c.headY = 0f; c.ditherAt = 0L }   // parado: el recuerdo del ultimo paseo se apaga
        when (c.phase) {
            Phase.IDLE -> if (now >= c.until) decide(c, now)
            Phase.WALK -> {
                // Lo que se puede mover de sitio (pelota, cesta, bebedero) se persigue donde este AHORA.
                when (c.task) {
                    Task.PLAY -> { val b = c.ball ?: balls[0]; c.tx = b.x; c.ty = b.y }
                    Task.TAG -> {}   // el pilla-pilla pone la meta de cada jugador en cada fotograma (ver updateTag)
                    Task.EAT, Task.DRINK -> {
                        val p = usablePiece(c) ?: run { giveUp(c, now); return }   // el mueble se retiro y no queda otro
                        chooseSide(p.foot, c, keep = true)
                        spotFor(p.foot, c, scratch); c.tx = scratch[0]; c.ty = scratch[1]
                    }
                    Task.WANDER -> {}
                }
                val step = speed() * dt * c.speedMul
                // Un mueble en medio: se rodea por el camino mas corto (en vez de atravesarlo o quedarse empujandolo).
                if (!nextHop(c.x, c.y, c.tx, c.ty)) { giveUp(c, now); return }   // meta inalcanzable: no se empuja contra el mueble
                val gx = hop[0]; val gy = hop[1]
                // Red de seguridad: si lleva 9 s sin acercarse nunca mas a su meta (la pelota quedo en un hueco al que no cabe,
                // etc.) lo deja: otro lado del mueble, o se rinde y se pone a otra cosa. Nunca se queda andando para siempre.
                // Una meta que se mueve (la pelota rodando) cuenta como avance: el cronometro se reinicia.
                val toTarget = hypot(c.tx - c.x, c.ty - c.y)
                if (hypot(c.tx - c.lastGx, c.ty - c.lastGy) > 40f * density) { c.lastGx = c.tx; c.lastGy = c.ty; c.bestDist = Float.MAX_VALUE; c.progressAt = now }
                if (toTarget < c.bestDist - 3f * density) { c.bestDist = toTarget; c.progressAt = now }
                else if (now - c.progressAt > 9000L) { giveUp(c, now); return }
                // (con margen: llegar "a unos pasos" vale; con uno de un paso, quien esquivaba a otro orbitaba su meta sin llegar nunca)
                val reach = if (c.task == Task.PLAY) width * 0.07f else max(2f * step, width * 0.025f)
                if (toTarget <= reach) {
                    arrive(c, now)
                } else {
                    val dx = gx - c.x; val dy = gy - c.y
                    val dist = max(hypot(dx, dy), 0.001f)
                    val ux = dx / dist; val uy = dy / dist
                    val stepLen = min(step, dist)
                    // 1) Rumbo deseado: hacia donde quiere ir, desviado POCO A POCO por los Pokemon cercanos que tiene delante: se aparta de
                    //    ellos y se desliza hacia el lado que le acerca a su meta (si esta justo delante, cada Pokemon tiene su lado fijo: dos
                    //    que se cruzan se esquivan). Nada de "girar un angulo fijo y volver a probar": eso hacia zigzag y la cara cambiaba sin parar.
                    var vx = ux; var vy = uy
                    // Cerca de su meta se aparta cada vez menos de los vecinos (la huella dura ya impide solaparse): si no, la repulsion lo
                    // sacaba del sitio justo al llegar y daba vueltas alrededor sin "llegar" nunca (y la cara cambiaba a cada vuelta).
                    val settle = (toTarget / (2.4f * bodyRx())).coerceIn(0f, 1f)
                    for (o in critters) {
                        if (c.ghost || o === c || o.x < 0f || !o.solid) continue
                        val ex = c.x - o.x; val ey = c.y - o.y
                        val d = bodyDist(ex, ey)
                        if (d >= AVOID_RANGE) continue
                        val el = max(hypot(ex, ey), 1f); val rx = ex / el; val ry = ey / el          // hacia fuera del otro
                        if (-(rx * ux + ry * uy) < -0.2f && d > 1.05f) continue                        // lo que queda detras no estorba
                        val w = ((AVOID_RANGE - d) / (AVOID_RANGE - 0.8f)).coerceIn(0f, 1.3f) * settle
                        if (w <= 0f) continue
                        var tx = -ry; var ty = rx                                                      // de lado
                        val along = tx * ux + ty * uy
                        if (abs(along) < 0.25f) { if (c.avoidSign < 0f) { tx = -tx; ty = -ty } } else if (along < 0f) { tx = -tx; ty = -ty }
                        vx += (rx * 0.7f + tx * 1.5f) * w; vy += (ry * 0.7f + ty * 1.5f) * w
                    }
                    val vl = max(hypot(vx, vy), 0.001f); vx /= vl; vy /= vl
                    // 2) Rumbo con INERCIA: gira poco a poco hacia el deseado (nunca de golpe).
                    if (c.headX == 0f && c.headY == 0f) { c.headX = vx; c.headY = vy }
                    else {
                        c.headX += (vx - c.headX) * HEAD_BLEND; c.headY += (vy - c.headY) * HEAD_BLEND
                        val hl = max(hypot(c.headX, c.headY), 0.001f); c.headX /= hl; c.headY /= hl
                    }
                    var mx = c.headX * stepLen; var my = c.headY * stepLen
                    // 3) Hitbox DURA: nunca entra en la huella de otro Pokemon ni en un mueble. Si el paso no cabe, lo acorta; si ni asi, prueba
                    //    solo hacia la meta; si tampoco, espera.
                    fun fits(px: Float, py: Float) = standable(c.x + px, c.y + py) && (c.ghost || bodyBlocker(c, c.x + px, c.y + py) == null)
                    if (!fits(mx, my)) {
                        var f = 0.5f; var moved = false
                        while (f > 0.1f) { if (fits(mx * f, my * f)) { mx *= f; my *= f; moved = true; break }; f *= 0.5f }
                        if (!moved) { if (fits(ux * stepLen, uy * stepLen)) { mx = ux * stepLen; my = uy * stepLen } else { mx = 0f; my = 0f } }
                    }
                    // 4) Casi parado (esperando a que le dejen paso) demasiado rato sin acercarse a su meta: cambia de plan. Si avanza, sigue.
                    if (hypot(mx, my) < 0.35f * stepLen) {
                        if (c.blockedSince == 0L) { c.blockedSince = now; c.blockedDist = toTarget }
                        else if (now - c.blockedSince > BLOCKED_GIVE_UP_MS) {
                            if (toTarget < c.blockedDist - 25f * density) { c.blockedSince = now; c.blockedDist = toTarget }
                            else { c.blockedSince = 0L; c.headX = 0f; c.headY = 0f; giveUp(c, now); return }
                        }
                    } else c.blockedSince = 0L
                    if (mx != 0f || my != 0f) {
                        c.x += mx; c.y += my
                        c.walked += hypot(mx, my)
                        c.x = c.x.coerceIn(floorLeft(), floorRight()); c.y = c.y.coerceIn(floorTop(), floorBottom())
                    }
                    // Mira hacia donde SE MUEVE DE VERDAD en promedio (los ultimos ~7 fotogramas, no un paso suelto). Sin movimiento claro (esperando)
                    // no cambia de cara, y entre dos cambios pasa un tiempo minimo.
                    c.moveX = c.moveX * FACE_EMA + mx; c.moveY = c.moveY * FACE_EMA + my
                    faceMove(c, c.moveX, c.moveY, now, minLen = 2.5f * step)
                    // Temblando en el sitio (una rendija estrecha entre dos muebles, un grupo apretado): si en DITHER_WINDOW_MS no se ha movido
                    // apenas y sigue lejos de su meta, cambia de plan en vez de seguir agitandose.
                    if (c.ditherAt == 0L) { c.ditherAt = now; c.ditherX = c.x; c.ditherY = c.y }
                    else if (now - c.ditherAt >= DITHER_WINDOW_MS) {
                        val stayed = hypot(c.x - c.ditherX, c.y - c.ditherY) < 0.25f * critterSize()
                        c.ditherAt = now; c.ditherX = c.x; c.ditherY = c.y
                        if (stayed && toTarget > 3f * reach) { c.blockedSince = 0L; c.headX = 0f; c.headY = 0f; giveUp(c, now); return }
                    }
                }
            }
            Phase.DO -> {
                // Comiendo o bebiendo, de vez en cuando suelta un corazoncito.
                if (now - c.lastHeartAt >= HEART_EVERY_MS) { c.lastHeartAt = now; spawnHeart(c, now) }
                if (now >= c.until) finish(c, now)
            }
        }
    }

    // ---------------------------------------------------------------- pilla-pilla

    /** ¿Puede [c] unirse a una partida? Solo los libres: sin necesidad real pendiente, sin comer ni beber, sin estar siendo llamado. */
    private fun tagEligible(c: Critter) =
        c.x >= 0f && c.solid && !c.leaving && c.real == null && c.pending.isEmpty() && c.phase != Phase.DO && !c.called && c.task != Task.TAG

    /** Mueve el pilla-pilla un fotograma. Es de UNO CONTRA UNO y de DOS pillados (pedido del usuario): de vez en cuando dos Pokemon libres
     *  (cualquiera del jardin, tuyos o visitantes) juegan; uno persigue al otro y, cuando lo pilla, ese pasa a perseguir al primero (que huye
     *  con unos segundos de inmunidad); cuando le pilla de vuelta, la partida termina. Si pasa TAG_TIMEOUT_MS sin acabar, termina igual. */
    private fun updateTag(now: Long) {
        val g = tag
        if (g == null) {
            if (nextTagCheckAt == 0L) nextTagCheckAt = now + TAG_FIRST_DELAY_MS
            if (now < nextTagCheckAt) return
            nextTagCheckAt = now + TAG_CHECK_MS
            val eligible = critters.filter { tagEligible(it) }
            if (eligible.size < 2 || Random.nextFloat() > TAG_CHANCE) return
            // La pareja: dos libres que esten a una distancia ADECUADA para empezar a perseguirse (pedido del usuario): ni tan cerca que
            // ya estan pegados, ni tan lejos que la persecucion no tiene sentido. Si no hay ninguna pareja asi, no empieza (vuelve a mirar).
            val pairs = startPairs(eligible)
            if (pairs.isEmpty()) return
            val (a, b) = pairs.random()
            startTag(arrayListOf(a, b), now)
            return
        }
        // Quien ya no juega (se va, lo llaman, ...) sale de la partida; con menos de 2 o al acabar el tiempo, se termina.
        g.players.removeAll { p -> !p.solid || p.leaving || p.task != Task.TAG || p.x < 0f || p !in critters }
        // Quien lleva TAG_STUCK_MS sin moverse (atrapado en un hueco entre muebles) deja la partida y vuelve a lo suyo.
        val trapped = g.players.filter { p ->
            if (p.tagRefAt == 0L || hypot(p.x - p.tagRefX, p.y - p.tagRefY) > 0.3f * critterSize()) { p.tagRefX = p.x; p.tagRefY = p.y; p.tagRefAt = now; false }
            else now - p.tagRefAt > TAG_STUCK_MS
        }
        for (p in trapped) { g.players.remove(p); p.task = Task.WANDER; p.phase = Phase.IDLE; p.until = now + 600L; p.speedMul = 1f; p.tagRefAt = 0L }
        if (g.players.size < 2 || now >= g.endsAt) { endTag(g, now); return }
        if (g.chaser !in g.players) g.chaser = g.players.first()
        // Pillar: quien persigue toca al otro (salvo que este sea el que acaba de pillar y aun tenga inmunidad).
        var chaser = g.chaser
        var runner = g.players.first { it !== chaser }
        if (!(runner === g.immune && now < g.immuneUntil) && bodyDist(runner.x - chaser.x, runner.y - chaser.y) < TAG_TOUCH) {
            g.tags++; debugTags++
            runner.exclaimUntil = now + 1100L
            spawnHeart(runner, now)
            if (g.tags >= 2) { endTag(g, now); return }   // le han pillado a cada uno una vez: se acabo
            g.immune = chaser; g.immuneUntil = now + TAG_IMMUNITY_MS
            g.chaser = runner                               // el pillado pasa a perseguir al primero
            chaser = g.chaser; runner = g.players.first { it !== chaser }
            chaser.fleeAt = 0L; runner.fleeAt = 0L
            // VENTAJA para el que acaba de pillar y ahora huye (pedido del usuario): el pillado, que ahora persigue, se queda parado un
            // segundo y luego arranca poco a poco (ver TAG_FREEZE_MS / TAG_RAMP_MS).
            chaser.freezeUntil = now + TAG_FREEZE_MS
        }
        chaser.task = Task.TAG; runner.task = Task.TAG
        if (chaser.phase != Phase.WALK) chaser.phase = Phase.WALK
        if (runner.phase != Phase.WALK) runner.phase = Phase.WALK
        // El que persigue: parado hasta freezeUntil y despues acelera de 0 a su velocidad en TAG_RAMP_MS ("empieza lento"); el que huye, a la suya.
        val sinceGo = now - chaser.freezeUntil
        chaser.speedMul = if (sinceGo < 0L) 0f else TAG_CHASER_SPEED * (0.3f + 0.7f * min(1f, sinceGo.toFloat() / TAG_RAMP_MS))
        runner.speedMul = TAG_RUNNER_SPEED
        chaser.tx = runner.x; chaser.ty = runner.y
        // Si el que huye esta parado (arrinconado, bloqueado) no espera al siguiente turno: elige otro sitio ya.
        if (runner.blockedSince != 0L) runner.fleeAt = 0L
        if (now >= runner.fleeAt) pickFleePoint(runner, chaser, now)
        yieldToTag(g, now)
    }

    /** Las parejas de [eligible] cuya distancia esta entre [TAG_START_MIN] y [TAG_START_MAX] tamaños de Pokemon. */
    private fun startPairs(eligible: List<Critter>): List<Pair<Critter, Critter>> {
        val minD = TAG_START_MIN * critterSize(); val maxD = TAG_START_MAX * critterSize()
        val out = ArrayList<Pair<Critter, Critter>>()
        for (i in eligible.indices) for (j in i + 1 until eligible.size) {
            val d = hypot(eligible[i].x - eligible[j].x, eligible[i].y - eligible[j].y)
            if (d in minD..maxD) out.add(eligible[i] to eligible[j])
        }
        return out
    }

    private fun startTag(players: ArrayList<Critter>, now: Long) {
        val game = TagGame(players, players.random(), now + TAG_TIMEOUT_MS)
        game.chaser.freezeUntil = now + TAG_FREEZE_MS   // el que persigue espera un segundo al empezar: el otro sale con ventaja
        tag = game; debugTagGames++
        for (p in players) {
            p.task = Task.TAG; p.phase = Phase.WALK; p.real = null; p.called = false; p.giveUps = 0; p.piece = null
            p.blockedSince = 0L; p.ditherAt = 0L; p.fleeAt = 0L; p.bestDist = Float.MAX_VALUE; p.progressAt = now; p.tagRefAt = 0L
        }
        game.chaser.exclaimUntil = now + 1200L   // el "!" dice quien la lleva al empezar
    }

    /** SOLO PARA PRUEBAS: empieza ya una partida con 2 Pokemon libres. Devuelve cuantos juegan (2, o 0 si no hay libres). */
    fun debugForceTag(): Int {
        if (tag != null || width <= 0) return 0
        val eligible = critters.filter { tagEligible(it) }
        // Una pareja a distancia de empezar; si no hay ninguna, la mas parecida a ella (es solo para pruebas).
        val mid = (TAG_START_MIN + TAG_START_MAX) / 2f * critterSize()
        val pair = startPairs(eligible).randomOrNull()
            ?: eligible.flatMap { a -> eligible.filter { it !== a }.map { b -> a to b } }.minByOrNull { (a, b) -> abs(hypot(a.x - b.x, a.y - b.y) - mid) }
            ?: return 0
        startTag(arrayListOf(pair.first, pair.second), System.currentTimeMillis())
        return 2
    }

    /** SOLO PARA PRUEBAS: (partidas empezadas, pillados) desde que se reinicio la cuenta. */
    fun debugTagStats(): String = "partidas=$debugTagGames pillados=$debugTags sin acabar=$debugTagUnfinished"

    /** SOLO PARA PRUEBAS: estado de la partida en curso (quien persigue, donde estan, distancia entre huellas, pillados...), o "sin partida". */
    fun debugTagState(): String {
        val g = tag ?: return "sin partida"
        val ch = g.chaser; val ru = g.players.firstOrNull { it !== ch } ?: return "partida con un solo jugador"
        val now = System.currentTimeMillis()
        // Sonda: de 8 direcciones, cuantas tiene libres un paso corto, y quien estorba en las demas (para entender por que alguien se queda parado).
        fun probe(c: Critter): String {
            val st = speed() * 0.033f * 3f
            var ok = 0; val blockers = LinkedHashSet<String>()
            for (k in 0 until 8) {
                val a = k * PI.toFloat() / 4f; val px = c.x + cos(a) * st; val py = c.y + sin(a) * st
                val blocker = bodyBlocker(c, px, py)
                if (standable(px, py) && blocker == null) ok++ else blockers.add(blocker?.name ?: (if (!standable(px, py)) "mueble/valla" else "?"))
            }
            return "libres $ok/8 estorban $blockers"
        }
        fun d(c: Critter) = "${c.name}(${c.x.toInt()},${c.y.toInt()} ${c.phase} meta ${c.tx.toInt()},${c.ty.toInt()} vel x${"%.2f".format(c.speedMul)} bloq=${c.blockedSince != 0L} ${probe(c)})"
        return "persigue ${d(ch)} huye ${d(ru)} distancia huellas ${"%.2f".format(bodyDist(ru.x - ch.x, ru.y - ch.y))} pillados ${g.tags} inmune=${g.immune?.name}${if (g.immune != null && now < g.immuneUntil) "(si)" else "(no)"} quedan ${(g.endsAt - now) / 1000} s"
    }

    /** Un sitio al que huir de [chaser]: de unos cuantos puntos a su alrededor (libres), el que mas lo aleja, sin pegarse a las vallas. */
    private fun pickFleePoint(p: Critter, chaser: Critter, now: Long) {
        p.fleeAt = now + TAG_FLEE_EVERY_MS + Random.nextLong(0L, 200L)
        val s = critterSize()
        val dMin = TAG_MIN_DIST * s; val dMax = TAG_MAX_DIST * s
        val dNow = hypot(p.x - chaser.x, p.y - chaser.y)
        // DEMASIADO LEJOS (pasa de la distancia maxima): no sigue huyendo (se iba a una esquina lejana y se quedaba ahi parado mientras el otro
        // aun venia): vuelve hacia el que persigue hasta quedar a media distancia de la banda, para que la persecucion siga siendo una persecucion.
        if (dNow > dMax) {
            val mid = (dMin + dMax) / 2f
            val tx = chaser.x + (p.x - chaser.x) / dNow * mid; val ty = chaser.y + (p.y - chaser.y) / dNow * mid
            if (standable(tx, ty)) { p.tx = tx; p.ty = ty; return }
        }
        var best: Pair<Float, Float>? = null; var bestScore = -Float.MAX_VALUE
        val reach = width * 0.30f
        for (k in 0 until 16) {
            val ang = k * (2f * PI.toFloat() / 16f) + Random.nextFloat() * 0.2f
            val x = (p.x + cos(ang) * reach).coerceIn(floorLeft(), floorRight())
            val y = (p.y + sin(ang) * reach * 0.7f).coerceIn(floorTop(), floorBottom())
            if (!standable(x, y)) continue
            val dP = hypot(x - chaser.x, y - chaser.y)
            // Banda de distancia: muy cerca del que persigue o mas alla del maximo penaliza; dentro de la banda, mejor cuanto mas lejos.
            val bandPenalty = when { dP < dMin -> (dMin - dP) * 3f; dP > dMax -> (dP - dMax) * 1.5f; else -> 0f }
            // Nada de rincones: cerca de una valla o esquina se queda arrinconado (parado) en vez de correr.
            val edge = minOf(x - floorLeft(), floorRight() - x, y - floorTop(), floorBottom() - y)
            val cornerPenalty = if (edge < 0.6f * s) (0.6f * s - edge) * 2.5f else 0f
            // Prefiere seguir en la misma direccion (si no, cambiaba de rumbo a cada eleccion y zigzagueaba).
            val keep = if (p.headX != 0f || p.headY != 0f) (cos(ang) * p.headX + sin(ang) * p.headY) else 0f
            val score = min(dP, dMax) - bandPenalty - cornerPenalty + 0.45f * reach * keep
            if (score > bestScore) { bestScore = score; best = x to y }
        }
        best?.let { p.tx = it.first; p.ty = it.second }
    }

    /** Quien NO juega y esta cerca de los que corren se aparta para dejarles paso (antes el que persigue se quedaba pillado detras de un
     *  Pokemon parado en el camino). Se va hacia el lado contrario del jugador mas cercano, unas dos huellas; los que estan comiendo,
     *  bebiendo o cubriendo una necesidad real no se mueven. */
    private fun yieldToTag(g: TagGame, now: Long) {
        val step = 2.8f * bodyRx()
        for (b in critters) {
            if (b.x < 0f || !b.solid || b.leaving || b.task == Task.TAG || b.phase == Phase.DO || b.real != null || b.called || now < b.yieldAt) continue
            val near = g.players.minByOrNull { bodyDist(b.x - it.x, b.y - it.y) } ?: continue
            if (bodyDist(b.x - near.x, b.y - near.y) > TAG_YIELD_RANGE) continue
            val dx = b.x - near.x; val dy = b.y - near.y; val len = max(hypot(dx, dy), 1f)
            val ux = dx / len; val uy = dy / len
            // hacia fuera, o de lado (a un lado u otro) si fuera no cabe
            val options = listOf(ux to uy, -uy to ux, uy to -ux, (ux - uy) to (uy + ux))
            val spot = options.map { (ox, oy) -> val m = max(hypot(ox, oy), 0.001f); (b.x + ox / m * step) to (b.y + oy / m * step) }
                .firstOrNull { (x, y) -> standable(x, y) && critters.none { o -> o !== b && o.x >= 0f && o.solid && bodyDist(o.x - x, o.y - y) < 1.0f } }
                ?: continue
            b.task = Task.WANDER; b.phase = Phase.WALK; b.tx = spot.first; b.ty = spot.second
            b.bestDist = Float.MAX_VALUE; b.progressAt = now; b.blockedSince = 0L; b.giveUps = 0
            b.yieldAt = now + TAG_YIELD_EVERY_MS
        }
    }

    private fun endTag(g: TagGame, now: Long) {
        if (g.tags < 2) debugTagUnfinished++   // acabo por tiempo / por quedarse un jugador atrapado, sin haberse pillado uno a otro
        for (p in g.players) {
            p.task = Task.WANDER; p.phase = Phase.IDLE; p.until = now + Random.nextLong(500L, 1500L)
            p.speedMul = 1f; p.fleeAt = 0L; p.headX = 0f; p.headY = 0f
            spawnHeart(p, now)   // la partida termina con corazones
        }
        tag = null
        nextTagCheckAt = now + Random.nextLong(TAG_COOLDOWN_MIN_MS, TAG_COOLDOWN_MAX_MS)
    }

    // Primera fila con algo de color de cada fotograma (fraccion del alto): los sprites pequeños (Pidgey...) ocupan solo la parte de abajo
    // de su celda, y los corazones / el "!" tienen que ir sobre la cabeza que se ve, no sobre el borde de la celda.
    private val headTopCache = java.util.WeakHashMap<Bitmap, Float>()
    private fun headTopFrac(bmp: Bitmap): Float = headTopCache.getOrPut(bmp) {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        var top = h
        for (y in 0 until h) { bmp.getPixels(row, 0, w, 0, y, w, 1); if (row.any { (it ushr 24) > 40 }) { top = y; break } }
        top.toFloat() / h
    }

    /** La y (en pantalla) de lo mas alto que se ve del sprite de [c]. */
    private fun headTop(c: Critter): Float {
        val s = critterSize()
        val frame = DungeonSpriteRepository.frames(context, c.name, c.shiny, c.dir)?.firstOrNull()
        return c.y - s * 0.86f + (if (frame != null) headTopFrac(frame) else 0.1f) * s
    }

    /** Un corazoncito sobre la cabeza de [c] que sube, se mece y se desvanece (maximo [MAX_HEARTS] a la vez). */
    private fun spawnHeart(c: Critter, now: Long) {
        if (hearts.size >= MAX_HEARTS) return
        val s = critterSize()
        hearts.add(Heart(c.x + (Random.nextFloat() - 0.5f) * s * 0.45f, headTop(c) - s * 0.05f, now, HEART_LIFE_MS + Random.nextLong(0L, 350L), Random.nextFloat() * 6.28f, 0.85f + Random.nextFloat() * 0.35f))
    }

    /** Lleva un rato sin avanzar hacia su meta: si iba a comer/beber prueba por otro lado del mueble (hasta 2 veces); si no,
     *  lo deja y se queda parado un momento (luego decide otra cosa). */
    private fun giveUp(c: Critter, now: Long) {
        c.bestDist = Float.MAX_VALUE; c.progressAt = now
        if (c.leaving) { headToDoor(c, now); return }   // el visitante que se va no se rinde: otra vez hacia el camino
        if (c.called && retargetCall(c, now)) return   // llamado por el jugador: otro sitio del grupo antes que rendirse
        if (c.task == Task.TAG && tag != null) { c.fleeAt = 0L; c.blockedSince = 0L; c.ditherAt = 0L; return }   // en el pilla-pilla no se rinde: elige otra meta
        if ((c.task == Task.EAT || c.task == Task.DRINK) && c.giveUps < 2) {
            // Segundo intento: si hay otro mueble del mismo tipo (con manzanas, si es una cesta), prueba con ese; si no, otro lado.
            if (c.giveUps >= 1) {
                val kind = if (c.task == Task.EAT) Kind.BASKET else Kind.TROUGH
                pieces.filter { it.kind == kind && it !== c.piece && it.leaveAt == 0L && (kind != Kind.BASKET || it.apples > 0) }
                    .minByOrNull { hypot(it.foot.centerX() - c.x, it.foot.centerY() - c.y) }?.let { c.piece = it }
            }
            val p = usablePiece(c)
            if (p != null) {
                c.giveUps++
                chooseSide(p.foot, c, keep = false)
                spotFor(p.foot, c, scratch); c.tx = scratch[0]; c.ty = scratch[1]
                return
            }
        }
        c.giveUps = 0; c.called = false
        c.phase = Phase.IDLE; c.until = now + 800L
    }

    /** Que hace ahora un Pokemon parado: primero una necesidad REAL pendiente, si no algo de adorno. */
    private fun decide(c: Critter, now: Long) {
        if (c.leaving) { headToDoor(c, now); return }
        c.real = null
        c.called = false
        val act = c.pending.removeFirstOrNull()
        when (act) {
            PokeWidgetProvider.ACTION_FEED -> { c.real = act; startEat(c, forceApple = true) }
            PokeWidgetProvider.ACTION_WASH -> { c.real = act; startDrink(c) }
            PokeWidgetProvider.ACTION_PET -> { c.real = act; startPlay(c, kicks = 3) }
            else -> when (Random.nextInt(100)) {
                in 0 until 55 -> startWander(c)
                in 55 until 72 -> startPlay(c, kicks = Random.nextInt(2, 5))
                in 72 until 86 -> startEat(c, forceApple = false)
                else -> startDrink(c)
            }
        }
    }

    private fun startWander(c: Critter) {
        c.task = Task.WANDER
        val reach = width * 0.35f
        // Un destino donde se pueda estar de pie (nunca "hasta dentro" de un mueble ni en el hueco estrecho entre dos);
        // si en unos cuantos intentos no sale ninguno, se queda donde esta.
        var tx = c.x; var ty = c.y
        for (attempt in 0 until 10) {
            val px = (c.x + (Random.nextFloat() * 2f - 1f) * reach).coerceIn(floorLeft(), floorRight())
            val py = (c.y + (Random.nextFloat() * 2f - 1f) * reach * 0.5f).coerceIn(floorTop(), floorBottom())
            if (standable(px, py)) { tx = px; ty = py; break }
        }
        c.tx = tx; c.ty = ty
        c.phase = Phase.WALK
    }

    /** El mueble de tipo [kind] mas cercano a [c] (con algo de azar entre los parecidos de cerca, para que no vayan todos al mismo);
     *  si [needApple], solo cestas con manzanas. null si no hay ninguno. */
    private fun pickPiece(c: Critter, kind: Kind, needApple: Boolean): Piece? =
        pieces.filter { it.kind == kind && it.leaveAt == 0L && (!needApple || it.apples > 0) }
            .minByOrNull { hypot(it.foot.centerX() - c.x, it.foot.centerY() - c.y) * (0.85f + Random.nextFloat() * 0.3f) }

    /** El mueble al que va [c] a comer o beber: el que ya tenia o, si se retiro, otro del mismo tipo; null si no queda ninguno. */
    private fun usablePiece(c: Critter): Piece? {
        val kind = if (c.task == Task.EAT) Kind.BASKET else Kind.TROUGH
        val cur = c.piece
        if (cur != null && cur.kind == kind && cur.leaveAt == 0L) return cur
        return pickPiece(c, kind, needApple = false).also { c.piece = it }
    }

    private fun startEat(c: Critter, forceApple: Boolean) {
        // Una cesta con manzanas (la mas cercana). Sin ninguna, el de adorno se pone a pasear; el de una necesidad REAL va igualmente a
        // la mas cercana (se repone rapido) y, si al llegar sigue vacia, espera su manzana (ver arrive): nadie come una que no hay.
        val basket = pickPiece(c, Kind.BASKET, needApple = true) ?: if (forceApple) pickPiece(c, Kind.BASKET, needApple = false) else null
        if (basket == null) { startWander(c); return }
        val retry = c.piece === basket && c.task == Task.EAT   // vuelve a la misma cesta (esperaba una manzana): mismo lado, sin darle vueltas
        c.piece = basket
        c.task = Task.EAT
        if (!retry) c.jit = Random.nextFloat() - 0.5f
        chooseSide(basket.foot, c, keep = retry)   // se acerca a la cesta por cualquiera de sus lados, no solo por abajo
        spotFor(basket.foot, c, scratch); c.tx = scratch[0]; c.ty = scratch[1]
        c.phase = Phase.WALK
    }

    private fun startDrink(c: Critter) {
        val trough = pickPiece(c, Kind.TROUGH, needApple = false)
        if (trough == null) { startWander(c); return }
        c.piece = trough
        c.task = Task.DRINK
        c.jit = Random.nextFloat() - 0.5f
        chooseSide(trough.foot, c, keep = false)   // idem con el bebedero
        spotFor(trough.foot, c, scratch); c.tx = scratch[0]; c.ty = scratch[1]
        c.phase = Phase.WALK
    }

    /** Se pone a jugar con [ball] (o con la pelota libre mas cercana, con algo de azar si hay dos). */
    private fun startPlay(c: Critter, kicks: Int, ball: Ball? = null) {
        val b = ball ?: balls.filter { !it.held }.minByOrNull { hypot(it.x - c.x, it.y - c.y) * (0.8f + Random.nextFloat() * 0.4f) } ?: balls[0]
        c.ball = b
        c.task = Task.PLAY; c.kicks = kicks
        c.tx = b.x; c.ty = b.y
        c.phase = Phase.WALK
    }

    /** Tras lanzar/soltar [ball]: los dos Pokemon libres mas cercanos corren a por ella (los que estan cubriendo una necesidad real,
     *  comiendo, bebiendo o jugando al pilla-pilla no se interrumpen). */
    private fun sendFetchers(ball: Ball) {
        critters.filter { it.x >= 0f && it.solid && !it.leaving && it.real == null && it.pending.isEmpty() && it.phase != Phase.DO && it.task != Task.TAG }
            .sortedBy { hypot(it.x - ball.x, it.y - ball.y) }
            .take(2)
            .forEach { startPlay(it, kicks = Random.nextInt(2, 5), ball = ball) }
    }

    private fun arrive(c: Critter, now: Long) {
        if (c.leaving) { c.script = Script.EXIT; c.phase = Phase.WALK; c.task = Task.WANDER; return }   // llego al camino: ahora sube hasta la puerta
        when (c.task) {
            Task.WANDER -> {
                c.phase = Phase.IDLE
                if (c.called) {
                    // Llamado por el jugador: se queda un rato junto al punto, mirandolo, contento.
                    c.called = false
                    c.until = now + CALL_HOLD_MS
                    c.dir = lookAt(c.x, c.y, c.calledX, c.calledY)
                    spawnHeart(c, now)
                } else c.until = now + Random.nextLong(700L, 2600L)
            }
            // Come / bebe mirando al mueble, desde el lado por el que ha llegado.
            Task.EAT -> {
                // La manzana se coge AL EMPEZAR a comer (antes se contaba al acabar y varios podian comerse la misma, o comer con la
                // cesta vacia). Si ya no queda ninguna: el de adorno lo deja y la necesidad real vuelve a la cola y reintenta.
                val basket = c.piece
                if (basket == null || basket.leaveAt != 0L || basket.apples <= 0) {
                    if (c.real != null) c.pending.addFirst(c.real!!)
                    c.real = null; c.giveUps = 0
                    c.phase = Phase.IDLE; c.until = now + 600L
                    return
                }
                if (basket.apples >= BASKET_MAX_APPLES) basket.nextApple = now + BASKET_REFILL_MS
                basket.apples--
                c.giveUps = 0; c.phase = Phase.DO; c.doStart = now; c.lastHeartAt = 0L; c.until = now + 1500L; c.dir = faceFurniture(c.side); sideUse[c.side.ordinal]++
            }
            Task.DRINK -> { c.giveUps = 0; c.phase = Phase.DO; c.doStart = now; c.lastHeartAt = 0L; c.until = now + 2000L; c.dir = faceFurniture(c.side); sideUse[4 + c.side.ordinal]++ }
            Task.PLAY -> {
                val ball = c.ball ?: balls[0]
                // Con la pelota en la mano del jugador se espera a que la suelte.
                if (ball.held) { c.phase = Phase.IDLE; c.until = now + 300L; return }
                // Patada: la pelota sale alejandose del Pokemon, con algo de desvio.
                val ang = atan2(ball.y - c.y, ball.x - c.x) + (Random.nextFloat() - 0.5f) * 1.2f
                val power = width * (0.45f + Random.nextFloat() * 0.35f)
                ball.vx = cos(ang) * power; ball.vy = sin(ang) * power * 0.7f
                spawnHeart(c, now)   // cada patada a la pelota es una accion contenta
                c.kicks--
                if (c.kicks <= 0) {
                    if (c.real != null) addXpText(c, now)
                    c.real = null
                    c.phase = Phase.IDLE; c.until = now + Random.nextLong(900L, 2200L)
                } else {
                    c.phase = Phase.WALK; c.tx = ball.x; c.ty = ball.y
                }
            }
            Task.TAG -> c.fleeAt = 0L   // llego a su meta: el pilla-pilla le da otra en el siguiente fotograma (sigue andando)
        }
    }

    private fun finish(c: Critter, now: Long) {
        if (c.real != null) addXpText(c, now)
        c.real = null
        c.phase = Phase.IDLE
        c.until = now + Random.nextLong(600L, 2000L)
    }

    // ---------------------------------------------------------------- llamar a los Pokemon con varios toques

    /** Un toque sobre el suelo vacio: enseña una onda y, si es el [CALL_TAPS]-esimo seguido sobre el mismo punto (y rapido), llama
     *  a los Pokemon del jugador (ver [callMembersTo]). */
    private fun registerGroundTap(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        taps.removeAll { now - it.at > CALL_WINDOW_MS || hypot(it.x - x, it.y - y) > CALL_RADIUS_DP * density }
        taps.add(Tap(x, y, now))
        ripples.add(Ripple(x, y, now, big = false))
        if (taps.size >= CALL_TAPS) {
            val cx = taps.map { it.x }.average().toFloat(); val cy = taps.map { it.y }.average().toFloat()
            taps.clear()
            ripples.add(Ripple(cx, cy, now, big = true))
            callMembersTo(cx, cy, now)
        }
        invalidate()
    }

    /** Los Pokemon DEL JUGADOR (no los visitantes) acuden al punto ([px], [py]) y se quedan un rato mirandolo. Cada uno va a un sitio
     *  libre distinto alrededor (separados lo que mide su huella, fuera de los muebles) para no amontonarse; los que cubren
     *  una necesidad real (comer, beber, jugar con la pelota por XP) no se interrumpen. */
    private fun callMembersTo(px: Float, py: Float, now: Long) {
        val callers = critters.filter { !it.visitor && it.x >= 0f && it.leaveAt == 0L && it.real == null }.sortedBy { hypot(it.x - px, it.y - py) }
        if (callers.isEmpty()) return
        val others = critters.filter { o -> o.x >= 0f && o.leaveAt == 0L && o !in callers }   // quien no acude (visitantes...) ocupa su sitio
        val spots = callSpots(px, py).filter { s -> others.none { o -> bodyDist(o.x - s.first, o.y - s.second) < 1.1f } }
        val pool = spots.take(callers.size + 3).toMutableList()   // los sitios mas cercanos al punto
        for (c in callers) {
            if (pool.isEmpty()) break
            val s = pool.minByOrNull { hypot(it.first - c.x, it.second - c.y) } ?: break   // el mas cercano a ESTE Pokemon: recorridos cortos
            pool.remove(s)
            c.task = Task.WANDER; c.phase = Phase.WALK; c.tx = s.first; c.ty = s.second
            c.called = true; c.calledX = px; c.calledY = py; c.callRetries = 0
            c.pending.clear(); c.real = null; c.giveUps = 0; c.blockedSince = 0L; c.bestDist = Float.MAX_VALUE; c.progressAt = now
        }
    }

    /** Sitios alrededor de ([px], [py]) donde cabe un Pokemon (separados lo que mide su huella y fuera de los muebles), del mas cercano
     *  al punto al mas lejano. */
    private fun callSpots(px: Float, py: Float): List<Pair<Float, Float>> {
        val sx = 2.3f * bodyRx(); val sy = 2.3f * bodyRy()
        val out = ArrayList<Pair<Float, Float>>()
        for (gy in -3..3) for (gx in -3..3) {
            val x = px + gx * sx; val y = py + gy * sy
            if (standable(x, y)) out.add(x to y)
        }
        out.sortBy { hypot((it.first - px) / sx, (it.second - py) / sy) }
        return out
    }

    /** Un Pokemon llamado que no puede llegar a su sitio (otro se le cruza, se lo han ocupado) prueba con otro sitio libre alrededor del
     *  mismo punto en vez de rendirse. Devuelve false si no queda ninguno o ya lo ha intentado [CALL_RETRIES] veces. */
    private fun retargetCall(c: Critter, now: Long): Boolean {
        if (c.callRetries >= CALL_RETRIES) return false
        c.callRetries++
        val taken = critters.filter { o -> o !== c && o.x >= 0f && o.leaveAt == 0L }
        val s = callSpots(c.calledX, c.calledY).filter { s ->
            taken.none { o -> bodyDist(o.x - s.first, o.y - s.second) < 1.1f } &&
                taken.none { o -> o.called && hypot(o.tx - s.first, o.ty - s.second) < 6f * density }   // ni el que otro ya va a ocupar
        }.minByOrNull { hypot(it.first - c.x, it.second - c.y) } ?: return false
        c.tx = s.first; c.ty = s.second
        c.phase = Phase.WALK; c.blockedSince = 0L; c.bestDist = Float.MAX_VALUE; c.progressAt = now
        return true
    }

    /** Hacia donde mira quien esta en ([x], [y]) para ver el punto ([gx], [gy]). */
    private fun lookAt(x: Float, y: Float, gx: Float, gy: Float): DungeonSpriteRepository.Direction {
        val dx = gx - x; val dy = gy - y
        return if (abs(dx) > abs(dy)) { if (dx > 0) DungeonSpriteRepository.Direction.RIGHT else DungeonSpriteRepository.Direction.LEFT }
        else { if (dy > 0) DungeonSpriteRepository.Direction.DOWN else DungeonSpriteRepository.Direction.UP }
    }

    /** SOLO PARA PRUEBAS: [times] toques sinteticos (solo a esta vista) en el punto (fx, fy) (fracciones del ancho/alto). */
    fun debugTap(fx: Float, fy: Float, times: Int) {
        if (width <= 0) return
        val t0 = android.os.SystemClock.uptimeMillis()
        for (i in 0 until times) {
            val down = MotionEvent.obtain(t0, t0 + i * 120L, MotionEvent.ACTION_DOWN, fx * width, fy * height, 0)
            dispatchTouchEvent(down); down.recycle()
            val up = MotionEvent.obtain(t0, t0 + i * 120L + 40L, MotionEvent.ACTION_UP, fx * width, fy * height, 0)
            dispatchTouchEvent(up); up.recycle()
        }
    }

    /** SOLO PARA PRUEBAS: un punto libre de la zona central del jardin (el mas alejado de todos los Pokemon), en fracciones del
     *  ancho/alto: un toque sobre un Pokemon abre su tarjeta, asi que la prueba de la llamada tiene que tocar suelo vacio. */
    fun debugFreeSpot(): Pair<Float, Float>? {
        if (width <= 0) return null
        var best: Pair<Float, Float>? = null; var bestD = -1f
        for (gx in 1..9) for (gy in 3..8) {
            val x = floorLeft() + (floorRight() - floorLeft()) * gx / 10f
            val y = floorTop() + (floorBottom() - floorTop()) * gy / 10f
            if (!standable(x, y)) continue
            // (tampoco sobre una pelota ni un mueble: un toque ahi coge la pelota o mueve el mueble, no llama)
            if (balls.any { hypot(x - it.x, y - (it.y - ballSize() * 0.4f)) < 2f * ballSize() } || pieces.any { it.leaveAt == 0L && hitRect(it.rect, x, y) }) continue
            val d = critters.filter { it.x >= 0f }.minOfOrNull { hypot(it.x - x, (it.y - y) * 1.5f) } ?: Float.MAX_VALUE
            if (d > bestD) { bestD = d; best = x / width to y / height }
        }
        return best
    }

    /** SOLO PARA PRUEBAS: distancia (px) de cada Pokemon del jugador al punto (fx, fy), p. ej. "bulbasaur 312, eevee 80". */
    fun debugDistances(fx: Float, fy: Float): String =
        critters.filter { !it.visitor && it.x >= 0f }.joinToString(", ") { "${it.name} ${hypot(it.x - fx * width, it.y - fy * height).toInt()}" }

    /** SOLO PARA PRUEBAS: distancia media (px) de los Pokemon del jugador al punto (fx, fy). */
    fun debugMeanDistance(fx: Float, fy: Float): Float {
        val mine = critters.filter { !it.visitor && it.x >= 0f }
        if (mine.isEmpty()) return 0f
        return mine.map { hypot(it.x - fx * width, it.y - fy * height) }.average().toFloat()
    }

    private fun addXpText(c: Critter, now: Long) {
        floats.add(FloatText("+5 XP", c.x, c.y - critterSize() * 0.95f, now, now + 1700L))
    }

    // ---------------------------------------------------------------- dibujo

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Fondo: el jardin a "cubrir" la vista (recorte centrado).
        bg?.let { b ->
            canvas.drawBitmap(b, null, RectF(bgOffX, bgOffY, bgOffX + b.width * bgSc, bgOffY + b.height * bgSc), pixelPaint)
        } ?: canvas.drawColor(0xFF6FB35A.toInt())

        val now = System.currentTimeMillis()
        drawSmoke(canvas, now)

        // Todo lo que tiene "suelo" se dibuja ordenado por su y (lo de mas abajo, delante); lo que se esta
        // arrastrando va el ultimo y un poco levantado, para que se note que esta "en la mano".
        class Item(val baseY: Float, val draw: () -> Unit)
        val items = ArrayList<Item>()
        val lift = 8f * density
        fun lifted(on: Boolean, d: () -> Unit): () -> Unit = if (on) ({ canvas.save(); canvas.translate(0f, -lift); d(); canvas.restore() }) else d
        for (p in pieces) {
            val held = drag == Drag.FURNITURE && dragPiece === p
            // Un mueble que aparece o se retira lo hace con un fundido.
            val alpha = when {
                p.leaveAt != 0L -> (255 * (1f - (now - p.leaveAt).toFloat() / PIECE_FADE_MS)).toInt()
                p.spawnAt != 0L -> (255 * ((now - p.spawnAt).toFloat() / PIECE_FADE_MS)).toInt()
                else -> 255
            }.coerceIn(0, 255)
            val draw = { if (p.kind == Kind.TROUGH) drawTrough(canvas, p) else drawBasket(canvas, p) }
            val faded: () -> Unit = if (alpha >= 255) draw else ({
                val save = canvas.saveLayerAlpha(RectF(p.rect.left - 20f, p.rect.top - 20f, p.rect.right + 20f, p.rect.bottom + 40f), alpha)
                draw(); canvas.restoreToCount(save)
            })
            items.add(Item(if (held) Float.MAX_VALUE else p.rect.bottom, lifted(held, faded)))
        }
        for (b in balls) {
            val held = drag == Drag.BALL && dragBall === b
            items.add(Item(if (held) Float.MAX_VALUE else b.y, lifted(held) { drawBall(canvas, b) }))
        }
        for (c in critters) if (c.x >= 0f) items.add(Item(c.y) { drawCritter(canvas, c) })
        items.sortBy { it.baseY }
        items.forEach { it.draw() }

        // Onda de cada toque sobre el suelo (un oval, como se ve el suelo en esta perspectiva): la grande marca la "llamada".
        for (r in ripples) {
            val t = ((now - r.born).toFloat() / (if (r.big) RIPPLE_MS + 300L else RIPPLE_MS)).coerceIn(0f, 1f)
            val rad = (if (r.big) 22f + 70f * t else 10f + 34f * t) * density
            ripplePaint.strokeWidth = (if (r.big) 3.5f else 2.5f) * density * (1f - 0.5f * t)
            ripplePaint.alpha = (210 * (1f - t)).toInt().coerceIn(0, 255)
            canvas.drawOval(RectF(r.x - rad, r.y - rad * 0.45f, r.x + rad, r.y + rad * 0.45f), ripplePaint)
        }

        // Corazoncitos (pixel art) por encima de todo: suben meciendose y se desvanecen al final.
        if (hearts.isNotEmpty()) {
            val u = max(2f * density, critterSize() * 0.022f)
            for (hh in hearts) {
                val t = ((now - hh.born).toFloat() / hh.life).coerceIn(0f, 1f)
                val hx = hh.x + sin(t * 7f + hh.phase) * 5f * density
                val hy = hh.y - t * 34f * density
                val wPx = heartBmp.width * u * hh.scale; val hPx = heartBmp.height * u * hh.scale
                heartPaint.alpha = (255 * (1f - max(0f, (t - 0.55f) / 0.45f))).toInt().coerceIn(0, 255)
                canvas.drawBitmap(heartBmp, null, RectF(hx - wPx / 2f, hy - hPx / 2f, hx + wPx / 2f, hy + hPx / 2f), heartPaint)
            }
        }

        // "!" breve sobre quien acaba de pillar / de quedarse con ella en el pilla-pilla (pixel art, como los corazones).
        for (c in critters) if (c.exclaimUntil > now && c.x >= 0f && c.leaveAt == 0L) {
            val u = max(2f * density, critterSize() * 0.03f)
            val bw = 7 * u; val bh = 10 * u
            val left = c.x - bw / 2f; val top = headTop(c) - critterSize() * 0.05f - bh - sin(now / 90.0).toFloat() * u
            val p = bangPaint
            p.color = 0xFF3A2A00.toInt(); canvas.drawRect(left - u, top - u, left + bw + u, top + bh + u, p)
            p.color = 0xFFFFD54A.toInt(); canvas.drawRect(left, top, left + bw, top + bh, p)
            p.color = 0xFF3A2A00.toInt()
            canvas.drawRect(left + 3 * u, top + 2 * u, left + 4 * u, top + 6 * u, p)
            canvas.drawRect(left + 3 * u, top + 7 * u, left + 4 * u, top + 8 * u, p)
        }

        for (f in floats) {
            val t = ((now - f.start).toFloat() / (f.until - f.start)).coerceIn(0f, 1f)
            val y = f.y - t * 26f * density
            val alpha = (255 * (1f - max(0f, (t - 0.6f) / 0.4f))).toInt().coerceIn(0, 255)
            xpFill.textSize = 15f * density; xpEdge.textSize = 15f * density
            xpFill.alpha = alpha; xpEdge.alpha = alpha
            canvas.drawText(f.text, f.x, y, xpEdge); canvas.drawText(f.text, f.x, y, xpFill)
        }
    }

    /** Humo de la chimenea del fondo: bocanadas que suben, se ensanchan, derivan con la brisa y se desvanecen.
     *  Se dibujan con "pixeles" del tamaño de los del fondo para que encajen con el pixel art. */
    private fun drawSmoke(canvas: Canvas, now: Long) {
        val sc = bgSc
        for (p in puffs) {
            val t = ((now - p.born).toFloat() / PUFF_LIFE_MS).coerceIn(0f, 1f)
            val gx = CHIMNEY_X + 11f * t + 1.8f * sin(t * 8f + p.seed * 6.28f)
            val gy = CHIMNEY_Y - 34f * t
            val r = 1.6f + 3.4f * t
            val fade = (if (t < 0.08f) t / 0.08f else 1f) * (1f - t).pow(1.1f) * 0.92f
            val a = (fade * 255).toInt().coerceIn(0, 255)
            if (a <= 2) continue
            val ix = gx.roundToInt(); val iy = gy.roundToInt(); val ri = ceil(r).toInt()
            for (dy in -ri..ri) for (dx in -ri..ri) {
                val d2 = (dx * dx + dy * dy).toFloat()
                if (d2 > r * r) continue
                val edge = d2 > (r - 1.2f) * (r - 1.2f)
                smokePaint.color = (a shl 24) or (if (edge) 0xC2C8D6 else 0xECF0F6)
                val l = floor(bgOffX + (ix + dx) * sc); val tp = floor(bgOffY + (iy + dy) * sc)
                canvas.drawRect(l, tp, floor(bgOffX + (ix + dx + 1) * sc), floor(bgOffY + (iy + dy + 1) * sc), smokePaint)
            }
        }
    }

    private fun drawTrough(canvas: Canvas, p: Piece) {
        val trough = p.rect
        canvas.drawBitmap(troughBmp, null, trough, pixelPaint)
        // Gotas mientras alguien bebe EN ESTE bebedero.
        val now = System.currentTimeMillis()
        for (c in critters) if (c.phase == Phase.DO && c.task == Task.DRINK && c.piece === p) {
            val t = ((now - c.doStart) % 600L) / 600f
            for (i in 0 until 3) {
                val tt = (t + i / 3f) % 1f
                val gx = trough.centerX() + (i - 1) * trough.width() * 0.18f
                val gy = trough.top + trough.height() * 0.15f - tt * trough.height() * 0.5f
                dropPaint.alpha = (200 * (1f - tt)).toInt()
                canvas.drawCircle(gx, gy, 2.2f * density, dropPaint)
            }
        }
    }

    // Donde van las manzanas dentro de la cesta (fracciones del ancho/alto de la cesta): fila de atras y de delante.
    private val appleSpots = listOf(0.26f to 0.22f, 0.50f to 0.15f, 0.74f to 0.22f, 0.38f to 0.30f, 0.62f to 0.30f)

    private fun drawBasket(canvas: Canvas, p: Piece) {
        val basket = p.rect
        // Sombra, cesta (asa + cuerpo), las manzanas que quedan y, POR ENCIMA de ellas, el borde delantero.
        canvas.drawOval(RectF(basket.left + basket.width() * 0.04f, basket.bottom - basket.height() * 0.10f, basket.right - basket.width() * 0.04f, basket.bottom + basket.height() * 0.14f), shadowPaint)
        canvas.drawBitmap(basketBackBmp, null, basket, pixelPaint)
        val icon = DungeonSpriteRepository.itemIcon(context, "SWEETAPPLE")
        val half = basket.width() * 0.19f
        for (i in 0 until p.apples.coerceAtMost(appleSpots.size)) {
            val cx = basket.left + basket.width() * appleSpots[i].first
            val cy = basket.top + basket.height() * appleSpots[i].second
            if (icon != null) canvas.drawBitmap(icon, null, RectF(cx - half, cy - half, cx + half, cy + half), pixelPaint)
            else { dropPaint.color = 0xFFD93A3A.toInt(); canvas.drawCircle(cx, cy, half * 0.9f, dropPaint); dropPaint.color = 0xFF7CCBF2.toInt() }
        }
        canvas.drawBitmap(basketLipBmp, null, basket, pixelPaint)
    }

    private fun drawBall(canvas: Canvas, ball: Ball) {
        val s = ballSize()
        canvas.drawOval(RectF(ball.x - s * 0.5f, ball.y - s * 0.12f, ball.x + s * 0.5f, ball.y + s * 0.2f), shadowPaint)
        // Un pequeño bote segun la velocidad (quieta en la mano mientras se arrastra).
        val bounce = if (ball.held) 0f else min(1f, hypot(ball.vx, ball.vy) / (width * 0.4f)) * s * 0.25f * abs(sin(System.currentTimeMillis() / 120.0)).toFloat()
        canvas.drawBitmap(ballBmp, null, RectF(ball.x - s / 2f, ball.y - s - bounce + s * 0.2f, ball.x + s / 2f, ball.y + s * 0.2f - bounce), pixelPaint)
    }

    /** Corazon de pixel art (7x6 con borde oscuro y un brillo): 9x8 px que se escalan sin suavizar. */
    private fun makeHeartBitmap(): Bitmap {
        val pat = listOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
        val edge = 0xFF6A1030.toInt(); val fill = 0xFFFF5C8A.toInt(); val shine = 0xFFFFB6CB.toInt()
        val bmp = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
        fun on(r: Int, q: Int) = r in pat.indices && q in 0 until 7 && pat[r][q] == 'X'
        for (r in -1..6) for (q in -1..7) {
            when {
                on(r, q) -> bmp.setPixel(q + 1, r + 1, fill)
                on(r - 1, q) || on(r + 1, q) || on(r, q - 1) || on(r, q + 1) -> bmp.setPixel(q + 1, r + 1, edge)
            }
        }
        bmp.setPixel(2, 2, shine)
        return bmp
    }

    private fun drawCritter(canvas: Canvas, c: Critter) {
        val now = System.currentTimeMillis()
        // Un visitante entra y sale con un fundido (los Pokemon del jugador siempre estan del todo).
        val alpha = when {
            !c.visitor -> 255
            c.leaveAt != 0L -> (255 * (1f - (now - c.leaveAt).toFloat() / VISITOR_FADE_MS)).toInt()
            c.script == Script.ENTER && !c.emerged -> 0     // aun dentro de la casa, esperando su turno
            // Al subir por el camino se "mete" en la casa: se desvanece en el ultimo tramo antes de la puerta.
            c.script == Script.EXIT -> (255 * ((c.y - doorY()) / (height * 0.06f))).toInt()
            else -> (255 * ((now - c.spawnAt).toFloat() / VISITOR_FADE_MS)).toInt()
        }.coerceIn(0, 255)
        if (alpha < 255) {
            if (alpha == 0) return
            val s = critterSize()
            val save = canvas.saveLayerAlpha(RectF(c.x - s, c.y - s, c.x + s, c.y + s * 0.4f), alpha)
            drawCritterOpaque(canvas, c, now)
            canvas.restoreToCount(save)
        } else drawCritterOpaque(canvas, c, now)
    }

    private fun drawCritterOpaque(canvas: Canvas, c: Critter, now: Long) {
        val s = critterSize()
        val frames = DungeonSpriteRepository.frames(context, c.name, c.shiny, c.dir)
        // Los dos del pilla-pilla (el que persigue Y el perseguido) se ven CORRIENDO: rayas de velocidad detras (al lado contrario del que
        // mira), zancada mas rapida y un pequeño bote al correr. El que persigue las lleva mas largas.
        val running = c.task == Task.TAG && c.phase == Phase.WALK && c.speedMul > 0.5f && tag?.players?.contains(c) == true   // parado o arrancando: aun no corre
        // Pequeño bote mientras come/bebe (cabeza agachada) para que se note la accion; al correr, un saltito rapido.
        val bob = when {
            c.phase == Phase.DO -> abs(sin((now - c.doStart) / 140.0)).toFloat() * s * 0.07f
            running -> -abs(sin(now / 80.0 + c.x * 0.01)).toFloat() * s * 0.05f
            else -> 0f
        }
        canvas.drawOval(RectF(c.x - s * 0.30f, c.y - s * 0.07f, c.x + s * 0.30f, c.y + s * 0.07f), shadowPaint)
        if (running) {
            val back = when (c.dir) { DungeonSpriteRepository.Direction.LEFT -> 1f; DungeonSpriteRepository.Direction.RIGHT -> -1f; else -> 0f }
            val isChaser = tag?.chaser === c
            val len = s * (if (isChaser) 0.26f else 0.20f)
            ripplePaint.strokeWidth = 2.5f * density; ripplePaint.alpha = if (isChaser) 200 else 160
            val backY = if (c.dir == DungeonSpriteRepository.Direction.DOWN) -1f else 1f    // al andar hacia abajo, "detras" es arriba
            if (back != 0f) {
                for (k in -1..1) {
                    val ly = c.y - s * 0.30f + k * s * 0.09f
                    canvas.drawLine(c.x + back * s * 0.24f, ly, c.x + back * (s * 0.24f + len), ly, ripplePaint)
                }
            } else {
                // Andando de frente o de espaldas: dos rayas verticales a los lados del cuerpo, hacia atras.
                for (k in intArrayOf(-1, 1)) {
                    val lx = c.x + k * s * 0.30f; val y0 = c.y - s * 0.38f
                    canvas.drawLine(lx, y0, lx, y0 + backY * len, ripplePaint)
                }
            }
        }
        if (!frames.isNullOrEmpty()) {
            // Zancada: un fotograma cada cierta distancia andada (mas rapida al correr)
            val idx = if (c.phase == Phase.WALK) ((c.walked * (if (running) 1.8f else 1f) / (width * 0.05f)).toInt()) % frames.size else 0
            canvas.drawBitmap(frames[idx], null, RectF(c.x - s / 2f, c.y - s * 0.86f + bob, c.x + s / 2f, c.y + s * 0.14f + bob), pixelPaint)
        }
    }

    // ---------------------------------------------------------------- tacto

    private fun critterAt(x: Float, y: Float): Critter? {
        val s = critterSize()
        // El de mas abajo (el que se ve delante) tiene prioridad.
        return critters.filter { it.x >= 0f && it.leaveAt == 0L }.sortedByDescending { it.y }.firstOrNull { c ->
            x in (c.x - s / 2f)..(c.x + s / 2f) && y in (c.y - s * 0.86f)..(c.y + s * 0.14f)
        }
    }

    /** La pelota que hay en (x, y) (la mas cercana si se tocan dos), o null. */
    private fun ballAt(x: Float, y: Float): Ball? {
        val r = max(ballSize() * 0.9f, 28f * density)
        return balls.filter { hypot(x - it.x, y - (it.y - ballSize() * 0.4f)) <= r }
            .minByOrNull { hypot(x - it.x, y - (it.y - ballSize() * 0.4f)) }
    }

    /** Dos muebles no pueden quedar uno encima del otro: el que se mueve ([dragged]) se aparta por el lado de menor solape
     *  (con un pequeño hueco) y se vuelve a encajar en el jardin. */
    private fun separateFurniture(dragged: Piece) {
        if (width <= 0) return
        // Entre dos muebles tiene que caber una pelota: con un hueco mas estrecho quedaba atrapada en la rendija (dentro de uno u otro).
        val gap = max(6f * density, 2f * ballR() + 2f * density)
        repeat(3) {
            var moved = false
            for (other in pieces) {
                if (other === dragged || other.leaveAt != 0L) continue
                val me = dragged.foot; val o = other.foot
                val l = o.left - gap; val r = o.right + gap; val t = o.top - gap; val b = o.bottom + gap
                if (!(me.left < r && me.right > l && me.top < b && me.bottom > t)) continue
                val moves = floatArrayOf(-(me.right - l), r - me.left, -(me.bottom - t), b - me.top)   // izq, der, arriba, abajo
                var best = 0
                for (i in 1..3) if (abs(moves[i]) < abs(moves[best])) best = i
                val dx = if (best < 2) moves[best] else 0f; val dy = if (best >= 2) moves[best] else 0f
                dragged.base = clampBase((dragged.base.first * width + dx) / width to (dragged.base.second * height + dy) / height, dragged.w, dragged.h)
                layoutFurniture()
                moved = true
            }
            if (!moved) return
        }
    }

    /** Desfase entre el dedo y el punto de apoyo del mueble, para que no "salte" al empezar a arrastrarlo. */
    private fun setFurnitureOffset(p: Piece, x: Float, y: Float) {
        dragDx = p.base.first * width - x; dragDy = p.base.second * height - y
    }

    private fun hitRect(r: RectF, x: Float, y: Float): Boolean {
        val m = 8f * density
        return x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downCritter = null; pendingPiece = null; dragPiece = null
                val critter = critterAt(event.x, event.y)
                val furniture = pieces.filter { it.leaveAt == 0L && hitRect(it.rect, event.x, event.y) }.maxByOrNull { it.rect.bottom }   // el de delante
                val ball = ballAt(event.x, event.y)
                when {
                    // Prioridad: pelota > (Pokemon / mueble). Donde se solapan un Pokemon y un mueble (suelen estar
                    // justo ahi comiendo o bebiendo): un toque corto es del Pokemon y, si el dedo se arrastra, del mueble.
                    ball != null -> {
                        drag = Drag.BALL; dragBall = ball; ball.held = true; ball.vx = 0f; ball.vy = 0f
                        dragDx = ball.x - event.x; dragDy = ball.y - event.y
                    }
                    critter != null && furniture != null -> { drag = Drag.CRITTER; downCritter = critter; pendingPiece = furniture; setFurnitureOffset(furniture, event.x, event.y) }
                    critter != null -> { drag = Drag.CRITTER; downCritter = critter }
                    furniture != null -> { drag = Drag.FURNITURE; dragPiece = furniture; setFurnitureOffset(furniture, event.x, event.y) }
                    // Suelo vacio del jardin: un toque suelto es una "llamada" (varios seguidos juntan a los Pokemon, ver registerGroundTap).
                    event.x in floorLeft()..floorRight() && event.y in floorTop()..floorBottom() -> drag = Drag.GROUND
                    else -> { drag = Drag.NONE; return false }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                // Un toque sobre un Pokemon encima de un mueble que se convierte en arrastre pasa a mover el mueble.
                val pf = pendingPiece
                if (drag == Drag.CRITTER && pf != null && hypot(event.x - downX, event.y - downY) > 12f * density) {
                    drag = Drag.FURNITURE; dragPiece = pf; downCritter = null; pendingPiece = null
                }
                when (drag) {
                    Drag.BALL -> dragBall?.let { b ->
                        b.x = (event.x + dragDx).coerceIn(floorLeft(), floorRight())
                        b.y = (event.y + dragDy).coerceIn(floorTop(), floorBottom())
                        // La pelota en la mano tampoco puede meterse dentro de un mueble.
                        scratch[0] = b.x; scratch[1] = b.y
                        clearOfFurniture(scratch, ballR(), ballR())
                        b.x = scratch[0]; b.y = scratch[1]
                    }
                    Drag.FURNITURE -> dragPiece?.let { p ->
                        p.base = clampBase((event.x + dragDx) / width to (event.y + dragDy) / height, p.w, p.h)
                        layoutFurniture()
                        separateFurniture(p)
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = event.actionMasked == MotionEvent.ACTION_UP
                when (drag) {
                    Drag.BALL -> dragBall?.let { b ->
                        // Lanzamiento: la velocidad del dedo al soltar (con tope) pasa a la pelota, y los dos
                        // Pokemon libres mas cercanos van a por ella.
                        tracker?.addMovement(event)
                        tracker?.computeCurrentVelocity(1000)
                        var vx = if (up) tracker?.xVelocity ?: 0f else 0f
                        var vy = if (up) tracker?.yVelocity ?: 0f else 0f
                        val sp = hypot(vx, vy); val cap = width * 2.4f
                        if (sp > cap) { vx *= cap / sp; vy *= cap / sp }
                        b.held = false
                        b.vx = vx * 0.9f; b.vy = vy * 0.9f
                        sendFetchers(b)
                    }
                    Drag.FURNITURE -> dragPiece?.let { onFurnitureMoved?.invoke(it.key, it.base.first, it.base.second) }
                    Drag.CRITTER -> {
                        // Un toque (no un arrastre) sobre un Pokemon: ver sus necesidades.
                        val moved = hypot(event.x - downX, event.y - downY)
                        val c = downCritter
                        if (up && c != null && moved < 24f * density) {
                            if (c.visitor) onVisitorTap?.invoke(c.name, c.level) else onCritterTap?.invoke(c.name, c.shiny)
                            performClick()
                        }
                    }
                    Drag.GROUND -> if (up && hypot(event.x - downX, event.y - downY) < 24f * density) { registerGroundTap(event.x, event.y); performClick() }
                    Drag.NONE -> {}
                }
                drag = Drag.NONE; downCritter = null; dragPiece = null; dragBall = null
                tracker?.recycle(); tracker = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    // ---------------------------------------------------------------- arte (pixel art generado por codigo)

    /** Bebedero de madera con agua (40x22 px, se escala sin suavizar). */
    private fun makeTroughBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(40, 22, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { isAntiAlias = false }
        fun rect(l: Int, t: Int, r: Int, b: Int, color: Long) { p.color = color.toInt(); c.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), p) }
        rect(3, 19, 8, 22, 0xFF3B2412)          // patas
        rect(32, 19, 37, 22, 0xFF3B2412)
        rect(1, 6, 39, 20, 0xFF3B2412)          // contorno del cuerpo
        rect(2, 7, 38, 19, 0xFF9A6A3A)          // madera
        rect(2, 7, 38, 9, 0xFFC08A50)           // labio claro
        for (x in intArrayOf(11, 20, 29)) rect(x, 9, x + 1, 19, 0xFF7A4E27)   // tablones
        rect(2, 17, 38, 19, 0xFF7A4E27)         // base mas oscura
        rect(3, 1, 37, 9, 0xFF3B2412)           // abertura (contorno)
        rect(4, 2, 36, 8, 0xFF4FB3E8)           // agua
        rect(4, 2, 36, 4, 0xFF9AD8F5)           // brillo superior
        rect(9, 5, 14, 6, 0xFFDFF4FF)           // destellos
        rect(23, 6, 29, 7, 0xFFDFF4FF)
        return bmp
    }

    /** Cesta de mimbre (40x28 px). [front] = false: asa, cuerpo y fondo del interior (va DETRAS de las manzanas);
     *  true: solo el borde delantero (va POR ENCIMA, para que las manzanas queden "dentro"). */
    private fun makeBasketBitmap(front: Boolean): Bitmap {
        val w = 40; val h = 28
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outline = 0xFF3F2814.toInt()
        val wickerA = 0xFFB98443.toInt(); val wickerB = 0xFF9A6A32.toInt(); val wickerHi = 0xFFD9A55E.toInt(); val wickerLo = 0xFF7A5226.toInt()
        val inside = 0xFF4A2F18.toInt()
        fun put(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) bmp.setPixel(x, y, c) }
        if (!front) {
            // asa: un arco por encima de la cesta (2 px de grosor)
            for (i in 0..60) {
                val t = i / 60f
                val x = (4 + 32 * t).toInt(); val y = (13 - 12 * sin(PI * t)).toInt()
                put(x, y, outline); put(x, y + 1, 0xFF8A5C2E.toInt()); put(x + 1, y + 1, 0xFF8A5C2E.toInt()); put(x, y + 2, outline)
            }
            // interior visible (la boca de la cesta, oscura)
            for (y in 10..14) for (x in 3..36) put(x, y, inside)
            // cuerpo: trapecio que se estrecha hacia abajo, tejido de mimbre
            for (y in 13 until h) {
                val left = 2 + ((y - 13) * 0.22f).toInt(); val right = 37 - ((y - 13) * 0.22f).toInt()
                for (x in left..right) {
                    var c = if (((x + (y / 3) * 3) % 6) < 3) wickerA else wickerB
                    if (y % 3 == 2) c = wickerLo                      // renglones del tejido
                    if (y == h - 1 || x == left || x == right) c = outline
                    put(x, y, c)
                }
            }
        } else {
            // borde delantero (labio) sobre las manzanas: banda clara de mimbre con contorno
            for (y in 13..17) for (x in 1..38) {
                val edge = (y == 13 || y == 17 || x == 1 || x == 38)
                put(x, y, if (edge) outline else if (y == 14) wickerHi else if ((x / 2) % 2 == 0) wickerA else wickerB)
            }
        }
        return bmp
    }

    /** Pelota de playa (16x16 px): gajos rojo / blanco / amarillo / azul con brillo. */
    private fun makeBallBitmap(): Bitmap {
        val n = 16
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cols = intArrayOf(0xFFE63946.toInt(), 0xFFF4F1EA.toInt(), 0xFFFFC93C.toInt(), 0xFFF4F1EA.toInt(), 0xFF3A86FF.toInt(), 0xFFF4F1EA.toInt())
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x - 7.5f; val dy = y - 7.5f
            val d = hypot(dx, dy)
            if (d > 7.7f) continue
            var color = if (d > 6.5f) 0xFF2B2B3A.toInt() else {
                val ang = atan2(dy, dx) + PI.toFloat()
                cols[((ang / (2f * PI.toFloat())) * 6f).toInt().coerceIn(0, 5)]
            }
            if (d <= 6.5f && dx + dy > 5f) color = darken(color)      // sombra abajo a la derecha
            if (hypot(x - 5f, y - 5f) < 1.7f) color = Color.WHITE      // brillo
            bmp.setPixel(x, y, color)
        }
        return bmp
    }

    private fun darken(c: Int): Int = Color.rgb((Color.red(c) * 0.78f).toInt(), (Color.green(c) * 0.78f).toInt(), (Color.blue(c) * 0.78f).toInt())

    companion object {
        const val BASKET_MAX_APPLES = 5
        // La cesta se repone RAPIDO (pedido del usuario: antes 20 s por manzana y la escena inventaba una si estaba vacia).
        private const val BASKET_REFILL_MS = 2_500L
        // Corazoncitos al comer / beber / dar patadas a la pelota, y fundido de entrada y salida de los visitantes.
        private const val HEART_EVERY_MS = 520L
        private const val HEART_LIFE_MS = 1_100L
        private const val MAX_HEARTS = 40
        private const val VISITOR_FADE_MS = 800L
        // Puerta de la casa en bg_guarderia (pixeles del dibujo): el pie de la puerta, sobre el escalon. Los visitantes salen y entran por ella.
        private const val DOOR_X = 67.5f
        private const val DOOR_Y = 79f
        private const val ENTER_STAGGER_MS = 2_000L    // entre dos visitantes que llegan a la vez
        private const val ENTER_DELAY_MS = 1_500L      // los que llegan esperan dentro de la casa este tiempo (y a que se vayan los que se van)
        private const val LEAVE_GHOST_MS = 3_500L      // si un visitante que se va no llega al camino en este tiempo, deja de ser solido y pasa entre los demas
        private const val LEAVE_TIMEOUT_MS = 40_000L   // y si ni asi llega, se desvanece donde esta
        // Hitbox entre Pokemon: si otro le tapa el paso, rodea girando el rumbo (siempre hacia el mismo lado, asi dos que se cruzan
        // se esquivan en vez de bloquearse) y, si lleva este tiempo sin poder avanzar, cambia de plan.
        private const val AVOID_RANGE = 1.8f          // a que distancia (en huellas) empieza a apartarse de otro Pokemon
        private const val HEAD_BLEND = 0.2f           // cuanto gira el rumbo hacia el deseado en cada fotograma (inercia)
        private const val DITHER_WINDOW_MS = 2_000L   // cada cuanto se comprueba que quien anda se mueve de verdad
        // Pilla-pilla (uno contra uno, dos pillados): cada TAG_CHECK_MS hay una probabilidad TAG_CHANCE de empezar una partida; entre partidas
        // pasan TAG_COOLDOWN_MIN_MS..TAG_COOLDOWN_MAX_MS. Quien persigue corre mas que quien huye (TAG_CHASER_SPEED > TAG_RUNNER_SPEED > 1).
        private const val TAG_FIRST_DELAY_MS = 6_000L
        private const val TAG_CHECK_MS = 4_000L
        private const val TAG_CHANCE = 0.3f
        private const val TAG_TIMEOUT_MS = 45_000L    // si pasado este tiempo no se han pillado uno a otro, la partida acaba igual
        private const val TAG_COOLDOWN_MIN_MS = 12_000L
        private const val TAG_COOLDOWN_MAX_MS = 30_000L
        private const val TAG_TOUCH = 1.18f           // distancia entre huellas (1 = tocandose) a la que se pilla
        private const val TAG_IMMUNITY_MS = 1_800L
        private const val TAG_CHASER_SPEED = 1.55f    // en uno contra uno el que persigue tiene que ganar terreno rapido para que cada pillado
        private const val TAG_RUNNER_SPEED = 1.25f    // no se alargue (con 1,3 contra 1,15 un pillado tardaba ~20 s); el que huye tambien corre
        private const val TAG_FLEE_EVERY_MS = 900L
        private const val TAG_STUCK_MS = 3_500L
        // Distancia (en tamaños de Pokemon, ~280 px cada uno) entre quien huye y quien persigue: no se deja alcanzar de golpe (minima) ni se va
        // lejos a esperar en una esquina (maxima: pasada esa, vuelve hacia el otro).
        private const val TAG_MIN_DIST = 1.2f
        private const val TAG_MAX_DIST = 2.3f
        // Para EMPEZAR una persecucion los dos tienen que estar entre TAG_START_MIN y TAG_START_MAX tamaños de Pokemon (ni muy cerca ni muy lejos).
        private const val TAG_START_MIN = 1.3f
        private const val TAG_START_MAX = 2.6f
        // Ventaja del que huye: el que persigue (al empezar la partida y tras cada pillado) se queda parado TAG_FREEZE_MS y luego acelera en TAG_RAMP_MS.
        private const val TAG_FREEZE_MS = 1_000L
        private const val TAG_RAMP_MS = 800L
        private const val TAG_YIELD_RANGE = 2.0f      // a que distancia (en huellas) de un jugador se aparta quien no juega
        private const val TAG_YIELD_EVERY_MS = 900L
        private const val FACE_DWELL_MS = 320L        // tiempo minimo entre dos cambios de cara al andar
        private const val FACE_EMA = 0.85f            // cuanto pesa el movimiento anterior al decidir hacia donde mira
        private const val BLOCKED_GIVE_UP_MS = 1_200L
        // Llamar a los Pokemon: CALL_TAPS toques seguidos, en menos de CALL_WINDOW_MS y a menos de CALL_RADIUS_DP del primero; se
        // quedan CALL_HOLD_MS junto al punto cuando llegan.
        private const val CALL_TAPS = 3
        private const val CALL_WINDOW_MS = 2_200L
        private const val CALL_RADIUS_DP = 70f
        private const val CALL_HOLD_MS = 7_000L
        private const val CALL_RETRIES = 4
        private const val RIPPLE_MS = 550L
        // Muebles segun la gente: un juego (bebedero + cesta) por cada STATION_EVERY Pokemon en el jardin (ver stationSets); aparecen y se
        // retiran con un fundido de PIECE_FADE_MS.
        private const val STATION_EVERY = 4
        private const val PIECE_FADE_MS = 700L
        // Boca de la chimenea en el fondo (pixeles de bg_guarderia, 135x300) y ritmo del humo.
        private const val CHIMNEY_X = 92.5f
        private const val CHIMNEY_Y = 21f
        private const val PUFF_EVERY_MS = 700L
        private const val PUFF_LIFE_MS = 5000L
    }
}
