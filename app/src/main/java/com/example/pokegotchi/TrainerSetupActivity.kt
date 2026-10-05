package com.example.pokegotchi

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.sin

/**
 * Nickname + genero + retrato de entrenador, guiado por el Profesor Oak como en los juegos
 * (dialogo -> genero -> retrato filtrado por ese genero -> nombre) - pedido explicito del
 * usuario: "que Oak sea el que me guia... me pregunte el genero, luego me muestre los sprites
 * separados por genero". Mismo flujo para dos usos, distinguidos por [EXTRA_EDIT_MODE]: primer
 * arranque (obligatorio, con la charla introductoria, se muestra ANTES de StarterActivity - ver
 * MainActivity.onCreate) y edicion posterior desde la ficha de entrenador (salta la charla, va
 * directo a genero/retrato/nombre, se puede cancelar).
 */
class TrainerSetupActivity : AppCompatActivity() {

    // Lista PLANA, sin categorias (ver cabecera de TrainerSprites.kt: se probaron categorias por
    // rol y el usuario detecto un personaje real mal encajonado en "generico" solo porque el
    // nombre del fichero no lo delataba - sin poder confirmar el rol de cada sprite, cualquier
    // agrupacion por rol puede quedar mal, y eso no es fidedigno, pedido explicito del usuario:
    // "quitale la categoria a todos... que no va a ser fidedigno").
    private data class SpriteRow(val key: String, val label: String)

    private var editMode = false
    private var chosenGender: String = "chico"
    private var selectedSprite: String = PetState.DEFAULT_TRAINER_SPRITE
    private var typedName: String = ""
    private val rows = mutableListOf<SpriteRow>()
    private lateinit var adapter: SpriteAdapter

    private lateinit var oakText: TextView
    private lateinit var oakBox: View
    private lateinit var contentArea: View
    private lateinit var pokemonReveal: ImageView
    private lateinit var eggReveal: ImageView
    private lateinit var pokemonRevealFrame: FrameLayout
    private lateinit var stageFrame: FrameLayout
    private lateinit var ballThrow: ImageView
    private lateinit var ballBurst: ImageView
    private lateinit var widgetPreviewContainer: FrameLayout
    private lateinit var tutorialProgress: ProgressBar
    private lateinit var panelContinue: View
    private lateinit var panelGender: View
    private lateinit var panelSprite: View
    private lateinit var panelName: View
    private lateinit var nameInput: EditText
    private val uiHandler = Handler(Looper.getMainLooper())

