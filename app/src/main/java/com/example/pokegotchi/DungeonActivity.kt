package com.example.pokegotchi

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Pantalla de la mazmorra (ver plan: "vamos a quitar el widget... hacer lo mismo bien en la
 * propia aplicacion") - sustituye por completo al widget de pantalla de inicio retirado. Dos
 * estados: si hay un explorador asignado, DungeonView lo dibuja en vivo (fluido, sprites reales,
 * camara siguiendo); si no, un selector para elegir cual mandar (o reintentar tras una derrota,
 * viendo el resumen de la carrera anterior).
 *
 * El avance de FONDO (DungeonService, ~1 min mientras esta pantalla no esta abierta) se PAUSA
 * mientras esta pantalla esta en primer plano (onResume) y se retoma al salir (onPause) - evita
 * que el ciclo de fondo y la animacion en vivo muten el mismo estado a la vez. El servicio en si
 * (y su notificacion permanente) NO se para al entrar aqui, solo deja de simular pasos.
 */
class DungeonActivity : AppCompatActivity() {

    private lateinit var dungeonView: DungeonView
    private lateinit var liveRoot: View
    private lateinit var pickerRoot: View
    private lateinit var pickerGrid: LinearLayout
    private lateinit var pickerScroll: android.widget.ScrollView
    private lateinit var pickerTitle: TextView
    private lateinit var lastSummaryText: TextView
    private lateinit var runSummaryText: TextView
    private lateinit var abandonButton: View
    // Mensaje + botones de la pantalla de CIERRE (ver showCountdownOnly) - pedido explicito del
    // usuario: "no has agregado lo de salir del bucle con el pokemon actual. mientras esperas el
    // tiempo de cooldon agrega un boton para cambiar de pokemon y que te deje elegir si es bucle
    // otra vez o no", aclarado despues dos veces: (1) "si no tocas nada el bucle sigue [solo, en
    // segundo plano], el boton es para sustituir el pokemon del bucle o hacer la siguiente carrera
    // con otro pokemon" - NO mostrar la rejilla entera todo el rato, un mensaje + UN boton que la
    // revela solo si el jugador quiere intervenir; y (2), corrigiendo el diseño de (1): "el
    // cooldown... es como que la mazmorra esta cerrada. Entonces no puede entrar ningun Pokemon...
    // no porque [el Pokemon] tenga que descansar" - la rejilla que revela el boton NO asigna a
    // nadie de inmediato (eso reabriria la mazmorra antes de tiempo), solo PRE-ELIGE quien entrara
    // en cuanto el cierre termine (ver [pickingForCooldownQueue]/DungeonState.queueNextExplorer).
    private lateinit var cooldownMessageText: TextView
    private lateinit var cooldownActionsRow: View
    private lateinit var switchPokemonButton: View
    private lateinit var cooldownAbandonButton: View
    // true mientras la rejilla mostrada es la de "elegir quien entra cuando reabra" (revelada por
    // switchPokemonButton durante el cierre) - decide si tocar una celda ASIGNA de inmediato
    // (showSendModeDialog → assignToDungeon, caso normal en IDLE) o solo la PRE-ELIGE sin abrir la
    // mazmorra antes de tiempo (→ queueNextExplorer). Se resetea a false en cuanto se vuelve a
    // mostrar la pantalla normal (IDLE o el mensaje de cierre sin rejilla).
    private var pickingForCooldownQueue = false
    // Orden del selector de Pokemon - pedido explicito del usuario: "pon los niveles de los
    // pokemon y filtralo por nivel". Descendente por defecto (el mas fuerte primero, mas util
    // para elegir a quien mandar a un piso concreto); un TextView en la cabecera del selector
    // permite invertirlo.
    // Buscador, orden y filtros del selector (los mismos que el de la guarderia; orden y filtros se recuerdan, lo que se escribe no).
    private var pickerSortMode = 0
    private var pickerQuery = ""
    private var pickerOnlyFree = false
    private var pickerOnlyShiny = false
    private lateinit var pickerControls: LinearLayout
    private lateinit var pickerCountText: TextView
    // Compañeros elegidos para la PROXIMA carrera (pedido explicito del usuario: el explorador extra
    // es un acompañante en la MISMA mazmorra, y se reparten lo que ganan - ver ExplorerPartyRules).
    // Se precargan con los de la ultima carrera (los que sigan pudiendo ir) y se pasan al asignar o
    // al pre-elegir durante el cierre. [pickingCompanion] = true mientras la rejilla sirve para
    // elegir un COMPAÑERO en vez del explorador.
    private val chosenCompanions = ArrayList<Pair<String, Boolean>>()
    private var pickingCompanion = false
    // Explorador ya elegido que esta esperando a que elijas su compañero (se pulso "Añadir un compañero" en la pregunta de
    // como mandarlo): al elegir el compañero se vuelve a esa pregunta, con el compañero ya puesto.
    private var pendingExplorer: Pair<String, Boolean>? = null
    private var lastSendDialog: androidx.appcompat.app.AlertDialog? = null   // solo para el volcado de pruebas
    // Cuenta atras del cooldown de modo continuo (pedido explicito del usuario) - solo mientras
    // esta pantalla esta abierta y en ese estado, un tick por segundo para refrescar el texto de
    // "vuelve en X". No resuelve el cooldown el mismo (eso lo hace SIEMPRE DungeonService, aqui
    // solo se refresca lo que ya paso) - se limita a detectar cuando ya se resolvio para pasar a
    // la vista en vivo sola, sin que el usuario tenga que salir y volver a entrar. Se para en
    // cuanto el jugador toca "Cambiar de Pokémon" (ver su listener) - a partir de ahi esta pantalla
    // ya no debe cambiar sola debajo de sus dedos mientras elige.
    private val cooldownHandler = Handler(Looper.getMainLooper())
    private val cooldownTicker = object : Runnable {
        override fun run() {
            if (!DungeonState.isInCooldown(this@DungeonActivity)) {
                showPickerOrLive()
                return
            }
            refreshCooldownCountdown()
            cooldownHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El sentido del orden por nivel del selector se recuerda entre aperturas y arranques (UiPrefs).
        pickerSortMode = UiPrefs.int(this, UiPrefs.DUNGEON_SORT_MODE, if (UiPrefs.bool(this, UiPrefs.DUNGEON_SORT_DESC, true)) 0 else 1).takeIf { it in 0..2 } ?: 0
        pickerOnlyFree = UiPrefs.bool(this, UiPrefs.DUNGEON_ONLY_FREE, false)
        pickerOnlyShiny = UiPrefs.bool(this, UiPrefs.DUNGEON_ONLY_SHINY, false)
        // Bug real reportado por el usuario: "ahora hay dos textos de pokegotchi" - NO era
        // MainActivity transparentandose detras (se probo desplazar/reposicionar la ventana entera
        // sin ningun cambio visible, descartandolo) - era la propia barra de titulo por defecto
        // del dialogo (Theme.Material3.DayNight.Dialog), que "android:windowNoTitle" en el tema NO
        // llega a suprimir del todo para AppCompatActivity (ese atributo es del framework; hace
        // falta pedir la feature explicitamente en codigo, antes de inflar el layout). Esa barra
        // mostraba el nombre de la app ("PokeGotchi", con esquinas redondeadas de tarjeta) justo
        // encima de la cabecera roja propia de activity_dungeon.xml - de ahi el duplicado.
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // Ventana flotante, no a pantalla completa (pedido explicito del usuario: "no quiero que
        // se vea en pantalla completa... algo opcional") - tamaño real en dp aqui, el tema
        // (Theme.PokeGotchi.Dungeon) solo pone windowIsFloating.
        val dm = resources.displayMetrics
        window.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.75f).toInt())
        // Pedido explicito del usuario: "puedes hacer que la pantalla no se apague en esta
        // ventana?" - solo mientras esta pantalla esta abierta, se libera sola al salir (no hace
        // falta quitar el flag a mano, la propia Window lo hace al destruirse la Activity).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_dungeon)
        dungeonView = findViewById(R.id.dungeon_view)
        liveRoot = findViewById(R.id.dungeon_live_root)
        pickerRoot = findViewById(R.id.dungeon_picker_root)
        pickerGrid = findViewById(R.id.dungeon_picker_grid)
        pickerScroll = findViewById(R.id.dungeon_picker_scroll)
        pickerScroll.setOnScrollChangeListener { _, _, _, _, _ -> addPickerRowsIfNeeded() }
        pickerControls = buildPickerControls()
        (pickerGrid.parent as ViewGroup).let { it.addView(pickerControls, it.indexOfChild(pickerGrid)) }
        pickerTitle = findViewById(R.id.dungeon_picker_title)
        lastSummaryText = findViewById(R.id.dungeon_last_summary)
        runSummaryText = findViewById(R.id.dungeon_run_summary)
        abandonButton = findViewById(R.id.dungeon_abandon)
        cooldownMessageText = findViewById(R.id.dungeon_cooldown_message)
        cooldownActionsRow = findViewById(R.id.dungeon_cooldown_actions)
        switchPokemonButton = findViewById(R.id.dungeon_switch_pokemon)
        cooldownAbandonButton = findViewById(R.id.dungeon_cooldown_abandon)
        findViewById<View>(R.id.dungeon_back).setOnClickListener { finish() }
        // Los compañeros de la ultima carrera que sigan valiendo, para no tener que elegirlos otra vez.
        chosenCompanions.addAll(
            DungeonState.companions(this).filter { (n, s) -> DungeonState.companionBlockReason(this, n, s, null) == null }
                .take(DungeonState.maxCompanions(this))
        )
        abandonButton.setOnClickListener {
            dungeonView.stop()
            DungeonState.abandon(this)
            NotificationHelper.refreshDungeonNotification(this)
            showPickerOrLive()
        }
        cooldownAbandonButton.setOnClickListener {
            // NO reabre antes de tiempo (la mazmorra sigue cerrada su tiempo entero) - solo
            // cancela a quien se le habia pre-elegido, ver DungeonState.cancelQueuedExplorer.
            DungeonState.cancelQueuedExplorer(this)
            NotificationHelper.refreshDungeonNotification(this)
            showPickerOrLive()
        }
        // "Cambiar de Pokémon" - revela la rejilla completa SOLO cuando el jugador quiere
        // intervenir; si no se toca nada aqui, el bucle sigue solo en segundo plano (pedido
        // explicito del usuario, ver comentario de los campos de arriba). Tocar una celda aqui NO
        // asigna de inmediato (pickingForCooldownQueue=true se lo indica a showSendModeDialog).
        switchPokemonButton.setOnClickListener {
            cooldownHandler.removeCallbacks(cooldownTicker)
            cooldownMessageText.visibility = View.GONE
            cooldownActionsRow.visibility = View.GONE
            pickerTitle.visibility = View.VISIBLE
            pickerTitle.text = "Elige quién entra cuando la mazmorra reabra"
            pickingForCooldownQueue = true
            buildPickerGrid()
        }

        dungeonView.onRunEnded = {
            runOnUiThread {
                // Sin Toast aqui (pedido explicito del usuario: "quitame las notificaciones que
                // aparecen abajo como pop-ups... vamos a tener un sitio especifico para eso") -
                // el resumen ya se ve en el selector (dungeon_last_summary) al volver.
                NotificationHelper.refreshDungeonNotification(this)
                showPickerOrLive()
            }
        }
        // Sin Toasts por combate/objeto/piso (pedido explicito del usuario, ver arriba) - en su
        // lugar se refresca el resumen PERSISTENTE de la carrera (XP/niveles simulados/objetos),
        // pensado para poder juzgar de un vistazo si el equilibrio actual tiene sentido.
        dungeonView.listener = DungeonView.Listener { refreshRunSummaryText() }
    }

    /** XP ganada/niveles/objetos recogidos de la carrera EN CURSO - pedido explicito del usuario
     *  en vez de avisos puntuales: "pon abajo un resumen de lo que has ganado y la
     *  experiencia... asi empezamos a ver si esta nivelado o no, que items se estan usando".
     *  [DungeonState.simulatedLevel] ya coincide con el nivel real en PetState desde que
     *  DungeonSimulator.APPLY_REAL_CANDY_XP esta en true (los caramelos aplican la XP real en el
     *  mismo momento en que se recogen) - el texto decia "(simulado)" de cuando el flag estaba en
     *  false y no se quito al activarlo; verificado en el propio dispositivo que el numero SI es
     *  el real (run_start_xp+run_xp_gained coincide exactamente con el XP real guardado). */
    private fun refreshRunSummaryText() {
        val species = DungeonState.currentSpecies(this) ?: return
        val levelsGained = (DungeonState.simulatedLevel(this) - DungeonState.runStartLevel(this)).coerceAtLeast(0)
        // "🔁 " si el modo continuo esta activo (pedido explicito del usuario) - para que se note
        // en la propia vista en vivo que, al acabar esta carrera, empezara otra sola.
        val repeatPrefix = if (DungeonState.autoRepeat(this)) "🔁 " else ""
        // Con compañeros: se nombran, y el XP que se ve es el de CADA UNO (se reparte a partes iguales).
        val companions = DungeonState.companions(this)
        val partyText = if (companions.isEmpty()) "" else " + " + companions.joinToString(" + ") { PetState.displayLabel(it.first) }
        runSummaryText.text = "$repeatPrefix${PetState.displayLabel(species)}$partyText · +${DungeonSimulator.formatXpAmount(DungeonState.runXpGained(this))} XP${if (companions.isEmpty()) "" else " c/u"} · " +
            "$levelsGained ${if (levelsGained == 1) "nivel" else "niveles"} · " +
            "${DungeonState.runItemsCollected(this)} objetos recogidos"
    }

    /** Lo que resaltan las pistas de esta pantalla (ver TutorialTips.stepsFor): la rejilla de elegir explorador y la caja de compañeros. */
    private fun tipTarget(id: String): View? = when (id) {
        "picker" -> findViewById<View>(R.id.dungeon_picker_grid)
        "party" -> findViewById<View>(R.id.dungeon_picker_grid)?.findViewWithTag<View>("party_row")
        else -> null
    }

    companion object {
        private const val PICKER_FIRST_ROWS = 12
        private const val PICKER_BATCH_ROWS = 4
        private const val PICKER_AHEAD_SCREENS = 1.5f
        /** SOLO PRUEBAS: clave del consejo que se enseña a la fuerza (TutorialTips.Tip.key) y paso que se vuelca. */
        const val EXTRA_FORCE_TIP = "force_tip"
        const val EXTRA_TIP_STEP = "force_tip_step"
    }

    override fun onResume() {
        super.onResume()
        DungeonService.pauseForLiveView() // mientras esta pantalla este abierta, solo avanza la vista en vivo
        showPickerOrLive()
        // Consejos de primera vez: la mazmorra y, si ya se desbloquearon, los compañeros (uno tras otro).
        window.decorView.post {
            val forced = intent?.getStringExtra(EXTRA_FORCE_TIP)
            if (forced != null) {
                // SOLO PRUEBAS (desde el menu Debug o MainActivity --es debug_tip): se enseña a la fuerza y se vuelca esta pantalla.
                val step = intent.getIntExtra(EXTRA_TIP_STEP, 0)
                intent.removeExtra(EXTRA_FORCE_TIP)
                TutorialTips.Tip.values().firstOrNull { it.key == forced }?.let { tip ->
                    TutorialTips.show(this, tip, force = true, onScreen = true, resolve = ::tipTarget)
                    window.decorView.postDelayed({
                        TutorialTips.lastOverlay?.takeIf { it.isAttachedToWindow }?.debugGoTo(step)
                        window.decorView.postDelayed({
                            try {
                                // La pista vive en su propia ventana a pantalla completa: se pinta la pantalla entera (esta ventana + la pista).
                                val ov = TutorialTips.lastOverlay?.takeIf { it.isAttachedToWindow }
                                val b = TutorialTips.debugComposite(this, window.decorView, emptyList(), ov?.rootView)
                                java.io.File(filesDir, "debug_tip_${forced}_$step.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                b.recycle()
                                DebugLog.log(this, "mazmorra: consejo '$forced' paso $step volcado")
                            } catch (e: Exception) { DebugLog.log(this, "mazmorra: volcado del consejo fallo: $e") }
                        }, 700L)
                    }, 2000L)
                }
            } else TutorialTips.showPending(this, TutorialTips.Tip.DUNGEON, TutorialTips.Tip.COMPANIONS, chain = true, onScreen = true, resolve = ::tipTarget)
        }
        // SOLO PARA PRUEBAS: vuelca la propia ventana a filesDir/debug_dungeon_screen.png (sin capturar la
        // pantalla del movil) y se cierra. Extra opcional "companion_state" ("pick"/"filled") muestra la
        // rejilla eligiendo compañero o con uno ya elegido. Se lanza desde MainActivity (esta Activity no
        // esta exportada): adb shell am start -n com.example.pokegotchi/.MainActivity --ez debug_dungeon_dump true --es companion_state pick
        if (intent?.getBooleanExtra("debug_dump", false) == true) {
            intent.removeExtra("debug_dump")
            // "companion_state": "pick" = rejilla eligiendo compañero; "filled" = un compañero ya elegido (de
            // muestra, solo en memoria). Durante un cierre se revela la rejilla como "Cambiar de Pokémon".
            val state = intent.getStringExtra("companion_state")
            if (state != null && DungeonState.maxCompanions(this) > 0 && !DungeonState.isExploring(this)) {
                if (DungeonState.isInCooldown(this)) switchPokemonButton.performClick()
                if (state == "filled" && chosenCompanions.isEmpty()) {
                    PetState.ownedIndividuals(this).firstOrNull { (n, s) -> DungeonState.companionBlockReason(this, n, s, null) == null }
                        ?.let { chosenCompanions.add(it) }
                }
                if (state == "pick") {
                    pickingCompanion = true
                    pickerTitle.text = "Elige un compañero (comparte el XP)"
                }
                // "debug_query": escribe esa busqueda en el buscador antes de volcar.
                buildPickerGrid()
                intent.getStringExtra("debug_query")?.let { q -> ((pickerControls.getChildAt(0) as ViewGroup).getChildAt(0) as android.widget.EditText).setText(q) }
                // "ask": abre la pregunta de como mandar al primer Pokemon libre (la que ahora ofrece añadir un compañero) y la vuelca aparte.
                if (state == "ask" || state == "ask0") {
                    if (state == "ask0") chosenCompanions.clear()   // sin compañero elegido: sale la opcion de añadirlo
                    val activeName = PetState.currentPokemon(this); val activeShiny = PetState.isActiveShiny(this)
                    PetState.ownedIndividuals(this).firstOrNull { (n, sh) ->
                        !(n == activeName && sh == activeShiny) && !PetState.isInNursery(this, n, sh) && (n to sh) !in chosenCompanions
                    }?.let { showSendModeDialog(it.first, it.second) }
                }
            }
            window.decorView.postDelayed({
                // Con la pantalla del movil apagada la Activity se pausa y la vista suelta la carrera:
                // se recarga para que el volcado tenga algo que pintar.
                if (DungeonState.isExploring(this) && !dungeonView.debugHasRun()) {
                    DungeonState.currentSpecies(this)?.let { dungeonView.start(it, DungeonState.currentShiny(this)) }
                }
            }, 2000)
            window.decorView.postDelayed({
                lastSendDialog?.takeIf { it.isShowing }?.window?.decorView?.let { dv ->
                    try {
                        val b = android.graphics.Bitmap.createBitmap(dv.width.coerceAtLeast(1), dv.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(b).apply { drawColor(Color.parseColor("#303030")); dv.draw(this) }
                        java.io.File(filesDir, "debug_dungeon_ask.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        b.recycle()
                        DebugLog.log(this, "mazmorra: pregunta de como mandarlo volcada a debug_dungeon_ask.png")
                    } catch (e: Exception) { DebugLog.log(this, "mazmorra: volcado de la pregunta fallo: $e") }
                }
            }, 3000)
            window.decorView.postDelayed({
                try {
                    val decor = window.decorView
                    val bmp = android.graphics.Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                    decor.draw(android.graphics.Canvas(bmp))
                    java.io.File(filesDir, "debug_dungeon_screen.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    DebugLog.log(this, "mazmorra: vista previa volcada a debug_dungeon_screen.png")
                } catch (e: Exception) { DebugLog.log(this, "mazmorra: volcado de la vista previa fallo: $e") }
                finish()
            }, 3500)
        }
    }

    override fun onPause() {
        super.onPause()
        dungeonView.stop()
        cooldownHandler.removeCallbacks(cooldownTicker)
        DungeonService.resumeBackgroundTicks()
        // Notificacion permanente y silenciosa con el estado actual (pedido explicito del
        // usuario: "que cuando tu vas a ver la notificacion puedas ver el estado en el que esta
        // el Pokemon") - SOLO si sigue explorando o en cooldown (refleja el piso/HP actuales, o
        // cuanto falta para la siguiente carrera, al salir de esta pantalla). Bug real reportado
        // por el usuario: "cuando sales del menu de mazmorras te envia siempre la notificacion de
        // que acabo" - antes se refrescaba aqui SIEMPRE, aunque la carrera ya hubiera terminado
        // hace rato, asi que el aviso de resumen (derrota/victoria/abandono) volvia a aparecer
        // cada vez que entrabas y salias del menu, no solo la vez que de verdad terminaba. El
        // aviso de fin de carrera ya se dispara una unica vez, justo cuando ocurre (onRunEnded/
        // boton Abandonar) - aqui no hace falta tocarlo si ya no hay nada explorando ni en espera.
        if (DungeonState.isExploring(this) || DungeonState.isInCooldown(this)) {
            DungeonService.start(this) // retoma el avance de fondo (o la espera del cooldown) al salir
            NotificationHelper.refreshDungeonNotification(this)
        }
    }

    private fun showPickerOrLive() {
        when {
            DungeonState.isExploring(this) -> {
                cooldownHandler.removeCallbacks(cooldownTicker)
                pickerRoot.visibility = View.GONE
                liveRoot.visibility = View.VISIBLE
                abandonButton.visibility = View.VISIBLE
                val species = DungeonState.currentSpecies(this) ?: return
                dungeonView.start(species, DungeonState.currentShiny(this))
                refreshRunSummaryText()
            }
            DungeonState.isInCooldown(this) -> {
                liveRoot.visibility = View.GONE
                abandonButton.visibility = View.GONE // el de aqui es el de EXPLORING (ver dungeon_live_root) - en cooldown se usa cooldownAbandonButton
                pickerRoot.visibility = View.VISIBLE
                val summary = DungeonState.lastRunSummary(this)
                lastSummaryText.visibility = if (summary != null) View.VISIBLE else View.GONE
                lastSummaryText.text = summary
                showCountdownOnly()
                cooldownHandler.removeCallbacks(cooldownTicker)
                cooldownHandler.postDelayed(cooldownTicker, 1000)
            }
            else -> {
                cooldownHandler.removeCallbacks(cooldownTicker)
                liveRoot.visibility = View.GONE
                abandonButton.visibility = View.GONE
                pickerRoot.visibility = View.VISIBLE
                val summary = DungeonState.lastRunSummary(this)
                lastSummaryText.visibility = if (summary != null) View.VISIBLE else View.GONE
                lastSummaryText.text = summary
                cooldownMessageText.visibility = View.GONE
                cooldownActionsRow.visibility = View.GONE
                pickerTitle.visibility = View.VISIBLE
                pickerTitle.text = "Elige quién explora la mazmorra"
                pickingForCooldownQueue = false
                pickingCompanion = false
                pendingExplorer = null
                buildPickerGrid()
            }
        }
    }

    /** Pantalla de CIERRE por defecto: mensaje de "mazmorra cerrada" + cuenta atras, y los dos
     *  botones ("Cambiar de Pokémon" / "Abandonar") - SIN la rejilla (pedido explicito del
     *  usuario, ver comentario de los campos arriba: "si no tocas nada el bucle sigue [solo], el
     *  boton es para sustituir..."). La rejilla solo aparece si se toca "Cambiar de Pokémon" (ver
     *  su listener en onCreate). */
    private fun showCountdownOnly() {
        pickerGrid.removeAllViews()
        pickerControls.visibility = View.GONE
        pickerTitle.visibility = View.GONE
        pickingForCooldownQueue = false
        pickingCompanion = false
        pendingExplorer = null
        cooldownMessageText.visibility = View.VISIBLE
        cooldownActionsRow.visibility = View.VISIBLE
        refreshCooldownCountdown()
    }

    /** Solo el texto de la cuenta atras (ver [showCountdownOnly]) - se llama cada segundo desde
     *  [cooldownTicker] mientras esa pantalla siga en pie (deja de llamarse en cuanto se toca
     *  "Cambiar de Pokémon", que para el ticker). Redactado como CIERRE de la mazmorra entera, no
     *  como que el Pokémon en concreto tenga que descansar - pedido explicito del usuario (ver
     *  comentario de los campos arriba). */
    private fun refreshCooldownCountdown() {
        val species = DungeonState.currentSpecies(this)
        val companions = DungeonState.companions(this)
        val label = species?.let {
            PetState.displayLabel(it) + if (companions.isEmpty()) "" else " + " + companions.joinToString(" + ") { c -> PetState.displayLabel(c.first) }
        } ?: "nadie"
        val remainingMs = (DungeonState.cooldownUntil(this) - System.currentTimeMillis()).coerceAtLeast(0)
        val mins = remainingMs / 60000
        val secs = (remainingMs / 1000) % 60
        cooldownMessageText.text = "🔒 La mazmorra está cerrada (la última carrera llegó al piso ${DungeonState.cooldownFloor(this)})\n" +
            "Reabre en ${mins}:${secs.toString().padStart(2, '0')} · entrará: $label"
    }

    /** Buscador, orden y filtros (los mismos que el selector de la guarderia: buscar por nombre, orden Nivel ▼ / Nivel ▲ / Nombre y los
     *  filtros "Disponibles" y "Shiny"). Vive FUERA de la rejilla (que se reconstruye con cada cambio) para que el teclado y el foco no se pierdan. */
    private fun buildPickerControls(): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sortLabels = listOf("Nivel ▼", "Nivel ▲", "Nombre A-Z")
        fun chip(label: String, selected: Boolean, onClick: (TextView) -> Unit) = TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(Color.parseColor(if (selected) "#EE1515" else "#332F3B"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            setOnClickListener { onClick(this) }
        }
        val search = android.widget.EditText(this).apply {
            hint = "🔍 Buscar por nombre"
            setSingleLine()
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8C849C"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { pickerQuery = s?.toString().orEmpty(); onPickerFilterChanged() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        val sortChip = chip(sortLabels[pickerSortMode], selected = false) { v ->
            pickerSortMode = (pickerSortMode + 1) % sortLabels.size
            (v as TextView).text = sortLabels[pickerSortMode]
            UiPrefs.putInt(this, UiPrefs.DUNGEON_SORT_MODE, pickerSortMode)
            onPickerFilterChanged()
        }
        pickerCountText = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#B8B0C8")); setPadding(dp(2), dp(6), 0, dp(8)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(LinearLayout(this@DungeonActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(search)
                addView(sortChip.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
            })
            addView(android.widget.HorizontalScrollView(this@DungeonActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(8), 0, 0)
                addView(LinearLayout(this@DungeonActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(chip("🗺️ Disponibles", pickerOnlyFree) { v -> pickerOnlyFree = !pickerOnlyFree; UiPrefs.putBool(this@DungeonActivity, UiPrefs.DUNGEON_ONLY_FREE, pickerOnlyFree); v.setBackgroundColor(Color.parseColor(if (pickerOnlyFree) "#EE1515" else "#332F3B")); onPickerFilterChanged() })
                    addView(chip("✨ Shiny", pickerOnlyShiny) { v -> pickerOnlyShiny = !pickerOnlyShiny; UiPrefs.putBool(this@DungeonActivity, UiPrefs.DUNGEON_ONLY_SHINY, pickerOnlyShiny); v.setBackgroundColor(Color.parseColor(if (pickerOnlyShiny) "#EE1515" else "#332F3B")); onPickerFilterChanged() })
                })
            })
            addView(pickerCountText)
        }
    }

    /** Cambio de busqueda, orden o filtro: se rehace la rejilla y, si se habia desplazado mas abajo de los controles, se vuelve a ellos
     *  (la lista nueva es otra y si no se quedaria al final de ella). */
    private fun onPickerFilterChanged() {
        buildPickerGrid()
        pickerGrid.post {
            var y = 0; var v: View = pickerControls
            val content = pickerScroll.getChildAt(0)
            while (v !== content && v.parent is View) { y += v.top; v = v.parent as View }
            if (pickerScroll.scrollY > y) pickerScroll.scrollTo(0, y)
        }
    }

    /** Selector "que Pokemon mandar a la mazmorra" - CUALQUIER individuo ya poseido
     *  (PetState.ownedIndividuals, sin el filtro de favorito), no tiene por que ser el compañero
     *  activo del widget principal - pedido explicito del usuario ("de hecho la gracia es que
     *  pongas a otro Pokemon"). Sprites LOCALES (SpriteRepository, mismo patron que
     *  TrainerSetupActivity) en vez del helper de red de MainActivity - esta Activity no tiene
     *  esos helpers privados a mano. */
    private fun buildPickerGrid() {
        pickerGrid.removeAllViews()
        appendPickerGridRows()
    }

    /** Cuerpo de la rejilla (compañeros + celdas), SIN el removeAllViews inicial - solo lo
     *  usa [buildPickerGrid] (IDLE, o COOLDOWN tras tocar "Cambiar de Pokémon"). */
    private fun appendPickerGridRows() {
        val tStart = System.nanoTime()
        val candidates = PetState.ownedIndividuals(this)
        if (candidates.isEmpty()) {
            pickerControls.visibility = View.GONE
            pickerGrid.addView(TextView(this).apply {
                text = "Todavía no tienes ningún Pokémon para mandar a la mazmorra"
                setTextColor(Color.WHITE)
            })
            return
        }
        pickerControls.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // El compañero activo del widget principal NO puede explorar la mazmorra (pedido
        // explicito del usuario, "por obvias razones": ahora que la mazmorra aplica XP real
        // (DungeonSimulator.APPLY_REAL_CANDY_XP), el mismo individuo siendo a la vez el que se
        // cuida a mano - comer/acariciar/lavar, ver PetState.tryApplyAction - y el que explora solo
        // en segundo plano no tiene sentido: dos fuentes de XP real sobre el mismo Pokemon, una
        // pensada para estar delante de la pantalla y otra para no estarlo. Se compara especie+
        // shiny (el mismo criterio que identifica a un individuo en todo el proyecto).
        val activeCompanion = PetState.currentPokemon(this) to PetState.isActiveShiny(this)

        val nurserySet = PetState.nurseryMembers(this).toSet()
        // No disponible: el activo, uno de la guarderia, ya elegido de compañero o el explorador que espera compañero.
        fun isBlocked(name: String, shiny: Boolean) = (name == activeCompanion.first && shiny == activeCompanion.second) || (name to shiny) in nurserySet ||
            (name to shiny) in chosenCompanions || (pickingCompanion && (name to shiny) == pendingExplorer)
        // Nivel real de cada candidato (pedido explicito del usuario: "pon los niveles de los pokemon y filtralo por nivel").
        val q = pickerQuery.trim().lowercase()
        val withLevel = candidates.filter { (name, shiny) ->
            (q.isEmpty() || name.contains(q) || PetState.displayLabel(name).lowercase().contains(q)) &&
                (!pickerOnlyShiny || shiny) && (!pickerOnlyFree || !isBlocked(name, shiny))
        }.map { (name, shiny) ->
            val xp = PetState.rawStats(this, name, shiny)?.xp ?: 0f
            Triple(name, shiny, PetState.levelOf(this, xp, name))
        }
        val sorted = when (pickerSortMode) {
            1 -> withLevel.sortedWith(compareBy({ it.third }, { it.first }))
            2 -> withLevel.sortedBy { PetState.displayLabel(it.first) }
            else -> withLevel.sortedWith(compareBy({ -it.third }, { it.first }))
        }
        pickerCountText.text = if (sorted.isEmpty()) "Ningún Pokémon coincide · prueba con otro filtro" else "${sorted.size} Pokémon"
        // Compañeros (solo si el nivel de entrenador ya permite alguno).
        if (DungeonState.maxCompanions(this) > 0) pickerGrid.addView(buildPartyRow())
        fun buildRow(row: List<Triple<String, Boolean, Int>>): LinearLayout {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for ((name, shiny, level) in row) {
                val isActiveCompanion = name == activeCompanion.first && shiny == activeCompanion.second
                // Un Pokemon de la guarderia (PetState.nurseryBlockReason) tampoco explora: ya gana XP alli.
                val inNursery = (name to shiny) in nurserySet
                // Ya elegido como compañero de la proxima carrera: no puede ser tambien el explorador.
                val isChosenCompanion = (name to shiny) in chosenCompanions
                // El explorador que espera compañero tampoco puede serlo de si mismo.
                val isPendingExplorer = pickingCompanion && (name to shiny) == pendingExplorer
                val blocked = isActiveCompanion || inNursery || isChosenCompanion || isPendingExplorer
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    setBackgroundColor(Color.parseColor("#332F3B"))
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    alpha = if (blocked) 0.5f else 1f
                }
                val img = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                cell.addView(img)
                val spriteKey = "$name|$shiny"
                img.tag = spriteKey
                val cached = spriteCache.get(spriteKey)
                if (cached != null) img.setImageBitmap(cached)
                else spriteLoader.execute {
                    val bmp = SpriteRepository.localFirstFrameScaled(this@DungeonActivity, name, shiny, PetState.spriteScaleMode(this@DungeonActivity))
                    if (bmp != null) {
                        spriteCache.put(spriteKey, bmp)
                        runOnUiThread { if (img.tag == spriteKey) img.setImageBitmap(bmp) }
                    }
                }
                cell.addView(TextView(this).apply {
                    text = PetState.displayLabel(name) + if (shiny) " ✨" else ""
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })
                cell.addView(TextView(this).apply {
                    text = if (isActiveCompanion) "🏠 activo" else if (inNursery) "🌱 guardería"
                        else if (isPendingExplorer) "🧭 explorador" else if (isChosenCompanion) "🤝 compañero" else "Nv. $level"
                    textSize = 10f
                    setTextColor(if (blocked) Color.parseColor("#E0A521") else Color.parseColor("#B8B0C8"))
                    gravity = Gravity.CENTER
                })
                cell.setOnClickListener {
                    if (isActiveCompanion) {
                        Toast.makeText(this, "${PetState.displayLabel(name)} es tu compañero activo - no puede explorar la mazmorra mientras lo cuidas", Toast.LENGTH_LONG).show()
                    } else if (inNursery) {
                        Toast.makeText(this, "${PetState.displayLabel(name)} está en la guardería - sácalo de allí para mandarlo a la mazmorra", Toast.LENGTH_LONG).show()
                    } else if (isChosenCompanion) {
                        Toast.makeText(this, "${PetState.displayLabel(name)} ya va de compañero - quítalo arriba para mandarlo como explorador", Toast.LENGTH_LONG).show()
                    } else if (isPendingExplorer) {
                        Toast.makeText(this, "${PetState.displayLabel(name)} es el explorador - elige a otro como compañero", Toast.LENGTH_SHORT).show()
                    } else if (pickingCompanion) {
                        // Esta pulsacion elige un COMPAÑERO (no al explorador): se queda en el grupo y se
                        // vuelve a la rejilla normal.
                        val leader = pendingExplorer
                        val reason = leader?.let { DungeonState.companionBlockReason(this, name, shiny, it) }
                        if (reason != null) {
                            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        chosenCompanions.add(name to shiny)
                        pickingCompanion = false
                        pickerTitle.text = if (pickingForCooldownQueue) "Elige quién entra cuando la mazmorra reabra" else "Elige quién explora la mazmorra"
                        buildPickerGrid()
                        // Si se venia de elegir al explorador, se vuelve a SU pregunta (con el compañero ya puesto): mandarlo
                        // ya o añadir otro compañero.
                        pendingExplorer = null
                        leader?.let { showSendModeDialog(it.first, it.second) }
                    } else {
                        showSendModeDialog(name, shiny)
                    }
                }
                rowLayout.addView(cell)
            }
            return rowLayout
        }
        // Las filas se crean SOBRE LA MARCHA al desplazarse (con ~1100 Pokemon, crear las ~370 filas - aunque fuera en tandas - dejaba la
        // pantalla a trompicones un buen rato y no solo al abrirla, mientras la selector de la guarderia, que reutiliza celdas, iba fina):
        // solo se construyen las de la pantalla y un par de pantallas por delante.
        pickerRows = sorted.chunked(3)
        pickerNext = 0
        pickerRowBuilder = { row -> buildRow(row) }
        addPickerRows(PICKER_FIRST_ROWS)
        if (sorted.size > 180) DebugLog.log(this, "mazmorra: selector de ${sorted.size} individuos - ${pickerNext} filas al abrir en ${(System.nanoTime() - tStart) / 1_000_000} ms (el resto, al desplazarse)")
        pickerGrid.post { addPickerRowsIfNeeded() }
    }

    private var pickerRows: List<List<Triple<String, Boolean, Int>>> = emptyList()
    private var pickerNext = 0
    private var pickerRowBuilder: ((List<Triple<String, Boolean, Int>>) -> LinearLayout)? = null

    private fun addPickerRows(count: Int) {
        val build = pickerRowBuilder ?: return
        val end = minOf(pickerNext + count, pickerRows.size)
        while (pickerNext < end) pickerGrid.addView(build(pickerRows[pickerNext++]))
    }

    /** Si quedan filas por crear y lo que falta por desplazar hasta el final es menos de ~1,5 pantallas, crea otra tanda (y vuelve a mirar cuando
     *  ya se haya medido, hasta tener pantalla y media por delante). */
    private fun addPickerRowsIfNeeded() {
        if (pickerNext >= pickerRows.size || isFinishing) return
        val child = pickerScroll.getChildAt(0) ?: return
        val remaining = child.bottom - (pickerScroll.scrollY + pickerScroll.height)
        if (remaining < pickerScroll.height * PICKER_AHEAD_SCREENS) {
            addPickerRows(PICKER_BATCH_ROWS)
            pickerGrid.post { addPickerRowsIfNeeded() }
        }
    }

    // Sprites del selector: una sola cola de carga y una cache pequeña (como el selector de la guarderia), no un hilo por celda.
    private val spriteLoader: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
    }
    private val spriteCache = android.util.LruCache<String, android.graphics.Bitmap>(160)

    /** Caja "Compañeros" del selector: los ya elegidos (con ✕ para quitarlos) y, mientras queden
     *  huecos, un boton para añadir otro - al tocarlo la rejilla de abajo sirve para elegirlo. Los
     *  compañeros van en la MISMA carrera que el explorador y se reparten el XP a partes iguales. */
    private fun buildPartyRow(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val max = DungeonState.maxCompanions(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#26222E"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            tag = "party_row"   // para que la pista de los compañeros pueda resaltarla
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        box.addView(TextView(this).apply {
            text = "🤝 Compañeros (${chosenCompanions.size}/$max) · van contigo y se reparten el XP"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        // Con equipo, mas enemigos por piso (ExplorerPartyRules.enemyMultiplier) y solo pega quien llega al enemigo.
        if (chosenCompanions.isNotEmpty()) {
            val n = 1 + chosenCompanions.size
            val mult = ExplorerPartyRules.formatMultiplier(ExplorerPartyRules.enemyMultiplier(n))
            box.addView(TextView(this).apply {
                text = "⚔️ Con $n Pokémon, cada piso trae ×$mult de enemigos · solo pega quien llega al enemigo"
                textSize = 11.5f
                setTextColor(Color.parseColor("#C9C2D6"))
                setPadding(0, dp(3), 0, 0)
            })
        }
        for (member in chosenCompanions.toList()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(img)
            Thread {
                val bmp = SpriteRepository.localFirstFrameScaled(this, member.first, member.second, PetState.spriteScaleMode(this))
                runOnUiThread { if (bmp != null) img.setImageBitmap(bmp) }
            }.start()
            row.addView(TextView(this).apply {
                val xp = PetState.rawStats(this@DungeonActivity, member.first, member.second)?.xp ?: 0f
                text = PetState.displayLabel(member.first) + (if (member.second) " ✨" else "") + " · Nv. ${PetState.levelOf(this@DungeonActivity, xp, member.first)}"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "✕ Quitar"
                textSize = 12f
                setTextColor(Color.parseColor("#E0A521"))
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    chosenCompanions.remove(member)
                    buildPickerGrid()
                }
            })
            box.addView(row)
        }
        if (chosenCompanions.size < max) {
            box.addView(TextView(this).apply {
                text = if (pickingCompanion) "Toca abajo a quien quieras como compañero · cancelar" else "＋ Añadir un compañero"
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(if (pickingCompanion) "#992B2B2B" else "#992B6B2B"))
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                setOnClickListener {
                    pickingCompanion = !pickingCompanion
                    if (!pickingCompanion) pendingExplorer = null
                    pickerTitle.text = when {
                        pickingCompanion -> "Elige un compañero (comparte el XP)"
                        pickingForCooldownQueue -> "Elige quién entra cuando la mazmorra reabra"
                        else -> "Elige quién explora la mazmorra"
                    }
                    buildPickerGrid()
                }
            })
        }
        return box
    }

    /** Pedido explicito del usuario: "puedas elegir un pokemon y te de a elegir si lo mandas una
     *  vez o quieres que lo haga continuamente. cuando acabe una empieza otra" - se pregunta ANTES
     *  de asignar, no despues; "Una vez" es el comportamiento de siempre (slot inactivo tras
     *  derrota/victoria hasta reasignar a mano).
     *
     *  [pickingForCooldownQueue] decide que hace cada boton al confirmar: en IDLE asigna de
     *  inmediato (assignToDungeon); durante el cierre solo PRE-ELIGE quien entrara cuando reabra
     *  (queueSelected), sin abrir la mazmorra antes de tiempo. */
    private fun showSendModeDialog(name: String, shiny: Boolean) {
        // Defensa por si la rejilla estuviera desfasada (ej. el jugador cambia de compañero activo
        // en MainActivity mientras esta pantalla ya estaba abierta) - el guardian real ya esta en
        // el listener de la celda (ver appendPickerGridRows), esto es solo un cinturon extra.
        if (name == PetState.currentPokemon(this) && shiny == PetState.isActiveShiny(this)) {
            Toast.makeText(this, "${PetState.displayLabel(name)} es tu compañero activo - no puede explorar la mazmorra mientras lo cuidas", Toast.LENGTH_LONG).show()
            return
        }
        val confirm: (Boolean) -> Unit = if (pickingForCooldownQueue) {
            { autoRepeat -> queueSelected(name, shiny, autoRepeat) }
        } else {
            { autoRepeat -> assignToDungeon(name, shiny, autoRepeat) }
        }
        val party = DungeonState.usableCompanions(this, name to shiny, chosenCompanions)
        val maxCompanions = DungeonState.maxCompanions(this)
        val canAdd = party.size < maxCompanions
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        content.addView(TextView(this).apply {
            text = "¿Cómo quieres mandarlo a la mazmorra?" +
                (if (party.isEmpty()) "" else "\n\nVa con ${party.joinToString(" y ") { PetState.displayLabel(it.first) }}: se reparten el XP a partes iguales.") +
                (if (canAdd) "\n\n¿Quieres que lo acompañe alguien? (compañeros: ${party.size}/$maxCompanions)" else "")
            textSize = 14f
        })
        lateinit var dlg: androidx.appcompat.app.AlertDialog
        fun addButton(label: String, onClick: () -> Unit) {
            content.addView(Button(this).apply {
                text = label
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                setOnClickListener { dlg.dismiss(); onClick() }
            })
        }
        // Con huecos de compañero libres, primero la opcion de añadirlo (se elige en la rejilla y se vuelve aqui).
        if (canAdd) addButton("🤝 Añadir un compañero") {
            pendingExplorer = name to shiny
            pickingCompanion = true
            pickerTitle.text = "Elige un compañero para ${PetState.displayLabel(name)} (comparte el XP)"
            buildPickerGrid()
        }
        addButton("▶ Una vez") { pendingExplorer = null; confirm(false) }
        addButton("🔁 Continuo") { pendingExplorer = null; confirm(true) }
        addButton("Cancelar") { pendingExplorer = null }
        dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(PetState.displayLabel(name) + if (shiny) " ✨" else "")
            .setView(content)
            .create()
        dlg.setOnCancelListener { pendingExplorer = null }
        lastSendDialog = dlg
        dlg.show()
    }

    private fun assignToDungeon(name: String, shiny: Boolean, autoRepeat: Boolean) {
        val hp = DungeonSimulator.maxHp(this, name, shiny)
        val startXp = PetState.rawStats(this, name, shiny)?.xp ?: 0f
        val party = DungeonState.usableCompanions(this, name to shiny, chosenCompanions)
        DungeonState.assign(this, name, shiny, hp, startXp, autoRepeat, companions = party)
        val modeText = if (autoRepeat) " (modo continuo: al acabar, empieza otra sola)" else ""
        val partyText = if (party.isEmpty()) "" else " con ${party.joinToString(" y ") { PetState.displayLabel(it.first) }}"
        Toast.makeText(this, "${PetState.displayLabel(name)}$partyText entra en la mazmorra desde el piso 1$modeText", Toast.LENGTH_LONG).show()
        NotificationHelper.refreshDungeonNotification(this)
        showPickerOrLive()
    }

    /** Pre-elige quien entrara cuando la mazmorra reabra - NO cambia status/floor/hp, el cierre
     *  sigue su curso igual (pedido explicito del usuario, ver comentario de los campos arriba).
     *  Vuelve a la pantalla de cuenta atras (ya no a la rejilla) para que se note que sigue
     *  cerrada, ahora con $name como quien entrara. */
    private fun queueSelected(name: String, shiny: Boolean, autoRepeat: Boolean) {
        val party = DungeonState.usableCompanions(this, name to shiny, chosenCompanions)
        DungeonState.queueNextExplorer(this, name, shiny, autoRepeat, party)
        val modeText = if (autoRepeat) " en modo continuo" else " una vez"
        val partyText = if (party.isEmpty()) "" else " con ${party.joinToString(" y ") { PetState.displayLabel(it.first) }}"
        Toast.makeText(this, "${PetState.displayLabel(name)}$partyText entrará$modeText en cuanto la mazmorra reabra", Toast.LENGTH_LONG).show()
        showPickerOrLive()
    }
}
