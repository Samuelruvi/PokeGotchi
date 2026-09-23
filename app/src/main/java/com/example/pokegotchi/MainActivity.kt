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
import android.widget.Button
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
        NotificationHelper.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        tvTrainerLevel.setOnClickListener {
            if (PetState.bgTokensAvailable(this) > 0) showSettingsDialog()
        }
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
        showLevel()
        WidgetRefresh.updateWidgets(this)   // refresca el widget al abrir (regenera sprites de cache antigua)
    }

    override fun onResume() {
        super.onResume()
        if (!PetState.hasChosenStarter(this)) return   // aun redirigiendo a StarterActivity
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
        showLevel()
        // Ofertas periodicas (Fase 3): NO hay popup automatico (mismo criterio que la
        // evolucion) - solo se genera en silencio si toca, y se avisa con el icono 🎁 del
        // encabezado; el jugador decide cuando mirarla.
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
                playShinySparkles(spriteFrame, w, h)
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
    private fun playShinySparkles(container: FrameLayout, w: Int, h: Int) {
        val positions = listOf(
            0.02f to 0.02f, 0.88f to 0.08f, 0.05f to 0.72f,
            0.90f to 0.68f, 0.42f to -0.08f, 0.48f to 0.85f
        )
        positions.forEachIndexed { i, (fx, fy) ->
            val star = TextView(this).apply {
                text = "✨"
                textSize = 18f
                alpha = 0f
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = (w * fx).toInt()
                    topMargin = (h * fy).toInt()
                }
            }
            container.addView(star)
            star.postDelayed({ sparkleLoop(star) }, i * 180L)
        }
    }

    private fun sparkleLoop(star: TextView) {
        if (!star.isAttachedToWindow) return
        star.alpha = 0f; star.scaleX = 0.4f; star.scaleY = 0.4f; star.rotation = 0f
        star.animate().alpha(1f).scaleX(1.2f).scaleY(1.2f).rotationBy(180f).setDuration(380)
            .withEndAction {
                if (!star.isAttachedToWindow) return@withEndAction
                star.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(380)
                    .withEndAction {
                        if (star.isAttachedToWindow) star.postDelayed({ sparkleLoop(star) }, (300..900).random().toLong())
                    }
                    .start()
            }
            .start()
    }

    /** Aviso de "¿seguro?" antes de quedarse con el Pokemon del huevo: deja claro que el Pokemon
     *  ACTUAL (con el que se lleva jugando hasta ahora) se pierde. La cria del huevo NO siempre
     *  empieza en nivel 1: si ya existia un individuo de esa misma especie+shiny sin evolucionar
     *  (ej. un Treecko normal "de repuesto" de un huevo anterior nunca evolucionado), setPokemon
     *  lo reactiva tal cual estaba en vez de resetearlo (ver isFreshIndividual, PetState.kt) -
     *  texto desactualizado detectado por el usuario tras el cambio que hizo que normal/shiny (y
     *  distintos individuos de la misma especie) convivan de verdad en vez de pisarse. */
    private fun confirmKeepEggHatch(hatchDialog: androidx.appcompat.app.AlertDialog, species: String) {
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
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¿Seguro?")
            .setMessage("Si sueltas a este Pokémon lo perderás para siempre. Tocará esperar a otro huevo desde cero.")
            .setPositiveButton("Sí, soltarlo") { _, _ ->
                dbg("huevo: soltado (especie ${PetState.eggHatchSpecies(this)}, shiny=${PetState.eggIsShiny(this)})")
                PetState.releaseEgg(this)
                NotificationHelper.cancelEggLaid(this)
                NotificationHelper.cancelEggHatched(this)
                WidgetRefresh.updateWidgets(this)
                Toast.makeText(this, "Has soltado al Pokémon. Tocará esperar a otro huevo desde cero.", Toast.LENGTH_LONG).show()
                hatchDialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

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
     *  del usuario ("verlo saliendo del huevo" ya en shiny). */
    private fun loadAnimatedSprite(imageView: ImageView, name: String, shiny: Boolean = false) {
        Thread {
            val local = SpriteRepository.localAnimatedFramesScaled(imageView.context, name, shiny, PetState.spriteScaleMode(imageView.context))
            runOnUiThread {
                if (local != null) {
                    playLocalFrames(imageView, local.first, local.second)
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
     */
    private fun showOfferDialog() {
        val offer = PetState.currentOffer(this)
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
            text = "🎁 Toca uno para añadirlo a tu Pokédex"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(14))
        })

        lateinit var dialog: androidx.appcompat.app.AlertDialog
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
                // Legendario/mitico (ver PetState.OfferMon/evolutionInfo.rare): mismo aviso
                // visual que un shiny al eclosionar (playShinySparkles), para que destaque solo
                // sobre el resto de la oferta - pedido explicito del usuario.
                val isRare = PetState.evolutionInfo(this@MainActivity, mon.name)?.rare == true
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
                loadAnimatedSprite(cell, mon.name)
                cellFrame.addView(cell)
                if (isRare) playShinySparkles(cellFrame, cellW, cellH)
                cellFrame.setOnClickListener {
                    dbg("regalo: elegido ${mon.name} de entre ${offer.joinToString(",") { it.name }}")
                    PetState.resolveOffer(this@MainActivity, mon.name)
                    NotificationHelper.cancelOfferReady(this@MainActivity)
                    dialog.dismiss()
                    refreshOfferBadge()
                    refreshRows()
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this@MainActivity, "¡${PetState.displayLabel(mon.name)} añadido a tu Pokédex!", Toast.LENGTH_LONG).show()
                }
                rowLl.addView(cellFrame)
            }
            content.addView(rowLl)
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
            dbg("regalo: descartado entero (${offer.joinToString(",") { it.name }})")
            PetState.dismissOffer(this)
            NotificationHelper.cancelOfferReady(this)
            refreshOfferBadge()
            dialog.dismiss()
        }
        btnLater.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Descarga el sprite de [opt] y, si sale bien, evoluciona [fromName] hacia el (migra
     * stats/nivel, marca [fromName] como "evolucionado" en la Pokedex) y refresca todo.
     * Se llama desde el boton "Evolucionar a X" del dialogo de detalle de un Pokemon. [shiny] es
     * el individuo CONCRETO que se esta evolucionando (el de la ficha abierta) - no se asume el
     * activo del widget, mismo motivo que pendingEvolutionFor (bug real: evolucionar un shiny
     * desde su ficha migraba las stats del individuo normal en su lugar).
     */
    private fun doEvolve(fromName: String, opt: PetState.EvoOption, shiny: Boolean) {
        // Resuelto UNA VEZ aqui, antes de preparar nada: normalmente es opt.name/opt.id tal cual,
        // salvo el sorteo raro de Ursaring -> Ursaluna Luna Carmesi (ver
        // PetState.resolveEvolutionTarget) - asi el sprite/grito que se descarga y lo que
        // finalmente se muestra ya son consistentes desde el principio, sin tener que "corregir"
        // nada a medias.
        val (toName, toId) = PetState.resolveEvolutionTarget(opt.name, opt.id)
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
                        PetState.evolveTo(this, fromName, toName, toId, shiny)
                        selected = toName
                        currentGen = allMons.find { it.name == selected }?.gen ?: currentGen
                        refreshRows()
                        buildGenRow()
                        adapter.notifyDataSetChanged()
                        showLevel()
                        WidgetRefresh.updateWidgets(this)
                        Toast.makeText(this, "¡Felicidades! Ahora tienes a ${PetState.displayLabel(toName)}", Toast.LENGTH_LONG).show()
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

    /** Recalcula [rows] segun el modo activo: "Mis Pokemon" (solo desbloqueados, todas las
     *  generaciones juntas), "Favoritos" (solo los marcados, ver isFavoriteSpecies) o "Pokedex
     *  completa" (solo [currentGen], como siempre); y segun [sortBy]: por numero de Pokedex, o
     *  por nivel (los bloqueados/sin nivel se van al final). Tambien oculta la fila de
     *  generaciones salvo en Pokedex completa (no aplica en los otros dos: los desbloqueados/
     *  favoritos normalmente son pocos, no hace falta filtrar mas). */
    private fun refreshRows() {
        var base = when (filterMode) {
            FILTER_MINE -> allMons.filter { PetState.isUnlocked(this, it.name) }
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

        // --- Fondo (rejilla de 3 columnas) ---
        root.addView(TextView(this).apply {
            text = "Fondo"; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid)
        buildBgGrid(grid)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(14)) })

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

        // --- Ver log: fuera del menu debug (pedido explicito del usuario) - no hace falta
        //     contraseña para esto, es solo diagnostico de lectura, no edita nada del juego. ---
        root.addView(Button(this).apply {
            text = "📋 Ver log"; isAllCaps = false
            setOnClickListener { showDebugLogDialog() }
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

    // ==================== MENU DEBUG (protegido por contraseña) ====================
    // Consolida aqui TODO lo que antes solo se podia hacer via adb (log persistente, forzar
    // fase del huevo, saltar horas, editar/anadir/borrar Pokemon a mano) - pedido explicito del
    // usuario para no depender de mi para probar cosas. La contrasena es una comprobacion local
    // simple (no hay nada que proteger de verdad, es un telefono personal) - solo evita que
    // alguien que coja el telefono y toque "Ajustes" se tropiece con esto sin querer.
    private val DEBUG_PASSWORD = "27021996"

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
            "✏️ Editar / añadir Pokémon" to { showDebugPokemonEditorDialog() }
        )
        items.forEach { (label, action) ->
            root.addView(Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener { action() }
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Menú debug")
            .setView(root)
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

    private fun showDebugLogDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val logText = try {
            val lines = DebugLog.file(this).readLines()
            lines.takeLast(300).joinToString("\n")
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
            .setTitle("Log (últimas 300 líneas)")
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar log") { _, _ ->
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
            text = if (tokens > 0) "Puedes desbloquear $tokens fondo(s) más ahora mismo (cada 5 niveles de entrenador, 1 a tu elección)"
                else "Sube de nivel a tus Pokémon para desbloquear más fondos (cada 5 niveles de entrenador, 1 a tu elección)"
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
            setTextColor(Color.parseColor("#666666"))
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
            setTextColor(Color.parseColor("#888888"))
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
        Thread {
            val meta = SpriteRepository.ensure(
                this, mon.name, mon.id, PetState.currentStyle(this), shiny, PetState.spriteScaleMode(this)
            )
            runOnUiThread {
                if (meta != null) {
                    val prev = selected
                    dbg("activo: $prev -> ${mon.name} (shiny=$shiny)")
                    PetState.setPokemon(this, mon.name, mon.id, shiny)
                    selected = mon.name
                    WidgetRefresh.updateWidgets(this)
                    showLevel()
                    adapter.refreshSelection(prev, mon.name)
                    Toast.makeText(this, "${PetState.displayLabel(mon.name)} está en el widget", Toast.LENGTH_SHORT).show()
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
                setTextColor(Color.parseColor("#666666"))
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
        dialog.show()
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
    private fun showEvolutionChoiceDialog(fromName: String, options: List<PetState.EvoOption>, shiny: Boolean) {
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
                    doEvolve(fromName, opt, shiny)
                }
                cellCol.addView(cell)
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
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ---------------- Adapter ----------------
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
            holder.name.setTextColor(
                if (row.name == selected) Color.parseColor("#1B5E20") else Color.parseColor("#EEEEEE")
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
                if (unlocked && row.name == selected) Color.parseColor("#B7F0B1") else Color.TRANSPARENT
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
