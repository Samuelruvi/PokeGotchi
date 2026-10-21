package com.example.pokegotchi

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.ObjectAnimator
import android.animation.ValueAnimator

/**
 * Consejos de PRIMERA VEZ: en vez de explicarlo todo en el tutorial inicial, cada mecanica se explica la primera vez
 * que importa (al abrir su pantalla, o cuando ocurre por primera vez), encima de la interfaz REAL. Pedido del usuario
 * (2026-10-17) tras el feedback de un probador: el tutorial era largo y pasivo. El tutorial de inicio (TrainerSetupActivity)
 * solo cubre nombre, la idea del juego, cuidar -> XP -> evolucion y el widget.
 *
 *  - Cada consejo se marca como visto (preferencias propias) al cerrarse y no vuelve a salir.
 *  - Los jugadores que YA tenian partida al actualizar los tienen todos como vistos ([initIfNeeded]).
 *  - Solo sale uno a la vez; si uno ya esta en pantalla, los demas esperan a la siguiente ocasion.
 *  - Se pueden ver y restablecer desde el menu Debug.
 */
object TutorialTips {

    enum class Tip(val key: String, val emoji: String, val title: String) {
        MAIN("main", "📖", "Tu colección"),
        DUNGEON("dungeon", "🗺️", "La mazmorra"),
        COMPANIONS("companions", "🤝", "Compañeros de mazmorra"),
        NURSERY("nursery", "🌱", "La guardería"),
        BACKPACK("backpack", "🎒", "La mochila"),
        EGG("egg", "🥚", "¡Tu Pokémon ha puesto un huevo!"),
        GIFT("gift", "🎁", "Tienes un regalo"),
        GIFT_PICK("giftpick", "🎁", "Elige tu regalo"),
        BACKGROUNDS("backgrounds", "🖼️", "Fichas de fondo"),
        MEGA("mega", "🔺", "Formas especiales"),
    }

    private const val PREFS = "pokegotchi_tips"
    private const val KEY_INIT = "initialized"

    // Un solo consejo en pantalla a la vez.
    private var showing = false

    /** Ultimo dialogo mostrado (solo para el volcado de pruebas). */
    var lastDialog: Dialog? = null
        private set

    /** Ultima pista sobre la interfaz mostrada (solo para el volcado de pruebas). */
    var lastOverlay: SpotlightOverlay? = null
        private set

    /** Un paso de una pista sobre la interfaz: [target] es el identificador de lo que se resalta (lo resuelve quien la muestra
     *  con su propio `resolve`: el boton, la fila...) o null para una tarjeta sin hueco. */
    class Step(val target: String?, val text: String, val tap: Boolean = false, val waitFor: String? = null, val hint: String? = null)

    /** Avisa a la pista que haya en pantalla de que ha ocurrido [name] (p. ej. "gift_picked" con el nombre del elegido). */
    fun notifyEvent(name: String, arg: String? = null) {
        lastOverlay?.takeIf { it.isAttachedToWindow }?.onEvent(name, arg)
    }

    /** SOLO PRUEBAS: otras ventanas (dialogos) que hay que pintar entre la actividad y la pista al volcar la pantalla entera. */
    val debugWindows = ArrayList<View>()

