package com.example.pokegotchi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import org.json.JSONArray

/**
 * Pokedex: rejilla de Pokemon de UNA generacion a la vez (con selector para cambiar), con
 * miniatura. Al tocar uno ya desbloqueado, se descarga su sprite animado y se pone en el widget.
 */
class MainActivity : AppCompatActivity() {
    // Mismo tag/formato que PokeWidgetProvider.dbg() (misma sesion de logcat, un solo filtro) -
    // pedido explicito del usuario para poder verificar TODAS las mecanicas de la app en marcha,
    // no solo el huevo. Registra las acciones que el jugador dispara desde aqui (evolucionar,
    // Megaevolucionar, elegir/soltar huevo y regalo, cambiar de Pokemon activo o de forma) - lo
    // que pasa en segundo plano (huevo/oferta/evolucion-lista/atencion urgente) ya se registra en
    // PokeWidgetProvider.checkAlerts. Filtrar con: adb logcat -s PokeGotchiDbg
    private fun dbg(msg: String) = DebugLog.log(this, msg)

    private data class Mon(val id: Int, val name: String, val gen: Int)

    private var allMons = listOf<Mon>()
    private var rows = listOf<Mon>()   // segun el modo: solo currentGen, o solo los desbloqueados
    private var currentGen = 1
    private var maxGen = 9
    // Pedido por el usuario: buscar un Pokemon concreto entre los ~1025 de toda la Pokedex (sin
    // saber la generacion) es incomodo - pestaña alternativa que solo muestra los YA
    // desbloqueados, sin filtrar por generacion (normalmente son pocos, caben bien juntos).
    // Empieza en FILTER_MINE (abre en "Mis Pokemon" por defecto, a peticion del usuario).
    // FILTER_FAVORITES (pedido explicito del usuario: pestaña aparte entre "Mis Pokemon" y
    // "Pokedex completa" con solo los marcados como favoritos) se comporta igual que
    // FILTER_MINE en todo lo que no sea el propio filtro de [rows] (interactive=true en la
    // ficha, sin fila de generaciones, tinte de evolucionado activo) - la unica diferencia real
    // es QUE conjunto de especies entra en [rows].
    private val FILTER_MINE = "mine"
    private val FILTER_FAVORITES = "favorites"
    private val FILTER_ALL = "all"
    private var filterMode = FILTER_MINE
    private var sortBy = "id"   // "id" (num. Pokedex) | "level"
    private var searchQuery = ""   // filtro de texto libre, se combina con el resto (ver refreshRows)
    private lateinit var tvLevel: TextView
    private lateinit var tvTrainerLevel: TextView
    private lateinit var trainerLevelBar: ProgressBar
    private lateinit var adapter: DexAdapter
    private var selected = ""
    // Cerrojo real contra dialogos de eclosion duplicados: onResume() puede llamarse mas de una
    // vez seguida (ej. la pantalla se apaga/enciende otra vez mientras el dialogo sigue abierto,
    // antes de reclamar/soltar el huevo) sin que el huevo se haya consumido todavia - sin este
    // cerrojo se apilaba un SEGUNDO dialogo por debajo, que ya habia acabado su animacion de
    // eclosion mientras estaba tapado por el primero: al cerrar el de arriba aparecia el de abajo
    // ya revelado del todo, pareciendo "el mensaje de toda la vida sin animacion" de la nada -
    // bug real reportado por el usuario.
    private var eggHatchDialogShowing = false

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* si dice que no, simplemente no se notifica */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SOLO PRUEBAS (cuenta secundaria): lanza el arnes de QA en una pantalla en primer plano (ver QaActivity). Va ANTES de las
        // redirecciones de abajo porque el propio arnes borra el estado (sin perfil de entrenador esta pantalla redirige al tutorial).
        intent?.getStringExtra("debug_qa")?.let { suites ->
            startActivity(Intent(this, QaActivity::class.java).putExtra("suites", suites)
                .putExtra("n", intent.getIntExtra("n", 200)).putExtra("seed", intent.getLongExtra("seed", 1L)))
            finish()
            return
        }
        // Consejos de primera vez: en una partida que ya existia se dan todos por vistos (ver TutorialTips.initIfNeeded).
        TutorialTips.initIfNeeded(this)
        NotificationHelper.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Primer arranque de verdad: todavia no hay nickname/retrato de entrenador -> redirige a
        // TrainerSetupActivity ANTES que a StarterActivity (pedido explicito del usuario: elegir
        // nombre y avatar antes que el Pokemon inicial).
        if (!PetState.hasTrainerProfile(this)) {
            startActivity(Intent(this, TrainerSetupActivity::class.java))
            finish()
            return
        }
        // Primer arranque: aun no se ha elegido inicial -> redirige a StarterActivity y no
        // muestra la Pokedex normal (que empezaria "vacia", sin nada activo en el widget).
        if (!PetState.hasChosenStarter(this)) {
            startActivity(Intent(this, StarterActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        // A partir de Android 15 (targetSdk 35+) el contenido se dibuja de borde a borde por
        // defecto - sin esto, la cabecera roja queda debajo de la barra de estado y la lista de
        // abajo debajo de la barra de gestos, en vez de respetarlas. Se aplica como padding del
        // margen de sistema al layout raiz entero (arriba+abajo+lados), no solo a la cabecera.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        tvLevel = findViewById(R.id.tv_level)
        tvTrainerLevel = findViewById(R.id.tv_trainer_level)
        trainerLevelBar = findViewById(R.id.trainer_level_bar)
        selected = PetState.currentPokemon(this)

        loadAllMons()
        currentGen = allMons.find { it.name == selected }?.gen ?: 1
        // El orden elegido en "⇅ Ordenar" se recuerda entre arranques (la pestaña no: abre en "Mis Pokemon", pedido anterior).
        sortBy = UiPrefs.string(this, UiPrefs.MAIN_SORT, "id").takeIf { it in UiPrefs.MAIN_SORTS } ?: "id"
        refreshRows()
        buildGenRow()

        val rv = findViewById<RecyclerView>(R.id.rv)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(24)
        rv.itemAnimator = null   // sin animacion de refresco: evita cualquier mezcla visual
        adapter = DexAdapter()
        rv.adapter = adapter
        // Al abrir la app (modo "Mis Pokemon" por defecto), lleva la vista hasta el Pokemon
        // activo - sin reordenar la lista (pedido explicito: mismo orden de siempre, solo que la
        // pantalla no se quede en el principio si tu activo esta mas abajo). rv.post: el layout
        // aun no tiene medidas la primera vez que se llega aqui, sin esto scrollToPosition no
        // haria nada.
        rv.post { scrollToActivePokemon(smooth = false) }

        // Tocar el nivel de entrenador, cuando hay algun fondo por desbloquear (mismo aviso "⬆️"
        // que ya se enseña en showLevel()), abre directamente Ajustes en la rejilla de fondos -
        // pedido explicito del usuario, en vez de tener que entrar al menu ⋮ a mano para verlo.
        // Tocar el nivel de entrenador abre SIEMPRE la ficha de entrenador (pedido explicito del
        // usuario) - el atajo de antes al menu de fondos (solo si habia una ficha pendiente)
        // ahora vive DENTRO de la ficha, ver showTrainerCardDialog.
        tvTrainerLevel.setOnClickListener { showTrainerCardDialog() }
        findViewById<TextView>(R.id.tab_mine).setOnClickListener { setFilterMode(FILTER_MINE) }
        findViewById<TextView>(R.id.tab_favorites).setOnClickListener { setFilterMode(FILTER_FAVORITES) }
        findViewById<TextView>(R.id.tab_all).setOnClickListener { setFilterMode(FILTER_ALL) }
        updateFilterTabs()
        findViewById<EditText>(R.id.search_dex).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                // Al buscar algo, se salta a "Todas" las generaciones - si no, buscar solo
                // encontraria resultados dentro de la generacion que tocara tener puesta,
                // pedido explicito del usuario.
                if (searchQuery.isNotEmpty() && filterMode == FILTER_ALL && currentGen != 0) {
                    currentGen = 0
                    buildGenRow()
                }
                refreshRows()
                adapter.notifyDataSetChanged()
            }
        })
        findViewById<View>(R.id.btn_sort).setOnClickListener { showSortMenu(it) }