    /** Antirebote del boton "Continuar" (pedido explicito del usuario: "me da la sensacion de
     *  que a veces salto dos textos") - un toque rapido/doble en el mismo instante que Android
     *  a veces entrega como dos eventos de click se ignora si llega a menos de 400ms del ultimo
     *  aceptado, en vez de avanzar dos pasos de golpe. */
    private var lastAdvanceAt = 0L
    private fun debouncedAdvance(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastAdvanceAt < 400L) return
        lastAdvanceAt = now
        action()
    }

    private var introIndex = 0
    private val introLines = listOf(
        "¡Hola! Siento haberte hecho esperar. Bienvenido al mundo de PokeGotchi. Yo soy el Profesor Oak.",
        "Este mundo está habitado por unas criaturas llamadas Pokémon. Vamos a conocernos un poco antes de empezar."
    )

    /** Tutorial de mecánicas tras elegir nombre y retrato (solo primer arranque, pedido explicito
     *  del usuario: "que Oak me explique todas las mecanicas y la interfaz") - mismo motor de
     *  dialogo que la intro, reutilizado como una fase mas de esta Activity. [spriteName]/[shiny]
     *  opcionales: si los hay, se cargan en pokemon_reveal (junto a Oak, igual que el aleatorio de
     *  la intro) para ilustrar esa mecanica concreta; si no, Oak se queda solo. Todas las mecanicas
     *  citadas son reales, verificadas en el codigo - nada inventado ni aspiracional.
     */
    private data class TutorialStep(
        val text: String,
        val spriteName: String? = null,
        val shiny: Boolean = false,
        // Si esta puesto, en vez de revelar spriteName directamente se hace una transformacion
        // rapida de [transformFrom] -> [spriteName] (parpadeo corto) - pedido explicito del
        // usuario para el paso de evolucion ("que se vea Charmander, la animacion rapida y luego
        // Charizard") y reutilizado igual para Megaevolucion.
        val transformFrom: String? = null,
        // Previsualizacion del widget real debajo del texto - pedido explicito del usuario,
        // "mantenlo en la siguiente vista" = tambien en el paso de nivel/experiencia.
        val showWidgetPreview: Boolean = false,
        // Previsualizacion del dialogo de regalo (oferta de especies) debajo del texto - pedido
        // explicito del usuario ("en el regalo pon debajo una previsualizacion").
        val showOfferPreview: Boolean = false,
        // Si esta puesto, [spriteName] pasa a ser la CRIA y aqui va el PADRE: se muestra el padre
        // en pokemon_reveal y un huevo en egg_reveal (a su derecha) que avanza de fase cada
        // segundo y eclosiona en la cria - pedido explicito del usuario.
        val eggParentName: String? = null,
        // Previsualizacion del selector de la Mazmorra debajo del texto - pedido explicito del
        // usuario ("en el tutorial falta mostrar lo de la mazmorra al jugador"), la Mazmorra es
        // una pantalla propia (DungeonActivity) sin sitio en el tutorial hasta ahora.
        val showDungeonPreview: Boolean = false,
    )
    private var tutorialIndex = -1
    private val tutorialLines = listOf(
        TutorialStep("Ahora que ya nos conocemos, deja que te explique cómo funciona todo esto - será rápido."),
        TutorialStep(
            "Tu Pokémon necesita que lo cuides: dale de comer, acarícialo y lávalo tocando los botones del widget o de la app - pero solo hace falta cuando él lo pide, no todo el rato.",
            showWidgetPreview = true
        ),
        TutorialStep("Cuidándolo bien, tu Pokémon gana experiencia y sube de nivel.", showWidgetPreview = true),
        TutorialStep("Al nivel suficiente, ¡tu Pokémon puede evolucionar!", spriteName = "charizard", transformFrom = "charmander"),
        TutorialStep("Muy de vez en cuando, un Pokémon nace... shiny. El mismo Pokémon, pero con colores distintos - rarísimo de encontrar.", spriteName = "gyarados", shiny = true),
        TutorialStep("Algunos Pokémon, a partir de cierto nivel, pueden Megaevolucionar o hacer Gigamax durante un tiempo limitado - una forma temporal aún más poderosa.", spriteName = "absol-mega", transformFrom = "absol"),
        TutorialStep(
            "Si tu Pokémon ya evolucionó del todo, de vez en cuando pondrá un huevo. Cuídalo y eclosionará en una cría de su misma familia.",
            spriteName = "charmander", eggParentName = "charizard"
        ),
        TutorialStep(
            "Cada cierto tiempo tendrás un regalo esperando: podrás elegir uno de varios Pokémon nuevos para tu Pokédex, sin tocar a tu compañero activo.",
            showOfferPreview = true
        ),
        TutorialStep(
            "Y con el icono 🗺️ de arriba entras a la Mazmorra: manda a cualquiera de tus Pokémon a explorar pisos, luchar y encontrar objetos - sigue avanzando incluso con la app cerrada.",
            showDungeonPreview = true
        ),
        TutorialStep("Al subir de nivel de entrenador ganarás fichas para desbloquear fondos nuevos - algunos incluso ayudan más a ciertos tipos de Pokémon."),
        TutorialStep("Puedes marcar tus Pokémon favoritos, y elegir uno para que aparezca en tu propia ficha de entrenador - la que se abre tocando tu nivel."),
        TutorialStep("En la Pokédex tienes tres pestañas: Mis Pokémon, Favoritos, y la Pokédex completa - con buscador y varias formas de ordenar."),
        TutorialStep("Y no olvides el widget de tu pantalla de inicio: tu Pokémon vive ahí también, siempre a un toque de distancia."),
        TutorialStep("Creo que ya lo sabes todo. ¡Ahora sí, vamos a elegir tu primer Pokémon!"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trainer_setup)
        // A partir de Android 15 (targetSdk 35+) el contenido se dibuja de borde a borde por
        // defecto - sin esto, la barra de progreso del tutorial (arriba del todo) quedaba debajo
        // de la barra de estado, atravesando la camara/notificaciones - pedido explicito del
        // usuario. Mismo parche que ya usa MainActivity.onCreate para su layout raiz.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.trainer_setup_root)) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        editMode = intent.getBooleanExtra(EXTRA_EDIT_MODE, false)
        if (editMode) {
            selectedSprite = PetState.trainerSprite(this)
            chosenGender = TrainerSprites.genderFor(selectedSprite)
            typedName = PetState.trainerName(this)
            findViewById<View>(R.id.btn_edit_cancel).visibility = View.VISIBLE
            findViewById<View>(R.id.btn_edit_cancel).setOnClickListener { finish() }
        }

        findViewById<ImageView>(R.id.oak_portrait).setImageBitmap(TrainerSprites.bitmap(this, "OAK"))
        oakText = findViewById(R.id.oak_text)
        oakBox = findViewById(R.id.oak_box)
        contentArea = findViewById(R.id.content_area)
        pokemonReveal = findViewById(R.id.pokemon_reveal)
        eggReveal = findViewById(R.id.egg_reveal)
        pokemonRevealFrame = findViewById(R.id.pokemon_reveal_frame)
        stageFrame = findViewById(R.id.stage_frame)
        ballThrow = findViewById(R.id.ball_throw)
        ballBurst = findViewById(R.id.ball_burst)
        widgetPreviewContainer = findViewById(R.id.widget_preview_container)
        tutorialProgress = findViewById(R.id.tutorial_progress)
        panelContinue = findViewById(R.id.panel_continue)
        panelGender = findViewById(R.id.panel_gender)
        panelSprite = findViewById(R.id.panel_sprite)
        panelName = findViewById(R.id.panel_name)
        nameInput = findViewById(R.id.input_nickname)

        // Retrato generico de cada genero en el propio boton (CHICO.png/CHICA.png, ya existen en
        // el pack) en vez de emojis - pedido explicito del usuario.
        findViewById<ImageView>(R.id.gender_boy_img).setImageBitmap(TrainerSprites.bitmap(this, "CHICO"))
        findViewById<ImageView>(R.id.gender_girl_img).setImageBitmap(TrainerSprites.bitmap(this, "CHICA"))
        findViewById<Button>(R.id.btn_continue_narration).setOnClickListener { debouncedAdvance(::advanceIntro) }
        findViewById<View>(R.id.btn_gender_boy).setOnClickListener { onGenderChosen("chico") }
        findViewById<View>(R.id.btn_gender_girl).setOnClickListener { onGenderChosen("chica") }
        findViewById<Button>(R.id.btn_sprite_confirm).setOnClickListener { showNamePanel() }
        findViewById<Button>(R.id.btn_name_confirm).setOnClickListener { onNameConfirmed() }

        val rv = findViewById<RecyclerView>(R.id.rv_trainer_sprites)
        rv.layoutManager = GridLayoutManager(this, 3)
        adapter = SpriteAdapter()
        rv.adapter = adapter

        val debugPreviewStep = intent.getIntExtra(EXTRA_DEBUG_PREVIEW_STEP, -1)
        if (debugPreviewStep in tutorialLines.indices) {
            findViewById<View>(R.id.btn_skip_tutorial).apply { visibility = View.VISIBLE; setOnClickListener { finish() } }
            tutorialIndex = debugPreviewStep
            showTutorialStep()
            findViewById<Button>(R.id.btn_continue_narration).setOnClickListener { debouncedAdvance(::advanceTutorial) }
            showPanel(panelContinue)
        } else if (editMode) {
            showGenderPanel()
        } else {
            oakText.text = introLines[0]
            showPanel(panelContinue)
        }
    }

    private fun advanceIntro() {
        introIndex++
        if (introIndex < introLines.size) {
            oakText.text = introLines[introIndex]
            // La linea que habla de "unas criaturas llamadas Pokemon" enseña uno de verdad, JUNTO
            // a Oak (no en su lugar - como si lo sacara de la Pokedex), sorteado entre los
            // sprites locales (assets/localsprites/normal) - pedido explicito del usuario.
            if (introIndex == 1) showRandomPokemon() else hideRandomPokemon()
        } else {
            hideRandomPokemon()
            showGenderPanel()
        }
    }

    private fun hideRandomPokemon() {
        uiHandler.removeCallbacksAndMessages(null)
        pokemonReveal.visibility = View.GONE
        eggReveal.visibility = View.GONE
        ballThrow.visibility = View.GONE
        ballBurst.visibility = View.GONE
        EffectGenerator.clearSparkles(pokemonRevealFrame)
    }

    /** Sortea un Pokemon local Y una Pokebola de assets/pokeballs, y lanza la animacion completa
     *  (ver animateBallThrow) - pedido explicito del usuario: "que pilles una Pokeball random...
     *  Oak lanza la Pokebola a la derecha y aparece el Pokemon". Si algo falta (sin bolas en el
     *  paquete, o esa especie sin sprite local), cae a revelar el Pokemon directamente sin
     *  animacion, en vez de dejar el hueco vacio. */
    private fun showRandomPokemon() {
        Thread {
            val names = try { assets.list("localsprites/normal")?.map { it.removeSuffix(".png") } ?: emptyList() }
                catch (_: Exception) { emptyList() }
            val bmp = names.randomOrNull()?.let {
                SpriteRepository.localFirstFrameScaled(this, it, false, PetState.spriteScaleMode(this))
            }
            val ballKeys = try {
                assets.list("pokeballs")
                    ?.filter { it.startsWith("ball_") && it.endsWith(".png") && !it.contains("_open") }
                    ?.map { it.removePrefix("ball_").removeSuffix(".png") }
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val ballKey = ballKeys.randomOrNull()
            val ballFrames = ballKey?.let { loadBallFrames(it) }
            val ballOpen = ballKey?.let { loadBallOpen(it) }
            runOnUiThread {
                if (bmp == null) return@runOnUiThread
                if (ballFrames.isNullOrEmpty() || ballOpen == null) {
                    pokemonReveal.setImageBitmap(bmp)
                    pokemonReveal.visibility = View.VISIBLE
                } else {
                    animateBallThrow(ballFrames, ballOpen, bmp)
                }
            }
        }.start()
    }

    /** Decodifica el strip de [ballKey] - NO son fotogramas cuadrados por el ALTO (los 26 tipos
     *  comprobados miden 256x64): cada fotograma real mide la MITAD de ancho que de alto (32x64,
     *  con relleno transparente arriba/abajo alrededor de una bola de ~30x30), 8 fotogramas en
     *  total, no 4. Cortar por el alto (64x64) partia cada recorte en DOS bolas seguidas - bug
     *  real reportado por el usuario ("veo dos Pokebolas"), verificado viendo el PNG de verdad. */
    private fun loadBallFrames(ballKey: String): List<Bitmap>? = try {
        val bytes = assets.open("pokeballs/ball_$ballKey.png").use { it.readBytes() }
        val strip = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val h = strip?.height ?: 0
        val w = h / 2
        if (strip == null || h <= 0 || w <= 0 || strip.width % w != 0) null
        else (0 until strip.width / w).map { i -> Bitmap.createBitmap(strip, i * w, 0, w, h) }
    } catch (_: Exception) { null }

    /** ball_X_open.png NO es una sola imagen: es una tira VERTICAL de fotogramas cuadrados
     *  (entreabierto -> abierto del todo), igual en los 26 tipos comprobados (todos 32x64 = 2
     *  fotogramas de 32x32). Mostrar el fichero entero de una se veia como "dos Pokebolas"
     *  apiladas - pedido explicito del usuario. Se recorta y se queda solo con el ULTIMO
     *  fotograma (el mas abierto). */
    private fun loadBallOpen(ballKey: String): Bitmap? = try {
        val bytes = assets.open("pokeballs/ball_${ballKey}_open.png").use { it.readBytes() }
        val strip = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (strip == null || strip.width <= 0 || strip.height % strip.width != 0) strip
        else {
            val frameCount = strip.height / strip.width
            val last = Bitmap.createBitmap(strip, 0, (frameCount - 1) * strip.width, strip.width, strip.width)
            if (last !== strip) strip.recycle()
            last
        }
    } catch (_: Exception) { null }

    /** Pokebola lanzada desde junto a Oak en ARCO (sube despacio, baja hacia el centro donde
     *  aparecera el Pokemon - pedido explicito del usuario, no una linea recta) + giro, tiembla
     *  con sus propios 4 fotogramas al "aterrizar", se abre (ballOpen) con un destello (ballBurst,
     *  ballBurst_ray.png) y por ultimo se revela [targetBmp] en pokemon_reveal. La distancia del
     *  arco se mide de verdad entre ballThrow y pokemon_reveal (ambos ya colocados dentro de
     *  stage_frame) en vez de una cifra fija - antes se iba demasiado lejos de la pantalla en
     *  algunos tamaños, pedido explicito del usuario. */
    private fun animateBallThrow(frames: List<Bitmap>, ballOpen: Bitmap, targetBmp: Bitmap) {
        ballThrow.translationX = 0f
        ballThrow.translationY = 0f
        ballThrow.rotation = 0f
        ballThrow.setImageBitmap(frames[0])
        ballThrow.visibility = View.VISIBLE
        // Delante de todo lo demas (Oak, pokemon_reveal_frame) SIEMPRE - antes se veia "desaparecer"
        // la bola al llegar al centro, porque no caia en el sitio exacto donde iba a aparecer el
        // Pokemon (ver mas abajo) y el usuario lo interpreto como que iba por detras - pedido
        // explicito del usuario, cinturon y tirantes.
        ballThrow.bringToFront()
        ballBurst.bringToFront()
        // ballThrow acaba de pasar de GONE a VISIBLE: su posicion real en pantalla solo existe
        // DESPUES de la siguiente pasada de layout, no en este mismo instante - post() la espera.
        // pokemon_reveal_frame, en cambio, esta SIEMPRE visible (ver XML) asi que su posicion ya
        // es fiable, pero se lee aqui igualmente para que ambas medidas sean del mismo instante.
        stageFrame.post {
            val density = resources.displayMetrics.density
            val stagePos = IntArray(2).also { stageFrame.getLocationOnScreen(it) }
            val ballPos = IntArray(2).also { ballThrow.getLocationOnScreen(it) }
            val targetPos = IntArray(2).also { pokemonRevealFrame.getLocationOnScreen(it) }
            // Centro real de cada uno (no su esquina superior-izquierda) - antes se alineaba la
            // esquina de la bola con la esquina del Pokemon, y como este es mucho mas ancho que la
            // bola, se quedaba corta del centro - pedido explicito del usuario ("no cae donde va a
            // caer el Pokemon").
            val targetCenterX = (targetPos[0] - stagePos[0]) + pokemonRevealFrame.width / 2f
            val targetCenterY = (targetPos[1] - stagePos[1]) + pokemonRevealFrame.height / 2f
            val ballCenterX = (ballPos[0] - stagePos[0]) + ballThrow.width / 2f
            val ballCenterY = (ballPos[1] - stagePos[1]) + ballThrow.height / 2f
            val distanceX = targetCenterX - ballCenterX
            val distanceY = targetCenterY - ballCenterY
            // Arco mas alto y mas lento - pedido explicito del usuario ("que vaya mas lenta...
            // arco mas alto").
            val arcHeight = 100f * density
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                addUpdateListener { va ->
                    val f = va.animatedValue as Float
                    ballThrow.translationX = distanceX * f
                    // Arco real: sube (Y negativa respecto a la linea recta) en la primera mitad,
                    // baja de vuelta a la trayectoria en la segunda - "sube un poco hacia arriba...
                    // y baja en el centro donde va a salir el Pokemon".
                    ballThrow.translationY = distanceY * f - arcHeight * sin(Math.PI * f).toFloat()
                    ballThrow.rotation = 720f * f
                    // Ciclar los propios fotogramas de temblor MIENTRAS vuela (antes se quedaba
                    // fija en frames[0] durante todo el vuelo y solo se animaba al aterrizar) -
                    // pedido explicito del usuario ("no veo que la Pokebola este animada mientras
                    // se mueve"). 3 vueltas completas a lo largo del vuelo.
                    val frameIdx = ((f * frames.size * 3).toInt()) % frames.size
                    ballThrow.setImageBitmap(frames[frameIdx])
                }
            }
            anim.doOnEndCompat {
                var i = 0
                fun wobble() {
                    if (i >= 6) {
                        ballThrow.setImageBitmap(ballOpen)
                        ballBurst.visibility = View.VISIBLE
                        uiHandler.postDelayed({
                            ballThrow.visibility = View.GONE
                            ballBurst.visibility = View.GONE
                            pokemonReveal.setImageBitmap(targetBmp)
                            pokemonReveal.visibility = View.VISIBLE
                        }, 180L)
                        return
                    }
                    ballThrow.setImageBitmap(frames[i % frames.size])
                    i++
                    uiHandler.postDelayed(::wobble, 90L)
                }
                wobble()
            }
            anim.start()
        }
    }

    /** ValueAnimator.doOnEnd no esta disponible sin la dependencia de animation-ktx - un listener
     *  a mano hace lo mismo (llamar solo si termino de verdad, no si se cancelo a medias). */
    private fun ValueAnimator.doOnEndCompat(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!isCancelled) action()
            }
            private var isCancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) {
                isCancelled = true
            }
        })
    }

    /** Transformacion rapida [fromName] -> [toName] (parpadeo corto, sin musica ni pantalla
     *  completa - a diferencia de playEvolutionAnimation/activateMegaWithAnimation, que dependen
     *  de un compañero activo real que aun no existe en este punto del primer arranque) - pedido
     *  explicito del usuario para el paso de evolucion del tutorial ("que se vea Charmander, la
     *  animacion rapida y luego Charizard"), reutilizada igual para el paso de Megaevolucion. */
    private fun playQuickTransformDemo(fromName: String, toName: String, shiny: Boolean) {
        Thread {
            val fromBmp = SpriteRepository.localFirstFrameScaled(this, fromName, shiny, PetState.spriteScaleMode(this))
            val toBmp = SpriteRepository.localFirstFrameScaled(this, toName, shiny, PetState.spriteScaleMode(this))
            runOnUiThread {
                if (fromBmp == null || toBmp == null) { hideRandomPokemon(); return@runOnUiThread }
                pokemonReveal.setImageBitmap(fromBmp)
                pokemonReveal.visibility = View.VISIBLE
                var t = 700L
                val flickerReps = 5
                for (i in 0 until flickerReps) {
                    val showTo = i % 2 == 1
                    uiHandler.postDelayed({ pokemonReveal.setImageBitmap(if (showTo) toBmp else fromBmp) }, t)
                    t += 90L
                }
                uiHandler.postDelayed({ pokemonReveal.setImageBitmap(toBmp) }, t)
            }
        }.start()
    }

    /** Huevo del paso de huevos: [parentName] se muestra en pokemon_reveal (el padre, ya
     *  evolucionado del todo), y a su derecha (egg_reveal) un huevo real (EffectGenerator.
     *  eggBitmap, el mismo dibujo que usa el widget) que avanza de fase 0->1->2->3 cada segundo
     *  (pedido explicito del usuario) y termina eclosionando en [babyName] - misma cria de la
     *  misma cadena evolutiva, igual que la mecanica real (ver PetState.maybeHatchEgg). */
    private fun playEggDemo(parentName: String, babyName: String) {
        Thread {
            val parentBmp = SpriteRepository.localFirstFrameScaled(this, parentName, false, PetState.spriteScaleMode(this))
            val babyBmp = SpriteRepository.localFirstFrameScaled(this, babyName, false, PetState.spriteScaleMode(this))
            runOnUiThread {
                if (parentBmp == null) { hideRandomPokemon(); return@runOnUiThread }
                pokemonReveal.setImageBitmap(parentBmp)
                pokemonReveal.visibility = View.VISIBLE
                eggReveal.setImageBitmap(EffectGenerator.eggBitmap(this, 0, hatched = false))
                eggReveal.visibility = View.VISIBLE
                var stage = 1
                fun tick() {
                    if (stage <= 3) {
                        eggReveal.setImageBitmap(EffectGenerator.eggBitmap(this, stage, hatched = false))
                        stage++
                        uiHandler.postDelayed(::tick, 1000L)
                    } else {
                        eggReveal.setImageBitmap(EffectGenerator.eggBitmap(this, 3, hatched = true))
                        uiHandler.postDelayed({ if (babyBmp != null) eggReveal.setImageBitmap(babyBmp) }, 700L)
                    }
                }
                uiHandler.postDelayed(::tick, 1000L)
            }
        }.start()
    }

    /** Limpia [widgetPreviewContainer] y construye el contenido que toque para [step] - se
     *  reconstruye cada vez (tres tipos posibles: widget, regalo o mazmorra, ver mas abajo) en vez
     *  de guardar una unica vista cacheada. */
    private fun showPreviewFor(step: TutorialStep) {
        val visible = step.showWidgetPreview || step.showOfferPreview || step.showDungeonPreview
        widgetPreviewContainer.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        widgetPreviewContainer.removeAllViews()
        when {
            step.showWidgetPreview -> buildWidgetPreview()
            step.showOfferPreview -> buildOfferPreview()
            step.showDungeonPreview -> buildDungeonPreview()
        }
    }

    /** Previsualizacion real del widget (mismo layout XML que el widget de verdad, inflado como
     *  vista normal en vez de RemoteViews - no hace falta AppWidgetHost para solo enseñarlo, ver
     *  widget_pokegotchi.xml), a tamaño GRANDE (llena todo widgetPreviewContainer, pedido
     *  explicito del usuario: "que ocupe toda la parte negra de abajo" - a tamaño pequeño el
     *  Pokemon salia diminuto y las barras enormes, ya que estas ultimas tienen un alto fijo en
     *  dp y el sprite se lleva solo el hueco que sobra) con un Pokemon pidiendo comida (misma
     *  nube de necesidad que ya usa el widget real, EffectGenerator.needCloud). */
    private fun buildWidgetPreview() {
        val view = LayoutInflater.from(this).inflate(R.layout.widget_pokegotchi, widgetPreviewContainer, false)
        widgetPreviewContainer.addView(view)
        val bgRes = resources.getIdentifier("bg_meadow", "drawable", packageName)
        if (bgRes != 0) view.findViewById<ImageView>(R.id.widget_bg).setImageResource(bgRes)
        view.findViewById<TextView>(R.id.widget_level).text = "Nv. 12"
        view.findViewById<ProgressBar>(R.id.xp_bar).progress = 40
        for (id in intArrayOf(R.id.stat_health, R.id.stat_hygiene, R.id.stat_happiness)) {
            view.findViewById<ProgressBar>(id).progress = 70
        }
        view.findViewById<Button>(R.id.btn_feed).text = "🍎❗"
        Thread {
            val bmp = SpriteRepository.localFirstFrameScaled(this, "pikachu", false, PetState.spriteScaleMode(this))
            val cloud = EffectGenerator.needCloud("🍎", 1f, 0.6f)
            runOnUiThread {
                if (bmp != null) {
                    val frame = LayoutInflater.from(this).inflate(R.layout.item_sprite_frame, view.findViewById<ViewGroup>(R.id.widget_pokemon), false)
                    frame.findViewById<ImageView>(R.id.frame_img).setImageBitmap(bmp)
                    view.findViewById<ViewGroup>(R.id.widget_pokemon).addView(frame)
                }
                view.findViewById<ImageView>(R.id.need_cloud).apply {
                    setImageBitmap(cloud)
                    visibility = View.VISIBLE
                }
            }
        }.start()
    }

    /** Ejemplos FIJOS (no al azar, pedido explicito del usuario: "que no sea random") para la
     *  previsualizacion del regalo diario - incluye un legendario (Mewtwo, ya marcado "rare" en
     *  evolutions.json) para que se note la chispa especial de un objeto raro en la oferta. */
    private val offerPreviewSpecies = listOf("pikachu", "eevee", "mewtwo", "charizard", "bulbasaur")

    /** Previsualizacion del regalo diario (oferta de especies, ver MainActivity.showOfferDialog):
     *  MISMA interfaz que el dialogo real (fondo, titulo, celdas redondeadas de hasta 3 por fila,
     *  chispas en el legendario) pero SIN los botones de accion, que aqui no hacen nada - pedido
     *  explicito del usuario ("hazlo igual, con la misma interfaz... quita los botones porque no
     *  van a servir"). Especies fijas (ver offerPreviewSpecies), no un sorteo real. */
    private fun buildOfferPreview() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bgRes = resources.getIdentifier("bg_meadow", "drawable", packageName)
            if (bgRes != 0) setImageResource(bgRes)
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        content.addView(TextView(this).apply {
            text = "🎁 Toca uno para añadirlo a tu Pokédex"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        val cellFrames = HashMap<String, FrameLayout>()
        offerPreviewSpecies.chunked(3).forEach { rowMons ->
            val rowLl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = dp(2)
                }
            }
            for (mon in rowMons) {
                val cellW = dp(84); val cellH = dp(78)
                val cellFrame = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(cellW, cellH).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#40FFFFFF")); cornerRadius = dp(12).toFloat()
                    }
                }
                cellFrames[mon] = cellFrame
                rowLl.addView(cellFrame)
            }
            content.addView(rowLl)
        }
        frame.addView(content)
        widgetPreviewContainer.addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        Thread {
            val bmps = offerPreviewSpecies.associateWith { SpriteRepository.localFirstFrameScaled(this, it, false, PetState.spriteScaleMode(this)) }
            runOnUiThread {
                for (mon in offerPreviewSpecies) {
                    val cellFrame = cellFrames[mon] ?: continue
                    cellFrame.addView(ImageView(this).apply {
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        setImageBitmap(bmps[mon])
                    })
                    // Legendario/mitico: borde morado que late (las estrellitas son del shiny) - ver
                    // EffectGenerator.addLegendaryBorder.
                    if (PetState.evolutionInfo(this, mon)?.rare == true) {
                        EffectGenerator.addLegendaryBorder(this, cellFrame, dp(12).toFloat(), dp(3))
                    }
                }
            }
        }.start()
    }

    /** Ejemplos FIJOS (mismo criterio que [offerPreviewSpecies]: nada al azar en el tutorial) para
     *  la previsualizacion de la Mazmorra - ya usados en pasos anteriores del propio tutorial, asi
     *  que sus sprites locales ya se han cargado alguna vez en esta misma pantalla. */
    private val dungeonPreviewSpecies = listOf("charizard" to 44, "gyarados" to 30, "sceptile" to 22)

    /** Previsualizacion del selector de la Mazmorra (ver DungeonActivity.appendPickerGridRows):
     *  MISMO fondo/celda que la pantalla real (fondo de cueva, celda oscura con sprite + nombre +
     *  nivel) pero sin accion alguna al tocar - pedido explicito del usuario ("en el tutorial falta
     *  mostrar lo de la mazmorra al jugador"), mismo patron que [buildOfferPreview]. */
    private fun buildDungeonPreview() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bgRes = resources.getIdentifier("bg_earthycave", "drawable", packageName)
            if (bgRes != 0) setImageResource(bgRes)
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        content.addView(TextView(this).apply {
            text = "🗺️ Elige quién explora la mazmorra"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        val rowLl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cellImgs = HashMap<String, ImageView>()
        for ((mon, level) in dungeonPreviewSpecies) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                setBackgroundColor(Color.parseColor("#332F3B"))
                setPadding(dp(6), dp(8), dp(6), dp(8))
            }
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(50))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            cellImgs[mon] = img
            cell.addView(img)
            cell.addView(TextView(this).apply {
                text = PetState.displayLabel(mon)
                textSize = 10f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
            })
            cell.addView(TextView(this).apply {
                text = "Nv. $level"
                textSize = 9f
                setTextColor(Color.parseColor("#B8B0C8"))
                gravity = android.view.Gravity.CENTER
            })
            rowLl.addView(cell)
        }
        content.addView(rowLl)
        frame.addView(content)
        widgetPreviewContainer.addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        Thread {
            val bmps = dungeonPreviewSpecies.associate { (mon, _) -> mon to SpriteRepository.localFirstFrameScaled(this, mon, false, PetState.spriteScaleMode(this)) }
            runOnUiThread {
                for ((mon, _) in dungeonPreviewSpecies) {
                    cellImgs[mon]?.setImageBitmap(bmps[mon])
                }
            }
        }.start()
    }

    /** El area de Oak domina la pantalla en narracion/genero/nombre (pedido explicito del
     *  usuario: "usa todo el espacio disponible para oak y el texto"); se encoge SOLO cuando toca
     *  la rejilla de retratos, que si necesita el espacio de verdad. */
    private fun setOakBoxWeight(big: Boolean) {
        (oakBox.layoutParams as LinearLayout.LayoutParams).weight = if (big) 3f else 1f
        (contentArea.layoutParams as LinearLayout.LayoutParams).weight = if (big) 2f else 4f
        oakBox.requestLayout()
        contentArea.requestLayout()
    }

    private fun showGenderPanel() {
        oakText.text = "Antes de nada... ¿eres chico o chica?"
        showPanel(panelGender)
    }

    private fun onGenderChosen(gender: String) {
        chosenGender = gender
        rebuildRows()
        // Si el retrato ya elegido no encaja con el genero recien elegido (o aun no se ha
        // elegido ninguno de verdad), se cae al primero disponible de esa lista - evita guardar
        // en silencio un retrato del genero contrario si el jugador pulsa "Continuar" sin tocar
        // ninguna celda.
        val keysOfGender = rows.map { it.key }
        if (selectedSprite !in keysOfGender) selectedSprite = keysOfGender.firstOrNull() ?: selectedSprite
        adapter.notifyDataSetChanged()
        oakText.text = "¡Ya veo! Ahora dime, ¿cuál de estos entrenadores se parece más a ti?"
        showPanel(panelSprite)
    }

    private fun rebuildRows() {
        rows.clear()
        val keys = TrainerSprites.list(this).filter { TrainerSprites.genderFor(it) == chosenGender }
        // Varios personajes claramente DISTINTOS comparten el mismo nombre generico
        // (TrainerSprites.displayNameFor) - ej. dos "Entrenador" sin identificar. En vez de
        // forzar una etiqueta unica por fichero (perderia informacion real), se numera la 2ª,
        // 3ª... aparicion de cada nombre DENTRO de este genero - pedido explicito del usuario
        // tras ver nombres repetidos en la rejilla.
        val nameCount = mutableMapOf<String, Int>()
        for (key in keys) {
            val base = TrainerSprites.displayNameFor(key)
            val n = (nameCount[base] ?: 0) + 1
            nameCount[base] = n
            rows.add(SpriteRow(key, if (n == 1) base else "$base $n"))
        }
    }

    private fun showNamePanel() {
        oakText.text = "¡Un gran estilo! Por cierto... ¿cómo te llaman?"
        nameInput.setText(typedName)
        showPanel(panelName)
    }

    private fun onNameConfirmed() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Escribe un nombre antes de continuar", Toast.LENGTH_SHORT).show()
            return
        }
        typedName = name
        PetState.setTrainerName(this, typedName)
        PetState.setTrainerSprite(this, selectedSprite)
        if (editMode) {
            finish()
            return
        }
        oakText.text = "¿$typedName, eh? ¡Un nombre estupendo!"
        findViewById<Button>(R.id.btn_continue_narration).setOnClickListener { debouncedAdvance(::startTutorial) }
        showPanel(panelContinue)
    }

    private fun startTutorial() {
        findViewById<View>(R.id.btn_skip_tutorial).apply {
            visibility = View.VISIBLE
            setOnClickListener { launchMain() }
        }
        tutorialIndex = 0
        showTutorialStep()
        findViewById<Button>(R.id.btn_continue_narration).setOnClickListener { debouncedAdvance(::advanceTutorial) }
        showPanel(panelContinue)
    }

    private fun showTutorialStep() {
        val step = tutorialLines[tutorialIndex]
        oakText.text = step.text
        // Cuanto queda del tutorial - pedido explicito del usuario ("una barra que informe de
        // cuanto le queda... para que sepa que no es tanto").
        tutorialProgress.visibility = View.VISIBLE
        tutorialProgress.max = tutorialLines.size
        tutorialProgress.progress = tutorialIndex + 1
        // Las estrellitas del Shiny quedan pegadas a pokemonRevealFrame en bucle infinito hasta
        // que se limpien explicitamente (EffectGenerator.playShinySparkles) - sin este corte se
        // verian tambien en el paso siguiente (Megaevolucion) si no se llega a pasar por
        // hideRandomPokemon.
        EffectGenerator.clearSparkles(pokemonRevealFrame)
        showPreviewFor(step)
        val toName = step.spriteName
        val fromName = step.transformFrom
        val eggParent = step.eggParentName
        when {
            eggParent != null && toName != null -> {
                uiHandler.removeCallbacksAndMessages(null)
                ballThrow.visibility = View.GONE
                ballBurst.visibility = View.GONE
                playEggDemo(eggParent, toName)
            }
            toName != null && fromName != null -> playQuickTransformDemo(fromName, toName, step.shiny)
            toName != null -> {
                uiHandler.removeCallbacksAndMessages(null)
                ballThrow.visibility = View.GONE
                ballBurst.visibility = View.GONE
                Thread {
                    val bmp = SpriteRepository.localFirstFrameScaled(this, toName, step.shiny, PetState.spriteScaleMode(this))
                    runOnUiThread {
                        if (bmp != null) {
                            pokemonReveal.setImageBitmap(bmp)
                            pokemonReveal.visibility = View.VISIBLE
                            // Estrellitas del Shiny (EffectGenerator.playShinySparkles, ya usado
                            // en el hatch de huevos real), SOLO alrededor del Pokemon
                            // (pokemonRevealFrame, no todo stageFrame que incluye a Oak) - pedido
                            // explicito del usuario ("las estrellas tienen que ser solo alrededor
                            // del Pokemon, no de todo").
                            if (step.shiny) pokemonRevealFrame.post {
                                EffectGenerator.playShinySparkles(this, pokemonRevealFrame, pokemonRevealFrame.width, pokemonRevealFrame.height)
                            }
                        } else hideRandomPokemon()
                    }
                }.start()
            }
            else -> hideRandomPokemon()
        }
    }

    private fun advanceTutorial() {
        tutorialIndex++
        if (tutorialIndex < tutorialLines.size) showTutorialStep() else launchMain()
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showPanel(target: View) {
        for (p in listOf(panelContinue, panelGender, panelSprite, panelName)) {
            p.visibility = if (p === target) View.VISIBLE else View.GONE
        }
        setOakBoxWeight(big = target !== panelSprite)
    }

    private inner class SpriteAdapter : RecyclerView.Adapter<SpriteVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SpriteVH =
            SpriteVH(LayoutInflater.from(parent.context).inflate(R.layout.item_trainer_sprite, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: SpriteVH, position: Int) {
            val row = rows[position]
            val key = row.key
            holder.root.setBackgroundColor(if (key == selectedSprite) Color.parseColor("#B7F0B1") else Color.TRANSPARENT)
            holder.name.text = row.label
            holder.img.setImageBitmap(null)
            // Mismo patron tag+expected que StarterActivity/DexAdapter: evita que un decode
            // tardio pinte la celda equivocada tras reciclarse.
            val expected = key
            holder.img.tag = expected
            Thread {
                val bmp = TrainerSprites.bitmap(holder.img.context, key)
                runOnUiThread { if (holder.img.tag == expected) holder.img.setImageBitmap(bmp) }
            }.start()
            holder.root.setOnClickListener {
                val prevIdx = rows.indexOfFirst { it.key == selectedSprite }
                selectedSprite = key
                if (prevIdx >= 0) notifyItemChanged(prevIdx)
                notifyItemChanged(position)
            }
        }
    }

    private class SpriteVH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.item_root)
        val img: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
    }

    companion object {
        const val EXTRA_EDIT_MODE = "edit_mode"
        // SOLO PARA PRUEBAS (disparado a mano por adb, nunca desde la propia app): salta
        // directamente a un paso del tutorial (indice en tutorialLines) SIN pasar por
        // genero/retrato/nombre - showTutorialStep no toca ninguno de esos campos ni PetState, asi
        // que es seguro para previsualizar un paso nuevo sin arriesgar la identidad real del
        // entrenador. Ejemplo (indice del paso de la Mazmorra, ver tutorialLines):
        // adb shell am start -n com.example.pokegotchi/.TrainerSetupActivity --ei debug_preview_step 7
        const val EXTRA_DEBUG_PREVIEW_STEP = "debug_preview_step"
    }
}