    /** SOLO PRUEBAS: la pantalla entera tal como se ve (actividad + [extra] + la pista), cada ventana en su sitio. */
    fun debugComposite(context: Context, base: View, extra: List<View> = emptyList(), overlay: View? = null): android.graphics.Bitmap {
        val dm = context.resources.displayMetrics
        val b = android.graphics.Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(Color.parseColor("#303030"))
        for (v in listOfNotNull(base) + extra + listOfNotNull(overlay)) {
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            c.save(); c.translate(loc[0].toFloat(), loc[1].toFloat()); v.draw(c); c.restore()
        }
        return b
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Llamar al arrancar la app, ANTES de decidir nada: si ya habia una partida (inicial elegido) cuando aparece esta
     *  funcion por primera vez, todos los consejos se dan por vistos (el jugador ya conoce esas pantallas). En una
     *  instalacion nueva aun no hay inicial, asi que los consejos quedan sin ver. */
    fun initIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_INIT, false)) return
        val e = p.edit().putBoolean(KEY_INIT, true)
        if (PetState.hasChosenStarter(context)) Tip.values().forEach { e.putBoolean("seen.${it.key}", true) }
        e.apply()
    }

    fun seen(context: Context, tip: Tip): Boolean = prefs(context).getBoolean("seen.${tip.key}", false)

    fun markSeen(context: Context, tip: Tip) { prefs(context).edit().putBoolean("seen.${tip.key}", true).apply() }

    /** Vuelve a dejar todos sin ver (para probarlos desde Debug). */
    fun resetAll(context: Context) {
        val e = prefs(context).edit().putBoolean(KEY_INIT, true)
        Tip.values().forEach { e.putBoolean("seen.${it.key}", false) }
        e.apply()
    }

    /** ¿Tiene sentido enseñar este consejo ahora mismo? (p. ej. el de compañeros solo si ya se desbloquearon). */
    private fun applicable(context: Context, tip: Tip): Boolean = when (tip) {
        Tip.COMPANIONS -> DungeonState.maxCompanions(context) > 0
        Tip.NURSERY -> PetState.nurseryUnlocked(context)
        Tip.EGG -> PetState.hasActiveEgg(context)
        Tip.GIFT, Tip.GIFT_PICK -> PetState.currentOffer(context).isNotEmpty()
        Tip.BACKGROUNDS -> PetState.bgTokensAvailable(context) > 0
        else -> true
    }

    /** Texto de cada consejo: corto (3-4 lineas) y con los numeros reales del juego (no inventados). */
    fun lines(context: Context, tip: Tip): List<String> = when (tip) {
        Tip.MAIN -> listOf(
            "Aquí están todos tus Pokémon: «Mis Pokémon», «Favoritos» y la Pokédex completa, con buscador y orden.",
            "El que estás cuidando sale en verde, el que está en la mazmorra en rojo y el de la guardería en rosa.",
            "Toca tu nivel de entrenador para ver tu ficha: ahí eliges tu favorito y los fondos.",
            "Según subas de nivel de entrenador irás desbloqueando cosas nuevas: te las explico al abrirlas."
        )
        Tip.DUNGEON -> listOf(
            "Manda a uno de tus Pokémon a explorar pisos: lucha solo y recoge caramelos, bayas y objetos.",
            "Sigue avanzando aunque cierres la app. Los caramelos que encuentra le dan XP de verdad.",
            "Si cae o llega al final, la mazmorra se cierra un rato (más cuanto más lejos llegó). En modo 🔁 Continuo empieza otra carrera sola.",
            "El Pokémon que estás cuidando en el widget no puede explorar."
        )
        Tip.COMPANIONS -> {
            val m2 = ExplorerPartyRules.enemyMultiplier(2); val m3 = ExplorerPartyRules.enemyMultiplier(3)
            // Multiplicador con hasta 2 decimales y coma (1,15 / 1,3); formatXp solo da 1 decimal y dejaria 1,15 en 1,1.
            fun f(x: Float) = String.format(java.util.Locale.US, "%.2f", x).trimEnd('0').trimEnd('.').replace('.', ',')
            listOf(
                "Desde el nivel de entrenador ${ExplorerPartyRules.FIRST_COMPANION_LEVEL} puedes llevar un compañero (dos desde el ${ExplorerPartyRules.SECOND_COMPANION_LEVEL}): al mandar a explorar, toca «Añadir un compañero».",
                "Los enemigos van a por cualquiera del grupo, y cuando alguien es atacado pelean todos. Solo pega el que llega al enemigo: en un pasillo estrecho, el de delante; en una sala, los demás se acercan.",
                "Se reparten la XP a partes iguales, y la carrera solo acaba cuando caen todos. Si al de delante le queda poca vida, se cambia con un compañero.",
                "Con más Pokémon, cada piso trae más enemigos (×${f(m2)} con dos, ×${f(m3)} con tres)."
            )
        }
        Tip.NURSERY -> listOf(
            "Los Pokémon que metas aquí ganan XP sin que los cuides (la mitad que el activo) y andan sueltos por el jardín.",
            "Comen de la cesta, beben y juegan con la pelota por su cuenta, y eso también les da XP. Puedes arrastrar la cesta, el bebedero y la pelota (incluso lanzarla).",
            "Los que pueden poner huevos los incuban solos: si sale un shiny te lo quedas, y si no, se convierte en un caramelo.",
            "Uno dentro no puede ser el activo del widget ni explorar la mazmorra. El ⚙ sirve para meter y sacar Pokémon."
        )
        Tip.BACKPACK -> listOf(
            "Aquí se guardan los caramelos. Los consigues soltando un huevo.",
            "Un caramelo da XP a cualquier Pokémon tuyo, y vale lo mismo para uno de nivel bajo que para uno de nivel alto."
        )
        Tip.EGG -> listOf(
            "Cuando un Pokémon ya ha evolucionado del todo, de vez en cuando pone un huevo. Lo verás en la lista, junto a quien lo puso.",
            "Con el tiempo eclosiona en una cría de su misma familia. Entonces puedes quedártela o soltarla (a cambio de un caramelo)."
        )
        Tip.GIFT -> listOf(
            "Cada cierto tiempo te espera un regalo (el 🎁 de arriba): tócalo y elige uno de varios Pokémon nuevos para tu Pokédex.",
            "No cambia a tu compañero activo."
        )
        Tip.GIFT_PICK -> listOf(
            "Toca una de las opciones que te han tocado para añadirla a tu Pokédex (te pide confirmación).",
            "«Descartar» tira la oferta entera para que salgan otros; «Ahora no» la deja guardada."
        )
        Tip.BACKGROUNDS -> listOf(
            "Al subir de nivel de entrenador ganas fichas para desbloquear fondos nuevos (una cada ${PetState.trainerLevelsPerToken()} niveles).",
            "Toca tu nivel de entrenador y luego «🖼️ Fondos». Algunos fondos ayudan más a ciertos tipos de Pokémon."
        )
        Tip.MEGA -> listOf(
            "Este Pokémon puede cambiar a una forma especial (Megaevolución, Gigamax o fusión) durante un tiempo limitado.",
            "Pulsa el botón 🔺. Al acabar el tiempo vuelve a su forma normal y hay que esperar un enfriamiento para repetirlo."
        )
    }

    /** Pasos del consejo [tip] cuando se enseña SOBRE LA INTERFAZ REAL (resaltando botones: ver [SpotlightOverlay]); null si ese
     *  consejo no tiene un sitio donde apuntar y se enseña como tarjeta (ver [lines]). Los identificadores de destino los
     *  resuelve quien lo muestra. */
    fun stepsFor(context: Context, tip: Tip, textArgs: List<String>? = null): List<Step>? = when (tip) {
        Tip.MAIN -> listOf(
            Step("tab_mine", "Mis Pokémon: todos los que ya tienes. El que estás cuidando sale en verde, el que está en la mazmorra en rojo y el de la guardería en rosa."),
            Step("tab_favorites", "Favoritos: los que marques con 🤍 en su ficha."),
            Step("tab_all", "Pokédex completa: todas las especies del juego, con buscador y por generaciones."),
            Step("btn_sort", "Aquí cambias el orden de la lista (número, nivel, fecha, nombre o shiny primero). La app se acuerda de tu elección."),
            Step("tv_trainer_level", "Tu nivel de entrenador. Tócalo para ver tu ficha; según lo subas irás desbloqueando cosas nuevas y te las explico al abrirlas."),
            Step("btn_dungeon", "Y aquí entras a la mazmorra: manda a un Pokémon a explorar. Te lo cuento cuando entres.")
        )
        Tip.DUNGEON -> listOf(
            Step("picker", "Toca a uno de tus Pokémon para mandarlo a explorar. El que cuidas en el widget no puede ir."),
            Step(null, "Lucha solo y recoge caramelos, bayas y objetos, y sigue avanzando aunque cierres la app: los caramelos le dan XP de verdad. Si cae o llega al final, la mazmorra se cierra un rato (más cuanto más lejos llegó); en modo 🔁 Continuo empieza otra carrera sola.")
        )
        Tip.COMPANIONS -> {
            val m2 = ExplorerPartyRules.formatMultiplier(ExplorerPartyRules.enemyMultiplier(2))
            val m3 = ExplorerPartyRules.formatMultiplier(ExplorerPartyRules.enemyMultiplier(3))
            listOf(
                Step("party", "Desde el nivel de entrenador ${ExplorerPartyRules.FIRST_COMPANION_LEVEL} puedes llevar un compañero (dos desde el ${ExplorerPartyRules.SECOND_COMPANION_LEVEL}). Se eligen aquí."),
                Step(null, "Los enemigos van a por cualquiera del grupo, y cuando alguien es atacado pelean todos. Solo pega el que llega al enemigo: en un pasillo estrecho, el de delante; en una sala, los demás se acercan."),
                Step(null, "Se reparten la XP a partes iguales, y la carrera solo acaba cuando caen todos. Si al de delante le queda poca vida, se cambia con un compañero. Con más Pokémon, cada piso trae más enemigos (×$m2 con dos, ×$m3 con tres).")
            )
        }
        Tip.NURSERY -> listOf(
            Step(null, "Los Pokémon que metas aquí ganan XP sin que los cuides (la mitad que el activo) y andan sueltos por el jardín."),
            Step("gear", "El ⚙ sirve para meter y sacar Pokémon y elegir quién pone huevos. Los que pueden ponerlos los incuban solos: si sale un shiny te lo quedas, y si no, se convierte en un caramelo."),
            Step(null, "Comen de la cesta, beben y juegan con la pelota por su cuenta, y eso también les da XP. Puedes arrastrar la cesta, el bebedero y la pelota (incluso lanzarla). Uno dentro no puede ser el activo del widget ni explorar la mazmorra.")
        )
        Tip.BACKPACK -> listOf(
            Step("btn_bag", "¡Mochila desbloqueada! Aquí se guardan los caramelos. Los consigues soltando un huevo, y cada uno da XP a cualquier Pokémon tuyo, tenga el nivel que tenga.")
        )
        // El regalo es interactivo: primero hay que tocar el 🎁 de verdad y DENTRO de su ventana se explican las posibilidades.
        Tip.GIFT -> listOf(
            Step("btn_offer", "¡Tienes un regalo! Cada cierto tiempo te espera uno. Ábrelo tocando el 🎁.", tap = true)
        )
        Tip.GIFT_PICK -> {
            // Las opciones REALES que le han tocado al jugador (en el dialogo salen sin nombre): [textArgs] las trae quien lo muestra.
            val names = (textArgs ?: PetState.currentOffer(context).map { PetState.displayLabel(it.name) })
                .map { n -> n.replaceFirstChar { it.uppercase() } }
            val list = when (names.size) {
                0 -> ""
                1 -> names[0]
                else -> names.dropLast(1).joinToString(", ") + " y " + names.last()
            }
            listOf(
                Step("offer_grid", "Estas son las opciones que te han tocado" + (if (list.isNotEmpty()) ": $list." else ".")),
                Step("offer_buttons", "«Descartar» tira esta oferta entera para que salgan otras opciones más adelante; «Ahora no» la deja guardada para elegir luego."),
                // Se elige de verdad: se pulsa el candidato (la ventana del regalo sigue abierta) y al confirmar continua la pista.
                Step("offer_grid", "Ahora elige el que quieras tocándolo: se añade a tu Pokédex (te pedirá confirmación) y tu compañero activo no cambia.",
                    tap = true, waitFor = "gift_picked", hint = "👆 Toca el Pokémon que quieras"),
                Step("tab_mine", "¡Listo! {arg} ya está en tu Pokédex: lo encontrarás en «Mis Pokémon».")
            )
        }
        Tip.MEGA -> listOf(
            Step("mega", "Este Pokémon puede cambiar a una forma especial (Megaevolución, Gigamax o fusión) durante un tiempo limitado. Pulsa este botón; al acabar el tiempo vuelve a su forma normal y hay que esperar un enfriamiento para repetirlo.")
        )
        Tip.BACKGROUNDS -> listOf(
            Step("tv_trainer_level", "Al subir de nivel de entrenador ganas fichas para desbloquear fondos nuevos (una cada ${PetState.trainerLevelsPerToken()} niveles). Toca tu nivel y luego «🖼️ Fondos»; algunos ayudan más a ciertos tipos de Pokémon.")
        )
        else -> null   // el huevo: no hay un sitio fijo de la pantalla al que apuntar (se enseña como tarjeta)
    }

    /** Enseña [tip] si no se ha visto ni hay otro en pantalla (o siempre con [force], para las pruebas). Devuelve si salio.
     *  Con [resolve] (identificador -> vista real) y pasos definidos ([stepsFor]) se enseña SOBRE LA INTERFAZ, resaltando lo que
     *  se explica, en la ventana [host] (por defecto la de la actividad; un dialogo a pantalla completa pasa la suya); si no, como
     *  tarjeta. [onClose] solo se llama si el jugador llega al final (o salta): no si la pantalla se cierra con la pista a medias. */
    fun show(
        activity: Activity, tip: Tip, force: Boolean = false, host: android.view.Window? = null,
        resolve: ((String) -> View?)? = null, onScreen: Boolean = false, textArgs: List<String>? = null, onClose: (() -> Unit)? = null
    ): Boolean {
        if (!force && (seen(activity, tip) || !applicable(activity, tip))) return false
        if (showing || activity.isFinishing || activity.isDestroyed) return false
        val steps = if (resolve != null) stepsFor(activity, tip, textArgs) else null
        if (steps != null && resolve != null) {
            val onEnd = { finished: Boolean ->
                showing = false
                if (finished) { markSeen(activity, tip); onClose?.invoke() }
            }
            if (onScreen) {
                // En una capa propia a pantalla completa POR ENCIMA de todo (ventanas flotantes como la de la mazmorra o dialogos
                // como el del regalo): asi la tarjeta tiene sitio de sobra fuera de esa ventana y el hueco apunta a su contenido.
                val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
                dialog.window?.apply {
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
                }
                showing = true
                val overlay = SpotlightOverlay(activity, tip, steps, resolve, onEnd)
                overlay.onRemove = { try { dialog.dismiss() } catch (_: Exception) {} }
                lastOverlay = overlay
                dialog.setContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                dialog.show()
                return true
            }
            val decor = (host ?: activity.window).decorView as? ViewGroup ?: return false
            showing = true
            val overlay = SpotlightOverlay(activity, tip, steps, resolve, onEnd)
            lastOverlay = overlay
            decor.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return true
        }
        showing = true
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26222E"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), Color.parseColor("#C9A227"))
            }
        }
        // Icono grande que "respira" y se mueve un poco: da vida al consejo sin necesitar imagenes nuevas.
        val icon = TextView(activity).apply {
            text = tip.emoji
            textSize = 46f
            gravity = Gravity.CENTER
        }
        card.addView(icon)
        ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -dp(7).toFloat()).apply {
            duration = 700; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator(); start()
        }
        card.addView(TextView(activity).apply {
            text = tip.title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        })
        // Cada linea entra con un pequeño retardo (aparece y sube): se lee a su ritmo y no es un muro de texto.
        val lineViews = lines(activity, tip).map { text ->
            TextView(activity).apply {
                this.text = "▸  $text"
                textSize = 14.5f
                setTextColor(Color.parseColor("#E6E1EF"))
                setLineSpacing(0f, 1.1f)
                setPadding(0, dp(5), 0, dp(5))
                alpha = 0f
                translationY = dp(12).toFloat()
            }.also { card.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        lineViews.forEachIndexed { i, v ->
            v.animate().alpha(1f).translationY(0f).setStartDelay(250L + i * 220L).setDuration(340L).start()
        }
        card.addView(Button(activity).apply {
            text = "¡Entendido!"
            isAllCaps = false
            setTextColor(Color.parseColor("#1D1B22"))
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#C9A227")); cornerRadius = dp(14).toFloat() }
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) })

        dialog.setContentView(card)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            showing = false
            markSeen(activity, tip)
            onClose?.invoke()
        }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val w = (activity.resources.displayMetrics.widthPixels * 0.9f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        lastDialog = dialog
        dialog.show()
        return true
    }

    /** Enseña el primer consejo de [tips] que no se haya visto y venga a cuento. Con [chain] se siguen enseñando los
     *  demas, uno tras otro, segun se van cerrando (solo para pantallas donde todos son del mismo sitio). */
    fun showPending(
        activity: Activity, vararg tips: Tip, chain: Boolean = false, host: android.view.Window? = null,
        resolve: ((String) -> View?)? = null, onScreen: Boolean = false, textArgs: List<String>? = null
    ) {
        val next = tips.firstOrNull { !seen(activity, it) && applicable(activity, it) } ?: return
        val rest = tips.filter { it != next }.toTypedArray()
        show(activity, next, host = host, resolve = resolve, onScreen = onScreen, textArgs = textArgs) {
            if (chain && rest.isNotEmpty()) showPending(activity, *rest, chain = true, host = host, resolve = resolve, onScreen = onScreen, textArgs = textArgs)
        }
    }
}
