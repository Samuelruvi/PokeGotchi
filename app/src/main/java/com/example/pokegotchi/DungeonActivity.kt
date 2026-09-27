package com.example.pokegotchi

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
    private var pickerSortDescending = true
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
        pickerTitle = findViewById(R.id.dungeon_picker_title)
        lastSummaryText = findViewById(R.id.dungeon_last_summary)
        runSummaryText = findViewById(R.id.dungeon_run_summary)
        abandonButton = findViewById(R.id.dungeon_abandon)
        cooldownMessageText = findViewById(R.id.dungeon_cooldown_message)
        cooldownActionsRow = findViewById(R.id.dungeon_cooldown_actions)
        switchPokemonButton = findViewById(R.id.dungeon_switch_pokemon)
        cooldownAbandonButton = findViewById(R.id.dungeon_cooldown_abandon)
        findViewById<View>(R.id.dungeon_back).setOnClickListener { finish() }
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
        runSummaryText.text = "$repeatPrefix${PetState.displayLabel(species)} · +${DungeonState.runXpGained(this).toInt()} XP · " +
            "$levelsGained ${if (levelsGained == 1) "nivel" else "niveles"} · " +
            "${DungeonState.runItemsCollected(this)} objetos recogidos"
    }

    override fun onResume() {
        super.onResume()
        DungeonService.pauseForLiveView() // mientras esta pantalla este abierta, solo avanza la vista en vivo
        showPickerOrLive()
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
        pickerTitle.visibility = View.GONE
        pickingForCooldownQueue = false
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
        val label = species?.let { PetState.displayLabel(it) } ?: "nadie"
        val remainingMs = (DungeonState.cooldownUntil(this) - System.currentTimeMillis()).coerceAtLeast(0)
        val mins = remainingMs / 60000
        val secs = (remainingMs / 1000) % 60
        cooldownMessageText.text = "🔒 La mazmorra está cerrada (la última carrera llegó al piso ${DungeonState.cooldownFloor(this)})\n" +
            "Reabre en ${mins}:${secs.toString().padStart(2, '0')} · entrará: $label"
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

    /** Cuerpo de la rejilla (cabecera de orden + celdas), SIN el removeAllViews inicial - solo lo
     *  usa [buildPickerGrid] (IDLE, o COOLDOWN tras tocar "Cambiar de Pokémon"). */
    private fun appendPickerGridRows() {
        val candidates = PetState.ownedIndividuals(this)
        if (candidates.isEmpty()) {
            pickerGrid.addView(TextView(this).apply {
                text = "Todavía no tienes ningún Pokémon para mandar a la mazmorra"
                setTextColor(Color.WHITE)
            })
            return
        }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Nivel real de cada candidato (pedido explicito del usuario: "pon los niveles de los
        // pokemon y filtralo por nivel") - ordenado de mayor a menor por defecto, invertible
        // tocando la cabecera.
        val withLevel = candidates.map { (name, shiny) ->
            val xp = PetState.rawStats(this, name, shiny)?.xp ?: 0f
            Triple(name, shiny, PetState.levelOf(this, xp, name))
        }
        val sorted = if (pickerSortDescending) withLevel.sortedByDescending { it.third } else withLevel.sortedBy { it.third }

        pickerGrid.addView(TextView(this).apply {
            text = "Ordenar por nivel " + (if (pickerSortDescending) "▼" else "▲")
            setTextColor(Color.parseColor("#C9A227"))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(8))
            setOnClickListener {
                pickerSortDescending = !pickerSortDescending
                buildPickerGrid() // en cooldown esto solo se ve tras tocar "Cambiar de Pokémon" (ver su listener) - misma rejilla que IDLE en ese punto
            }
        })

        // El compañero activo del widget principal NO puede explorar la mazmorra (pedido
        // explicito del usuario, "por obvias razones": ahora que la mazmorra aplica XP real
        // (DungeonSimulator.APPLY_REAL_CANDY_XP), el mismo individuo siendo a la vez el que se
        // cuida a mano - comer/acariciar/lavar, ver PetState.tryApplyAction - y el que explora solo
        // en segundo plano no tiene sentido: dos fuentes de XP real sobre el mismo Pokemon, una
        // pensada para estar delante de la pantalla y otra para no estarlo. Se compara especie+
        // shiny (el mismo criterio que identifica a un individuo en todo el proyecto).
        val activeCompanion = PetState.currentPokemon(this) to PetState.isActiveShiny(this)

        for (row in sorted.chunked(3)) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for ((name, shiny, level) in row) {
                val isActiveCompanion = name == activeCompanion.first && shiny == activeCompanion.second
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    setBackgroundColor(Color.parseColor("#332F3B"))
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    alpha = if (isActiveCompanion) 0.5f else 1f
                }
                val img = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                cell.addView(img)
                Thread {
                    val bmp = SpriteRepository.localFirstFrameScaled(this, name, shiny, PetState.spriteScaleMode(this))
                    runOnUiThread { if (bmp != null) img.setImageBitmap(bmp) }
                }.start()
                cell.addView(TextView(this).apply {
                    text = PetState.displayLabel(name) + if (shiny) " ✨" else ""
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })
                cell.addView(TextView(this).apply {
                    text = if (isActiveCompanion) "🏠 activo" else "Nv. $level"
                    textSize = 10f
                    setTextColor(if (isActiveCompanion) Color.parseColor("#E0A521") else Color.parseColor("#B8B0C8"))
                    gravity = Gravity.CENTER
                })
                cell.setOnClickListener {
                    if (isActiveCompanion) {
                        Toast.makeText(this, "${PetState.displayLabel(name)} es tu compañero activo - no puede explorar la mazmorra mientras lo cuidas", Toast.LENGTH_LONG).show()
                    } else {
                        showSendModeDialog(name, shiny)
                    }
                }
                rowLayout.addView(cell)
            }
            pickerGrid.addView(rowLayout)
        }
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
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(PetState.displayLabel(name) + if (shiny) " ✨" else "")
            .setMessage("¿Cómo quieres mandarlo a la mazmorra?")
            .setPositiveButton("Una vez") { _, _ -> confirm(false) }
            .setNegativeButton("🔁 Continuo") { _, _ -> confirm(true) }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun assignToDungeon(name: String, shiny: Boolean, autoRepeat: Boolean) {
        val hp = DungeonSimulator.maxHp(this, name, shiny)
        val startXp = PetState.rawStats(this, name, shiny)?.xp ?: 0f
        DungeonState.assign(this, name, shiny, hp, startXp, autoRepeat)
        val modeText = if (autoRepeat) " (modo continuo: al acabar, empieza otra sola)" else ""
        Toast.makeText(this, "${PetState.displayLabel(name)} entra en la mazmorra desde el piso 1$modeText", Toast.LENGTH_LONG).show()
        NotificationHelper.refreshDungeonNotification(this)
        showPickerOrLive()
    }

    /** Pre-elige quien entrara cuando la mazmorra reabra - NO cambia status/floor/hp, el cierre
     *  sigue su curso igual (pedido explicito del usuario, ver comentario de los campos arriba).
     *  Vuelve a la pantalla de cuenta atras (ya no a la rejilla) para que se note que sigue
     *  cerrada, ahora con $name como quien entrara. */
    private fun queueSelected(name: String, shiny: Boolean, autoRepeat: Boolean) {
        DungeonState.queueNextExplorer(this, name, shiny, autoRepeat)
        val modeText = if (autoRepeat) " en modo continuo" else " una vez"
        Toast.makeText(this, "${PetState.displayLabel(name)} entrará$modeText en cuanto la mazmorra reabra", Toast.LENGTH_LONG).show()
        showPickerOrLive()
    }
}