        findViewById<TextView>(R.id.btn_menu).setOnClickListener { showSettingsDialog() }
        findViewById<TextView>(R.id.btn_offer).setOnClickListener { showOfferDialog() }
        // Icono propio en la cabecera, junto al del regalo - pedido explicito del usuario tras
        // ver la Mazmorra enterrada en Ajustes: "no me gusta la ubicacion de la mazmorra. me
        // gustaria que puedas entrar al menu de una forma mas organica".
        findViewById<TextView>(R.id.btn_dungeon).setOnClickListener { startActivity(Intent(this, DungeonActivity::class.java)) }
        findViewById<TextView>(R.id.btn_bag).setOnClickListener { showBagDialog() }
        findViewById<TextView>(R.id.btn_nursery).setOnClickListener { showNurseryDialog() }
        showLevel()
        WidgetRefresh.updateWidgets(this)   // refresca el widget al abrir (regenera sprites de cache antigua)
        // El arranque del servicio de la mazmorra (retomar el avance de fondo o la espera del
        // cooldown si el proceso se reinicio a medio explorar) vive SOLO en onResume - onCreate
        // siempre va seguido de onResume, y hacerlo en los dos lo arrancaba dos veces seguidas.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)   // para que onResume vea los extras de depuracion (ver debugDemoOffer)
        openDungeonDumpIfRequested()   // con la app ya en primer plano onResume no se repite
        if (PetState.hasChosenStarter(this)) openNurseryIfRequested()   // idem: tocar el aviso con la app ya abierta no pasa por onResume
    }

    /** Al tocar el aviso de la guarderia ("puede evolucionar") se abre directamente la escena. Consume el extra (se llama
     *  desde onResume y desde onNewIntent: con la app ya en primer plano solo se ejecuta esta ultima). */
    private fun openNurseryIfRequested() {
        if (intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_NURSERY, false) != true) return
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_NURSERY)
        if (PetState.nurseryUnlocked(this)) {
            dbg("guarderia: se abre desde el aviso de la notificacion")
            window.decorView.post { showNurseryDialog() }
        }
    }

    /** SOLO PARA PRUEBAS: abre la mazmorra (que no esta exportada) y vuelca su ventana a PNG.
     *  adb shell am start -n com.example.pokegotchi/.MainActivity --ez debug_dungeon_dump true [--es companion_state pick|filled] */
    private fun openDungeonDumpIfRequested() {
        if (intent?.getBooleanExtra("debug_dungeon_dump", false) != true) return
        val state = intent.getStringExtra("companion_state")
        intent.removeExtra("debug_dungeon_dump")
        // Handler propio, no decorView.post: con la app ya abierta pero detras/pantalla apagada, la vista
        // esta "desacoplada" y su post no corre.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            startActivity(Intent(this, DungeonActivity::class.java).putExtra("debug_dump", true).putExtra("companion_state", state)
                .putExtra("debug_query", intent.getStringExtra("debug_query")))
        }
    }

    private fun debugDumpMainGrid() {
        val added = ArrayList<Pair<String, Boolean>>()
        if (PetState.nurseryUnlocked(this) && PetState.nurseryMembers(this).isEmpty()) {
            PetState.ownedIndividuals(this).filter { (n, s) -> PetState.nurseryBlockReason(this, n, s) == null }
                .take(PetState.nurseryCapacity(this).coerceAtMost(2)).forEach { c -> if (PetState.nurseryAdd(this, c.first, c.second) == null) added.add(c) }
        }
        adapter.notifyDataSetChanged()
        dbg("cuadricula principal: ${allMons.size} especies en la lista, ${allMons.count { PetState.isUnlocked(this, it.name) }} desbloqueadas, contador '${ownedSpeciesCountText()}', filas ${rows.size}")
        val rv = findViewById<RecyclerView>(R.id.rv)
        val decor = window.decorView
        fun dump(fileName: String) {
            try {
                val bmp = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bmp).apply { drawColor(Color.WHITE); decor.draw(this) }
                java.io.File(filesDir, fileName).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            } catch (e: Exception) { dbg("volcado de la cuadricula fallo: $e") }
        }
        // Tres volcados: el principio (el activo en verde), la primera fila en la guarderia (rosa) y el lider de la mazmorra (rojo).
        val nursery = PetState.nurseryMembers(this).firstOrNull()?.first
        val dungeon = DungeonState.party(this).firstOrNull()?.first
        decor.postDelayed({ dump("debug_main_grid.png") }, 2000L)
        decor.postDelayed({ rows.indexOfFirst { it.name == nursery }.takeIf { it >= 0 }?.let { rv.scrollToPosition(it) } }, 2600L)
        decor.postDelayed({ dump("debug_main_grid_nursery.png") }, 3600L)
        decor.postDelayed({ rows.indexOfFirst { it.name == dungeon }.takeIf { it >= 0 }?.let { rv.scrollToPosition(it) } }, 4200L)
        decor.postDelayed({
            dump("debug_main_grid_dungeon.png")
            dbg("cuadricula principal volcada (guarderia: ${PetState.nurseryMembers(this).map { it.first }}, muestra: ${added.map { it.first }}, mazmorra real: ${DungeonState.party(this).map { it.first }})")
            added.forEach { PetState.nurseryRemove(this, it.first, it.second) }
            adapter.notifyDataSetChanged()
        }, 5200L)
    }

    /** SOLO PARA PRUEBAS: lista de ejemplo para ver como se pintan los distintivos del regalo -
     *  normal, legendario (borde morado), shiny, legendario+shiny, normal. Se abre con
     *  adb shell am start -n com.example.pokegotchi/.MainActivity --ez debug_offer_preview true
     *  y es solo una VISTA PREVIA: no toca la oferta real ni el ciclo de regalos (ver showOfferDialog). */
    private fun debugDemoOffer() = listOf(
        PetState.OfferMon("pikachu", 25),
        PetState.OfferMon("mewtwo", 150),
        PetState.OfferMon("eevee", 133, shiny = true),
        PetState.OfferMon("rayquaza", 384, shiny = true),
        PetState.OfferMon("gengar", 94)
    )

    override fun onResume() {
        super.onResume()
        if (!PetState.hasChosenStarter(this)) return   // aun redirigiendo a StarterActivity
        if (intent?.getBooleanExtra("debug_offer_preview", false) == true) {
            intent.removeExtra("debug_offer_preview")
            window.decorView.post { showOfferDialog(debugDemoOffer()) }
        }
        // SOLO PARA PRUEBAS: abre la guarderia y vuelca su vista (y la del selector) a PNG en filesDir.
        //   adb shell am start -n com.example.pokegotchi/.MainActivity --ez debug_nursery_preview true
        if (intent?.getBooleanExtra("debug_nursery_preview", false) == true) {
            intent.removeExtra("debug_nursery_preview")
            window.decorView.post { showNurseryDialog(preview = true) }
        }
        openNurseryIfRequested()
        // SOLO PARA PRUEBAS (cuenta secundaria): abre el selector de evolucion de una especie con varias ramas y vuelca su ventana a
        // filesDir/debug_evo_choice.png (marca con Poke Ball las que ya tienes).
        //   adb shell am start -S --user 999 -n com.example.pokegotchi/.MainActivity --es debug_evo_choice eevee [--ez shiny true]
        intent?.getStringExtra("debug_evo_choice")?.takeIf { QaHarness.isSecondaryUser() }?.let { species ->
            val shinyWanted = intent.getBooleanExtra("shiny", false)
            intent.removeExtra("debug_evo_choice"); intent.removeExtra("shiny")
            window.decorView.post {
                val options = PetState.evolutionInfo(this, species)?.evolvesTo.orEmpty()
                if (options.isNotEmpty()) {
                    val dlg = showEvolutionChoiceDialog(species, options, shinyWanted)
                    dumpDialogView(dlg, "debug_evo_choice.png", 1500)
                    window.decorView.postDelayed({ dlg.dismiss() }, 2500)
                }
            }
        }
        // SOLO PARA PRUEBAS (cuenta secundaria): abre el dialogo "Cadena evolutiva y mecanicas" de una especie y vuelca su ventana a filesDir/debug_chain.png.
        //   adb shell am start -S --user 999 -n com.example.pokegotchi/.MainActivity --es debug_chain applin
        intent?.getStringExtra("debug_chain")?.takeIf { QaHarness.isSecondaryUser() }?.let { species ->
            intent.removeExtra("debug_chain")
            window.decorView.post {
                showEvolutionAndMechanicsDialog(species)
                lastChainDialog?.let { dumpDialogView(it, "debug_chain.png", 2000) }
            }
        }
        intent?.getBooleanExtra("debug_candy", false)?.takeIf { it && QaHarness.isSecondaryUser() }?.let {
            intent.removeExtra("debug_candy")
            window.decorView.post { debugCandyFlow() }
        }
        openDungeonDumpIfRequested()
        // SOLO PARA PRUEBAS: vuelca la cuadricula principal (con 2 Pokemon de muestra en la guarderia si esta vacia, para ver
        // el rosa; el rojo es el de la mazmorra real y el verde el activo) a filesDir/debug_main_grid.png.
        //   adb shell am start -n com.example.pokegotchi/.MainActivity --ez debug_grid_dump true
        if (intent?.getBooleanExtra("debug_grid_dump", false) == true) {
            intent.removeExtra("debug_grid_dump")
            // --es debug_filter all|favorites: abre esa pestaña antes de volcar (por defecto, Mis Pokemon).
            when (intent.getStringExtra("debug_filter")) { "all" -> setFilterMode(FILTER_ALL); "favorites" -> setFilterMode(FILTER_FAVORITES) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ debugDumpMainGrid() }, 1500L)
        }
        // Retoma el avance de fondo de la mazmorra (o la espera del cooldown). Aqui y no en
        // onCreate: tocar el aviso "mazmorra parada" del widget con la app ya en recientes la
        // trae al frente SIN pasar por onCreate, y el servicio tiene que reanudarse igualmente.
        // Idempotente - onStartCommand solo reprograma el ciclo y recupera ciclos perdidos.
        if (DungeonState.isExploring(this) || DungeonState.isInCooldown(this)) DungeonService.start(this)
        // Abrir la app es evidencia real de que el usuario esta despierto - ver
        // PetState.recordInteraction (aprendizaje de la ventana de noche adaptativa).
        PetState.recordInteraction(this)
        // Cada aviso se quita SOLO cuando se resuelve de verdad su propia accion (huevo: al
        // reclamar/soltarlo; regalo: al elegir/descartarlo; evolucion/atencion urgente: se
        // autocorrigen solas en checkAlerts en cuanto la condicion deja de darse) - pedido
        // explicito del usuario, nada de un cancelAll general solo por abrir la app: si un aviso
        // sigue sin resolver, debe seguir colgado aunque se entre a mirar otra cosa.
        // Refrescar SIEMPRE cual es el Pokemon activo de verdad antes de repintar el nivel - bug
        // real reportado (nivel distinto entre la cabecera y el widget): si el Pokemon activo
        // cambio mientras la Activity estaba en segundo plano (evoluciono, eclosiono un huevo...
        // desde el widget, sin pasar por aqui), "selected" se quedaba con el nombre VIEJO. Como
        // levelOf() usa el ritmo de crecimiento de esa especie para pasar de xp a nivel, aplicar
        // el ritmo de la especie equivocada podia dar un nivel distinto al mismo xp real - el
        // widget, que SI usa siempre PetState.currentPokemon() fresco, ya enseñaba el correcto.
        if (selected != PetState.currentPokemon(this)) {
            // Mismo refresco que al quedarse con la cria de un huevo (confirmKeepEggHatch): si
            // cambio de verdad, tambien hay que recolocar la pestaña de generacion y repintar la
            // cuadricula (si no, se quedaria resaltada/filtrada la especie vieja).
            selected = PetState.currentPokemon(this)
            currentGen = allMons.find { it.name == selected }?.gen ?: currentGen
            refreshRows()
            buildGenRow()
            adapter.notifyDataSetChanged()
        }
        // Repintar el nivel SIEMPRE al reanudar - bug real reportado: si Android reutiliza la
        // Activity (onResume sin onCreate, ej. al volver de segundo plano) esto antes NO se
        // llamaba, asi que se quedaba con el nivel de la ULTIMA vez que se abrio, aunque el
        // widget (que si se repinta solo en cada tick) ya llevase rato enseñando uno mas alto.
        // Antes, liquidar la guarderia (XP y huevos de lo que ha pasado con la app cerrada): asi el
        // nivel de entrenador que se pinta ya cuenta ese XP y las plazas que se abran se ven al momento.
        try { PetState.settleNursery(this) } catch (t: Throwable) { dbg("guarderia: error al liquidar al abrir: $t") }
        showLevel()
        // Ofertas periodicas (Fase 3): NO hay popup automatico (mismo criterio que la
        // evolucion) - solo se genera en silencio si toca, y se avisa con el icono 🎁 del
        // encabezado; el jugador decide cuando mirarla.
        PetState.catchUpStarterGiftIfWaiting(this)
        PetState.maybeGenerateOffer(this)
        refreshOfferBadge()
        // Repinta SIEMPRE el icono de huevo de "Mis Pokemon": antes solo se refrescaba si cambio
        // el Pokemon activo (bloque de arriba), asi que si el huevo se ponia/soltaba mientras la
        // Activity estaba en segundo plano (ej. desde el widget) el icono se quedaba desfasado
        // hasta el siguiente cambio de activo - pedido explicito del usuario: la notificacion de
        // huevo puesto abria la app sin dar ninguna pista de a quien pertenecia.
        adapter.notifyDataSetChanged()
        // Huevo listo: a diferencia de ofertas/evolucion (silenciosas, el jugador decide cuando
        // mirar), aqui SI se muestra directo al abrir - es la propia gracia del huevo ("entrar a
        // descubrir que te ha salido"), y ya hubo una notificacion avisando de que tocaba mirar.
        if (!eggHatchDialogShowing && PetState.hasActiveEgg(this) && PetState.isEggReady(this)) showEggHatchDialog()
        // Consejos de primera vez de esta pantalla (uno por apertura): la coleccion la primera vez, y despues el huevo, el regalo y
        // las fichas de fondo cuando ocurren por primera vez.
        window.decorView.post { TutorialTips.showPending(this, TutorialTips.Tip.MAIN, TutorialTips.Tip.EGG, TutorialTips.Tip.GIFT, TutorialTips.Tip.BACKGROUNDS, resolve = ::tipTarget) }
        // SOLO PARA PRUEBAS: abre el tutorial en modo sandbox (sin guardar nada) en el paso N y vuelca su vista a
        // filesDir/debug_tutorial_<N>.png (TrainerSetupActivity no esta exportada, asi que no se puede lanzar directo por adb):
        //   adb shell am start -S -n com.example.pokegotchi/.MainActivity --ei debug_tutorial 1
        // SOLO PRUEBAS (cuenta secundaria): barrido de interfaz (ver runUiSweep).
        intent?.getStringExtra("debug_ui_sweep")?.takeIf { QaHarness.isSecondaryUser() }?.let { mode ->
            intent.removeExtra("debug_ui_sweep")
            window.decorView.postDelayed({ runUiSweep(mode) }, 1500L)
        }
        if (intent?.getBooleanExtra("debug_intro", false) == true) {
            intent.removeExtra("debug_intro")
            startActivity(Intent(this, TrainerSetupActivity::class.java)
                .putExtra(TrainerSetupActivity.EXTRA_DEBUG_SANDBOX, true)
                .putExtra(TrainerSetupActivity.EXTRA_DEBUG_INTRO, true))
        }
        if (intent?.hasExtra("debug_tutorial") == true) {
            val step = intent.getIntExtra("debug_tutorial", 0)
            intent.removeExtra("debug_tutorial")
            startActivity(Intent(this, TrainerSetupActivity::class.java)
                .putExtra(TrainerSetupActivity.EXTRA_DEBUG_SANDBOX, true)
                .putExtra(TrainerSetupActivity.EXTRA_DEBUG_PREVIEW_STEP, step)
                .putExtra(TrainerSetupActivity.EXTRA_DEBUG_DUMP, true))
        }
        // SOLO PARA PRUEBAS: muestra un consejo (clave) y vuelca su ventana a filesDir/debug_tip_<clave>_<paso>.png. Extra opcional
        // "debug_step": el paso de la pista que se vuelca (la mazmorra vuelca su propia pantalla):
        //   adb shell am start -S -n com.example.pokegotchi/.MainActivity --es debug_tip main --ei debug_step 2
        intent?.getStringExtra("debug_tip")?.let { key ->
            val stepWanted = intent.getIntExtra("debug_step", 0)
            val intentTap = intent.getBooleanExtra("debug_tap", false)
            val intentPick = intent.getBooleanExtra("debug_pick", false)   // ademas del toque, elige un candidato y confirma
            intent.removeExtra("debug_tip"); intent.removeExtra("debug_step"); intent.removeExtra("debug_tap"); intent.removeExtra("debug_pick")
            TutorialTips.Tip.values().firstOrNull { it.key == key }?.let { tip ->
                val h = android.os.Handler(android.os.Looper.getMainLooper())
                h.postDelayed({
                    showTipFromDebug(tip, stepWanted)
                    if (tip == TutorialTips.Tip.DUNGEON || tip == TutorialTips.Tip.COMPANIONS) return@postDelayed   // esa pantalla vuelca la suya
                    h.postDelayed({
                        val ov = TutorialTips.lastOverlay?.takeIf { it.isAttachedToWindow }
                        ov?.debugGoTo(stepWanted)
                        // --ez debug_tap true: toca de verdad lo resaltado (la cadena regalo -> ventana del regalo -> segunda pista).
                        val tapped = intentTap && ov != null
                        if (tapped) h.postDelayed({ ov?.debugTapTarget() }, 800L)
                        if (tapped && intentPick) {
                            h.postDelayed({ TutorialTips.lastOverlay?.takeIf { it.isAttachedToWindow }?.debugGoTo(2) }, 3300L)
                            h.postDelayed({ ((lastOfferGrid?.getChildAt(0) as? ViewGroup)?.getChildAt(0))?.performClick() }, 3900L)
                            h.postDelayed({ lastConfirmDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.performClick() }, 4800L)
                        }
                        h.postDelayed({
                            val overlayNow = (if (tapped) TutorialTips.lastOverlay else ov)?.takeIf { it.isAttachedToWindow }
                            val extra = listOfNotNull(lastOfferDialog?.takeIf { it.isShowing }?.window?.decorView) + TutorialTips.debugWindows
                            if (overlayNow != null) {
                                // Con la capa en una ventana propia hay que pintar la actividad, los dialogos y la capa en su sitio.
                                val b = TutorialTips.debugComposite(this, window.decorView, extra, overlayNow.rootView.takeIf { it !== window.decorView })
                                java.io.File(filesDir, "debug_tip_${key}_$stepWanted.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                val ol = IntArray(2); overlayNow.getLocationOnScreen(ol)
                                // Control: la capa sola sobre gris, para ver hasta donde llega el oscurecido.
                                TutorialTips.debugComposite(this, overlayNow.rootView).let { ob ->
                                    java.io.File(filesDir, "debug_tip_${key}_${stepWanted}_overlay.png").outputStream().use { ob.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    ob.recycle()
                                }
                                b.recycle(); dbg("consejo '$key' paso $stepWanted volcado (pantalla entera); capa ${overlayNow.width}x${overlayNow.height} en ${ol[0]},${ol[1]}; raiz ${overlayNow.rootView.width}x${overlayNow.rootView.height}")
                            } else (TutorialTips.lastDialog?.window?.decorView)?.let { dumpViewToFile(it, "debug_tip_${key}_$stepWanted.png") }
                        }, if (tapped && intentPick) 7500L else if (tapped) 3500L else 700L)
                    }, 2500L)
                }, 1500L)
            }
        }
    }

    private var lastDetailDialog: androidx.appcompat.app.AlertDialog? = null

    private fun collectTexts(v: View?, out: MutableList<String>) {
        if (v is TextView) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out)
    }

    /** SOLO PRUEBAS (cuenta secundaria; `--es debug_ui_sweep detail|detail_all|lists`): abre la ficha de CADA especie (o mide el repintado de la
     *  lista con una coleccion enorme) y anota en el log excepciones, textos raros ("null", "NaN"...) y tiempos. Resultado: lineas "SWEEP ..." del log. */
    private fun runUiSweep(mode: String) {
        val log = { m: String -> DebugLog.log(this, "SWEEP $m") }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        log("arranca '$mode' con ${PetState.ownedIndividuals(this).size} individuos")
        if (mode == "lists") {
            val savedSort = sortBy; val savedMode = filterMode
            for (m in listOf(FILTER_MINE, FILTER_FAVORITES, FILTER_ALL)) for (sb in listOf("id", "level", "date", "name", "shiny")) {
                filterMode = m; sortBy = sb
                val t0 = System.nanoTime()
                try { refreshRows(); adapter.notifyDataSetChanged() } catch (e: Throwable) { log("EXC lista $m/$sb: $e") }
                log("lista $m orden=$sb: ${rows.size} filas en ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            for (q in listOf("a", "pi", "#25", "zzz")) {
                searchQuery = q; filterMode = FILTER_ALL
                val t0 = System.nanoTime(); refreshRows(); adapter.notifyDataSetChanged()
                log("busqueda '$q': ${rows.size} filas en ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            searchQuery = ""; sortBy = savedSort; filterMode = savedMode; refreshRows(); adapter.notifyDataSetChanged()
            log("fin 'lists'")
            return
        }
        if (mode == "dialogs") {
            val steps = listOf<Pair<String, () -> Unit>>(
                "ajustes" to { showSettingsDialog() },
                "ficha de entrenador" to { showTrainerCardDialog() },
                "objetos de la Pokedex" to { showItemsPokedexDialog() },
                "fondos" to { showBackgroundPickerDialog() },
                "favorito destacado" to { showFavoriteShowcasePickerDialog() },
                "mochila" to { showBagDialog() },
                "selector de caramelos" to { showCandyPicker() },
                "selector de guarderia" to { showNurseryPicker({}) },
                "ajustes de guarderia" to { showNurserySettings({}) },
                "guarderia (escena)" to { showNurseryDialog() },
                "regalo" to { showOfferDialog() },
                "debug: coleccion" to { showDebugOwnedGridDialog() },
                "debug: añadir" to { showDebugAddGridDialog() },
                "debug: editor" to { showDebugPokemonEditorDialog() },
                "log" to { showDebugLogDialog() },
                "evolucion y mecanicas" to { showEvolutionAndMechanicsDialog(PetState.currentPokemon(this)) },
            )
            var k = 0; var exc = 0
            fun next() {
                if (k >= steps.size) { log("fin 'dialogs': ${steps.size} dialogos, $exc excepciones"); return }
                val (name, fn) = steps[k++]
                val t0 = System.nanoTime()
                try { fn() } catch (e: Throwable) { exc++; log("EXC dialogo '$name': $e @ ${e.stackTrace.take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }}") }
                log("dialogo '$name': ${(System.nanoTime() - t0) / 1_000_000} ms")
                handler.postDelayed({ next() }, 700L)
            }
            next()
            return
        }
        val list = if (mode == "detail") allMons.filter { isCurrentlyOwnedSpecies(it.name) } else allMons
        var i = 0; var exc = 0; var weird = 0; var maxMs = 0L
        val slow = ArrayList<String>()
        fun step() {
            if (i >= list.size) { log("fin '$mode': ${list.size} fichas, $exc excepciones, $weird con textos raros, la mas lenta $maxMs ms; >250ms: ${slow.take(15)}"); return }
            val mon = list[i++]
            val t0 = System.nanoTime()
            try { showDetailDialog(mon, interactive = (mode == "detail")) } catch (e: Throwable) {
                exc++; log("EXC ficha ${mon.name}: $e @ ${e.stackTrace.take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }}")
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > maxMs) maxMs = ms
            if (ms > 250) slow.add("${mon.name}=${ms}ms")
            handler.postDelayed({
                lastDetailDialog?.let { d ->
                    val texts = ArrayList<String>(); collectTexts(d.window?.decorView, texts)
                    val bad = texts.filter { it.contains("null") || it.contains("NaN") || it.contains("Infinity") || it.contains("%s") || it.contains("%d") }
                    if (bad.isNotEmpty()) { weird++; log("TEXTO RARO ficha ${mon.name}: ${bad.take(3)}") }
                    try { d.dismiss() } catch (_: Exception) {}
                }
                lastDetailDialog = null
                step()
            }, 40L)
        }
        step()
    }

    /** Revelacion del huevo: antes de enseñar el resultado, se ve una animacion de eclosion de
     *  verdad (fases de grieta + temblor + sonido de crujido, "como el de Pokemon GO", pedido
     *  explicito del usuario) y solo AL FINAL aparece el sprite (mismo animado que las ofertas)
     *  con el resultado ya decidido (especie = forma base de la cadena del padre, shiny = ya
     *  tirado al ponerlo) - aqui solo se elige que hacer con el. */
    private fun showEggHatchDialog() {
        val species = PetState.eggHatchSpecies(this) ?: return
        val shiny = PetState.eggIsShiny(this)
        eggHatchDialogShowing = true
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Mismo fondo aleatorio a pantalla completa que la revelacion de la oferta de 5
        // Pokemon (regalo), pedido explicito del usuario para que esta pantalla tambien "quede
        // bonita" en vez de un dialogo gris plano.
        val frame = android.widget.FrameLayout(this)
        frame.addView(ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(resources.getIdentifier(backgrounds.random(), "drawable", packageName))
        })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }
        val titleView = TextView(this).apply {
            text = "🥚 Tu huevo se está abriendo..."
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(titleView)
        // Un unico hueco: primero el huevo (rompiendose de verdad, fase a fase), luego el
        // sprite revelado en su lugar - envuelto en FrameLayout para poder superponer las
        // estrellas de shiny despues, sin cambiar tamaño/posicion.
        val spriteFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(160)).apply { topMargin = dp(12) }
        }
        val eggImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val pokeImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(140), dp(120)).apply { topMargin = dp(20) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
        }
        spriteFrame.addView(eggImg)
        spriteFrame.addView(pokeImg)
        root.addView(spriteFrame)
        val nameView = TextView(this).apply {
            text = species
            textSize = 15f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            alpha = 0f
        }
        root.addView(nameView)
        frame.addView(root)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(frame)
            .setCancelable(false)
            .setPositiveButton("Quedármelo", null)
            .setNegativeButton("Soltarlo", null)
            .create()
        // Libera el cerrojo pase lo que pase (reclamado, soltado, o la Activity destruida a
        // medias) - asi nunca se queda bloqueado sin poder volver a mostrar la revelacion.
        dialog.setOnDismissListener { eggHatchDialogShowing = false }
        // Los listeners de los botones se ponen a mano (en vez de en setPositiveButton/
        // setNegativeButton de arriba) para que NO cierren este dialogo solos: antes de aplicar
        // nada se muestra un aviso de "¿seguro?" explicando la consecuencia, y solo si se
        // confirma ahi se ejecuta la accion y se cierra esta pantalla de revelacion. Si el
        // usuario cancela el aviso, sigue viendo esta misma pantalla tal cual.
        dialog.setOnShowListener {
            val posBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val negBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
            // Ocultos hasta que el Pokemon salga de verdad del huevo (ver playEggHatchAnimation) -
            // antes se podia "quedar/soltar" un resultado que ni se habia visto aparecer todavia.
            posBtn.visibility = View.INVISIBLE
            negBtn.visibility = View.INVISIBLE
            posBtn.setOnClickListener { confirmKeepEggHatch(dialog, species) }
            negBtn.setOnClickListener { confirmReleaseEggHatch(dialog) }
            playEggHatchAnimation(eggImg, pokeImg, titleView, nameView, posBtn, negBtn, species, shiny, spriteFrame, dp(140), dp(120))
        }
        dialog.show()
    }

    /** Recorre las 4 fases de grieta REALES del huevo (las mismas de EffectGenerator.eggBitmap
     *  que ya se ven en el widget - no una animacion de mentira aparte) con un temblor +
     *  SoundManager.playEggCrack en cada paso, termina con la cascara abierta, y entonces revela
     *  el Pokemon en su lugar (destello+sonido de shiny si toca) - pedido explicito del usuario
     *  ("como el de Pokemon GO... pasando por todas las fases del huevo"). playEggCrack ya
     *  existia (sonido+funcion) de una rafaga antigua ya retirada del widget - se reutiliza aqui
     *  tal cual, no hacia falta ningun sonido nuevo. */
    private fun playEggHatchAnimation(
        eggImg: ImageView, pokeImg: ImageView, titleView: TextView, nameView: TextView,
        posBtn: View, negBtn: View, species: String, shiny: Boolean,
        spriteFrame: FrameLayout, w: Int, h: Int
    ) {
        val handler = android.os.Handler(mainLooper)
        val shakeAngles = floatArrayOf(-12f, 10f, -8f, 6f, -3f, 0f)

        fun crackBurst(stage: Int, onDone: () -> Unit) {
            SoundManager.playEggCrack(this)
            var i = 0
            fun step() {
                if (i >= shakeAngles.size) { onDone(); return }
                eggImg.setImageBitmap(EffectGenerator.eggBitmap(this, stage, hatched = false, shakeAngleDeg = shakeAngles[i]))
                i++
                handler.postDelayed(::step, 90L)
            }
            step()
        }

        fun reveal() {
            eggImg.animate().alpha(0f).setDuration(250).withEndAction { eggImg.visibility = View.GONE }.start()
            pokeImg.visibility = View.VISIBLE
            loadAnimatedSprite(pokeImg, PetState.eggHatchSpriteName(this) ?: species, shiny)
            pokeImg.animate().alpha(1f).setDuration(350).start()
            titleView.text = if (shiny) "✨ ¡El huevo ha eclosionado... y es SHINY!" else "El huevo ha eclosionado"
            nameView.animate().alpha(1f).setDuration(350).start()
            if (shiny) {
                SoundManager.playShiny(this)
                EffectGenerator.playShinySparkles(this, spriteFrame, w, h)
            }
            posBtn.visibility = View.VISIBLE
            negBtn.visibility = View.VISIBLE
        }

        eggImg.setImageBitmap(EffectGenerator.eggBitmap(this, 0, hatched = false))
        handler.postDelayed({
            crackBurst(0) {
                eggImg.setImageBitmap(EffectGenerator.eggBitmap(this, 1, hatched = false))
                handler.postDelayed({
                    crackBurst(1) {
                        eggImg.setImageBitmap(EffectGenerator.eggBitmap(this, 2, hatched = false))
                        handler.postDelayed({
                            crackBurst(2) {
                                eggImg.setImageBitmap(EffectGenerator.eggBitmap(this, 3, hatched = false))
                                handler.postDelayed({
                                    crackBurst(3) {
                                        eggImg.setImageBitmap(EffectGenerator.eggBitmap(this, 0, hatched = true))
                                        handler.postDelayed({ reveal() }, 500L)
                                    }
                                }, 350L)
                            }
                        }, 350L)
                    }
                }, 350L)
            }
        }, 500L)
    }

    /** Destello de estrellitas (✨) alrededor del sprite, como en los juegos de verdad al
     *  encontrar un shiny - pedido explicito del usuario: algunos shiny casi no se distinguen
     *  del normal a simple vista, y esto deja claro que SI lo es antes de decidir soltarlo. Cada
     *  estrella parpadea+gira+escala en un bucle propio con retardo aleatorio entre vueltas (para
     *  que no titilen todas a la vez, mas organico) - el bucle se para solo cuando el dialogo se
     *  cierra (la vista deja de estar attached), sin depender de cancelar nada a mano. */
    /** Aviso de "¿seguro?" antes de quedarse con el Pokemon del huevo: deja claro que el Pokemon
     *  ACTUAL (con el que se lleva jugando hasta ahora) se pierde. La cria del huevo NO siempre
     *  empieza en nivel 1: si ya existia un individuo de esa misma especie+shiny sin evolucionar
     *  (ej. un Treecko normal "de repuesto" de un huevo anterior nunca evolucionado), setPokemon
     *  lo reactiva tal cual estaba en vez de resetearlo (ver isFreshIndividual, PetState.kt) -
     *  texto desactualizado detectado por el usuario tras el cambio que hizo que normal/shiny (y
     *  distintos individuos de la misma especie) convivan de verdad en vez de pisarse. */
    private fun confirmKeepEggHatch(hatchDialog: androidx.appcompat.app.AlertDialog, species: String) {
        // Si ya tienes a esa cria y esta explorando una mazmorra, no puede pasar a ser tu compañero activo hasta que vuelva.
        if (DungeonState.isInParty(this, species, PetState.eggIsShiny(this))) {
            Toast.makeText(this, "${PetState.displayLabel(species)} está en la mazmorra: no puedes quedártelo hasta que salga (o suelta el huevo)", Toast.LENGTH_LONG).show()
            return
        }
        val current = PetState.currentPokemon(this)
        val level = PetState.levelOf(this, PetState.loadWithDecay(this).xp, current)
        val hatchShiny = PetState.eggIsShiny(this)
        val alreadyExists = PetState.hasIndividual(this, species, hatchShiny) && !PetState.hasEvolvedAway(this, species, hatchShiny)
        val continuationText = if (alreadyExists) {
            val existingLevel = PetState.levelForPokemon(this, species, hatchShiny) ?: 1
            "Ya tenías un $species ${if (hatchShiny) "shiny " else ""}sin evolucionar: retomarás ese mismo, en Nv. $existingLevel."
        } else {
            "$species empezará desde nivel 1."
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¿Seguro?")
            .setMessage(
                "Si te quedas con $species vas a dejar de cuidar a tu $current (Nv. $level) y todo lo " +
                "que hubiera evolucionado a partir de ahi. $continuationText"
            )
            .setPositiveButton("Sí, quedármelo") { _, _ ->
                val shinyKept = PetState.eggIsShiny(this)
                val kept = PetState.keepEggHatch(this)
                dbg("huevo: reclamado como $kept (shiny=$shinyKept), $current (Nv. $level) queda evolucionado")
                NotificationHelper.cancelEggLaid(this)
                NotificationHelper.cancelEggHatched(this)
                WidgetRefresh.updateWidgets(this)
                selected = PetState.currentPokemon(this)
                currentGen = allMons.find { it.name == selected }?.gen ?: currentGen
                refreshRows()
                buildGenRow()
                adapter.notifyDataSetChanged()
                showLevel()
                Toast.makeText(this, "¡Ahora cuidas a $kept!", Toast.LENGTH_LONG).show()
                hatchDialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Aviso de "¿seguro?" antes de soltar al Pokemon del huevo: deja claro que se pierde. */
    private fun confirmReleaseEggHatch(hatchDialog: androidx.appcompat.app.AlertDialog) {
        val candyNote = if (PetState.candiesUnlocked(this)) " A cambio recibirás 1 caramelo en la mochila 🎒." else ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¿Seguro?")
            .setMessage("Si sueltas a este Pokémon lo perderás para siempre. Tocará esperar a otro huevo desde cero.$candyNote")
            .setPositiveButton("Sí, soltarlo") { _, _ ->
                dbg("huevo: soltado (especie ${PetState.eggHatchSpecies(this)}, shiny=${PetState.eggIsShiny(this)})")
                val gotCandy = PetState.releaseEggForCandy(this)
                NotificationHelper.cancelEggLaid(this)
                NotificationHelper.cancelEggHatched(this)
                WidgetRefresh.updateWidgets(this)
                refreshUnlockButtons()
                Toast.makeText(
                    this,
                    "Has soltado al Pokémon." + (if (gotCandy) " ¡+1 caramelo en la mochila!" else "") + " Tocará esperar a otro huevo desde cero.",
                    Toast.LENGTH_LONG
                ).show()
                hatchDialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** SOLO PRUEBAS: el ultimo dialogo de regalo abierto (para volcar la pantalla entera con su pista). */
    private var lastOfferDialog: androidx.appcompat.app.AlertDialog? = null
    private var lastOfferGrid: ViewGroup? = null
    private var lastConfirmDialog: androidx.appcompat.app.AlertDialog? = null

    private fun refreshOfferBadge() {
        val hasOffer = PetState.currentOffer(this).isNotEmpty()
        findViewById<TextView>(R.id.btn_offer).visibility = if (hasOffer) View.VISIBLE else View.GONE
        val tvTimer = findViewById<TextView>(R.id.tv_offer_timer)
        // Pedido explicito del usuario: mientras el regalo esta disponible sin abrir, el timer se
        // OCULTA (el plazo de gracia sigue contando por detras, solo que no se muestra) - se
        // vuelve a mostrar de golpe al abrirlo/descartarlo, ya contando el ciclo siguiente (que
        // arranca desde que ESTE regalo aparecio, no desde ahora - ver resolveOffer/dismissOffer).
        if (hasOffer) {
            tvTimer.visibility = View.GONE
            return
        }
        val remainingMs = PetState.offerCooldownRemainingMs(this)
        if (remainingMs > 0L) {
            tvTimer.text = "🎁 ${formatCountdown(remainingMs)}"
            tvTimer.visibility = View.VISIBLE
        } else {
            tvTimer.visibility = View.GONE
        }
    }

    /** "1d 4h" / "4h 12m" / "12m 30s" / "30s" segun la magnitud - siempre las 2 unidades mas
     *  relevantes, bajando hasta segundos cuando ya queda menos de un minuto. */
    private fun formatCountdown(ms: Long): String {
        val totalSec = ms / 1000L
        val days = totalSec / 86400
        val hours = (totalSec / 3600) % 24
        val minutes = (totalSec / 60) % 60
        val seconds = totalSec % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** "Xd Yh"/"Xh Ym"/"Xm" para PetState.careHours - mismo estilo que formatCountdown. */
    private fun formatCareHours(hours: Float): String {
        val totalMin = (hours * 60).toLong().coerceAtLeast(0L)
        val days = totalMin / (24 * 60)
        val hrs = (totalMin / 60) % 24
        val mins = totalMin % 60
        return when {
            days > 0 -> "${days}d ${hrs}h"
            hrs > 0 -> "${hrs}h ${mins}m"
            else -> "${mins}m"
        }
    }

    /** Miniatura estatica de [name]: primero el paquete LOCAL (assets/localsprites), sin red -
     *  solo si esa especie no esta ahi (hoy en dia, solo urshifu-rapid normal) se llama a
     *  [onNetworkFallback] para cargarla de Showdown como hasta ahora. [isStillValid] protege las
     *  celdas de RecyclerView recicladas (por defecto siempre valido, para dialogos que no se
     *  reciclan) - la respuesta del hilo de fondo podria llegar cuando la celda ya es de OTRO
     *  Pokemon, y sin este chequeo se pintaria encima por error. Ya escalada segun el modo de
     *  suavizado elegido (antes se dejaba a resolucion nativa y el ImageView la desenfocaba solo
     *  con su filtro bilineal por defecto, sin respetar "ninguno/parcial/total") - por eso
     *  tambien se desactiva el filtro del ImageView, para no volver a desenfocar por encima. */
    private fun loadLocalOrNetworkThumb(
        imageView: ImageView, name: String, shiny: Boolean,
        isStillValid: () -> Boolean = { true },
        onNetworkFallback: () -> Unit
    ) {
        Thread {
            val bmp = SpriteRepository.localFirstFrameScaled(imageView.context, name, shiny, PetState.spriteScaleMode(imageView.context))
            runOnUiThread {
                if (!isStillValid()) return@runOnUiThread
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = false
                } else onNetworkFallback()
            }
        }.start()
    }

    /** Anima [imageView] ciclando a mano los fotogramas de [frames] cada [intervalMs] (mismo
     *  patron que el resto de bucles de animacion de este archivo, ver sparkleLoop): se para solo
     *  cuando la vista se desengancha (dialogo cerrado), no hace falta cancelar el Handler a
     *  mano. isFilterBitmap se desactiva en cada fotograma para no desenfocar por encima del
     *  escalado Scale2x ya aplicado (mismo motivo que en loadLocalOrNetworkThumb). */
    private fun playLocalFrames(imageView: ImageView, frames: List<Bitmap>, intervalMs: Int) {
        if (frames.isEmpty()) return
        val handler = android.os.Handler(mainLooper)
        var idx = 0
        fun step() {
            if (!imageView.isAttachedToWindow) return
            imageView.setImageBitmap(frames[idx])
            (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = false
            idx = (idx + 1) % frames.size
            handler.postDelayed(::step, intervalMs.toLong())
        }
        step()
    }

    /** Sprite animado de [name] en [imageView]: paquete LOCAL primero (assets/localsprites, sin
     *  red, mismo criterio que loadLocalOrNetworkThumb) - solo si esa especie no esta ahi (hoy en
     *  dia, solo urshifu-rapid normal) se cae al GIF de Showdown como antes. Antes esta funcion iba
     *  SIEMPRE a la red aunque el resto del juego ya fuese local-first: se detecto al desactivar
     *  WiFi/datos de la app para simular offline, y el sprite de la oferta se quedaba estatico en
     *  silencio (fallo de red capturado y descartado) en vez de animarse con el paquete local ya
     *  disponible. [shiny]: en la oferta de 5 (candidatos que AUN NO se tienen, nunca tirados)
     *  siempre es false; en la revelacion del huevo SI hace falta (el shiny ya se tiro al
     *  ponerlo) - antes se ignoraba aqui y el sprite salia siempre en color normal aunque el
     *  huevo fuese shiny de verdad (solo se notaba por las estrellitas/sonido), pedido explicito
     *  del usuario ("verlo saliendo del huevo" ya en shiny). [intervalOverrideMs]: por defecto
     *  null (usa el intervalo de siempre, LOCAL_SPRITE_INTERVAL_MS=100ms) - la ficha de
     *  entrenador pide uno mas lento (pedido explicito del usuario, "va muy rapido") porque ahi
     *  el sprite se queda en bucle mientras se mira la tarjeta entera, a diferencia de la
     *  revelacion puntual del huevo/oferta. */
    private fun loadAnimatedSprite(imageView: ImageView, name: String, shiny: Boolean = false, intervalOverrideMs: Int? = null) {
        Thread {
            val local = SpriteRepository.localAnimatedFramesScaled(imageView.context, name, shiny, PetState.spriteScaleMode(imageView.context))
            runOnUiThread {
                if (local != null) {
                    playLocalFrames(imageView, local.first, intervalOverrideMs ?: local.second)
                } else {
                    loadAnimatedSpriteFromNetwork(imageView, name, shiny)
                }
            }
        }.start()
    }

    /** Descarga el GIF animado de [name] y lo pone en [imageView] (se anima solo, es lo que hace
     *  GifDrawable). Si falla, cae a la miniatura estatica. Solo se usa cuando [loadAnimatedSprite]
     *  no encuentra la especie en el paquete local (gen9 no siempre tiene animado tampoco). */
    private fun loadAnimatedSpriteFromNetwork(imageView: ImageView, name: String, shiny: Boolean) {
        Thread {
            val slug = SpriteRepository.toShowdownSlug(name)
            val aniFolder = if (shiny) "gen5ani-shiny" else "gen5ani"
            var bytes: ByteArray? = null
            try {
                val conn = (java.net.URL("https://play.pokemonshowdown.com/sprites/$aniFolder/$slug.gif")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode == 200) bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (_: Exception) {}
            runOnUiThread {
                val ok = bytes?.let {
                    try { imageView.setImageDrawable(pl.droidsonroids.gif.GifDrawable(it)); true }
                    catch (_: Exception) { false }
                } ?: false
                if (!ok) {
                    val staticFolder = if (shiny) "gen5-shiny" else "gen5"
                    loadLocalOrNetworkThumb(imageView, name, shiny) {
                        imageView.load("https://play.pokemonshowdown.com/sprites/$staticFolder/$slug.png") { crossfade(true) }
                    }
                }
            }
        }.start()
    }

    /**
     * Dialogo de la oferta: fondo bonito (uno de los del juego, al azar) con los 5 candidatos
     * ANIMADOS y SIN nombre - tocar el sprite directamente lo elige y lo añade a la Pokedex
     * (sin tocar al Pokemon activo). Tambien se puede descartar la oferta entera si no gusta
     * ninguno (para que aparezcan otros mas adelante en vez de quedar atascada).
     * Distintivos: shiny = su sprite shiny + estrellitas ✨ + una ✨ en la esquina; legendario/
     * mitico = borde morado que late (las estrellitas NO se comparten - pedido del usuario).
     * [previewOffer] != null = VISTA PREVIA (solo para ver el aspecto, ver debugDemoOffer): usa esa
     * lista en vez de la oferta real y no guarda, elige ni descarta nada.
     */
    private fun showOfferDialog(previewOffer: List<PetState.OfferMon>? = null, forceTip: Boolean = false) {
        val preview = previewOffer != null
        val offer = previewOffer ?: PetState.currentOffer(this)
        if (offer.isEmpty()) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val frame = android.widget.FrameLayout(this)
        frame.addView(ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(resources.getIdentifier(backgrounds.random(), "drawable", packageName))
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        content.addView(TextView(this).apply {
            text = if (preview) "🎁 Vista previa (no se guarda nada)" else "🎁 Toca uno para añadirlo a tu Pokédex"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(14))
        })

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        // Contenedor de las filas de candidatos (lo resalta la pista del regalo).
        val gridBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(gridBox)
        // Filas manuales (en vez de GridLayout) de hasta 3, cada fila con ancho WRAP_CONTENT y
        // centrada: asi la ULTIMA fila, si queda incompleta (aqui: 2 de 5), se centra en vez de
        // quedar pegada a la izquierda con un hueco a la derecha.
        offer.chunked(3).forEach { rowMons ->
            val rowLl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(2)
                }
            }
            for (mon in rowMons) {
                val cellW = dp(92); val cellH = dp(84)
                // Legendario/mitico (ver PetState.OfferMon/evolutionInfo.rare): borde morado que
                // late (EffectGenerator.addLegendaryBorder) para que destaque sobre el resto de la
                // oferta - pedido explicito del usuario; antes compartia las estrellitas del shiny.
                val isRare = PetState.evolutionInfo(this@MainActivity, mon.name)?.rare == true
                // Shiny sorteado al generar el regalo (ver PetState.OfferMon.shiny): se muestra su
                // sprite shiny, con las estrellitas (ahora SOLO de el) y una ✨ para que se note.
                val cellFrame = android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(cellW, cellH).apply {
                        setMargins(dp(5), dp(5), dp(5), dp(5))
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#40FFFFFF"))
                        cornerRadius = dp(12).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                }
                val cell = ImageView(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                loadAnimatedSprite(cell, mon.name, mon.shiny)
                cellFrame.addView(cell)
                if (isRare) EffectGenerator.addLegendaryBorder(this, cellFrame, dp(12).toFloat(), dp(3))
                if (mon.shiny) cellFrame.addView(TextView(this).apply {
                    text = "✨"
                    textSize = 14f
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(2), dp(4), 0) }
                })
                if (mon.shiny) EffectGenerator.playShinySparkles(this, cellFrame, cellW, cellH)
                // Confirmacion antes de elegir (pedido explicito del usuario tras descartar un
                // regalo entero sin querer con un toque perdido: "en el regalo puedes poner una
                // confirmacion de descartar y de confirmacion del pokemon?") - elegir tambien es
                // irreversible (el resto de la oferta se pierde), asi que merece el mismo aviso.
                cellFrame.setOnClickListener {
                    if (preview) {
                        Toast.makeText(this@MainActivity, "Vista previa: no se añade nada", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val monLabel = PetState.displayLabel(mon.name) + if (mon.shiny) " ✨ shiny" else ""
                    lastConfirmDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(monLabel)
                        .setMessage("¿Seguro que quieres elegir a $monLabel? Se añadirá a tu Pokédex y el resto de la oferta se perderá.")
                        .setPositiveButton("Elegir") { _, _ ->
                            dbg("regalo: elegido ${mon.name}${if (mon.shiny) " (SHINY)" else ""} de entre ${offer.joinToString(",") { it.name + if (it.shiny) "(shiny)" else "" }}")
                            PetState.resolveOffer(this@MainActivity, mon.name, mon.shiny)
                            NotificationHelper.cancelOfferReady(this@MainActivity)
                            dialog.dismiss()
                            refreshOfferBadge()
                            refreshRows()
                            adapter.notifyDataSetChanged()
                            Toast.makeText(this@MainActivity, "¡$monLabel añadido a tu Pokédex!", Toast.LENGTH_LONG).show()
                            // Si la pista del regalo esperaba esta eleccion, continua (sin haber cerrado nada: la eleccion la ha hecho el jugador).
                            TutorialTips.notifyEvent("gift_picked", PetState.displayLabel(mon.name).replaceFirstChar { it.uppercase() })
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
                rowLl.addView(cellFrame)
            }
            gridBox.addView(rowLl)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        }
        val btnDismiss = Button(this).apply { text = "Descartar"; isAllCaps = false; textSize = 12f }
        val btnLater = Button(this).apply { text = "Ahora no"; isAllCaps = false; textSize = 12f }
        buttonRow.addView(btnDismiss, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        buttonRow.addView(btnLater, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(buttonRow)

        frame.addView(content)
        dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(frame).create()
        btnDismiss.setOnClickListener {
            if (preview) { dialog.dismiss(); return@setOnClickListener }
            // Confirmacion antes de descartar (pedido explicito del usuario, ver comentario de
            // arriba) - antes un solo toque perdido tiraba la oferta entera sin forma de deshacerlo.
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Descartar regalo")
                .setMessage("¿Seguro que quieres descartar esta oferta entera? No podrás recuperar estos 5 candidatos.")
                .setPositiveButton("Descartar") { _, _ ->
                    dbg("regalo: descartado entero (${offer.joinToString(",") { it.name }})")
                    PetState.dismissOffer(this)
                    NotificationHelper.cancelOfferReady(this)
                    refreshOfferBadge()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        btnLater.setOnClickListener { dialog.dismiss() }
        dialog.show()
        lastOfferDialog = dialog
        lastOfferGrid = gridBox
        // Pista DENTRO de la ventana del regalo (se llega aqui tocando el 🎁 que resalta la pista anterior): enseña las posibilidades.
        // Si se abrio sin haber visto la pista del 🎁, esa ya no hace falta.
        if (!preview || forceTip) {
            val offerTargets = { id: String -> when (id) { "offer_grid" -> gridBox; "offer_buttons" -> buttonRow; else -> tipTarget(id) } }
            // Los nombres de las opciones que de verdad le han tocado (en el dialogo salen solo los sprites).
            val offerNames = offer.map { PetState.displayLabel(it.name) }
            if (forceTip) TutorialTips.show(this, TutorialTips.Tip.GIFT_PICK, force = true, onScreen = true, resolve = offerTargets, textArgs = offerNames)
            else {
                TutorialTips.markSeen(this, TutorialTips.Tip.GIFT)
                TutorialTips.showPending(this, TutorialTips.Tip.GIFT_PICK, onScreen = true, resolve = offerTargets, textArgs = offerNames)
            }
        }
        // Vista previa: vuelca SOLO la vista del propio dialogo a PNG (sin capturar la pantalla del
        // movil) cuando ya han cargado los sprites - la forma de comprobar el aspecto desde adb.
        if (preview) frame.postDelayed({
            try {
                val bmp = android.graphics.Bitmap.createBitmap(frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
                frame.draw(android.graphics.Canvas(bmp))
                java.io.File(filesDir, "debug_offer_preview.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                dbg("regalo: vista previa volcada a debug_offer_preview.png")
            } catch (e: Exception) { dbg("regalo: volcado de la vista previa fallo: $e") }
        }, 3500)
    }

    /**
     * Descarga el sprite de [opt] y, si sale bien, evoluciona [fromName] hacia el (migra
     * stats/nivel, marca [fromName] como "evolucionado" en la Pokedex) y refresca todo.
     * Se llama desde el boton "Evolucionar a X" del dialogo de detalle de un Pokemon. [shiny] es
     * el individuo CONCRETO que se esta evolucionando (el de la ficha abierta) - no se asume el
     * activo del widget, mismo motivo que pendingEvolutionFor (bug real: evolucionar un shiny
     * desde su ficha migraba las stats del individuo normal en su lugar).
     */
    private fun doEvolve(fromName: String, opt: PetState.EvoOption, shiny: Boolean, onEvolved: (() -> Unit)? = null) {
        // Resuelto UNA VEZ aqui, antes de preparar nada: normalmente es opt.name/opt.id tal cual,
        // salvo el sorteo raro de Ursaring -> Ursaluna Luna Carmesi (ver
        // PetState.resolveEvolutionTarget) - asi el sprite/grito que se descarga y lo que
        // finalmente se muestra ya son consistentes desde el principio, sin tener que "corregir"
        // nada a medias.
        val (toName, toId) = PetState.resolveEvolutionTarget(opt.name, opt.id)
        // Si ya tienes a la especie de destino (solo puede haber un individuo vivo por especie y variante), el que evoluciona se queda y
        // sustituye al que tenias: es el comportamiento acordado (el usuario: "no puedes tener dos Charizard: el Charmeleon que evoluciona
        // pasa a ser tu Charizard"), asi que NO se pregunta; la Poke Ball del selector de ramas ya avisa de cuales tienes.
        startEvolution(fromName, opt, toName, toId, shiny, onEvolved)
    }

    /** Parte de [doEvolve] ya sin dudas: prepara los recursos y lanza la animacion; al acabar migra el estado y llama a [onEvolved]. */
    private fun startEvolution(fromName: String, opt: PetState.EvoOption, toName: String, toId: Int, shiny: Boolean, onEvolved: (() -> Unit)? = null) {
        // Sin Toast de "preparando": el sprite viene de assets/localsprites (sin red) casi
        // siempre, y hasta cuando de verdad hace falta red tarda tan poco que avisar de ello
        // sobra - pedido explicito del usuario.
        Thread {
            // Asegura TAMBIEN el sprite de la forma ANTERIOR (fromName), no solo la nueva: si no
            // ha sido el Pokemon activo desde el ultimo cambio de ajustes que afecte a la clave
            // de cache (estilo/shiny/nitidez), su cache puede estar bajo una clave antigua y
            // faltar bajo la actual - sin esto, playEvolutionAnimation no encontraba su sprite y
            // se cancelaba en silencio (sin animacion, directa al resultado).
            SpriteRepository.ensure(this, fromName, PetState.currentPokemonId(this), PetState.currentStyle(this), shiny, PetState.spriteScaleMode(this))
            val meta = SpriteRepository.ensure(this, toName, toId, PetState.currentStyle(this), shiny, PetState.spriteScaleMode(this))
            // El cry se descarga y se prepara (MediaPlayer.prepare, sincrono) aqui mismo, en este
            // hilo de fondo, para que playEvolutionAnimation solo tenga que llamar a start() en el
            // momento justo, sin bloquear la UI con la descarga o el prepare.
            val cry = CryRepository.ensure(this, toName, toId)?.let { f ->
                try {
                    android.media.MediaPlayer().apply { setDataSource(f.path); prepare() }
                } catch (e: Exception) { null }
            }
            runOnUiThread {
                if (meta != null) {
                    playEvolutionAnimation(fromName, toName, shiny, cry) {
                        // Asienta ANTES de migrar: evolveTo copia salud/higiene/felicidad/xp tal
                        // cual esten guardadas, sin aplicar el decaimiento/xp pasiva pendiente -
                        // si no se asienta aqui, esa xp pendiente se "cobraba" toda de golpe en
                        // el primer showLevel() de despues, pareciendo que evolucionar en si sube
                        // de nivel (bug real reportado por el usuario: Pikachu Nv.25 -> Raichu
                        // Nv.28 al instante).
                        PetState.loadWithDecay(this)
                        dbg("evolucion: $fromName -> $toName (metodo=${opt.method}, nivel pedido=${opt.level}, shiny=$shiny)")
                        // Si estaba en la guarderia o en el grupo de la mazmorra, se queda ahi y el activo no cambia.
                        val inNursery = PetState.isInNursery(this, fromName, shiny) && !DungeonState.isInParty(this, toName, shiny)
                        val inPlace = PetState.evolvesInPlace(this, fromName, toName, shiny)
                        PetState.evolveTo(this, fromName, toName, toId, shiny)
                        if (!inPlace) {
                            selected = toName
                            currentGen = allMons.find { it.name == selected }?.gen ?: currentGen
                        }
                        refreshRows()
                        buildGenRow()
                        adapter.notifyDataSetChanged()
                        showLevel()
                        WidgetRefresh.updateWidgets(this)
                        val label = PetState.displayLabel(toName)
                        Toast.makeText(this, when {
                            inNursery -> "¡Felicidades! $label ha evolucionado en la guardería"
                            inPlace -> "¡Felicidades! $label ha evolucionado en la mazmorra"
                            else -> "¡Felicidades! Ahora tienes a $label"
                        }, Toast.LENGTH_LONG).show()
                        onEvolved?.invoke()
                    }
                } else {
                    cry?.release()
                    Toast.makeText(this, "No se pudo descargar el sprite de ${PetState.displayLabel(toName)}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** La animacion clasica de evolucion: sprite normal -> parpadeo -> silueta blanca solida ->
     *  disolucion "a bloques" (trama fija, no un fundido liso) hacia la silueta de la nueva
     *  forma -> disolucion hacia su color real. [onDone] se llama al terminar, para aplicar el
     *  cambio de estado de verdad (la animacion en si NO toca nada de PetState). */
    /** Reparte [totalMs] entre [n] huecos con ritmo "lento -> rapido sostenido": el hueco es
     *  largo al principio y se acorta cada vez mas rapido hasta quedarse en un tembleque rapido
     *  YA NO VUELVE A FRENAR - asi es la transformacion de los juegos originales: el sprite
     *  alterna entre el Pokemon viejo y el nuevo cada vez mas rapido hasta que, justo cuando
     *  termina la musica, aparece directamente el evolucionado (sin fundido de color aparte). */
    private fun easeSlowToFast(n: Int, totalMs: Long): LongArray {
        val raw = DoubleArray(n) { i ->
            val x = (i + 0.5) / n
            0.15 + 0.85 * Math.exp(-3.0 * x)
        }
        val sum = raw.sum()
        return LongArray(n) { i -> ((raw[i] / sum) * totalMs).toLong().coerceAtLeast(12L) }
    }

    private fun playEvolutionAnimation(fromName: String, toName: String, shiny: Boolean, cry: android.media.MediaPlayer?, onDone: () -> Unit) {
        // Estilo del individuo que EVOLUCIONA DE VERDAD, no PetState.currentStyleKey(this) (que
        // resuelve el shiny contra el activo del widget) - mismo bug que ya se corrigio en
        // playMegaEvolveAnimation: si difieren, BitmapFactory.decodeFile fallaba en silencio y la
        // animacion se saltaba entera sin avisar.
        val styleKey = SpriteRepository.styleKey(PetState.currentStyle(this), shiny) + "_" + PetState.spriteScaleMode(this)
        val fromRaw = BitmapFactory.decodeFile(SpriteRepository.frameFile(this, styleKey, fromName, 0).path)
        val toRaw = BitmapFactory.decodeFile(SpriteRepository.frameFile(this, styleKey, toName, 0).path)
        if (fromRaw == null || toRaw == null) { cry?.release(); onDone(); return }

        // La fanfarria de evolucion (tema fijo, sonando desde el primer parpadeo hasta que se
        // revela el color) - se corta en seco justo cuando empieza a sonar el cry, igual que en
        // los juegos originales.
        val theme = try { MediaPlayer.create(this, R.raw.evolution_theme) } catch (e: Exception) { null }

        val w = maxOf(fromRaw.width, toRaw.width)
        val h = maxOf(fromRaw.height, toRaw.height)
        val fromBmp = SpriteRepository.centeredOn(fromRaw, w, h)
        val toBmp = SpriteRepository.centeredOn(toRaw, w, h)
        val fromSil = SpriteRepository.toWhiteSilhouette(fromBmp)
        val toSil = SpriteRepository.toWhiteSilhouette(toBmp)

        val imgView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(fromBmp)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(imgView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(root)
            setCancelable(false)
        }
        dialog.show()
        try { theme?.start() } catch (e: Exception) {}

        // La transformacion entera (parpadeo + las dos disoluciones) tiene que durar EXACTAMENTE
        // lo que dura la fanfarria real (theme.duration), no un numero de pasos fijo - si no, o
        // sobra musica sin sonar (se corta a medias) o la escena acaba antes de que la musica
        // haya arrancado de verdad. Con esto el cry siempre suena justo cuando la musica termina.
        val themeDuration = theme?.duration?.toLong()?.coerceAtLeast(2500L) ?: 5000L
        val flickerReps = 6
        val flickerStep = 150L
        val holdA = 300L
        val fixedTime = flickerReps * flickerStep + holdA
        val remaining = (themeDuration - fixedTime).coerceAtLeast(1500L)
        // Fiel al video real de los juegos: NO hay un fundido de color aparte - el sprite viejo
        // y el nuevo se alternan (superpuestos en el mismo sitio) cada vez MAS RAPIDO, sin volver
        // a frenar, y en cuanto termina la musica aparece DIRECTAMENTE el evolucionado a color (a
        // la vez que empieza a sonar su grito). steps PAR para que el ultimo fotograma antes del
        // corte sea el de la silueta nueva.
        val steps = 56
        val delays = easeSlowToFast(steps - 1, remaining)

        val handler = android.os.Handler(mainLooper)
        var revealed = false

        // Desenlace: revela el color final, corta la fanfarria de transformacion en seco y suena
        // el cry - luego la musica de "felicidades" - y cierra. Vive aparte (no solo colgado del
        // ultimo postDelayed) porque tambien se llama al saltarse la animacion con un doble
        // toque, no solo en el momento normal calculado mas abajo.
        fun revealAndFinish() {
            if (revealed) return
            revealed = true
            handler.removeCallbacksAndMessages(null)
            imgView.alpha = 1f
            imgView.setImageBitmap(toBmp)
            try { theme?.stop(); theme?.release() } catch (e: Exception) {}
            try { cry?.start() } catch (e: Exception) {}
            val cryDuration = cry?.duration?.toLong()?.coerceAtLeast(300L) ?: 600L
            val congrats = try { MediaPlayer.create(this@MainActivity, R.raw.evolution_congrats) } catch (e: Exception) { null }
            val congratsDuration = congrats?.duration?.toLong()?.coerceAtLeast(300L) ?: 1000L
            handler.postDelayed({ try { congrats?.start() } catch (e: Exception) {} }, cryDuration + 200L)
            handler.postDelayed({
                try { cry?.release() } catch (e: Exception) {}
                try { congrats?.release() } catch (e: Exception) {}
                dialog.dismiss()
                onDone()
            }, cryDuration + 200L + congratsDuration + 300L)
        }

        // Doble toque en la pantalla = saltarse toda la animacion y pasar directamente al
        // resultado (con su grito), igual que en los juegos originales.
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                revealAndFinish()
                return true
            }
        })
        root.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }

        var t = 0L
        for (i in 0 until flickerReps) {
            val showSil = i % 2 == 0
            handler.postDelayed({ imgView.setImageBitmap(if (showSil) fromSil else fromBmp) }, t)
            t += flickerStep
        }
        handler.postDelayed({ imgView.setImageBitmap(fromSil) }, t); t += holdA

        for (s in 0 until steps) {
            val showFrom = s % 2 == 0
            handler.postDelayed({ imgView.setImageBitmap(if (showFrom) fromSil else toSil) }, t)
            if (s < steps - 1) t += delays[s]
        }

        handler.postDelayed({ revealAndFinish() }, t)
    }

    /** Aplica la Megaevolucion/Gigantamax de [fromName] a [opt] con animacion: pantalla completa
     *  en negro, el sprite actual se queda quieto debajo mientras encima se superponen los
     *  frames de mega_evo_sheet.png (crece -> se agrieta -> ennegrece del todo) con el sonido de
     *  fondo descargado de myinstants, y al terminar se revela el sprite ya transformado (con su
     *  grito). Un toque en la pantalla se salta el resto de la animacion, igual que en
     *  playEvolutionAnimation. [gmax]: retiñe la animacion a rojo (ver EffectGenerator.
     *  megaEvoFrames) para diferenciarla de la Megaevolucion normal (morada), ya que Gigantamax
     *  no tiene arte propio en el paquete.
     *  Aplica PetState.activateMega SOLO al terminar, nunca antes - la animacion en si no toca
     *  el estado. */
    private fun activateMegaWithAnimation(fromName: String, opt: PetState.MegaOption, shiny: Boolean, interactive: Boolean) {
        val gmax = opt.name.endsWith("-gmax")
        fun finish() {
            dbg("mega: $fromName -> ${opt.name}")
            PetState.activateMega(this, fromName, opt.name)
            WidgetRefresh.updateWidgets(this)
            Toast.makeText(this, "¡${opt.label} activado durante unas horas!", Toast.LENGTH_LONG).show()
            // Sin esto la fila de la lista/rejilla se queda con el sprite viejo hasta el proximo
            // notifyDataSetChanged que toque por otro motivo (ej. cambiar de Pokemon activo) -
            // bug real reportado por el usuario.
            adapter.notifyDataSetChanged()
            // preferShiny/interactive explicitos: sin esto se perdia que variante (normal/shiny)
            // se estaba mirando y la ficha se reabria siempre en modo interactivo, aunque se
            // hubiera fusionado desde la Pokedex de solo lectura - bug real encontrado en el
            // repaso de codigo.
            allMons.find { it.name == fromName }?.let { showDetailDialog(it, preferShiny = shiny, interactive = interactive) }
        }
        val partner = PetState.fusionPartnerSpeciesFor(opt.name)
        Thread {
            // Asegura TAMBIEN el sprite de ORIGEN (fromName), no solo el de destino: mismo motivo
            // exacto que doEvolve (ver su comentario) - si fromName no ha sido el Pokemon activo
            // desde el ultimo cambio de estilo/shiny/nitidez, su cache puede faltar bajo la clave
            // actual. Con Megaevolucion normal esto quedaba oculto porque el nivel 40 que hace
            // falta para desbloquearla ya garantiza horas como activo (cache ya poblada de
            // rebote); las Fusiones (Necrozma/Kyurem/Calyrex) NO piden nivel, solo tener al
            // compañero, asi que se podian activar sin haber sido nunca el activo - bug real
            // reportado por el usuario ("lo he fusionado y no ha hecho la animacion"): playFusionAnimation
            // se salia en silencio en su primer BitmapFactory.decodeFile por no encontrar el
            // fichero, y onDone() aplicaba igualmente la fusion sin mostrar nada.
            SpriteRepository.ensure(this, fromName, PetState.currentPokemonId(this), PetState.currentStyle(this), shiny, PetState.spriteScaleMode(this))
            val meta = SpriteRepository.ensure(this, opt.name, PetState.currentPokemonId(this), PetState.currentStyle(this), shiny, PetState.spriteScaleMode(this))
            // Fusion (Necrozma/Kyurem/Calyrex): tambien hace falta el sprite del OTRO ingrediente
            // (Reshiram/Zekrom/Glastrier/Spectrier/Solgaleo/Lunala) para la animacion de los dos
            // sprites juntandose - normal (nunca shiny, es un individuo aparte) y en el mismo
            // estilo/nitidez que el resto.
            val partnerMeta = partner?.let {
                SpriteRepository.ensure(this, it, PetState.currentPokemonId(this), PetState.currentStyle(this), false, PetState.spriteScaleMode(this))
            }
            if (meta == null) {
                dbg("mega/fusion-anim: sin sprite para ${opt.name} (meta null) -> aplicando sin animacion")
                runOnUiThread { finish() }
                return@Thread
            }
            // Precalculados aqui (hilo de fondo), NO frame a frame en el hilo principal - eso era
            // lo que alargaba la animacion mucho mas de lo que duraba el sonido (bug real
            // reportado por el usuario, ver EffectGenerator.megaEvoFrames).
            val overlaySizePx = resources.displayMetrics.widthPixels.coerceAtMost(1024)
            val frames = EffectGenerator.megaEvoFrames(this, overlaySizePx, gmax)
            // El grito de la forma EN CONCRETO (Mega/Gigantamax SI tienen grito propio distinto
            // del de la especie base, comprobado contra PokeAPI - ver assets/cries/${opt.name}.ogg,
            // verificado uno a uno). Si por lo que sea faltase esa forma en concreto, cae al grito
            // de la especie base (dexId) como red de seguridad - mismo patron que doEvolve:
            // preparado ya (prepare sincrono) en este hilo de fondo, para que revealAndFinish solo
            // tenga que llamar a start().
            val dexId = allMons.find { it.name == fromName }?.id ?: -1
            val cry = if (dexId > 0) CryRepository.ensure(this, opt.name, dexId)?.let { f ->
                try { android.media.MediaPlayer().apply { setDataSource(f.path); prepare() } } catch (e: Exception) { null }
            } else null
            dbg("fusion-anim: opt=${opt.name} partner=$partner partnerMeta=${partnerMeta != null}")
            runOnUiThread {
                if (partner != null && partnerMeta != null) {
                    playFusionAnimation(fromName, partner, opt.name, shiny, frames, cry, ::finish)
                } else {
                    playMegaEvolveAnimation(fromName, opt.name, shiny, frames, cry, ::finish)
                }
            }
        }.start()
    }

    private fun playMegaEvolveAnimation(fromName: String, toName: String, shiny: Boolean, frames: List<Bitmap>, cry: MediaPlayer?, onDone: () -> Unit) {
        // Bug real reportado por el usuario (aparecia con Fusiones, pero es el MISMO fallo aqui):
        // currentStyleKey() usa el shiny del Pokemon ACTIVO del widget, no el de [fromName] - si
        // fromName no es el activo (ej. estas viendo la ficha de otro Pokemon ya conseguido) y sus
        // shiny no coinciden, buscaba el sprite en la carpeta de cache equivocada, no lo
        // encontraba, y abortaba en silencio directo al resultado sin animacion ninguna. [shiny] lo
        // recibe ya resuelto de activateMegaWithAnimation, en vez de volver a mirarlo aqui con la
        // preferencia legacy por especie (PetState.isShiny(this, fromName)).
        val styleKey = SpriteRepository.styleKey(PetState.currentStyle(this), shiny) + "_" + PetState.spriteScaleMode(this)
        val fromRaw = BitmapFactory.decodeFile(SpriteRepository.frameFile(this, styleKey, fromName, 0).path)
        val toRaw = BitmapFactory.decodeFile(SpriteRepository.frameFile(this, styleKey, toName, 0).path)
        if (fromRaw == null || toRaw == null) {
            dbg("mega-anim: sin fichero de sprite (fromRaw=${fromRaw != null} toRaw=${toRaw != null}) para $fromName/$toName en $styleKey -> sin animacion")
            fromRaw?.recycle(); toRaw?.recycle()
            frames.forEach { it.recycle() }
            cry?.release(); onDone(); return
        }
        // En gris hasta que termine de transformarse (pedido explicito del usuario) - se revela
        // a color solo al final, ya como la forma nueva (toRaw), nunca antes.
        val fromGray = SpriteRepository.toGrayscale(fromRaw)
        fromRaw.recycle()   // ya convertido a fromGray, no hace falta guardarlo aparte

        val sound = try { MediaPlayer.create(this, R.raw.sfx_mega_evolution) } catch (e: Exception) { null }

        // El sprite se ve mas PEQUEÑO que la bola de energia (pedido explicito del usuario: antes
        // los dos ocupaban la pantalla entera y quedaban del mismo tamaño) - la bola se queda a
        // pantalla completa (MATCH_PARENT), el sprite en un recuadro mas chico centrado encima.
        val spriteBoxPx = (resources.displayMetrics.widthPixels.coerceAtMost(resources.displayMetrics.heightPixels) * 0.45f).toInt()
        val spriteView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(fromGray)
        }
        val overlayView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(spriteView, FrameLayout.LayoutParams(spriteBoxPx, spriteBoxPx, Gravity.CENTER))
            addView(overlayView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(root)
            setCancelable(false)
        }
        dialog.show()
        try { sound?.start() } catch (e: Exception) {}

        // La duracion de la animacion sigue al sonido (igual que playEvolutionAnimation sigue a
        // evolution_theme), no un numero de pasos fijo - con los frames ya precalculados (ver
        // activateMegaWithAnimation) el reparto de tiempo ahora si se cumple de verdad, antes se
        // alargaba solo por el coste de generar cada frame en el momento.
        val soundDuration = sound?.duration?.toLong()?.coerceAtLeast(1200L) ?: 1800L
        val perFrame = (soundDuration / frames.size).coerceAtLeast(16L)

        val handler = android.os.Handler(mainLooper)
        var revealed = false
        fun revealAndFinish() {
            if (revealed) return
            revealed = true
            handler.removeCallbacksAndMessages(null)
            // Sin esto los 13 fotogramas de la bola de energia (hasta ~1024^2 cada uno) y el
            // gris intermedio se quedaban esperando al GC indefinidamente - no crasheaba (Android
            // los recicla solo tarde o temprano) pero era una fuga real en cada Mega/Fusion.
            frames.forEach { it.recycle() }
            fromGray.recycle()
            spriteView.setImageBitmap(toRaw)
            overlayView.setImageDrawable(null)
            try { sound?.stop(); sound?.release() } catch (e: Exception) {}
            try { cry?.start() } catch (e: Exception) {}
            val cryDuration = cry?.duration?.toLong()?.coerceAtLeast(300L) ?: 300L
            handler.postDelayed({
                try { cry?.release() } catch (e: Exception) {}
                dialog.dismiss()
                toRaw.recycle()
                onDone()
            }, cryDuration + 300L)
        }
        root.setOnClickListener { revealAndFinish() }

        var t = 0L
        for (bmp in frames) {
            handler.postDelayed({ overlayView.setImageBitmap(bmp) }, t)
            t += perFrame
        }
        handler.postDelayed({ revealAndFinish() }, t)
    }

    /** Igual que playMegaEvolveAnimation, pero para las Fusiones de verdad (Necrozma/Kyurem/
     *  Calyrex): en vez de UN sprite transformandose, se ven LOS DOS (el que fusiona y su
     *  ingrediente - Solgaleo/Lunala, Reshiram/Zekrom, Glastrier/Spectrier) deslizandose hacia el
     *  centro mientras dura el mismo efecto de energia, y al final desaparecen los dos para dejar
     *  solo el sprite ya fusionado - pedido explicito del usuario ("que aparezcan los dos sprites
     *  y se junten"). Mismo patron de "gris hasta revelarse a color" y de saltarsela con un tap. */
    private fun playFusionAnimation(fromName: String, partnerName: String, toName: String, shiny: Boolean, frames: List<Bitmap>, cry: MediaPlayer?, onDone: () -> Unit) {
        // Mismo fallo que en playMegaEvolveAnimation (ver su comentario): la carpeta de cache
        // depende del shiny de CADA sprite en concreto, no del Pokemon activo del widget. El
        // ingrediente (partnerName) ademas SIEMPRE se ensuring como shiny=false en
        // activateMegaWithAnimation (es un individuo aparte, nunca el shiny) - su clave tiene que
        // usar ese mismo false, no el de fromName/toName. [shiny] (el de fromName/toName) lo recibe
        // ya resuelto de activateMegaWithAnimation, en vez de volver a mirarlo aqui.
        val scaleMode = PetState.spriteScaleMode(this)
        val styleKey = SpriteRepository.styleKey(PetState.currentStyle(this), shiny) + "_" + scaleMode
        val partnerStyleKey = SpriteRepository.styleKey(PetState.currentStyle(this), false) + "_" + scaleMode
        val fromRaw = BitmapFactory.decodeFile(SpriteRepository.frameFile(this, styleKey, fromName, 0).path)
        val partnerRaw = BitmapFactory.decodeFile(SpriteRepository.frameFile(this, partnerStyleKey, partnerName, 0).path)
        val toRaw = BitmapFactory.decodeFile(SpriteRepository.frameFile(this, styleKey, toName, 0).path)
        if (fromRaw == null || partnerRaw == null || toRaw == null) {
            dbg("fusion-anim: sin fichero de sprite (fromRaw=${fromRaw != null} partnerRaw=${partnerRaw != null} toRaw=${toRaw != null}) para $fromName+$partnerName en $styleKey/$partnerStyleKey -> sin animacion")
            fromRaw?.recycle(); partnerRaw?.recycle(); toRaw?.recycle()
            frames.forEach { it.recycle() }
            cry?.release(); onDone(); return
        }
        val fromGray = SpriteRepository.toGrayscale(fromRaw)
        val partnerGray = SpriteRepository.toGrayscale(partnerRaw)
        fromRaw.recycle(); partnerRaw.recycle()   // ya convertidos a *Gray, no hace falta guardarlos aparte

        val sound = try { MediaPlayer.create(this, R.raw.sfx_mega_evolution) } catch (e: Exception) { null }

        // Cada sprite en su propia caja, mas pequeña que en la Megaevolucion normal (aqui hay DOS
        // a la vez en pantalla) - arrancan separados a los lados y se deslizan hacia el centro.
        val spriteBoxPx = (resources.displayMetrics.widthPixels.coerceAtMost(resources.displayMetrics.heightPixels) * 0.32f).toInt()
        val fromView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(fromGray) }
        val partnerView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(partnerGray) }
        val overlayView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(fromView, FrameLayout.LayoutParams(spriteBoxPx, spriteBoxPx, Gravity.CENTER))
            addView(partnerView, FrameLayout.LayoutParams(spriteBoxPx, spriteBoxPx, Gravity.CENTER))
            addView(overlayView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(root)
            setCancelable(false)
        }
        dialog.show()
        try { sound?.start() } catch (e: Exception) {}

        val soundDuration = sound?.duration?.toLong()?.coerceAtLeast(1200L) ?: 1800L
        val perFrame = (soundDuration / frames.size).coerceAtLeast(16L)

        val startOffsetPx = spriteBoxPx * 0.9f
        fromView.translationX = -startOffsetPx
        partnerView.translationX = startOffsetPx

        val handler = android.os.Handler(mainLooper)
        var revealed = false
        fun revealAndFinish() {
            if (revealed) return
            revealed = true
            handler.removeCallbacksAndMessages(null)
            // Misma fuga que en playMegaEvolveAnimation (ver su comentario), aqui con 2 grises en
            // vez de 1.
            frames.forEach { it.recycle() }
            fromGray.recycle()
            partnerGray.recycle()
            fromView.translationX = 0f
            fromView.setImageBitmap(toRaw)
            partnerView.setImageDrawable(null)
            partnerView.visibility = View.GONE
            overlayView.setImageDrawable(null)
            try { sound?.stop(); sound?.release() } catch (e: Exception) {}
            try { cry?.start() } catch (e: Exception) {}
            val cryDuration = cry?.duration?.toLong()?.coerceAtLeast(300L) ?: 300L
            handler.postDelayed({
                try { cry?.release() } catch (e: Exception) {}
                dialog.dismiss()
                toRaw.recycle()
                onDone()
            }, cryDuration + 300L)
        }
        root.setOnClickListener { revealAndFinish() }

        // Deslizamiento hacia el centro repartido en la MITAD del tiempo del sonido (ease-out: se
        // juntan rapido al principio) - por Handler, igual que los frames de la bola de energia,
        // no un animator Android aparte, asi un tap que salte a revealAndFinish deja todo
        // exactamente en su sitio sin animadores sueltos corriendo de fondo.
        val slideSteps = 24
        val slideStepMs = (soundDuration / 2 / slideSteps).coerceAtLeast(8L)
        for (i in 1..slideSteps) {
            val frac = i / slideSteps.toFloat()
            val eased = 1f - (1f - frac) * (1f - frac)
            handler.postDelayed({
                fromView.translationX = -startOffsetPx * (1f - eased)
                partnerView.translationX = startOffsetPx * (1f - eased)
            }, i * slideStepMs)
        }

        var t = 0L
        for (bmp in frames) {
            handler.postDelayed({ overlayView.setImageBitmap(bmp) }, t)
            t += perFrame
        }
        handler.postDelayed({ revealAndFinish() }, t)
    }

    /** Nivel de [mon] (null si no esta desbloqueado - no hay xp guardada que mirar). El xp NO
     *  decae con el tiempo (solo vida/higiene/felicidad), asi que rawStats vale igual para el
     *  Pokemon activo que para cualquier otro, sin necesitar loadWithDecay aqui.
     *  rawStats por defecto mira el individuo NORMAL - si solo tienes el shiny de esa especie
     *  (sin normal), eso devolvia null y "Ordenar por nivel" lo mandaba al final como si no
     *  tuviera nivel (bug real: cualquier especie que solo tengas en shiny). Se resuelve igual
     *  que DexAdapter.onBindViewHolder: el individuo que exista, o el ultimo visto si hay los dos. */
    private fun levelOf(mon: Mon): Int? {
        val hasShinyIndiv = PetState.hasIndividual(this, mon.name, shiny = true)
        val hasNormalIndiv = PetState.hasIndividual(this, mon.name, shiny = false)
        val shiny = when {
            hasNormalIndiv && hasShinyIndiv -> PetState.isShiny(this, mon.name)
            hasShinyIndiv -> true
            else -> false
        }
        return PetState.rawStats(this, mon.name, shiny)?.let { PetState.levelOf(this, it.xp, mon.name) }
    }

    /** ¿Alguno de los dos individuos (normal/shiny) de [name] esta marcado como favorito? Una
     *  especie con solo uno de los dos favorito ya cuenta para la pestaña Favoritos. Excluye la
     *  forma YA evolucionada-away: el favorito esta ligado a la LINEA (se hereda al evolucionar,
     *  ver evolveTo), asi que solo debe aparecer una vez en la pestaña - representado por la
     *  forma ACTUAL de esa linea, no por cada etapa antigua que tambien arrastra el flag. */
    private fun isFavoriteSpecies(name: String): Boolean =
        (PetState.hasIndividual(this, name, false) && !PetState.hasEvolvedAway(this, name, false) && PetState.isFavorite(this, name, false)) ||
            (PetState.hasIndividual(this, name, true) && !PetState.hasEvolvedAway(this, name, true) && PetState.isFavorite(this, name, true))

    /** ¿Tiene [name] al menos un individuo (normal/shiny) que siga siendo ESA forma ahora mismo
     *  (no evolucionado ya hacia otra cosa)? Pedido explicito del usuario: "quitar las
     *  evoluciones de la vista de mis pokemon" - antes "Mis Pokemon" enseñaba tambien cada
     *  etapa ya evolucionada (Treecko Y Grovyle Y Sceptile a la vez, aunque solo tengas el
     *  ultimo), mismo criterio ya usado en isFavoriteSpecies para representar la linea por su
     *  forma actual, no por cada etapa antigua. La Pokedex completa (sin filtrar) no se toca -
     *  ahi si tiene sentido ver el historial de toda la cadena. */
    private fun isCurrentlyOwnedSpecies(name: String): Boolean =
        (PetState.hasIndividual(this, name, false) && !PetState.hasEvolvedAway(this, name, false)) ||
            (PetState.hasIndividual(this, name, true) && !PetState.hasEvolvedAway(this, name, true))

    /** Recalcula [rows] segun el modo activo: "Mis Pokemon" (solo desbloqueados, todas las
     *  generaciones juntas), "Favoritos" (solo los marcados, ver isFavoriteSpecies) o "Pokedex
     *  completa" (solo [currentGen], como siempre); y segun [sortBy]: por numero de Pokedex, o
     *  por nivel (los bloqueados/sin nivel se van al final). Tambien oculta la fila de
     *  generaciones salvo en Pokedex completa (no aplica en los otros dos: los desbloqueados/
     *  favoritos normalmente son pocos, no hace falta filtrar mas). */
    private fun refreshRows() {
        var base = when (filterMode) {
            FILTER_MINE -> allMons.filter { isCurrentlyOwnedSpecies(it.name) }
            FILTER_FAVORITES -> allMons.filter { isFavoriteSpecies(it.name) }
            else -> if (currentGen == 0) allMons else allMons.filter { it.gen == currentGen }
        }
        val q = searchQuery.lowercase()
        if (q.isNotEmpty()) {
            base = base.filter { mon ->
                mon.name.replace("-", " ").contains(q) ||
                    PetState.displayLabel(mon.name).lowercase().contains(q) ||
                    mon.id.toString() == q.removePrefix("#")
            }
        }
        rows = when (sortBy) {
            "level" -> base.sortedWith(compareByDescending { levelOf(it) ?: -1 })
            // Sin fecha registrada (desbloqueado antes de que existiera este dato) -> -1L, que
            // con compareByDescending cae siempre al final, detras de cualquier fecha real.
            "date" -> base.sortedWith(compareByDescending { PetState.unlockedAt(this, it.name) ?: -1L })
            "name" -> base.sortedBy { PetState.displayLabel(it.name) }
            "shiny" -> base.sortedWith(
                compareByDescending<Mon> { PetState.hasIndividual(this, it.name, true) }.thenBy { it.id }
            )
            else -> base.sortedBy { it.id }
        }
        findViewById<View>(R.id.gen_row_scroll).visibility = if (filterMode == FILTER_ALL) View.VISIBLE else View.GONE
        // Contador de especies por numero de Pokedex (no de individuos - un normal+shiny de la
        // misma especie cuenta una sola vez), pedido explicito del usuario - reemplaza el "nombre
        // · Nivel" que antes vivia aqui (ver tvLevel/showLevel, ya no le toca ese texto). Se
        // recalcula con cualquier cambio de pestaña/genero/busqueda ya que todos pasan por
        // refreshRows(). El total SIEMPRE es el total real de especies del juego (todas las
        // posibilidades, pedido explicito), no el total de la pestaña/filtro activo - asi el
        // porcentaje es siempre el % real de la Pokedex completa, mires donde mires.
        val speciesCount = rows.map { it.id }.distinct().size
        val totalSpecies = allMons.map { it.id }.distinct().size
        val pct = if (totalSpecies > 0) speciesCount * 100f / totalSpecies else 0f
        tvLevel.text = "$speciesCount / $totalSpecies especies (${"%.1f".format(pct)}%)"
    }

    private fun setFilterMode(mode: String) {
        if (filterMode == mode) return
        filterMode = mode
        // Clear search when switching sections
        val searchView = findViewById<EditText>(R.id.search_dex)
        if (searchView.text.toString().isNotEmpty()) {
            searchView.setText("")
            searchQuery = ""
        }
        // Cerrar el buscador y el teclado al cambiar de pestaña (Mis Pokemon/Favoritos/Pokedex) -
        // pedido explicito del usuario: antes solo se vaciaba el texto, pero si se cambiaba de
        // pestaña justo mientras se escribia, el foco y el teclado se quedaban abiertos.
        searchView.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchView.windowToken, 0)
        refreshRows()
        updateFilterTabs()
        adapter.notifyDataSetChanged()
        if (mode == FILTER_MINE) findViewById<RecyclerView>(R.id.rv).post { scrollToActivePokemon() }
    }

    /** Lleva la vista hasta la celda del Pokemon activo ahora mismo (PetState.currentPokemon),
     *  SIN reordenar [rows] - solo tiene sentido en "Mis Pokemon" (en la Pokedex completa el
     *  activo puede no estar ni entre los desbloqueados de esa generacion). Silencioso si no se
     *  encuentra (p.ej. una busqueda activa lo esta filtrando fuera). [smooth] false para el
     *  salto instantaneo al abrir la app en frio; true (por defecto) para el cambio de pestaña,
     *  igual que el resto de saltos ya existentes en la pantalla (ver "Ver en Mis Pokémon"). */
    private fun scrollToActivePokemon(smooth: Boolean = true) {
        val idx = rows.indexOfFirst { it.name == PetState.currentPokemon(this) }
        if (idx < 0) return
        val rv = findViewById<RecyclerView>(R.id.rv)
        if (smooth) rv.smoothScrollToPosition(idx) else rv.scrollToPosition(idx)
    }

    /** Menu del boton "⇅ Ordenar" - mas opciones ademas de numero/nivel, pedido explicito del
     *  usuario ("como en Pokemon GO"): fecha de obtencion (recientes primero), alfabetico, y
     *  shiny primero (aprovechando la nueva coexistencia normal/shiny). */
    private fun showSortMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, "Número de Pokédex")
        popup.menu.add(0, 1, 1, "Nivel")
        popup.menu.add(0, 2, 2, "Fecha de obtención (recientes primero)")
        popup.menu.add(0, 3, 3, "Alfabético (A-Z)")
        popup.menu.add(0, 4, 4, "Shiny primero")
        popup.setOnMenuItemClickListener { item ->
            val newSort = when (item.itemId) {
                1 -> "level"; 2 -> "date"; 3 -> "name"; 4 -> "shiny"; else -> "id"
            }
            if (newSort != sortBy) {
                sortBy = newSort
                UiPrefs.putString(this, UiPrefs.MAIN_SORT, newSort)
                refreshRows()
                adapter.notifyDataSetChanged()
            }
            true
        }
        popup.show()
    }

    private fun updateFilterTabs() {
        val active = Color.parseColor("#EE1515"); val inactive = Color.parseColor("#DDDDDD")
        findViewById<TextView>(R.id.tab_mine).apply {
            setBackgroundColor(if (filterMode == FILTER_MINE) active else inactive)
            setTextColor(if (filterMode == FILTER_MINE) Color.WHITE else Color.BLACK)
        }
        findViewById<TextView>(R.id.tab_favorites).apply {
            setBackgroundColor(if (filterMode == FILTER_FAVORITES) active else inactive)
            setTextColor(if (filterMode == FILTER_FAVORITES) Color.WHITE else Color.BLACK)
        }
        findViewById<TextView>(R.id.tab_all).apply {
            setBackgroundColor(if (filterMode == FILTER_ALL) active else inactive)
            setTextColor(if (filterMode == FILTER_ALL) Color.WHITE else Color.BLACK)
        }
    }

    /** Fila superior con un boton por generacion (1..maxGen) mas uno "Todas" (currentGen=0, para
     *  poder buscar por nombre sin estar atado a una generacion concreta); resalta la
     *  seleccionada. */
    private fun buildGenRow() {
        val row = findViewById<LinearLayout>(R.id.gen_row)
        row.removeAllViews()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        row.addView(TextView(this).apply {
            text = "Todas"
            textSize = 14f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setBackgroundColor(if (currentGen == 0) Color.parseColor("#EE1515") else Color.parseColor("#DDDDDD"))
            setTextColor(if (currentGen == 0) Color.WHITE else Color.BLACK)
            setOnClickListener {
                if (currentGen != 0) {
                    currentGen = 0
                    refreshRows()
                    adapter.notifyDataSetChanged()
                    buildGenRow()
                }
            }
        })
        for (g in 1..maxGen) {
            val tv = TextView(this).apply {
                text = "$g"
                textSize = 14f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setBackgroundColor(if (g == currentGen) Color.parseColor("#EE1515") else Color.parseColor("#DDDDDD"))
                setTextColor(if (g == currentGen) Color.WHITE else Color.BLACK)
                setOnClickListener {
                    if (currentGen != g) {
                        currentGen = g
                        refreshRows()
                        adapter.notifyDataSetChanged()
                        buildGenRow()
                    }
                }
            }
            row.addView(tv)
        }
    }

    // ---------------- Ajustes (menu ⋮): estilo + shiny, para pruebas ----------------
    private fun showSettingsDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // --- Fondos: se movio a la ficha de entrenador (showBackgroundPickerDialog), pedido
        //     explicito del usuario - ya no vive aqui. ---

        // --- Nitidez del sprite (prueba: vecino cercano vs Scale2x, ver conversacion) - al
        //     fondo, pedido explicito del usuario, justo encima del Debug. ---
        root.addView(TextView(this).apply {
            text = "Nitidez del sprite (prueba)"; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Cambia el sprite del Pokémon activo al momento, animación incluida - para probar cuál se ve mejor."
            textSize = 11.5f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(6))
        })
        val scaleGroup = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val scaleOptions = listOf("none" to "Normal (como siempre)", "partial" to "Suavizado parcial", "full" to "Suavizado completo")
        val currentScaleMode = PetState.spriteScaleMode(this)
        val radioButtons = scaleOptions.map { (mode, label) ->
            android.widget.RadioButton(this).apply {
                text = label
                isChecked = mode == currentScaleMode
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        PetState.setSpriteScaleMode(this@MainActivity, mode)
                        WidgetRefresh.updateWidgets(this@MainActivity)
                    }
                }
            }
        }
        radioButtons.forEach { scaleGroup.addView(it) }
        root.addView(scaleGroup)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(24)) })

        // Mazmorra: ya NO vive aqui - movida a un icono propio en la cabecera (junto al regalo),
        // pedido explicito del usuario: "no me gusta la ubicacion de la mazmorra. me gustaria que
        // puedas entrar al menu de una forma mas organica" (enterrada en Ajustes junto a opciones
        // de desarrollo no encajaba con lo importante que es esta pantalla). Ver btn_dungeon en
        // activity_main.xml y su listener en onCreate.

        // --- Ver log: fuera del menu debug (pedido explicito del usuario) - no hace falta
        //     contraseña para esto, es solo diagnostico de lectura, no edita nada del juego. ---
        root.addView(Button(this).apply {
            text = "📋 Ver log"; isAllCaps = false
            setOnClickListener { showDebugLogDialog() }
        })
        // --- Log de mazmorra: misma idea, filtrado a solo lo relevante para diagnosticar el
        //     segundo plano (pedido explicito del usuario: "logs solo de la dungeon... para
        //     diagnosticar si no esta funcionando en segundo plano"). ---
        root.addView(Button(this).apply {
            text = "🗺️ Log de mazmorra"; isAllCaps = false
            setOnClickListener { showDungeonLogDialog() }
        })
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(8)) })

        // --- Debug (con contraseña, al fondo del todo - pedido explicito del usuario: boton
        //     igual que el resto del juego (no un texto discreto), herramientas de prueba que
        //     el propio usuario pueda abrir sin tener que pedirmelo a mi cada vez). ---
        root.addView(Button(this).apply {
            text = "🔧 Debug"; isAllCaps = false
            setOnClickListener { promptDebugPassword() }
        })

        val scroll = ScrollView(this).apply { addView(root) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // ==================== FICHA DE ENTRENADOR ====================
    // Retrato + nickname (editables "cuando sea" via TrainerSetupActivity en modo edicion),
    // nivel de entrenador, cuantas especies tiene registradas, y un Pokemon favorito destacado
    // (elegido de entre los ya marcados favoritos, con su variante/forma exacta) - pedido
    // explicito del usuario. Se abre tocando tv_trainer_level (ver listener en onCreate).

    /** Cuantas especies DISTINTAS tiene el jugador desbloqueadas ahora mismo, sobre el total real
     *  de la Pokedex - a diferencia del contador de refreshRows() (que depende de la pestaña/
     *  busqueda activa), este es siempre el mismo numero fijo ("Mis Pokemon" sin filtrar),
     *  pensado para la tarjeta de entrenador, no para la pantalla principal. Triple(poseidas,
     *  total, progreso 0f..1f) para poder rellenar tanto el texto como la barra sin repetir el
     *  calculo. */
    private fun ownedSpeciesStats(): Triple<Int, Int, Float> {
        val owned = allMons.filter { PetState.isUnlocked(this, it.name) }.map { it.id }.distinct().size
        val total = allMons.map { it.id }.distinct().size
        return Triple(owned, total, if (total > 0) owned.toFloat() / total else 0f)
    }

    private fun ownedSpeciesCountText(): String {
        val (owned, total, progress) = ownedSpeciesStats()
        return "$owned / $total especies (${"%.1f".format(progress * 100)}%)"
    }

    /** Todas las formas YA vistas de verdad (obtained=true) de las 3 vitrinas de mecanica de
     *  [name] (genero, formas, mega/gigantamax - ver PetState.speciesMechanics), fusionadas en
     *  una sola lista plana: cada MechanicForm ya trae su propia etiqueta descriptiva, no hace
     *  falta agrupar por categoria para un selector de "elige una". */
    private fun obtainedShowcaseForms(name: String): List<PetState.MechanicForm> {
        val m = PetState.speciesMechanics(this, name)
        return (m.genderShowcase?.forms.orEmpty() + m.formShowcase?.forms.orEmpty() + m.megaShowcase?.forms.orEmpty())
            .filter { it.obtained }
    }

    /** Tarjeta de entrenador "de verdad" (pedido explicito del usuario: "como en los juegos
     *  originales") - nombre, nivel CON barra justo debajo, un escenario con el fondo REAL del
     *  tipo del favorito (ver PetState.suggestedBackgrounds) mostrando al entrenador y al
     *  favorito uno junto al otro a tamaño fiel, contador de Pokedex CON barra, y el selector de
     *  fondos (antes en el menu ⋮, movido aqui a peticion explicita del usuario, dejando claro
     *  cuantos hay por desbloquear). Toda la logica de datos es la misma de siempre, solo cambia
     *  de "filas de texto en un AlertDialog" a "vistas ya maquetadas en XML". */
    private fun showTrainerCardDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_trainer_card, null)
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        view.findViewById<TextView>(R.id.card_name).text = PetState.trainerName(this)
        view.findViewById<TextView>(R.id.card_level).text = "Entrenador Nv.${PetState.trainerLevel(this)}"
        view.findViewById<ProgressBar>(R.id.card_level_bar).progress = (PetState.trainerProgress(this) * 1000).toInt()
        view.findViewById<TextView>(R.id.card_dex_count).text = ownedSpeciesCountText()
        view.findViewById<ProgressBar>(R.id.card_dex_bar).progress = (ownedSpeciesStats().third * 1000).toInt()

        val portrait = view.findViewById<ImageView>(R.id.card_trainer_portrait)
        portrait.setImageBitmap(TrainerSprites.bitmap(this, PetState.trainerSprite(this)))
        portrait.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, TrainerSetupActivity::class.java).putExtra(TrainerSetupActivity.EXTRA_EDIT_MODE, true))
        }

        val favImg = view.findViewById<ImageView>(R.id.card_favorite_pokemon)
        val favHint = view.findViewById<TextView>(R.id.card_favorite_hint)
        val fav = PetState.showcaseFavorite(this)
        // Escenario: fondo real a juego con el tipo del favorito (mismo sistema que ya usan los
        // fondos comprables, ver PetState.suggestedBackgrounds/BG_FOR_TYPE) - si no hay favorito
        // elegido, se usa el fondo actual del compañero activo como alternativa razonable.
        val stageBg = fav?.let { (name, shiny, _) -> PetState.suggestedBackgrounds(this, name, shiny).firstOrNull() }
            ?: PetState.currentBg(this)
        val stageBgRes = resources.getIdentifier(stageBg, "drawable", packageName)
        if (stageBgRes != 0) view.findViewById<ImageView>(R.id.card_stage_bg).setImageResource(stageBgRes)
        if (fav != null) {
            val (favName, favShiny, favForm) = fav
            val spriteName = favForm.ifEmpty { favName }
            favHint.visibility = View.GONE
            // Tamaño fiel al sprite real (pedido explicito del usuario: un Sceptile no puede
            // verse la mitad de pequeño que el entrenador) - ver
            // SpriteRepository.localCanvasHeight. 1.85 calibrado a mano para que Charizard
            // (h=91) roce el tope de 170dp sin superarlo; especies aun mas grandes (Wailord,
            // h=103) se recortan ahi, lo demas escala hacia abajo proporcionalmente.
            val canvasH = SpriteRepository.localCanvasHeight(this, spriteName, favShiny) ?: 70
            val targetDp = (canvasH * 1.85f).coerceIn(60f, 170f)
            favImg.layoutParams = favImg.layoutParams.apply {
                height = (targetDp * resources.displayMetrics.density).toInt()
            }
            // Animado (pedido explicito del usuario) - mismo helper ya usado para la revelacion
            // del huevo/oferta, local-first con red como respaldo. Intervalo mas lento (180ms,
            // el mismo ritmo del idle del widget) - el de 100ms de siempre se quedaba "muy
            // rapido" en bucle continuo (pedido explicito del usuario).
            loadAnimatedSprite(favImg, spriteName, favShiny, intervalOverrideMs = 180)
        } else {
            favImg.setImageBitmap(null)
            favHint.visibility = View.VISIBLE
        }
        favImg.setOnClickListener { dialog.dismiss(); showFavoriteShowcasePickerDialog() }
        favHint.setOnClickListener { dialog.dismiss(); showFavoriteShowcasePickerDialog() }

        // El selector de fondos vive aqui ahora (antes en el menu ⋮, pedido explicito del
        // usuario) - el texto deja claro cuantos hay por desbloquear ahora mismo.
        val tokens = PetState.bgTokensAvailable(this)
        view.findViewById<Button>(R.id.card_bg_shortcut).apply {
            text = if (tokens > 0) "🖼️ Fondos (🎁 $tokens por desbloquear)" else "🖼️ Fondos"
            setOnClickListener { dialog.dismiss(); showBackgroundPickerDialog() }
        }

        val foundItems = DungeonState.discoveredItems(this).size
        view.findViewById<Button>(R.id.card_items_shortcut).apply {
            text = "🎒 Objetos ($foundItems/${DungeonItemCatalog.DECOR_ITEMS.size})"
            setOnClickListener { dialog.dismiss(); showItemsPokedexDialog() }
        }

        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("📤 Compartir", null)
            .create()
        dialog.show()
        // Sobreescrito DESPUES de show() para que el boton NO cierre el dialogo solo (el
        // comportamiento por defecto de AlertDialog.Builder.setNeutralButton siempre cierra al
        // pulsar) - pedido explicito del usuario: generar y compartir una imagen de la ficha.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            shareTrainerCardImage(view)
        }
    }

    /** Genera una imagen PNG de la ficha de entrenador TAL CUAL se ve ahora mismo, quitando solo
     *  lo que no tiene sentido en una imagen estatica para compartir (pedido explicito del
     *  usuario: "quitando el boton de cerrar, los fondos y el lapiz de editar") - el boton
     *  "Cerrar"/"Compartir" ya viven fuera de [dialogView] (son botones del propio AlertDialog,
     *  nunca se capturan); aqui solo hace falta ocultar temporalmente el lapiz de editar retrato
     *  y el atajo de fondos, dibujar el contenido a un Bitmap, y devolverlos a como estaban. */
    private fun shareTrainerCardImage(dialogView: View) {
        val contentRoot = dialogView.findViewById<View>(R.id.card_content_root)
        val pencil = dialogView.findViewById<View>(R.id.card_pencil_icon)
        val bgButton = dialogView.findViewById<View>(R.id.card_bg_shortcut)
        val prevPencilVis = pencil.visibility
        val prevBgVis = bgButton.visibility
        pencil.visibility = View.GONE
        bgButton.visibility = View.GONE
        // Igual que en TrainerSetupActivity.animateBallThrow: cambiar a GONE solo deja el tamaño
        // final REAL disponible despues de la siguiente pasada de layout, no en este mismo
        // instante - post() la espera.
        contentRoot.post {
            try {
                val w = contentRoot.width; val h = contentRoot.height
                if (w <= 0 || h <= 0) throw IllegalStateException("vista sin medir")
                // Fondo solido detras del contenido (el LinearLayout no pinta ninguno propio, se
                // apoya en el fondo del dialogo) - se elige claro u oscuro segun el color de
                // texto YA resuelto por el tema en tiempo real, para que el resultado sea
                // legible tanto en tema claro como oscuro sin tener que adivinarlo a mano.
                val textColor = dialogView.findViewById<TextView>(R.id.card_name).currentTextColor
                val luminance = (Color.red(textColor) * 299 + Color.green(textColor) * 587 + Color.blue(textColor) * 114) / 1000
                val cardBg = if (luminance > 128) Color.parseColor("#1B1B1B") else Color.WHITE
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawColor(cardBg)
                contentRoot.draw(canvas)
                val file = java.io.File(filesDir, "trainer_card_share.png")
                java.io.FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Compartir ficha de entrenador"))
            } catch (e: Exception) {
                dbg("compartir ficha fallo: $e")
                Toast.makeText(this, "No se pudo compartir: $e", Toast.LENGTH_LONG).show()
            } finally {
                pencil.visibility = prevPencilVis
                bgButton.visibility = prevBgVis
            }
        }
    }

    /** Pokedex de objetos decorativos de la mazmorra - pedido explicito del usuario: "quiero que
     *  los objetos decorativos se puedan coleccionar... que incluya todos los objetos de la
     *  mazmorra... que en la pokedex aparezca la probabilidad de salir. asi se puede ver si te ha
     *  tocado un item raro". Rejilla de los 129 objetos del catalogo (DungeonItemCatalog),
     *  ordenados por categoria (Comun->Epico) y luego alfabeticamente; los ya encontrados
     *  (DungeonState.discoveredItems) muestran su icono real, nombre y % de probabilidad de
     *  categoria - los que no, una silueta "❓" y "???" (mismo espiritu que una Pokedex real: se
     *  sabe QUE existe y de que rareza es, pero no el aspecto hasta encontrarlo). Solo cubre el
     *  rol "decor" (129 de 308 objetos catalogados) - el resto de roles ya tienen su propio efecto
     *  mecanico y su propio texto al recogerlos, no necesitan coleccion aparte (ver comentario de
     *  DungeonItemCatalog). */
    private fun showItemsPokedexDialog() {
        val discovered = DungeonState.discoveredItems(this)
        val rarityOrder = DungeonItemCatalog.ItemRarity.values().toList()
        val entries = DungeonItemCatalog.DECOR_ITEMS.sortedWith(
            compareBy({ rarityOrder.indexOf(it.rarity) }, { it.displayName })
        )
        val tierCounts = DungeonItemCatalog.ItemRarity.values().associateWith { tier ->
            val total = DungeonItemCatalog.DECOR_ITEMS.count { it.rarity == tier }
            val found = DungeonItemCatalog.DECOR_ITEMS.count { it.rarity == tier && it.fileName in discovered }
            found to total
        }

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        root.addView(TextView(this).apply {
            text = DungeonItemCatalog.ItemRarity.values().joinToString("   ") { tier ->
                val (found, total) = tierCounts.getValue(tier)
                "${tier.label} $found/$total"
            }
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })

        val rv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            layoutManager = GridLayoutManager(this@MainActivity, 4)
        }
        rv.adapter = object : RecyclerView.Adapter<DungeonItemVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DungeonItemVH =
                DungeonItemVH(LayoutInflater.from(parent.context).inflate(R.layout.item_dungeon_item, parent, false))
            override fun getItemCount() = entries.size
            override fun onBindViewHolder(holder: DungeonItemVH, position: Int) {
                val info = entries[position]
                val found = info.fileName in discovered
                val pct = DungeonItemCatalog.dropChancePercent(info)
                // El icono SIEMPRE es el real (ya viene bundleado en la app, no hay nada que
                // "descargar" ni spoilear con la forma/color) - lo que cambia es si se ve a todo
                // color o en gris, pedido explicito del usuario: "que aparezca el sprite del item
                // en gris en vez de un interrogante". El nombre real se sigue ocultando hasta
                // encontrarlo (misma logica que una Pokedex real con silueta).
                holder.thumb.setImageBitmap(DungeonSpriteRepository.itemIcon(this@MainActivity, info.fileName))
                holder.thumb.colorFilter = if (found) null else GRAYSCALE_FILTER
                holder.name.text = if (found) info.displayName else "???"
                holder.rarity.text = "${info.rarity.label} · ${"%.2f".format(pct)}%"
                holder.rarity.setTextColor(Color.parseColor(info.rarity.colorHex))
                holder.root.setOnClickListener {
                    val msg = if (found) "${info.displayName} · ${info.rarity.label} (${"%.2f".format(pct)}% de probabilidad)"
                        else "Todavía no lo has encontrado · ${info.rarity.label} (${"%.2f".format(pct)}% de probabilidad)"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(rv)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Objetos de la mazmorra (${discovered.size}/${entries.size})")
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .create()
            .show()
    }

    private inner class DungeonItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.item_root)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val rarity: TextView = v.findViewById(R.id.rarity)
    }

    private companion object {
        // Satura a 0 - icono real en gris (sin recolorear a mano) para un objeto de la mazmorra
        // aun no encontrado, ver DungeonItemVH.onBindViewHolder.
        val GRAYSCALE_FILTER = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix().apply { setSaturation(0f) }
        )
    }

    /** Selector de fondos - antes vivia dentro del menu ⋮ (showSettingsDialog), movido a su
     *  propio dialogo pedido explicito del usuario ("que ahora este ahi la seleccion de fondos y
     *  no en los tres puntitos"), abierto desde el boton de la ficha de entrenador. Misma rejilla
     *  de siempre (buildBgGrid), sin cambios en su logica. */
    private fun showBackgroundPickerDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        buildBgGrid(grid)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Fondos")
            .setView(ScrollView(this).apply { addView(grid) })
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** Paso 1 del selector de favorito destacado: elegir de entre los que YA cuentan como
     *  favoritos de verdad (PetState.favoriteIndividuals) - mismo molde de rejilla en chunked(3)
     *  que showDecorativeFormDialog. */
    private fun showFavoriteShowcasePickerDialog() {
        val candidates = PetState.favoriteIndividuals(this)
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Marca algún Pokémon como favorito primero (pestaña Favoritos)", Toast.LENGTH_LONG).show()
            return
        }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elige tu favorito destacado")
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton("Cancelar", null)
            .create()
        for (row in candidates.chunked(3)) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for ((name, shiny) in row) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                }
                val img = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                cell.addView(img)
                loadLocalOrNetworkThumb(img, PetState.displaySpriteName(this, name, shiny), shiny) {
                    img.load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(name)}.png")
                }
                cell.addView(TextView(this).apply {
                    text = PetState.displayLabel(name) + if (shiny) " ✨" else ""
                    textSize = 11f
                    gravity = Gravity.CENTER
                })
                cell.setOnClickListener {
                    dialog.dismiss()
                    val forms = obtainedShowcaseForms(name)
                    if (forms.size > 1) showFavoriteFormPickerDialog(name, shiny, forms)
                    else {
                        PetState.setShowcaseFavorite(this, name, shiny, "")
                        showTrainerCardDialog()
                    }
                }
                rowLayout.addView(cell)
            }
            grid.addView(rowLayout)
        }
        dialog.show()
    }

    /** Paso 2 (solo si hay mas de una forma YA vista de [name]): elegir la forma exacta a
     *  mostrar en la ficha - mismo molde que showFavoriteShowcasePickerDialog. */
    private fun showFavoriteFormPickerDialog(name: String, shiny: Boolean, forms: List<PetState.MechanicForm>) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elige la forma")
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton("Cancelar", null)
            .create()
        for (row in forms.chunked(3)) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (form in row) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                }
                val img = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                cell.addView(img)
                loadLocalOrNetworkThumb(img, form.spriteKey, shiny) {
                    img.load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(form.spriteKey)}.png")
                }
                cell.addView(TextView(this).apply {
                    text = form.label
                    textSize = 11f
                    gravity = Gravity.CENTER
                })
                cell.setOnClickListener {
                    dialog.dismiss()
                    PetState.setShowcaseFavorite(this, name, shiny, form.spriteKey)
                    showTrainerCardDialog()
                }
                rowLayout.addView(cell)
            }
            grid.addView(rowLayout)
        }
        dialog.show()
    }

    // ==================== MENU DEBUG (protegido por contraseña) ====================
    // Consolida aqui TODO lo que antes solo se podia hacer via adb (log persistente, forzar
    // fase del huevo, saltar horas, editar/anadir/borrar Pokemon a mano) - pedido explicito del
    // usuario para no depender de mi para probar cosas. La contrasena es una comprobacion local
    // simple (no hay nada que proteger de verdad, es un telefono personal) - solo evita que
    // alguien que coja el telefono y toque "Ajustes" se tropiece con esto sin querer.
    private val DEBUG_PASSWORD = "27021996"
    private var debugMenuDialog: androidx.appcompat.app.AlertDialog? = null

    private fun promptDebugPassword() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Contraseña"
        }
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val frame = FrameLayout(this).apply { setPadding(pad, pad, pad, 0) }
        frame.addView(input)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Debug")
            .setView(frame)
            .setPositiveButton("Entrar") { _, _ ->
                if (input.text.toString() == DEBUG_PASSWORD) showDebugMenu()
                else Toast.makeText(this, "Contraseña incorrecta", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDebugMenu() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val items = listOf(
            "🌙 Ventana de noche" to { showDebugSleepWindowDialog() },
            "⏱️ Simular paso del tiempo" to { showDebugTimeSkipDialog() },
            "🥚 Forzar fase del huevo" to { showDebugEggStageDialog() },
            "✏️ Editar / añadir Pokémon" to { showDebugPokemonEditorDialog() },
            "📊 Simulador de mazmorra" to { showDungeonBalanceSimulatorDialog() },
            "🎓 Tutorial completo (sin guardar nada)" to { startActivity(Intent(this, TrainerSetupActivity::class.java).putExtra(TrainerSetupActivity.EXTRA_DEBUG_SANDBOX, true)) },
            "🎓 Solo los pasos del tutorial" to { startActivity(Intent(this, TrainerSetupActivity::class.java).putExtra(TrainerSetupActivity.EXTRA_DEBUG_SANDBOX, true).putExtra(TrainerSetupActivity.EXTRA_DEBUG_PREVIEW_STEP, 0)) },
            "💡 Consejos de primera vez" to { showDebugTipsDialog() }
        )
        items.forEach { (label, action) ->
            root.addView(Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener { action() }
            })
        }
        debugMenuDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Menú debug")
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** Vistas de esta pantalla que resaltan las pistas sobre la interfaz (ver TutorialTips.stepsFor); null = sin hueco. */
    private fun tipTarget(id: String): View? = when (id) {
        "tab_mine" -> findViewById(R.id.tab_mine)
        "tab_favorites" -> findViewById(R.id.tab_favorites)
        "tab_all" -> findViewById(R.id.tab_all)
        "btn_sort" -> findViewById(R.id.btn_sort)
        "tv_trainer_level" -> findViewById(R.id.tv_trainer_level)
        "btn_dungeon" -> findViewById(R.id.btn_dungeon)
        "btn_bag" -> findViewById(R.id.btn_bag)
        "btn_offer" -> findViewById(R.id.btn_offer)
        else -> null
    }

    /** Enseña [tip] a la fuerza desde el menu Debug, en la pantalla a la que pertenece (la mazmorra y la guarderia abren la suya). */
    private fun showTipFromDebug(tip: TutorialTips.Tip, step: Int = 0) {
        when (tip) {
            TutorialTips.Tip.DUNGEON, TutorialTips.Tip.COMPANIONS -> startActivity(
                Intent(this, DungeonActivity::class.java).putExtra(DungeonActivity.EXTRA_FORCE_TIP, tip.key).putExtra(DungeonActivity.EXTRA_TIP_STEP, step)
            )
            TutorialTips.Tip.NURSERY -> showNurseryDialog(forceTip = true)
            TutorialTips.Tip.GIFT_PICK -> showOfferDialog(debugDemoOffer(), forceTip = true)
            else -> window.decorView.postDelayed({ TutorialTips.show(this, tip, force = true, resolve = ::tipTarget) }, 250L)
        }
    }

    /** SOLO PRUEBAS: vuelca [view] (con todo lo que tenga dibujado, p. ej. una pista) a filesDir/[name]. */
    private fun dumpViewToFile(view: View, name: String) {
        try {
            val b = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(b).apply { drawColor(Color.parseColor("#303030")); view.draw(this) }
            java.io.File(filesDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
            dbg("vista volcada a $name")
        } catch (e: Exception) { dbg("volcado de $name fallo: $e") }
    }

    /** Menu Debug: lista de los consejos de primera vez (si cada uno ya se ha visto), para verlos uno a uno o dejarlos todos
     *  sin ver otra vez (se vuelven a enseñar la proxima vez que toque). */
    private fun showDebugTipsDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        lateinit var dlg: androidx.appcompat.app.AlertDialog
        TutorialTips.Tip.values().forEach { tip ->
            root.addView(Button(this).apply {
                text = "${tip.emoji} ${tip.title}  ·  " + if (TutorialTips.seen(this@MainActivity, tip)) "visto" else "sin ver"
                isAllCaps = false
                setOnClickListener { dlg.dismiss(); debugMenuDialog?.dismiss(); showTipFromDebug(tip) }
            })
        }
        root.addView(Button(this).apply {
            text = "↺ Dejar todos sin ver"
            isAllCaps = false
            setOnClickListener {
                TutorialTips.resetAll(this@MainActivity)
                Toast.makeText(this@MainActivity, "Consejos restablecidos: saldrán de nuevo cuando toque", Toast.LENGTH_LONG).show()
                dlg.dismiss()
            }
        })
        dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Consejos de primera vez")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Cerrar", null)
            .create()
        dlg.show()
    }

    /** Simulador de balance de la mazmorra - pedido explicito del usuario: "añadir algun tipo de
     *  log a las mazmorras para poder tirar varias veces un Pokemon, dejarlo ahi farmeando, y ver
     *  estadisticas... la probabilidad de criticos, el nivel que va a obtener de media, los items
     *  que aparecen, los enemigos que aparecen... para validar que esta todo bien y ajustar
     *  parametros". Corre N carreras COMPLETAS AISLADAS (DungeonSimulator.runBalanceSimulation,
     *  nunca toca la carrera real en curso) en un hilo aparte y muestra un informe agregado -
     *  tambien se vuelca al mismo log que "Ver log" (DebugLog), para poder compartirlo igual que
     *  cualquier otro registro de depuracion. */
    private fun showDungeonBalanceSimulatorDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }
        val speciesInput = EditText(this).apply {
            hint = "Especie (ej. sceptile)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val shinyCheck = CheckBox(this).apply { text = "Shiny" }
        val attemptsInput = EditText(this).apply {
            hint = "Nº de intentos"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("200")
        }
        root.addView(TextView(this).apply {
            text = "Corre esa cantidad de carreras completas (piso 1 hasta morir) de forma " +
                "aislada - no toca ninguna carrera real que este en curso a la vez, ni el " +
                "Pokemon de verdad."
            textSize = 12.5f
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(speciesInput)
        root.addView(shinyCheck)
        root.addView(attemptsInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📊 Simulador de mazmorra")
            .setView(root)
            .setPositiveButton("Simular") { _, _ ->
                val species = speciesInput.text.toString().trim().lowercase()
                val attempts = attemptsInput.text.toString().toIntOrNull()?.coerceIn(1, 2000) ?: 200
                if (species.isEmpty()) {
                    Toast.makeText(this, "Escribe una especie", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val shiny = shinyCheck.isChecked
                val progressDlg = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Simulando…")
                    .setMessage("$attempts carreras de ${PetState.displayLabel(species)}, puede tardar unos segundos.")
                    .setCancelable(false)
                    .show()
                Thread {
                    val startXp = PetState.rawStats(this, species, shiny)?.xp ?: 0f
                    val report = DungeonSimulator.runBalanceSimulation(this, species, shiny, startXp, attempts)
                    val text = DungeonSimulator.formatBalanceReport(report)
                    dbg("Simulador de mazmorra:\n$text")
                    runOnUiThread {
                        progressDlg.dismiss()
                        showBalanceReportDialog(text)
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showBalanceReportDialog(text: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val scroll = android.widget.ScrollView(this)
        scroll.addView(TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Resultado de la simulación")
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showDebugSleepWindowDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        root.addView(TextView(this).apply {
            text = PetState.sleepWindowLabel(this@MainActivity)
            textSize = 15f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Empieza en 22:00–08:00 y se ajusta sola según cuándo usas de verdad el widget/la app (nunca notifica de más durante esta franja)."
            textSize = 11.5f
            setTextColor(Color.parseColor("#888888"))
        })
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ventana de noche actual")
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showDebugLogDialog() = showFilteredLogDialog("Log (últimas 300 líneas)") { true }

    /** Log de mazmorra (pedido explicito del usuario) - misma vista de siempre, filtrada a solo
     *  las lineas con el prefijo "mazmorra:" ya usado en todo DungeonService/DungeonSimulator/
     *  DungeonState. Filtra el archivo COMPLETO primero y de ahi se queda con las ultimas 300
     *  coincidencias - filtrar solo dentro de las ultimas 300 lineas crudas dejaria casi vacio el
     *  resultado si hubo mucha actividad de otros sistemas (huevo, ofertas...) entremedias. */
    private fun showDungeonLogDialog() = showFilteredLogDialog("Log de la mazmorra") { it.contains("mazmorra:") }

    private fun showFilteredLogDialog(title: String, filter: (String) -> Boolean) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val logText = try {
            val lines = DebugLog.file(this).readLines().filter(filter)
            if (lines.isEmpty()) "(sin líneas todavía)" else lines.takeLast(300).joinToString("\n")
        } catch (e: Exception) { "(sin log todavía)" }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Button(this).apply {
            text = "📤 Compartir (log completo)"; isAllCaps = false
            setOnClickListener {
                // A diferencia del texto de arriba (solo las ultimas 300 lineas, para poder
                // leerlo aqui sin que el dialogo se vuelva enorme), compartir manda el ARCHIVO
                // entero como adjunto - pedido explicito del usuario: "que este todo el log en
                // un documento", no solo un trozo pegado como texto. Hace falta un content://
                // Uri via FileProvider (ver AndroidManifest.xml/res/xml/file_paths.xml) - un
                // file:// directo ya no se puede compartir entre apps desde hace varias
                // versiones de Android.
                try {
                    val file = DebugLog.file(this@MainActivity)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", file
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "PokeGotchi - log completo")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Compartir log"))
                } catch (e: Exception) {
                    dbg("compartir log fallo: $e")
                    Toast.makeText(this@MainActivity, "No se pudo compartir: $e", Toast.LENGTH_LONG).show()
                }
            }
        })
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = logText
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
        })
        root.addView(scroll)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar TODO el log") { _, _ ->
                // Un solo archivo compartido por toda la app (ver DebugLog) - borra tambien lo
                // que no se ve en esta vista filtrada, no solo las lineas de mazmorra.
                try { DebugLog.file(this).delete() } catch (e: Exception) {}
                Toast.makeText(this, "Log borrado", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDebugTimeSkipDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Horas a avanzar"
        }
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val frame = FrameLayout(this).apply { setPadding(pad, pad, pad, 0) }
        frame.addView(input)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Simular paso del tiempo")
            .setMessage("Adelanta el reloj del Pokémon activo (y el del huevo si hay uno incubando) sin esperar de verdad.")
            .setView(frame)
            .setPositiveButton("Aplicar") { _, _ ->
                val hours = input.text.toString().toFloatOrNull()
                if (hours == null || hours <= 0f) {
                    Toast.makeText(this, "Pon un número de horas mayor que 0", Toast.LENGTH_SHORT).show()
                } else {
                    dbg("debug: saltando $hours horas")
                    PetState.debugSkipHours(this, hours)
                    WidgetRefresh.updateWidgets(this)
                    Toast.makeText(this, "$hours horas simuladas", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDebugEggStageDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        root.addView(TextView(this).apply {
            text = if (PetState.eggStageOverrideActive(this@MainActivity))
                "Forzada ahora mismo. Fases: 0 a ${PetState.EGG_CRACK_STAGES - 1}."
            else "Sin forzar (usando la fase real). Fases: 0 a ${PetState.EGG_CRACK_STAGES - 1}."
            textSize = 12.5f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(8))
        })
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Fase (0-${PetState.EGG_CRACK_STAGES - 1})"
        }
        root.addView(input)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Forzar fase del huevo")
            .setView(root)
            .setPositiveButton("Aplicar") { _, _ ->
                val stage = input.text.toString().toIntOrNull()
                if (stage == null || stage !in 0 until PetState.EGG_CRACK_STAGES) {
                    Toast.makeText(this, "Fase entre 0 y ${PetState.EGG_CRACK_STAGES - 1}", Toast.LENGTH_SHORT).show()
                } else {
                    PetState.debugSetEggStage(this, stage)
                    WidgetRefresh.updateWidgets(this)
                    Toast.makeText(this, "Fase $stage forzada", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Quitar override") { _, _ ->
                PetState.debugClearEggStageOverride(this)
                WidgetRefresh.updateWidgets(this)
                Toast.makeText(this, "Override quitado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDebugPokemonEditorDialog() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Button(this).apply {
            text = "📋 Ver mis Pokémon"; isAllCaps = false
            setOnClickListener { showDebugOwnedGridDialog() }
        })
        root.addView(Button(this).apply {
            text = "➕ Añadir Pokémon"; isAllCaps = false
            setOnClickListener { showDebugAddGridDialog() }
        })
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Editar / añadir Pokémon")
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** Rellena una celda de rejilla (mismo layout item_pokemon.xml de la Pokedex real) para
     *  [mon]/[shiny] - version simplificada de lo que hace DexAdapter (sin tinte de
     *  evolucionado/fusion ni huevo, no hace falta en el menu debug). */
    private fun bindDebugPokemonCell(holder: MonVH, mon: Mon, shiny: Boolean) {
        val owned = PetState.hasIndividual(this, mon.name, shiny)
        holder.name.text = "#${mon.id} ${PetState.displayLabel(mon.name)}"
        holder.name.setTextColor(Color.parseColor("#EEEEEE"))
        holder.shinyStar.visibility = if (shiny) View.VISIBLE else View.GONE
        holder.eggBadge.visibility = View.GONE
        holder.thumb.setImageDrawable(null)
        holder.thumb.clearColorFilter()
        val expected = mon.name + if (shiny) "#s" else ""
        holder.thumb.tag = expected
        val spriteName = if (owned) PetState.displaySpriteName(this, mon.name, shiny) else mon.name
        loadLocalOrNetworkThumb(holder.thumb, spriteName, shiny, isStillValid = { holder.thumb.tag == expected }) {
            val req = ImageRequest.Builder(this)
                .data("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(spriteName)}.png")
                .target(
                    onSuccess = { d -> if (holder.thumb.tag == expected) holder.thumb.setImageDrawable(d) },
                    onError = { if (holder.thumb.tag == expected) holder.thumb.setImageResource(android.R.drawable.ic_menu_help) }
                )
                .build()
            holder.thumb.context.imageLoader.enqueue(req)
        }
        holder.lvl.text = if (owned) PetState.levelForPokemon(this, mon.name, shiny)?.let { "Nv. $it" } ?: "" else "—"
    }

    /** Rejilla de TODA especie desbloqueada (PetState.isUnlocked - el mismo criterio que la
     *  Pokedex real "Mis Pokemon", NO solo las que ya tienen individuo con stats: una especie
     *  reclamada de una oferta periodica cuenta como "tuya" en la Pokedex desde el momento en
     *  que se reclama, pero no tiene stats propios hasta la primera vez que se cuida de verdad -
     *  bug real reportado por el usuario, la rejilla solo miraba hasIndividual y se dejaba esas
     *  fuera). Normal y shiny cuentan como dos celdas independientes si ambos tienen individuo;
     *  una especie reclamada-pero-nunca-criada aparece como una sola celda sin nivel ("—") -
     *  tocarla la selecciona por primera vez (le crea sus stats) igual que en la Pokedex real. */
    private fun showDebugOwnedGridDialog() {
        data class Entry(val mon: Mon, val shiny: Boolean, val hasData: Boolean)
        val entries = allMons.filter { PetState.isUnlocked(this, it.name) }.flatMap { mon ->
            val hasNormal = PetState.hasIndividual(this, mon.name, false)
            val hasShiny = PetState.hasIndividual(this, mon.name, true)
            when {
                hasNormal && hasShiny -> listOf(Entry(mon, false, true), Entry(mon, true, true))
                hasShiny -> listOf(Entry(mon, true, true))
                hasNormal -> listOf(Entry(mon, false, true))
                else -> listOf(Entry(mon, false, false))
            }
        }.sortedBy { PetState.displayLabel(it.mon.name) + if (it.shiny) "1" else "0" }

        val rv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            layoutManager = GridLayoutManager(this@MainActivity, 3)
        }
        lateinit var dlg: androidx.appcompat.app.AlertDialog
        rv.adapter = object : RecyclerView.Adapter<MonVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonVH =
                MonVH(LayoutInflater.from(parent.context).inflate(R.layout.item_pokemon, parent, false))
            override fun getItemCount() = entries.size
            override fun onBindViewHolder(holder: MonVH, position: Int) {
                val entry = entries[position]
                bindDebugPokemonCell(holder, entry.mon, entry.shiny)
                holder.root.setOnClickListener {
                    dlg.dismiss()
                    if (entry.hasData) {
                        showDebugIndividualDialog(entry.mon.name, entry.shiny)
                    } else {
                        dbg("debug: creando individuo ${entry.mon.name} (shiny=${entry.shiny}) sin activarlo, reclamado por oferta")
                        PetState.rollFreshIndividual(this@MainActivity, entry.mon.name, entry.shiny, entry.mon.id)
                        showLevel()   // el nivel de entrenador cuenta capturas - ver PetState.trainerXpPool
                        showDebugIndividualDialog(entry.mon.name, entry.shiny)
                    }
                }
            }
        }
        dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Mis Pokémon (${entries.size})")
            .setView(rv)
            .setPositiveButton("Cerrar", null)
            .create()
        dlg.show()
    }

    /** Rejilla de TODA la Pokedex (con buscador) para añadir cualquiera - tocar uno lo selecciona
     *  (normal, no shiny) y abre su editor completo (showDebugIndividualDialog), donde shiny es
     *  un parametro mas a cambiar si hace falta - pedido explicito del usuario: no un paso previo
     *  aparte antes de añadir. */
    private fun showDebugAddGridDialog() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val searchInput = EditText(this).apply { hint = "Buscar Pokémon..." }
        root.addView(searchInput)

        // TODA la Pokedex, la tengas ya o no (pedido explicito del usuario: no quitar de aqui
        // los que ya tienes) - el dialogo de confirmacion decide "Agregar" o "Editar" segun si
        // esa variante concreta (shiny o no) ya existe.
        val sortedMons = allMons.sortedBy { PetState.displayLabel(it.name) }
        var filtered = sortedMons
        val rv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            layoutManager = GridLayoutManager(this@MainActivity, 3)
        }
        lateinit var dlg: androidx.appcompat.app.AlertDialog
        val gridAdapter = object : RecyclerView.Adapter<MonVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonVH =
                MonVH(LayoutInflater.from(parent.context).inflate(R.layout.item_pokemon, parent, false))
            override fun getItemCount() = filtered.size
            override fun onBindViewHolder(holder: MonVH, position: Int) {
                val mon = filtered[position]
                bindDebugPokemonCell(holder, mon, false)
                // NO se añade al tocar - pedido explicito del usuario: primero una confirmacion
                // propia (showDebugAddConfirmDialog), con su boton "Agregar".
                holder.root.setOnClickListener { showDebugAddConfirmDialog(mon, dlg) }
            }
        }
        rv.adapter = gridAdapter
        root.addView(rv)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                filtered = if (q.isEmpty()) sortedMons
                    else sortedMons.filter { PetState.displayLabel(it.name).lowercase().contains(q) || it.id.toString() == q }
                gridAdapter.notifyDataSetChanged()
            }
        })
        dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Añadir Pokémon")
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .create()
        dlg.show()
    }

    /** Elegir shiny o no ANTES de decidir que hacer con [mon] (pedido explicito del usuario:
     *  seleccionar NO añade al instante) - el boton cambia solo entre "✏️ Editar" (esa variante
     *  YA existe: abre su editor tal cual, sin tocarla) y "➕ Agregar" (no existe: la crea, activa
     *  en el widget, y abre su editor) segun lo que marque la casilla Shiny en cada momento.
     *  Cancelar no toca nada y vuelve a la rejilla de detras. */
    private fun showDebugAddConfirmDialog(mon: Mon, gridDlg: androidx.appcompat.app.AlertDialog) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }
        val thumb = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(96), dp(80)) }
        root.addView(thumb)
        root.addView(TextView(this).apply {
            text = "#${mon.id} ${PetState.displayLabel(mon.name)}"
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        val shinyCheck = android.widget.CheckBox(this).apply { text = "Shiny" }
        root.addView(shinyCheck)
        val actionBtn = Button(this).apply { isAllCaps = false }
        root.addView(actionBtn)

        fun refreshPreview() {
            val shiny = shinyCheck.isChecked
            val expected = mon.name + if (shiny) "#s" else ""
            thumb.tag = expected
            loadLocalOrNetworkThumb(thumb, mon.name, shiny, isStillValid = { thumb.tag == expected }) {}
            actionBtn.text = if (PetState.hasIndividual(this, mon.name, shiny)) "✏️ Editar" else "➕ Agregar"
        }
        refreshPreview()
        shinyCheck.setOnCheckedChangeListener { _, _ -> refreshPreview() }

        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(root)
            .setNegativeButton("Cancelar", null)
            .create()
        actionBtn.setOnClickListener {
            val shiny = shinyCheck.isChecked
            dlg.dismiss()
            if (PetState.hasIndividual(this, mon.name, shiny)) {
                gridDlg.dismiss()
                showDebugIndividualDialog(mon.name, shiny)
                return@setOnClickListener
            }
            dbg("debug: añadiendo ${mon.name} (shiny=$shiny)")
            selectPokemon(mon, shiny)
            gridDlg.dismiss()
            // selectPokemon asegura el sprite en un hilo de fondo antes de guardar nada - un
            // pequeño margen para que ya este todo escrito cuando se abra el editor.
            android.os.Handler(mainLooper).postDelayed({ showDebugIndividualDialog(mon.name, shiny) }, 700)
        }
        dlg.show()
    }

    /** Editor de UN individuo concreto ([startName]/[shiny], no hace falta que sea el activo):
     *  nivel, stats, evolucion forzada, Mega/Gigantamax forzado, hacerlo activo, eliminarlo. */
    private fun showDebugIndividualDialog(startName: String, startShiny: Boolean) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(60))
        }
        root.addView(thumb)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)
        var currentName = startName.lowercase()
        var shiny = startShiny
        lateinit var dlg: androidx.appcompat.app.AlertDialog

        fun statRow(label: String, value: Int, applyFn: (Float) -> Unit, refresh: () -> Unit): LinearLayout {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(TextView(this).apply {
                text = label; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val input = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
                layoutParams = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(input)
            row.addView(Button(this).apply {
                text = "Aplicar"; isAllCaps = false; textSize = 11f
                setOnClickListener {
                    val v = input.text.toString().toFloatOrNull()
                    if (v == null || v < 0f || v > 100f) Toast.makeText(this@MainActivity, "0-100", Toast.LENGTH_SHORT).show()
                    else { applyFn(v); WidgetRefresh.updateWidgets(this@MainActivity); refresh() }
                }
            })
            return row
        }

        fun refresh() {
            body.removeAllViews()
            val name = currentName
            val isActive = PetState.currentPokemon(this) == name && PetState.isActiveShiny(this) == shiny
            val stats = if (isActive) PetState.loadWithDecay(this) else (PetState.rawStats(this, name, shiny) ?: PetState.Stats(75f, 75f, 75f, 0f))
            val level = PetState.levelOf(this, stats.xp, name)

            val expected = name + if (shiny) "#s" else ""
            thumb.tag = expected
            loadLocalOrNetworkThumb(thumb, PetState.displaySpriteName(this, name, shiny), shiny, isStillValid = { thumb.tag == expected }) {}

            body.addView(TextView(this).apply {
                text = "${PetState.displayLabel(name)}${if (shiny) " ✨" else ""} · Nv. $level"
                textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            })
            body.addView(TextView(this).apply {
                text = if (isActive) "🟢 Activo ahora mismo" else "Congelado (no es el activo ahora)"
                textSize = 11.5f; setTextColor(Color.parseColor("#888888"))
                setPadding(0, 0, 0, dp(8))
            })

            val levelInput = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(level.toString())
                hint = "Nivel (1-100)"
            }
            body.addView(levelInput)
            body.addView(Button(this).apply {
                text = "Aplicar nivel"; isAllCaps = false
                setOnClickListener {
                    val lvl = levelInput.text.toString().toIntOrNull()
                    if (lvl == null || lvl !in 1..100) {
                        Toast.makeText(this@MainActivity, "Nivel entre 1 y 100", Toast.LENGTH_SHORT).show()
                    } else {
                        PetState.debugSetIndividualStats(this@MainActivity, name, shiny, xp = PetState.xpForLevel(this@MainActivity, lvl, name))
                        if (isActive) WidgetRefresh.updateWidgets(this@MainActivity)
                        showLevel()   // el nivel de entrenador suma el nivel de cada Pokemon - ver PetState.trainerXpPool
                        refresh()
                    }
                }
            })
            body.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(8)) })

            body.addView(statRow("Salud", stats.health.toInt(),
                { PetState.debugSetIndividualStats(this, name, shiny, health = it) }, ::refresh))
            body.addView(statRow("Higiene", stats.hygiene.toInt(),
                { PetState.debugSetIndividualStats(this, name, shiny, hygiene = it) }, ::refresh))
            body.addView(statRow("Felicidad", stats.happiness.toInt(),
                { PetState.debugSetIndividualStats(this, name, shiny, happiness = it) }, ::refresh))
            body.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(10)) })

            // Variantes de esta especie: normal y shiny son individuos INDEPENDIENTES (ver
            // PetState.slot) - puede tener solo normal, solo shiny, o los dos a la vez (pedido
            // explicito del usuario). Cada casilla crea o quita ESA variante en concreto; el
            // nivel/stats/evolucion/mega de mas abajo son siempre los de la que se esta viendo
            // ahora mismo (marcada al crear una nueva, o a la que quede si se quita la actual).
            body.addView(TextView(this).apply {
                text = "Variantes que existen"; textSize = 13f; setPadding(0, 0, 0, dp(2))
            })
            val hasNormalNow = PetState.hasIndividual(this, name, false)
            val hasShinyNow = PetState.hasIndividual(this, name, true)
            lateinit var normalCheck: android.widget.CheckBox
            lateinit var shinyIndivCheck: android.widget.CheckBox
            fun onVariantToggle(wantShiny: Boolean) {
                val checkbox = if (wantShiny) shinyIndivCheck else normalCheck
                val nowHas = PetState.hasIndividual(this, name, wantShiny)
                if (checkbox.isChecked && !nowHas) {
                    val mon = allMons.find { it.name == name } ?: return
                    dbg("debug: creando variante shiny=$wantShiny de $name")
                    PetState.rollFreshIndividual(this, name, wantShiny, mon.id)
                    shiny = wantShiny
                    adapter.notifyDataSetChanged()
                    showLevel()   // el nivel de entrenador cuenta capturas - ver PetState.trainerXpPool
                    refresh()
                } else if (!checkbox.isChecked && nowHas) {
                    dbg("debug: quitando variante shiny=$wantShiny de $name")
                    val newActive = PetState.debugDeleteIndividual(this, name, wantShiny)
                    if (newActive != null) WidgetRefresh.updateWidgets(this)
                    adapter.notifyDataSetChanged()
                    // Borrar tu UNICO individuo en TODA la partida (ninguna otra especie con
                    // datos) resetea KEY_STARTER_DONE dentro de debugDeleteIndividual - hay que
                    // mandar a StarterActivity ya mismo (bug real reportado: sin esto, esta misma
                    // pantalla se quedaba a medias, con el Pokemon activo sin datos detras).
                    if (!PetState.hasChosenStarter(this)) {
                        dlg.dismiss()
                        Toast.makeText(this, "Ya no tienes ningún Pokémon - elige un inicial nuevo", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, StarterActivity::class.java))
                        finish()
                        return
                    }
                    showLevel()   // idem al quitar - el pozo del entrenador baja con menos capturas
                    if (!PetState.hasIndividual(this, name, !wantShiny)) {
                        Toast.makeText(this, "${PetState.displayLabel(name)} ya no tiene ningún individuo", Toast.LENGTH_LONG).show()
                        dlg.dismiss()
                    } else {
                        if (shiny == wantShiny) shiny = !wantShiny
                        refresh()
                    }
                }
            }
            val variantsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            normalCheck = android.widget.CheckBox(this).apply { text = "Normal"; isChecked = hasNormalNow }
            shinyIndivCheck = android.widget.CheckBox(this).apply { text = "Shiny"; isChecked = hasShinyNow }
            variantsRow.addView(normalCheck)
            variantsRow.addView(shinyIndivCheck)
            normalCheck.setOnCheckedChangeListener { _, _ -> onVariantToggle(false) }
            shinyIndivCheck.setOnCheckedChangeListener { _, _ -> onVariantToggle(true) }
            body.addView(variantsRow)
            if (hasNormalNow && hasShinyNow) {
                // Las dos existen a la vez: las casillas de arriba no sirven para cambiar cual se
                // esta viendo (tocar una ya marcada no dispara su listener) - botones aparte.
                val viewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                viewRow.addView(Button(this).apply {
                    text = "Ver normal"; isAllCaps = false; textSize = 11f
                    setOnClickListener { shiny = false; refresh() }
                })
                viewRow.addView(Button(this).apply {
                    text = "Ver shiny"; isAllCaps = false; textSize = 11f
                    setOnClickListener { shiny = true; refresh() }
                })
                body.addView(viewRow)
            }
            body.addView(TextView(this).apply {
                text = "Editando abajo: ${if (shiny) "la shiny" else "la normal"}"
                textSize = 11f; setTextColor(Color.parseColor("#888888"))
                setPadding(0, dp(2), 0, dp(4))
            })
            body.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(10)) })

            if (isActive) {
                body.addView(TextView(this).apply {
                    text = "🟢 Este es el Pokémon que se ve ahora mismo en el widget de tu pantalla de inicio"
                    textSize = 12f
                    setTextColor(Color.parseColor("#888888"))
                })
            } else {
                body.addView(TextView(this).apply {
                    text = "Pone a este Pokémon en el widget de tu pantalla de inicio, en vez del que tengas ahora."
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#888888"))
                    setPadding(0, 0, 0, dp(4))
                })
                body.addView(Button(this).apply {
                    text = "Poner en el widget"; isAllCaps = false
                    setOnClickListener {
                        val mon = allMons.find { it.name == name } ?: return@setOnClickListener
                        dbg("debug: haciendo activo $name (shiny=$shiny)")
                        selectPokemon(mon, shiny)
                        android.os.Handler(mainLooper).postDelayed({ refresh() }, 700)
                    }
                })
            }
            body.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(10)) })

            val evo = PetState.evolutionInfo(this, name)
            if (evo != null && evo.evolvesTo.isNotEmpty()) {
                body.addView(TextView(this).apply {
                    text = "Forzar evolución (ignora nivel/condición)"; textSize = 13f; setPadding(0, 0, 0, dp(2))
                })
                val evoSpinner = android.widget.Spinner(this)
                evoSpinner.adapter = android.widget.ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item, evo.evolvesTo.map { PetState.displayLabel(it.name) }
                )
                body.addView(evoSpinner)
                body.addView(Button(this).apply {
                    text = "Evolucionar ahora"; isAllCaps = false
                    setOnClickListener {
                        val opt = evo.evolvesTo[evoSpinner.selectedItemPosition]
                        val (toName, toId) = PetState.resolveEvolutionTarget(opt.name, opt.id)
                        Thread {
                            SpriteRepository.ensure(this@MainActivity, toName, toId, PetState.currentStyle(this@MainActivity), shiny, PetState.spriteScaleMode(this@MainActivity))
                            runOnUiThread {
                                dbg("debug: evolucion forzada $name -> $toName")
                                PetState.evolveTo(this@MainActivity, name, toName, toId, shiny)
                                currentName = toName
                                if (isActive) WidgetRefresh.updateWidgets(this@MainActivity)
                                adapter.notifyDataSetChanged()
                                showLevel()   // el nivel de entrenador cuenta evoluciones - ver PetState.trainerXpPool
                                refresh()
                                Toast.makeText(this@MainActivity, "Evolucionado a ${PetState.displayLabel(toName)}", Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }
                })
                body.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(10)) })
            }

            val megaOpts = PetState.megaOptionsFor(this, name)
            if (megaOpts.isNotEmpty()) {
                val activeMegaName = PetState.activeMegaSpriteName(this, name)
                if (activeMegaName != null) {
                    val activeLabel = megaOpts.find { it.name == activeMegaName }?.label ?: activeMegaName
                    body.addView(TextView(this).apply {
                        text = "$activeLabel activo ahora mismo"; textSize = 13f; setPadding(0, 0, 0, dp(4))
                    })
                    body.addView(Button(this).apply {
                        text = "Quitar Mega/Gigantamax"; isAllCaps = false
                        setOnClickListener {
                            dbg("debug: mega/gigantamax quitado a mano de $name")
                            PetState.debugDeactivateMega(this@MainActivity, name)
                            if (isActive) WidgetRefresh.updateWidgets(this@MainActivity)
                            refresh()
                        }
                    })
                } else {
                    body.addView(TextView(this).apply {
                        text = "Activar Mega/Gigantamax (ignora nivel)"; textSize = 13f; setPadding(0, 0, 0, dp(2))
                    })
                    val megaSpinner = android.widget.Spinner(this)
                    megaSpinner.adapter = android.widget.ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item, megaOpts.map { it.label }
                    )
                    body.addView(megaSpinner)
                    body.addView(Button(this).apply {
                        text = "Activar"; isAllCaps = false
                        setOnClickListener {
                            val opt = megaOpts[megaSpinner.selectedItemPosition]
                            dbg("debug: mega/gigantamax forzado $name -> ${opt.name}")
                            PetState.activateMega(this@MainActivity, name, opt.name)
                            if (isActive) WidgetRefresh.updateWidgets(this@MainActivity)
                            refresh()
                        }
                    })
                }
                body.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(10)) })
            }

            // Formas decorativas (disfraces de Pikachu, patrones de Vivillon, sabores de
            // Alcremie, cortes de Furfrou, letras de Unown, colores de Basculin/Squawkabilly/
            // Minior, tamaños de Pumpkaboo/Gourgeist, formas de Castform/Deoxys, Shellos/
            // Gastrodon, Flabébé/Floette/Florges, razas de Tauros, formas de Arceus/Silvally...
            // ver PetState.DECORATIVE_FORMS) - pedido explicito del usuario: marcar/desmarcar
            // igual que shiny, no solo elegir la puesta ahora. Rotom/Oricorio/Ogerpon/Genesect/
            // Meloetta y las formas puramente automaticas (Darmanitan Zen, Aegislash, Cramorant,
            // Zygarde, Terapagos...) se quedan fuera a proposito: las primeras se desbloquean por
            // nivel sin ningun "conjunto conseguido" que marcar (el campo Nivel de arriba ya las
            // desbloquea), y las segundas no son una eleccion del jugador.
            val decoRoot = when (name) {
                "gourgeist" -> "pumpkaboo"
                "gastrodon" -> "shellos"
                "floette", "florges" -> "flabebe"
                else -> name
            }
            val decoOptions = PetState.decorativeFormsOptions(decoRoot)
            if (decoOptions.isNotEmpty()) {
                body.addView(TextView(this).apply {
                    text = "Formas de ${PetState.displayLabel(decoRoot)}"; textSize = 13f; setPadding(0, 0, 0, dp(2))
                })
                body.addView(TextView(this).apply {
                    text = "Marca las que quieras tener disponibles para elegir - compartidas entre el normal y el shiny de esta especie."
                    textSize = 11f; setTextColor(Color.parseColor("#888888")); setPadding(0, 0, 0, dp(4))
                })
                val ownedForms = PetState.decorativeFormsOwned(this, decoRoot)
                val currentForm = PetState.decorativeForm(this, decoRoot)
                decoOptions.forEach { form ->
                    body.addView(android.widget.CheckBox(this).apply {
                        text = form + if (form == currentForm) " (puesta ahora)" else ""
                        isChecked = form in ownedForms
                        setOnCheckedChangeListener { _, checked ->
                            dbg("debug: forma decorativa $decoRoot/$form owned=$checked")
                            PetState.debugSetDecorativeFormOwned(this@MainActivity, decoRoot, form, checked)
                            if (isActive) WidgetRefresh.updateWidgets(this@MainActivity)
                            refresh()
                        }
                    })
                }
                body.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(10)) })
            }
        }
        refresh()

        val scroll = ScrollView(this).apply { addView(root) }
        dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Editar Pokémon")
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .create()
        dlg.show()
    }

    /** Rellena [grid] con los fondos: primero los ya desbloqueados que le pegan al Pokemon activo
     *  (⭐), luego el resto de desbloqueados, luego los bloqueados (atenuados, con 🔒). Tocar uno
     *  desbloqueado lo aplica; tocar uno bloqueado gasta un token si hay alguno disponible, o solo
     *  avisa si no. */
    private fun buildBgGrid(grid: LinearLayout) {
        grid.removeAllViews()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cur = PetState.currentBg(this)
        val matched = PetState.suggestedBackgrounds(this, PetState.currentPokemon(this)).toSet()
        val unlocked = PetState.unlockedBackgrounds(this)
        val tokens = PetState.bgTokensAvailable(this)

        grid.addView(TextView(this).apply {
            text = if (tokens > 0) "Puedes desbloquear $tokens fondo(s) más ahora mismo (${PetState.bgTokenLabelEvery()}, 1 a tu elección)"
                else "Sube de nivel a tus Pokémon para desbloquear más fondos (${PetState.bgTokenLabelEvery()}, 1 a tu elección)"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, dp(2))
        })

        val ordered = backgrounds.sortedWith(
            compareByDescending<String> { it in unlocked && it in matched }.thenByDescending { it in unlocked }
        )

        var i = 0
        while (i < ordered.size) {
            val rowLl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            for (c in 0 until 3) {
                val idx = i + c
                if (idx >= ordered.size) {
                    rowLl.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = if (c > 0) dp(8) else 0 }
                    })
                    continue
                }
                val name = ordered[idx]
                val isUnlockedBg = name in unlocked
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = if (c > 0) dp(8) else 0 }
                }
                // El marco de seleccion va en una capa PROPIA (un View aparte, superpuesto por
                // ENCIMA de la miniatura, con sus mismos limites exactos) en vez de ser el fondo
                // del propio ImageView - asi nunca depende de padding ni de como CENTER_CROP
                // recorta la imagen (2 intentos previos con ese truco se vieron "cortados", una
                // vez arriba/abajo y otra vez izquierda/derecha - un View overlay independiente
                // no tiene ninguno de esos problemas, su rectangulo es siempre el real).
                val cell = android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96))
                }
                val iv = ImageView(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(resources.getIdentifier(name, "drawable", packageName))
                    alpha = if (isUnlockedBg) 1f else 0.35f
                }
                val border = View(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setStroke(dp(3), if (name == cur) Color.parseColor("#2E86E0") else Color.parseColor("#33000000"))
                    }
                }
                cell.addView(iv)
                cell.addView(border)
                // Iconos de tipo que este fondo boostea (ver PetState.typesBenefitedBy/BG_FOR_TYPE),
                // arriba a la izquierda - pedido explicito del usuario para poder ver de un
                // vistazo, al elegir fondo, que tipos se benefician. GridLayout con 4 columnas fijas
                // envuelve solo por columnCount (nunca por ancho real medido), asi que fondos con
                // muchos tipos (ej. montaña, 7) simplemente caen en una segunda fila sin logica de
                // ajuste manual. Iconos "type_<tipo>.png" (redondos, estilo Gen8 Espada/Escudo,
                // extraidos y verificados uno a uno) - no son pixel-art (revisado y descartado por
                // el usuario: Fairy no existe en el estilo pixel-art de Gen5, asi que se paso a
                // este set moderno para los 18 tipos por igual, ver conversacion).
                val boostTypes = PetState.typesBenefitedBy(name)
                if (boostTypes.isNotEmpty()) {
                    val iconsGrid = android.widget.GridLayout(this).apply {
                        columnCount = 4
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = dp(2); leftMargin = dp(2) }
                    }
                    for (t in boostTypes) {
                        val resId = resources.getIdentifier("type_$t", "drawable", packageName)
                        if (resId == 0) continue
                        iconsGrid.addView(ImageView(this).apply {
                            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                                width = dp(15); height = dp(15)
                                setMargins(dp(1), dp(1), dp(1), dp(1))
                            }
                            setImageResource(resId)
                        })
                    }
                    cell.addView(iconsGrid)
                }
                if (name in matched) {
                    cell.addView(TextView(this).apply {
                        text = "⭐"; textSize = 14f
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(2); rightMargin = dp(2) }
                    })
                }
                if (!isUnlockedBg) {
                    cell.addView(TextView(this).apply {
                        text = "🔒"; textSize = 20f
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply { gravity = Gravity.CENTER }
                    })
                }
                cell.setOnClickListener { showBgInfoDialog(name) { buildBgGrid(grid) } }
                col.addView(cell)
                col.addView(TextView(this).apply {
                    text = bgDisplayName(name)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                })
                rowLl.addView(col)
            }
            grid.addView(rowLl)
            i += 3
        }
    }

    /** Ficha de un fondo al tocarlo: imagen grande, a que tipos beneficia (con su color) y en que
     *  consiste el beneficio - y desde ahi, aplicarlo (si esta desbloqueado) o desbloquearlo (si
     *  no, y hay algun desbloqueo disponible). Antes esto se hacia sin explicar nada al tocar.
     *  [onChanged] se llama tras aplicar/desbloquear, para que quien la abrio pueda refrescarse
     *  (la rejilla de Ajustes, la ficha de un Pokemon...) sin que esta funcion sepa de donde viene. */
    private fun showBgInfoDialog(bg: String, onChanged: () -> Unit) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val isUnlockedBg = PetState.isBgUnlocked(this, bg)
        val types = PetState.typesBenefitedBy(bg)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        root.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(resources.getIdentifier(bg, "drawable", packageName))
            alpha = if (isUnlockedBg) 1f else 0.4f
        })
        root.addView(TextView(this).apply {
            text = bgDisplayName(bg)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Beneficia a:"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(typeChipRow(types).apply { setPadding(0, dp(4), 0, dp(10)) })
        root.addView(TextView(this).apply {
            text = "Si tu Pokémon activo es de uno de esos tipos y tienes este fondo puesto: " +
                "el doble de XP y el doble de probabilidad de shiny en sus huevos."
            textSize = 12.5f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, dp(8))
        })
        if (!isUnlockedBg) {
            root.addView(TextView(this).apply {
                text = if (PetState.bgTokensAvailable(this@MainActivity) > 0)
                    "Aún bloqueado - puedes desbloquearlo ahora." else "Aún bloqueado - sigue subiendo de nivel a tus Pokémon."
                textSize = 12.5f
                setPadding(0, 0, 0, dp(4))
            })
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton("Cerrar", null)
            .setPositiveButton(if (isUnlockedBg) "Aplicar este fondo" else "Desbloquear", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = isUnlockedBg || PetState.bgTokensAvailable(this@MainActivity) > 0
                setOnClickListener {
                    if (isUnlockedBg) {
                        PetState.setBg(this@MainActivity, bg)
                        WidgetRefresh.updateWidgets(this@MainActivity)
                    } else {
                        PetState.unlockBackground(this@MainActivity, bg)
                        Toast.makeText(this@MainActivity, "¡Fondo desbloqueado!", Toast.LENGTH_SHORT).show()
                    }
                    onChanged()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    // bg_beachshore y bg_river se QUITARON de la lista (pedido explicito del usuario): con el
    // rediseño a 1 fondo por tipo estricto, un fondo sin tipo asociado no aporta nada util en esta
    // cuadricula - ver PetState.BG_FOR_TYPE, no volver a añadirlos aqui sin que se pida.
    private val backgrounds = listOf(
        "bg_meadow", "bg_forest", "bg_beach", "bg_city", "bg_mountain",
        "bg_route", "bg_desert", "bg_deepsea", "bg_thunderplains",
        "bg_dampcave", "bg_earthycave", "bg_icecave", "bg_volcanocave",
        // Nuevos (rediseño de fondo-por-tipo 1 a 1, ver conversacion): antes de esto, varios tipos
        // compartian el mismo fondo (montaña llegaba a valer para 7 tipos a la vez) - estos 5
        // cubren los tipos que ningun fondo de Showdown representaba bien.
        "bg_templo", "bg_cuevalila", "bg_mundodistorsion", "bg_cuevaoscura", "bg_bosquearcoiris"
    )

    private val bgDisplayNames = mapOf(
        "bg_meadow" to "Pradera", "bg_forest" to "Bosque", "bg_beach" to "Playa",
        "bg_city" to "Ciudad", "bg_mountain" to "Montaña",
        "bg_route" to "Ruta", "bg_desert" to "Desierto",
        "bg_deepsea" to "Mar profundo", "bg_thunderplains" to "Llanura eléctrica",
        "bg_dampcave" to "Cueva húmeda", "bg_earthycave" to "Cueva de tierra",
        "bg_icecave" to "Cueva de hielo", "bg_volcanocave" to "Volcán",
        "bg_templo" to "Templo", "bg_cuevalila" to "Cueva de cristal", "bg_mundodistorsion" to "Mundo Distorsión",
        "bg_cuevaoscura" to "Cueva oscura", "bg_bosquearcoiris" to "Bosque de cerezos"
    )
    private fun bgDisplayName(bg: String) = bgDisplayNames[bg] ?: bg

    private val typeDisplayNames = mapOf(
        "normal" to "Normal", "fighting" to "Lucha", "flying" to "Volador", "poison" to "Veneno",
        "ground" to "Tierra", "rock" to "Roca", "bug" to "Bicho", "ghost" to "Fantasma",
        "steel" to "Acero", "fire" to "Fuego", "water" to "Agua", "grass" to "Planta",
        "electric" to "Eléctrico", "psychic" to "Psíquico", "ice" to "Hielo", "dragon" to "Dragón",
        "dark" to "Siniestro", "fairy" to "Hada"
    )
    private fun typeDisplayName(t: String) = typeDisplayNames[t] ?: t

    private val vivillonPatternNames = mapOf(
        "meadow" to "Pradera", "archipelago" to "Archipiélago", "continental" to "Continental",
        "elegant" to "Elegante", "garden" to "Jardín", "highplains" to "Meseta",
        "icysnow" to "Nieve", "jungle" to "Selva", "marine" to "Marino", "modern" to "Moderno",
        "monsoon" to "Monzón", "ocean" to "Océano", "polar" to "Polar", "river" to "Río",
        "sandstorm" to "Tormenta de arena", "savanna" to "Sabana", "sun" to "Sol",
        "tundra" to "Tundra", "fancy" to "Fantasía", "pokeball" to "Poké Ball"
    )
    private fun vivillonPatternName(p: String) = vivillonPatternNames[p] ?: p

    private val pikachuCostumeNames = mapOf(
        "regular" to "Sin disfraz", "cosplay" to "Cosplay (base)", "phd" to "Doctorado",
        "rockstar" to "Estrella del Rock", "libre" to "Libre", "belle" to "Bella",
        "popstar" to "Estrella del Pop", "original" to "Gorra Original", "hoenn" to "Gorra Hoenn",
        "sinnoh" to "Gorra Sinnoh", "unova" to "Gorra Teselia", "kalos" to "Gorra Kalos",
        "alola" to "Gorra Alola", "partner" to "Gorra Compañero", "world" to "Gorra Mundial"
    )
    private fun pikachuCostumeName(p: String) = pikachuCostumeNames[p] ?: p

    private val alcremieFlavorNames = mapOf(
        "ruby" to "Crema Rubí", "rubyswirl" to "Remolino Rubí", "matcha" to "Crema Matcha",
        "mint" to "Crema Menta", "lemon" to "Crema Limón", "vanilla" to "Crema Vainilla",
        "salted" to "Crema Salada", "caramel" to "Remolino Caramelo", "rainbow" to "Remolino Arcoíris"
    )
    private val alcremieSweetNames = mapOf(
        "strawberry" to "Fresa", "berry" to "Baya", "love" to "Amor", "star" to "Estrella",
        "clover" to "Trébol", "flower" to "Flor", "ribbon" to "Lazo"
    )
    private fun alcremieFlavorName(form: String): String {
        val parts = form.split("-")
        val flavor = alcremieFlavorNames[parts.getOrNull(0)] ?: parts.getOrNull(0) ?: form
        val sweet = alcremieSweetNames[parts.getOrNull(1)] ?: parts.getOrNull(1) ?: ""
        return "$flavor · $sweet"
    }

    private val typeColors = mapOf(
        "normal" to "#A8A878", "fighting" to "#C03028", "flying" to "#A890F0", "poison" to "#A040A0",
        "ground" to "#E0C068", "rock" to "#B8A038", "bug" to "#A8B820", "ghost" to "#705898",
        "steel" to "#B8B8D0", "fire" to "#F08030", "water" to "#6890F0", "grass" to "#78C850",
        "electric" to "#F8D030", "psychic" to "#F85888", "ice" to "#98D8D8", "dragon" to "#7038F8",
        "dark" to "#705848", "fairy" to "#EE99AC"
    )

    /** Una "pastilla" de color con el nombre del tipo, para reconocerlo de un vistazo (en vez de
     *  texto plano gris igual para todos los tipos). */
    private fun typeChip(t: String): TextView {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return TextView(this).apply {
            text = typeDisplayName(t)
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(typeColors[t] ?: "#888888"))
            }
        }
    }

    private fun typeChipRow(types: List<String>): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for ((idx, t) in types.withIndex()) {
                if (idx > 0) addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
                addView(typeChip(t))
            }
        }
    }

    /** Frase corta para un multiplicador de personalidad ("le entra hambre con facilidad" /
     *  "aguanta mucho sin comer") - null si esta cerca de 1 (no hay nada que destacar en esa
     *  barra, para no llenar la frase de "normal, normal, normal"). */
    private fun personalityPhrase(mult: Float, fastPhrase: String, slowPhrase: String): String? = when {
        mult > 1.05f -> fastPhrase
        mult < 0.95f -> slowPhrase
        else -> null
    }

    /** Debajo de los tipos: personalidad segun sus estadisticas base (afecta al decaimiento de
     *  cada barra) - ya funciona en el juego, esto solo la hace visible, en lenguaje llano en vez
     *  de numeros/multiplicadores (pedido explicito: nada tecnico). El grupo de crecimiento se
     *  probo tambien aqui pero se quito: aun bien explicado, no se entendia de un vistazo. */
    private fun addPersonalityInfo(root: LinearLayout, name: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val (mHealth, mHappy, mHygiene) = PetState.personalityMultipliers(this, name)
        val rasgos = listOfNotNull(
            personalityPhrase(mHealth, "le entra hambre con facilidad", "aguanta mucho sin comer"),
            personalityPhrase(mHappy, "se aburre con facilidad", "es bastante independiente"),
            personalityPhrase(mHygiene, "se ensucia con facilidad", "se mantiene limpio mucho tiempo")
        )
        val personalidadTexto = if (rasgos.isEmpty()) "un carácter equilibrado, sin rasgos destacables."
            else rasgos.joinToString(", ") + "."
        root.addView(TextView(this).apply {
            text = "🧬 Personalidad: $personalidadTexto"
            textSize = 11.5f
            // Blanco (antes gris #666666) - pedido explicito del usuario: "los textos de
            // informacion... estan en un gris y no se lee bien".
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(2), 0, dp(6))
        })

        // Debug: cuantas veces al dia hace falta en TEORIA cada accion para esta especie en
        // concreto (calculado con su personalidad y el decaimiento/boost reales, no un registro
        // de lo que hayas hecho hasta ahora - ver PetState.estimatedActionsPerDay).
        val est = PetState.estimatedActionsPerDay(this, name)
        root.addView(TextView(this).apply {
            text = "🐛 ≈%.1f comidas/día (x%.2f) · ≈%.1f caricias/día (x%.2f) · ≈%.1f lavados/día (x%.2f)"
                .format(est.feed, mHealth, est.pet, mHappy, est.wash, mHygiene)
            textSize = 10.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun showLevel() {
        val s = PetState.loadWithDecay(this)
        val tokens = PetState.bgTokensAvailable(this)
        val bgSuffix = if (tokens > 0) " ⬆️" else ""
        // tv_level (tvLevel) ya no muestra "especie · Nivel X" aqui - pedido explicito del
        // usuario: ahora esta vista enseña el contador de especies (ver refreshRows()), asi que
        // showLevel() ya solo actualiza el nivel de entrenador.
        tvTrainerLevel.text = "Entrenador Nv.${PetState.trainerLevel(this)}$bgSuffix"
        trainerLevelBar.max = 1000
        trainerLevelBar.progress = (PetState.trainerProgress(this) * 1000).toInt()
        announceTrainerLevelUp()
        refreshUnlockButtons()
        if (tokens > 0) TutorialTips.showPending(this, TutorialTips.Tip.BACKGROUNDS, resolve = ::tipTarget)
    }

    /** Color de texto secundario del tema del dialogo (en el tema oscuro, un gris claro): un gris
     *  fijo como #666 se pierde sobre el fondo oscuro. */
    private fun dialogSecondaryTextColor(): Int {
        val a = theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        return try { a.getColor(0, Color.parseColor("#B0B0B0")) } finally { a.recycle() }
    }

    // ==================== MOCHILA Y CARAMELOS ====================
    // Se abre con el boton 🎒 de la cabecera (visible desde el nivel de entrenador que la desbloquea).
    // De momento solo guarda caramelos (se consiguen soltando un huevo); un caramelo da XP a cualquier
    // Pokemon tuyo (ver PetState.useCandy). Elegir a quien se lo das es el selector de showCandyPicker.

    private fun showBagDialog(): androidx.appcompat.app.AlertDialog {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val candies = PetState.candyCount(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(DungeonSpriteRepository.itemIcon(this@MainActivity, "EXPCANDYM"))
        }
        head.addView(icon)
        head.addView(TextView(this).apply {
            text = "Caramelos: $candies"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(head)
        root.addView(TextView(this).apply {
            text = "Cada caramelo da XP a un Pokémon tuyo: un ${"%.0f".format(100 * PetState.candyPctOfNextLevel())} % de lo que le cuesta su siguiente nivel (mínimo ${PetState.candyMinXp().toInt()} XP), " +
                "así que sirve igual a cualquier nivel. Los consigues soltando un huevo."
            textSize = 13f
            setTextColor(dialogSecondaryTextColor())
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "♻️ Un Pokémon al nivel 100 ya no puede subir: su XP sobrante se guarda y cada ${PetState.overflowXpPerCandy().toInt()} XP se convierte en un caramelo. " +
                "Ahora: ${PetState.overflowXp(this@MainActivity).toInt()} / ${PetState.overflowXpPerCandy().toInt()} para el próximo."
            textSize = 12.5f
            setTextColor(dialogSecondaryTextColor())
            setPadding(0, 0, 0, dp(12))
        })
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎒 Mochila")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Cerrar", null)
            .create()
        root.addView(Button(this).apply {
            text = if (candies > 0) "Dar un caramelo a un Pokémon" else "Aún no tienes caramelos"
            isAllCaps = false
            isEnabled = candies > 0
            setOnClickListener { dialog.dismiss(); showCandyPicker() }
        })
        dialog.show()
        return dialog
    }

    /** Una fila del selector de Pokémon ([showMonPicker]): [subtitle] es la segunda línea; con
     *  [enabled] = false se ve en gris y, al tocarla, solo enseña [blockedToast] (si lo hay). */
    private class PickRow(
        val name: String, val shiny: Boolean, val level: Int, val subtitle: String,
        val enabled: Boolean = true, val blockedToast: String? = null
    )

    /** Lo que puede hacer quien reacciona a una elección: repintar la lista o cerrarla. */
    private class PickerHandle(val reload: () -> Unit, val dismiss: () -> Unit)

    /** Selector genérico de Pokémon tuyos (lista con sprite + nombre + [PickRow.subtitle]).
     *  [load] da las filas YA ordenadas; [onChoose] se llama al tocar una fila activa. */
    private fun showMonPicker(
        title: String,
        load: () -> List<PickRow>,
        onChoose: (PickRow, PickerHandle) -> Unit,
        previewDump: String? = null
    ): androidx.appcompat.app.AlertDialog {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var rows = load()
        val sprites = android.util.LruCache<String, Bitmap>(80)
        val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val rv = RecyclerView(this)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    isClickable = true
                    background = android.util.TypedValue().let { tv ->
                        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                        ContextCompat.getDrawable(this@MainActivity, tv.resourceId)
                    }
                }
                row.addView(ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
                row.addView(TextView(this@MainActivity).apply {
                    tag = "label"
                    textSize = 15f
                    setPadding(dp(12), 0, 0, 0)
                })
                return object : RecyclerView.ViewHolder(row) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val r = rows[position]
                val row = holder.itemView as LinearLayout
                val img = row.getChildAt(0) as ImageView
                val label = row.getChildAt(1) as TextView
                label.text = (PetState.displayLabel(r.name).replaceFirstChar { it.uppercase() }) + (if (r.shiny) " ✨" else "") +
                    "\n" + r.subtitle
                row.alpha = if (r.enabled) 1f else 0.45f
                val key = "${r.name}|${r.shiny}"
                img.tag = key
                img.setImageBitmap(sprites.get(key))
                if (sprites.get(key) == null) {
                    loader.execute {
                        val bmp = SpriteRepository.localFirstFrameScaled(this@MainActivity, r.name, r.shiny, PetState.spriteScaleMode(this@MainActivity))
                        if (bmp != null) {
                            sprites.put(key, bmp)
                            runOnUiThread { if (img.tag == key) img.setImageBitmap(bmp) }
                        }
                    }
                }
                row.setOnClickListener {
                    if (!r.enabled) {
                        r.blockedToast?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                        return@setOnClickListener
                    }
                    onChoose(r, PickerHandle(
                        reload = { rows = load(); notifyDataSetChanged() },
                        dismiss = { dialog.dismiss() }
                    ))
                }
            }
        }
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rv.adapter = adapter
        rv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380))
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(rv)
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.setOnDismissListener { loader.shutdown(); refreshUnlockButtons() }
        dialog.show()
        previewDump?.let { dumpDialogView(dialog, it, 2500) }
        return dialog
    }

    /** SOLO PARA PRUEBAS: vuelca la vista del propio [dialog] a filesDir/[fileName] pasados [delayMs]
     *  (sin capturar la pantalla del movil) - la forma de revisar el aspecto desde adb. */
    private fun dumpDialogView(dialog: android.app.Dialog, fileName: String, delayMs: Long) {
        val decor = dialog.window?.decorView ?: return
        decor.postDelayed({
            try {
                val bmp = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bmp).apply { drawColor(Color.WHITE); decor.draw(this) }
                java.io.File(filesDir, fileName).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                dbg("vista previa volcada a $fileName")
            } catch (e: Exception) { dbg("volcado de $fileName fallo: $e") }
        }, delayMs)
    }

    /** Lista de tus Pokémon (de menor a mayor nivel) para elegir a quien le das UN caramelo. Al
     *  elegir se gasta el caramelo y la lista se refresca (se puede seguir dando hasta que se acaben). */
    private fun showCandyPicker(): androidx.appcompat.app.AlertDialog {
        lastCandyPicker = showMonPicker(
            "¿A quién le das el caramelo?",
            load = {
                PetState.ownedIndividuals(this).mapNotNull { (n, s) ->
                    val lvl = PetState.levelForPokemon(this, n, s) ?: return@mapNotNull null
                    val xp = PetState.rawStats(this, n, s)?.xp ?: 0f
                    val gain = PetState.candyXpFor(this, n, xp) ?: return@mapNotNull null   // nivel 100: no sube
                    PickRow(n, s, lvl, "Nv $lvl · +${gain.toInt().coerceAtLeast(1)} XP")
                }.sortedWith(compareBy({ it.level }, { it.name }))
            },
            onChoose = { r, picker ->
                fun finish(use: PetState.CandyUse?) {
                    if (use == null) {
                        Toast.makeText(this, "No se ha podido usar el caramelo", Toast.LENGTH_SHORT).show()
                    } else {
                        dbg("mochila: ${if (use.used > 1) "${use.used} caramelos" else "caramelo"} a ${r.name}${if (r.shiny) " (shiny)" else ""} +${use.xp.toInt()} XP, nivel ${r.level} -> ${use.newLevel}")
                        val name = PetState.displayLabel(r.name).replaceFirstChar { it.uppercase() }
                        Toast.makeText(
                            this,
                            "$name: " + (if (use.used > 1) "${use.used} caramelos · " else "") + "+${use.xp.toInt().coerceAtLeast(1)} XP" +
                                (if (use.leveledUp) " · ¡nivel ${use.newLevel}!" else ""),
                            Toast.LENGTH_SHORT
                        ).show()
                        if (use.leveledUp) SoundManager.playLevelUp(this)
                        WidgetRefresh.updateWidgets(this)
                        showLevel()
                        if (PetState.candyCount(this) <= 0) picker.dismiss() else picker.reload()
                    }
                }
                // Con mas de un caramelo (y si a este Pokemon le sirve mas de uno) se pregunta cuantos; con uno solo, directo como siempre.
                if ((PetState.candyPlan(this, r.name, r.shiny, PetState.candyCount(this))?.used ?: 0) > 1) showCandyAmountDialog(r) { finish(it) }
                else finish(PetState.useCandy(this, r.name, r.shiny))
            }
        )
        return lastCandyPicker!!
    }

    /** Barra de nivel de la vista previa de los caramelos: lo que ya lleva en este nivel (verde oscuro) y, encima, lo que ganaria (verde claro). */
    private class CandyBarView(context: android.content.Context) : View(context) {
        var before = 0f
        var after = 0f
        private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3646.toInt() }
        private val gain = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF81C784.toInt() }
        private val own = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt() }
        private val clip = android.graphics.Path()
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
            clip.reset(); clip.addRoundRect(android.graphics.RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            canvas.drawRect(0f, 0f, w, h, bg)
            canvas.drawRect(0f, 0f, w * after.coerceIn(0f, 1f), h, gain)
            canvas.drawRect(0f, 0f, w * before.coerceIn(0f, 1f), h, own)
            canvas.restore()
        }
    }

    /** SOLO PRUEBAS: el ultimo dialogo de cantidad de caramelos abierto (ver [debugCandyFlow]). */
    private var lastCandyAmountDialog: androidx.appcompat.app.AlertDialog? = null

    /** Pregunta cuantos caramelos dar a [row] (por defecto 1; botones - y + y una cantidad escrita) y ensena, antes de confirmar, el nivel de
     *  ahora, el que tendria y la barra de como quedaria. El maximo es lo que haya o lo que haga falta para llegar al nivel 100 (el resto no
     *  se gastaria). [onConfirm] recibe el resultado de [PetState.useCandies]. */
    private fun showCandyAmountDialog(row: PickRow, onConfirm: (PetState.CandyUse?) -> Unit) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val have = PetState.candyCount(this)
        val maxUseful = (PetState.candyPlan(this, row.name, row.shiny, have)?.used ?: 0).coerceAtLeast(1)
        var amount = 1
        val label = PetState.displayLabel(row.name).replaceFirstChar { it.uppercase() } + (if (row.shiny) " ✨" else "")
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val secondary = dialogSecondaryTextColor()

        val levels = TextView(this).apply { textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; tag = "candy_levels" }
        val levelNote = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(secondary); tag = "candy_levels_note" }
        val bar = CandyBarView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(10) } }
        val xpLine = TextView(this).apply { textSize = 12.5f; gravity = Gravity.CENTER; setTextColor(secondary); setPadding(0, dp(8), 0, 0); tag = "candy_xp_line" }
        val input = EditText(this).apply {
            setText("1"); gravity = Gravity.CENTER; textSize = 20f; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT)
            tag = "candy_amount"
        }
        var updating = false
        fun render() {
            val plan = PetState.candyPlan(this, row.name, row.shiny, amount) ?: return
            levels.text = "Nv ${plan.levelBefore}  →  Nv ${plan.levelAfter}"
            val gained = plan.levelAfter - plan.levelBefore
            levelNote.text = when {
                plan.levelAfter >= 100 -> "Llega al nivel máximo" + if (gained > 0) " · sube $gained ${if (gained == 1) "nivel" else "niveles"}" else ""
                gained > 0 -> "Sube $gained ${if (gained == 1) "nivel" else "niveles"}"
                else -> "No sube de nivel"
            }
            // Si no cambia de nivel se ve lo que ya llevaba (oscuro) y lo que ganaria (claro); si sube, solo como quedaria en el nivel nuevo.
            bar.before = if (gained == 0) plan.progressBefore else 0f
            bar.after = plan.progressAfter
            bar.invalidate()
            xpLine.text = "+${plan.xpGained.toInt().coerceAtLeast(1)} XP · usas ${plan.used} de $have · te ${if (have - plan.used == 1) "queda" else "quedan"} ${have - plan.used}"
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.text = if (plan.used == 1) "Dar 1 caramelo" else "Dar ${plan.used} caramelos"
        }
        fun setAmount(v: Int, fromText: Boolean = false) {
            amount = v.coerceIn(1, maxUseful)
            if (!fromText || input.text.toString() != amount.toString()) {
                updating = true; input.setText(amount.toString()); input.setSelection(input.text.length); updating = false
            }
            render()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                if (updating) return
                val typed = e?.toString()?.toIntOrNull()
                if (typed == null) { amount = 1; render(); return }   // vacio: se cuenta como 1 mientras escribe (no se reescribe el campo)
                setAmount(typed, fromText = true)
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        fun stepButton(text: String, tag_: String, delta: Int) = Button(this).apply {
            this.text = text; textSize = 20f; isAllCaps = false; tag = tag_
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6); marginEnd = dp(6) }
            setOnClickListener { setAmount(amount + delta) }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(stepButton("−", "candy_minus", -1))
            addView(input)
            addView(stepButton("+", "candy_plus", +1))
            addView(Button(this@MainActivity).apply {
                text = "Máx."; isAllCaps = false; tag = "candy_max"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) }
                setOnClickListener { setAmount(maxUseful) }
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(6))
            addView(TextView(this@MainActivity).apply {
                text = "Tienes $have caramelos" + if (maxUseful < have) " · a $label solo le sirven $maxUseful (llega al nivel 100)" else ""
                textSize = 13f; setTextColor(secondary); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10))
            })
            addView(controls)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0)
                addView(levels); addView(levelNote); addView(bar); addView(xpLine)
            })
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Caramelos para $label")
            .setView(root)
            .setPositiveButton("Dar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        lastCandyAmountDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val use = PetState.useCandies(this, row.name, row.shiny, amount)
                dialog.dismiss()
                onConfirm(use)
            }
            render()
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.show()
    }
    /** SOLO PRUEBAS: el ultimo selector de caramelos abierto (ver [debugCandyFlow]). */
    private var lastCandyPicker: androidx.appcompat.app.AlertDialog? = null

    /** SOLO PRUEBAS (cuenta secundaria): recorre el camino REAL de la interfaz de los caramelos - mochila, boton "Dar un caramelo", selector y
     *  toque en las primeras filas - llamando a performClick de las propias vistas (no se inyecta ningun toque en el movil), vuelca cada paso a
     *  filesDir/debug_candy_*.png y deja en el log lo que pasa. Al terminar devuelve los caramelos y la XP a como estaban.
     *    adb shell am start -S --user 999 -n com.example.pokegotchi/.MainActivity --ez debug_candy true */
    private fun debugCandyFlow() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val sp = getSharedPreferences("pokegotchi_state", MODE_PRIVATE)
        val candiesBefore = PetState.candyCount(this)
        val xpBefore = PetState.ownedIndividuals(this).associateWith { (n, s) -> PetState.rawStats(this, n, s)?.xp ?: 0f }
        PetState.addCandies(this, 5)
        // El primero de la lista del selector (menor nivel, luego nombre) con algo de XP, para ver la barra de dos tonos (lo que llevaba + lo que ganaria).
        PetState.ownedIndividuals(this).mapNotNull { (n, s) -> PetState.levelForPokemon(this, n, s)?.takeIf { it < 100 }?.let { Triple(n, s, it) } }
            .minWithOrNull(compareBy({ it.third }, { it.first }))?.let { PetState.debugSetIndividualStats(this, it.first, it.second, xp = 3f) }
        dbg("caramelos (interfaz): $candiesBefore -> ${PetState.candyCount(this)} caramelos para la prueba; ${xpBefore.size} Pokemon")
        fun findView(v: View, match: (View) -> Boolean): View? {
            if (match(v)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) findView(v.getChildAt(i), match)?.let { return it }
            return null
        }
        val bag = showBagDialog()
        dumpDialogView(bag, "debug_candy_bag.png", 800)
        h.postDelayed({
            val btn = bag.window?.decorView?.let { dv -> findView(dv) { it is Button && it.text.toString().startsWith("Dar un caramelo") } }
            dbg("caramelos (interfaz): boton de la mochila ${if (btn != null) "encontrado" else "NO encontrado"}")
            btn?.performClick()
        }, 1200)
        h.postDelayed({
            val picker = lastCandyPicker
            if (picker == null || !picker.isShowing) { dbg("caramelos (interfaz): el selector NO se abrio"); return@postDelayed }
            val rv = picker.window?.decorView?.let { dv -> findView(dv) { it is RecyclerView } } as? RecyclerView
            dbg("caramelos (interfaz): selector abierto con ${rv?.adapter?.itemCount} Pokemon")
            dumpDialogView(picker, "debug_candy_picker0.png", 300)
            // 1) toque en la primera fila: con 5 caramelos se abre el dialogo de cantidad (por defecto 1)
            h.postDelayed({
                val vh = rv?.findViewHolderForAdapterPosition(0)
                vh?.itemView?.performClick()
                dbg("caramelos (interfaz): toque en la primera fila ${if (vh != null) "hecho" else "SIN fila"}; dialogo de cantidad ${if (lastCandyAmountDialog?.isShowing == true) "abierto" else "NO abierto"}")
            }, 1200L)
            h.postDelayed({ lastCandyAmountDialog?.let { dumpDialogView(it, "debug_candy_amount1.png", 100) } }, 2000L)
            // 2) +, + (3 caramelos): la vista previa cambia
            h.postDelayed({
                val dv = lastCandyAmountDialog?.window?.decorView
                val plus = dv?.let { findView(it) { v -> v.tag == "candy_plus" } }
                plus?.performClick(); plus?.performClick()
                val amount = (dv?.let { findView(it) { v -> v.tag == "candy_amount" } } as? EditText)?.text
                val line = (dv?.let { findView(it) { v -> v.tag == "candy_xp_line" } } as? TextView)?.text
                val lv = (dv?.let { findView(it) { v -> v.tag == "candy_levels" } } as? TextView)?.text
                dbg("caramelos (interfaz): tras + + la cantidad es '$amount' | $lv | $line")
            }, 2600L)
            h.postDelayed({ lastCandyAmountDialog?.let { dumpDialogView(it, "debug_candy_amount3.png", 100) } }, 3300L)
            // 3) escribir una cantidad concreta (4) y un pasado de maximo (99 -> se queda en los 5 que hay)
            h.postDelayed({
                val dv = lastCandyAmountDialog?.window?.decorView
                val input = dv?.let { findView(it) { v -> v.tag == "candy_amount" } } as? EditText
                input?.setText("99")
                dbg("caramelos (interfaz): escribir 99 -> '${input?.text}'")
                input?.setText("4")
                dbg("caramelos (interfaz): escribir 4 -> '${input?.text}'")
            }, 3800L)
            // 4) confirmar con el boton "Dar"
            h.postDelayed({
                val btn = lastCandyAmountDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                dbg("caramelos (interfaz): boton '${btn?.text}' (caramelos antes=${PetState.candyCount(this)})")
                btn?.performClick()
                dbg("caramelos (interfaz): tras confirmar quedan ${PetState.candyCount(this)}")
            }, 4500L)
            dumpDialogView(picker, "debug_candy_picker1.png", 5600)
        }, 2600)
        h.postDelayed({
            // devolver todo como estaba
            var restored = 0
            for ((m, xp) in xpBefore) if ((PetState.rawStats(this, m.first, m.second)?.xp ?: 0f) != xp) { PetState.debugSetIndividualStats(this, m.first, m.second, xp = xp); restored++ }
            sp.edit().putInt("candy_count", candiesBefore).commit()
            lastCandyPicker?.dismiss()
            showLevel()
            dbg("caramelos (interfaz): restaurado ($restored Pokemon recuperan su XP, caramelos=${PetState.candyCount(this)})")
        }, 8500)
    }

    // ==================== GUARDERIA ====================
    // Se abre con el botón 🌱 de la cabecera (visible desde el nivel de entrenador que la desbloquea).
    // Es una escena a PANTALLA COMPLETA (NurseryView): los Pokémon de la guardería andan sueltos por el
    // jardín, con bebedero, manzanas y pelota. Encima solo hay tres botones: ✕ cerrar, el título con las
    // plazas y ⚙ ajustes (showNurserySettings: meter/sacar Pokémon, huevos de cada uno y avisos de huevos).
    // Tocar a un Pokémon enseña sus tres necesidades; las cubre él solo y gana XP por ello (ver
    // PetState.simulateNurseryCare). Además gana XP por tiempo e incuba sus propios huevos (settleNursery).

    private fun showNurseryDialog(preview: Boolean = false, forceTip: Boolean = false) {
        val firstReport = PetState.settleNursery(this)   // que lo que se ve ya cuente lo ocurrido hasta ahora
        // Vista previa (adb --ez debug_nursery_preview true): si la guarderia esta vacia, mete unos Pokemon
        // unos segundos para que se vea la escena con gente; se vuelven a sacar al terminar.
        val previewAdded = ArrayList<Pair<String, Boolean>>()
        if (preview && PetState.nurseryMembers(this).isEmpty()) {
            PetState.ownedIndividuals(this).filter { (n, s) -> PetState.nurseryBlockReason(this, n, s) == null }
                .take(PetState.nurseryCapacity(this).coerceAtMost(3)).forEach { c ->
                    if (PetState.nurseryAdd(this, c.first, c.second) == null) previewAdded.add(c)
                }
        }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun pill(radiusDp: Int = 22) = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x99000000.toInt()); cornerRadius = dp(radiusDp).toFloat()
        }
        val loop = android.os.Handler(android.os.Looper.getMainLooper())

        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog.window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)   // la escena llega hasta los bordes
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val lp = w.attributes
                lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                w.attributes = lp
            }
        }
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#1B1822")) }
        val scene = NurseryView(this)
        root.addView(scene, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))

        // --- barra superior: ✕ | título | ⚙
        fun roundButton(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = pill()
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }
        val titleView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = pill()
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        // --- tarjeta de estado de un Pokemon (al tocarlo)
        val infoName = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }
        fun bar(color: String): android.widget.ProgressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A4658"))
            layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f).apply { marginStart = dp(8) }
        }
        val infoHealth = bar("#E5604D"); val infoHappy = bar("#F2C14E"); val infoHygiene = bar("#4FA8E8")
        fun infoRow(label: String, b: android.widget.ProgressBar) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(TextView(this@MainActivity).apply { text = label; textSize = 12.5f; setTextColor(Color.WHITE); minWidth = dp(86) })
            addView(b)
        }
        // Si el Pokemon ya puede evolucionar, la tarjeta lo dice y ofrece hacerlo; evoluciona sin salir de la guarderia.
        val infoEvolve = TextView(this).apply {
            text = "✨ ¡Puede evolucionar!  ·  Evolucionar"
            textSize = 14f
            setTextColor(Color.parseColor("#3A2A00"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFD54A.toInt()); cornerRadius = dp(14).toFloat() }
            setPadding(dp(12), dp(9), dp(12), dp(9))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val infoRows = listOf(infoRow("🍎 Vida", infoHealth), infoRow("🎾 Felicidad", infoHappy), infoRow("💧 Higiene", infoHygiene))
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(16).apply { setColor(0xDD1B1822.toInt()) }
            setPadding(dp(16), dp(12), dp(16), dp(14))
            visibility = View.GONE
            addView(infoName)
            infoRows.forEach { addView(it) }
            addView(infoEvolve)
        }
        val infoLp = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(dp(20), 0, dp(20), dp(20)) }
        var infoShown: Pair<String, Boolean>? = null
        val hideInfo = Runnable { infoCard.visibility = View.GONE; infoShown = null }
        fun fillInfo(n: String, s: Boolean) {
            val st = PetState.rawStats(this, n, s) ?: return
            infoRows.forEach { it.visibility = View.VISIBLE }   // por si la tarjeta era de un visitante
            infoName.text = PetState.displayLabel(n).replaceFirstChar { it.uppercase() } + (if (s) " ✨" else "") + "  ·  Nv ${PetState.levelForPokemon(this, n, s) ?: 1}"
            infoHealth.progress = st.health.toInt(); infoHappy.progress = st.happiness.toInt(); infoHygiene.progress = st.hygiene.toInt()
            val canEvolve = PetState.evolutionReadyFor(this, n, s) != null
            infoEvolve.visibility = if (canEvolve) View.VISIBLE else View.GONE
        }
        infoCard.setOnClickListener { loop.removeCallbacks(hideInfo); hideInfo.run() }
        infoEvolve.setOnClickListener {
            val (n, s) = infoShown ?: return@setOnClickListener
            val pending = PetState.evolutionReadyFor(this, n, s) ?: return@setOnClickListener
            dialog.dismiss()   // la animacion de evolucion se ve sobre la pantalla principal, no bajo la escena
            // Evoluciona SOLO porque el jugador lo ha pedido, se queda en la guarderia y se vuelve a abrir para verlo ya evolucionado.
            val back = { showNurseryDialog() }
            if (pending.evolvesTo.size == 1) doEvolve(n, pending.evolvesTo.first(), s, back)
            else showEvolutionChoiceDialog(n, pending.evolvesTo, s, back)
        }

        fun refreshTitle() {
            titleView.text = "🌱 Guardería · ${PetState.nurseryMembers(this).size}/${PetState.nurseryCapacity(this)}"
        }
        // Visitantes: Pokemon que NO son tuyos y andan de paso (nivel parecido a los de dentro, cambian cada 15 min).
        fun applyVisitors() {
            val visitors = PetState.nurseryVisitors(this, accept = { n ->
                !DungeonSpriteRepository.frames(this, n, false, DungeonSpriteRepository.Direction.DOWN).isNullOrEmpty()   // solo los que se pueden dibujar
            })
            scene.setVisitors(visitors.map { it.name to it.level })
        }
        fun applyMembers() {
            scene.setMembers(PetState.nurseryMembers(this))
            applyVisitors()   // dependen de los niveles de los de dentro
            refreshTitle()
        }
        topBar.addView(roundButton("✕") { dialog.dismiss() })
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(titleView)
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val gearButton = roundButton("⚙") { showNurserySettings(onChanged = { applyMembers() }) }
        topBar.addView(gearButton)
        root.addView(topBar, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(infoCard, infoLp)
        // Respetar muescas/barras: la barra superior y la tarjeta se apartan de ellas.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(12) + bars.left, dp(12) + bars.top, dp(12) + bars.right, 0)
            infoLp.bottomMargin = dp(20) + bars.bottom
            infoCard.layoutParams = infoLp
            insets
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)

        // Lo que la guarderia liquide mientras la escena esta abierta (o lo cubierto durante la ausencia) se
        // representa en el jardin: el Pokemon va a la manzana / pelota / bebedero.
        fun playEvents(report: PetState.NurseryReport?) {
            report?.careEvents?.forEach { scene.queueCare(it.species, it.shiny, it.action) }
        }
        scene.onVisitorTap = { n, lvl ->
            // Solo el nombre y el nivel (pedido del usuario: no hace falta avisar de cuando se va).
            infoShown = null
            infoName.text = "Visitante  ·  " + PetState.displayLabel(n).replaceFirstChar { it.uppercase() } + "  ·  Nv $lvl"
            infoRows.forEach { it.visibility = View.GONE }
            infoEvolve.visibility = View.GONE
            infoCard.visibility = View.VISIBLE
            loop.removeCallbacks(hideInfo)
            loop.postDelayed(hideInfo, 5000L)
        }
        scene.onCritterTap = { n, s ->
            fillInfo(n, s)
            infoShown = n to s
            infoCard.visibility = View.VISIBLE
            loop.removeCallbacks(hideInfo)
            // Con el boton de evolucionar dentro, mas tiempo para decidir (si no, se iria justo al ir a tocarlo).
            loop.postDelayed(hideInfo, if (infoEvolve.visibility == View.VISIBLE) 10_000L else 5000L)
        }
        // El bebedero y la cesta se arrastran por el jardin y se quedan donde los dejes; la pelota se lanza.
        // Con mas Pokemon salen una cesta y un bebedero mas ("trough2", "basket2"): tambien se arrastran y se recuerda donde los dejas.
        scene.furnitureSaved = { key -> PetState.nurseryFurniturePos(this, key) }
        scene.setFurniturePositions(PetState.nurseryFurniturePos(this, "trough"), PetState.nurseryFurniturePos(this, "basket"))
        scene.onFurnitureMoved = { kind, fx, fy -> PetState.setNurseryFurniturePos(this, kind, fx, fy) }
        val tick = object : Runnable {
            override fun run() {
                playEvents(PetState.settleNursery(this@MainActivity, force = true))
                applyVisitors()   // al cambiar de franja (cada 15 min) se van unos y llegan otros
                infoShown?.let { (n, s) -> if (infoCard.visibility == View.VISIBLE) fillInfo(n, s) }
                loop.postDelayed(this, 15_000L)
            }
        }
        dialog.setOnDismissListener {
            scene.stop(); loop.removeCallbacksAndMessages(null); showLevel()
            adapter.notifyDataSetChanged()   // la cuadricula marca en rosa a los que estan en la guarderia
        }
        applyMembers()
        playEvents(firstReport)
        dialog.show()
        // Pantalla completa de verdad: fuera tambien la barra de navegacion (reaparece al deslizar desde el borde).
        dialog.window?.let { w ->
            val c = androidx.core.view.WindowInsetsControllerCompat(w, root)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        scene.start()
        loop.postDelayed(tick, 15_000L)
        if (!preview) {
            // Pista sobre la propia escena (en la ventana del dialogo): resalta el ⚙ y explica el resto.
            val nurseryTargets = { id: String -> when (id) { "gear" -> gearButton; "title" -> titleView; else -> null } }
            if (forceTip) TutorialTips.show(this, TutorialTips.Tip.NURSERY, force = true, host = dialog.window, resolve = nurseryTargets)
            else TutorialTips.showPending(this, TutorialTips.Tip.NURSERY, host = dialog.window, resolve = nurseryTargets)
        }
        if (preview && intent?.getBooleanExtra("debug_nursery_tag", false) == true) {
            // --ez debug_nursery_tag true: fuerza una partida de pilla-pilla y vuelca la escena en tres momentos; deja en el log los pillados.
            loop.postDelayed({ dbg("guarderia: pilla-pilla forzado con ${scene.debugForceTag()} jugadores") }, 2000L)
            dumpDialogView(dialog, "debug_nursery_tag_s.png", 2500)   // justo al empezar: el "!" de quien la lleva
            dumpDialogView(dialog, "debug_nursery_tag_0.png", 3500)
            dumpDialogView(dialog, "debug_nursery_tag_1.png", 6500)
            dumpDialogView(dialog, "debug_nursery_tag_2.png", 10500)
            for (ms in 2300..9300 step 700) loop.postDelayed({ dbg("guarderia: pilla-pilla a los ${(ms - 2000) / 1000.0} s: ${scene.debugTagStats()} ${scene.debugTagState()}") }, ms.toLong())
            loop.postDelayed({ previewAdded.forEach { PetState.nurseryRemove(this, it.first, it.second) }; dialog.dismiss() }, 12000L)
        } else if (preview && intent?.getBooleanExtra("debug_nursery_visitors", false) == true) {
            // --ez debug_nursery_visitors true: cambia a los visitantes y vuelca la escena mientras unos andan hacia la puerta de la casa y entran
            // y otros salen por ella y bajan por el camino; deja en el log donde esta cada uno.
            loop.removeCallbacks(tick)   // el tick de 15 s volveria a poner a los visitantes de verdad en mitad de la prueba
            loop.postDelayed({ dbg("guarderia: cambio de visitantes (llegan ${scene.debugChurnVisitors()}) - ${scene.debugVisitorState()}") }, 2000L)
            dumpDialogView(dialog, "debug_nursery_visitors_0.png", 3000)
            dumpDialogView(dialog, "debug_nursery_visitors_1.png", 5500)
            dumpDialogView(dialog, "debug_nursery_visitors_2.png", 8000)
            dumpDialogView(dialog, "debug_nursery_visitors_3.png", 11000)
            dumpDialogView(dialog, "debug_nursery_visitors_4.png", 13000)   // ya han salido los de dentro y salen los nuevos
            dumpDialogView(dialog, "debug_nursery_visitors_5.png", 14500)
            dumpDialogView(dialog, "debug_nursery_visitors_6.png", 16500)
            for (ms in 2500..20500 step 1500) loop.postDelayed({ dbg("guarderia: a los ${(ms - 2000) / 1000.0} s - ${scene.debugVisitorState()}") }, ms.toLong())
            loop.postDelayed({ previewAdded.forEach { PetState.nurseryRemove(this, it.first, it.second) }; dialog.dismiss() }, 22000L)
        } else if (preview && intent?.getBooleanExtra("debug_nursery_gather", false) == true) {
            // --ez debug_nursery_gather true: prueba de "varios toques en un punto llaman a mis Pokemon" con toques sinteticos solo a la
            // propia vista; vuelca la escena antes y despues y deja en el log cuanto se acercan.
            var tx = 0.5f; var ty = 0.78f
            var before = 0f
            dumpDialogView(dialog, "debug_nursery_gather_0.png", 2300)
            loop.postDelayed({
                scene.debugFreeSpot()?.let { tx = it.first; ty = it.second }   // suelo vacio (un toque sobre un Pokemon abre su tarjeta)
                before = scene.debugMeanDistance(tx, ty); scene.debugTap(tx, ty, 3)
            }, 2500L)
            dumpDialogView(dialog, "debug_nursery_gather_1.png", 2800)   // la onda del toque (y los visitantes, que no acuden)
            dumpDialogView(dialog, "debug_nursery_gather_2.png", 8000)   // ya reunidos
            loop.postDelayed({
                dbg("guarderia: llamada con 3 toques en (${"%.2f".format(tx)}, ${"%.2f".format(ty)}) - distancia media al punto: antes ${before.toInt()} px, despues ${scene.debugMeanDistance(tx, ty).toInt()} px; por Pokemon: ${scene.debugDistances(tx, ty)}")
            }, 8100L)
            loop.postDelayed({ previewAdded.forEach { PetState.nurseryRemove(this, it.first, it.second) }; dialog.dismiss() }, 11500L)
        } else if (preview) {
            // Con los Pokemon de muestra, una necesidad real de cada tipo en marcha (solo visual) para ver las
            // nubes y las acciones; volcados de la escena, de la tarjeta, de los ajustes y del selector.
            scene.debugQueueSamples()
            dumpDialogView(dialog, "debug_nursery_scene_0.png", 2500)
            // Arrastre con eventos sinteticos (solo a la vista): bebedero y cesta a otro sitio y la pelota lanzada
            // hacia el fondo; luego se restaura la disposicion que tuviera el jugador.
            val troughBefore = PetState.nurseryFurniturePos(this, "trough"); val basketBefore = PetState.nurseryFurniturePos(this, "basket")
            loop.postDelayed({
                scene.debugDrag("trough", 0.30f, 0.80f)
                scene.debugDrag("basket", 0.72f, 0.74f)
            }, 3200L)
            loop.postDelayed({ scene.debugDrag("ball", 0.28f, 0.55f) }, 3800L)
            dumpDialogView(dialog, "debug_nursery_scene_2.png", 4600)
            dumpDialogView(dialog, "debug_nursery_scene_1.png", 6500)
            loop.postDelayed({
                if (intent?.getBooleanExtra("debug_visitor_tap", false) == true) {
                    // --ez debug_visitor_tap true: en vez de un miembro, se toca a un visitante (para ver su tarjeta).
                    PetState.nurseryVisitors(this).firstOrNull()?.let { scene.onVisitorTap?.invoke(it.name, it.level) }
                } else {
                    // Si ya habia gente en la guarderia, se toca al primero que pueda evolucionar (para ver su tarjeta con el boton).
                    (previewAdded.firstOrNull() ?: PetState.nurseryEvolvable(this).firstOrNull() ?: PetState.nurseryMembers(this).firstOrNull())
                        ?.let { scene.onCritterTap?.invoke(it.first, it.second) }
                }
            }, 8000L)
            dumpDialogView(dialog, "debug_nursery_info.png", 9500)
            loop.postDelayed({
                val settings = showNurserySettings(onChanged = { applyMembers() }, previewDump = "debug_nursery_settings.png")
                loop.postDelayed({ settings.dismiss() }, 4500L)
            }, 11000L)
            loop.postDelayed({
                val picker = showNurseryPicker({ applyMembers() }, previewDump = "debug_nursery_picker.png")
                loop.postDelayed({
                    picker.dismiss()
                    // Segundo volcado del selector con la busqueda/filtros aplicados (ponen huevos, nivel de mayor a menor).
                    val filtered = showNurseryPicker({ applyMembers() }, previewDump = "debug_nursery_picker_filtered.png", previewState = "layers")
                    loop.postDelayed({ filtered.dismiss() }, 4000L)
                }, 4000L)
            }, 17000L)
            loop.postDelayed({
                previewAdded.forEach { PetState.nurseryRemove(this, it.first, it.second) }
                if (troughBefore != null) PetState.setNurseryFurniturePos(this, "trough", troughBefore.first, troughBefore.second) else PetState.clearNurseryFurniturePos(this, "trough")
                if (basketBefore != null) PetState.setNurseryFurniturePos(this, "basket", basketBefore.first, basketBefore.second) else PetState.clearNurseryFurniturePos(this, "basket")
                dialog.dismiss()
                dbg("guarderia: vista previa terminada (${previewAdded.size} de muestra vueltos a sacar)")
            }, 23000L)
        }
    }

    /** Ajustes de la guardería (botón ⚙): quién está dentro (meter/sacar), si cada uno pone huevos, y cómo
     *  avisan los huevos. Al cerrarlo se llama a [onChanged] para que la escena refleje los cambios. Devuelve
     *  el diálogo (para poder cerrarlo desde las pruebas). */
    private fun showNurserySettings(onChanged: () -> Unit, previewDump: String? = null): androidx.appcompat.app.AlertDialog {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val secondary = dialogSecondaryTextColor()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚙ Ajustes de la guardería")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Cerrar", null)
            .create()
        dialog.setOnDismissListener { loader.shutdown(); showLevel(); onChanged() }

        fun bar(color: String): android.widget.ProgressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A4658"))
            layoutParams = LinearLayout.LayoutParams(0, dp(7), 1f).apply { marginStart = dp(8) }
        }

        fun rebuild() {
            root.removeAllViews()
            val members = PetState.nurseryMembers(this)
            val capacity = PetState.nurseryCapacity(this)
            val next = PetState.nurseryNextSlotLevel(this)
            root.addView(TextView(this).apply {
                text = "Plazas: ${members.size} / $capacity" +
                    (if (next != null) "  ·  la siguiente, en el nivel de entrenador $next" else "  ·  ¡todas desbloqueadas!")
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })
            root.addView(TextView(this).apply {
                val xpH = PetState.nurseryXpPerHour().toInt()
                text = "Cada Pokémon gana $xpH XP por hora y tiene las mismas necesidades que cuando lo cuidas: " +
                    "come de la cesta de manzanas (vida), juega con la pelota (felicidad) y bebe del bebedero (higiene) cuando le hace falta, " +
                    "y gana +5 XP cada vez. Si puede poner huevos, los incuba él solo: los shiny se quedan contigo y el resto " +
                    "se convierte en caramelos. No puede explorar la mazmorra mientras está dentro. " +
                    "En el jardín puedes arrastrar el bebedero y la cesta para cambiarlos de sitio, y lanzar la pelota: irán a por ella."
                textSize = 12.5f
                setTextColor(secondary)
                setPadding(0, dp(6), 0, dp(10))
            })
            for ((n, s) in members) {
                val st = PetState.rawStats(this, n, s)
                val img = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                loader.execute {
                    val bmp = SpriteRepository.localFirstFrameScaled(this, n, s, PetState.spriteScaleMode(this))
                    if (bmp != null) runOnUiThread { img.setImageBitmap(bmp) }
                }
                fun barRow(label: String, value: Float, color: String) = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, 0)
                    addView(TextView(this@MainActivity).apply { text = label; textSize = 11.5f; minWidth = dp(76) })
                    addView(bar(color).apply { progress = value.toInt() })
                }
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = PetState.displayLabel(n).replaceFirstChar { it.uppercase() } + (if (s) " ✨" else "") +
                            "  ·  Nv ${PetState.levelForPokemon(this@MainActivity, n, s) ?: 1}"
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    if (st != null) {
                        addView(barRow("🍎 Vida", st.health, "#E5604D"))
                        addView(barRow("🎾 Felicidad", st.happiness, "#F2C14E"))
                        addView(barRow("💧 Higiene", st.hygiene, "#4FA8E8"))
                    }
                    // Poner huevos (como en la ficha): solo si esta en su ultima fase y asi puede poner.
                    if (PetState.isFinalStage(this@MainActivity, n)) {
                        addView(android.widget.CheckBox(this@MainActivity).apply {
                            text = "🥚 Pone huevos"
                            textSize = 12.5f
                            isChecked = PetState.eggLayingEnabled(this@MainActivity, n)
                            setOnCheckedChangeListener { _, checked -> PetState.setEggLayingEnabled(this@MainActivity, n, checked) }
                        })
                    }
                }
                root.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    addView(img)
                    addView(info)
                    addView(Button(this@MainActivity).apply {
                        text = "Sacar"
                        isAllCaps = false
                        setOnClickListener {
                            PetState.nurseryRemove(this@MainActivity, n, s)
                            Toast.makeText(this@MainActivity, "${PetState.displayLabel(n)} sale de la guardería", Toast.LENGTH_SHORT).show()
                            showLevel()
                            rebuild()
                        }
                    })
                })
            }
            if (members.isEmpty()) {
                root.addView(TextView(this).apply {
                    text = "La guardería está vacía. Mete a alguien y se pondrá a jugar en el jardín."
                    textSize = 13f
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            if (members.size < capacity) {
                root.addView(Button(this).apply {
                    text = "＋ Añadir un Pokémon (${capacity - members.size} libre${if (capacity - members.size == 1) "" else "s"})"
                    isAllCaps = false
                    setOnClickListener { showNurseryPicker(onChanged = { rebuild() }) }
                })
            }
            root.addView(TextView(this).apply {
                text = "Avisos de huevos"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(2))
            })
            val group = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
            val always = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = "Siempre (un resumen por tanda; los shiny avisan aparte)"
                textSize = 13f
            }
            val onlyShiny = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = "Solo cuando sale un shiny"
                textSize = 13f
            }
            group.addView(always); group.addView(onlyShiny)
            group.check(if (PetState.nurseryNotifyAlways(this)) always.id else onlyShiny.id)
            group.setOnCheckedChangeListener { _, checked -> PetState.setNurseryNotifyAlways(this, checked == always.id) }
            root.addView(group)
        }
        rebuild()
        dialog.show()
        previewDump?.let { dumpDialogView(dialog, it, 1800) }
        return dialog
    }

    /** Elegir a quién meter en la guardería: cuadrícula como la de la mazmorra (3 por fila, con sprite y nivel) y,
     *  porque con muchos Pokémon cuesta encontrar a alguien, búsqueda por nombre, orden (nivel ▲/▼ o nombre) y
     *  filtros ("Disponibles", "Ponen huevos", "Shiny"). Los que no pueden entrar se ven en gris con el motivo
     *  y van al final. Al elegir a alguien, entra y el selector se cierra. */
    private fun showNurseryPicker(onChanged: () -> Unit, previewDump: String? = null, previewState: String? = null): androidx.appcompat.app.AlertDialog {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        class Cand(val name: String, val shiny: Boolean, val level: Int, val reason: String?, val layer: Boolean)
        val all = PetState.ownedIndividuals(this).mapNotNull { (n, s) ->
            if (PetState.isInNursery(this, n, s)) return@mapNotNull null
            val lvl = PetState.levelForPokemon(this, n, s) ?: return@mapNotNull null
            Cand(n, s, lvl, PetState.nurseryBlockReason(this, n, s), PetState.isFinalStage(this, n) && PetState.eggLayingEnabled(this, n))
        }
        val sortLabels = listOf("Nivel ▲", "Nivel ▼", "Nombre A-Z")
        // Orden (0 = nivel ▲ - los que van por detrás primero -, 1 = nivel ▼, 2 = nombre) y filtros: se recuerdan entre aperturas y
        // entre arranques de la app. Lo que se escribe en el buscador no.
        var sortMode = UiPrefs.int(this, UiPrefs.NURSERY_SORT, 0).takeIf { it in sortLabels.indices } ?: 0
        var query = ""
        var onlyFree = UiPrefs.bool(this, UiPrefs.NURSERY_ONLY_FREE, false)
        var onlyLayers = UiPrefs.bool(this, UiPrefs.NURSERY_ONLY_LAYERS, false)
        var onlyShiny = UiPrefs.bool(this, UiPrefs.NURSERY_ONLY_SHINY, false)
        // Solo para la vista previa de pruebas: arrancar con un filtro y un orden ya puestos (y NO tocar lo guardado).
        if (previewState == "layers") { onlyLayers = true; onlyFree = false; onlyShiny = false; sortMode = 1 }
        val remember = previewState == null

        fun filtered(): List<Cand> {
            val q = query.trim().lowercase()
            val list = all.filter { c ->
                (q.isEmpty() || c.name.contains(q) || PetState.displayLabel(c.name).lowercase().contains(q)) &&
                    (!onlyFree || c.reason == null) && (!onlyLayers || c.layer) && (!onlyShiny || c.shiny)
            }
            return when (sortMode) {
                0 -> list.sortedWith(compareBy({ it.reason != null }, { it.level }, { it.name }))
                1 -> list.sortedWith(compareBy({ it.reason != null }, { -it.level }, { it.name }))
                else -> list.sortedWith(compareBy({ it.reason != null }, { PetState.displayLabel(it.name) }))
            }
        }
        fun shortReason(r: String) = when {
            r.contains("activo") -> "🏠 activo"
            r.contains("mazmorra") -> "🗺️ mazmorra"
            r.contains("Ya está") -> "🌱 dentro"
            r.contains("plazas") -> "sin plazas"
            else -> "no disponible"
        }

        var rows = filtered()
        val sprites = android.util.LruCache<String, Bitmap>(120)
        val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val countText = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#B8B0C8")); setPadding(dp(2), dp(6), 0, dp(4)) }
        val rv = RecyclerView(this)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val cell = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#332F3B"))
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                }
                cell.addView(ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
                cell.addView(TextView(this@MainActivity).apply { textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1 })
                cell.addView(TextView(this@MainActivity).apply { textSize = 10f; gravity = Gravity.CENTER; maxLines = 1 })
                return object : RecyclerView.ViewHolder(cell) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val c = rows[position]
                val cell = holder.itemView as LinearLayout
                val img = cell.getChildAt(0) as ImageView
                val name = cell.getChildAt(1) as TextView
                val sub = cell.getChildAt(2) as TextView
                name.text = PetState.displayLabel(c.name) + (if (c.shiny) " ✨" else "")
                sub.text = c.reason?.let { shortReason(it) } ?: ("Nv. ${c.level}" + if (c.layer) " 🥚" else "")
                sub.setTextColor(if (c.reason != null) Color.parseColor("#E0A521") else Color.parseColor("#B8B0C8"))
                cell.alpha = if (c.reason != null) 0.5f else 1f
                val key = "${c.name}|${c.shiny}"
                img.tag = key
                img.setImageBitmap(sprites.get(key))
                if (sprites.get(key) == null) {
                    loader.execute {
                        val bmp = SpriteRepository.localFirstFrameScaled(this@MainActivity, c.name, c.shiny, PetState.spriteScaleMode(this@MainActivity))
                        if (bmp != null) {
                            sprites.put(key, bmp)
                            runOnUiThread { if (img.tag == key) img.setImageBitmap(bmp) }
                        }
                    }
                }
                cell.setOnClickListener {
                    if (c.reason != null) {
                        Toast.makeText(this@MainActivity, c.reason, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val err = PetState.nurseryAdd(this@MainActivity, c.name, c.shiny)
                    if (err != null) {
                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "${PetState.displayLabel(c.name)} entra en la guardería", Toast.LENGTH_SHORT).show()
                        showLevel()
                        dialog.dismiss()
                        onChanged()
                    }
                }
            }
        }
        fun refresh() {
            rows = filtered()
            adapter.notifyDataSetChanged()
            countText.text = if (rows.isEmpty()) "Ningún Pokémon coincide · prueba con otro filtro" else "${rows.size} Pokémon"
            rv.scrollToPosition(0)
        }
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
                override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString().orEmpty(); refresh() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        val sortChip = chip(sortLabels[sortMode], selected = false) { v ->
            sortMode = (sortMode + 1) % sortLabels.size
            (v as TextView).text = sortLabels[sortMode]
            if (remember) UiPrefs.putInt(this, UiPrefs.NURSERY_SORT, sortMode)
            refresh()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#15131A"))
            setPadding(dp(10), dp(8), dp(10), dp(4))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(search)
                addView(sortChip.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
            })
            addView(android.widget.HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(8), 0, 0)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(chip("🌱 Disponibles", onlyFree) { v -> onlyFree = !onlyFree; if (remember) UiPrefs.putBool(this@MainActivity, UiPrefs.NURSERY_ONLY_FREE, onlyFree); v.setBackgroundColor(Color.parseColor(if (onlyFree) "#EE1515" else "#332F3B")); refresh() })
                    addView(chip("🥚 Ponen huevos", onlyLayers) { v -> onlyLayers = !onlyLayers; if (remember) UiPrefs.putBool(this@MainActivity, UiPrefs.NURSERY_ONLY_LAYERS, onlyLayers); v.setBackgroundColor(Color.parseColor(if (onlyLayers) "#EE1515" else "#332F3B")); refresh() })
                    addView(chip("✨ Shiny", onlyShiny) { v -> onlyShiny = !onlyShiny; if (remember) UiPrefs.putBool(this@MainActivity, UiPrefs.NURSERY_ONLY_SHINY, onlyShiny); v.setBackgroundColor(Color.parseColor(if (onlyShiny) "#EE1515" else "#332F3B")); refresh() })
                })
            })
            addView(countText)
            addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.56f).toInt()))
        }
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        rv.adapter = adapter
        countText.text = "${rows.size} Pokémon"
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¿A quién metes en la guardería?")
            .setView(root)
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.setOnDismissListener { loader.shutdown(); refreshUnlockButtons() }
        dialog.show()
        previewDump?.let { dumpDialogView(dialog, it, 2500) }
        return dialog
    }

    /** Si el nivel de entrenador ha subido desde la ultima vez, lo dice (y cuenta lo que se
     *  desbloquea). La primera vez que se calcula NO avisa de nada (ver PetState.noteTrainerLevel). */
    private fun announceTrainerLevelUp() {
        val (newMax, from) = PetState.noteTrainerLevel(this)
        if (from == null) return
        val lines = ArrayList<String>()
        for (lvl in (from + 1)..newMax) lines.addAll(PetState.unlocksAtTrainerLevel(lvl))
        val msg = "¡Nivel de entrenador $newMax!" + if (lines.isNotEmpty()) "\n" + lines.joinToString("\n") { "🔓 $it" } else ""
        dbg("entrenador: sube de nivel $from -> $newMax (desbloquea: ${lines.joinToString(" | ")})")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Los botones de la cabecera que se desbloquean con el nivel de entrenador: 🎒 mochila (nivel 4) y
     *  🌱 guardería (nivel 6); ninguno lleva un numero a su lado (pedido del usuario: sobraba). */
    private fun refreshUnlockButtons() {
        findViewById<TextView>(R.id.btn_bag)?.let { btn ->
            val unlocked = PetState.candiesUnlocked(this)
            btn.visibility = if (unlocked) View.VISIBLE else View.GONE
            if (unlocked) {
                btn.text = "🎒"   // sin el numero de caramelos a su lado (pedido del usuario: sobraba)
                // ¡Mochila desbloqueada!: la primera vez se señala el boton.
                window.decorView.post { TutorialTips.showPending(this, TutorialTips.Tip.BACKPACK, resolve = ::tipTarget) }
            }
        }
        findViewById<TextView>(R.id.btn_nursery)?.let { btn ->
            val unlocked = PetState.nurseryUnlocked(this)
            btn.visibility = if (unlocked) View.VISIBLE else View.GONE
            if (unlocked) btn.text = "🌱"
        }
    }

    private fun loadAllMons() {
        val json = assets.open("pokedex.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        val list = ArrayList<Mon>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Mon(o.getInt("id"), o.getString("name"), o.getInt("gen")))
        }
        allMons = list
        maxGen = list.maxOfOrNull { it.gen } ?: 9
    }

    /** Tocar una especie abre su ficha - cual de las dos depende de la pestaña: en "Mis Pokémon"
     *  la ficha de siempre (estadisticas, cuidar, evolucionar...); en "Pokédex completa" una
     *  ficha SOLO informativa (sin nada de accion) - pedido explicito del usuario para que la
     *  Pokedex sea un catalogo de consulta, no la misma pantalla de cuidar. Desde la ficha
     *  informativa hay un boton para saltar a la interactiva si ya se tiene (ver
     *  showDetailDialog, parametro interactive). */
    private fun onPick(mon: Mon) {
        // Favoritos se comporta como Mis Pokemon (interactiva: son cosas que ya tienes) - solo
        // Pokedex completa es la ficha de solo consulta.
        showDetailDialog(mon, interactive = filterMode != FILTER_ALL)
    }

    /** Descarga el sprite de [mon] y lo pone como Pokemon activo del widget. [shiny] elige que
     *  individuo activar - el normal o el shiny (ver PetState.slot/hasIndividual) - la variante
     *  que se estuviera viendo en la ficha, no necesariamente la que hubiera antes. */
    private fun selectPokemon(mon: Mon, shiny: Boolean) {
        // Uno que esta en la guarderia o en la mazmorra no se puede poner en el widget (pedido explicito del usuario).
        if (PetState.isInNursery(this, mon.name, shiny)) {
            Toast.makeText(this, "${PetState.displayLabel(mon.name)} está en la guardería: sácalo de ahí para ponerlo en el widget", Toast.LENGTH_LONG).show()
            return
        }
        if (DungeonState.isInParty(this, mon.name, shiny) && !(mon.name == PetState.currentPokemon(this) && shiny == PetState.isActiveShiny(this))) {
            Toast.makeText(this, "${PetState.displayLabel(mon.name)} está en la mazmorra: no se puede cuidar hasta que salga", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val meta = SpriteRepository.ensure(
                this, mon.name, mon.id, PetState.currentStyle(this), shiny, PetState.spriteScaleMode(this)
            )
            runOnUiThread {
                if (meta != null) {
                    val prev = selected
                    dbg("activo: $prev -> ${mon.name} (shiny=$shiny)")
                    val leavesNursery = PetState.isInNursery(this, mon.name, shiny)   // setPokemon lo saca de la guarderia
                    PetState.setPokemon(this, mon.name, mon.id, shiny)
                    selected = mon.name
                    WidgetRefresh.updateWidgets(this)
                    showLevel()
                    adapter.refreshSelection(prev, mon.name)
                    Toast.makeText(
                        this,
                        "${PetState.displayLabel(mon.name)} está en el widget" + if (leavesNursery) " (ha salido de la guardería)" else "",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "Sin sprite animado: ${PetState.displayLabel(mon.name)}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Ficha detallada de un Pokemon YA desbloqueado: sprite grande, nivel, estadisticas
     * (activas si es el Pokemon actual, congeladas - "de la ultima vez" - si no), y su
     * informacion de evolucion. Si ya evoluciono (esta en "limbo"), se muestra en modo solo
     * lectura (sin botones de seleccionar/evolucionar). Aqui viven las acciones de
     * seleccionar/evolucionar (con eleccion de rama si hay varias, ej. Eevee).
     */
    private fun showDetailDialog(mon: Mon, preferShiny: Boolean? = null, interactive: Boolean = true) {
        val unlocked = PetState.isUnlocked(this, mon.name)
        // Normal/shiny coexistiendo (ver PetState.slot): esta especie puede tener a la vez un
        // individuo normal Y uno shiny, cada uno con su propio progreso - pedido explicito del
        // usuario tras el bug de esta noche (un huevo de una especie ya tenida pisaba el
        // individuo anterior en silencio). Si existen los dos, se ofrece un selector arriba;
        // [preferShiny] es solo para reabrir esta misma ficha ya en la otra variante (ver el
        // propio selector, mas abajo).
        val hasNormalIndiv = unlocked && PetState.hasIndividual(this, mon.name, false)
        val hasShinyIndiv = unlocked && PetState.hasIndividual(this, mon.name, true)
        val viewShiny = preferShiny ?: when {
            hasNormalIndiv && hasShinyIndiv -> if (mon.name == selected) PetState.isActiveShiny(this) else PetState.isShiny(this, mon.name)
            hasShinyIndiv -> true
            else -> false
        }
        val evolvedAway = unlocked && PetState.hasEvolvedAway(this, mon.name, viewShiny)
        // "Activo de verdad" para esta ficha: no basta con que la ESPECIE coincida con la
        // seleccionada - tiene que ser tambien la MISMA variante (si tienes normal Y shiny, y el
        // activo es el normal, ver la ficha del shiny no debe enseñar stats "en vivo" de otro
        // individuo distinto).
        val isActive = mon.name == selected && viewShiny == PetState.isActiveShiny(this)
        val stats = if (isActive) PetState.loadWithDecay(this) else PetState.rawStats(this, mon.name, viewShiny)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // Si esta Megaevolucionado ahora mismo, se enseña ESE sprite; si no, la forma actual
        // (Tatsugiri/Zygarde) o la especie tal cual (puramente visual - nivel/stats de mas abajo
        // siguen siendo los de la especie real, ver PetState.displaySpriteName).
        val spriteDisplayName = PetState.displaySpriteName(this, mon.name, viewShiny)
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(120))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        // En la ficha SOLO informativa (Pokedex, ver interactive) se enseña siempre el color de
        // referencia de la especie, sin distinguir variante - eso ya lo cuenta el boton de
        // mecanica "✨ Shiny" (ver addFormMechanicInfo) de forma explicita.
        loadLocalOrNetworkThumb(img, spriteDisplayName, unlocked && viewShiny && interactive) {
            img.load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(spriteDisplayName)}.png") {
                crossfade(true)
            }
        }
        // No conseguido: mismo tinte gris que en la rejilla de la Pokedex (ver DexAdapter), asi
        // se puede identificar de un vistazo pero sin verlo "a color" antes de tenerlo.
        if (!unlocked) img.setColorFilter(Color.parseColor("#707070"))
        root.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            addView(img)
        })

        root.addView(TextView(this).apply {
            text = "#${mon.id} ${PetState.displayLabel(mon.name)}"
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })
        val types = PetState.typesOf(this, mon.name, viewShiny)
        if (types.isNotEmpty()) {
            root.addView(typeChipRow(types).apply { setPadding(0, 0, 0, dp(4)) })
        }
        // Selector normal/shiny: solo si de verdad existen los DOS individuos de esta especie
        // (ver PetState.hasIndividual) - si solo hay uno, la ficha se ve exactamente igual que
        // siempre, sin ningun selector de por medio. Los listeners de verdad (que cierran esta
        // ficha y reabren la otra variante) se ponen mas abajo, junto al resto de botones de la
        // ficha - los dos necesitan la referencia a `dialog`, que aun no existe aqui.
        var toggleNormalBtn: Button? = null
        var toggleShinyBtn: Button? = null
        if (interactive && hasNormalIndiv && hasShinyIndiv) {
            fun tab(text: String, active: Boolean) = Button(this).apply {
                this.text = text
                isAllCaps = false
                textSize = 12f
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setBackgroundColor(Color.parseColor(if (active) "#EE1515" else "#DDDDDD"))
                setTextColor(if (active) Color.WHITE else Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            toggleNormalBtn = tab("Normal", !viewShiny).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4) }
            toggleShinyBtn = tab("✨ Shiny", viewShiny)
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6))
                addView(toggleNormalBtn)
                addView(toggleShinyBtn)
            })
        }
        // La personalidad (rasgos, ritmo de cuidados) solo tiene sentido una vez conseguido -
        // para lo no conseguido no hay individuo real detras todavia.
        // La personalidad es un rasgo FIJO de la especie (no de un individuo concreto - ver
        // PetState.personalityMultipliers), asi que tiene sentido enseñarla igual aunque no se
        // tenga todavia (pedido explicito del usuario: toda la info visible en la Pokedex).
        addPersonalityInfo(root, mon.name)
        if (PetState.hasIndividual(this, mon.name, viewShiny)) {
            root.addView(TextView(this).apply {
                text = "⏱️ ${formatCareHours(PetState.careHours(this@MainActivity, mon.name, viewShiny))} cuidándolo"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, dp(4))
            })
            // Favorito: pedido explicito del usuario, para poder filtrarlos aparte en la
            // pestaña Favoritos (ver isFavoriteSpecies/FILTER_FAVORITES). Por individuo (el
            // shiny y el normal de una misma especie se marcan por separado).
            lateinit var favBtn: Button
            fun favLabel(fav: Boolean) = if (fav) "❤️ Favorito" else "🤍 Marcar favorito"
            favBtn = Button(this).apply {
                text = favLabel(PetState.isFavorite(this@MainActivity, mon.name, viewShiny))
                isAllCaps = false
                setOnClickListener {
                    val newValue = !PetState.isFavorite(this@MainActivity, mon.name, viewShiny)
                    PetState.setFavorite(this@MainActivity, mon.name, viewShiny, newValue)
                    favBtn.text = favLabel(newValue)
                    if (filterMode == FILTER_FAVORITES) { refreshRows(); adapter.notifyDataSetChanged() }
                }
            }
            root.addView(favBtn)
            root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(6)) })
        }

        var selectBtn: Button? = null
        val evolveButtons = mutableListOf<Pair<PetState.EvoOption, Button>>()
        // Si hay VARIAS ramas (Eevee, Slowpoke, Pikachu/Raichu de Alola...) no se apila un boton
        // por opcion (con Eevee serian 8) - un solo boton abre un selector aparte con el sprite
        // de cada una, pedido explicito del usuario.
        var multiEvolveButton: Button? = null
        var multiEvolveOptions: List<PetState.EvoOption> = emptyList()
        var megaButton: Button? = null
        var megaOptions: List<PetState.MegaOption> = emptyList()
        var rotomFormButton: Button? = null
        var unlockableFormButton: Button? = null
        var unlockableFormSpecies: String = ""
        var decorativeFormButton: Button? = null
        var decorativeFormRootSpecies: String = ""
        var decorativeFormLabelFor: (String) -> String = { it.replaceFirstChar { c -> c.uppercase() } }
        var decorativeFormSpriteKeyFor: (String) -> String = { PetState.decorativeSpriteKey(decorativeFormRootSpecies, it) }

        // Info de forma/estado por especie (Rotom, Oricorio/Ogerpon/Genesect/Meloetta,
        // decorativas, automaticas, genero...): se guarda POR ESPECIE, no depende de si esta
        // activa ahora mismo en el widget - se muestra siempre que este conseguida, este activa
        // o no (antes solo se veia si era la activa, pedido explicito del usuario arreglarlo).
        fun addFormMechanicInfo() {
            if (mon.name == "rotom") {
                val current = PetState.ROTOM_FORMS.find { it.form == PetState.rotomForm(this, viewShiny) }?.label ?: "Rotom"
                root.addView(TextView(this).apply {
                    text = "🔌 Forma actual: $current"
                    setPadding(0, dp(10), 0, 0)
                })
                // El boton de cambiar solo tiene sentido si ya se tiene (si no, no hay nada que
                // cambiar todavia) - la info de arriba se ve igual, solo se oculta la accion.
                if (unlocked) {
                    rotomFormButton = Button(this).apply { text = "🔌 Cambiar de aparato"; isAllCaps = false }
                    root.addView(rotomFormButton)
                }
            }

            // Oricorio/Ogerpon/Genesect/Meloetta: mismo concepto que Rotom (desbloqueo por nivel,
            // cambio libre entre las ya desbloqueadas) - ver PetState.UNLOCKABLE_FORMS.
            if (mon.name == "oricorio" || mon.name == "ogerpon" || mon.name == "genesect" || mon.name == "meloetta") {
                val options = PetState.unlockableFormOptions(mon.name)
                val current = options.find { it.key == PetState.unlockableForm(this, mon.name, viewShiny) }?.label ?: mon.name
                root.addView(TextView(this).apply {
                    text = "🎭 Forma actual: $current"
                    setPadding(0, dp(10), 0, 0)
                })
                if (unlocked) {
                    unlockableFormSpecies = mon.name
                    unlockableFormButton = Button(this).apply { text = "🎭 Cambiar de forma"; isAllCaps = false }
                    root.addView(unlockableFormButton)
                }
            }

            // Formas automaticas/decorativas: solo informativo, no hay nada que elegir aqui.
            when (mon.name) {
                "burmy" -> root.addView(TextView(this).apply {
                    text = "🌿 Manto actual (según el fondo): " + when (PetState.burmyCloakForBg(PetState.currentBg(this@MainActivity))) {
                        "sandy" -> "Arenoso"; "trash" -> "Basura"; else -> "Vegetal"
                    }
                    setPadding(0, dp(10), 0, 0)
                })
                "wormadam" -> root.addView(TextView(this).apply {
                    text = "🌿 Manto: " + when (PetState.wormadamCloak(this@MainActivity)) {
                        "sandy" -> "Arenoso"; "trash" -> "Basura"; else -> "Vegetal"
                    } + " (fijo para siempre)"
                    setPadding(0, dp(10), 0, 0)
                })
                "darmanitan" -> root.addView(TextView(this).apply {
                    text = if ((stats?.health ?: 100f) < 50f) "🧘 Modo Zen activo (vida por debajo del 50%)"
                        else "😤 Modo Estándar (vida al 50% o más)"
                    setPadding(0, dp(10), 0, 0)
                })
                // Formas decorativas de verdad "propias": se sortean al azar de huevo, pero
                // pedido explicito del usuario, entre las YA conseguidas se puede elegir
                // libremente (igual que Rotom/Genesect entre sus formas por nivel) - de ahi el
                // boton "Cambiar forma" cuando hay mas de una conseguida (ver
                // PetState.selectDecorativeForm). Gimmighoul se queda FUERA a proposito (mas
                // abajo, rama aparte): en el juego real Cofre/Errante son fijos para siempre.
                "unown" -> {
                    val form = PetState.decorativeForm(this@MainActivity, "unown")
                    val label = when (form) { "em" -> "!"; "qm" -> "?"; else -> form.uppercase() }
                    root.addView(TextView(this).apply {
                        text = "🎲 Letra: $label (al azar de un huevo)"
                        setPadding(0, dp(10), 0, 0)
                    })
                    if (PetState.decorativeFormsOwned(this, "unown").size > 1) {
                        decorativeFormRootSpecies = "unown"
                        decorativeFormLabelFor = { f -> when (f) { "em" -> "!"; "qm" -> "?"; else -> f.uppercase() } }
                        decorativeFormSpriteKeyFor = { f -> PetState.decorativeSpriteKey("unown", f) }
                        decorativeFormButton = Button(this).apply { text = "🎲 Cambiar forma"; isAllCaps = false }
                        root.addView(decorativeFormButton)
                    }
                }
                "arceus", "silvally" -> {
                    val form = PetState.decorativeForm(this@MainActivity, mon.name)
                    root.addView(TextView(this).apply {
                        text = "🎲 Tipo: ${typeDisplayName(form)} (al azar de un huevo)"
                        setPadding(0, dp(10), 0, 0)
                    })
                    if (PetState.decorativeFormsOwned(this, mon.name).size > 1) {
                        decorativeFormRootSpecies = mon.name
                        decorativeFormLabelFor = { f -> typeDisplayName(f) }
                        decorativeFormSpriteKeyFor = { f -> PetState.decorativeSpriteKey(mon.name, f) }
                        decorativeFormButton = Button(this).apply { text = "🎲 Cambiar forma"; isAllCaps = false }
                        root.addView(decorativeFormButton)
                    }
                }
                "vivillon" -> {
                    val form = PetState.decorativeForm(this@MainActivity, "vivillon")
                    root.addView(TextView(this).apply {
                        text = "🎲 Patrón: ${vivillonPatternName(form)} (al azar de un huevo)"
                        setPadding(0, dp(10), 0, 0)
                    })
                    if (PetState.decorativeFormsOwned(this, "vivillon").size > 1) {
                        decorativeFormRootSpecies = "vivillon"
                        decorativeFormLabelFor = { f -> vivillonPatternName(f) }
                        decorativeFormSpriteKeyFor = { f -> PetState.decorativeSpriteKey("vivillon", f) }
                        decorativeFormButton = Button(this).apply { text = "🎲 Cambiar forma"; isAllCaps = false }
                        root.addView(decorativeFormButton)
                    }
                }
                "pikachu" -> {
                    val form = PetState.decorativeForm(this@MainActivity, "pikachu")
                    root.addView(TextView(this).apply {
                        text = "🎲 Disfraz: ${pikachuCostumeName(form)} (al azar de un huevo)"
                        setPadding(0, dp(10), 0, 0)
                    })
                    if (PetState.decorativeFormsOwned(this, "pikachu").size > 1) {
                        decorativeFormRootSpecies = "pikachu"
                        decorativeFormLabelFor = { f -> pikachuCostumeName(f) }
                        decorativeFormSpriteKeyFor = { f -> PetState.decorativeSpriteKey("pikachu", f) }
                        decorativeFormButton = Button(this).apply { text = "🎲 Cambiar forma"; isAllCaps = false }
                        root.addView(decorativeFormButton)
                    }
                }
                "alcremie" -> {
                    val form = PetState.decorativeForm(this@MainActivity, "alcremie")
                    root.addView(TextView(this).apply {
                        text = "🎲 Sabor: ${alcremieFlavorName(form)} (al azar de un huevo)"
                        setPadding(0, dp(10), 0, 0)
                    })
                    if (PetState.decorativeFormsOwned(this, "alcremie").size > 1) {
                        decorativeFormRootSpecies = "alcremie"
                        decorativeFormLabelFor = { f -> alcremieFlavorName(f) }
                        decorativeFormSpriteKeyFor = { f -> PetState.decorativeSpriteKey("alcremie", f) }
                        decorativeFormButton = Button(this).apply { text = "🎲 Cambiar forma"; isAllCaps = false }
                        root.addView(decorativeFormButton)
                    }
                }
                "gimmighoul" -> root.addView(TextView(this).apply {
                    text = "🎲 Forma: " + PetState.decorativeForm(this@MainActivity, "gimmighoul") +
                        " (al azar de un huevo, fija para siempre - igual que en el juego real)"
                    setPadding(0, dp(10), 0, 0)
                })
                "furfrou", "basculin", "squawkabilly", "pumpkaboo", "gourgeist", "castform", "deoxys",
                "tauros", "shellos", "gastrodon", "flabebe", "floette", "florges" -> {
                    val root_species = when (mon.name) {
                        "gourgeist" -> "pumpkaboo"
                        "gastrodon" -> "shellos"
                        "floette", "florges" -> "flabebe"
                        else -> mon.name
                    }
                    root.addView(TextView(this).apply {
                        text = "🎲 Forma: " + PetState.decorativeForm(this@MainActivity, root_species) +
                            " (al azar de un huevo)"
                        setPadding(0, dp(10), 0, 0)
                    })
                    if (PetState.decorativeFormsOwned(this, root_species).size > 1) {
                        decorativeFormRootSpecies = root_species
                        decorativeFormLabelFor = { f -> f.replaceFirstChar { c -> c.uppercase() } }
                        decorativeFormSpriteKeyFor = when (mon.name) {
                            "gourgeist" -> { f -> PetState.gourgeistSpriteKey(f) }
                            "gastrodon" -> { f -> if (f == "east") "gastrodon-east" else "gastrodon" }
                            "tauros" -> { f -> PetState.taurosSpriteKey(f) }
                            "floette" -> { f -> "floette" + (f.takeIf { it != "red" }?.let { "-$it" } ?: "") }
                            "florges" -> { f -> "florges" + (f.takeIf { it != "red" }?.let { "-$it" } ?: "") }
                            else -> { f -> PetState.decorativeSpriteKey(root_species, f) }
                        }
                        decorativeFormButton = Button(this).apply { text = "🎲 Cambiar forma"; isAllCaps = false }
                        root.addView(decorativeFormButton)
                    }
                }
                "minior" -> {
                    val color = PetState.decorativeForm(this@MainActivity, "minior")
                    root.addView(TextView(this).apply {
                        text = if ((stats?.health ?: 100f) < 50f) "💎 Nucleo revelado: $color (vida por debajo del 50%)"
                            else "☄️ Meteorito cerrado (nucleo $color escondido - se revela con la vida baja)"
                        setPadding(0, dp(10), 0, 0)
                    })
                    if (PetState.decorativeFormsOwned(this, "minior").size > 1) {
                        decorativeFormRootSpecies = "minior"
                        decorativeFormLabelFor = { f -> f.replaceFirstChar { c -> c.uppercase() } }
                        decorativeFormSpriteKeyFor = { f -> "minior-$f" }
                        decorativeFormButton = Button(this).apply { text = "🎲 Cambiar núcleo"; isAllCaps = false }
                        root.addView(decorativeFormButton)
                    }
                }
                // Necrozma/Kyurem/Calyrex no llevan info aqui: su fusion con otra especie se
                // explica en la seccion de Megaevolucion de mas abajo (ver FUSION_PARTNERS).
                "cramorant" -> root.addView(TextView(this).apply {
                    text = "🐟 Estado: " + when (PetState.cramorantState(this@MainActivity)) {
                        "gulping" -> "Tragando algo"; "gorging" -> "¡Atragantado!"; else -> "Normal"
                    } + " (a veces pasa al darle de comer)"
                    setPadding(0, dp(10), 0, 0)
                })
                // Sexo: SIN texto aqui a proposito (pedido explicito del usuario) - solo se
                // descubre entrando en su seccion "Diferencias de Género" (ver
                // showEvolutionAndMechanicsDialog), no se enseña de golpe en la ficha.
                "keldeo" -> root.addView(TextView(this).apply {
                    text = if (PetState.keldeoIsResolute(this@MainActivity)) "⚔️ Forma Resuelta (fijo para siempre)"
                        else "🐴 Forma Ordinaria (pasa a Resuelta en nivel ${PetState.KELDEO_RESOLUTE_LEVEL})"
                    setPadding(0, dp(10), 0, 0)
                })
                "cherrim" -> root.addView(TextView(this).apply {
                    text = "🌸 Forma actual (según el fondo): " +
                        (if (PetState.cherrimSpriteKey(PetState.currentBg(this@MainActivity)) == "cherrim-sunshine") "Soleada" else "Nublada")
                    setPadding(0, dp(10), 0, 0)
                })
                "mimikyu" -> root.addView(TextView(this).apply {
                    text = if ((stats?.health ?: 100f) < 50f) "😱 Forma Destrozada (vida por debajo del 50%)" else "🎭 Forma Disfrazada"
                    setPadding(0, dp(10), 0, 0)
                })
                "eiscue" -> root.addView(TextView(this).apply {
                    text = if ((stats?.health ?: 100f) < 50f) "🧊 Cara Noice (vida por debajo del 50%)" else "🧊 Cara Hielo"
                    setPadding(0, dp(10), 0, 0)
                })
                "wishiwashi" -> root.addView(TextView(this).apply {
                    val lvl = stats?.let { PetState.levelOf(this@MainActivity, it.xp, "wishiwashi") } ?: 1
                    text = if (lvl >= 20 && (stats?.health ?: 100f) > 25f) "🐟 Forma Banco (nivel 20+, vida por encima del 25%)"
                        else "🐠 Forma Solitaria"
                    setPadding(0, dp(10), 0, 0)
                })
                "morpeko" -> root.addView(TextView(this).apply {
                    text = "🐹 Modo actual: " + (if (PetState.morpekoIsHangry()) "Hambriento" else "Barriga Llena") + " (alterna solo con el tiempo)"
                    setPadding(0, dp(10), 0, 0)
                })
                "palafin" -> root.addView(TextView(this).apply {
                    text = if (PetState.palafinIsHero(this@MainActivity)) "🦸 Forma Héroe (fijo para siempre)"
                        else "🐬 Forma Cero (pasa a Héroe la próxima vez que vuelvas a elegirlo)"
                    setPadding(0, dp(10), 0, 0)
                })
                "aegislash" -> root.addView(TextView(this).apply {
                    text = "⚔️ Postura: " + (if (PetState.aegislashStance(this@MainActivity) == "blade") "Hoja" else "Escudo") + " (alterna con cada acción)"
                    setPadding(0, dp(10), 0, 0)
                })
                "terapagos" -> root.addView(TextView(this).apply {
                    text = if (PetState.terapagosIsTerastal(this@MainActivity)) "💎 Forma Terastal (fijo para siempre)"
                        else "🐉 Forma Normal (pasa a Terastal en nivel ${PetState.TERAPAGOS_TERASTAL_LEVEL})"
                    setPadding(0, dp(10), 0, 0)
                })
                "dudunsparce" -> root.addView(TextView(this).apply {
                    text = "🐛 " + (if (PetState.dudunsparceIsThree(this@MainActivity)) "¡3 segmentos! (1% de suerte, fijo para siempre)" else "2 segmentos")
                    setPadding(0, dp(10), 0, 0)
                })
                "maushold" -> root.addView(TextView(this).apply {
                    text = "🐭 " + (if (PetState.mausholdIsThree(this@MainActivity)) "¡Familia de 3! (1% de suerte, fijo para siempre)" else "Familia de 4")
                    setPadding(0, dp(10), 0, 0)
                })
                "solgaleo", "lunala" -> root.addView(TextView(this).apply {
                    val label = if (mon.name == "solgaleo") "Fase Sol Radiante" else "Fase Luna Llena"
                    text = if (PetState.isRadiantPhaseActive(this@MainActivity, mon.name)) "✨ $label activa (dura un momento tras cada acción)"
                        else "🌑 Forma normal"
                    setPadding(0, dp(10), 0, 0)
                })
            }

            // Shiny/genero/formas/Mega ya NO van aqui como botones sueltos (pedido explicito del
            // usuario) - se ven integrados dentro del dialogo de cadena evolutiva (ver
            // addEvolutionChainButton/showEvolutionAndMechanicsDialog), en orden fijo detras de
            // la cadena: shiny, genero, formas, mega/gigantamax.
        }

        // Ficha SOLO informativa (Pokedex completa, ver el parametro interactive): sin
        // estadisticas ni botones de accion, cualquiera que sea el estado (conseguido, evolucionado
        // o no) - es un catalogo de consulta, pedido explicito del usuario para separarla de la
        // ficha de "Mis Pokémon" (cuidar/evolucionar/etc). Si ya se tiene, un boton salta a esa
        // otra ficha - el listener de verdad se pone mas abajo (necesita `dialog`).
        var openCareBtn: Button? = null
        if (!interactive) {
            if (!unlocked) {
                root.addView(TextView(this).apply {
                    text = "Aún no lo has conseguido."
                    setTypeface(typeface, Typeface.ITALIC)
                    setTextColor(Color.parseColor("#888888"))
                    setPadding(0, dp(2), 0, dp(8))
                })
            }
            addFormMechanicInfo()
            addEvolutionChainButton(root, mon.name)
            addSuggestedBackgrounds(root, mon.name, viewShiny)
            if (unlocked) {
                openCareBtn = Button(this).apply {
                    text = "Ver en Mis Pokémon"
                    isAllCaps = false
                    setPadding(0, dp(14), 0, 0)
                }
                root.addView(openCareBtn)
            }
        } else if (!unlocked) {
            // Aun no conseguido: toda la info general se muestra igual (mecanica/forma, cadena
            // evolutiva, fondos sugeridos) - pedido explicito del usuario. Solo faltan las
            // estadisticas (no hay individuo real todavia) y los botones de accion (cuidar/
            // evolucionar/cambiar de forma), que no tienen sentido sin tenerlo.
            root.addView(TextView(this).apply {
                text = "Aún no lo has conseguido."
                setTypeface(typeface, Typeface.ITALIC)
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, dp(2), 0, dp(8))
            })
            addFormMechanicInfo()
            addEvolutionChainButton(root, mon.name)
            addSuggestedBackgrounds(root, mon.name, viewShiny)
        } else if (evolvedAway) {
            if (stats != null) {
                root.addView(TextView(this).apply { text = "Nivel cuando evolucionó: ${PetState.levelOf(this@MainActivity, stats.xp, mon.name)}" })
            }
            addFormMechanicInfo()
            addEvolutionChainButton(root, mon.name)
            addSuggestedBackgrounds(root, mon.name, viewShiny)
        } else {
            if (stats != null) {
                root.addView(TextView(this).apply {
                    text = "Nivel ${PetState.levelOf(this@MainActivity, stats.xp, mon.name)}"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4), 0, dp(8))
                })
                addStatRow(root, "❤", stats.health.toInt(), "#E23B3B")
                addStatRow(root, "💧", stats.hygiene.toInt(), "#3B9BE2")
                addStatRow(root, "😊", stats.happiness.toInt(), "#F2B705")
            }

            addEvolutionChainButton(root, mon.name)
            addSuggestedBackgrounds(root, mon.name, viewShiny)

            if (isActive) {
                root.addView(TextView(this).apply {
                    text = "Este es tu Pokémon activo ahora mismo."
                    setTypeface(typeface, Typeface.ITALIC)
                    setPadding(0, dp(8), 0, 0)
                })
                if (PetState.isFinalStage(this, mon.name)) {
                    val eggRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(10), 0, 0)
                    }
                    eggRow.addView(TextView(this).apply {
                        text = "🥚 Puede poner huevos"
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    eggRow.addView(Switch(this).apply {
                        isChecked = PetState.eggLayingEnabled(this@MainActivity, mon.name)
                        setOnCheckedChangeListener { _, checked ->
                            PetState.setEggLayingEnabled(this@MainActivity, mon.name, checked)
                        }
                    })
                    root.addView(eggRow)
                    if (PetState.activeIsEggParent(this)) {
                        root.addView(TextView(this).apply {
                            text = if (PetState.isEggReady(this@MainActivity)) "🥚 ¡Ya tienes un huevo listo para eclosionar!"
                                else "🥚 Ahora mismo está incubando un huevo."
                            setPadding(0, dp(6), 0, 0)
                        })
                    }
                }
            } else if (PetState.isFusionDonorBusy(this, mon.name)) {
                // Pedido explicito del usuario: mientras este "prestado" dentro de una Fusion
                // activa de otra especie (Kyurem/Necrozma/Calyrex), no se puede elegir por
                // separado - ver PetState.isFusionDonorBusy.
                root.addView(TextView(this).apply {
                    text = "🔺 Ahora mismo está fusionado con otro Pokémon - no se puede seleccionar por separado."
                    setTextColor(Color.parseColor("#888888"))
                    setPadding(0, dp(8), 0, 0)
                })
            } else if (PetState.isInNursery(this, mon.name, viewShiny)) {
                // Pedido explicito del usuario: igual que uno de la mazmorra, uno que esta en la guarderia no se puede
                // poner en el widget (para cuidarlo hay que sacarlo antes de la guarderia).
                root.addView(TextView(this).apply {
                    text = "🌱 Está en la guardería - no se puede cuidar mientras esté dentro. Sácalo de la guardería (⚙) para poder ponerlo en el widget."
                    setTextColor(Color.parseColor("#C2185B"))
                    setPadding(0, dp(8), 0, 0)
                })
            } else if (DungeonState.isInParty(this, mon.name, viewShiny)) {
                root.addView(TextView(this).apply {
                    text = "🗺️ Está en la mazmorra - no se puede cuidar hasta que salga de ella."
                    setTextColor(Color.parseColor("#C62828"))
                    setPadding(0, dp(8), 0, 0)
                })
            } else {
                selectBtn = Button(this).apply { text = "Cuidar a este Pokémon"; isAllCaps = false }
                root.addView(selectBtn)
            }

            // Megaevolucion/Gigantamax: para CUALQUIER Pokemon ya conseguido, no solo el activo -
            // su estado (activo/enfriamiento) se guarda por especie (ver PetState, seccion
            // MEGAEVOLUCION), y su propio sprite ya se enseña en esta ficha sea o no el activo
            // (ver spriteDisplayName mas arriba). Antes este bloque vivia DENTRO de "if (isActive)"
            // con el razonamiento de que solo el activo se veia en algun sitio - ya no es cierto
            // (esta ficha ya lo enseña siempre) y causaba un bug real reportado por el usuario: el
            // boton aparecia o no segun cual fuera el Pokemon activo en ese momento, sin relacion
            // con si ESTE Pokemon podia mega/gigantaxizar de verdad.
            // Necrozma/Kyurem/Calyrex son casos especiales: sus opciones piden ademas tener
            // conseguida otra especie distinta (ver FUSION_PARTNERS) - megaOptions se queda
            // solo con las YA activables (megaOptionsAvailable), pero allMegaOptions (todas,
            // activables o no) hace falta para el mensaje de "que falta" y para poder resolver
            // el nombre si hay una activa ahora mismo.
            val allMegaOptions = PetState.megaOptionsFor(this, mon.name)
            val fusionSpecies = setOf("necrozma", "kyurem", "calyrex")
            val level = stats?.let { PetState.levelOf(this, it.xp, mon.name) } ?: 0
            // Gigantamax reutiliza el mismo mecanismo que Megaevolucion (mismo sistema de
            // opciones - ver megas.json, opciones cuyo name acaba en "-gmax"), pero con su
            // PROPIO nivel/duracion/enfriamiento, mas bajo y corto que Megaevolucion "de
            // verdad" (ver PetState.unlockLevelForMegaOption) - pedido explicito del usuario
            // tras jugar un tiempo con un unico nivel para todo. megaOptions se queda solo con
            // las que YA se pueden activar con el nivel actual (megaOptionsEligible), asi una
            // especie con Mega Y Gigantamax a la vez (Blastoise/Venusaur/Gengar/Charizard)
            // puede tener disponible solo una de las dos.
            megaOptions = PetState.megaOptionsEligible(this, mon.name, level)
            // Solo cambia el TEXTO (Megaevolucionar/Gigantamaxizar) cuando TODAS las opciones
            // YA DISPONIBLES son Gigantamax - si una especie mixta solo tiene Gigantamax
            // elegible todavia (nivel < MEGA_UNLOCK_LEVEL), el boton ya dice "Gigantamaxizar",
            // no el generico de Mega.
            val allGmax = megaOptions.isNotEmpty() && megaOptions.all { it.name.endsWith("-gmax") }
            // Necrozma/Kyurem/Calyrex no "megaevolucionan" de verdad (no hay Mega Piedra ni
            // vuelven a la normalidad solos) - se FUSIONAN de forma permanente con otra especie
            // distinta (Solgaleo/Lunala, Reshiram/Zekrom, Glastrier/Spectrier). Mismo motivo que
            // ya distinguia Gigantamax de Mega por texto (pedido explicito del usuario: no llamar
            // "Megaevolucionar" a algo que conceptualmente es una fusion).
            val megaVerb = if (allGmax) "Gigantamaxizar" else if (mon.name in fusionSpecies) "Fusionar" else "Megaevolucionar"
            val megaNoun = if (allGmax) "Gigantamax" else if (mon.name in fusionSpecies) "Fusión" else "Megaevolución"
            if (allMegaOptions.isNotEmpty() && stats != null) {
                val activeMega = PetState.activeMegaSpriteName(this, mon.name)
                when {
                    activeMega != null -> {
                        val label = allMegaOptions.find { it.name == activeMega }?.label ?: activeMega
                        root.addView(TextView(this).apply {
                            text = "🔺 $label activo — vuelve a la normalidad en " +
                                formatCountdown(PetState.megaActiveRemainingMs(this@MainActivity, mon.name))
                            setPadding(0, dp(10), 0, 0)
                        })
                    }
                    mon.name == "necrozma" && megaOptions.isEmpty() -> {
                        root.addView(TextView(this).apply {
                            text = "🔺 Para fusionarlo hace falta tener conseguido a Solgaleo (Crepúsculo) o Lunala (Alba Nocturna) - las dos para llegar a Necrozma Ultra"
                            setTextColor(Color.parseColor("#888888"))
                            setPadding(0, dp(10), 0, 0)
                        })
                    }
                    mon.name == "kyurem" && megaOptions.isEmpty() -> {
                        root.addView(TextView(this).apply {
                            text = "🔺 Para fusionarlo hace falta tener conseguido a Reshiram (Blanco) o Zekrom (Negro)"
                            setTextColor(Color.parseColor("#888888"))
                            setPadding(0, dp(10), 0, 0)
                        })
                    }
                    mon.name == "calyrex" && megaOptions.isEmpty() -> {
                        root.addView(TextView(this).apply {
                            text = "🔺 Para fusionarlo hace falta tener conseguido a Glastrier (Jinete de Hielo) o Spectrier (Jinete Sombrío)"
                            setTextColor(Color.parseColor("#888888"))
                            setPadding(0, dp(10), 0, 0)
                        })
                    }
                    mon.name == "terapagos" && !PetState.terapagosIsTerastal(this) -> {
                        root.addView(TextView(this).apply {
                            text = "🔺 Para fusionarlo en Forma Estelar hace falta llegar antes a Forma Terastal (nivel ${PetState.TERAPAGOS_TERASTAL_LEVEL})"
                            setTextColor(Color.parseColor("#888888"))
                            setPadding(0, dp(10), 0, 0)
                        })
                    }
                    mon.name !in fusionSpecies && mon.name != "terapagos" && megaOptions.isEmpty() -> {
                        // La opcion que antes se desbloquea (menor unlockLevelForMegaOption) decide
                        // el numero Y el nombre del aviso - para una especie mixta (Mega Y
                        // Gigantamax, ej. Charizard) esto es Gigantamax primero, Mega despues.
                        val next = allMegaOptions.minByOrNull { PetState.unlockLevelForMegaOption(it.name) }
                        val nextLevel = next?.let { PetState.unlockLevelForMegaOption(it.name) } ?: PetState.MEGA_UNLOCK_LEVEL
                        val nextNoun = if (next?.name?.endsWith("-gmax") == true) "Gigantamax" else megaNoun
                        root.addView(TextView(this).apply {
                            text = "🔺 $nextNoun disponible a partir de nivel $nextLevel"
                            setTextColor(Color.parseColor("#888888"))
                            setPadding(0, dp(10), 0, 0)
                        })
                    }
                    PetState.canMegaEvolve(this, mon.name, level) -> {
                        megaButton = Button(this).apply { text = "🔺 $megaVerb"; isAllCaps = false }
                        root.addView(megaButton)
                    }
                    else -> {
                        root.addView(TextView(this).apply {
                            text = "🔺 $megaNoun disponible de nuevo en " +
                                formatCountdown(PetState.megaCooldownRemainingMs(this@MainActivity, mon.name))
                            setTextColor(Color.parseColor("#888888"))
                            setPadding(0, dp(10), 0, 0)
                        })
                    }
                }
            }
            addFormMechanicInfo()

            val pending = stats?.let { PetState.pendingEvolutionFor(this, mon.name, it, viewShiny) }
            if (pending != null) {
                root.addView(TextView(this).apply {
                    text = "✨ ¡Puede evolucionar!"
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(14), 0, dp(4))
                })
                if (pending.evolvesTo.size == 1) {
                    val opt = pending.evolvesTo.first()
                    val b = Button(this).apply { text = "Evolucionar a ${PetState.displayLabel(mon.name)}"; isAllCaps = false }
                    root.addView(b)
                    evolveButtons.add(opt to b)
                } else {
                    // No se anuncia a que evoluciona en el propio boton: con varias ramas (ej.
                    // Eevee) mostrar el nombre de la PRIMERA opcion daba a entender que esa era la
                    // unica/principal, cuando en realidad las 8 estan igual de disponibles y se
                    // eligen en el menu de despues. El boton es la accion "evolucionar a [este
                    // Pokemon]", no un adelanto de a que evoluciona.
                    val b = Button(this).apply { text = "Evolucionar a ${PetState.displayLabel(mon.name)}"; isAllCaps = false }
                    root.addView(b)
                    multiEvolveButton = b
                    multiEvolveOptions = pending.evolvesTo
                }
            }
        }

        val scroll = ScrollView(this).apply { addView(root) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create()

        selectBtn?.setOnClickListener {
            dialog.dismiss()
            selectPokemon(mon, viewShiny)
        }
        // Se guarda de verdad (no solo visual): asi la fila de la Pokedex (ver DexAdapter) sabe
        // cual de los dos elegiste tu ultima vez y lo respeta, en vez de mostrar siempre el shiny
        // a ciegas - pedido explicito del usuario. Ojo: NO toca active_shiny (eso solo cambia al
        // seleccionarlo de verdad como activo, ver selectPokemon) - aqui solo "cual prefieres ver".
        toggleNormalBtn?.setOnClickListener {
            PetState.setShiny(this, false, mon.name)
            dialog.dismiss(); showDetailDialog(mon, preferShiny = false)
        }
        toggleShinyBtn?.setOnClickListener {
            PetState.setShiny(this, true, mon.name)
            dialog.dismiss(); showDetailDialog(mon, preferShiny = true)
        }
        // "Ver en Mis Pokémon": NO abre otra ficha encima - cambia a esa pestaña y desplaza la
        // rejilla hasta esa especie, para que el jugador la vea "de frente" en su sitio dentro de
        // la coleccion, en vez de saltar directo a otro dialogo apilado - pedido explicito del
        // usuario.
        openCareBtn?.setOnClickListener {
            dialog.dismiss()
            if (filterMode != FILTER_MINE) setFilterMode(FILTER_MINE)
            val idx = rows.indexOfFirst { it.name == mon.name }
            if (idx >= 0) findViewById<RecyclerView>(R.id.rv).smoothScrollToPosition(idx)
        }
        for ((opt, btn) in evolveButtons) {
            btn.setOnClickListener {
                dialog.dismiss()
                doEvolve(mon.name, opt, viewShiny)
            }
        }
        multiEvolveButton?.setOnClickListener {
            dialog.dismiss()
            showEvolutionChoiceDialog(mon.name, multiEvolveOptions, viewShiny)
        }
        megaButton?.setOnClickListener {
            dialog.dismiss()
            if (megaOptions.size == 1) {
                activateMegaWithAnimation(mon.name, megaOptions.first(), viewShiny, interactive)
            } else {
                showMegaChoiceDialog(mon.name, megaOptions, viewShiny, interactive)
            }
        }
        rotomFormButton?.setOnClickListener {
            dialog.dismiss()
            showRotomFormDialog(mon, viewShiny)
        }
        unlockableFormButton?.setOnClickListener {
            dialog.dismiss()
            showUnlockableFormDialog(mon, unlockableFormSpecies, viewShiny)
        }
        decorativeFormButton?.setOnClickListener {
            dialog.dismiss()
            showDecorativeFormDialog(mon, decorativeFormRootSpecies, decorativeFormLabelFor, decorativeFormSpriteKeyFor, viewShiny)
        }
        lastDetailDialog = dialog
        dialog.show()
        if (megaButton != null) TutorialTips.showPending(this, TutorialTips.Tip.MEGA, onScreen = true, resolve = { id -> if (id == "mega") megaButton else null })
    }

    /** Selector para las especies de "formas decorativas" (Alcremie, Pikachu, Furfrou, Vivillon,
     *  Unown, Minior, Arceus/Silvally...): a diferencia de Rotom/Oricorio/Ogerpon/Genesect/
     *  Meloetta (desbloqueo por NIVEL), aqui el "desbloqueo" es haberla conseguido de un huevo
     *  (ver PetState.decorativeFormsOwned) - mismo estilo visual que showUnlockableFormDialog,
     *  pero atenuando por "no conseguida" en vez de "nivel insuficiente". [labelFor]/
     *  [spriteKeyFor] los decide la especie concreta que abrio el dialogo (ver addFormMechanicInfo). */
    private fun showDecorativeFormDialog(mon: Mon, rootSpecies: String, labelFor: (String) -> String, spriteKeyFor: (String) -> String, shiny: Boolean) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val owned = PetState.decorativeFormsOwned(this, rootSpecies)
        val current = PetState.decorativeForm(this, rootSpecies)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elige la forma")
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton("Cancelar", null)
            .create()
        for (row in PetState.decorativeFormsOptions(rootSpecies).chunked(3)) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (form in row) {
                val has = form in owned
                val spriteKey = spriteKeyFor(form)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor(when {
                            !has -> "#DDDDDD"
                            form == current -> "#B7F0B1"
                            else -> "#EEEEEE"
                        }))
                        cornerRadius = dp(10).toFloat()
                    }
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    isClickable = has
                }
                val img = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (!has) img.alpha = 0.35f
                cell.addView(img)
                loadLocalOrNetworkThumb(img, spriteKey, false) {
                    img.load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(spriteKey)}.png")
                }
                cell.addView(TextView(this).apply {
                    text = if (has) labelFor(form) else "${labelFor(form)}\n(sin conseguir)"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    if (!has) setTextColor(Color.parseColor("#888888"))
                })
                if (has) {
                    cell.setOnClickListener {
                        dbg("forma decorativa: $rootSpecies -> $form")
                        PetState.selectDecorativeForm(this, rootSpecies, form)
                        WidgetRefresh.updateWidgets(this)
                        refreshRows()
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this, "¡Forma cambiada!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        showDetailDialog(mon, preferShiny = shiny)
                    }
                }
                rowLayout.addView(cell)
            }
            grid.addView(rowLayout)
        }

        dialog.show()
    }

    /** Selector generico para especies con formas FUNCIONALES desbloqueadas por nivel (Oricorio,
     *  Ogerpon - ver PetState.UNLOCKABLE_FORMS) - mismo estilo/comportamiento que
     *  showRotomFormDialog, pero para cualquier especie de esa tabla. */
    private fun showUnlockableFormDialog(mon: Mon, species: String, shiny: Boolean) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val level = PetState.levelForPokemon(this, mon.name, shiny) ?: 1

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elige la forma")
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton("Cancelar", null)
            .create()
        for (row in PetState.unlockableFormOptions(species).chunked(3)) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (opt in row) {
                val unlocked = PetState.isUnlockableFormUnlocked(species, opt.key, level)
                val spriteKey = PetState.unlockableSpriteKey(species, opt.key)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor(when {
                            !unlocked -> "#DDDDDD"
                            opt.key == PetState.unlockableForm(this@MainActivity, species, shiny) -> "#B7F0B1"
                            else -> "#EEEEEE"
                        }))
                        cornerRadius = dp(10).toFloat()
                    }
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    isClickable = unlocked
                }
                val img = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (!unlocked) img.alpha = 0.35f
                cell.addView(img)
                loadLocalOrNetworkThumb(img, spriteKey, shiny) {
                    img.load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(spriteKey)}.png")
                }
                cell.addView(TextView(this).apply {
                    text = if (unlocked) opt.label else "${opt.label}\n(Nv. ${opt.unlockLevel})"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    if (!unlocked) setTextColor(Color.parseColor("#888888"))
                })
                if (unlocked) {
                    cell.setOnClickListener {
                        dbg("forma: $species -> ${opt.key} (Nv. actual $level)")
                        PetState.setUnlockableForm(this, species, opt.key, level, shiny)
                        WidgetRefresh.updateWidgets(this)
                        refreshRows()
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this, "¡Ahora es ${opt.label}!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        showDetailDialog(mon, preferShiny = shiny)
                    }
                }
                rowLayout.addView(cell)
            }
            grid.addView(rowLayout)
        }

        dialog.show()
    }

    /** Selector de aparato de Rotom (mismo estilo visual que el de Megaevolucion, pero sin
     *  activacion temporal: al tocar uno YA desbloqueado se cambia al momento, sin mas). Los que
     *  aun no tocan se ven atenuados, con el nivel que hace falta en vez del boton de elegir. */
    private fun showRotomFormDialog(mon: Mon, shiny: Boolean) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val level = PetState.levelForPokemon(this, mon.name, shiny) ?: 1

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elige el aparato")
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton("Cancelar", null)
            .create()
        for (row in PetState.ROTOM_FORMS.chunked(3)) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (opt in row) {
                val unlocked = PetState.isRotomFormUnlocked(opt.form, level)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor(when {
                            !unlocked -> "#DDDDDD"
                            opt.form == PetState.rotomForm(this@MainActivity, shiny) -> "#B7F0B1"
                            else -> "#EEEEEE"
                        }))
                        cornerRadius = dp(10).toFloat()
                    }
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    isClickable = unlocked
                }
                val img = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (!unlocked) img.alpha = 0.35f
                cell.addView(img)
                loadLocalOrNetworkThumb(img, PetState.rotomSpriteKey(opt.form), shiny) {
                    img.load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(PetState.rotomSpriteKey(opt.form))}.png")
                }
                cell.addView(TextView(this).apply {
                    text = if (unlocked) opt.label else "${opt.label}\n(Nv. ${opt.unlockLevel})"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    if (!unlocked) setTextColor(Color.parseColor("#888888"))
                })
                if (unlocked) {
                    cell.setOnClickListener {
                        dbg("forma: rotom -> ${opt.form} (Nv. actual $level)")
                        PetState.setRotomForm(this, opt.form, level, shiny)
                        WidgetRefresh.updateWidgets(this)
                        refreshRows()
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this, "¡Ahora es ${opt.label}!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        showDetailDialog(mon, preferShiny = shiny)
                    }
                }
                rowLayout.addView(cell)
            }
            grid.addView(rowLayout)
        }

        dialog.show()
    }

    /** Selector de Megaevolucion (mismo estilo que el de evolucion con varias ramas) cuando esa
     *  especie tiene mas de una - se ve el sprite de cada una para elegir. */
    private fun showMegaChoiceDialog(fromName: String, options: List<PetState.MegaOption>, shiny: Boolean, interactive: Boolean) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val frame = android.widget.FrameLayout(this)
        frame.addView(ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(resources.getIdentifier(backgrounds.random(), "drawable", packageName))
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        content.addView(TextView(this).apply {
            // "Transformar" en vez de "Megaevolucionar": este selector tambien se usa para
            // especies que mezclan Mega Y Gigantamax en la misma lista (Blastoise/Venusaur/
            // Gengar) - ver megas.json y el "allGmax" de showDetailDialog.
            text = "🔺 ¿A cuál quieres transformar?"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(14))
        })

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        options.chunked(3).forEach { rowOpts ->
            val rowLl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(2)
                }
            }
            for (opt in rowOpts) {
                val cellCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(5), dp(5), dp(5), dp(5))
                    }
                }
                val cell = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(92), dp(84))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#40FFFFFF"))
                        cornerRadius = dp(12).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                loadLocalOrNetworkThumb(cell, opt.name, shiny) {
                    cell.load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(opt.name)}.png") { crossfade(true) }
                }
                cell.setOnClickListener {
                    dialog.dismiss()
                    activateMegaWithAnimation(fromName, opt, shiny, interactive)
                }
                cellCol.addView(cell)
                cellCol.addView(TextView(this).apply {
                    text = opt.label
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                rowLl.addView(cellCol)
            }
            content.addView(rowLl)
        }

        content.addView(Button(this).apply {
            text = "Cancelar"; isAllCaps = false; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
            }
        }.also { it.setOnClickListener { dialog.dismiss() } })

        frame.addView(content)
        dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(frame).create()
        dialog.show()
    }

    /** Selector aparte (mismo estilo que el dialogo de la oferta) cuando hay VARIAS ramas de
     *  evolucion posibles (Eevee, Slowpoke, Pikachu/Raichu de Alola...) - un sprite animado por
     *  opcion, tocar uno evoluciona a esa directamente. Pedido explicito del usuario en vez de
     *  apilar un boton de texto por opcion (con Eevee serian 8 botones). */
    private fun showEvolutionChoiceDialog(fromName: String, options: List<PetState.EvoOption>, shiny: Boolean, onEvolved: (() -> Unit)? = null): androidx.appcompat.app.AlertDialog {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val frame = android.widget.FrameLayout(this)
        frame.addView(ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(resources.getIdentifier(backgrounds.random(), "drawable", packageName))
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        content.addView(TextView(this).apply {
            text = "✨ ¿A cuál quieres evolucionar?"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(14))
        })

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        var anyOwned = false
        // Mismas filas de hasta 3 centradas que en la oferta, por si hay muchas ramas (Eevee).
        options.chunked(3).forEach { rowOpts ->
            val rowLl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(2)
                }
            }
            for (opt in rowOpts) {
                val cellCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(5), dp(5), dp(5), dp(5))
                    }
                }
                val cell = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(92), dp(84))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#40FFFFFF"))
                        cornerRadius = dp(12).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                loadAnimatedSprite(cell, opt.name, shiny)
                cell.setOnClickListener {
                    dialog.dismiss()
                    doEvolve(fromName, opt, shiny, onEvolved)
                }
                // Las que YA tienes (individuo vivo de esa variante) llevan una Poké Ball en la esquina de la casilla.
                val owned = PetState.ownsLiveIndividual(this, opt.name, shiny)
                if (owned) anyOwned = true
                cellCol.addView(android.widget.FrameLayout(this).apply {
                    // Sin esto, el hueco de una columna vertical ocupa solo el ancho del nombre y recorta la casilla.
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    addView(cell)
                    if (owned) {
                        addView(ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.ic_pokeball)
                            contentDescription = "Ya lo tienes"
                            layoutParams = android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(3), dp(3), 0) }
                        })
                    }
                })
                cellCol.addView(TextView(this).apply {
                    text = opt.name
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                rowLl.addView(cellCol)
            }
            content.addView(rowLl)
        }
        if (anyOwned) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10)
                }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_pokeball)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "  Ya lo tienes"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                })
            })
        }

        content.addView(Button(this).apply {
            text = "Cancelar"; isAllCaps = false; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
            }
        }.also { it.setOnClickListener { dialog.dismiss() } })

        frame.addView(content)
        dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(frame).create()
        dialog.show()
        return dialog
    }

    /** Fila de estadistica con icono+porcentaje a la izquierda y una barra de progreso del
     *  mismo color que en el widget, para que la ficha se lea de un vistazo. */
    private fun addStatRow(container: LinearLayout, icon: String, pct: Int, colorHex: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(3) }
        }
        row.addView(TextView(this).apply {
            text = "$icon $pct%"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(10), 1f)
            max = 100
            progress = pct
            progressTintList = ColorStateList.valueOf(Color.parseColor(colorHex))
        })
        container.addView(row)
    }

    /** Debajo de la ficha: hasta 2 fondos que le pegan a [name] segun su tipo, con su nombre.
     *  Tocar CUALQUIERA (desbloqueado o no) abre la misma ficha que en Ajustes - imagen, tipos
     *  beneficiados y beneficio, con boton de aplicar o desbloquear ahi mismo. */
    private fun addSuggestedBackgrounds(root: LinearLayout, name: String, shiny: Boolean) {
        val bgs = PetState.suggestedBackgrounds(this, name, shiny)
        if (bgs.isEmpty()) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        root.addView(TextView(this).apply {
            text = "Fondos que le pegan"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        })
        val rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowContainer)

        fun render() {
            rowContainer.removeAllViews()
            val unlocked = PetState.unlockedBackgrounds(this)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((idx, bg) in bgs.withIndex()) {
                val isUnlockedBg = bg in unlocked
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = if (idx > 0) dp(10) else 0 }
                }
                val cell = android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70))
                }
                cell.addView(ImageView(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(resources.getIdentifier(bg, "drawable", packageName))
                    alpha = if (isUnlockedBg) 1f else 0.35f
                })
                cell.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(2), Color.parseColor("#33000000"))
                }
                if (!isUnlockedBg) {
                    cell.addView(TextView(this).apply {
                        text = "🔒"; textSize = 18f
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply { gravity = Gravity.CENTER }
                    })
                }
                cell.setOnClickListener { showBgInfoDialog(bg) { render() } }
                col.addView(cell)
                col.addView(TextView(this).apply {
                    text = bgDisplayName(bg)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(col)
            }
            rowContainer.addView(row)
        }
        render()
    }

    private data class EvoEdge(val fromName: String, val toName: String, val level: Int, val method: String)

    /** Reconstruye la cadena evolutiva ENTERA de [name] (no solo su preevolucion/evolucion
     *  inmediata): sube hasta la forma base y luego recorre todas las ramas hacia adelante. */
    private fun fullChainEdges(name: String): List<EvoEdge> {
        var rootName = name.lowercase()
        var guard = 0
        while (guard < 10) {
            val from = PetState.evolutionInfo(this, rootName)?.evolvesFrom ?: break
            rootName = from
            guard++
        }
        val edges = mutableListOf<EvoEdge>()
        fun walk(curName: String) {
            val info = PetState.evolutionInfo(this, curName) ?: return
            for (opt in info.evolvesTo) {
                edges.add(EvoEdge(curName, opt.name, opt.level, opt.method))
                walk(opt.name)
            }
        }
        walk(rootName)
        return edges
    }

    /** Boton que abre la cadena evolutiva completa en un dialogo aparte, mas grande y clara -
     *  antes se pintaba directo (compacta) en la propia ficha; pedido explicito del usuario. Si
     *  la especie no tiene ninguna cadena (ej. Kangaskhan), no se añade nada. */
    /** Se muestra SIEMPRE (a diferencia de antes, que se ocultaba sin cadena evolutiva real) -
     *  el Shiny es una mecanica universal, asi que este boton es la unica puerta de entrada a
     *  todas las mecanicas de la especie (cadena, shiny, genero, formas, mega/gigantamax - ver
     *  showEvolutionAndMechanicsDialog), no solo a la cadena. */
    private fun addEvolutionChainButton(container: LinearLayout, viewedName: String) {
        container.addView(Button(this).apply {
            text = "🔗 Cadena evolutiva y mecánicas"
            isAllCaps = false
            setOnClickListener { showEvolutionAndMechanicsDialog(viewedName) }
        })
    }

    /** Bloque de una especie dentro de la cadena: sprite grande + nombre + si ya la tienes o no.
     *  Las NO conseguidas ensenan su sprite real con el mismo tinte gris que la rejilla de la
     *  Pokedex y la ficha detallada (ver DexAdapter/showDetailDialog) - aqui ademas se dice
     *  explicitamente "ya tienes"/"aun no" en texto, no solo con el tinte. */
    private fun evoChainSpeciesBlock(name: String, viewedName: String): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val unlocked = PetState.isUnlocked(this, name)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(4), 0, dp(4), 0)
        }
        col.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(64))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (!unlocked) setColorFilter(Color.parseColor("#707070"))
            loadLocalOrNetworkThumb(this, name, unlocked && PetState.isShiny(this@MainActivity, name)) {
                load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(name)}.png") {
                    crossfade(true)
                }
            }
        })
        col.addView(TextView(this).apply {
            text = PetState.displayLabel(name)
            textSize = 13f
            gravity = Gravity.CENTER
            if (name == viewedName) setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = if (unlocked) "✓ ya la tienes" else "aún no"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(if (unlocked) "#57c785" else "#9E9E9E"))
        })
        return col
    }

    /** Dialogo unico con TODAS las mecanicas de [viewedName], en un orden fijo (pedido explicito
     *  del usuario): cadena evolutiva -> shiny (universal) -> genero -> formas propias de la
     *  especie -> mega/gigantamax. Antes cada una vivia en su propio boton/dialogo separado; ahora
     *  todo se enseña integrado de un tiron, con sprites y explicacion, sin abrir nada aparte. */
    private fun showEvolutionAndMechanicsDialog(viewedName: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        fun divider() {
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
            })
        }
        fun sectionTitle(text_: String) {
            root.addView(TextView(this).apply {
                text = text_
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, dp(6))
            })
        }

        // ---- Cadena evolutiva: una fila por cada especie que evoluciona, con TODAS sus ramas
        // juntas al lado (en vez de repetir el sprite de origen una vez por rama) - asi Eevee, por
        // ejemplo, enseña sus 8 evoluciones de un vistazo bajo un unico Eevee, no 8 filas identicas.
        sectionTitle("🔗 Cadena evolutiva")
        val edges = fullChainEdges(viewedName)
        if (edges.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No evoluciona ni proviene de ninguna evolución."
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 0, 0, dp(4))
            })
        } else {
            val grouped = edges.groupBy { it.fromName }
            var first = true
            for ((fromName, options) in grouped) {
                if (!first) {
                    root.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        setBackgroundColor(Color.parseColor("#22FFFFFF"))
                        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
                        (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
                    })
                }
                first = false
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(evoChainSpeciesBlock(fromName, viewedName))
                val branchCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                for (opt in options) {
                    val branchRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    branchRow.addView(TextView(this@MainActivity).apply {
                        text = "→ nv.${opt.level} →"
                        textSize = 12f
                        setTextColor(Color.parseColor("#9E9E9E"))
                        setPadding(dp(4), 0, dp(4), 0)
                    })
                    branchRow.addView(evoChainSpeciesBlock(opt.toName, viewedName))
                    branchCol.addView(branchRow)
                }
                row.addView(branchCol)
                root.addView(row)
            }
        }

        // ---- Shiny: mecanica universal (CUALQUIER especie), va SIEMPRE justo despues de la
        // cadena, con los mismos dos colores gris/normal de siempre segun tengas registrado el
        // individuo normal y/o el shiny (ver PetState.hasIndividual).
        divider()
        sectionTitle("✨ Shiny")
        root.addView(TextView(this).apply {
            text = "Versión con otros colores. Probabilidad al eclosionar: 2-4% según el fondo " +
                "puesto. Normal y shiny son individuos distintos, cada uno con su progreso."
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        })
        run {
            val hasNormal = PetState.hasIndividual(this, viewedName, false)
            val hasShiny = PetState.hasIndividual(this, viewedName, true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun col(label: String, obtained: Boolean, shinyThumb: Boolean) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(4), 0, dp(4), 0)
                addView(ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(64))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (!obtained) setColorFilter(Color.parseColor("#707070"))
                    loadLocalOrNetworkThumb(this, viewedName, shinyThumb) {
                        val folder = if (shinyThumb) "gen5-shiny" else "gen5"
                        load("https://play.pokemonshowdown.com/sprites/$folder/${SpriteRepository.toShowdownSlug(viewedName)}.png") {
                            crossfade(true)
                        }
                    }
                })
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    if (!obtained) setTextColor(Color.parseColor("#888888"))
                })
                addView(TextView(this@MainActivity).apply {
                    text = if (obtained) "✅ Registrado" else "❌ Sin registrar"
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(if (obtained) "#2E7D32" else "#888888"))
                })
            }
            row.addView(col("Normal", hasNormal, false))
            row.addView(col("✨ Shiny", hasShiny, true))
            root.addView(row)
        }

        // ---- Genero -> formas propias -> Mega/Gigantamax, en ese orden fijo - ver
        // PetState.speciesMechanics (clasifica el resultado de mechanicShowcase/
        // megaMechanicShowcase en sus 3 categorias; null si esta especie no tiene esa categoria).
        fun addMechanicSection(showcase: PetState.MechanicShowcase) {
            divider()
            sectionTitle("${showcase.title}")
            root.addView(TextView(this).apply {
                text = showcase.desc
                textSize = 13f
                setPadding(0, 0, 0, dp(4))
            })
            val gotCount = showcase.forms.count { it.obtained }
            root.addView(TextView(this).apply {
                text = "$gotCount / ${showcase.forms.size} vistas"
                textSize = 11.5f
                setTypeface(typeface, Typeface.ITALIC)
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 0, 0, dp(10))
            })
            val perRow = 4
            showcase.forms.chunked(perRow).forEach { rowForms ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setPadding(0, 0, 0, dp(10))
                }
                for (f in rowForms) {
                    val col = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(dp(4), 0, dp(4), 0)
                    }
                    col.addView(ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        if (!f.obtained) setColorFilter(Color.parseColor("#707070"))
                        loadLocalOrNetworkThumb(this, f.spriteKey, false) {
                            load("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(f.spriteKey)}.png") {
                                crossfade(true)
                            }
                        }
                    })
                    col.addView(TextView(this@MainActivity).apply {
                        text = f.label
                        textSize = 10f
                        gravity = Gravity.CENTER
                        if (!f.obtained) setTextColor(Color.parseColor("#888888"))
                    })
                    row.addView(col)
                }
                root.addView(row)
            }
        }
        // Antes solo se miraban las mecanicas de [viewedName] en concreto - si abrias la ficha de
        // un eslabon SIN mecanica propia (ej. Treecko, Grovyle), la de una fase MAS ADELANTE en la
        // MISMA cadena (ej. Mega Sceptile) no aparecia, aunque la cadena entera ya se estuviera
        // mostrando aqui mismo arriba - no era un fallo de datos, faltaba mirar toda la cadena, no
        // solo la especie concreta desde la que se abrio el dialogo (reportado por el usuario:
        // "no me aparece la mega de Sceptile en la ficha de Treecko"). Ahora se recorren TODAS las
        // especies de la cadena (edges trae fromName/toName de cada eslabon; si no evoluciona de/a
        // nada, solo esta la propia [viewedName]) y se juntan las vitrinas de todas, sin repetir
        // si la misma apareciera dos veces por alguna rama.
        val chainSpecies = if (edges.isEmpty()) listOf(viewedName) else edges.flatMap { listOf(it.fromName, it.toName) }.distinct()
        val allMech = chainSpecies.map { PetState.speciesMechanics(this, it) }
        allMech.mapNotNull { it.genderShowcase }.distinctBy { it.title }.forEach { addMechanicSection(it) }
        allMech.mapNotNull { it.formShowcase }.distinctBy { it.title }.forEach { addMechanicSection(it) }
        allMech.mapNotNull { it.megaShowcase }.distinctBy { it.title }.forEach { addMechanicSection(it) }

        val scroll = ScrollView(this).apply { addView(root) }
        lastChainDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .show()
    }
    /** SOLO PRUEBAS: el ultimo dialogo de cadena evolutiva abierto (para volcarlo, ver "debug_chain"). */
    private var lastChainDialog: androidx.appcompat.app.AlertDialog? = null

    // ---------------- Adapter ----------------
    /** Estado de una celda de la cuadricula principal: el que cuidas (verde), en la mazmorra (rojo) o en la guarderia (rosa). */
    private enum class CellState { NONE, ACTIVE, DUNGEON, NURSERY }

    private inner class DexAdapter : RecyclerView.Adapter<MonVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonVH =
            MonVH(LayoutInflater.from(parent.context).inflate(R.layout.item_pokemon, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: MonVH, position: Int) {
            val row = rows[position]
            val unlocked = PetState.isUnlocked(this@MainActivity, row.name)
            // Normal/shiny coexistiendo (ver PetState.hasIndividual): si existe un individuo
            // shiny de esta especie, TIENE PRIORIDAD para decidir todo lo que se muestra en esta
            // fila (sprite, si esta "evolucionado", nivel) - aunque el normal tambien exista y
            // este en una fase distinta (bug real reportado: un Snivy shiny recien salido de un
            // huevo aparecia en gris "evoluciono" porque se miraba el estado del Snivy NORMAL, ya
            // evolucionado hace tiempo a Serperior - cada individuo es independiente, ver slot()).
            val hasShinyIndiv = unlocked && PetState.hasIndividual(this@MainActivity, row.name, shiny = true)
            val hasNormalIndiv = unlocked && PetState.hasIndividual(this@MainActivity, row.name, shiny = false)
            // Con los dos individuos a la vez, gana el que hayas mirado/elegido tu ultima vez en
            // su ficha (PetState.isShiny, la misma preferencia que ya usaba showDetailDialog para
            // esto - ver viewShiny mas abajo), no siempre el shiny a ciegas (pedido explicito del
            // usuario: antes esta fila SIEMPRE mostraba el shiny aunque estuvieras viendo/editando
            // el normal en su ficha, lo que confundia a los dos individuos entre si en mecanicas
            // por-individuo como el aparato de Rotom o el cartucho de Genesect).
            val shiny = when {
                hasNormalIndiv && hasShinyIndiv ->
                    if (row.name == selected) PetState.isActiveShiny(this@MainActivity) else PetState.isShiny(this@MainActivity, row.name)
                hasShinyIndiv -> true
                else -> false
            }
            // El tinte gris de "ya evoluciono" solo tiene sentido en "Mis Pokemon"/"Favoritos"
            // (tu roster real: el Pokemon en si ya no esta "activo", paso a su forma siguiente) -
            // en la Pokedex completa (registro de especies vistas) se ve a color igual que
            // cualquier otra especie conseguida, pedido explicito del usuario. Se consulta el
            // individuo shiny si existe (prioridad), si no el normal (comportamiento de siempre).
            // Fusion activa de otra especie (Kyurem/Necrozma/Calyrex): mientras dure, este
            // ingrediente se ve igual de "no disponible" que un evolucionado - ver
            // PetState.isFusionDonorBusy y el bloque equivalente en showDetailDialog.
            val fusionBusy = unlocked && PetState.isFusionDonorBusy(this@MainActivity, row.name)
            val evolvedAway = unlocked && (fusionBusy || (filterMode != FILTER_ALL && PetState.hasEvolvedAway(this@MainActivity, row.name, shiny = shiny)))
            holder.name.text = "#${row.id} ${PetState.displayLabel(row.name)}"
            // Estrellita: fue shiny alguna vez (el flag es por especie, se conserva aunque ya
            // haya evolucionado a otra cosa - no solo el Pokemon activo ahora mismo).
            holder.shinyStar.visibility = if (hasShinyIndiv) View.VISIBLE else View.GONE
            // Huevo: solo el Pokemon que lo puso (PetState.eggParent) lo muestra - el huevo es
            // GLOBAL (solo puede haber uno a la vez), no por especie. Sprite real (fase de grieta
            // actual, sin temblor) en vez de un emoji generico.
            val hasEgg = unlocked && PetState.hasActiveEgg(this@MainActivity) &&
                PetState.eggParent(this@MainActivity) == row.name
            if (hasEgg) {
                holder.eggBadge.setImageBitmap(
                    EffectGenerator.eggBitmap(this@MainActivity, PetState.eggCrackStage(this@MainActivity), hatched = false)
                )
                holder.eggBadge.visibility = View.VISIBLE
            } else {
                holder.eggBadge.visibility = View.GONE
            }
            // Lo NO conseguido usa el mismo tinte gris que lo evolucionado, pero revelando su
            // sprite real (no un "?" generico) para poder identificarlo de un vistazo.
            // Estado de la celda (pedido explicito del usuario): el que estas cuidando (activo) en verde, el que esta en la
            // mazmorra en rojo y el que esta en la guarderia en rosa. Se mira el individuo que se muestra en la fila; si es
            // el activo, el verde gana (la guarderia no admite al activo, y en la mazmorra solo puede coincidir si lo
            // pusiste activo mientras exploraba).
            val cellState = when {
                !unlocked -> CellState.NONE
                row.name == selected -> CellState.ACTIVE
                DungeonState.isInParty(this@MainActivity, row.name, shiny) -> CellState.DUNGEON
                PetState.isInNursery(this@MainActivity, row.name, shiny) -> CellState.NURSERY
                else -> CellState.NONE
            }
            holder.name.setTextColor(
                when (cellState) {
                    CellState.ACTIVE -> Color.parseColor("#1B5E20")
                    CellState.DUNGEON -> Color.parseColor("#8E1010")
                    CellState.NURSERY -> Color.parseColor("#AD1457")
                    CellState.NONE -> if (row.name == selected) Color.parseColor("#1B5E20") else Color.parseColor("#EEEEEE")
                }
            )
            if (unlocked && !evolvedAway) holder.thumb.clearColorFilter()
            else holder.thumb.setColorFilter(Color.parseColor("#707070"))
            holder.thumb.setImageDrawable(null)
            // No basta con placeholder()/crossfade(false): si Coil no cancela a tiempo una
            // peticion anterior de OTRO Pokemon sobre esta MISMA celda reciclada, su resultado
            // podia llegar tarde y machacar la imagen correcta. Aqui se hace la peticion "a mano"
            // (ImageRequest+target) y se guarda en la vista (via tag) para QUE Pokemon es esta
            // carga; al llegar el resultado, solo se aplica si la celda SIGUE siendo de ese mismo
            // Pokemon (si no, se descarta sin mas).
            val expected = row.name
            holder.thumb.tag = expected
            // displaySpriteName: para Tatsugiri/Zygarde ensena la forma actual (registrada la
            // ultima vez que se tuvo), no siempre la misma - ver PetState.displaySpriteName.
            val spriteName = PetState.displaySpriteName(this@MainActivity, row.name, shiny)
            loadLocalOrNetworkThumb(holder.thumb, spriteName, shiny, isStillValid = { holder.thumb.tag == expected }) {
                val req = ImageRequest.Builder(this@MainActivity)
                    .data("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(spriteName)}.png")
                    .target(
                        onSuccess = { d -> if (holder.thumb.tag == expected) holder.thumb.setImageDrawable(d) },
                        onError = { if (holder.thumb.tag == expected) holder.thumb.setImageResource(android.R.drawable.ic_menu_help) }
                    )
                    .build()
                holder.thumb.context.imageLoader.enqueue(req)
            }
            if (unlocked) {
                holder.lvl.setTextColor(if (evolvedAway) Color.parseColor("#999999") else Color.parseColor("#2E86E0"))
                val lvl = PetState.levelForPokemon(this@MainActivity, row.name, shiny = shiny)
                holder.lvl.text = when {
                    evolvedAway -> "→ evolucionó"
                    lvl != null -> "Nv. $lvl"
                    else -> ""
                }
            } else {
                holder.lvl.text = ""
            }
            holder.root.setBackgroundColor(
                when (cellState) {
                    CellState.ACTIVE -> Color.parseColor("#B7F0B1")     // verde: el que estas cuidando
                    CellState.DUNGEON -> Color.parseColor("#EF8F88")    // rojo: en la mazmorra
                    CellState.NURSERY -> Color.parseColor("#FBCFE3")    // rosa: en la guarderia
                    CellState.NONE -> Color.TRANSPARENT
                }
            )
            holder.root.setOnClickListener { onPick(row) }
        }

        fun refreshSelection(oldName: String, newName: String) {
            rows.forEachIndexed { i, r -> if (r.name == oldName || r.name == newName) notifyItemChanged(i) }
        }
    }

    private inner class MonVH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.item_root)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val lvl: TextView = v.findViewById(R.id.lvl)
        val shinyStar: TextView = v.findViewById(R.id.shiny_star)
        val eggBadge: ImageView = v.findViewById(R.id.egg_badge)
    }
}
