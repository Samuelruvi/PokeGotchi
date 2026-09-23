package com.example.pokegotchi

import android.content.Context
import org.json.JSONArray
import kotlin.random.Random

/**
 * Estado del Pokemon en SharedPreferences (sincrono, adecuado para el BroadcastReceiver
 * del widget). Stats y xp en Float para acumular decaimiento/tiempo fraccionarios.
 *
 * Nivel: sube con el TIEMPO (no con las acciones); cada accion da un pequeno bonus que
 * adelanta un poco el siguiente nivel.
 */
object PetState {

    private const val PREFS = "pokegotchi_state"
    private const val KEY_HEALTH = "health"
    private const val KEY_HYGIENE = "hygiene"
    private const val KEY_HAPPINESS = "happiness"
    private const val KEY_XP = "xp"
    private const val KEY_LAST = "last_update"
    private const val KEY_REACT_EMOTE = "react_emote"
    private const val KEY_REACT_UNTIL = "react_until"
    private const val KEY_REJECT_ACTION = "reject_action"
    private const val KEY_REJECT_UNTIL = "reject_until"
    private const val KEY_EVOLVED_AWAY = "evolved_away"
    private const val KEY_EVOLVED_TO = "evolved_to"
    // Horas reales que este INDIVIDUO ha sido el activo del widget (pedido explicito del
    // usuario: "cuanto tiempo llevas cuidando a este Pokemon", distinto por shiny/genero/forma
    // (cada uno es un individuo con su propia slot, ver slot()) pero CONTINUO a traves de toda
    // su cadena evolutiva y de Mega/Gigantamax (evolveTo migra este valor igual que xp/salud;
    // Mega/Gigantamax ni siquiera cambia de slot activo, asi que sigue sumando solo). Se
    // acumula en loadWithDecay con las MISMAS horas reales ya calculadas ahi para el resto
    // (xp/decaimiento), sin limite de 48h (igual que el xp: el tiempo real que ha pasado con
    // el como activo cuenta entero, no solo la ventana de decaimiento).
    private const val KEY_CARE_HOURS = "care_hours"

    // --- Ritmos (ajustables) ---
    // Decaimiento por hora, distinto de DIA (08-22) y de NOCHE (22-08) - la NOCHE sigue siendo
    // el tramo que mas baja las tres barras (misma proporcion noche/dia por barra desde hace
    // varias rondas), calibrado con simulacion para que el "presupuesto diario" real de acciones
    // sea el que pidio el usuario: comer 3 veces/dia, acariciar 5 veces/dia, lavar 1 vez/dia.
    // Recalibrado en esta ronda (bajado un poco mas que la vez anterior) porque el umbral de
    // necesidad paso de ser un corte FIJO en 50 a un umbral ALEATORIO por ciclo (ver
    // THRESH_MIN/MAX_* mas abajo) - promediado sobre muchos ciclos, un umbral aleatorio
    // dispara la accion MAS a menudo que el umbral fijo en su misma media (un umbral alto suelta
    // el aviso enseguida, uno bajo lo retrasa mucho, y el promedio de "cuanto tarda" no es
    // simetrico) - se reescalo el decaimiento para compensarlo, verificado con la misma tecnica
    // de simulacion hasta volver a converger en 3.00/5.00/1.00.
    // Ronda siguiente: el umbral paso de un rango GLOBAL (20-80 para las 3) a uno POR STAT (ver
    // THRESH_MIN/MAX_* mas abajo), y el boost de acariciar bajo de 55 a 25 (con 55 + umbral
    // 60-80, la felicidad saturaba a 100 en un solo toque casi siempre). Se reescalo otra vez el
    // decaimiento con la misma tecnica de simulacion para volver a converger en 3.00/5.00/1.00
    // con los 3 perfiles de jugador probados (atento, revisa cada 4h, revisa 3x/dia).
    private const val HEALTH_DAY = 5.67f;   private const val HEALTH_NIGHT = 2.27f
    // Felicidad bajada a x0.75 de la calibracion anterior (6.30/2.52): el objetivo base pedia
    // 5 caricias/dia neutras (2.5x el de comer, 5x el de lavar) y con personalidad desfavorable
    // de verdad (ej. Iron Bundle, HP56/Vel136/Def114) llegaba a pedir ~10.5/dia - una locura para
    // una app de fondo. Verificado en el Simulador de Economia (junto con bajar el tope del
    // multiplicador, ver PERSONALITY_MULT_MAX) que deja el peor caso real en ~7/dia, sin cambiar
    // apenas la media poblacional (que ya rondaba el objetivo antes de este ajuste).
    private const val HAPPY_DAY = 4.725f;   private const val HAPPY_NIGHT = 1.89f
    // CUARTA VUELTA (pedido explicito del usuario: "que el lavado vaya de 0.75 lavados que hay
    // ahora a 1.75"): subido x2.82 desde el valor original (1.43/0.57) - verificado con las 1083
    // especies reales (mismo Simulador de Economia que el resto de rondas) que esto deja la MEDIA
    // poblacional de lavados/dia en 1.75 (antes ~0.9), con el sesgo de etapa+edad de arriba ya
    // puesto. Aviso real encontrado y aceptado explicitamente por el usuario: por el coste
    // cruzado (lavar quita felicidad, ver sideEffectFor), el peor caso combinado (Onix) sube de
    // ~7.08 a ~8.11 mimos/dia - supera el tope de ~7/dia que se habia validado antes (aquella vez
    // por Iron Bundle) porque hacia falta mas mimo para compensar el lavado extra. El usuario
    // decidio aceptar este nuevo peor caso (una unica especie ya conocida como extrema) en vez de
    // suavizar el coste cruzado o quedarse cerca del ritmo anterior.
    private const val HYGIENE_DAY = 4.03f;  private const val HYGIENE_NIGHT = 1.61f


    private val tz = java.util.TimeZone.getDefault()
    private fun localHourFloat(ms: Long): Float = (((ms + tz.getOffset(ms)) % 86_400_000L).toFloat()) / 3_600_000f

    // --- Ventana de "noche" ADAPTATIVA (antes fija en 22-8) ---
    // Pedido explicito del usuario: gente distinta se acuesta/levanta a horas distintas, y un
    // corte fijo no le vale a nadie que no encaje en el 22-8 de siempre. Sin sensores ni permisos
    // nuevos (no hay forma fiable de saber "esta dormido" para una app normal - eso es lo que
    // hace Pokemon Sleep, con el movil FISICAMENTE en la cama toda la noche): en vez de eso, se
    // aprende de la UNICA señal real que ya tenemos, las interacciones de verdad (pulsar un
    // boton, tocar al Pokemon, abrir la app) - ver recordInteraction. 22-8 sigue siendo el punto
    // de partida (nunca se ha tocado nada todavia).
    private const val KEY_SLEEP_BEDTIME_H = "sleep_bedtime_h"
    private const val KEY_SLEEP_WAKE_H = "sleep_wake_h"
    private const val KEY_SLEEP_LAST_INTERACTION_AT = "sleep_last_interaction_at"
    private const val DEFAULT_BEDTIME_H = 22f
    private const val DEFAULT_WAKE_H = 8f
    // Hueco minimo entre 2 interacciones para pensar "esto fue dormir" y no solo "un rato sin
    // mirar el movil en pleno dia".
    private const val SLEEP_GAP_MIN_H = 3f
    // Cuanto se acerca la hora aprendida al dato nuevo cada vez que se observa un hueco de sueño -
    // distinto segun la direccion, a proposito (pedido explicito del usuario): "no pasa nada que
    // empiece un poco pronto la noche" (adoptar YA una hora de acostarse mas temprana) y "si por
    // la mañana el jugador enciende el movil, que ya estemos preparados" (adoptar YA una hora de
    // despertar mas temprana) - pero un dato que apunta a MAS TARDE en cualquiera de las 2 se
    // adopta mas despacio, para que un solo dia raro no desplace mucho la ventana.
    private const val LEARN_RATE_TOWARD_EARLIER = 1.0f
    private const val LEARN_RATE_TOWARD_LATER = 0.2f
    // Limites duros para que un solo dato raro (ej. dejar el movil a un lado a las 19h sin ir a
    // dormir) no pueda mandar la ventana a un extremo absurdo de un tiron - la adopcion INMEDIATA
    // hacia "mas temprano" (ver arriba) sin esto era vulnerable a un solo dia de ruido. En escala
    // "desenrollada" (ver nudgeBedtime): 20f..26f = 20:00..02:00.
    private const val BEDTIME_MIN_H = 20f
    private const val BEDTIME_MAX_H = 26f   // 02:00 del dia siguiente
    private const val WAKE_MIN_H = 5f
    private const val WAKE_MAX_H = 10f

    private fun bedtimeHour(context: Context): Float = prefs(context).getFloat(KEY_SLEEP_BEDTIME_H, DEFAULT_BEDTIME_H)
    private fun wakeHour(context: Context): Float = prefs(context).getFloat(KEY_SLEEP_WAKE_H, DEFAULT_WAKE_H)

    private fun isNightAt(hourFloat: Float, bedH: Float, wakeH: Float): Boolean =
        if (bedH > wakeH) hourFloat >= bedH || hourFloat < wakeH else hourFloat >= bedH && hourFloat < wakeH

    /** Publico para que el widget pueda callar los avisos NUEVOS de la ventana de noche actual
     *  (el Pokemon "duerme"): si ya hacia falta algo desde antes de dormir, el aviso salta igual
     *  en cuanto amanece - esto solo evita que aparezca uno NUEVO en mitad de la noche. */
    fun isNightNow(context: Context): Boolean =
        isNightAt(localHourFloat(System.currentTimeMillis()), bedtimeHour(context), wakeHour(context))

    /** Para enseñar en Ajustes la ventana de noche YA calibrada (empieza en "22:00 - 08:00" y se
     *  va moviendo con el uso real, ver recordInteraction) - pedido explicito del usuario para
     *  poder ver si el aprendizaje esta funcionando bien. */
    fun sleepWindowLabel(context: Context): String {
        fun fmt(h: Float): String {
            val hh = h.toInt().coerceIn(0, 23)
            val mm = ((h - hh) * 60).toInt().coerceIn(0, 59)
            return "%02d:%02d".format(hh, mm)
        }
        return "${fmt(bedtimeHour(context))} – ${fmt(wakeHour(context))}"
    }

    /** Marca "el usuario acaba de interactuar de verdad" (pulsar un boton, tocar al Pokemon,
     *  abrir la app) - llamar desde CUALQUIER interaccion real, la unica señal de que esta
     *  despierto que tenemos. Si el hueco con la interaccion anterior es largo Y tiene pinta de
     *  sueño nocturno (la anterior de tarde/noche, esta de madrugada/mañana), recalibra la hora
     *  de acostarse (con la hora de la interaccion ANTERIOR) y de despertar (con la hora de
     *  ESTA), cada una con su propia velocidad de aprendizaje (ver LEARN_RATE_*). */
    fun recordInteraction(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val prev = p.getLong(KEY_SLEEP_LAST_INTERACTION_AT, 0L)
        if (prev > 0L) {
            val gapH = (now - prev) / 3_600_000f
            val observedBed = localHourFloat(prev)
            val observedWake = localHourFloat(now)
            if (gapH >= SLEEP_GAP_MIN_H && looksLikeSleepGap(observedBed, observedWake)) {
                val newBed = nudgeBedtime(bedtimeHour(context), observedBed)
                val newWake = nudgeHour(wakeHour(context), observedWake).coerceIn(WAKE_MIN_H, WAKE_MAX_H)
                p.edit().putFloat(KEY_SLEEP_BEDTIME_H, newBed).putFloat(KEY_SLEEP_WAKE_H, newWake).apply()
            }
        }
        p.edit().putLong(KEY_SLEEP_LAST_INTERACTION_AT, now).apply()
    }

    /** Evita confundir un hueco de sueño con, por ejemplo, un finde sin tocar el movil en pleno
     *  dia: la interaccion anterior tiene que caer de tarde/noche y esta de madrugada/mañana. */
    private fun looksLikeSleepGap(observedBedHour: Float, observedWakeHour: Float): Boolean =
        (observedBedHour >= 18f || observedBedHour < 4f) && observedWakeHour < 12f

    private fun nudgeHour(learned: Float, observed: Float): Float {
        val rate = if (observed <= learned) LEARN_RATE_TOWARD_EARLIER else LEARN_RATE_TOWARD_LATER
        return learned + (observed - learned) * rate
    }

    /** Igual que [nudgeHour] pero desenrollando la medianoche primero (acostarse a las 00:30 es
     *  MAS TARDE que a las 23:00, aunque 0.5 sea numericamente menor que 23 - sin esto, un
     *  trasnoche real se habria confundido con "se acosto mas temprano"). */
    private fun nudgeBedtime(learned: Float, observed: Float): Float {
        fun scale(h: Float) = if (h < 12f) h + 24f else h
        val result = nudgeHour(scale(learned), scale(observed)).coerceIn(BEDTIME_MIN_H, BEDTIME_MAX_H)
        return if (result >= 24f) result - 24f else result
    }

    /** Decaimiento integrado en tramos de 30 min usando la tasa dia/noche de cada momento. */
    private fun decayOver(fromMs: Long, toMs: Long, dayRate: Float, nightRate: Float, bedH: Float, wakeH: Float): Float {
        var t = fromMs; var total = 0f; var guard = 0
        val step = 30L * 60L * 1000L
        while (t < toMs && guard < 200) {
            val next = minOf(t + step, toMs)
            total += (if (isNightAt(localHourFloat(t), bedH, wakeH)) nightRate else dayRate) * ((next - t) / 3_600_000f)
            t = next; guard++
        }
        return total
    }

    // XP: el tiempo da TIME_XP_PER_H por hora y cada accion ACTION_XP, IGUAL para todos.
    private const val TIME_XP_PER_H = 40f   // x2: nivel 100 en ~1.7 meses
    private const val ACTION_XP = 5f
    private const val MAX_LEVEL = 100

    // Curva propia MAS LINEAL: el coste por nivel sube poco a poco (LVL_BASE al inicio,
    // +LVL_STEP por nivel). Asi los primeros niveles son minutos y los ultimos un par de dias,
    // sin la explosion de la curva cubica real.
    private const val LVL_BASE = 10f   // xp del nivel 1->2 (~30 min a 20 xp/h)
    private const val LVL_STEP = 10f   // cuanto sube el coste de cada nivel siguiente

    // Grupo de crecimiento REAL por especie (PokeAPI, pokemon-species.growth_rate.name) - se
    // mantiene igual en toda una cadena evolutiva (comprobado con datos reales: bulbasaur/
    // ivysaur/venusaur los 3 "medium-slow"). [name] null (o sin datos) cae en "medium", el ritmo
    // de referencia de siempre - asi ningun sitio que se le olvide pasar el nombre se rompe, solo
    // deja de tener en cuenta la especie.
    @Volatile private var growthTable: Map<String, String>? = null

    private fun loadGrowthTable(context: Context): Map<String, String> {
        growthTable?.let { return it }
        val json = context.assets.open("growth_rates.json").bufferedReader().use { it.readText() }
        val obj = org.json.JSONObject(json)
        val map = HashMap<String, String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) { val name = keys.next(); map[name] = obj.getString(name) }
        growthTable = map
        return map
    }

    fun growthRateOf(context: Context, name: String?): String =
        if (name == null) "medium" else loadGrowthTable(context)[name.lowercase()] ?: "medium"

    // Diferencia de dificultad por especie: multiplicador segun el grupo real (ratio del total
    // a nivel 100 respecto a "medium"). Mantiene que Tyranitar (slow) cueste mas, etc. Probado
    // con las 1025 especies reales + el simulador de entrenador antes de conectarlo aqui: el
    // efecto en cuanto se tarda en llegar a nivel 100 de ENTRENADOR es minimo (el peso "nivel"
    // del pozo ya es pequeño a proposito) - esto solo afecta de verdad al nivel de cada Pokemon.
    private fun groupFactor(group: String): Float = when (group) {
        "fast" -> 0.8f
        "medium" -> 1.0f
        "medium-slow" -> 1.06f
        "slow" -> 1.25f
        "slow-then-very-fast" -> 0.6f   // erratic
        "fast-then-very-slow" -> 1.64f  // fluctuating
        else -> 1.0f
    }

    // XP acumulada para ALCANZAR 'level' = coste lineal por nivel * factor de la especie.
    private fun cumXp(level: Int, context: Context, name: String?): Float {
        if (level <= 1) return 0f
        val n = (level - 1).toFloat()
        val base = LVL_BASE * n + LVL_STEP * n * (n - 1) / 2f
        return base * groupFactor(growthRateOf(context, name))
    }

    /** [name] identifica la especie para aplicar su grupo de crecimiento real - omitirlo (o pasar
     *  null) cae en el ritmo "medium" de siempre, sin romper nada. */
    fun levelOf(context: Context, xp: Float, name: String? = null): Int {
        var l = 1
        while (l < MAX_LEVEL && xp >= cumXp(l + 1, context, name)) l++
        return l
    }

    fun progressOf(context: Context, xp: Float, name: String? = null): Float {
        val l = levelOf(context, xp, name)
        if (l >= MAX_LEVEL) return 1f
        val a = cumXp(l, context, name); val b = cumXp(l + 1, context, name)
        return if (b > a) ((xp - a) / (b - a)).coerceIn(0f, 1f) else 0f
    }

    /** Envoltorio publico de cumXp para el menu debug: xp exacta para EMPEZAR en [level] de
     *  [name] (su propio grupo de crecimiento, ver growthRateOf/cumXp). */
    fun xpForLevel(context: Context, level: Int, name: String? = null): Float =
        cumXp(level.coerceIn(1, MAX_LEVEL), context, name)

    // Umbral de "necesidad": por debajo de esto el Pokemon SI necesita esa accion (se indica
    // en el widget con un aviso en el boton Y una nube sobre su cabeza). Es el Pokemon quien
    // decide cuando hace falta, no el jugador: pulsar una accion que no hace falta se rechaza
    // (no sube nada), asi que no se puede spamear el boton para mantener la barra al maximo.
    //
    // YA NO es un corte fijo en 50: el usuario penso que un numero identico siempre quedaba
    // "cutre" - queria que fuese el Pokemon quien decida cuando le apetece cada cosa, con algo
    // de aleatoriedad (a veces "sin motivo" le entra hambre con la barra casi llena, a veces no
    // le apetece aunque este bastante vacia). Cada "ciclo de necesidad" (desde que se satisface
    // una accion hasta la siguiente) sortea su PROPIO umbral, uniforme entre estos 2 extremos -
    // asi el punto exacto en el que aparece el aviso varia de una vez a otra, en vez de ser
    // siempre el mismo corte. La media (50) se eligio IGUAL al umbral fijo de antes para no
    // desviar el "presupuesto diario" ya calibrado (ronda 44: comer 3x/dia, acariciar 5x/dia,
    // lavar 1x/dia) - verificado con una simulacion antes de fijar el rango (ver notas de esa
    // ronda en la memoria del proyecto).
    // Cada stat tiene su PROPIO rango de umbral (antes uno solo, 20-80, para las 3): asi se acota
    // quien se queja mas a menudo (higiene, rango bajo y estrecho) de quien se queja poco pero
    // fuerte (felicidad, rango alto).
    private const val THRESH_MIN_HEALTH = 35f;  private const val THRESH_MAX_HEALTH = 65f
    private const val THRESH_MIN_HAPPY = 60f;   private const val THRESH_MAX_HAPPY = 80f
    private const val THRESH_MIN_HYGIENE = 20f; private const val THRESH_MAX_HYGIENE = 40f
    private const val KEY_THRESH_HEALTH = "thresh_health"
    private const val KEY_THRESH_HYGIENE = "thresh_hygiene"
    private const val KEY_THRESH_HAPPY = "thresh_happy"
    private fun rangeForThreshKey(key: String): Pair<Float, Float> = when (key) {
        KEY_THRESH_HAPPY -> THRESH_MIN_HAPPY to THRESH_MAX_HAPPY
        KEY_THRESH_HYGIENE -> THRESH_MIN_HYGIENE to THRESH_MAX_HYGIENE
        else -> THRESH_MIN_HEALTH to THRESH_MAX_HEALTH
    }
    private fun rollThreshold(key: String): Float {
        val (min, max) = rangeForThreshKey(key)
        return min + Random.nextFloat() * (max - min)
    }

    /** Umbral ACTUAL (por especie) de esta stat: si aun no se ha sorteado ninguno (Pokemon nuevo,
     *  o guardado de antes de este cambio), se sortea una vez y se guarda (self-healing). Se
     *  vuelve a sortear en tryApplyAction al satisfacer la accion -
     *  cada ciclo de necesidad tiene su propio umbral, no siempre el mismo. */
    private fun ensureThreshold(context: Context, n: String, key: String): Float {
        val p = prefs(context)
        val v = p.getFloat(k(n, key), -1f)
        if (v > 0f) return v
        val t = rollThreshold(key)
        p.edit().putFloat(k(n, key), t).apply()
        return t
    }

    private fun rerollThreshold(context: Context, n: String, key: String) {
        prefs(context).edit().putFloat(k(n, key), rollThreshold(key)).apply()
    }

    private fun thresholdKeyFor(action: String): String? = when (action) {
        PokeWidgetProvider.ACTION_FEED -> KEY_THRESH_HEALTH
        PokeWidgetProvider.ACTION_PET -> KEY_THRESH_HAPPY
        PokeWidgetProvider.ACTION_WASH -> KEY_THRESH_HYGIENE
        else -> null
    }

    // Cada accion sube su propia stat un importe FIJO (no la deja al 100): si reaccionas justo
    // cuando cruza el umbral, sube cerca del maximo; si tardas y ha bajado mas, se queda mas
    // corta. Al quedar por encima del umbral tras aplicarse, el aviso desaparece solo y no dejas
    // pulsar de nuevo hasta que vuelva a bajar - sin necesitar un cooldown aparte.
    private const val BOOST_HEALTH = 55f
    // Mas bajo que las otras 2: con boost 55 y umbral 60-80, la felicidad saturaba a 100 en un
    // solo toque casi siempre (siempre se acababa "en el maximo" tras acariciar).
    private const val BOOST_HAPPY = 25f
    private const val BOOST_HYGIENE = 55f

    // Cada accion tambien baja UN POCO otra stat distinta (coste cruzado, en bucle):
    // comer ensucia un poco (higiene), bañarle no le hace gracia (felicidad), y jugar/emocionarse
    // da hambre (vida). Asi ninguna accion es "gratis": arreglar una cosa desgasta otra un poco.
    private const val SIDE_EFFECT = 10f

    // Suelo minimo de decaimiento PASIVO (sin acciones): si compruebas el Pokemon dentro de
    // FLOOR_GRACE_H horas desde la ultima vez, nunca lo veras bajar de FLOOR. Si lo abandonas
    // mas tiempo que eso, el suelo deja de proteger y SI puede llegar a 0 - posible pero muy raro
    // (hace falta olvidarlo de verdad durante un buen rato, no un despiste de un rato).
    private const val FLOOR = 12f
    private const val FLOOR_GRACE_H = 20f

    data class Stats(
        var health: Float,
        var hygiene: Float,
        var happiness: Float,
        var xp: Float
    )

    private const val KEY_POKEMON = "pokemon"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pokemon actualmente en el widget (nombre en ingles minusculas). */
    fun currentPokemon(context: Context): String =
        prefs(context).getString(KEY_POKEMON, "pikachu") ?: "pikachu"

    // --- Pokedex: que especies estan desbloqueadas (Fase 1 del sistema de consecucion) ---
    // Arranca VACIA (Pokedex "vacia"): solo se desbloquea al elegirlo como inicial, ganarlo en
    // una oferta aleatoria, o (mas adelante) al evolucionar hacia el. Es un conjunto que solo
    // crece; nunca se bloquea de nuevo una especie ya desbloqueada.
    private const val KEY_UNLOCKED = "unlocked_species"
    private const val KEY_STARTER_DONE = "starter_done"

    fun hasChosenStarter(context: Context): Boolean = prefs(context).getBoolean(KEY_STARTER_DONE, false)
    fun markStarterChosen(context: Context) {
        // Tambien fija el punto de partida para las ofertas periodicas (Fase 3): asi la
        // primera oferta tarda el intervalo completo desde que se elige el inicial, en vez de
        // aparecer de inmediato en el primer arranque.
        prefs(context).edit()
            .putBoolean(KEY_STARTER_DONE, true)
            .putLong(KEY_OFFER_LAST, System.currentTimeMillis())
            .commit()
    }

    private const val KEY_UNLOCKED_AT = "unlocked_at"

    /** Añade [name] al conjunto de especies desbloqueadas (no hace nada si ya lo estaba) y apunta
     *  la fecha - para el orden "Fecha de obtención" de la Pokedex (pedido explicito del usuario,
     *  "como en Pokemon GO"). Especies desbloqueadas ANTES de que existiera esta fecha se quedan
     *  sin ella (unlockedAt devuelve null) - no se puede reconstruir un dato que nunca se guardo. */
    fun unlock(context: Context, name: String) {
        val n = name.lowercase()
        val p = prefs(context)
        val current = p.getStringSet(KEY_UNLOCKED, emptySet()) ?: emptySet()
        if (n in current) return
        val updated = HashSet(current).apply { add(n) }
        p.edit().putStringSet(KEY_UNLOCKED, updated).putLong(k(n, KEY_UNLOCKED_AT), System.currentTimeMillis()).commit()
    }

    /** Cuando se desbloqueo [name] por primera vez (epoch ms), o null si no se sabe (desbloqueado
     *  antes de que existiera este registro). */
    fun unlockedAt(context: Context, name: String): Long? {
        val v = prefs(context).getLong(k(name.lowercase(), KEY_UNLOCKED_AT), -1L)
        return if (v < 0) null else v
    }

    /** ¿Esta especie esta disponible para seleccionar en la Pokedex? Ademas del conjunto
     *  explicito, cualquier especie que YA se haya cuidado alguna vez (tiene xp guardada, de
     *  antes de este sistema) cuenta como desbloqueada - no se "bloquea" progreso existente. */
    fun isUnlocked(context: Context, name: String): Boolean {
        val n = name.lowercase()
        val p = prefs(context)
        if (p.getStringSet(KEY_UNLOCKED, emptySet())?.contains(n) == true) return true
        return p.contains(k(n, KEY_XP))
    }

    /** ¿Esta especie YA evoluciono (esta en "limbo": registrada en la Pokedex - se tuvo - pero
     *  ya no se puede seleccionar porque el individuo que era ya es otra especie)? [shiny] elige
     *  el individuo normal o el shiny de esta especie (ver slot()) - por defecto el normal, igual
     *  que siempre antes de que existiera esta distincion. */
    fun hasEvolvedAway(context: Context, name: String, shiny: Boolean = false): Boolean =
        prefs(context).getBoolean(k(slot(name.lowercase(), shiny), KEY_EVOLVED_AWAY), false)

    /** ¿A que especie evoluciono (si `hasEvolvedAway` es true)? null si no aplica. */
    fun evolvedToName(context: Context, name: String, shiny: Boolean = false): String? =
        prefs(context).getString(k(slot(name.lowercase(), shiny), KEY_EVOLVED_TO), null)

    // El estado (stats/xp/reloj) es POR POKEMON: claves con prefijo "<name>.".
    private fun k(name: String, base: String) = "$name.$base"

    // ==================== INDIVIDUOS NORMAL/SHINY COEXISTIENDO ====================
    // Cada especie puede tener a la vez UN individuo normal Y uno shiny, con progreso
    // (xp/salud/higiene/felicidad/nivel/evolucion/genero) independiente - pedido explicito del
    // usuario tras el bug real de esta noche (un huevo de una especie ya tenida pisaba en
    // silencio el individuo anterior). slot(name, false) == name a proposito: las claves de
    // siempre (un solo individuo, sin esta distincion) siguen siendo el individuo "normal" sin
    // ninguna migracion - todo lo ya guardado sigue funcionando tal cual. Mecanicas de forma
    // (Mega, Zygarde, Palafin, decorativas...) se quedan COMPARTIDAS entre ambas variantes de una
    // misma especie - simplificacion consciente, ver el plan de esta funcion.
    private const val SHINY_SLOT_SUFFIX = "#shiny"
    private fun slot(name: String, shiny: Boolean): String = if (shiny) "$name$SHINY_SLOT_SUFFIX" else name

    /** ¿Existe ya un individuo (normal o shiny, segun [shiny]) de esta especie? (tiene xp
     *  guardada en esa slot en concreto) - para que la ficha sepa si ofrecer el selector. */
    fun hasIndividual(context: Context, name: String, shiny: Boolean): Boolean =
        prefs(context).contains(k(slot(name.lowercase(), shiny), KEY_XP))

    /** Que individuo (normal o shiny) representa a [name] cuando hace falta UNO solo (ordenar,
     *  vitrina de mecanicas...) y no hay una ficha concreta abierta de la que heredar la
     *  preferencia: el que exista si solo hay uno, o el ultimo visto (ver isShiny/setShiny) si
     *  existen los dos - misma resolucion que ya usaba DexAdapter.onBindViewHolder, para no
     *  repetirla suelta en cada sitio nuevo que la necesite. */
    fun preferredShinyFor(context: Context, name: String): Boolean {
        val hasShinyIndiv = hasIndividual(context, name, shiny = true)
        val hasNormalIndiv = hasIndividual(context, name, shiny = false)
        return when {
            hasNormalIndiv && hasShinyIndiv -> isShiny(context, name)
            hasShinyIndiv -> true
            else -> false
        }
    }

    private const val KEY_ACTIVE_SHINY = "active_shiny"

    /** ¿La variante ACTIVA ahora mismo (currentPokemon) es la shiny? */
    fun isActiveShiny(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE_SHINY, false)

    /** Cambia el Pokemon del widget. Inicializa su estado si es nuevo y reinicia su reloj
     *  (para que no reciba decaimiento del tiempo que estuvo inactivo). */
    // Fondo del widget (nombre del drawable, ej. "bg_meadow").
    private const val KEY_BG = "bg"
    fun currentBg(context: Context): String =
        prefs(context).getString(KEY_BG, "bg_meadow") ?: "bg_meadow"
    fun setBg(context: Context, drawableName: String) =
        prefs(context).edit().putString(KEY_BG, drawableName).apply()

    // Estilo del sprite ("gen5" | "pmd"). id nacional POR Pokemon.
    private const val KEY_STYLE = "sprite_style"
    private const val KEY_SHINY = "shiny"
    private const val KEY_ID = "dexid"
    // PMD (Mundo Misterioso) pausado: el widget usa siempre Gen 5. El resto de la maquinaria
    // de estilos queda latente por si se retoma en el futuro.
    fun currentStyle(context: Context): String = "gen5"
    fun setStyle(context: Context, style: String) =
        prefs(context).edit().putString(KEY_STYLE, style).apply()
    // Shiny POR ESPECIE (bug corregido: antes era un unico flag GLOBAL compartido por todo el
    // juego - si conseguias un shiny y luego cambiabas a otro Pokemon, ese otro tambien salia
    // shiny sin serlo, porque nada reseteaba el flag al cambiar de activo). [name] por defecto es
    // el Pokemon ACTIVO; se puede pasar explicito para consultar/fijar el de OTRA especie (ej. al
    // quedarse con la cria de un huevo, antes de que pase a ser la activa).
    fun isShiny(context: Context, name: String = currentPokemon(context)): Boolean =
        prefs(context).getBoolean(k(name.lowercase(), KEY_SHINY), false)
    fun setShiny(context: Context, shiny: Boolean, name: String = currentPokemon(context)) =
        prefs(context).edit().putBoolean(k(name.lowercase(), KEY_SHINY), shiny).apply()

    // Favorito: marca libre del jugador (pedido explicito del usuario: pestaña "Favoritos"
    // aparte, entre "Mis Pokemon" y "Pokedex completa"). Ligado a la CADENA EVOLUTIVA entera
    // (pedido explicito del usuario: no es "marca este Pokemon", es "marca esta linea") - se
    // guarda bajo la forma RAIZ de la cadena (ver baseFormOf, subiendo por evolves_from), asi
    // que marcar a Grovyle favorito lo deja favorito para Treecko (ya criado o no) Y para
    // Sceptile (aunque todavia no exista ese individuo) sin tener que copiar nada al
    // evolucionar - la lectura ya resuelve siempre a la misma clave para toda la linea. Si es
    // shiny o no SI se mantiene independiente (un Treecko shiny favorito no marca al normal).
    fun favoriteChainKey(context: Context, name: String): String = baseFormOf(context, name.lowercase())
    private const val KEY_FAVORITE = "favorite"
    fun isFavorite(context: Context, name: String, shiny: Boolean = false): Boolean =
        prefs(context).getBoolean(k(slot(favoriteChainKey(context, name), shiny), KEY_FAVORITE), false)
    fun setFavorite(context: Context, name: String, shiny: Boolean = false, value: Boolean) =
        prefs(context).edit().putBoolean(k(slot(favoriteChainKey(context, name), shiny), KEY_FAVORITE), value).apply()

    private const val KEY_SPRITE_SCALE_MODE = "sprite_scale_mode"

    /** "none" (vecino cercano de siempre), "partial" (mezcla al 50%) o "full" (una pasada de
     *  Scale2x + relleno) - ver SpriteRepository.scaledFrame. Prueba visual pedida por el
     *  usuario antes de decidir cual dejar puesta de forma permanente. */
    fun spriteScaleMode(context: Context): String = prefs(context).getString(KEY_SPRITE_SCALE_MODE, "none") ?: "none"
    fun setSpriteScaleMode(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_SPRITE_SCALE_MODE, mode).apply()

    fun currentStyleKey(context: Context): String =
        SpriteRepository.styleKey(currentStyle(context), isShiny(context)) + "_" + spriteScaleMode(context)
    fun currentPokemonId(context: Context): Int {
        val species = currentPokemon(context)
        val p = prefs(context)
        // Slot del individuo activo primero (normal o shiny, ver currentSlot); si por lo que sea
        // no tiene dexid propio guardado, cae al de la especie a secas antes que al default fijo.
        val fallback = if (species == "pikachu") 25 else 0
        return p.getInt(k(currentSlot(context), KEY_ID), p.getInt(k(species, KEY_ID), fallback))
    }

    /** [shiny] elige que INDIVIDUO de [name] se activa - el normal o el shiny (ver slot()),
     *  independientes entre si (progreso/evolucion propios). Por defecto, el que ya estuviera
     *  marcado para esta especie (mismo comportamiento de siempre si no se pasa explicito). */
    fun setPokemon(context: Context, name: String, id: Int = -1, shiny: Boolean = isShiny(context, name)) {
        migrate(context)
        val base = name.lowercase()
        val n = slot(base, shiny)
        unlock(context, base)
        val p = prefs(context)
        // Palafin: en el juego real pasa a Forma Heroe la primera vez que se le retira sin
        // desmayarse. Aqui, el equivalente es "volver a elegirlo" (ya existia, no es un individuo
        // nuevo) viniendo de cuidar OTRO Pokemon distinto - se lo habias "retirado" una vez.
        // Compartido entre variantes (ver slot()): la Forma Heroe no distingue normal/shiny.
        val wasActive = currentPokemon(context)
        if (base == "palafin" && p.contains(k(n, KEY_XP)) && !hasEvolvedAway(context, base, shiny) &&
            wasActive != "palafin" && !palafinIsHero(context)
        ) {
            p.edit().putBoolean(KEY_PALAFIN_HERO, true).commit()
        }
        // Si esta especie YA habia evolucionado antes (ej. un Pikachu de un huevo ANTERIOR que ya
        // se convirtio en Raichu), esto es un individuo NUEVO de la misma especie - no "el mismo
        // Pikachu que vuelve" (nunca se borraron sus stats viejos de antes de evolucionar, ver
        // evolveTo, asi que sin este chequeo se heredarian). Sin resetear evolved_away aqui, un
        // segundo Pikachu asi NUNCA podria volver a evolucionar (pendingEvolutionFor lo bloquea
        // por especie, no por individuo) - bug real que impedia rehacer una cadena con ramas
        // (Slowpoke, Eevee, Pikachu/Raichu de Alola...) para elegir una rama distinta la segunda
        // vez, pedido explicito del usuario.
        val isFreshIndividual = !p.contains(k(n, KEY_XP)) || hasEvolvedAway(context, base, shiny)
        val e = p.edit().putString(KEY_POKEMON, base).putBoolean(KEY_ACTIVE_SHINY, shiny)
            .putBoolean(k(base, KEY_SHINY), shiny)  // flag legacy en sincronia (sprite/tinte de siempre)
        if (id > 0) e.putInt(k(n, KEY_ID), id)
        if (isFreshIndividual) {
            // Pokemon nuevo: NO arranca con las 3 barras al 100% - eso es un artefacto irreal
            // (nunca vuelve a repetirse el resto de la partida) que ademas sesga el propio dia 1
            // (ej. higiene, la mas lenta, casi nunca le da tiempo a pedir nada ese primer dia).
            // Cada barra arranca en un punto aleatorio entre 50 y 100, cada una por su cuenta
            // (mismo criterio ya usado en el simulador de economia).
            e.putFloat(k(n, KEY_HEALTH), 50f + Random.nextFloat() * 50f)
                .putFloat(k(n, KEY_HYGIENE), 50f + Random.nextFloat() * 50f)
                .putFloat(k(n, KEY_HAPPINESS), 50f + Random.nextFloat() * 50f)
                .putFloat(k(n, KEY_XP), 0f)
                .remove(k(n, KEY_EVOLVED_AWAY))
                .remove(k(n, KEY_EVOLVED_TO))
            // Un Zygarde NUEVO (individuo distinto) siempre arranca en su forma base 10%, nunca
            // hereda la forma a la que habia llegado uno anterior. Compartido entre variantes.
            if (base == "zygarde") e.remove(KEY_ZYGARDE_FORME)
            // Palafin nuevo: siempre arranca en Forma Cero, nunca hereda la Heroe de uno anterior.
            if (base == "palafin") e.putBoolean(KEY_PALAFIN_HERO, false)
            // Genero (ver GENERO): individuo NUEVO -> se sortea uno fresco (con la probabilidad
            // real de esa especie), nunca se hereda el de un individuo anterior de la misma especie -
            // y AHORA independiente por variante (un Snivy shiny y uno normal pueden tener sexos
            // distintos, son individuos distintos de verdad).
            if (base in GENDER_SPECIES) e.putString(k(n, KEY_GENDER), rollGender(base))
        }
        e.putLong(k(n, KEY_LAST), System.currentTimeMillis())  // reinicia el reloj
        e.apply()
    }

    /** Nivel guardado de un Pokemon ya cuidado, o null si nunca se cuido. [shiny] elige el
     *  individuo normal o el shiny de esta especie (ver slot()). OJO: a levelOf() se le pasa
     *  siempre el nombre BASE (nunca la slot con sufijo) - el ritmo de crecimiento se consulta
     *  por especie real, no existe una tabla aparte para el individuo shiny. */
    fun levelForPokemon(context: Context, name: String, shiny: Boolean = false): Int? {
        migrate(context)
        val base = name.lowercase()
        val n = slot(base, shiny)
        val p = prefs(context)
        if (!p.contains(k(n, KEY_XP))) return null
        return levelOf(context, p.getFloat(k(n, KEY_XP), 0f), base)
    }

    /** Stats CONGELADAS de una especie (tal cual quedaron guardadas la ultima vez), SIN aplicar
     *  decaimiento - a diferencia de `loadWithDecay` (que es solo para el Pokemon ACTIVO). Para
     *  el detalle de un Pokemon que no es el activo ahora mismo (o que ya evoluciono): sus
     *  stats estan "congeladas" desde que se dejo de cuidar. null si nunca se tuvo. */
    /** Horas reales que este individuo ([name]/[shiny]) lleva siendo el activo del widget - 0 si
     *  nunca lo ha sido. Continua a traves de su cadena evolutiva (ver evolveTo) y de Mega/
     *  Gigantamax (no cambia de individuo activo, sigue sumando el mismo). Se congela (deja de
     *  subir) en cuanto deja de ser el activo, igual que el resto de sus stats (ver rawStats). */
    fun careHours(context: Context, name: String, shiny: Boolean = false): Float =
        prefs(context).getFloat(k(slot(name.lowercase(), shiny), KEY_CARE_HOURS), 0f)

    fun rawStats(context: Context, name: String, shiny: Boolean = false): Stats? {
        val n = slot(name.lowercase(), shiny)
        val p = prefs(context)
        if (!p.contains(k(n, KEY_XP))) return null
        val xp = try { p.getFloat(k(n, KEY_XP), 0f) } catch (e: ClassCastException) { p.getInt(k(n, KEY_XP), 0).toFloat() }
        return Stats(
            health = p.getFloat(k(n, KEY_HEALTH), 100f),
            hygiene = p.getFloat(k(n, KEY_HYGIENE), 100f),
            happiness = p.getFloat(k(n, KEY_HAPPINESS), 100f),
            xp = xp
        )
    }

    /** Migra el estado antiguo (compartido, sin prefijo) al de pikachu, una sola vez. */
    private fun migrate(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("migrated_v2", false)) return
        val e = p.edit().putBoolean("migrated_v2", true)
        if (p.contains(KEY_XP) && !p.contains(k("pikachu", KEY_XP))) {
            val oldXp = try { p.getFloat(KEY_XP, 0f) } catch (ex: Exception) { p.getInt(KEY_XP, 0).toFloat() }
            e.putFloat(k("pikachu", KEY_HEALTH), p.getFloat(KEY_HEALTH, 100f))
                .putFloat(k("pikachu", KEY_HYGIENE), p.getFloat(KEY_HYGIENE, 100f))
                .putFloat(k("pikachu", KEY_HAPPINESS), p.getFloat(KEY_HAPPINESS, 100f))
                .putFloat(k("pikachu", KEY_XP), oldXp)
                .putLong(k("pikachu", KEY_LAST), p.getLong(KEY_LAST, System.currentTimeMillis()))
        }
        e.apply()
    }

    /** Carga aplicando decaimiento de stats Y sumando xp por el tiempo transcurrido. */
    /** Slot de DATOS DE VERDAD (progreso/stats) del individuo ACTIVO ahora mismo: normal o shiny
     *  (ver slot()), segun isActiveShiny(). BUG REAL encontrado y corregido (reportado por el
     *  usuario: un Snivy shiny recien salido de un huevo aparecia con el nivel/xp de OTRO Snivy
     *  normal, ya evolucionado hace tiempo a Serperior) - loadWithDecay/save llevaban TODA la
     *  sesion leyendo/escribiendo el progreso SIEMPRE bajo el nombre base de la especie (via
     *  currentPokemon(context) a secas), ignorando isActiveShiny() por completo, mientras que
     *  setPokemon SI inicializaba el individuo nuevo en su slot correcto (name#shiny) - esos datos
     *  frescos (xp=0, barras 50-100 al azar) quedaban escritos pero JAMAS leidos por el resto del
     *  juego, que seguia usando en su lugar el slot normal (con el progreso VIEJO y ya congelado
     *  del individuo anterior que evoluciono). Para un individuo normal (shiny=false), slot()
     *  devuelve el nombre base tal cual - CERO cambio de comportamiento para todo el progreso ya
     *  guardado de siempre; el fix solo afecta al caso, hasta ahora nunca ejercitado en la
     *  practica, de jugar activamente con el individuo SHINY. */
    private fun currentSlot(context: Context): String = slot(currentPokemon(context), isActiveShiny(context))

    fun loadWithDecay(context: Context): Stats {
        migrate(context)
        val p = prefs(context)
        val species = currentPokemon(context)   // para tablas por ESPECIE (personalidad, tipo, curva de nivel...)
        val n = currentSlot(context)            // para el progreso de VERDAD del individuo activo
        val now = System.currentTimeMillis()
        val last0 = p.getLong(k(n, KEY_LAST), now)
        val hours = (now - last0).coerceAtLeast(0L) / 3_600_000f          // para xp (tiempo real)
        val last = last0.coerceAtLeast(now - 48L * 3_600_000L)            // integrar decaimiento max 48h

        // Dentro de la "gracia" (compruebas el Pokemon con cierta frecuencia) el decaimiento
        // pasivo no baja del suelo. Solo si lo abandonas mas tiempo que eso puede llegar a 0.
        val floorNow = if (hours < FLOOR_GRACE_H) FLOOR else 0f
        val (mHealth, mHappy, mHygiene) = personalityMultipliers(context, species)
        val bedH = bedtimeHour(context); val wakeH = wakeHour(context)
        val health = (p.getFloat(k(n, KEY_HEALTH), 100f) - decayOver(last, now, HEALTH_DAY*mHealth, HEALTH_NIGHT*mHealth, bedH, wakeH)).coerceIn(floorNow, 100f)
        val hygiene = (p.getFloat(k(n, KEY_HYGIENE), 100f) - decayOver(last, now, HYGIENE_DAY*mHygiene, HYGIENE_NIGHT*mHygiene, bedH, wakeH)).coerceIn(floorNow, 100f)
        val happiness = (p.getFloat(k(n, KEY_HAPPINESS), 100f) - decayOver(last, now, HAPPY_DAY*mHappy, HAPPY_NIGHT*mHappy, bedH, wakeH)).coerceIn(floorNow, 100f)
        val xpStored = try {
            p.getFloat(k(n, KEY_XP), 0f)
        } catch (e: ClassCastException) {
            p.getInt(k(n, KEY_XP), 0).toFloat()
        }
        val xp = xpStored + TIME_XP_PER_H * hours * xpMultiplier(context, species)   // el nivel sube con el tiempo

        val stats = Stats(health, hygiene, happiness, xp)
        save(context, stats, now)
        p.edit().putFloat(k(n, KEY_CARE_HOURS), p.getFloat(k(n, KEY_CARE_HOURS), 0f) + hours).apply()
        val lvl = levelOf(context, xp, species)
        maybeAdvanceZygardeForme(context, species, lvl)
        maybeAdvanceTerapagos(context, species, lvl)
        if (species == "keldeo" && !keldeoIsResolute(context) && lvl >= KELDEO_RESOLUTE_LEVEL) {
            p.edit().putBoolean(KEY_KELDEO_RESOLUTE, true).commit()
        }
        return stats
    }

    /** ¿La stat de esta accion esta por debajo del umbral (el Pokemon la necesita AHORA)? */
    private fun statFor(s: Stats, action: String): Float? = when (action) {
        PokeWidgetProvider.ACTION_FEED -> s.health
        PokeWidgetProvider.ACTION_PET -> s.happiness
        PokeWidgetProvider.ACTION_WASH -> s.hygiene
        else -> null
    }

    fun isNeeded(context: Context, s: Stats, action: String): Boolean {
        val stat = statFor(s, action) ?: return false
        val key = thresholdKeyFor(action) ?: return false
        return stat < ensureThreshold(context, currentPokemon(context), key)
    }

    fun hasAnyNeed(context: Context, s: Stats): Boolean {
        val n = currentPokemon(context)
        return s.health < ensureThreshold(context, n, KEY_THRESH_HEALTH) ||
            s.happiness < ensureThreshold(context, n, KEY_THRESH_HAPPY) ||
            s.hygiene < ensureThreshold(context, n, KEY_THRESH_HYGIENE)
    }

    /** La accion MAS urgente ahora mismo (la stat mas hundida bajo SU umbral), o null si no
     *  hace falta ninguna. Solo se muestra UNA nube a la vez: la de mayor necesidad; al
     *  satisfacerla, pasa a mostrar la siguiente. */
    fun mostNeededAction(context: Context, s: Stats): String? {
        val n = currentPokemon(context)
        val candidates = listOfNotNull(
            PokeWidgetProvider.ACTION_FEED.takeIf { s.health < ensureThreshold(context, n, KEY_THRESH_HEALTH) }?.let { it to s.health },
            PokeWidgetProvider.ACTION_PET.takeIf { s.happiness < ensureThreshold(context, n, KEY_THRESH_HAPPY) }?.let { it to s.happiness },
            PokeWidgetProvider.ACTION_WASH.takeIf { s.hygiene < ensureThreshold(context, n, KEY_THRESH_HYGIENE) }?.let { it to s.hygiene }
        )
        return candidates.minByOrNull { it.second }?.first
    }

    /** Stat "de coste" que baja un poco al hacer cada accion (bucle: arreglar una cosa
     *  desgasta otra). Comer ensucia -> higiene. Bañarle no le hace gracia -> felicidad.
     *  Jugar/emocionarse da hambre -> vida. Escalado por la personalidad de la stat que RECIBE
     *  el coste (no la que lo causa) - hallazgo real de esta sesion: sin esto, el multiplicador
     *  de personalidad quedaba diluido de forma desigual segun la stat (higiene depende en buena
     *  parte de este coste cruzado de comer, felicidad casi nada del suyo, asi que el mismo
     *  multiplicador x1.5 daba +46.7% de caricias pero solo +24.5% de lavados) - verificado en el
     *  Simulador de Economia que escalando tambien el coste cruzado los tres dan el mismo +51%. */
    private fun sideEffectFor(action: String, mHealth: Float, mHappy: Float, mHygiene: Float): ((Stats) -> Unit)? = when (action) {
        PokeWidgetProvider.ACTION_FEED -> { s -> s.hygiene = (s.hygiene - SIDE_EFFECT * mHygiene).coerceIn(0f, 100f) }
        PokeWidgetProvider.ACTION_PET -> { s -> s.health = (s.health - SIDE_EFFECT * mHealth).coerceIn(0f, 100f) }
        PokeWidgetProvider.ACTION_WASH -> { s -> s.happiness = (s.happiness - SIDE_EFFECT * mHappy).coerceIn(0f, 100f) }
        else -> null
    }

    /**
     * Intenta aplicar una accion: SOLO tiene efecto si el Pokemon la necesita de verdad (stat
     * por debajo de su umbral, ver THRESH_MIN/MAX_*). En ese caso sube su stat un importe FIJO
     * (BOOST_HEALTH/HAPPY/HYGIENE, no siempre llega a 100: hace falta reaccionar rapido, justo
     * cuando aparece el aviso, para acercarse al maximo) y baja un poco OTRA stat distinta (coste
     * cruzado). Si no la necesita,
     * se RECHAZA (no cambia nada): asi el boton no sirve para mantener la barra siempre llena,
     * es el propio Pokemon el que marca cuando toca cuidarlo.
     * Devuelve true si se acepto (para lanzar la animacion completa) o false si se rechazo.
     */
    // ==================== ESTIMACION DE ACCIONES/DIA (debug, por especie) ====================
    // NO es un registro de lo que de verdad has hecho (eso dependia de cuanto llevaras jugando y
    // salia "0 caricias" en un Pokemon al que aun no le habia tocado pedir eso, aunque su
    // personalidad diga que deberia pedirlo a menudo) - es el calculo directo, con las MISMAS
    // formulas reales de decaimiento/umbral/boost/coste-cruzado, de cuantas veces al dia hace
    // falta esa accion en teoria para esta especie en concreto. Asume que se atiende cada vez que
    // hace falta (ni antes ni despues): el decaimiento total de un dia completo (14h de dia + 10h
    // de noche, ya con el multiplicador de personalidad de esa especie) dividido entre lo que
    // sube cada accion. Pedido explicito del usuario en vez del registro real.
    //
    // OJO con el coste CRUZADO (sideEffectFor): comer baja higiene, acariciar baja vida, lavar
    // baja felicidad - cada accion perjudica a OTRA barra, no solo la suya. Ignorarlo (primera
    // version de esto) subestimaba mucho el resultado en especies con personalidad muy desigual:
    // Torchic (HP45/Vel45/Def40) pide comer muy a menudo (decae rapido de vida), y cada una de
    // esas comidas le resta higiene de rebote - sin contar eso salia ~0.27 lavados/dia, con ello
    // (verificado contra el Simulador de Economia, que SI corre la simulacion completa) sale
    // ~0.90, mucho mas parecido. Se resuelve por iteracion de punto fijo (las 3 ecuaciones se
    // realimentan entre si) - converge en un puñado de vueltas de sobra porque el coste cruzado
    // (10) es bastante mas pequeño que cualquier boost (25-55).
    private const val DAY_HOURS = 14f
    private const val NIGHT_HOURS = 10f

    data class EstimatedActionsPerDay(val feed: Float, val pet: Float, val wash: Float)

    fun estimatedActionsPerDay(context: Context, name: String): EstimatedActionsPerDay {
        val (mHealth, mHappy, mHygiene) = personalityMultipliers(context, name)
        val dailyHealthDecay = (HEALTH_DAY * DAY_HOURS + HEALTH_NIGHT * NIGHT_HOURS) * mHealth
        val dailyHappyDecay = (HAPPY_DAY * DAY_HOURS + HAPPY_NIGHT * NIGHT_HOURS) * mHappy
        val dailyHygieneDecay = (HYGIENE_DAY * DAY_HOURS + HYGIENE_NIGHT * NIGHT_HOURS) * mHygiene
        var feed = dailyHealthDecay / BOOST_HEALTH
        var pet = dailyHappyDecay / BOOST_HAPPY
        var wash = dailyHygieneDecay / BOOST_HYGIENE
        repeat(30) {
            feed = (dailyHealthDecay + SIDE_EFFECT * mHealth * pet) / BOOST_HEALTH
            pet = (dailyHappyDecay + SIDE_EFFECT * mHappy * wash) / BOOST_HAPPY
            wash = (dailyHygieneDecay + SIDE_EFFECT * mHygiene * feed) / BOOST_HYGIENE
        }
        return EstimatedActionsPerDay(feed = feed, pet = pet, wash = wash)
    }

    /** [applied]: si la accion se aceptó de verdad (habia necesidad). [leveledUp]: si el Pokemon
     *  activo cruzó de nivel al sumarle la xp de esta accion en concreto - solo tiene sentido si
     *  [applied] es true. Pedido explicito del usuario: el sonido de subir de nivel SOLO debe
     *  sonar cuando pasa como consecuencia directa de tocar el widget, nunca por la xp pasiva que
     *  se suma sola con el tiempo (ver loadWithDecay) - por eso se compara el nivel justo antes y
     *  despues de sumar SOLO el ACTION_XP de esta pulsada, no desde la ultima vez que se miró. */
    data class ActionResult(val applied: Boolean, val leveledUp: Boolean)

    fun tryApplyAction(context: Context, action: String): ActionResult {
        val s = loadWithDecay(context)
        if (!isNeeded(context, s, action)) return ActionResult(false, false)
        val levelBefore = levelOf(context, s.xp, currentPokemon(context))
        when (action) {
            PokeWidgetProvider.ACTION_FEED -> s.health = (s.health + BOOST_HEALTH).coerceIn(0f, 100f)
            PokeWidgetProvider.ACTION_PET -> s.happiness = (s.happiness + BOOST_HAPPY).coerceIn(0f, 100f)
            PokeWidgetProvider.ACTION_WASH -> s.hygiene = (s.hygiene + BOOST_HYGIENE).coerceIn(0f, 100f)
        }
        val (mHealth, mHappy, mHygiene) = personalityMultipliers(context, currentPokemon(context))
        sideEffectFor(action, mHealth, mHappy, mHygiene)?.invoke(s)
        s.xp += ACTION_XP * xpMultiplier(context, currentPokemon(context))
        val leveledUp = levelOf(context, s.xp, currentPokemon(context)) > levelBefore
        // durable=true (commit sincrono): esta escritura tiene que estar en disco YA al volver
        // de onReceive. Con apply() (asincrono) existia una rendija teorica: si MIUI mata el
        // proceso justo despues de pulsar un boton (antes de que el disco recibiera la
        // escritura), una pulsacion posterior en un proceso NUEVO podia leer el estado VIEJO
        // (sin el boost aplicado) y volver a aceptar una accion que ya se habia satisfecho,
        // pareciendo una animacion "encolada". MIUI es conocido por matar procesos en segundo
        // plano de forma agresiva, asi que esto es una hipotesis real, no solo teorica.
        save(context, s, System.currentTimeMillis(), durable = true)
        // Nuevo "ciclo de necesidad" para esta stat: se sortea un umbral fresco (no siempre el
        // mismo) para la proxima vez, ANTES de que vuelva a hacer falta.
        thresholdKeyFor(action)?.let { rerollThreshold(context, currentPokemon(context), it) }
        if (action == PokeWidgetProvider.ACTION_FEED && currentPokemon(context) == "cramorant") {
            maybeAdvanceCramorant(context)
        }
        if (currentPokemon(context) == "aegislash") {
            toggleAegislashStance(context)
        }
        if (currentPokemon(context) == "solgaleo" || currentPokemon(context) == "lunala") {
            triggerRadiantPhase(context, currentPokemon(context))
        }
        return ActionResult(true, leveledUp)
    }

    fun setReaction(context: Context, emote: String, durationMs: Long) {
        prefs(context).edit()
            .putString(KEY_REACT_EMOTE, emote)
            .putLong(KEY_REACT_UNTIL, System.currentTimeMillis() + durationMs)
            .commit()
    }

    fun activeReaction(context: Context): String? {
        val p = prefs(context)
        return if (System.currentTimeMillis() < p.getLong(KEY_REACT_UNTIL, 0L))
            p.getString(KEY_REACT_EMOTE, null)
        else null
    }

    fun clearReaction(context: Context) {
        prefs(context).edit().putLong(KEY_REACT_UNTIL, 0L).commit()
    }

    // Duracion de la nube "no hace falta" cuando se rechaza una accion (feedback claro de que
    // NO es un fallo de la app: el Pokemon esta diciendo que no lo necesita ahora mismo).
    const val REJECT_FLASH_MS = 1600L

    /** Marca el rechazo de [action] (para mostrar su nube "no hace falta"). Devuelve el
     *  timestamp de caducidad, para poder limpiarlo despues SOLO si nadie lo ha sobreescrito. */
    fun setRejectFlash(context: Context, action: String): Long {
        val until = System.currentTimeMillis() + REJECT_FLASH_MS
        prefs(context).edit()
            .putString(KEY_REJECT_ACTION, action)
            .putLong(KEY_REJECT_UNTIL, until)
            .commit()
        return until
    }

    /** ¿Que accion se acaba de rechazar (si la nube sigue activa)? null si no hay ninguna. */
    fun activeRejectFlash(context: Context): String? {
        val p = prefs(context)
        return if (System.currentTimeMillis() < p.getLong(KEY_REJECT_UNTIL, 0L))
            p.getString(KEY_REJECT_ACTION, null)
        else null
    }

    /** Limpia el rechazo SOLO si sigue siendo el mismo que se marco (evita que un rechazo
     *  posterior de OTRA accion se borre antes de tiempo). */
    fun clearRejectFlashIfStill(context: Context, until: Long) {
        val p = prefs(context)
        if (p.getLong(KEY_REJECT_UNTIL, 0L) == until) p.edit().putLong(KEY_REJECT_UNTIL, 0L).commit()
    }

    /** durable=true fuerza un commit() sincrono (para el resultado de una accion del jugador,
     *  que debe quedar en disco YA); el resto de llamadas (refrescos rutinarios de decaimiento
     *  en cada render) usan apply() normal, mas barato. */
    private fun save(context: Context, s: Stats, now: Long, durable: Boolean = false) {
        val n = currentSlot(context)
        val editor = prefs(context).edit()
            .putFloat(k(n, KEY_HEALTH), s.health)
            .putFloat(k(n, KEY_HYGIENE), s.hygiene)
            .putFloat(k(n, KEY_HAPPINESS), s.happiness)
            .putFloat(k(n, KEY_XP), s.xp)
            .putLong(k(n, KEY_LAST), now)
        if (durable) editor.commit() else editor.apply()
    }

    /** SOLO PARA EL MENU DEBUG: fija a mano cualquier subconjunto de estadisticas del individuo
     *  ACTIVO ahora mismo (null en cualquiera = no tocar esa). Parte de loadWithDecay (no de los
     *  valores crudos guardados) para que las que NO se tocan se queden en su valor real actual,
     *  no en el que tenian antes de que el decaimiento pendiente se aplicara. Reinicia last_update
     *  a ahora, igual que save() en el resto del juego. */
    fun debugSetActiveStats(
        context: Context, health: Float? = null, hygiene: Float? = null,
        happiness: Float? = null, xp: Float? = null
    ) {
        val current = loadWithDecay(context)
        save(
            context,
            Stats(
                health = (health ?: current.health).coerceIn(0f, 100f),
                hygiene = (hygiene ?: current.hygiene).coerceIn(0f, 100f),
                happiness = (happiness ?: current.happiness).coerceIn(0f, 100f),
                xp = (xp ?: current.xp).coerceAtLeast(0f)
            ),
            System.currentTimeMillis(),
            durable = true
        )
    }

    /** SOLO PARA EL MENU DEBUG: fija a mano cualquier subconjunto de estadisticas de un
     *  individuo CUALQUIERA (no hace falta que sea el activo) - null en cualquiera = no tocar
     *  esa. A diferencia de debugSetActiveStats, parte de rawStats (congeladas, sin
     *  decaimiento - un individuo que no es el activo no decae, ver rawStats) en vez de
     *  loadWithDecay. */
    fun debugSetIndividualStats(
        context: Context, name: String, shiny: Boolean,
        health: Float? = null, hygiene: Float? = null, happiness: Float? = null, xp: Float? = null
    ) {
        val base = name.lowercase()
        val n = slot(base, shiny)
        val current = rawStats(context, base, shiny) ?: Stats(75f, 75f, 75f, 0f)
        prefs(context).edit()
            .putFloat(k(n, KEY_HEALTH), (health ?: current.health).coerceIn(0f, 100f))
            .putFloat(k(n, KEY_HYGIENE), (hygiene ?: current.hygiene).coerceIn(0f, 100f))
            .putFloat(k(n, KEY_HAPPINESS), (happiness ?: current.happiness).coerceIn(0f, 100f))
            .putFloat(k(n, KEY_XP), (xp ?: current.xp).coerceAtLeast(0f))
            .putLong(k(n, KEY_LAST), System.currentTimeMillis())
            .commit()
    }

    /** Crea el individuo [shiny] de [name] con datos iniciales (nivel 1, barras 50-100 al azar
     *  como cualquier individuo nuevo, sexo sorteado si aplica) SIN ponerlo como Pokemon activo
     *  del widget - a diferencia de setPokemon/selectPokemon. Usado por resolveOffer (pedido
     *  explicito del usuario: conseguir un Pokemon - aunque no se ponga de compañero al momento -
     *  ya le registra sus valores iniciales, no se queda "vacio" hasta la primera vez que se
     *  cuide) y por el menu debug (crear a mano una especie ya desbloqueada que se quedo sin
     *  datos de antes de este cambio). No hace nada si ese individuo ya existe (no pisa datos
     *  reales por error). */
    fun rollFreshIndividual(context: Context, name: String, shiny: Boolean, id: Int) {
        val base = name.lowercase()
        val n = slot(base, shiny)
        val p = prefs(context)
        if (p.contains(k(n, KEY_XP))) return
        unlock(context, base)
        val e = p.edit()
            .putInt(k(n, KEY_ID), id)
            .putFloat(k(n, KEY_HEALTH), 50f + Random.nextFloat() * 50f)
            .putFloat(k(n, KEY_HYGIENE), 50f + Random.nextFloat() * 50f)
            .putFloat(k(n, KEY_HAPPINESS), 50f + Random.nextFloat() * 50f)
            .putFloat(k(n, KEY_XP), 0f)
            .putLong(k(n, KEY_LAST), System.currentTimeMillis())
        if (base in GENDER_SPECIES) e.putString(k(n, KEY_GENDER), rollGender(base))
        e.commit()
    }

    /** SOLO PARA EL MENU DEBUG: simula que han pasado [hours] horas de golpe - adelanta el reloj
     *  del individuo ACTIVO (decaimiento, ver loadWithDecay) Y el de la tirada del huevo si hay
     *  uno incubando (ver KEY_EGG_LAST_ROLL/maybeHatchEgg), sin esperar de verdad. No hace falta
     *  disparar ningun render aqui: el siguiente tick o accion ya recalcula todo con el reloj
     *  adelantado, igual que si el tiempo hubiera pasado de verdad. */
    fun debugSkipHours(context: Context, hours: Float) {
        val ms = (hours * 3_600_000L).toLong()
        if (ms <= 0L) return
        val n = currentSlot(context)
        val p = prefs(context)
        val e = p.edit()
        e.putLong(k(n, KEY_LAST), p.getLong(k(n, KEY_LAST), System.currentTimeMillis()) - ms)
        if (p.contains(KEY_EGG_LAST_ROLL)) {
            e.putLong(KEY_EGG_LAST_ROLL, p.getLong(KEY_EGG_LAST_ROLL, System.currentTimeMillis()) - ms)
        }
        e.commit()
    }

    /** SOLO PARA EL MENU DEBUG: borra POR COMPLETO un individuo (normal o shiny segun [shiny],
     *  ver slot()) de [name] - como si nunca se hubiera tenido. Si la OTRA variante de la misma
     *  especie tambien ha dejado de existir, la especie entera desaparece de la Pokedex
     *  (unlocked_species); si no, se queda desbloqueada por la que queda (mismo criterio que
     *  isUnlocked/hasIndividual). SI es el individuo ACTIVO ahora mismo (pedido explicito del
     *  usuario: poder quitarse cualquiera, incluido el activo, sin tener que cambiar de compañero
     *  a mano antes), busca CUALQUIER otro individuo que exista y lo deja de compañero antes de
     *  borrar - para no dejar "pokemon" apuntando a una slot vacia. Devuelve el nuevo compañero
     *  (nombre, shiny) si tuvo que cambiarlo, o null si no hizo falta (no era el activo). */
    fun debugDeleteIndividual(context: Context, name: String, shiny: Boolean): Pair<String, Boolean>? {
        val base = name.lowercase()
        val n = slot(base, shiny)
        val p = prefs(context)
        var newActive: Pair<String, Boolean>? = null
        if (currentPokemon(context) == base && isActiveShiny(context) == shiny) {
            val unlocked = p.getStringSet(KEY_UNLOCKED, emptySet()) ?: emptySet()
            for (candidate in unlocked) {
                if (candidate == base && hasIndividual(context, candidate, !shiny)) {
                    newActive = candidate to !shiny; break
                }
                if (candidate == base) continue
                if (hasIndividual(context, candidate, false)) { newActive = candidate to false; break }
                if (hasIndividual(context, candidate, true)) { newActive = candidate to true; break }
            }
            if (newActive != null) {
                newActive.let { (rName, rShiny) ->
                    p.edit().putString(KEY_POKEMON, rName).putBoolean(KEY_ACTIVE_SHINY, rShiny).commit()
                }
            } else {
                // Bug real reportado por el usuario: borrar tu UNICO individuo (sin ningun otro
                // en ninguna especie) dejaba KEY_POKEMON apuntando a una especie sin datos - un
                // estado roto que el resto de la app (renderStatsOnly, refreshRows, el propio
                // widget) no esta preparado para leer. En vez de eso, se trata igual que "todavia
                // no se ha elegido starter": se resetea KEY_STARTER_DONE, y la app/widget vuelven
                // a mandar a StarterActivity solos (ver MainActivity.onCreate/
                // PokeWidgetProvider.renderWelcome) - esto es ademas la unica forma real de
                // "deshacer" el starter elegido para poder escoger otro.
                p.edit().putBoolean(KEY_STARTER_DONE, false).remove(KEY_POKEMON).remove(KEY_ACTIVE_SHINY).commit()
            }
        }
        val e = p.edit()
        // KEY_FAVORITE NO va en este bucle generico (bug real, confirmado con una prueba en el
        // dispositivo): el favorito se guarda bajo la RAIZ de la cadena evolutiva (ver
        // favoriteChainKey), asi que si [base] resulta SER esa raiz, "k(n, KEY_FAVORITE)" es
        // exactamente la misma clave que comparte TODA la cadena - borrarla aqui sin mas le
        // quitaba el favorito a una evolucion posterior que siguiera existiendo intacta (ej.
        // borrar un Cyndaquil ya evolucionado a Quilava le quitaba el favorito a Quilava, que
        // nunca se toco). Se trata aparte mas abajo, mirando toda la cadena antes de borrar.
        for (suffix in listOf(
            KEY_HEALTH, KEY_HYGIENE, KEY_HAPPINESS, KEY_XP, KEY_LAST, KEY_SHINY, KEY_ID,
            KEY_EVOLVED_AWAY, KEY_EVOLVED_TO, KEY_GENDER, KEY_CARE_HOURS,
            KEY_THRESH_HEALTH, KEY_THRESH_HYGIENE, KEY_THRESH_HAPPY,
            "rate_health", "rate_hygiene", "rate_happy"
        )) {
            e.remove(k(n, suffix))
        }
        if (!hasIndividual(context, base, !shiny)) {
            val unlocked = HashSet(p.getStringSet(KEY_UNLOCKED, emptySet()) ?: emptySet())
            unlocked.remove(base)
            e.putStringSet(KEY_UNLOCKED, unlocked).remove(k(base, KEY_UNLOCKED_AT))
        }
        // Favorito: solo se borra si YA NO queda ningun individuo (de esta misma [shiny]) en
        // NINGUNA etapa de la cadena entera - si queda cualquier otra (evolucionada o no), el
        // favorito compartido debe sobrevivir intacto para esa etapa que sigue existiendo.
        if (!chainHasAnyIndividual(context, base, shiny)) {
            e.remove(k(slot(favoriteChainKey(context, base), shiny), KEY_FAVORITE))
        }
        e.commit()
        return newActive
    }

    /** ¿Queda algun individuo [shiny] vivo en CUALQUIER etapa de la cadena evolutiva de [name]
     *  (subiendo hasta la raiz y bajando por TODAS las ramas de evolucion, no solo la lineal)?
     *  Usado para saber si es seguro borrar el favorito compartido de toda la cadena al borrar
     *  un individuo concreto (ver debugDeleteIndividual). */
    private fun chainHasAnyIndividual(context: Context, name: String, shiny: Boolean): Boolean {
        val root = baseFormOf(context, name)
        fun walk(species: String, seen: MutableSet<String>): Boolean {
            if (!seen.add(species)) return false
            if (hasIndividual(context, species, shiny)) return true
            val evolvesTo = evolutionInfo(context, species)?.evolvesTo ?: emptyList()
            return evolvesTo.any { walk(it.name, seen) }
        }
        return walk(root, mutableSetOf())
    }

    // ==================== EVOLUCION (Fase 2) ====================
    // Datos generados UNA vez en el PC (gen_evolutions.py, desde PokeAPI) y empaquetados en
    // assets/evolutions.json: para cada especie, de que preevoluciona (o null) y a que puede
    // evolucionar (id, nombre, nivel, metodo). Los metodos que NO son "sube de nivel a X" en el
    // juego real (piedra, amistad, intercambio...) se simplificaron a un nivel EQUIVALENTE fijo
    // (ver DEFAULT_EVO_LEVEL en el script), porque aqui no hay objetos/inventario.
    data class EvoOption(val id: Int, val name: String, val level: Int, val method: String)
    data class EvoEntry(val id: Int, val evolvesFrom: String?, val evolvesTo: List<EvoOption>, val rare: Boolean)

    @Volatile private var evoTable: Map<String, EvoEntry>? = null

    private fun loadEvoTable(context: Context): Map<String, EvoEntry> {
        evoTable?.let { return it }
        val json = context.assets.open("evolutions.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        val map = HashMap<String, EvoEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.getString("name")
            val from = if (o.isNull("evolves_from")) null else o.getString("evolves_from")
            val toArr = o.getJSONArray("evolves_to")
            val opts = ArrayList<EvoOption>(toArr.length())
            for (j in 0 until toArr.length()) {
                val e = toArr.getJSONObject(j)
                opts.add(EvoOption(e.getInt("id"), e.getString("name"), e.getInt("level"), e.getString("method")))
            }
            map[name] = EvoEntry(o.getInt("id"), from, opts, o.optBoolean("rare", false))
        }
        evoTable = map
        return map
    }

    /** Info de evolucion de una especie (null si no esta en la tabla, no deberia pasar). */
    fun evolutionInfo(context: Context, name: String): EvoEntry? = loadEvoTable(context)[name.lowercase()]

    /** Si [name] ya cumple el nivel para evolucionar (segun sus stats [stats], activas o
     *  congeladas), devuelve sus opciones YA DISPONIBLES (una o varias si hay ramas, ej. Eevee) -
     *  null si no le toca ninguna todavia o ya evoluciono. Cada rama se filtra por SU PROPIO
     *  nivel, no por el minimo del grupo (bug real: con el minimo, especies con ramas a niveles
     *  distintos - Eevee 22 vs 25, Slowpoke 32 vs 37, etc. - ofrecian la rama de nivel mas alto
     *  antes de tiempo). No consume nada ni marca nada: se puede llamar tantas veces como haga
     *  falta (el widget para el aviso del activo, el detalle de cualquier Pokemon). */
    /** [shiny] tiene que ser el del individuo dueño de [stats] - normal y shiny son individuos
     *  independientes (ver slot()), cada uno con su propio progreso evolutivo. Bug real reportado
     *  por el usuario: sin esto, siempre se miraba si el individuo NORMAL ya habia evolucionado
     *  (shiny=false por defecto en hasEvolvedAway), asi que un shiny que aun no evoluciono se
     *  quedaba sin la opcion de evolucionar en cuanto el normal de esa especie ya lo hubiera
     *  hecho. */
    fun pendingEvolutionFor(context: Context, name: String, stats: Stats, shiny: Boolean = false): EvoEntry? {
        if (hasEvolvedAway(context, name, shiny)) return null
        val entry = evolutionInfo(context, name) ?: return null
        if (entry.evolvesTo.isEmpty()) return null
        val lvl = levelOf(context, stats.xp, name)
        val ready = entry.evolvesTo.filter { lvl >= it.level }
        return if (ready.isEmpty()) null else entry.copy(evolvesTo = ready)
    }

    /** Atajo para el Pokemon ACTIVO (lo usa el widget para el aviso "✨¡Evoluciona!"). */
    fun pendingEvolution(context: Context, stats: Stats): EvoEntry? =
        pendingEvolutionFor(context, currentPokemon(context), stats, isActiveShiny(context))

    /**
     * Resuelve el destino REAL de una evolucion hacia [toName]/[toId]: normalmente el mismo tal
     * cual, salvo para Ursaring -> Ursaluna, donde hay un sorteo (RARE_EVO_VARIANT_CHANCE) hacia
     * su variante rara Luna Carmesi (mismo espiritu que Dudunsparce/Maushold en evolveTo: en el
     * juego real depende de si hay luna llena la noche en que se usa el Bloque de Turba, algo que
     * esta app no puede replicar - aqui, igual de silencioso y raro). Ursaluna Luna Carmesi
     * comparte el mismo dexId (901) que Ursaluna normal (es una FORMA suya, no otra especie de la
     * Pokedex nacional), asi que [toId] nunca cambia, solo el nombre de especie interno.
     * Llamar ANTES de preparar/animar la evolucion (ver doEvolve), para que el sprite/grito que se
     * descargue y el resultado mostrado ya sean los correctos desde el principio, en vez de migrar
     * a un nombre y solo despues "corregirlo" por dentro sin que la UI se entere.
     */
    fun resolveEvolutionTarget(toName: String, toId: Int): Pair<String, Int> {
        if (toName.lowercase() == "ursaluna" && Random.nextFloat() < RARE_EVO_VARIANT_CHANCE) {
            return "ursaluna-bloodmoon" to toId
        }
        return toName to toId
    }

    /**
     * Evoluciona el individuo [shiny] (normal o shiny, NO necesariamente el activo del widget) de
     * [fromName] hacia [toName]: migra su estado (stats/xp/reloj/ritmos) de las claves de la
     * especie vieja a las de la nueva (el nivel/xp/cariño NO se pierden, solo cambia la especie),
     * lo desbloquea en la Pokedex, lo deja como Pokemon ACTIVO del widget, y marca la especie
     * vieja como "evolucionada" (queda registrada en la Pokedex - se tuvo - pero ya no se puede
     * volver a seleccionar: el individuo que era ya es otra especie). NO descarga el sprite ni
     * refresca el widget - eso lo hace quien llama (necesita red/hilo de fondo). [toName] ya debe
     * venir resuelto (ver resolveEvolutionTarget) - esta funcion migra tal cual, sin sortear nada.
     */
    fun evolveTo(context: Context, fromName: String, toName: String, toId: Int, shiny: Boolean) {
        val from = fromName.lowercase()
        val to = toName.lowercase()
        if (from == to) return
        val p = prefs(context)
        // [shiny] es del INDIVIDUO que evoluciona de verdad (normal o shiny, ver slot()) - viene
        // de quien llama, YA NO se asume el activo del widget (bug real: evolucionar un shiny
        // desde su ficha mientras el activo era el normal migraba las stats del NORMAL y dejaba
        // el shiny intacto). El evolucionado SI pasa a ser el nuevo activo (KEY_POKEMON/
        // KEY_ACTIVE_SHINY mas abajo), pero cual de los dos individuos era el que evolucionaba lo
        // decide el llamador, no un flag global.
        val fromSlot = slot(from, shiny)
        val toSlot = slot(to, shiny)
        val health = p.getFloat(k(fromSlot, KEY_HEALTH), 100f)
        val hygiene = p.getFloat(k(fromSlot, KEY_HYGIENE), 100f)
        val happiness = p.getFloat(k(fromSlot, KEY_HAPPINESS), 100f)
        val xp = p.getFloat(k(fromSlot, KEY_XP), 0f)
        val lastUpdate = p.getLong(k(fromSlot, KEY_LAST), System.currentTimeMillis())
        // Horas de cuidado: MISMO individuo, solo cambia de especie - sigue siendo un unico
        // contador continuo a traves de toda la cadena (pedido explicito del usuario).
        val careHours = p.getFloat(k(fromSlot, KEY_CARE_HOURS), 0f)
        // Favorito: NO hace falta copiar nada aqui - se guarda bajo la RAIZ de la cadena (ver
        // isFavorite/setFavorite/favoriteChainKey), que es la misma antes y despues de
        // evolucionar, asi que ya "sigue" a la linea entera sin ningun paso extra.
        val e = p.edit()
            .putString(KEY_POKEMON, to)
            .putBoolean(KEY_ACTIVE_SHINY, shiny)
            .putInt(k(toSlot, KEY_ID), toId)
            .putFloat(k(toSlot, KEY_HEALTH), health)
            .putFloat(k(toSlot, KEY_HYGIENE), hygiene)
            .putFloat(k(toSlot, KEY_HAPPINESS), happiness)
            .putFloat(k(toSlot, KEY_XP), xp)
            .putLong(k(toSlot, KEY_LAST), lastUpdate)
            .putFloat(k(toSlot, KEY_CARE_HOURS), careHours)
            .putBoolean(k(to, KEY_SHINY), shiny)  // flag legacy (comparte con currentPokemon/isActiveShiny)
            .putBoolean(k(fromSlot, KEY_EVOLVED_AWAY), true)
            .putString(k(fromSlot, KEY_EVOLVED_TO), to)
        // Genero: el sexo de un individuo NUNCA cambia al evolucionar. Si [from] ya tenia uno
        // guardado (misma linea, ambas con sprite de genero) se hereda tal cual; si no (ej.
        // Lechonk, sin sprite propio, evolucionando a Oinkologne, que si lo tiene), se sortea
        // aqui por primera vez con la probabilidad real de [to]. Por variante (slot), igual que
        // el resto del progreso de este individuo.
        if (to in GENDER_SPECIES) {
            val inherited = p.getString(k(fromSlot, KEY_GENDER), null)
            e.putString(k(toSlot, KEY_GENDER), inherited ?: rollGender(to))
        }
        // Burmy -> Wormadam: el manto (que hasta ahora seguia el fondo actual en vivo, ver
        // displaySpriteName) queda FIJO para siempre en el que tuviera justo en este momento -
        // Mothim no tiene manto, no aplica.
        if (from == "burmy" && to == "wormadam") {
            e.putString(KEY_WORMADAM_CLOAK, burmyCloakForBg(currentBg(context)))
        }
        // Dudunsparce/Maushold: 1% (sorteado una sola vez, aqui, al evolucionar) de sacar la
        // variante rara - fijo para siempre desde este momento.
        if (to == "dudunsparce" && Random.nextFloat() < RARE_EVO_VARIANT_CHANCE) {
            e.putBoolean(KEY_DUDUNSPARCE_THREE, true)
        }
        if (to == "maushold" && Random.nextFloat() < RARE_EVO_VARIANT_CHANCE) {
            e.putBoolean(KEY_MAUSHOLD_THREE, true)
        }
        // Evolucionar a la fase final no debe poder poner huevo en el mismo instante: sin esto,
        // el refresco inmediato del widget tras evolucionar (MainActivity llama a
        // WidgetRefresh.updateWidgets justo despues de evolveTo) dispara onUpdate -> checkAlerts,
        // que es donde vive la tirada de huevo - y como isFinalStage acaba de pasar a true AHORA
        // mismo, esa es la PRIMERA tirada real posible para esta cadena, cayendo en la misma
        // pasada que la propia evolucion en vez de esperar el intervalo normal. Bug real
        // confirmado en el log del usuario (grovyle -> sceptile y "huevo: puesto nuevo" en el
        // mismo segundo, nivel 36 -> ~37% de probabilidad, no hizo falta mucha mala suerte).
        // Reiniciar aqui el reloj de tiradas retrasa la primera comprobacion real hasta que pase
        // EGG_ROLL_INTERVAL_MS de verdad, igual que cualquier otro chequeo periodico posterior.
        if (isFinalStage(context, to)) {
            e.putLong(KEY_EGG_LAST_ROLL, System.currentTimeMillis())
        }
        e.commit()
        unlock(context, to)
    }

    /**
     * SOLO PARA PRUEBAS (disparado por el broadcast ACTION_DEBUG_REVERT de PokeWidgetProvider,
     * via adb): deshace una evolucion entera. [toName] queda borrado por completo (como si nunca
     * hubiera evolucionado - stats, reloj, umbrales, shiny, id) y [fromName] vuelve a ser el
     * activo, con su reloj reiniciado a ahora (para no arrastrar de golpe la xp "pasiva" de todo
     * el tiempo que estuvo aparcado). Al pasar POR el proceso vivo de la app (esta misma
     * SharedPreferences en memoria, no un fichero externo), no hay ninguna carrera posible con
     * el propio proceso - no hace falta pararlo ni desactivarlo para nada.
     */
    fun debugRevertEvolution(context: Context, fromName: String, toName: String) {
        val from = fromName.lowercase()
        val to = toName.lowercase()
        if (from == to) return
        val p = prefs(context)
        val e = p.edit()
            .putString(KEY_POKEMON, from)
            .remove(k(from, KEY_EVOLVED_AWAY))
            .remove(k(from, KEY_EVOLVED_TO))
            .putLong(k(from, KEY_LAST), System.currentTimeMillis())
        for (suffix in listOf(
            KEY_HEALTH, KEY_HYGIENE, KEY_HAPPINESS, KEY_XP, KEY_LAST, KEY_SHINY, KEY_ID,
            KEY_THRESH_HEALTH, KEY_THRESH_HYGIENE, KEY_THRESH_HAPPY,
            "rate_health", "rate_hygiene", "rate_happy"
        )) {
            e.remove(k(to, suffix))
        }
        val unlocked = HashSet(p.getStringSet(KEY_UNLOCKED, emptySet()) ?: emptySet())
        unlocked.remove(to)
        unlocked.add(from)
        e.putStringSet(KEY_UNLOCKED, unlocked)
        e.commit()
    }

    // ==================== OFERTAS PERIODICAS (Fase 3) ====================
    // De vez en cuando (tiempo REAL, no ligado al nivel) se ofrecen 5 formas base al azar que
    // el jugador aun no tenga; elegir una la desbloquea en la Pokedex SIN tocar al Pokemon
    // activo. La oferta, una vez generada, se queda igual (no se regenera) hasta que se elige
    // una o se descarta explicitamente - asi no "rueda" sola con cada apertura de la app.
    private const val KEY_OFFER_SPECIES = "offer_species"   // "nombre:id,nombre:id,..." o vacio
    private const val KEY_OFFER_LAST = "offer_last_time"   // ancla de INICIO DE CICLO (no de generacion, ver maybeGenerateOffer)
    private const val OFFER_INTERVAL_HOURS = 24f   // a cuantas horas del inicio del ciclo aparece el regalo
    private const val OFFER_GRACE_HOURS = 48f      // limite del ciclo: pasado esto sin abrir, se pierde el turno del siguiente regalo (24h de margen tras aparecer)

    data class OfferMon(val name: String, val id: Int)

    // Peso de sorteo: los legendarios/miticos pesan MUCHO menos que el resto, para que salgan
    // pero raramente (con ~88 formas base legendarias/miticas de 541 totales, sin ponderar
    // saldrian con ~16% de probabilidad cada vez - demasiado facil). Con este peso, la
    // probabilidad de que un hueco concreto salga legendario baja a ~1%.
    private const val WEIGHT_NORMAL = 1.0f
    private const val WEIGHT_RARE = 0.05f

    /** Sortea [k] elementos SIN repetir de [items] (pares valor+peso), con probabilidad
     *  proporcional al peso de cada uno (mayor peso = mas probable, nunca sale dos veces). */
    private fun <T> weightedSampleWithoutReplacement(items: List<Pair<T, Float>>, k: Int): List<T> {
        val pool = items.toMutableList()
        val result = ArrayList<T>(minOf(k, pool.size))
        repeat(minOf(k, pool.size)) {
            val total = pool.sumOf { it.second.toDouble() }
            var r = Random.nextDouble() * total
            var idx = pool.size - 1
            for (i in pool.indices) {
                r -= pool[i].second
                if (r <= 0.0) { idx = i; break }
            }
            result.add(pool[idx].first)
            pool.removeAt(idx)
        }
        return result
    }

    /** Ciclo de regalos (pedido explicitamente asi): el regalo aparece a las OFFER_INTERVAL_HOURS
     *  (24h) del inicio del ciclo actual; si se abre/descarta antes de OFFER_GRACE_HOURS (48h), el
     *  ciclo se reinicia AL INSTANTE desde ese momento (ver resolveOffer/dismissOffer) - asi el
     *  jugador que va abriendo sus regalos a tiempo siempre tiene uno nuevo cada 24h, ni mas ni
     *  menos. Si NO se abre a tiempo, el ciclo avanza igualmente en bloques de 48h (no se queda
     *  esperando para siempre) pero el regalo pendiente NO desaparece ni se fuerza - solo se
     *  pierde el turno del regalo que le habria tocado al siguiente ciclo, porque el hueco (solo
     *  cabe 1 a la vez) sigue ocupado por el que aun no se ha abierto. Devuelve true solo si de
     *  verdad ha generado una oferta nueva (para poder notificar). */
    fun maybeGenerateOffer(context: Context): Boolean {
        val p = prefs(context)
        val last = p.getLong(KEY_OFFER_LAST, 0L)
        val hasPending = !p.getString(KEY_OFFER_SPECIES, "").isNullOrEmpty()
        if (hasPending) {
            if (last != 0L) {
                val graceMs = (OFFER_GRACE_HOURS * 3_600_000f).toLong()
                var anchor = last
                while (System.currentTimeMillis() - anchor >= graceMs) anchor += graceMs
                if (anchor != last) p.edit().putLong(KEY_OFFER_LAST, anchor).commit()
            }
            return false
        }
        if (last != 0L && (System.currentTimeMillis() - last) / 3_600_000f < OFFER_INTERVAL_HOURS) return false
        val candidates = loadEvoTable(context).entries
            .filter { (name, entry) -> entry.evolvesFrom == null && !isUnlocked(context, name) }
            .map { (name, entry) -> OfferMon(name, entry.id) to (if (entry.rare) WEIGHT_RARE else WEIGHT_NORMAL) }
        if (candidates.isEmpty()) return false   // ya se tiene absolutamente todo
        val picked = weightedSampleWithoutReplacement(candidates, 5)
        val csv = picked.joinToString(",") { "${it.name}:${it.id}" }
        // OJO: KEY_OFFER_LAST NO se toca aqui - sigue anclado al inicio del ciclo (fijado en
        // resolveOffer/dismissOffer/markStarterChosen), no al momento en que el regalo aparece.
        p.edit().putString(KEY_OFFER_SPECIES, csv).commit()
        return true
    }

    /** Milisegundos que faltan hasta el proximo hito del ciclo: si aun no hay ninguna oferta
     *  pendiente, tiempo hasta que APAREZCA (marca de 24h desde el inicio del ciclo); si ya
     *  aparecio y sigue sin abrirse, tiempo que queda de plazo antes de perder el turno del
     *  siguiente regalo (marca de 48h). Sigue contando siempre (nunca se oculta ni se congela)
     *  para que el jugador pueda comprobar el mismo que abrir tarde no le penaliza. */
    fun offerCooldownRemainingMs(context: Context): Long {
        val p = prefs(context)
        val last = p.getLong(KEY_OFFER_LAST, 0L)
        if (last == 0L) return 0L
        val hasPending = !p.getString(KEY_OFFER_SPECIES, "").isNullOrEmpty()
        val targetHours = if (hasPending) OFFER_GRACE_HOURS else OFFER_INTERVAL_HOURS
        val targetMs = (targetHours * 3_600_000f).toLong()
        return (last + targetMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** La oferta pendiente ahora mismo (vacia si no hay ninguna). */
    fun currentOffer(context: Context): List<OfferMon> {
        val csv = prefs(context).getString(KEY_OFFER_SPECIES, "") ?: ""
        if (csv.isEmpty()) return emptyList()
        return csv.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            val id = parts.getOrNull(1)?.toIntOrNull()
            if (parts.isNotEmpty() && id != null) OfferMon(parts[0], id) else null
        }
    }

    /** Momento en que aparecio la oferta ACTUAL (ancla de ciclo + 24h) - usado por
     *  resolveOffer/dismissOffer para no penalizar al jugador que tarda en abrir el regalo. */
    private fun offerAppearedAt(context: Context): Long {
        val last = prefs(context).getLong(KEY_OFFER_LAST, 0L)
        return last + (OFFER_INTERVAL_HOURS * 3_600_000f).toLong()
    }

    /** Elige [name] de la oferta actual: lo desbloquea Y le crea sus valores iniciales de una vez
     *  (nivel 1, barras al azar - ver rollFreshIndividual, pedido explicito del usuario: "cada vez
     *  que obtienes un Pokemon... se registran sus valores iniciales como si te lo hubieses
     *  puesto", aunque no se ponga de compañero al momento - a diferencia de antes, que solo lo
     *  apuntaba en la Pokedex y no le creaba nada hasta la primera vez que se cuidaba de verdad),
     *  sin cambiar el Pokemon activo, y limpia la oferta. Reinicia el ciclo desde el momento en
     *  que ESTE regalo aparecio (no desde ahora): pedido explicitamente asi porque anclar al
     *  instante de abrir penalizaba tardar en abrirlo (cada retraso se acumulaba para siempre en
     *  el ciclo siguiente). Con esto, el siguiente regalo siempre llega justo 24h despues de que
     *  este apareciera, se abra al momento o con retraso (dentro del plazo de gracia) - si
     *  tardaste 2h en abrirlo, el timer del siguiente ya empieza mostrando 22h en vez de 24h
     *  completas. */
    fun resolveOffer(context: Context, name: String) {
        val n = name.lowercase()
        unlock(context, n)
        val id = loadEvoTable(context)[n]?.id
        if (id != null) rollFreshIndividual(context, n, false, id)
        val anchor = offerAppearedAt(context)
        prefs(context).edit().putString(KEY_OFFER_SPECIES, "").putLong(KEY_OFFER_LAST, anchor).commit()
    }

    /** Descarta la oferta actual sin elegir ninguno (si no gustan los 5, esperar a los
     *  siguientes en vez de dejarla pendiente para siempre). Mismo reinicio de ciclo que en
     *  resolveOffer: descartar cuenta igual que abrir, el hueco vuelve a quedar libre desde el
     *  momento en que este regalo aparecio, no desde ahora. */
    fun dismissOffer(context: Context) {
        val anchor = offerAppearedAt(context)
        prefs(context).edit().putString(KEY_OFFER_SPECIES, "").putLong(KEY_OFFER_LAST, anchor).commit()
    }

    // ==================== NOTIFICACIONES (base) ====================
    // Evita machacar al jugador con la misma notificacion cada 15 min mientras la condicion
    // siga activa: se marca "ya avisado" al notificar, y SOLO se limpia cuando la condicion deja
    // de cumplirse (evoluciona / se satisface la necesidad) - asi una condicion NUEVA (evoluciona
    // otra vez, vuelve a bajar una barra) si vuelve a avisar.
    private const val KEY_NOTIFIED_EVOLUTION = "notified_evolution"
    private const val KEY_NOTIFIED_URGENT = "notified_urgent"

    fun wasNotifiedEvolutionReady(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFIED_EVOLUTION, false)
    fun setNotifiedEvolutionReady(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_NOTIFIED_EVOLUTION, value).apply()

    fun wasNotifiedUrgent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFIED_URGENT, false)
    fun setNotifiedUrgent(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_NOTIFIED_URGENT, value).apply()

    // Mientras la necesidad SIGA sin resolverse, se recuerda cada cierto tiempo (no solo la
    // primera vez) - respeta igualmente el sueño (22h-8h), asi que no repite de noche.
    private const val KEY_URGENT_LAST_NOTIF = "urgent_last_notif"
    private const val URGENT_REPEAT_HOURS = 1f

    fun urgentRepeatDue(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_URGENT_LAST_NOTIF, 0L)
        return last == 0L || (System.currentTimeMillis() - last) / 3_600_000f >= URGENT_REPEAT_HOURS
    }
    fun markUrgentNotified(context: Context) =
        prefs(context).edit().putLong(KEY_URGENT_LAST_NOTIF, System.currentTimeMillis()).apply()

    // Causa raiz REAL (confirmada con crashes reales en dumpsys dropbox, no solo teoria) del bug
    // "RemoteViews exceeds maximum bitmap memory usage": cada partiallyUpdateAppWidget (nube de
    // necesidad, icono del huevo...) ACUMULA en la cuenta de memoria de bitmaps que lleva el
    // propio Android para ese widget - un updateAppWidget COMPLETO es lo unico que la resetea
    // (reemplaza el arbol entero de RemoteViews en vez de fusionar encima). Si se pasan muchas
    // horas sin ninguna accion aceptada (que ya fuerza un render completo al reaccionar), esa
    // cuenta puede acabar creciendo hasta el limite real del dispositivo (~19.85MB visto en
    // logs) - el try/catch de renderStatsOnly ya evita que esto CRASHEE, pero no evita que seria
    // siga ocurriendo de fondo cada 8-12h de uso real. Este timestamp permite forzar un render
    // completo PERIODICO (ver FULL_RENDER_RESET_INTERVAL_MS en PokeWidgetProvider) antes de que
    // la cuenta llegue a acercarse al limite, en vez de esperar a que falle para recuperarse.
    private const val KEY_LAST_FULL_RENDER = "last_full_render"
    fun msSinceLastFullRender(context: Context): Long {
        val last = prefs(context).getLong(KEY_LAST_FULL_RENDER, 0L)
        return if (last == 0L) Long.MAX_VALUE else System.currentTimeMillis() - last
    }
    fun markFullRender(context: Context) =
        prefs(context).edit().putLong(KEY_LAST_FULL_RENDER, System.currentTimeMillis()).apply()

    // ==================== HUEVO (Pieza 2) ====================
    // Solo un Pokemon en su ULTIMA fase evolutiva puede poner huevos (por tiempo, con mas
    // probabilidad cuanto mas nivel tenga - aliciente para seguir subiendo tras evolucionar del
    // todo, que hoy no tenia ninguno). El huevo aparece JUNTO al Pokemon (no lo sustituye) y
    // eclosiona SIEMPRE en la forma BASE de la MISMA cadena evolutiva del padre (es cria, no una
    // oferta) + un roll de shiny independiente. Solo un huevo pendiente a la vez.
    private const val KEY_EGG_ACTIVE = "egg_active"
    private const val KEY_EGG_PARENT = "egg_parent"
    private const val KEY_EGG_PARENT_SHINY = "egg_parent_shiny"  // shiny del PADRE al poner el huevo (para el bono de xp al eclosionar, ver EGG_HATCH_XP_BONUS)
    private const val KEY_EGG_LAID_AT = "egg_laid_at"
    private const val KEY_EGG_SHINY = "egg_shiny"
    private const val KEY_EGG_READY = "egg_ready"
    private const val KEY_EGG_DISABLED_PREFIX = "egg_disabled."
    private const val KEY_EGG_DEBUG_STAGE = "egg_debug_stage"  // -1 = sin forzar, usa la fase real guardada (ver menu debug)
    private const val KEY_EGG_STAGE = "egg_stage"  // 0..EGG_CRACK_STAGES-1, avanza SOLO por tirada (maybeHatchEgg), nunca por tiempo
    private const val KEY_EGG_LAST_ROLL = "egg_last_roll"
    // checkAlerts (poner/avanzar el huevo) no se llama SOLO desde el tick real de 15min: tambien
    // se dispara desde onUpdate, que salta con CUALQUIER refresco del widget (cambiar de Pokemon
    // activo, evolucionar...). Sin este limite, cada cambio de activo colaba una tirada extra sin
    // haber pasado tiempo real - bug real reportado por el usuario (la fase subia con solo
    // cambiar de Pokemon y volver). Con esto, como mucho una tirada de verdad por este intervalo,
    // pase lo que pase mientras tanto.
    private const val EGG_ROLL_INTERVAL_MS = 15 * 60 * 1000L
    // Tope de tiradas a recuperar de golpe si el chequeo periodico se retraso mucho (Doze/ahorro
    // de bateria reteniendo la alarma horas - caso real detectado por el usuario via el log de
    // diagnostico: hueco de 2h20m sin ninguna tirada) - ver eggRollsDue. 200*15min = 50h, mas que
    // de sobra para cualquier retraso real; solo protege de un reloj de sistema mal puesto.
    private const val MAX_EGG_CATCHUP_ROLLS = 200

    // Numeros de PRIMERA PASADA (probabilidad de poner/eclosionar, duracion tipica): faciles de
    // retocar despues con feedback real, igual que se hizo con el resto de la economia del
    // juego.
    private const val EGG_LAY_BASE_PROB = 0.01f
    private const val EGG_LAY_LEVEL_SCALE = 0.01f
    private const val EGG_LAY_PROB_MAX = 0.4f
    // Rediseñado: ya NO es una unica tirada "eclosiona ya/todavia no" independiente de la fase
    // visual (eso permitia "eclosionar" en fase 0, sin ninguna grieta - lo que parecia un bug).
    // Ahora el huevo tiene que pasar, si o si, por TODAS las fases en orden: cada tick con huevo
    // activo hay una tirada; si sale bien, pasa a la fase siguiente (o, si ya estaba en la
    // ultima, eclosiona de verdad) - si no, se queda igual. Es la misma probabilidad para cada
    // paso (como un yunque: cada uso tiene su propia tirada de "se rompe un poco mas o no").
    // Con EGG_CRACK_STAGES=4 hacen falta 4 tiradas con exito en total (3 para recorrer las fases
    // + 1 para eclosionar desde la ultima). p=0.5 da una media de 2 tics (30min) por tirada, asi
    // que la media total sigue siendo ~2h (4 x 30min) - la MISMA media que tenia la version
    // anterior (0.125 de probabilidad = media de 8 tics = 2h en una sola tirada), asi que el
    // ritmo general de huevos/dia y la cadencia de shinies (ver EGG_SHINY_CHANCE) no cambian.
    private const val EGG_STAGE_ADVANCE_PROB = 0.5f
    // Bajado de 0.05 a 0.01 al subir la frecuencia de huevos de arriba: el usuario ya habia
    // elegido "ocasional" (~2-3 semanas reales entre shinies) cuando los huevos salian ~1/dia: al
    // salir ahora ~6-8 veces mas seguido, hacia falta bajar la probabilidad proporcionalmente para
    // NO perder ese ritmo (validado con la misma simulacion: sigue dando un shiny cada ~13-17 dias
    // reales, igual que antes).
    // Subido de 0.01 a 0.02 (el usuario pidio explicitamente doblarlo): con solo x1 (sin fondo a
    // juego) el 1/50 de antes ya se sentia demasiado largo, y el "mejor caso posible" (fondo a
    // juego, x2) era 1/50 - le parecia mucho. Al doblar la base, el mejor caso pasa a 1/25 y el
    // peor caso (sin fondo a juego) a 1/50 (antes 1/100) - misma simulacion de antes escalada x0.5:
    // shiny cada ~6.5-8.5 dias reales sin boost (antes ~13-17), la mitad con fondo a juego. Ya NO
    // hay un tramo x4 ("boost doble") para nadie - ver bgMatchMultiplier, decision consciente al
    // perder la doble cobertura de tipo por fondo con la ronda de "un fondo por tipo".
    private const val EGG_SHINY_CHANCE = 0.02f
    const val EGG_CRACK_STAGES = 4          // fases visuales de rotura antes de "listo"

    // Bono de xp para el PADRE (no la cria) al eclosionar de verdad el huevo - pedido explicito
    // del usuario: quiere que criar un huevo ayude a subir de nivel mas rapido, y asi poner el
    // siguiente antes (la probabilidad de puesta escala con el nivel, ver EGG_LAY_LEVEL_SCALE),
    // pero SIN que el ciclo huevo->nivel->huevo se dispare de forma exponencial. Por eso es un
    // numero FIJO (no escala con el nivel ni con la especie): un empujon notable a niveles bajos/
    // medios, cada vez mas pequeño en proporcion segun el coste por nivel sube (ver cumXp) - se
    // autolimita solo, sin necesidad de ningun tope aparte. Se aplica en el momento exacto en que
    // el huevo eclosiona (maybeHatchEgg), no al decidir quedarselo o soltarlo - el padre ya hizo
    // el trabajo pase lo que pase despues con la cria.
    private const val EGG_HATCH_XP_BONUS = 150f

    /** ¿[name] esta en su ultima fase evolutiva (no tiene mas evoluciones)? Sin datos = se
     *  trata como final (no se puede saber que le falte una evolucion). */
    fun isFinalStage(context: Context, name: String): Boolean =
        evolutionInfo(context, name)?.evolvesTo?.isEmpty() ?: true

    /** ¿Tiene activada la puesta de huevos [name]? Por defecto SI (el jugador lo desactiva a
     *  mano desde la ficha si no lo quiere). */
    fun eggLayingEnabled(context: Context, name: String): Boolean =
        !prefs(context).getBoolean(KEY_EGG_DISABLED_PREFIX + name.lowercase(), false)
    fun setEggLayingEnabled(context: Context, name: String, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_EGG_DISABLED_PREFIX + name.lowercase(), !enabled).apply()

    fun hasActiveEgg(context: Context): Boolean = prefs(context).getBoolean(KEY_EGG_ACTIVE, false)
    fun eggParent(context: Context): String? = prefs(context).getString(KEY_EGG_PARENT, null)
    fun isEggReady(context: Context): Boolean = prefs(context).getBoolean(KEY_EGG_READY, false)
    fun eggIsShiny(context: Context): Boolean = prefs(context).getBoolean(KEY_EGG_SHINY, false)

    /** ¿El huevo pendiente (si hay alguno) es del Pokemon que esta activo AHORA MISMO? El huevo
     *  sigue el suyo (incubando/listo) aunque cambies de activo - eso ya pasa solo, el tick
     *  periodico no mira quien esta activo para eclosionarlo - pero los indicadores VISUALES
     *  (icono en el widget, aviso en la ficha) solo deben aparecer sobre el Pokemon que de
     *  verdad lo puso, no sobre cualquier otro que tengas activo mientras tanto. */
    fun activeIsEggParent(context: Context): Boolean {
        if (!hasActiveEgg(context)) return false
        val parent = eggParent(context) ?: return false
        return parent.lowercase() == currentPokemon(context)
    }

    /** La especie que va a eclosionar (la forma BASE de la cadena del padre, ya con la posible
     *  forma regional ya sorteada al poner el huevo aplicada - ver REGIONAL_HATCH_VARIANT) - para
     *  poder mostrarla en el dialogo de revelacion ANTES de que el jugador decida soltar/quedarsela. */
    fun eggHatchSpecies(context: Context): String? {
        val base = eggParent(context)?.let { baseFormOf(context, it) } ?: return null
        if (REGIONAL_HATCH_VARIANT.containsKey(base)) {
            return prefs(context).getString(KEY_EGG_REGIONAL_SPECIES, null) ?: base
        }
        return base
    }

    /** Nombre de sprite para la revelacion del huevo (misma idea que displaySpriteName, pero para
     *  la especie del huevo PENDIENTE, aun sin quedarse con ella - por eso lee directamente la
     *  forma de Tatsugiri YA sorteada al ponerlo, en vez de la forma "actual" que aun no existe). */
    fun eggHatchSpriteName(context: Context): String? {
        val species = eggHatchSpecies(context) ?: return null
        if (species == "tatsugiri") {
            val form = prefs(context).getString(KEY_EGG_TATSUGIRI_FORM, null) ?: return species
            return tatsugiriSpriteKey(form)
        }
        if (decorativeFormsOptions(species).isNotEmpty()) {
            val form = prefs(context).getString(KEY_EGG_DECORATIVE_FORM, null) ?: return species
            return decorativeSpriteKey(species, form)
        }
        return species
    }

    // Rafagas antiguas del huevo (ver PokeWidgetProvider.playEggPulse) - RETIRADAS del todo: las
    // 4 fases (1..3, la fase 0 nunca las uso) ya se animan ellas solas y sin parar con
    // egg_flipper (ViewFlipper nativo, autoStart), cada una a su propio ritmo (ver
    // EGG_STAGE_FLIP_MS/EGG_STAGE_PAUSE_MS en PokeWidgetProvider). Antes se intento sostener el
    // palpito con un Thread/goAsync de larga duracion y causaba una congelacion real del widget
    // en este dispositivo; el ViewFlipper nativo no depende de ningun hilo de la app, asi que no
    // deberia sufrir el mismo problema. Se deja este array en "nunca" para las 4 fases en vez de
    // borrar playEggPulse/eggPulseDue del todo (por si hiciera falta retomarlas).
    private val EGG_PULSE_PERIOD_MS = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
    private const val KEY_EGG_LAST_PULSE = "egg_last_pulse"

    /** ¿Toca ya un palpito nuevo, segun la fase [stage]? (fase 0 = nunca; fase final = siempre). */
    fun eggPulseDue(context: Context, stage: Int): Boolean {
        val period = EGG_PULSE_PERIOD_MS[stage.coerceIn(0, EGG_PULSE_PERIOD_MS.size - 1)]
        if (period == Long.MAX_VALUE) return false
        if (period < 0) return true
        val last = prefs(context).getLong(KEY_EGG_LAST_PULSE, 0L)
        return System.currentTimeMillis() - last >= period
    }
    fun markEggPulseStarted(context: Context) =
        prefs(context).edit().putLong(KEY_EGG_LAST_PULSE, System.currentTimeMillis()).apply()

    /** Cuantas tiradas reales tocan ya del ciclo de huevos (poner uno nuevo o avanzar de fase) -
     *  llamar UNA vez por cada checkAlerts, y repetir el cuerpo de "una tirada"
     *  (maybeHatchEgg/maybeLayEgg) tantas veces como devuelva - si no, cualquier refresco del
     *  widget (no solo el tick real de 15min) colaria una tirada de mas.
     *
     *  Normalmente devuelve 0 o 1. Puede devolver MAS de 1 si el propio chequeo periodico se
     *  retraso mas de EGG_ROLL_INTERVAL_MS de verdad (Doze/ahorro de bateria reteniendo la alarma
     *  varias horas - caso real detectado por el usuario: un hueco de 2h20m sin ninguna tirada en
     *  el log de diagnostico). ANTES, si el chequeo llegaba tarde, SIEMPRE colapsaba en una sola
     *  tirada sin importar cuanto tiempo real hubiera pasado, perdiendo las demas en silencio -
     *  ahora se recuperan todas, como si el chequeo hubiese llegado puntual cada vez. Tope
     *  MAX_EGG_CATCHUP_ROLLS como salvaguarda (reloj de sistema mal puesto, etc).
     *
     *  Consume el turno al llamarla (avanza KEY_EGG_LAST_ROLL en bloques exactos de
     *  EGG_ROLL_INTERVAL_MS, no a "ahora", para no perder el sobrante ni acumular deriva en el
     *  calendario real de tiradas) aunque luego las tiradas de verdad fallen. */
    fun eggRollsDue(context: Context): Int {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_EGG_LAST_ROLL, 0L)
        val elapsed = now - last
        if (elapsed < EGG_ROLL_INTERVAL_MS) return 0
        val missed = (elapsed / EGG_ROLL_INTERVAL_MS).coerceAtMost(MAX_EGG_CATCHUP_ROLLS.toLong()).toInt()
        p.edit().putLong(KEY_EGG_LAST_ROLL, last + missed * EGG_ROLL_INTERVAL_MS).apply()
        return missed
    }

    /** Fase de rotura REAL (0..EGG_CRACK_STAGES-1) - ya NO se estima por tiempo transcurrido:
     *  se guarda tal cual, y solo avanza de una en una cuando `maybeHatchEgg` tira con exito
     *  (como un yunque: cada tick hay una probabilidad de pasar a la siguiente fase, o quedarse
     *  igual - nunca se salta ninguna). Solo se puede eclosionar de verdad estando YA en la
     *  ultima fase. */
    fun eggCrackStage(context: Context): Int {
        val override = prefs(context).getInt(KEY_EGG_DEBUG_STAGE, -1)
        if (override >= 0) return override.coerceIn(0, EGG_CRACK_STAGES - 1)
        return prefs(context).getInt(KEY_EGG_STAGE, 0).coerceIn(0, EGG_CRACK_STAGES - 1)
    }

    /** SOLO PARA EL MENU DEBUG: fuerza eggCrackStage a [stage] hasta que se quite el override
     *  (ver debugClearEggStageOverride) - no toca la fase REAL guardada, asi que al quitarlo se
     *  vuelve a ver la de verdad tal cual iba. */
    fun debugSetEggStage(context: Context, stage: Int) =
        prefs(context).edit().putInt(KEY_EGG_DEBUG_STAGE, stage.coerceIn(0, EGG_CRACK_STAGES - 1)).apply()

    fun debugClearEggStageOverride(context: Context) =
        prefs(context).edit().remove(KEY_EGG_DEBUG_STAGE).apply()

    fun eggStageOverrideActive(context: Context): Boolean = prefs(context).getInt(KEY_EGG_DEBUG_STAGE, -1) >= 0

    /** Busca la forma BASE (raiz) de la cadena evolutiva de [name], subiendo por evolvesFrom. */
    private fun baseFormOf(context: Context, name: String): String {
        var cur = name
        while (true) {
            val prev = evolutionInfo(context, cur)?.evolvesFrom ?: return cur
            cur = prev
        }
    }

    /** Si el Pokemon ACTIVO esta en su ultima fase, tiene la puesta activada y no hay ya un
     *  huevo pendiente: tira, con probabilidad creciente segun su nivel, si pone uno ahora.
     *  Devuelve true si se ha puesto un huevo nuevo (para poder notificar). */
    fun maybeLayEgg(context: Context, stats: Stats): Boolean {
        if (hasActiveEgg(context)) return false
        val name = currentPokemon(context)
        if (!isFinalStage(context, name)) return false
        if (!eggLayingEnabled(context, name)) return false
        val level = levelOf(context, stats.xp, name)
        val prob = (EGG_LAY_BASE_PROB + EGG_LAY_LEVEL_SCALE * level).coerceAtMost(EGG_LAY_PROB_MAX)
        if (Random.nextFloat() >= prob) return false
        // Si el fondo puesto AHORA le pega al tipo del PADRE activo, mas probabilidad de que nazca
        // shiny - x1 o x2 segun beneficie o no (ver bgMatchMultiplier). Antes se miraba el tipo de
        // la CRIA (baseFormOf) en vez del padre - decision revisada: no tenia sentido narrativo que
        // la suerte dependiera de un tipo que aun no ha nacido, y ademas era inconsistente con el
        // bono de XP del mismo fondo, que SIEMPRE mira al padre activo (ver xpMultiplier, tryApplyAction/
        // loadWithDecay). Solo cambia para lineas evolutivas que GANAN un tipo al evolucionar (ej.
        // Charmander->Charizard): ahora un fondo de ese tipo ganado SI cuenta, aunque la cria (forma
        // base) no lo tenga todavia.
        val babySpecies = baseFormOf(context, name)
        val shinyChance = EGG_SHINY_CHANCE * bgMatchMultiplier(context, name)
        val e = prefs(context).edit()
            .putBoolean(KEY_EGG_ACTIVE, true)
            .putString(KEY_EGG_PARENT, name)
            .putBoolean(KEY_EGG_PARENT_SHINY, isActiveShiny(context))
            .putLong(KEY_EGG_LAID_AT, System.currentTimeMillis())
            .putBoolean(KEY_EGG_SHINY, Random.nextFloat() < shinyChance)
            .putBoolean(KEY_EGG_READY, false)
            .putInt(KEY_EGG_STAGE, 0)
            .remove(KEY_EGG_LAST_PULSE)
        // La forma de Tatsugiri (o de cualquier especie con forma DECORATIVA - Furfrou, Basculin,
        // Squawkabilly, Pumpkaboo) se sortea YA al poner el huevo (igual que el shiny): asi el
        // dialogo de revelacion (eggHatchSpecies, ANTES de decidir quedarselo) y el hatch real
        // (keepEggHatch) usan siempre la misma, nunca se sortea dos veces por separado.
        if (babySpecies == "tatsugiri") e.putString(KEY_EGG_TATSUGIRI_FORM, rollTatsugiriForm(context))
        else e.remove(KEY_EGG_TATSUGIRI_FORM)
        if (decorativeFormsOptions(babySpecies).isNotEmpty()) e.putString(KEY_EGG_DECORATIVE_FORM, rollDecorativeForm(context, babySpecies))
        else e.remove(KEY_EGG_DECORATIVE_FORM)
        // Formas regionales (ver REGIONAL_HATCH_VARIANT): 50/50 de nacer como la variante regional
        // en vez de la normal - sorteado YA aqui (igual que el shiny) para que la revelacion antes
        // de decidir quedarselo (eggHatchSpecies) coincida siempre con lo que de verdad se obtiene.
        REGIONAL_HATCH_VARIANT[babySpecies]?.let { variant ->
            e.putString(KEY_EGG_REGIONAL_SPECIES, if (Random.nextFloat() < 0.5f) variant else babySpecies)
        } ?: e.remove(KEY_EGG_REGIONAL_SPECIES)
        e.commit()
        return true
    }

    /** Si hay un huevo pendiente y aun no esta listo: UNA tirada por tick, como un yunque - si
     *  sale bien, pasa a la fase siguiente (o, si ya estaba en la ULTIMA fase, eclosiona de
     *  verdad); si sale mal, se queda igual. Nunca se salta ninguna fase ni eclosiona antes de
     *  haberlas pasado todas. Devuelve true solo cuando acaba de quedar listo (para notificar) -
     *  avanzar de fase no notifica nada, solo se refleja en el icono/palpito del huevo. */
    fun maybeHatchEgg(context: Context): Boolean {
        if (!hasActiveEgg(context) || isEggReady(context)) return false
        if (Random.nextFloat() >= EGG_STAGE_ADVANCE_PROB) return false
        val stage = eggCrackStage(context)
        if (stage >= EGG_CRACK_STAGES - 1) {
            prefs(context).edit().putBoolean(KEY_EGG_READY, true).commit()
            awardEggHatchXpToParent(context)
            return true
        }
        prefs(context).edit().putInt(KEY_EGG_STAGE, stage + 1).commit()
        return false
    }

    /** Aplica EGG_HATCH_XP_BONUS a la xp guardada del PADRE (la especie que puso el huevo), sea
     *  shiny o no - el simple hecho de eclosionar ya da el bono, el shiny SOLO decide a que
     *  individuo (normal o shiny, ver slot()) se le suma, nunca si se le suma. KEY_EGG_PARENT_SHINY
     *  dice cual de los dos era el padre en el momento de poner el huevo, pero por si acaso no
     *  coincidiera (ej. huevo puesto antes de guardar este dato) se prueba tambien con el otro
     *  antes de rendirse - asi nunca se pierde el bono en silencio por una etiqueta shiny mal
     *  puesta. No-op solo si de verdad no existe ningun individuo de esa especie (no deberia
     *  pasar: el padre no se borra al poner un huevo, solo deja de ser el activo si luego se
     *  acepta la cria). */
    private fun awardEggHatchXpToParent(context: Context) {
        val parent = eggParent(context) ?: return
        val p = prefs(context)
        val base = parent.lowercase()
        val recordedShiny = p.getBoolean(KEY_EGG_PARENT_SHINY, false)
        val n = listOf(slot(base, recordedShiny), slot(base, !recordedShiny))
            .firstOrNull { p.contains(k(it, KEY_XP)) } ?: return
        val xp = try {
            p.getFloat(k(n, KEY_XP), 0f)
        } catch (e: ClassCastException) {
            p.getInt(k(n, KEY_XP), 0).toFloat()
        }
        p.edit().putFloat(k(n, KEY_XP), xp + EGG_HATCH_XP_BONUS).apply()
    }

    /** Soltar (descartar) el huevo listo: NO se queda nada. Consume el huevo entero - hay que
     *  esperar desde cero a que se ponga otro, sin reintento instantaneo. */
    fun releaseEgg(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_EGG_ACTIVE, false)
            .putBoolean(KEY_EGG_READY, false)
            .remove(KEY_EGG_PARENT).remove(KEY_EGG_PARENT_SHINY).remove(KEY_EGG_LAID_AT).remove(KEY_EGG_SHINY)
            .remove(KEY_EGG_STAGE).remove(KEY_EGG_LAST_PULSE).remove(KEY_EGG_TATSUGIRI_FORM)
            .remove(KEY_EGG_DECORATIVE_FORM).remove(KEY_EGG_REGIONAL_SPECIES)
            .commit()
    }

    /** Quedarse con la cria: pasa a ser el Pokemon activo desde nivel 1 (setPokemon ya lo deja
     *  asi de gratis) y consume el huevo. Devuelve el nombre de la especie con la que empiezas.
     *  El shiny se fija explicitamente para [base] (la especie de la cria), no para la que
     *  estuviera activa hasta ahora (ver isShiny/setShiny, ya por especie). */
    fun keepEggHatch(context: Context): String? {
        val parent = eggParent(context) ?: return null
        val shiny = eggIsShiny(context)
        val rawBase = baseFormOf(context, parent)
        // Forma regional (ver REGIONAL_HATCH_VARIANT): ya sorteada al poner el huevo - de aqui en
        // adelante la especie de VERDAD de esta cria es esta (para todo: shiny, nivel, sprite...).
        val base = eggHatchSpecies(context) ?: rawBase
        val id = evolutionInfo(context, base)?.id ?: -1
        if (rawBase == "tatsugiri") {
            setTatsugiriForm(context, prefs(context).getString(KEY_EGG_TATSUGIRI_FORM, null) ?: rollTatsugiriForm(context))
        }
        if (decorativeFormsOptions(rawBase).isNotEmpty()) {
            setDecorativeForm(context, rawBase, prefs(context).getString(KEY_EGG_DECORATIVE_FORM, null) ?: rollDecorativeForm(context, rawBase))
        }
        // El shiny va directo a setPokemon (no a un setShiny() aparte): asi crea/activa la slot
        // que le toca (normal o shiny, ver slot()) sin pisar la otra variante de [base] si ya
        // existiera - bug real de esta noche (un huevo de una especie ya tenida sobrescribia en
        // silencio el individuo anterior).
        setPokemon(context, base, id, shiny)
        releaseEgg(context)
        return base
    }

    // ==================== TIPOS Y FONDOS SUGERIDOS ====================
    // Tipo de cada especie: generado UNA vez en el PC (gen_types.py, desde PokeAPI - 18 llamadas a
    // /type/{nombre} e invertidas especie->tipos, con fallback a /pokemon-species para las formas
    // multiples que no aparecen con el nombre pelado) y empaquetado en assets/types.json (nombre
    // -> lista de 1-2 tipos). BG_FOR_TYPE da, para cada tipo, hasta 2 fondos YA EXISTENTES que le
    // pegan - unos cuantos son un match flojo a proposito (psiquico, siniestro, volador... no
    // tienen paisaje propio entre los 15 fondos actuales), pero el usuario prefirio eso a dejarlos
    // sin ninguna sugerencia.
    @Volatile private var typesTable: Map<String, List<String>>? = null

    private fun loadTypesTable(context: Context): Map<String, List<String>> {
        typesTable?.let { return it }
        val json = context.assets.open("types.json").bufferedReader().use { it.readText() }
        val obj = org.json.JSONObject(json)
        val map = HashMap<String, List<String>>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val arr = obj.getJSONArray(name)
            map[name] = (0 until arr.length()).map { arr.getString(it) }
        }
        typesTable = map
        return map
    }

    /** Tipo(s) de [name]. Caso especial Rotom: sus formas de aparato SI cambian de tipo de verdad
     *  (ver ROTOM) - aqui se consulta la tabla bajo "rotom-<forma>" en vez de bajo "rotom" cuando
     *  hay una forma de aparato activa, para que el emparejamiento de fondo (bgMatchMultiplier)
     *  tambien la tenga en cuenta. */
    fun typesOf(context: Context, name: String, shiny: Boolean = isActiveShiny(context)): List<String> {
        val n = name.lowercase()
        when (n) {
            "rotom" -> rotomForm(context, shiny).takeIf { it != "rotom" }?.let { form ->
                return loadTypesTable(context)["rotom-$form"] ?: emptyList()
            }
            "oricorio" -> unlockableForm(context, "oricorio", shiny).takeIf { it != "baile" }?.let { form ->
                return loadTypesTable(context)["oricorio-$form"] ?: emptyList()
            }
            "ogerpon" -> unlockableForm(context, "ogerpon", shiny).takeIf { it != "teal" }?.let { form ->
                return loadTypesTable(context)["ogerpon-$form"] ?: emptyList()
            }
            "wormadam" -> return wormadamTypesFor(prefs(context).getString(KEY_WORMADAM_CLOAK, "plant") ?: "plant")
            "darmanitan" -> return darmanitanTypesFor(rawStats(context, "darmanitan", shiny)?.health ?: 100f)
            "meloetta" -> unlockableForm(context, "meloetta", shiny).takeIf { it != "aria" }?.let { form ->
                return loadTypesTable(context)["meloetta-$form"] ?: emptyList()
            }
            "tauros" -> return loadTypesTable(context)["tauros-${decorativeForm(context, "tauros")}"] ?: emptyList()
            "morpeko" -> if (!morpekoIsHangry()) return loadTypesTable(context)["morpeko-fullbelly"] ?: emptyList()
            "arceus", "silvally" -> decorativeForm(context, n).takeIf { it != "normal" }?.let { form ->
                return loadTypesTable(context)["$n-$form"] ?: emptyList()
            }
        }
        return loadTypesTable(context)[n] ?: emptyList()
    }

    // ==================== PERSONALIDAD SEGUN ESTADISTICAS BASE ====================
    // "Personalidad" = un multiplicador FIJO por especie (no aleatorio, no cambia nunca) sobre el
    // decaimiento YA calibrado (HEALTH_DAY/HAPPY_DAY/HYGIENE_DAY etc. arriba) - nunca lo
    // sustituye, solo lo modula: vida segun su HP (mas HP, decae mas despacio), felicidad segun
    // su Velocidad (mas rapido/energico, se aburre antes) e higiene segun su Defensa (mas
    // corpulento, se ensucia antes). Datos reales de PokeAPI (base_stats.json, generado una vez).
    //
    // Topado entre x0.5 y x1.7 (clampPersonality) - comprobado con las 1025 especies reales antes
    // de fijar este rango: SIN tope, Shedinja (1 HP) decaeria en vida 70 VECES mas rapido de lo
    // normal (practicamente instantaneo) - con el tope, el caso mas dificil de verdad (Munchlax)
    // solo llega a ~1.7x en las 3 barras a la vez, y el mas facil (Onix) a mitad de ritmo en 2 de
    // las 3 - nunca se rompe del todo pase lo que pase. Bajado de x2 a x1.7 (a la vez que
    // HAPPY_DAY/NIGHT, ver arriba) tras ver en el Simulador de Economia que con el tope viejo
    // Iron Bundle (HP56/Vel136/Def114) pedia ~10.5 caricias/dia - con los dos ajustes juntos el
    // peor caso real ronda ~7/dia. Este rango de tope NO cambia con lo de abajo (misma seguridad
    // ya validada, solo cambia CONTRA QUE se compara cada especie).
    private const val PERSONALITY_MULT_MIN = 0.5f
    private const val PERSONALITY_MULT_MAX = 1.7f

    // SEGUNDA VUELTA (pedido explicito del usuario: "una primera etapa evolutiva no actua igual
    // que una final"): antes se comparaba TODA especie contra una unica referencia global (70)
    // para las 3 estadisticas - verificado con las 1083 especies reales que esto hacia que el 57%
    // de las especies BASE (crias de verdad, sin contar formas de una sola etapa como Ditto/
    // Tauros/legendarios - esas cuentan como "final", ver isFinalStage) compartieran EXACTAMENTE
    // el mismo patron cualitativo (vida alta, felicidad y aseo bajos) - porque los Pokemon bebe
    // tienen las 3 estadisticas bajas A LA VEZ frente al resto de la Pokedex, asi que todos caian
    // del mismo lado sin importar la especie concreta. Con una referencia distinta por ETAPA
    // evolutiva (promedio real de hp/velocidad/defensa dentro de cada etapa) ese porcentaje baja a
    // 21.8% - ahora cada especie se compara contra sus iguales (otras crias, u otros adultos),
    // asi que lo que sobresale es el perfil REAL de esa especie (Onix vs. Bulbasaur ya no son
    // iguales solo por ser ambos "etapa base"), no simplemente el hecho de ser joven o adulta.
    private const val PERSONALITY_REF_HP_BASE = 50f
    private const val PERSONALITY_REF_SPEED_BASE = 51f
    private const val PERSONALITY_REF_DEF_BASE = 52f
    private const val PERSONALITY_REF_HP_MIDDLE = 65f
    private const val PERSONALITY_REF_SPEED_MIDDLE = 60f
    private const val PERSONALITY_REF_DEF_MIDDLE = 68f
    private const val PERSONALITY_REF_HP_FINAL = 83f
    private const val PERSONALITY_REF_SPEED_FINAL = 79f
    private const val PERSONALITY_REF_DEF_FINAL = 86f

    @Volatile private var baseStatsTable: Map<String, Triple<Int, Int, Int>>? = null

    private fun loadBaseStatsTable(context: Context): Map<String, Triple<Int, Int, Int>> {
        baseStatsTable?.let { return it }
        val json = context.assets.open("base_stats.json").bufferedReader().use { it.readText() }
        val obj = org.json.JSONObject(json)
        val map = HashMap<String, Triple<Int, Int, Int>>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val s = obj.getJSONObject(name)
            map[name] = Triple(s.getInt("hp"), s.getInt("speed"), s.getInt("defense"))
        }
        baseStatsTable = map
        return map
    }

    private fun clampPersonality(v: Float): Float = v.coerceIn(PERSONALITY_MULT_MIN, PERSONALITY_MULT_MAX)

    /** Etapa evolutiva de [name] para elegir la referencia de personalidad: 2=final (sin mas
     *  evoluciones - MISMO criterio que isFinalStage, incluye especies de una sola forma como
     *  Ditto/Tauros/legendarios, y el caso "sin datos"), 0=base (evolvesFrom null pero SI
     *  evoluciona mas - cria de verdad), 1=middle (el resto). */
    private fun personalityStage(context: Context, name: String): Int {
        if (isFinalStage(context, name)) return 2
        return if (evolutionInfo(context, name)?.evolvesFrom == null) 0 else 1
    }

    // TERCERA VUELTA (pedido explicito del usuario, comparacion con personas: "los niños
    // necesitan comer menos y mas caricias, cuando eres joven/mayor necesitas mas comida y menos
    // caricias"): lo de arriba (comparar contra el promedio de la propia etapa) deja la etapa
    // "neutra" por diseño - una especie con stats exactamente en el promedio de su etapa sale
    // 1.0/1.0/1.0 pase lo que pase, asi que NO empuja en ninguna direccion consistente segun la
    // edad, solo diferencia especies dentro de su misma etapa. Este sesgo añade ESO que faltaba:
    // un empujon FIJO, igual para todas las especies de una etapa, que sube "comer" y baja
    // "mimar" segun se crece (bebe come menos/pide mas mimo; joven Y adulto comen mas/piden
    // menos mimo, sin volver a bajar en la vejez - pedido explicito). Verificado con las 1083
    // especies reales que el patron queda consistente en cualquier cadena (comer sube y mimar
    // baja SIEMPRE de base a final) y que el peor caso combinado se queda en ~7.1/dia (Onix),
    // pegado al ~7/dia ya validado antes - no reabre el problema de exceso de caricias/dia.
    // No toca higiene (ver aparte, ritmo base ya pedido explicitamente por el usuario en otra
    // ronda: comer 3x/lavar 1x, no es lo mismo que esto).
    private const val AGE_BIAS_FEED_BASE = 0.80f;   private const val AGE_BIAS_PET_BASE = 1.25f
    private const val AGE_BIAS_FEED_MIDDLE = 1.10f; private const val AGE_BIAS_PET_MIDDLE = 0.90f
    private const val AGE_BIAS_FEED_FINAL = 1.15f;  private const val AGE_BIAS_PET_FINAL = 0.85f

    /** Multiplicadores (vida, felicidad, higiene) para [name] segun sus estadisticas base reales,
     *  comparadas contra el promedio real de su PROPIA etapa evolutiva (ver personalityStage), y
     *  con el sesgo de edad de arriba ya aplicado a vida/felicidad - 1f,1f,1f si no hay datos
     *  para esa especie (no deberia pasar, pero por si acaso). */
    fun personalityMultipliers(context: Context, name: String): Triple<Float, Float, Float> {
        val stats = loadBaseStatsTable(context)[name.lowercase()] ?: return Triple(1f, 1f, 1f)
        val (hp, speed, defense) = stats
        val stage = personalityStage(context, name)
        val (refHp, refSpeed, refDef) = when (stage) {
            0 -> Triple(PERSONALITY_REF_HP_BASE, PERSONALITY_REF_SPEED_BASE, PERSONALITY_REF_DEF_BASE)
            1 -> Triple(PERSONALITY_REF_HP_MIDDLE, PERSONALITY_REF_SPEED_MIDDLE, PERSONALITY_REF_DEF_MIDDLE)
            else -> Triple(PERSONALITY_REF_HP_FINAL, PERSONALITY_REF_SPEED_FINAL, PERSONALITY_REF_DEF_FINAL)
        }
        val (feedBias, petBias) = when (stage) {
            0 -> AGE_BIAS_FEED_BASE to AGE_BIAS_PET_BASE
            1 -> AGE_BIAS_FEED_MIDDLE to AGE_BIAS_PET_MIDDLE
            else -> AGE_BIAS_FEED_FINAL to AGE_BIAS_PET_FINAL
        }
        val mHealth = clampPersonality((refHp / hp) * feedBias)
        val mHappy = clampPersonality((speed / refSpeed) * petBias)
        val mHygiene = clampPersonality(defense / refDef)
        return Triple(mHealth, mHappy, mHygiene)
    }

    // ==================== MEGAEVOLUCION (piloto: solo Absol) ====================
    // Puramente DECORATIVA (pedido explicito del usuario): solo cambia que sprite se enseña un
    // rato, NUNCA toca xp/nivel/decaimiento/personalidad - la especie "de verdad" (para todo lo
    // demas) sigue siendo la base en todo momento. Se desbloquea con nivel (nivel 40, igual que
    // el resto de cosas del juego se gatea por nivel - no hace falta inventar un sistema de
    // objetos aparte para esto), y si tiene varias Megas (ej. Absol normal/Z) se desbloquean
    // TODAS a la vez al llegar a ese nivel, sin diferencia de coste entre ellas: en los juegos
    // reales ninguna Mega de una misma especie es "mas dificil" que otra, serian alternativas del
    // mismo peso, asi que una regla que encareciera la segunda seria inventada sin ninguna base.
    data class MegaOption(val name: String, val label: String, val types: List<String>)

    // Gigantamax se desbloquea antes pero dura menos (pensado como el efecto rapido de un
    // turno que es en los juegos reales); Megaevolucion "de verdad" (y el resto de formas que
    // en la UI se enseñan bajo ese mismo nombre - Primigenia/Origen/Corona/Formas Trio, ver
    // unlockLevelForMegaOption) se desbloquea mas tarde pero dura mas, igual que hasta ahora.
    // Pedido explicito del usuario tras probar el juego un tiempo con un solo nivel para todo.
    const val MEGA_UNLOCK_LEVEL = 50
    const val GIGANTAMAX_UNLOCK_LEVEL = 40
    private const val MEGA_DURATION_MS = 8 * 60 * 60 * 1000L   // 8 horas activo
    private const val MEGA_COOLDOWN_MS = 12 * 60 * 60 * 1000L  // 12 horas hasta poder repetir
    private const val GMAX_DURATION_MS = 4 * 60 * 60 * 1000L   // 4 horas activo
    private const val GMAX_COOLDOWN_MS = 8 * 60 * 60 * 1000L   // 8 horas hasta poder repetir
    private const val KEY_MEGA_ACTIVE_NAME = "mega_active_name"
    private const val KEY_MEGA_ACTIVE_UNTIL = "mega_active_until"
    private const val KEY_MEGA_LAST_USED_AT = "mega_last_used_at"

    /** Nivel a partir del cual se puede activar la opcion [optionName]: GIGANTAMAX_UNLOCK_LEVEL
     *  para las que acaban en "-gmax", MEGA_UNLOCK_LEVEL para el resto (incluye Megaevolucion
     *  de verdad y las demas formas que la UI enseña bajo ese mismo nombre). */
    fun unlockLevelForMegaOption(optionName: String): Int =
        if (optionName.endsWith("-gmax")) GIGANTAMAX_UNLOCK_LEVEL else MEGA_UNLOCK_LEVEL

    private fun durationMsForMegaOption(optionName: String): Long =
        if (optionName.endsWith("-gmax")) GMAX_DURATION_MS else MEGA_DURATION_MS

    private fun cooldownMsForMegaOption(optionName: String): Long =
        if (optionName.endsWith("-gmax")) GMAX_COOLDOWN_MS else MEGA_COOLDOWN_MS

    @Volatile private var megaTable: Map<String, List<MegaOption>>? = null

    private fun loadMegaTable(context: Context): Map<String, List<MegaOption>> {
        megaTable?.let { return it }
        val json = context.assets.open("megas.json").bufferedReader().use { it.readText() }
        val obj = org.json.JSONObject(json)
        val map = HashMap<String, List<MegaOption>>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val species = keys.next()
            val arr = obj.getJSONArray(species)
            val opts = ArrayList<MegaOption>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val typesArr = o.getJSONArray("types")
                val types = (0 until typesArr.length()).map { typesArr.getString(it) }
                opts.add(MegaOption(o.getString("name"), o.getString("label"), types))
            }
            map[species] = opts
        }
        megaTable = map
        return map
    }

    /** Opciones de Megaevolucion de [name] (vacia si esa especie no tiene ninguna todavia).
     *  Caso especial Tatsugiri: sus 3 Megas estan en la tabla bajo "tatsugiri-<forma>" (una por
     *  forma) - aqui solo se devuelve la de la forma ACTUAL, nunca las otras 2 (no tiene sentido
     *  Megaevolucionar a la Mega de una forma que no es la que tienes ahora mismo). */
    fun megaOptionsFor(context: Context, name: String): List<MegaOption> {
        val n = name.lowercase()
        if (n == "tatsugiri") return loadMegaTable(context)["tatsugiri-${tatsugiriForm(context)}"] ?: emptyList()
        return loadMegaTable(context)[n] ?: emptyList()
    }

    /** Nombre de sprite a usar para TODO lo visual (widget, ficha, Pokedex): el de la
     *  Megaevolucion si hay una activa ahora mismo; si no, para Tatsugiri/Zygarde (sus formas no
     *  son Megas, ver TATSUGIRI/ZYGARDE) el de su forma actual; para el resto, el de la propia
     *  especie. */
    fun displaySpriteName(context: Context, name: String, shiny: Boolean = isActiveShiny(context)): String {
        activeMegaSpriteName(context, name)?.let { return it }
        val n = name.lowercase()
        val result = when (n) {
            "tatsugiri" -> tatsugiriSpriteKey(tatsugiriForm(context))
            "zygarde" -> zygardeSpriteKey(zygardeForme(context))
            "rotom" -> rotomSpriteKey(rotomForm(context, shiny))
            "deerling" -> seasonalSpriteKey("deerling", currentSeason())
            "sawsbuck" -> seasonalSpriteKey("sawsbuck", currentSeason())
            "oricorio", "ogerpon", "genesect", "meloetta" -> unlockableSpriteKey(n, unlockableForm(context, n, shiny))
            "furfrou", "basculin", "squawkabilly", "castform", "deoxys", "unown", "arceus", "silvally", "vivillon", "pikachu", "alcremie",
            "gimmighoul" -> decorativeSpriteKey(n, decorativeForm(context, n))
            "tauros" -> taurosSpriteKey(decorativeForm(context, "tauros"))
            "pumpkaboo" -> decorativeSpriteKey("pumpkaboo", decorativeForm(context, "pumpkaboo"))
            "gourgeist" -> gourgeistSpriteKey(decorativeForm(context, "pumpkaboo"))
            "shellos" -> decorativeSpriteKey("shellos", decorativeForm(context, "shellos"))
            "gastrodon" -> "gastrodon" + (if (decorativeForm(context, "shellos") == "east") "-east" else "")
            "flabebe" -> decorativeSpriteKey("flabebe", decorativeForm(context, "flabebe"))
            "floette" -> "floette" + (decorativeForm(context, "flabebe").takeIf { it != "red" }?.let { "-$it" } ?: "")
            "florges" -> "florges" + (decorativeForm(context, "flabebe").takeIf { it != "red" }?.let { "-$it" } ?: "")
            "burmy" -> burmySpriteKey(burmyCloakForBg(currentBg(context)))
            "wormadam" -> wormadamSpriteKey(prefs(context).getString(KEY_WORMADAM_CLOAK, "plant") ?: "plant")
            "darmanitan" -> darmanitanSpriteKey(rawStats(context, "darmanitan", shiny)?.health ?: 100f)
            "cramorant" -> cramorantSpriteKey(cramorantState(context))
            "keldeo" -> keldeoSpriteKey(keldeoIsResolute(context))
            "cherrim" -> cherrimSpriteKey(currentBg(context))
            "mimikyu" -> mimikyuSpriteKey(rawStats(context, "mimikyu", shiny)?.health ?: 100f)
            "eiscue" -> eiscueSpriteKey(rawStats(context, "eiscue", shiny)?.health ?: 100f)
            "wishiwashi" -> wishiwashiSpriteKey(levelForPokemon(context, "wishiwashi", shiny) ?: 1, rawStats(context, "wishiwashi", shiny)?.health ?: 100f)
            "morpeko" -> morpekoSpriteKey(morpekoIsHangry())
            "palafin" -> palafinSpriteKey(palafinIsHero(context))
            "aegislash" -> aegislashSpriteKey(aegislashStance(context))
            "terapagos" -> terapagosSpriteKey(terapagosIsTerastal(context))
            "dudunsparce" -> dudunsparceSpriteKey(dudunsparceIsThree(context))
            "maushold" -> mausholdSpriteKey(mausholdIsThree(context))
            "minior" -> miniorSpriteKey(decorativeForm(context, "minior"), rawStats(context, "minior", shiny)?.health ?: 100f)
            "solgaleo" -> solgaleoSpriteKey(isRadiantPhaseActive(context, "solgaleo"))
            "lunala" -> lunalaSpriteKey(isRadiantPhaseActive(context, "lunala"))
            in GENDER_SPECIES -> genderSpriteKey(n, genderOf(context, n))
            else -> name
        }
        // Registro de "formas ya vistas de verdad" (ver mechanicShowcase/MECHANIC_TRACKED_SPECIES):
        // se marca cada vez que se calcula el sprite de una de estas especies YA CONSEGUIDA, sea
        // cual sea el resultado, para que el registro no se pierda ninguna que de verdad haya
        // pasado. OJO: displaySpriteName tambien se llama para especies SIN conseguir (para
        // pintar su sprite en gris en la rejilla/cadena evolutiva) - sin este isUnlocked, con solo
        // pasar el dedo por la rejilla ya se marcaria su forma por defecto como "vista", aunque
        // nunca se hubiera tenido.
        if ((n in MECHANIC_TRACKED_SPECIES || n in GENDER_SPECIES) && isUnlocked(context, n)) {
            markMechanicFormSeen(context, n, result)
        }
        return result
    }

    /** Si hay una Megaevolucion activa AHORA MISMO para [name] (dentro de las 4h desde que se
     *  activo), su nombre de sprite (ej. "absol-mega") - null si no hay ninguna o ya caduco. */
    fun activeMegaSpriteName(context: Context, name: String): String? {
        val n = name.lowercase()
        val p = prefs(context)
        val until = p.getLong(k(n, KEY_MEGA_ACTIVE_UNTIL), 0L)
        if (System.currentTimeMillis() >= until) return null
        return p.getString(k(n, KEY_MEGA_ACTIVE_NAME), null)
    }

    /** Milisegundos que quedan de Megaevolucion activa (0 si no hay ninguna). */
    fun megaActiveRemainingMs(context: Context, name: String): Long {
        val until = prefs(context).getLong(k(name.lowercase(), KEY_MEGA_ACTIVE_UNTIL), 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Milisegundos que quedan de espera hasta poder volver a Megaevolucionar/Gigantamaxizar (0
     *  si ya se puede; cuenta desde la ULTIMA vez que se activo, no desde que termino). El
     *  enfriamiento depende de CUAL opcion fue esa ultima vez (KEY_MEGA_ACTIVE_NAME - Gigantamax
     *  se refresca antes que Megaevolucion, ver cooldownMsForMegaOption). */
    fun megaCooldownRemainingMs(context: Context, name: String): Long {
        val n = name.lowercase()
        val p = prefs(context)
        val lastUsed = p.getLong(k(n, KEY_MEGA_LAST_USED_AT), 0L)
        if (lastUsed == 0L) return 0L
        val lastOption = p.getString(k(n, KEY_MEGA_ACTIVE_NAME), null) ?: return 0L
        val readyAt = lastUsed + cooldownMsForMegaOption(lastOption)
        return (readyAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Especie REAL distinta que hace falta tener conseguida para poder activar cada opcion de
     *  fusion (verificado: Kyurem con Reshiram/Zekrom via DNA Splicers, Calyrex con Glastrier/
     *  Spectrier via Riendas de la Unidad, Necrozma con Solgaleo/Lunala via N-Solarizer/
     *  N-Lunarizer - Necrozma Ultra pide las 2 a la vez). A diferencia del resto de Megas (que
     *  solo piden nivel), estas NO aparecen como opcion si no se tiene la otra especie - ver
     *  megaOptionsAvailable. */
    private val FUSION_PARTNERS: Map<String, List<String>> = mapOf(
        "kyurem-white" to listOf("reshiram"), "kyurem-black" to listOf("zekrom"),
        "calyrex-ice" to listOf("glastrier"), "calyrex-shadow" to listOf("spectrier"),
        "necrozma-duskmane" to listOf("solgaleo"), "necrozma-dawnwings" to listOf("lunala"),
        "necrozma-ultra" to listOf("solgaleo", "lunala"),
    )

    /** Opciones de Megaevolucion de [name] que YA se pueden activar ahora mismo: para las de
     *  FUSION_PARTNERS, ademas hace falta tener conseguida esa otra especie (asi, con solo
     *  Reshiram conseguido, Kyurem ofrece Blanco pero no Negro) - para el resto, igual que
     *  megaOptionsFor sin mas filtro. */
    fun megaOptionsAvailable(context: Context, name: String): List<MegaOption> =
        megaOptionsFor(context, name).filter { opt ->
            FUSION_PARTNERS[opt.name]?.all { isUnlocked(context, it) } ?: true
        }

    /** El OTRO Pokemon a mostrar en la animacion de fusion de [optionName] (ej. "kyurem-white" ->
     *  "reshiram"), o null si [optionName] no es una fusion (Mega/Gigantamax normales). Para
     *  "necrozma-ultra" (que pide las 2 a la vez) se queda solo con la primera - mostrar 3 sprites
     *  a la vez en la animacion es mas lio que aporta, pedido explicito del usuario era "los dos
     *  sprites juntandose", no exactamente cual de los dos ingredientes en ese caso concreto. */
    fun fusionPartnerSpeciesFor(optionName: String): String? = FUSION_PARTNERS[optionName]?.firstOrNull()

    /** ¿[name] esta ahora mismo "prestado" dentro de una Fusion activa de otra especie (ej.
     *  Reshiram mientras Kyurem esta fusionado en Blanco)? Pedido explicito del usuario: mientras
     *  dura la fusion no deberia poder seleccionarse por separado (en el juego real, se fusiona de
     *  verdad con el Impulsogen) - vuelve a estar disponible en cuanto esa fusion termine sola
     *  (MEGA_DURATION_MS) o se desactive. Recorre FUSION_PARTNERS al reves: por cada opcion de
     *  fusion que necesite a [name] como ingrediente, mira si la especie que fusiona (la parte
     *  antes del primer "-", ej. "kyurem-white" -> "kyurem") tiene activa justo esa opcion ahora. */
    fun isFusionDonorBusy(context: Context, name: String): Boolean {
        val n = name.lowercase()
        return FUSION_PARTNERS.any { (optionName, partners) ->
            n in partners && activeMegaSpriteName(context, optionName.substringBefore("-")) == optionName
        }
    }

    /** De megaOptionsAvailable, las que YA se pueden activar con este [level] - para Necrozma/
     *  Kyurem/Calyrex el nivel no cuenta (su gate real es tener conseguida la otra especie, ya
     *  aplicado en megaOptionsAvailable); para Terapagos, vacia hasta terapagosIsTerastal; para
     *  el resto, filtra cada opcion por su propio unlockLevelForMegaOption (asi una especie con
     *  Mega Y Gigantamax a la vez, ej. Charizard, puede tener una disponible y la otra no). */
    fun megaOptionsEligible(context: Context, name: String, level: Int): List<MegaOption> {
        val n = name.lowercase()
        val avail = megaOptionsAvailable(context, n)
        return when (n) {
            "necrozma", "kyurem", "calyrex" -> avail
            "terapagos" -> if (terapagosIsTerastal(context)) avail else emptyList()
            else -> avail.filter { level >= unlockLevelForMegaOption(it.name) }
        }
    }

    /** ¿Puede [name] (con este [level]) Megaevolucionar/Gigantamaxizar AHORA MISMO? Necesita
     *  tener alguna opcion ELEGIBLE (ver megaOptionsEligible), no estar ya transformado, y no
     *  seguir en el enfriamiento desde la ultima vez. */
    fun canMegaEvolve(context: Context, name: String, level: Int): Boolean {
        return megaOptionsEligible(context, name, level).isNotEmpty() &&
            activeMegaSpriteName(context, name) == null &&
            megaCooldownRemainingMs(context, name) == 0L
    }

    /** Activa la Megaevolucion/Gigantamax [optionName] (uno de los .name de megaOptionsFor) para
     *  [name] durante su duracion (durationMsForMegaOption - menos si es Gigantamax), y arranca
     *  su propio enfriamiento (cooldownMsForMegaOption) desde ahora. */
    fun activateMega(context: Context, name: String, optionName: String) {
        val n = name.lowercase()
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString(k(n, KEY_MEGA_ACTIVE_NAME), optionName)
            .putLong(k(n, KEY_MEGA_ACTIVE_UNTIL), now + durationMsForMegaOption(optionName))
            .putLong(k(n, KEY_MEGA_LAST_USED_AT), now)
            .commit()
        // Registro de "ya visto" (ver mechanicShowcase): para Necrozma/Kyurem/Calyrex, sus
        // formas de fusion solo se ven a color en la vitrina de mecanicas si de verdad se han
        // activado alguna vez - inofensivo para el resto de Megas normales (nadie lo consulta).
        markMechanicFormSeen(context, n, optionName)
    }

    /** SOLO PARA EL MENU DEBUG: desactiva la Megaevolucion/Gigantamax de [name] ahora mismo (si
     *  tenia alguna activa) y limpia tambien su enfriamiento, para poder volver a probar otra sin
     *  esperar. */
    fun debugDeactivateMega(context: Context, name: String) {
        val n = name.lowercase()
        prefs(context).edit()
            .putLong(k(n, KEY_MEGA_ACTIVE_UNTIL), 0L)
            .putLong(k(n, KEY_MEGA_LAST_USED_AT), 0L)
            .apply()
    }

    // ==================== ZYGARDE: forma 10%/50%/100% ====================
    // Caso especial (pedido explicito del usuario): el ascenso 10%->50%->100% NO es una
    // Megaevolucion (no es temporal, no usa el sistema de arriba) - es un ascenso PERMANENTE por
    // nivel, como una evolucion normal, pero sin crear una especie nueva (sigue contando como una
    // sola entrada "zygarde" en la Pokedex). Arranca en 10% y sube sola, sin vuelta atras, al
    // llegar a cada nivel - no hace falta ninguna eleccion del jugador. APARTE de esto, Zygarde
    // SI tiene su propia Megaevolucion real (megas.json, "zygarde" - sistema normal de arriba,
    // temporal e independiente de en que forma (10/50/100) este en cada momento).
    const val ZYGARDE_FORME_50_LEVEL = 20
    const val ZYGARDE_FORME_100_LEVEL = 40
    private const val KEY_ZYGARDE_FORME = "zygarde.forme"

    /** Forma actual de Zygarde: 10, 50 o 100. */
    fun zygardeForme(context: Context): Int = prefs(context).getInt(KEY_ZYGARDE_FORME, 10)

    /** Nombre de sprite (sin Mega) para la forma [forme] de Zygarde. "zygarde" (sin sufijo) ya
     *  existia de antes de este sistema y es la forma 50% - la mas usada en los juegos reales. */
    private fun zygardeSpriteKey(forme: Int): String = when (forme) {
        10 -> "zygarde-10"
        100 -> "zygarde-100"
        else -> "zygarde"
    }

    /** Si el nivel actual de Zygarde ya le toca subir de forma, la sube (de forma permanente) y
     *  devuelve true (para poder notificarlo) - no hace nada (y devuelve false) si no toca
     *  todavia, si ya esta en la forma maxima, o si la especie activa no es zygarde. Se llama
     *  desde loadWithDecay, igual que el resto de cosas ligadas al nivel. */
    private fun maybeAdvanceZygardeForme(context: Context, name: String, level: Int): Boolean {
        if (name.lowercase() != "zygarde") return false
        val current = zygardeForme(context)
        val next = when {
            current < 100 && level >= ZYGARDE_FORME_100_LEVEL -> 100
            current < 50 && level >= ZYGARDE_FORME_50_LEVEL -> 50
            else -> return false
        }
        prefs(context).edit().putInt(KEY_ZYGARDE_FORME, next).commit()
        return true
    }

    // ==================== TATSUGIRI: 3 formas (Curly/Droopy/Stretchy) ====================
    // Caso especial (pedido explicito del usuario): las 3 formas son puramente cosmeticas (mismos
    // stats/tipo/cadena - de hecho no evoluciona), igual que Zygarde sigue contando como una sola
    // especie en la Pokedex. La forma que te toca es ALEATORIA, pero SOLO se consigue una forma
    // nueva criando un huevo (isFinalStage ya es true para tatsugiri, no evoluciona) - nunca
    // eligiendo. Mientras falte alguna forma por tener, el huevo nunca repite una que ya tengas;
    // una vez tienes las 3, vuelve a ser aleatorio del todo entre las 3.
    private const val KEY_TATSUGIRI_FORM = "tatsugiri.form"
    private const val KEY_TATSUGIRI_FORMS_OWNED = "tatsugiri.forms_owned"
    private const val KEY_EGG_TATSUGIRI_FORM = "egg_tatsugiri_form"
    private const val KEY_EGG_DECORATIVE_FORM = "egg_decorative_form"
    private const val KEY_EGG_REGIONAL_SPECIES = "egg_regional_species"

    // ==================== FORMAS REGIONALES: 50/50 al nacer de un huevo ====================
    // Pedido explicito del usuario: para las especies BASE (sin nada de lo que evolucionar, asi
    // que no se les puede colgar una rama de evolucion como a Raichu-Alola) la variante regional
    // se sortea 50/50 al eclosionar - no se elige, es puro azar, fijo para siempre una vez nacida
    // (especie normal y regional son ESPECIES DISTINTAS de verdad: propio Pokedex/evolucion/tipo,
    // igual que Raichu/Raichu-Alola - ver pokedex.json/evolutions.json/types.json).
    val REGIONAL_HATCH_VARIANT: Map<String, String> = mapOf(
        "growlithe" to "growlithe-hisui",
        "diglett" to "diglett-alola",
        "voltorb" to "voltorb-hisui",
        "geodude" to "geodude-alola",
        "grimer" to "grimer-alola",
        "vulpix" to "vulpix-alola",
        "meowth" to "meowth-galar",
        "ponyta" to "ponyta-galar",
        "rattata" to "rattata-alola",
        "sandshrew" to "sandshrew-alola",
        "zorua" to "zorua-hisui",
        "zigzagoon" to "zigzagoon-galar",
        "slowpoke" to "slowpoke-galar",
        "darumaka" to "darumaka-galar",
        "articuno" to "articuno-galar",
        "moltres" to "moltres-galar",
        "zapdos" to "zapdos-galar",
        "stunfisk" to "stunfisk-galar",
        "yamask" to "yamask-galar",
        "sneasel" to "sneasel-hisui",
        "wooper" to "wooper-paldea",
        "qwilfish" to "qwilfish-hisui",
        "corsola" to "corsola-galar",
        "farfetchd" to "farfetchd-galar",
    )
    private val TATSUGIRI_FORMS = listOf("curly", "droopy", "stretchy")

    /** Forma actual (la que se ve/cuida ahora mismo) - "curly" por defecto, la forma base. */
    fun tatsugiriForm(context: Context): String = prefs(context).getString(KEY_TATSUGIRI_FORM, "curly") ?: "curly"

    /** Formas que se han tenido alguna vez (para no repetir una hasta tenerlas las 3). */
    fun tatsugiriFormsOwned(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TATSUGIRI_FORMS_OWNED, emptySet()) ?: emptySet()

    /** Nombre de sprite (sin Mega) para la forma [form] de Tatsugiri. "curly" es la forma con la
     *  que ya se creo el sprite generico "tatsugiri" (bulk import de antes de este sistema). */
    private fun tatsugiriSpriteKey(form: String): String = when (form) {
        "droopy" -> "tatsugiri-droopy"
        "stretchy" -> "tatsugiri-stretchy"
        else -> "tatsugiri"
    }

    /** Sortea una forma nueva para un huevo de Tatsugiri: entre las que aun faltan por tener, o
     *  entre las 3 si ya las tienes todas. */
    private fun rollTatsugiriForm(context: Context): String {
        val owned = tatsugiriFormsOwned(context)
        val pool = TATSUGIRI_FORMS.filter { it !in owned }.ifEmpty { TATSUGIRI_FORMS }
        return pool[Random.nextInt(pool.size)]
    }

    /** Marca [form] como tenida (se acumula, nunca se quita) y la deja como forma ACTUAL. */
    private fun setTatsugiriForm(context: Context, form: String) {
        val owned = HashSet(tatsugiriFormsOwned(context)).apply { add(form) }
        prefs(context).edit()
            .putString(KEY_TATSUGIRI_FORM, form)
            .putStringSet(KEY_TATSUGIRI_FORMS_OWNED, owned)
            .commit()
    }

    // ==================== ROTOM: 6 aparatos ====================
    // Caso especial (pedido explicito del usuario): Rotom no tiene ninguna evolucion, asi que sin
    // ningun gate tendrias las 6 formas gratis desde el primer momento - se sienten desbloqueadas
    // por nivel, igual que el resto de progreso del juego (Megaevolucion, Zygarde), en vez de
    // libres del todo. Cada aparato pide SU PROPIO nivel (repartidos a lo largo de la partida); una
    // vez desbloqueado un aparato, cambiar de uno a otro ya desbloqueado SI sigue siendo libre e
    // inmediato (fiel al "Catalogo Rotom" real - no hay cooldown ni gasto por cambiar). El aparato
    // SI cambia el tipo de verdad (ver typesOf) - afecta al emparejamiento de fondo, como
    // cualquier otra especie.
    private const val KEY_ROTOM_FORM = "rotom.form"
    data class RotomFormOption(val form: String, val label: String, val unlockLevel: Int)
    val ROTOM_FORMS: List<RotomFormOption> = listOf(
        RotomFormOption("rotom", "Rotom", 1),
        RotomFormOption("heat", "Rotom Calor", 20),
        RotomFormOption("wash", "Rotom Lavado", 25),
        RotomFormOption("frost", "Rotom Frio", 30),
        RotomFormOption("fan", "Rotom Ventilador", 35),
        RotomFormOption("mow", "Rotom Cortacesped", 40),
    )

    /** Forma actual de Rotom ("rotom" = la base, sin aparato). Por individuo (normal/shiny,
     *  ver slot()) - cada uno lleva SU PROPIO aparato, no se comparte (pedido explicito del
     *  usuario: normal y shiny son seres distintos con vida propia, igual que ya pasa con salud/
     *  felicidad/nivel - antes esto era una unica clave global, asi que cambiar el aparato del
     *  normal tambien se le "pegaba" al shiny). */
    fun rotomForm(context: Context, shiny: Boolean): String =
        prefs(context).getString(k(slot("rotom", shiny), KEY_ROTOM_FORM), "rotom") ?: "rotom"

    /** ¿Ya se desbloqueo este aparato (nivel actual >= el que pide)? */
    fun isRotomFormUnlocked(form: String, level: Int): Boolean =
        (ROTOM_FORMS.find { it.form == form }?.unlockLevel ?: Int.MAX_VALUE) <= level

    /** Cambia de aparato - solo entre los YA desbloqueados (ver isRotomFormUnlocked); no hace nada
     *  si [form] todavia no toca. Solo afecta al individuo [shiny] indicado. */
    fun setRotomForm(context: Context, form: String, level: Int, shiny: Boolean) {
        if (!isRotomFormUnlocked(form, level)) return
        prefs(context).edit().putString(k(slot("rotom", shiny), KEY_ROTOM_FORM), form).commit()
    }

    fun rotomSpriteKey(form: String): String = if (form == "rotom") "rotom" else "rotom-$form"

    // ==================== DEERLING/SAWSBUCK: estacion real ====================
    // Caso especial (pedido explicito del usuario): NO se elige ni se sortea - sigue, sin mas, la
    // estacion real actual (fecha del dispositivo, hemisferio norte). Ni se guarda ni hace falta
    // guardar nada: se recalcula cada vez que se enseña el sprite.
    private fun currentSeason(): String {
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)  // 0=enero
        return when (month) {
            2, 3, 4 -> "spring"    // marzo-mayo
            5, 6, 7 -> "summer"    // junio-agosto
            8, 9, 10 -> "autumn"   // septiembre-noviembre
            else -> "winter"       // diciembre-febrero
        }
    }

    private fun seasonalSpriteKey(species: String, season: String): String =
        if (season == "spring") species else "$species-$season"

    // ==================== FORMAS FUNCIONALES: desbloqueo por nivel (Oricorio, Ogerpon) ====================
    // Mismo concepto que ROTOM (el tipo SI cambia de verdad), generalizado: cada forma pide su
    // propio nivel: una vez desbloqueada, cambiar entre las que ya tienes es libre e inmediato.
    data class UnlockableForm(val key: String, val label: String, val unlockLevel: Int)
    private const val KEY_UNLOCKABLE_FORM = "unlock_form"
    private val UNLOCKABLE_FORMS: Map<String, List<UnlockableForm>> = mapOf(
        "oricorio" to listOf(
            UnlockableForm("baile", "Oricorio Baile", 1),
            UnlockableForm("pompom", "Oricorio Pom-Pom", 20),
            UnlockableForm("pau", "Oricorio Pa'u", 30),
            UnlockableForm("sensu", "Oricorio Sensu", 40),
        ),
        "ogerpon" to listOf(
            UnlockableForm("teal", "Ogerpon Mascara Verde", 1),
            UnlockableForm("wellspring", "Ogerpon Mascara Manantial", 20),
            UnlockableForm("hearthflame", "Ogerpon Mascara Llama", 30),
            UnlockableForm("cornerstone", "Ogerpon Mascara Piedra", 40),
        ),
        // Genesect: los Drives se cambian libremente con un objeto en el juego real, sin gatear
        // por nivel tampoco alli - pero igual que con Rotom, sin ningun gate aqui tendrias los 4
        // gratis desde el primer momento (Genesect tampoco evoluciona). Mismo reparto que Rotom.
        "genesect" to listOf(
            UnlockableForm("drive", "Genesect", 1),
            UnlockableForm("douse", "Genesect Impulso Acuoso", 20),
            UnlockableForm("burn", "Genesect Impulso Igneo", 25),
            UnlockableForm("shock", "Genesect Impulso Electrico", 30),
            UnlockableForm("chill", "Genesect Impulso Gelido", 35),
        ),
        // Meloetta: en el juego real se cambia con un movimiento en combate (Canto Arcano), sin
        // ningun nivel de por medio - aqui igual, libre desde el principio (las 2 "a nivel 1").
        "meloetta" to listOf(
            UnlockableForm("aria", "Meloetta Canto", 1),
            UnlockableForm("pirouette", "Meloetta Danza", 1),
        ),
    )

    fun unlockableFormOptions(species: String): List<UnlockableForm> = UNLOCKABLE_FORMS[species.lowercase()] ?: emptyList()

    /** Forma funcional actual de [species] (la primera de la lista = la base, sin sufijo). */
    /** Por individuo (normal/shiny, ver slot()) - cada uno lleva SU PROPIA forma, misma logica
     *  que rotomForm (pedido explicito del usuario, mismo motivo). */
    fun unlockableForm(context: Context, species: String, shiny: Boolean): String {
        val forms = unlockableFormOptions(species)
        if (forms.isEmpty()) return species
        return prefs(context).getString(k(slot(species, shiny), KEY_UNLOCKABLE_FORM), forms.first().key) ?: forms.first().key
    }

    fun isUnlockableFormUnlocked(species: String, form: String, level: Int): Boolean =
        (unlockableFormOptions(species).find { it.key == form }?.unlockLevel ?: Int.MAX_VALUE) <= level

    /** Cambia de forma - solo entre las YA desbloqueadas; no hace nada si [form] todavia no toca.
     *  Solo afecta al individuo [shiny] indicado. */
    fun setUnlockableForm(context: Context, species: String, form: String, level: Int, shiny: Boolean) {
        if (!isUnlockableFormUnlocked(species, form, level)) return
        prefs(context).edit().putString(k(slot(species, shiny), KEY_UNLOCKABLE_FORM), form).commit()
    }

    fun unlockableSpriteKey(species: String, form: String): String {
        val forms = unlockableFormOptions(species)
        return if (forms.firstOrNull()?.key == form) species else "$species-$form"
    }

    // ==================== FORMAS DECORATIVAS: al azar de un huevo (Furfrou, Basculin, ====================
    // ====================    Squawkabilly, Pumpkaboo/Gourgeist) ====================
    // Mismo concepto que TATSUGIRI, generalizado: no se elige ni se desbloquea por nivel (son
    // puramente esteticas, el tipo NO cambia) - cada vez que esa especie sale de un huevo le toca
    // una forma al azar, sin repetir ninguna que ya tengas hasta tenerlas todas. Pumpkaboo/
    // Gourgeist comparten el mismo "tamaño" (se guarda bajo la especie RAIZ, "pumpkaboo") - se
    // conserva tal cual al evolucionar, igual que el shiny.
    private const val KEY_DECORATIVE_FORM = "decorative_form"
    private const val KEY_DECORATIVE_FORMS_OWNED = "decorative_forms_owned"
    // Arceus/Silvally: 18 formas (Normal + 17 tipos con placa/disco) - a diferencia del resto de
    // esta lista, el TIPO SI cambia de verdad con la forma (ver typesOf) - se sortea igual al azar
    // de huevo (misma abstraccion de siempre para legendarios, ver Necrozma/Kyurem) ya que aqui no
    // existe un objeto que "equipar" a mano. Identificado por COLOR contra los sprites oficiales
    // de Pokemon Showdown (confianza alta - a diferencia de Unown, cada tipo tiene un color propio
    // muy reconocible), no por etiqueta del pack (no traia ninguna).
    private val ARCEUS_SILVALLY_FORMS = listOf(
        "normal", "fighting", "flying", "poison", "ground", "rock", "bug", "ghost", "steel",
        "fire", "water", "grass", "electric", "psychic", "ice", "dragon", "dark", "fairy"
    )
    private val DECORATIVE_FORMS: Map<String, List<String>> = mapOf(
        "arceus" to ARCEUS_SILVALLY_FORMS,
        "silvally" to ARCEUS_SILVALLY_FORMS,
        // Vivillon: 20 patrones regionales, puramente decorativos (nunca cambia tipo, siempre
        // Bicho/Volador) - identificado por comparacion de PIXELES contra los sprites oficiales de
        // Pokemon Showdown (coincidencia casi exacta, confianza muy alta - el pack usa
        // practicamente el mismo arte). "meadow" es la forma base (bare "vivillon"), coherente con
        // ser el patron "representativo" oficial de la especie.
        "vivillon" to listOf(
            "meadow", "archipelago", "continental", "elegant", "garden", "highplains", "icysnow",
            "jungle", "marine", "modern", "monsoon", "ocean", "polar", "river", "sandstorm",
            "savanna", "sun", "tundra", "fancy", "pokeball"
        ),
        // Pikachu: disfraces Cosplay (Rock Star/Belle/Pop Star/PhD/Libre) + gorras de eventos
        // (Original/Hoenn/Sinnoh/Unova/Kalos/Alola/Compañero/Mundial) - puramente decorativos, el
        // tipo nunca cambia. OJO: a diferencia de Vivillon, aqui el pack usa un estilo de arte
        // DISTINTO al oficial (sin match de pixeles posible) - identificado a ojo, confianza BAJA
        // sobre todo en las 8 gorras (muy parecidas entre si, solo cambia un detalle de color/
        // logo pequeño) - pedido explicito del usuario implementarlo igual con esta incertidumbre.
        "pikachu" to listOf(
            "regular", "cosplay", "phd", "rockstar", "libre", "belle", "popstar",
            "original", "hoenn", "sinnoh", "unova", "kalos", "alola", "partner", "world"
        ),
        // Alcremie: 63 formas = 9 sabores de crema x 7 decoraciones dulces, puramente decorativas
        // (nunca cambia tipo). CONFIANZA BAJA-MUY BAJA (mas que Unown/Pikachu): el sabor se asigno
        // por color visto en una hoja de contacto (razonable pero no verificado contra ninguna
        // referencia oficial), y el ORDEN de las 7 decoraciones DENTRO de cada sabor es una pura
        // suposicion posicional (Fresa/Baya/Amor/Estrella/Trebol/Flor/Lazo, el orden de listado
        // habitual) - Pokemon Showdown ni siquiera tiene sprites por decoracion para comprobar
        // nada. Se implementa igual por peticion explicita del usuario, sabiendo esto.
        "alcremie" to listOf(
            "ruby-strawberry", "ruby-berry", "ruby-love", "ruby-star", "ruby-clover", "ruby-flower", "ruby-ribbon",
            "rubyswirl-strawberry", "rubyswirl-berry", "rubyswirl-love", "rubyswirl-star", "rubyswirl-clover", "rubyswirl-flower", "rubyswirl-ribbon",
            "matcha-strawberry", "matcha-berry", "matcha-love", "matcha-star", "matcha-clover", "matcha-flower", "matcha-ribbon",
            "mint-strawberry", "mint-berry", "mint-love", "mint-star", "mint-clover", "mint-flower", "mint-ribbon",
            "lemon-strawberry", "lemon-berry", "lemon-love", "lemon-star", "lemon-clover", "lemon-flower", "lemon-ribbon",
            "vanilla-strawberry", "vanilla-berry", "vanilla-love", "vanilla-star", "vanilla-clover", "vanilla-flower", "vanilla-ribbon",
            "salted-strawberry", "salted-berry", "salted-love", "salted-star", "salted-clover", "salted-flower", "salted-ribbon",
            "caramel-strawberry", "caramel-berry", "caramel-love", "caramel-star", "caramel-clover", "caramel-flower", "caramel-ribbon",
            "rainbow-strawberry", "rainbow-berry", "rainbow-love", "rainbow-star", "rainbow-clover", "rainbow-flower", "rainbow-ribbon"
        ),
        "furfrou" to listOf("natural", "heart", "star", "diamond", "pharaoh", "debutante", "matron", "dandy", "lareine", "kabuki"),
        // Unown: 28 formas (A-Z, ! y ?), puramente decorativas (nunca cambia tipo/stats, no
        // evoluciona) - EN EL JUEGO REAL Unown no puede reproducirse (grupo huevo "Ningun huevo
        // descubierto"), pero aqui la puesta de huevos ya es una abstraccion generica de la app
        // (ver Necrozma/Kyurem, que tampoco crian de verdad) - se trata igual que el resto de esta
        // lista. OJO: el orden b..z/em/qm es una suposicion (el pack no traia las letras
        // etiquetadas) - se asumio el orden interno alfabetico de los juegos, sin poder
        // confirmarlo al 100% letra por letra.
        "unown" to listOf(
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "em", "qm"
        ),
        "basculin" to listOf("white", "red", "blue"),
        "squawkabilly" to listOf("green", "blue", "yellow", "white"),
        "pumpkaboo" to listOf("small", "average", "large", "super"),
        "castform" to listOf("castform", "sunny", "rainy", "snowy"),
        "deoxys" to listOf("deoxys", "defense", "attack", "speed"),
        // OJO: Kyurem, Calyrex y Necrozma NO van aqui - a diferencia del resto de esta lista, sus
        // formas NO se sortean al azar de un huevo: en el juego real se consiguen FUSIONANDOLas
        // con otra especie distinta que hay que tener conseguida (Reshiram/Zekrom para Kyurem,
        // Glastrier/Spectrier para Calyrex, Solgaleo/Lunala para Necrozma) - se implementan como
        // Megaevolucion normal, gateada por esa otra especie (ver FUSION_PARTNERS/canMegaEvolve).
        // Gimmighoul: Forma Cofre (comun) o Forma Errante (rara) - fijo al conseguirlo, no cambia.
        "gimmighoul" to listOf("chest", "roaming"),
        // Shellos/Gastrodon comparten el "mar" (raiz "shellos") - se conserva al evolucionar.
        "shellos" to listOf("west", "east"),
        // Flabebe/Floette/Florges comparten el color (raiz "flabebe") - se conserva al evolucionar
        // en TODA la cadena (Floette usa su propio sprite de mega aparte, ya implementado).
        // "eternal" (Flor Eterna) es en el juego real EXCLUSIVA de Floette (evento especial, no
        // sorteable para Flabebe/Florges) - aqui, pedido explicito del usuario, se mete igual en
        // el mismo sorteo compartido por simplicidad: si le toca a Flabebe/Florges no hay sprite
        // propio (solo existe para Floette) y cae al respaldo de red ya existente en la app.
        "flabebe" to listOf("red", "yellow", "orange", "blue", "white", "eternal"),
        // Minior: el COLOR del nucleo se sortea al azar de huevo igual que las demas (nunca se
        // elige), pero NO forma parte de esta lista "meteoro" - el envoltorio (mostrar el
        // meteorito o el nucleo ya revelado) es AUTOMATICO segun la vida (ver miniorSpriteKey),
        // no una forma mas a elegir.
        "minior" to listOf("red", "orange", "yellow", "green", "blue", "indigo", "violet"),
        // Tauros: las 3 razas de Paldea (Combate/Llama/Agua) - al azar de huevo, sin repetir hasta
        // tenerlas las 3. El Tauros de Kanto (el sprite generico ya existente) queda fuera de este
        // sorteo a proposito (pedido implicito: el usuario solo hablo de las 3 razas paldeanas).
        "tauros" to listOf("combat", "blaze", "water"),
    )

    fun decorativeFormsOptions(rootSpecies: String): List<String> = DECORATIVE_FORMS[rootSpecies.lowercase()] ?: emptyList()

    /** Forma decorativa actual de la especie RAIZ [rootSpecies] (la primera de la lista = la base,
     *  sin sufijo). Para Gourgeist hay que pasar "pumpkaboo" (ver baseFormOf), no "gourgeist". */
    fun decorativeForm(context: Context, rootSpecies: String): String {
        val forms = decorativeFormsOptions(rootSpecies)
        if (forms.isEmpty()) return rootSpecies
        return prefs(context).getString(k(rootSpecies, KEY_DECORATIVE_FORM), forms.first()) ?: forms.first()
    }

    fun decorativeFormsOwned(context: Context, rootSpecies: String): Set<String> =
        prefs(context).getStringSet(k(rootSpecies, KEY_DECORATIVE_FORMS_OWNED), emptySet()) ?: emptySet()

    private fun rollDecorativeForm(context: Context, rootSpecies: String): String {
        val forms = decorativeFormsOptions(rootSpecies)
        val owned = decorativeFormsOwned(context, rootSpecies)
        val pool = forms.filter { it !in owned }.ifEmpty { forms }
        return pool[Random.nextInt(pool.size)]
    }

    private fun setDecorativeForm(context: Context, rootSpecies: String, form: String) {
        val owned = HashSet(decorativeFormsOwned(context, rootSpecies)).apply { add(form) }
        prefs(context).edit()
            .putString(k(rootSpecies, KEY_DECORATIVE_FORM), form)
            .putStringSet(k(rootSpecies, KEY_DECORATIVE_FORMS_OWNED), owned)
            .commit()
    }

    /** Cambia la forma decorativa ACTIVA de [rootSpecies] a [form], pero SOLO entre las que ya
     *  tienes conseguidas de un huevo (ver decorativeFormsOwned) - no "desbloquea" ninguna
     *  nueva, solo deja elegir libremente entre las que el azar ya te dio, igual que Rotom/
     *  Genesect entre sus formas desbloqueadas por nivel (pedido explicito del usuario: "los
     *  pokemon con diferentes formas deberian poder seleccionar la forma... siempre y cuando
     *  la tengan"). Devuelve false sin hacer nada si todavia no la tienes. */
    fun selectDecorativeForm(context: Context, rootSpecies: String, form: String): Boolean {
        if (form !in decorativeFormsOwned(context, rootSpecies)) return false
        prefs(context).edit().putString(k(rootSpecies, KEY_DECORATIVE_FORM), form).commit()
        return true
    }

    /** SOLO PARA EL MENU DEBUG: marca o desmarca [form] como "conseguida" para la especie RAIZ
     *  [rootSpecies] (ver decorativeFormsOwned) - a diferencia de setDecorativeForm/
     *  rollDecorativeForm (que solo AÑADEN, nunca quitan, pensadas para el sorteo real de huevo),
     *  aqui se puede desmarcar tambien, para poder probar cualquier combinacion. Si se desmarca la
     *  que esta puesta ahora mismo (decorativeForm), la activa pasa a otra que siga marcada (o a la
     *  base de la lista si no queda ninguna) - nunca se deja mostrando una forma ya desmarcada. */
    fun debugSetDecorativeFormOwned(context: Context, rootSpecies: String, form: String, owned: Boolean) {
        val root = rootSpecies.lowercase()
        val p = prefs(context)
        val current = HashSet(decorativeFormsOwned(context, root))
        if (owned) current.add(form) else current.remove(form)
        val e = p.edit().putStringSet(k(root, KEY_DECORATIVE_FORMS_OWNED), current)
        if (!owned && decorativeForm(context, root) == form) {
            val fallback = current.firstOrNull() ?: decorativeFormsOptions(root).firstOrNull() ?: form
            e.putString(k(root, KEY_DECORATIVE_FORM), fallback)
        }
        e.commit()
    }

    fun decorativeSpriteKey(rootSpecies: String, form: String): String {
        val forms = decorativeFormsOptions(rootSpecies)
        return if (forms.firstOrNull() == form) rootSpecies else "$rootSpecies-$form"
    }

    /** Gourgeist usa el mismo "tamaño" que Pumpkaboo (ver decorativeForm(context, "pumpkaboo")),
     *  pero con sus propios sprites ("gourgeist"/"gourgeist-<tamaño>", nunca "pumpkaboo-..."). */
    fun gourgeistSpriteKey(size: String): String =
        if (decorativeFormsOptions("pumpkaboo").firstOrNull() == size) "gourgeist" else "gourgeist-$size"

    /** Tauros: a diferencia del resto, NINGUNA de las 3 razas usa el sprite generico "tauros"
     *  (ese es el Tauros de Kanto, fuera de este sorteo) - las 3 van siempre con sufijo. */
    fun taurosSpriteKey(breed: String): String = "tauros-$breed"

    /** Minior: el color (ya sorteado al azar de huevo, ver decorativeForm(context,"minior")) solo
     *  se VE si esta por debajo del 50% de vida (el meteorito se ha roto) - por encima, siempre
     *  se enseña el meteorito cerrado ("minior"), sea cual sea el color que le toco por dentro. */
    fun miniorSpriteKey(color: String, health: Float): String =
        if (health < 50f) "minior-$color" else "minior"

    // ==================== BURMY: manto segun el fondo actual ====================
    // Caso especial: mientras SIGUE siendo Burmy, el manto sigue en vivo el fondo actual (como
    // Deerling con la estacion - el manto real depende del terreno). Al evolucionar a Wormadam
    // (nunca a Mothim, que no tiene manto) el manto QUEDA FIJO para siempre - el que tuviera en
    // ese momento -, igual que el shiny se fija al evolucionar (ver evolveTo).
    fun burmyCloakForBg(bg: String): String = when (bg) {
        "bg_meadow", "bg_forest", "bg_route" -> "plant"
        "bg_city", "bg_thunderplains" -> "trash"
        else -> "sandy"  // desierto, montaña, cuevas, playa, mar, hielo, volcan
    }

    fun burmySpriteKey(cloak: String): String = if (cloak == "plant") "burmy" else "burmy-$cloak"

    private const val KEY_WORMADAM_CLOAK = "wormadam.cloak"

    /** Manto YA fijado de Wormadam ("plant" si por lo que sea nunca se fijo, ej. datos viejos). */
    fun wormadamCloak(context: Context): String = prefs(context).getString(KEY_WORMADAM_CLOAK, "plant") ?: "plant"

    fun wormadamSpriteKey(cloak: String): String = when (cloak) {
        "sandy" -> "wormadam-sandy"
        "trash" -> "wormadam-trash"
        else -> "wormadam"
    }

    private fun wormadamTypesFor(cloak: String): List<String> = when (cloak) {
        "sandy" -> listOf("bug", "ground")
        "trash" -> listOf("bug", "steel")
        else -> listOf("bug", "grass")
    }

    // ==================== DARMANITAN: Modo Zen segun la vida actual ====================
    // Automatico segun la vida ACTUAL (por debajo del 50% = Modo Zen, igual que la habilidad real
    // se activa en combate) - no se elige ni se guarda nada, se recalcula cada vez.
    fun darmanitanSpriteKey(health: Float): String = if (health < 50f) "darmanitan-zen" else "darmanitan"
    private fun darmanitanTypesFor(health: Float): List<String> =
        if (health < 50f) listOf("fire", "psychic") else listOf("fire")

    // ==================== CRAMORANT: Tragando/Atragantado al darle de comer ====================
    // Caso especial: en los juegos reales se activa al usar un movimiento en combate (no existe
    // aqui) - el analogo mas natural en esta app es la accion de darle de COMER: cada vez que se
    // le da de comer (y hace falta de verdad, ver tryApplyAction) hay una probabilidad de que
    // "atrape algo" y avance un paso (Normal -> Tragando -> Atragantado); pasado un rato sin
    // avanzar mas, vuelve solo a Normal - puramente decorativo, no cambia tipo ni stats.
    private const val KEY_CRAMORANT_STATE = "cramorant.state"
    private const val KEY_CRAMORANT_STATE_SINCE = "cramorant.state_since"
    private const val CRAMORANT_ADVANCE_PROB = 0.35f
    private const val CRAMORANT_REVERT_MS = 3 * 60 * 60 * 1000L  // 3 horas sin comer -> vuelve a Normal
    private val CRAMORANT_STATES = listOf("normal", "gulping", "gorging")

    /** Estado actual (ya con la reversion automatica por tiempo aplicada). */
    fun cramorantState(context: Context): String {
        val p = prefs(context)
        val state = p.getString(KEY_CRAMORANT_STATE, "normal") ?: "normal"
        if (state == "normal") return state
        val since = p.getLong(KEY_CRAMORANT_STATE_SINCE, 0L)
        if (System.currentTimeMillis() - since > CRAMORANT_REVERT_MS) {
            p.edit().putString(KEY_CRAMORANT_STATE, "normal").commit()
            return "normal"
        }
        return state
    }

    /** Al comer de verdad (accion aceptada): con CRAMORANT_ADVANCE_PROB de probabilidad, avanza
     *  un paso (Normal->Tragando->Atragantado; en Atragantado ya no avanza mas). */
    private fun maybeAdvanceCramorant(context: Context) {
        val current = cramorantState(context)
        val idx = CRAMORANT_STATES.indexOf(current)
        if (idx >= CRAMORANT_STATES.lastIndex) return
        if (Random.nextFloat() >= CRAMORANT_ADVANCE_PROB) return
        prefs(context).edit()
            .putString(KEY_CRAMORANT_STATE, CRAMORANT_STATES[idx + 1])
            .putLong(KEY_CRAMORANT_STATE_SINCE, System.currentTimeMillis())
            .commit()
    }

    fun cramorantSpriteKey(state: String): String = if (state == "normal") "cramorant" else "cramorant-$state"

    // ==================== SOLGALEO/LUNALA: Fase Sol Radiante / Luna Llena ====================
    // En el juego real es un destello brevisimo SOLO durante la animacion de su movimiento firma
    // (Acero Poniente / Rayo Umbrio) en combate - aqui, igual que Cramorant, se dispara con
    // cualquier accion aceptada y dura un ratito corto antes de volver sola a la normal.
    private const val KEY_RADIANT_UNTIL = "radiant_until"
    private const val RADIANT_DURATION_MS = 60 * 1000L  // medio minuto, un destello, no una forma larga
    private fun triggerRadiantPhase(context: Context, name: String) {
        prefs(context).edit().putLong(k(name, KEY_RADIANT_UNTIL), System.currentTimeMillis() + RADIANT_DURATION_MS).commit()
    }
    fun isRadiantPhaseActive(context: Context, name: String): Boolean =
        System.currentTimeMillis() < prefs(context).getLong(k(name.lowercase(), KEY_RADIANT_UNTIL), 0L)
    fun solgaleoSpriteKey(active: Boolean): String = if (active) "solgaleo-radiant" else "solgaleo"
    fun lunalaSpriteKey(active: Boolean): String = if (active) "lunala-fullmoon" else "lunala"

    // ==================== GENERO: diferencia visual macho/hembra ====================
    // Puramente decorativo (pedido explicito del usuario): estas 103 especies tienen un sprite de
    // hembra realmente distinto en el pack (el resto de la Pokedex no cambia nada visualmente
    // entre sexos, asi que no hace falta nada para ellas). Se sortea UNA vez al conseguir un
    // individuo NUEVO (ver setPokemon/isFreshIndividual) con la probabilidad REAL de esa especie
    // (verificada especie por especie via PokeAPI - la inmensa mayoria son 50/50, unas pocas no) y
    // se queda fijo para siempre, incluso al evolucionar (ver evolveTo) - el sexo nunca cambia.
    private const val KEY_GENDER = "gender"

    // Probabilidad de HEMBRA para las pocas especies que no son 50/50 (verificado via PokeAPI,
    // gender_rate/8): Alakazam/Kadabra 25%, Combee/Combusken/Torchic/Relicanth 12.5%, Pyroar 87.5%.
    private val GENDER_FEMALE_CHANCE: Map<String, Float> = mapOf(
        "alakazam" to 0.25f, "kadabra" to 0.25f,
        "combee" to 0.125f, "combusken" to 0.125f, "torchic" to 0.125f, "relicanth" to 0.125f,
        "pyroar" to 0.875f,
    )

    val GENDER_SPECIES: Set<String> = setOf(
        "abomasnow", "aipom", "alakazam", "ambipom", "basculegion", "beautifly", "bibarel",
        "bidoof", "blaziken", "buizel", "butterfree",
        "cacturne", "camerupt", "combee", "combusken", "croagunk", "doduo", "dodrio", "donphan",
        "dustox", "eevee",
        "finneon", "floatzel", "frillish", "gabite", "garchomp", "gible", "girafarig", "gligar",
        "gloom", "golbat", "goldeen", "gulpin", "gyarados",
        "heracross", "hippopotas", "hippowdon", "houndoom", "hypno", "indeedee", "jellicent",
        "kadabra", "kricketot", "kricketune", "ledian", "ledyba", "ludicolo", "lumineon", "luxio",
        "luxray", "magikarp", "mamoswine", "medicham", "meditite", "meganium", "meowstic",
        "milotic", "murkrow", "numel", "nuzleaf",
        "octillery", "oinkologne", "pachirisu", "pikachu", "piloswine", "politoed", "pyroar",
        "quagsire", "raichu", "raticate", "rattata",
        "relicanth", "rhydon", "rhyhorn", "rhyperior", "roselia", "roserade", "scizor", "scyther",
        "seaking", "shiftry", "shinx", "sneasel", "sneasel-hisui", "snover", "staraptor",
        "staravia", "starly", "steelix", "sudowoodo", "swalot", "tangrowth",
        "torchic", "toxicroak", "unfezant", "ursaring", "venusaur", "vileplume", "weavile",
        "wobbuffet", "wooper", "xatu",
        "zubat",
    )

    fun rollGender(species: String): String =
        if (Random.nextFloat() < (GENDER_FEMALE_CHANCE[species] ?: 0.5f)) "female" else "male"

    /** Sexo YA fijado de [name] ("male" por defecto si por lo que sea nunca se fijo). */
    fun genderOf(context: Context, name: String): String =
        prefs(context).getString(k(name.lowercase(), KEY_GENDER), "male") ?: "male"

    fun genderSpriteKey(name: String, gender: String): String {
        val n = name.lowercase()
        if (gender != "female") return n
        return "$n-female"
    }

    // ==================== KELDEO: Forma Resuelta al aprender la espada (por nivel) ====================
    // En el juego real se queda en Forma Resuelta para siempre en cuanto aprende Espada Secreta -
    // aqui igual, PERMANENTE por nivel (como Zygarde), sin marcha atras.
    const val KELDEO_RESOLUTE_LEVEL = 30
    private const val KEY_KELDEO_RESOLUTE = "keldeo.resolute"
    fun keldeoIsResolute(context: Context): Boolean = prefs(context).getBoolean(KEY_KELDEO_RESOLUTE, false)
    fun keldeoSpriteKey(resolute: Boolean): String = if (resolute) "keldeo-resolute" else "keldeo"

    // ==================== CHERRIM: Forma Soleada segun el fondo actual ====================
    // Automatico segun el fondo puesto ahora mismo (el mas parecido a "sol fuerte" que hay en esta
    // app) - igual que Burmy con el suyo, sin elegir ni guardar nada.
    fun cherrimSpriteKey(bg: String): String =
        if (bg == "bg_volcanocave" || bg == "bg_desert") "cherrim-sunshine" else "cherrim"

    // ==================== MIMIKYU: Forma Destrozada segun la vida actual ====================
    fun mimikyuSpriteKey(health: Float): String = if (health < 50f) "mimikyu-busted" else "mimikyu"

    // ==================== EISCUE: Cara Noice segun la vida actual ====================
    fun eiscueSpriteKey(health: Float): String = if (health < 50f) "eiscue-noice" else "eiscue"

    // ==================== WISHIWASHI: Forma Banco segun nivel Y vida ====================
    // En el juego real hace falta nivel 20 Y mas del 25% de vida para la Forma Banco - por debajo
    // de cualquiera de las 2, Forma Solitaria.
    fun wishiwashiSpriteKey(level: Int, health: Float): String =
        if (level >= 20 && health > 25f) "wishiwashi-school" else "wishiwashi"

    // ==================== MORPEKO: Hangry/Barriga Llena alternando con el tiempo real ====================
    // En el juego real cambia cada turno de combate, sin poder controlarlo - aqui, sin turnos, el
    // analogo mas parecido es alternar solo con el tiempo real (un intervalo fijo), tambien sin
    // poder elegirlo.
    private const val MORPEKO_SWITCH_INTERVAL_MS = 2 * 60 * 60 * 1000L  // cada 2h cambia solo
    fun morpekoIsHangry(): Boolean = (System.currentTimeMillis() / MORPEKO_SWITCH_INTERVAL_MS) % 2L == 0L
    fun morpekoSpriteKey(hangry: Boolean): String = if (hangry) "morpeko" else "morpeko-fullbelly"

    // ==================== PALAFIN: Forma Heroe al volver a elegirlo ====================
    // En el juego real pasa a Forma Heroe (para el resto del combate) la primera vez que se le
    // retira sin desmayarse. Aqui, sin combates, el equivalente mas parecido es: la primera vez
    // que vuelves a elegirlo tras haber estado cuidando OTRO Pokemon (osea, lo "retiraste" una
    // vez) - PERMANENTE desde entonces, no vuelve a Forma Cero.
    private const val KEY_PALAFIN_HERO = "palafin.hero"
    fun palafinIsHero(context: Context): Boolean = prefs(context).getBoolean(KEY_PALAFIN_HERO, false)
    fun palafinSpriteKey(hero: Boolean): String = if (hero) "palafin-hero" else "palafin"

    // ==================== AEGISLASH: Espada/Escudo alternando con cada accion ====================
    // En el juego real cambia segun el TIPO de movimiento que use (ataque -> Hoja, King's Shield
    // -> Escudo) - aqui, sin movimientos, alterna con cada accion aceptada (comer/acariciar/
    // bañar), igual de simple que Morpeko con el tiempo.
    private const val KEY_AEGISLASH_STANCE = "aegislash.stance"
    fun aegislashStance(context: Context): String = prefs(context).getString(KEY_AEGISLASH_STANCE, "shield") ?: "shield"
    fun aegislashSpriteKey(stance: String): String = if (stance == "blade") "aegislash-blade" else "aegislash"
    private fun toggleAegislashStance(context: Context) {
        val next = if (aegislashStance(context) == "blade") "shield" else "blade"
        prefs(context).edit().putString(KEY_AEGISLASH_STANCE, next).commit()
    }

    // ==================== TERAPAGOS: Terastal (permanente, por nivel) + Estelar (Mega) ====================
    // Terastal es un ascenso PERMANENTE por nivel (como Zygarde) - Normal -> Terastal. Estelar
    // (ver megas.json) usa el sistema de Megas normal, pero con su propio gate: solo se puede
    // fusionar en Estelar si YA se llego a Terastal (no por nivel 40 normal).
    const val TERAPAGOS_TERASTAL_LEVEL = 30
    private const val KEY_TERAPAGOS_TERASTAL = "terapagos.terastal"
    fun terapagosIsTerastal(context: Context): Boolean = prefs(context).getBoolean(KEY_TERAPAGOS_TERASTAL, false)
    fun terapagosSpriteKey(terastal: Boolean): String = if (terastal) "terapagos-terastal" else "terapagos"
    private fun maybeAdvanceTerapagos(context: Context, name: String, level: Int) {
        if (name.lowercase() != "terapagos") return
        if (!terapagosIsTerastal(context) && level >= TERAPAGOS_TERASTAL_LEVEL) {
            prefs(context).edit().putBoolean(KEY_TERAPAGOS_TERASTAL, true).commit()
        }
    }

    // ==================== DUDUNSPARCE / MAUSHOLD: variante rara (1%) al evolucionar ====================
    // En el juego real, Dunsparce->Dudunsparce y Tandemaus->Maushold tienen un 1% de sacar la
    // variante rara (3 segmentos / familia de 3) en vez de la normal (2 segmentos / familia de 4) -
    // sorteado una sola vez, en el momento de evolucionar, y fijo para siempre.
    private const val KEY_DUDUNSPARCE_THREE = "dudunsparce.three"
    private const val KEY_MAUSHOLD_THREE = "maushold.three"
    private const val RARE_EVO_VARIANT_CHANCE = 0.01f
    fun dudunsparceIsThree(context: Context): Boolean = prefs(context).getBoolean(KEY_DUDUNSPARCE_THREE, false)
    fun dudunsparceSpriteKey(three: Boolean): String = if (three) "dudunsparce-three" else "dudunsparce"
    fun mausholdIsThree(context: Context): Boolean = prefs(context).getBoolean(KEY_MAUSHOLD_THREE, false)
    fun mausholdSpriteKey(three: Boolean): String = if (three) "maushold-three" else "maushold"

    // Rediseñado a 1 fondo por tipo, SIN repetir ninguno entre los 18 (antes montaña sola valia
    // para 7 tipos a la vez - fighting/flying/ground/rock/steel/ice/dragon - y cueva rocosa para
    // otros 5; ver conversacion, incluye la revision visual completa comparando pixel-art real,
    // fondos ya integrados de Showdown, y la carpeta de fondos nueva del usuario). 13 de los 18
    // reutilizan fondos YA existentes (los propios de Showdown, sin coste extra); los otros 5
    // (Lucha/Veneno/Psiquico/Siniestro/Hada) son nuevos porque ningun fondo de Showdown encajaba
    // bien con ellos.
    //
    // bg_beachshore y bg_river se quedan DELIBERADAMENTE fuera de esta tabla (0 tipos, puramente
    // decorativos) - se probo añadirlos como opcion extra de Agua y el usuario pidio explicitamente
    // revertirlo: quiere estricto 1 fondo por tipo, sin excepciones ni siquiera como alternativa
    // dentro del mismo tipo. No es un descuido, no volver a "arreglarlo" sin que lo pida.
    private val BG_FOR_TYPE: Map<String, List<String>> = mapOf(
        "normal" to listOf("bg_route"),
        "fighting" to listOf("bg_templo"),
        "flying" to listOf("bg_mountain"),
        "poison" to listOf("bg_cuevalila"),
        "ground" to listOf("bg_desert"),
        "rock" to listOf("bg_earthycave"),
        "bug" to listOf("bg_forest"),
        "ghost" to listOf("bg_dampcave"),
        "steel" to listOf("bg_city"),
        "fire" to listOf("bg_volcanocave"),
        "water" to listOf("bg_beach"),
        "grass" to listOf("bg_meadow"),
        "electric" to listOf("bg_thunderplains"),
        "psychic" to listOf("bg_mundodistorsion"),
        "ice" to listOf("bg_icecave"),
        "dragon" to listOf("bg_deepsea"),
        "dark" to listOf("bg_cuevaoscura"),
        "fairy" to listOf("bg_bosquearcoiris")
    )

    /** Hasta 2 fondos (de los ya existentes) que le pegan a [name] segun su tipo - union de sus 2
     *  tipos si tiene dos, sin duplicados. Vacio si no hay datos de tipo para esa especie. */
    fun suggestedBackgrounds(context: Context, name: String, shiny: Boolean = isActiveShiny(context)): List<String> {
        val result = LinkedHashSet<String>()
        for (t in typesOf(context, name, shiny)) { BG_FOR_TYPE[t]?.forEach { result.add(it) } }
        return result.take(2)
    }

    /** Los tipos (nombre en ingles, para traducir en la UI) a los que beneficia [bg] - la
     *  inversa de BG_FOR_TYPE, para poder explicarlo en la ficha del fondo. */
    fun typesBenefitedBy(bg: String): List<String> = BG_FOR_TYPE.filterValues { bg in it }.keys.toList()

    // ==================== FONDOS: DESBLOQUEO POR NIVEL DE ENTRENADOR ====================
    // "Nivel de entrenador" = la MISMA curva xp->nivel que cada Pokemon (cumXp/levelOf), aplicada
    // a un POZO de 4 fuentes distintas, pensado para que tener MUCHAS especies (aunque sea a
    // nivel bajo) pese muchisimo mas que subir de nivel a las pocas que ya tienes - evolucionar y
    // encontrar shinies dan un empujon aparte. Calibrado con datos reales + varios escenarios
    // hipoteticos (ver conversacion): 7 especies sueltas (tu partida real al escribir esto) ~nv.9,
    // un coleccionista con ~300 especies decentes ~nv.75, y "la gran mayoria" (~800 especies, sin
    // hace falta tenerlas muy subidas) ya llega al tope de 100 - NO hace falta el 100% del juego.
    //
    // Cuidado al contar para no duplicar la misma hazaña en una cadena evolutiva (ej. Bulbasaur
    // -> Ivysaur -> Venusaur, 3 fases/2 evoluciones):
    //  - CAPTURA solo cuenta la forma ACTUAL (no evolucionada-away) de cada cadena - igual que
    //    NIVEL (ver mas abajo). Antes contaba TODAS las formas que hayas tenido alguna vez,
    //    evolucionadas o no (como en una Pokedex real) - bug real reportado por el usuario: eso
    //    hacia que una cadena de 3 fases sumara captura TRES veces por el mismo individuo, en vez
    //    de una, disparando el nivel de entrenador muy por encima de lo que tenia sentido para lo
    //    que de verdad se tiene ahora mismo.
    //  - NIVEL solo cuenta la forma ACTUAL (no evolucionada-away) de cada cadena: la anterior ya
    //    paso su xp a la siguiente al evolucionar (ver evolveTo), contarla tambien duplicaria el
    //    mismo progreso - una cadena de 3 fases suma UN nivel, no tres.
    //  - EVOLUCION cuenta una vez por cada transicion ya completada (evolved_away=true): una
    //    cadena de 3 fases da DOS, no tres.
    //  - SHINY solo cuenta si la forma ACTUAL es shiny: el flag se copia a cada fase al
    //    evolucionar (ver evolveTo), asi que sin este filtro un unico shiny de 3 fases contaria
    //    como si fueran 3 shinies distintos.
    //
    // Cuarta vuelta (pedido explicito del usuario, tras el bug de arriba): con el bug arreglado
    // pero los mismos pesos, el nivel de entrenador seguia dominado por CAPTURA (74% del pozo con
    // su partida real de esa ronda) - "muchas especies aunque sea a nivel bajo" se podia conseguir
    // sin esfuerzo real (una captura vale lo mismo aunque el individuo siga a nivel 1). Bajado el
    // peso de captura y subido el de nivel (lo unico que cuesta tiempo real, no se puede "rellenar"
    // de golpe) para que NIVEL sea el que mas pese, con evolucion/shiny como hitos/suerte aparte -
    // verificado con varios escenarios hipoteticos (ver conversacion) que el reparto quede sano en
    // todas las etapas, no solo en la inicial.
    private const val TRAINER_XP_PER_CAPTURE = 5f
    private const val TRAINER_XP_PER_MON_LEVEL = 1.5f
    private const val TRAINER_XP_PER_EVOLUTION = 15f
    private const val TRAINER_XP_PER_SHINY = 40f
    private const val TRAINER_LEVELS_PER_TOKEN = 2
    private const val KEY_UNLOCKED_BGS = "unlocked_bgs"
    private const val DEFAULT_BG = "bg_meadow"

    // Curva del NIVEL DE ENTRENADOR: propia y separada de la de cada Pokemon (antes reusaba la
    // misma cumXp/levelOf - eso fue lo que causo el lio del "nivel 28 de entrenador con 4
    // Pokemon" de una ronda anterior). La referencia vieja de "800 especies (la gran mayoria) ->
    // tope 100" ya no vale: el dex real solo tiene 541 especies.
    //
    // Segunda vuelta (pedido explicito del usuario): con un coste CRECIENTE por nivel (base fija
    // + paso que sube cada vez), los primeros niveles salian demasiado baratos - nivel 25 en
    // apenas una semana jugando bien, lo cual no se sentia bien para un hito temprano. Aqui es
    // justo AL REVES: el coste MARGINAL (lo que cuesta pasar de nivel a nivel) empieza alto y va
    // bajando hacia un suelo, nunca a cero - los primeros niveles cuestan de verdad, y una vez
    // llevas ya bastante capturado/evolucionado/subido de nivel, los ultimos vienen mucho mas
    // rapido por cada punto de pozo nuevo. Simulado (ver conversacion): nivel 25 ya no en 1
    // semana sino en 24-93 dias segun estilo de juego, nivel 100 en 75-293 dias - parecido o
    // mejor que la curva anterior en el total, solo que ahora el peso esta al principio.
    //
    // Tercera vuelta (pedido explicito del usuario, probado antes en el simulador): menos de 3
    // meses para nivel 100 jugando bien le parecia demasiado poco. Subida toda la curva x1.5
    // (60->90, 0.5->0.75, 3->4.5, misma forma) - simulado: nivel 100 en ~116 dias jugando con
    // soltura (antes 75), ~188 dias moderado (antes 124), ~442 dias muy casual (antes 293),
    // verificado que sigue siendo alcanzable con cualquier estilo de juego (nadie se queda sin
    // llegar nunca).
    //
    // Cuarta vuelta (pedido explicito del usuario, junto con el cambio de pesos de arriba): ni
    // siquiera esta curva aguantaba los nuevos pesos a largo plazo - un jugador dedicado (~150
    // especies, evoluciones y shinies reales, mas o menos un año jugando en serio) ya tocaba el
    // techo de 100 en unos pocos meses. Reescalada toda la curva x1.83 (misma forma, mismo
    // suelo/paso relativo) para que ESE escenario ("un año+ de dedicacion real") sea justo el
    // que alcanza el nivel 100 - ni una coleccion enorme (no hace falta acercarse al dex entero,
    // eso se probo y dejaba TODO lo demas sintiendose como si no avanzaras nunca), ni unos meses
    // sueltos (eso se probo tambien y llegaba a maximo demasiado pronto).
    private const val TRAINER_LVL_MARGINAL_BASE = 164.75f
    private const val TRAINER_LVL_MARGINAL_STEP = 1.373f
    private const val TRAINER_LVL_MARGINAL_FLOOR = 8.24f
    private fun cumXpTrainer(level: Int): Float {
        if (level <= 1) return 0f
        var total = 0f
        for (n in 0 until level - 1) {
            total += maxOf(TRAINER_LVL_MARGINAL_FLOOR, TRAINER_LVL_MARGINAL_BASE - TRAINER_LVL_MARGINAL_STEP * n)
        }
        return total
    }
    private fun trainerLevelOf(pool: Float): Int {
        var l = 1
        while (l < MAX_LEVEL && pool >= cumXpTrainer(l + 1)) l++
        return l
    }

    fun trainerXpPool(context: Context): Float {
        val p = prefs(context)
        val xpSuffix = ".$KEY_XP"
        val evolvedSuffix = ".$KEY_EVOLVED_AWAY"
        val seenSpecies = HashSet<String>()
        var captureCount = 0
        var levelSum = 0
        var evolutionCount = 0
        var shinyCount = 0
        for ((key, value) in p.all) {
            if (key.endsWith(xpSuffix)) {
                // [slotName] es la clave de guardado tal cual (name, o "name#shiny" - ver slot()),
                // NUNCA la especie real por si sola. BUG REAL encontrado y corregido (mismo tipo
                // que el del nivel 17 del Snivy shiny): antes se pasaba [slotName] tal cual tanto a
                // isShiny() (que en realidad busca la clave SIN sufijo "name.shiny", asi que un
                // shiny de verdad nunca se contaba aqui - ningun individuo shiny sumo NUNCA su
                // TRAINER_XP_PER_SHINY hasta ahora) como a levelOf() (que busca la curva de
                // crecimiento por nombre de especie real - con el sufijo "#shiny" nunca la
                // encontraba y caia siempre al grupo "medium" por defecto, aunque la especie real
                // fuera otro grupo). Derivar aqui mismo si es shiny (por el sufijo de la propia
                // clave, sin depender del flag legacy) y la especie real (sin el sufijo) arregla
                // los dos a la vez.
                val slotName = key.removeSuffix(xpSuffix)
                val isShinySlot = slotName.endsWith(SHINY_SLOT_SUFFIX)
                val realSpecies = if (isShinySlot) slotName.removeSuffix(SHINY_SLOT_SUFFIX) else slotName
                // captureCount YA NO cuenta las formas evolucionadas-away (ver comentario de
                // TRAINER_XP_PER_CAPTURE) - mismo filtro que levelSum/shinyCount, para que una
                // cadena de 3 fases sea UNA captura, no tres.
                if (!p.getBoolean(k(slotName, KEY_EVOLVED_AWAY), false)) {
                    if (seenSpecies.add(slotName)) captureCount++
                    val xp = when (value) { is Float -> value; is Int -> value.toFloat(); else -> 0f }
                    levelSum += levelOf(context, xp, realSpecies)
                    if (isShinySlot) shinyCount++
                }
            } else if (key.endsWith(evolvedSuffix) && value == true) {
                evolutionCount++
            }
        }
        return captureCount * TRAINER_XP_PER_CAPTURE +
            levelSum * TRAINER_XP_PER_MON_LEVEL +
            evolutionCount * TRAINER_XP_PER_EVOLUTION +
            shinyCount * TRAINER_XP_PER_SHINY
    }

    fun trainerLevel(context: Context): Int = trainerLevelOf(trainerXpPool(context))

    /** Progreso (0f..1f) del nivel de entrenador hacia el siguiente - 1f si ya esta al maximo. */
    fun trainerProgress(context: Context): Float {
        val pool = trainerXpPool(context)
        val lvl = trainerLevelOf(pool)
        if (lvl >= MAX_LEVEL) return 1f
        val a = cumXpTrainer(lvl); val b = cumXpTrainer(lvl + 1)
        return if (b > a) ((pool - a) / (b - a)).coerceIn(0f, 1f) else 0f
    }

    // ==================== NOMBRES EN ESPAÑOL: Pokemon Paradoja ====================
    // El nombre interno (name en pokedex.json) es el ingles con guiones (ej. "great-tusk") - se
    // usa tal cual para sprites/evoluciones/guardado, nunca se toca. Esto es SOLO para lo que ve
    // el jugador: los Paradoja son casi los unicos cuyo nombre ingles no se parece nada al
    // español real (Bulbapedia/WikiDex, verificado uno a uno) - el resto de la Pokedex ya se
    // reconoce igual en los dos idiomas (Pikachu, Charizard...), asi que no hace falta traducirlo.
    private val PARADOX_SPANISH_NAMES: Map<String, String> = mapOf(
        "great-tusk" to "Colmilargo", "scream-tail" to "Colagrito", "brute-bonnet" to "Furioseta",
        "flutter-mane" to "Melenaleteo", "slither-wing" to "Reptalada", "sandy-shocks" to "Pelarena",
        "roaring-moon" to "Bramaluna", "iron-treads" to "Ferrodada", "iron-bundle" to "Ferrosaco",
        "iron-hands" to "Ferropalmas", "iron-jugulis" to "Ferrocuello", "iron-moth" to "Ferropolilla",
        "iron-thorns" to "Ferropúas", "iron-valiant" to "Ferropaladín", "walking-wake" to "Ondulagua",
        "iron-leaves" to "Ferroverdor", "gouging-fire" to "Flamariete", "raging-bolt" to "Electrofuria",
        "iron-boulder" to "Ferromole", "iron-crown" to "Ferrotesta",
    )

    // Un unico caso aparte de los Paradoja: "urshifu"/"urshifu-rapid" no se reconocen por el
    // sufijo como si pasa con las formas regionales (-alola, -galar...). "Estilo Brusco"/"Estilo
    // Fluido" son los nombres oficiales reales (verificados contra varias fuentes independientes,
    // NO el "Furia Impetuosa" que tenia megas.json para el Gigamax - esa etiqueta resulto ser
    // incorrecta/no oficial al comprobarla).
    private val EXTRA_SPANISH_NAMES: Map<String, String> = mapOf(
        "urshifu" to "Urshifu Estilo Brusco",
        "urshifu-rapid" to "Urshifu Estilo Fluido",
    )

    /** Nombre a mostrar al jugador para [name]: el español real si es un Pokemon Paradoja (ver
     *  PARADOX_SPANISH_NAMES) o una de las EXTRA_SPANISH_NAMES, o el propio [name] tal cual para
     *  el resto (que ya se reconoce igual en ambos idiomas). NUNCA usar esto para sprites/
     *  evoluciones/guardado - solo para texto visible. */
    fun displayLabel(name: String): String {
        val key = name.lowercase()
        return PARADOX_SPANISH_NAMES[key] ?: EXTRA_SPANISH_NAMES[key] ?: name
    }

    fun unlockedBackgrounds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_UNLOCKED_BGS, null) ?: setOf(DEFAULT_BG)

    fun isBgUnlocked(context: Context, bg: String): Boolean = bg in unlockedBackgrounds(context)

    fun bgTokensAvailable(context: Context): Int =
        (trainerLevel(context) / TRAINER_LEVELS_PER_TOKEN - (unlockedBackgrounds(context).size - 1)).coerceAtLeast(0)

    /** Gasta un token para desbloquear [bg] (si aun no lo estaba y hay token disponible).
     *  Devuelve true si se desbloqueo de verdad. */
    fun unlockBackground(context: Context, bg: String): Boolean {
        if (isBgUnlocked(context, bg)) return false
        if (bgTokensAvailable(context) <= 0) return false
        prefs(context).edit().putStringSet(KEY_UNLOCKED_BGS, unlockedBackgrounds(context) + bg).apply()
        return true
    }

    /** Llamar SOLO al elegir starter (StarterActivity.onPick): desbloquea GRATIS (sin gastar
     *  ficha de nivel de entrenador) el/los fondo(s) a juego con el tipo de [name], y pone el
     *  primero como fondo activo. Pedido explicito del usuario: antes solo los iniciales de tipo
     *  planta "parecian" tener esto, porque el fondo por defecto (bg_meadow) YA es de tipo planta
     *  por pura coincidencia - el resto de tipos se quedaban con ese mismo fondo sin ningun
     *  sentido narrativo. Si [name] no tiene datos de tipo (no deberia pasar con un inicial real),
     *  no hace nada - se queda con el fondo por defecto de siempre. REEMPLAZA el set de
     *  desbloqueados en vez de sumarle el de siempre (bg_meadow): ese "por defecto" nunca se
     *  gano de verdad, era solo el valor de respaldo de un set vacio (ver unlockedBackgrounds) -
     *  bug real reportado por el usuario, dejaba 2 fondos desbloqueados (planta + el del tipo
     *  real) en vez de solo el que corresponde. Seguro reemplazar sin mas: esta funcion solo se
     *  llama una vez, al elegir starter, antes de que exista ninguna ficha ganada de verdad. */
    fun unlockStarterBackground(context: Context, name: String) {
        val suggested = suggestedBackgrounds(context, name, shiny = false)
        if (suggested.isEmpty()) return
        prefs(context).edit()
            .putStringSet(KEY_UNLOCKED_BGS, suggested.toSet())
            .putString(KEY_BG, suggested.first())
            .apply()
    }

    // ==================== FONDO A JUEGO: BENEFICIOS ====================
    // Si el fondo puesto beneficia al tipo del Pokemon activo: mas XP (tiempo Y acciones, ver
    // loadWithDecay/tryApplyAction) y mas probabilidad de shiny al poner un huevo (ver
    // maybeLayEgg, evaluado contra el tipo de la CRIA - baseFormOf - no el de quien lo pone).
    // x2 si el fondo puesto es el de ALGUNO de los tipos de name, x1 si no. Ya NO hay un tramo x4
    // para biTipos: antes de la ronda de "un fondo por tipo" (ver BG_FOR_TYPE) algunos fondos
    // podian pertenecer a dos tipos a la vez, y un biTipo con suerte encontraba un fondo que le
    // servia a ambos. Con BG_FOR_TYPE como biyeccion (cada fondo, exactamente un tipo, sin
    // repetir - pedido explicito del usuario) eso ya no puede pasar nunca por construccion:
    // decision consciente de aceptar el tope en x2 en vez de reintroducir fondos compartidos
    // entre tipos. Deliberadamente NO toca decaimiento/boost/umbral de las barras: esa economia
    // ya esta calibrada aparte (ver notas de la ronda de simulacion) y no hace falta arriesgarla
    // por esto.
    private fun bgMatches(context: Context, name: String): Boolean {
        val bg = currentBg(context)
        return typesOf(context, name).any { t -> BG_FOR_TYPE[t]?.contains(bg) == true }
    }

    private fun bgMatchMultiplier(context: Context, name: String): Float =
        if (bgMatches(context, name)) 2f else 1f

    private fun xpMultiplier(context: Context, name: String): Float = bgMatchMultiplier(context, name)

    // ==================== VITRINA DE MECANICAS: explicacion + todos los sprites posibles ====================
    // Para el boton "Como funciona" de la ficha (ver MainActivity.showMechanicShowcaseDialog):
    // reune, para cualquier especie con mecanica propia, su nombre oficial (el de la habilidad
    // real si la mecanica viene de una, verificado en WikiDex), una explicacion corta, y la lista
    // COMPLETA de sprites que puede llegar a tener (no solo el actual) - cada uno marcado como
    // "obtenido" o no, para que funcione como una Pokedex en miniatura DENTRO del Pokemon (pedido
    // explicito del usuario): lo no visto todavia se pinta en gris en el dialogo (ver
    // MainActivity.showMechanicShowcaseDialog), igual que lo no conseguido en la rejilla general.
    // Null si la especie no tiene ninguna mecanica propia (megas y formas regionales tienen su
    // propia UI aparte).
    data class MechanicForm(val spriteKey: String, val label: String, val obtained: Boolean)
    data class MechanicShowcase(val title: String, val desc: String, val forms: List<MechanicForm>)

    // Formas "binarias/automaticas" (no ligadas a un sorteo de huevo ni a nivel): se registran la
    // primera vez que displaySpriteName de verdad las enseña (ver markMechanicFormSeen mas abajo),
    // asi que sirven de registro real de lo que ha llegado a pasar, no de lo que "podria" pasar.
    private val MECHANIC_TRACKED_SPECIES = setOf(
        "darmanitan", "cramorant", "keldeo", "cherrim", "mimikyu", "eiscue", "wishiwashi",
        "morpeko", "palafin", "aegislash", "terapagos", "dudunsparce", "maushold", "solgaleo", "lunala"
    )
    private const val KEY_MECHANIC_SEEN = "mechanic_forms_seen"

    /** Marca [spriteKey] como ya visto de verdad para la especie [name] - llamar SIEMPRE que
     *  displaySpriteName calcule un sprite para una especie de MECHANIC_TRACKED_SPECIES o de
     *  GENDER_SPECIES, con el resultado que sea (incluido el "por defecto"), para que el registro
     *  sea fiel a lo que de verdad ha pasado. */
    fun markMechanicFormSeen(context: Context, name: String, spriteKey: String) {
        val key = k(name, KEY_MECHANIC_SEEN)
        val p = prefs(context)
        val current = p.getStringSet(key, emptySet()) ?: emptySet()
        if (spriteKey in current) return
        p.edit().putStringSet(key, HashSet(current).apply { add(spriteKey) }).commit()
    }

    fun mechanicFormsSeen(context: Context, name: String): Set<String> =
        prefs(context).getStringSet(k(name, KEY_MECHANIC_SEEN), emptySet()) ?: emptySet()

    // Titulos oficiales de la habilidad real que causa la mecanica (verificados en WikiDex) para
    // las especies decorativas SIN un branch propio mas abajo - el resto (Necrozma, Gourgeist,
    // Gastrodon, Floette, Florges, Tauros, Minior) tiene su propio titulo en su propio branch.
    private val DECORATIVE_TITLES: Map<String, String> = mapOf(
        "furfrou" to "Cortes de Furfrou", "basculin" to "Formas de Basculin",
        "squawkabilly" to "Plumaje de Squawkabilly", "pumpkaboo" to "Tamaño de Pumpkaboo",
        "castform" to "Pronóstico", "deoxys" to "Formas de Deoxys",
        "kyurem" to "Fusión de Kyurem", "calyrex" to "Fusión de Calyrex",
        "gimmighoul" to "Formas de Gimmighoul", "shellos" to "Mar de Shellos",
        "flabebe" to "Color de Flabébé",
    )

    /** La vitrina de mecanica "propia" de [name] (genero, color, Terastal, Rotom...) - la que
     *  decida el "when" de mas abajo segun especie (ver speciesMechanics, que la combina con
     *  megaMechanicShowcase y la clasifica en genero/formas).
     *  La Megaevolucion/Gigantamax "generica" ya NO vive aqui (ver megaMechanicShowcase) - antes
     *  estaba en el "else" de este mismo "when", asi que una especie con OTRA rama ya asignada
     *  (ej. Raichu, que cae en "in GENDER_SPECIES") nunca llegaba a verla, ocultando su
     *  Megaevolucion por completo aunque la tuviera - bug real reportado por el usuario. */
    private fun mechanicShowcase(context: Context, name: String): MechanicShowcase? {
        fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
        val n = name.lowercase()
        val ownedSpecies = isUnlocked(context, n)
        // Nivel actual para las formas por nivel (Rotom/Oricorio/Ogerpon/Genesect/Meloetta): 0 si
        // ni siquiera se tiene la especie, para que ninguna forma cuente como obtenida todavia.
        // levelForPokemon por defecto mira el individuo NORMAL - si solo tienes el SHINY de esta
        // especie (o el shiny es el que de verdad llego al nivel), preferredShinyFor resuelve cual
        // de los dos mirar en vez de asumir siempre el normal (bug real: formas de Rotom/Oricorio/
        // Genesect/Meloetta seguian saliendo bloqueadas en la vitrina aunque tu shiny ya tuviera
        // el nivel).
        val currentLevel = if (ownedSpecies) (levelForPokemon(context, n, preferredShinyFor(context, n)) ?: 1) else 0
        val seen = mechanicFormsSeen(context, n)
        fun unlockableShowcase(title: String) = MechanicShowcase(
            title,
            "El tipo cambia de verdad. Se desbloquea por nivel; entre las desbloqueadas, cambias libremente.",
            unlockableFormOptions(n).map { MechanicForm(unlockableSpriteKey(n, it.key), "${it.label} · nv.${it.unlockLevel}", currentLevel >= it.unlockLevel) }
        )
        fun binaryShowcase(title: String, desc: String, offKey: String, offLabel: String, onKey: String, onLabel: String) = MechanicShowcase(
            title, desc,
            listOf(MechanicForm(offKey, offLabel, offKey in seen), MechanicForm(onKey, onLabel, onKey in seen))
        )
        return when (n) {
            "rotom" -> MechanicShowcase(
                "Aparatos de Rotom",
                "Se desbloquea por nivel; entre los desbloqueados, cambias libremente.",
                ROTOM_FORMS.map { MechanicForm(rotomSpriteKey(it.form), "${it.label} · nv.${it.unlockLevel}", currentLevel >= it.unlockLevel) }
            )
            "oricorio" -> unlockableShowcase("Estilos de Oricorio")
            "ogerpon" -> unlockableShowcase("Máscaras de Ogerpon")
            "genesect" -> unlockableShowcase("Cartuchos de Genesect")
            "meloetta" -> unlockableShowcase("Formas de Meloetta")
            "burmy" -> MechanicShowcase(
                "Manto de Burmy",
                "El manto cambia solo según el fondo puesto.",
                listOf("plant" to "Manto Vegetal", "sandy" to "Manto Arenoso", "trash" to "Manto Basura")
                    .map { (cloak, label) -> MechanicForm(burmySpriteKey(cloak), label, burmySpriteKey(cloak) in seen) }
            )
            "wormadam" -> MechanicShowcase(
                "Manto de Wormadam",
                "Manto fijo desde que evoluciona de Burmy (el que tuviera en ese momento).",
                listOf("plant" to "Manto Vegetal", "sandy" to "Manto Arenoso", "trash" to "Manto Basura")
                    .map { (cloak, label) -> MechanicForm(wormadamSpriteKey(cloak), label, wormadamSpriteKey(cloak) in seen) }
            )
            "darmanitan" -> binaryShowcase(
                "Modo Zen",
                "Automático según la vida: <50% Modo Zen, ≥50% Estándar.",
                "darmanitan", "Modo Estándar (vida ≥ 50%)", "darmanitan-zen", "Modo Zen (vida < 50%)"
            )
            // Necrozma/Kyurem/Calyrex: la fusion en si vive en el sistema de Megaevolucion (ver
            // FUSION_PARTNERS/canMegaEvolve, con su propio boton y mensaje de "que falta" en la
            // ficha) - aqui solo se enseña a modo de vitrina/registro, con la forma base y cada
            // fusion marcada como obtenida solo si de verdad se ha activado alguna vez (ver
            // markMechanicFormSeen en activateMega).
            "necrozma" -> MechanicShowcase(
                "Fusión de Necrozma",
                "Se fusiona con Solgaleo o Lunala (hay que tenerlos conseguidos). Con los dos, fusión en Necrozma Ultra.",
                listOf(
                    MechanicForm("necrozma", "Forma base", ownedSpecies),
                    MechanicForm("necrozma-duskmane", "Necrozma Crepúsculo", "necrozma-duskmane" in seen),
                    MechanicForm("necrozma-dawnwings", "Necrozma Alba Nocturna", "necrozma-dawnwings" in seen),
                    MechanicForm("necrozma-ultra", "Necrozma Ultra", "necrozma-ultra" in seen),
                )
            )
            "kyurem" -> MechanicShowcase(
                "Fusión de Kyurem",
                "Se fusiona con Reshiram o Zekrom (hay que tenerlos conseguidos).",
                listOf(
                    MechanicForm("kyurem", "Forma base", ownedSpecies),
                    MechanicForm("kyurem-white", "Kyurem Blanco", "kyurem-white" in seen),
                    MechanicForm("kyurem-black", "Kyurem Negro", "kyurem-black" in seen),
                )
            )
            "calyrex" -> MechanicShowcase(
                "Fusión de Calyrex",
                "Se fusiona con Glastrier o Spectrier (hay que tenerlos conseguidos).",
                listOf(
                    MechanicForm("calyrex", "Forma base", ownedSpecies),
                    MechanicForm("calyrex-ice", "Calyrex Jinete de Hielo", "calyrex-ice" in seen),
                    MechanicForm("calyrex-shadow", "Calyrex Jinete Sombrío", "calyrex-shadow" in seen),
                )
            )
            "gourgeist" -> MechanicShowcase(
                "Tamaño de Gourgeist",
                "Sorteado al nacer del huevo (compartido con Pumpkaboo); no se repite hasta tener los 4.",
                decorativeFormsOptions("pumpkaboo").map { MechanicForm(gourgeistSpriteKey(it), cap(it), it in decorativeFormsOwned(context, "pumpkaboo")) }
            )
            "gastrodon" -> MechanicShowcase(
                "Mar de Gastrodon",
                "Sorteado al nacer del huevo (compartido con Shellos).",
                decorativeFormsOptions("shellos").map {
                    MechanicForm(if (it == "east") "gastrodon-east" else "gastrodon", cap(it), it in decorativeFormsOwned(context, "shellos"))
                }
            )
            "floette" -> MechanicShowcase(
                "Color de Floette",
                "Sorteado al nacer del huevo (compartido con Flabébé/Florges).",
                decorativeFormsOptions("flabebe").map { form ->
                    MechanicForm("floette" + (form.takeIf { it != "red" }?.let { "-$it" } ?: ""), if (form == "eternal") "Flor Eterna" else cap(form), form in decorativeFormsOwned(context, "flabebe"))
                }
            )
            "florges" -> MechanicShowcase(
                "Color de Florges",
                "Sorteado al nacer del huevo (compartido con Flabébé/Floette).",
                decorativeFormsOptions("flabebe").map { form ->
                    MechanicForm("florges" + (form.takeIf { it != "red" }?.let { "-$it" } ?: ""), if (form == "eternal") "Flor Eterna" else cap(form), form in decorativeFormsOwned(context, "flabebe"))
                }
            )
            "tauros" -> MechanicShowcase(
                "Razas de Tauros",
                "Sorteada al nacer del huevo; no se repite hasta tener las 3.",
                decorativeFormsOptions("tauros").map { MechanicForm(taurosSpriteKey(it), cap(it), it in decorativeFormsOwned(context, "tauros")) }
            )
            "minior" -> MechanicShowcase(
                "Escudo Limitado",
                "Núcleo sorteado al nacer del huevo; solo visible con vida <50%.",
                listOf(MechanicForm("minior", "Meteorito cerrado (vida ≥ 50%)", ownedSpecies)) +
                    decorativeFormsOptions("minior").map { MechanicForm("minior-$it", "Núcleo ${cap(it)} (vida < 50%)", it in decorativeFormsOwned(context, "minior")) }
            )
            "arceus", "silvally" -> {
                val typeLabel = mapOf(
                    "normal" to "Normal", "fighting" to "Lucha", "flying" to "Volador", "poison" to "Veneno",
                    "ground" to "Tierra", "rock" to "Roca", "bug" to "Bicho", "ghost" to "Fantasma",
                    "steel" to "Acero", "fire" to "Fuego", "water" to "Agua", "grass" to "Planta",
                    "electric" to "Eléctrico", "psychic" to "Psíquico", "ice" to "Hielo", "dragon" to "Dragón",
                    "dark" to "Siniestro", "fairy" to "Hada"
                )
                MechanicShowcase(
                    if (n == "arceus") "Placas de Arceus" else "Discos de Silvally",
                    "El tipo cambia de verdad (18 formas). Sorteado al nacer del huevo; no se repite hasta tenerlas todas.",
                    decorativeFormsOptions(n).map { form ->
                        MechanicForm(decorativeSpriteKey(n, form), typeLabel[form] ?: cap(form), form in decorativeFormsOwned(context, n))
                    }
                )
            }
            "vivillon" -> {
                val patternLabel = mapOf(
                    "meadow" to "Pradera", "archipelago" to "Archipiélago", "continental" to "Continental",
                    "elegant" to "Elegante", "garden" to "Jardín", "highplains" to "Meseta",
                    "icysnow" to "Nieve", "jungle" to "Selva", "marine" to "Marino", "modern" to "Moderno",
                    "monsoon" to "Monzón", "ocean" to "Océano", "polar" to "Polar", "river" to "Río",
                    "sandstorm" to "Tormenta de arena", "savanna" to "Sabana", "sun" to "Sol",
                    "tundra" to "Tundra", "fancy" to "Fantasía", "pokeball" to "Poké Ball"
                )
                MechanicShowcase(
                    "Patrones de Vivillon",
                    "Puramente decorativo (tipo siempre Bicho/Volador). Sorteado al nacer del huevo; no se repite hasta tenerlos todos.",
                    decorativeFormsOptions("vivillon").map { form ->
                        MechanicForm(decorativeSpriteKey("vivillon", form), patternLabel[form] ?: cap(form), form in decorativeFormsOwned(context, "vivillon"))
                    }
                )
            }
            "pikachu" -> {
                val label = mapOf(
                    "regular" to "Sin disfraz", "cosplay" to "Cosplay (base)", "phd" to "Doctorado",
                    "rockstar" to "Estrella del Rock", "libre" to "Libre", "belle" to "Bella",
                    "popstar" to "Estrella del Pop", "original" to "Gorra Original", "hoenn" to "Gorra Hoenn",
                    "sinnoh" to "Gorra Sinnoh", "unova" to "Gorra Teselia", "kalos" to "Gorra Kalos",
                    "alola" to "Gorra Alola", "partner" to "Gorra Compañero", "world" to "Gorra Mundial"
                )
                // Identificacion de que gorra es cual: sin verificar contra referencia oficial
                // (a diferencia de Alcremie, que ya se confirmo con una tabla real) - pendiente,
                // pero esto es nota interna, NUNCA texto de cara al jugador (pedido explicito).
                MechanicShowcase(
                    "Disfraces de Pikachu",
                    "Puramente decorativo. Sorteado al nacer del huevo.",
                    decorativeFormsOptions("pikachu").map { form ->
                        MechanicForm(decorativeSpriteKey("pikachu", form), label[form] ?: cap(form), form in decorativeFormsOwned(context, "pikachu"))
                    }
                )
            }
            "alcremie" -> {
                val flavorLabel = mapOf(
                    "ruby" to "Crema Rubí", "rubyswirl" to "Remolino Rubí", "matcha" to "Crema Matcha",
                    "mint" to "Crema Menta", "lemon" to "Crema Limón", "vanilla" to "Crema Vainilla",
                    "salted" to "Crema Salada", "caramel" to "Remolino Caramelo", "rainbow" to "Remolino Arcoíris"
                )
                val sweetLabel = mapOf(
                    "strawberry" to "Fresa", "berry" to "Baya", "love" to "Amor", "star" to "Estrella",
                    "clover" to "Trébol", "flower" to "Flor", "ribbon" to "Lazo"
                )
                MechanicShowcase(
                    "Sabores de Alcremie",
                    "Puramente decorativo (9 sabores × 7 decoraciones). Sorteado al nacer del huevo.",
                    decorativeFormsOptions("alcremie").map { form ->
                        val parts = form.split("-")
                        val label = "${flavorLabel[parts[0]] ?: parts[0]} · ${sweetLabel.getOrElse(parts.getOrNull(1) ?: "") { parts.getOrNull(1) ?: "" }}"
                        MechanicForm(decorativeSpriteKey("alcremie", form), label, form in decorativeFormsOwned(context, "alcremie"))
                    }
                )
            }
            "unown" -> MechanicShowcase(
                "Letras de Unown",
                "Sorteado al nacer del huevo (28 formas); no se repite hasta tenerlas todas. Orden de letras sin confirmar al 100%.",
                decorativeFormsOptions("unown").map { form ->
                    val label = when (form) { "em" -> "!"; "qm" -> "?"; else -> form.uppercase() }
                    MechanicForm(decorativeSpriteKey("unown", form), label, form in decorativeFormsOwned(context, "unown"))
                }
            )
            in DECORATIVE_FORMS.keys -> MechanicShowcase(
                DECORATIVE_TITLES[n] ?: cap(n),
                "Sorteado al nacer del huevo; no se repite hasta tenerlas todas.",
                decorativeFormsOptions(n).map { MechanicForm(decorativeSpriteKey(n, it), cap(it), it in decorativeFormsOwned(context, n)) }
            )
            "cramorant" -> MechanicShowcase(
                "Tragamisil",
                "Al darle de comer, a veces avanza un paso (Normal → Tragando → Atragantado); si pasan 3h sin avanzar, vuelve a Normal.",
                listOf(
                    MechanicForm("cramorant", "Normal", "cramorant" in seen),
                    MechanicForm("cramorant-gulping", "Tragando algo", "cramorant-gulping" in seen),
                    MechanicForm("cramorant-gorging", "¡Atragantado!", "cramorant-gorging" in seen)
                )
            )
            "keldeo" -> binaryShowcase(
                "Forma Resuelta",
                "Pasa a Forma Resuelta de forma permanente al llegar a nivel $KELDEO_RESOLUTE_LEVEL.",
                "keldeo", "Forma Ordinaria", "keldeo-resolute", "Forma Resuelta"
            )
            "cherrim" -> binaryShowcase(
                "Don Floral",
                "Sigue en vivo el fondo puesto: Forma Soleada con fondos de sol fuerte, Nublada con el resto.",
                "cherrim", "Forma Nublada", "cherrim-sunshine", "Forma Soleada"
            )
            "mimikyu" -> binaryShowcase(
                "Disfraz",
                "Automático según la vida: <50% Forma Destrozada.",
                "mimikyu", "Forma Disfrazada (vida ≥ 50%)", "mimikyu-busted", "Forma Destrozada (vida < 50%)"
            )
            "eiscue" -> binaryShowcase(
                "Cara de Hielo",
                "Automático según la vida: <50% Cara Noice.",
                "eiscue", "Cara Hielo (vida ≥ 50%)", "eiscue-noice", "Cara Noice (vida < 50%)"
            )
            "wishiwashi" -> binaryShowcase(
                "Banco",
                "A partir de nivel 20, con la vida por encima del 25% pasa a Forma Banco.",
                "wishiwashi", "Forma Solitaria", "wishiwashi-school", "Forma Banco (nv.20+, vida > 25%)"
            )
            "morpeko" -> binaryShowcase(
                "Mutapetito",
                "Alterna solo cada 2 horas.",
                "morpeko", "Modo Hambriento", "morpeko-fullbelly", "Modo Barriga Llena"
            )
            "palafin" -> binaryShowcase(
                "Cambio Heroico",
                "Pasa a Forma Héroe de forma permanente la primera vez que vuelves a elegirlo como activo tras haber cuidado a otro.",
                "palafin", "Forma Cero", "palafin-hero", "Forma Héroe"
            )
            "aegislash" -> binaryShowcase(
                "Cambio Táctico",
                "Alterna de postura con cada acción que aceptes (comer, lavar, acariciar...).",
                "aegislash", "Postura Escudo", "aegislash-blade", "Postura Hoja"
            )
            "terapagos" -> binaryShowcase(
                "Forma Terastal",
                "Pasa a Forma Terastal de forma permanente al llegar a nivel $TERAPAGOS_TERASTAL_LEVEL.",
                "terapagos", "Forma Normal", "terapagos-terastal", "Forma Terastal"
            )
            "dudunsparce" -> binaryShowcase(
                "Segmentos de Dudunsparce",
                "1% de salir con 3 segmentos al evolucionar - fijo para siempre.",
                "dudunsparce", "2 segmentos (99%)", "dudunsparce-three", "3 segmentos (1%)"
            )
            "maushold" -> binaryShowcase(
                "Familia de Maushold",
                "1% de salir Familia de 3 al evolucionar - fijo para siempre.",
                "maushold", "Familia de 4 (99%)", "maushold-three", "Familia de 3 (1%)"
            )
            "solgaleo" -> binaryShowcase(
                "Fase Sol Radiante",
                "Al aceptar cualquier acción, brilla un momento en Fase Sol Radiante antes de volver a la normalidad.",
                "solgaleo", "Forma normal", "solgaleo-radiant", "Fase Sol Radiante"
            )
            "lunala" -> binaryShowcase(
                "Fase Luna Llena",
                "Al aceptar cualquier acción, brilla un momento en Fase Luna Llena antes de volver a la normalidad.",
                "lunala", "Forma normal", "lunala-fullmoon", "Fase Luna Llena"
            )
            in GENDER_SPECIES -> binaryShowcase(
                "Diferencias de Género",
                "Sorteado al conseguirlo; se conserva para siempre, aunque evolucione.",
                n, "Macho", genderSpriteKey(n, "female"), "Hembra"
            )
            else -> null
        }
    }

    /** Vitrina de Megaevolucion/Gigantamax "generica" de [name] - null si esa especie no tiene
     *  ninguna opcion (ver megaOptionsFor). Separada de mechanicShowcase (antes vivia en su
     *  "else") para que una especie con OTRA mecanica propia (genero, color, Terastal...) pueda
     *  enseñar LAS DOS a la vez en vez de que la de mas arriba tape esta por completo - ver
     *  speciesMechanics, que junta ambas. Necrozma/Kyurem/Calyrex quedan fuera a proposito (su
     *  propia rama del "when" YA ES su vitrina de fusion, con texto explicando que se resuelve
     *  con Megaevolucion - no hay una segunda mecanica distinta detras, serian dos vitrinas
     *  diciendo lo mismo). */
    private fun megaMechanicShowcase(context: Context, name: String): MechanicShowcase? {
        val n = name.lowercase()
        if (n in setOf("necrozma", "kyurem", "calyrex")) return null
        fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
        val ownedSpecies = isUnlocked(context, n)
        val seen = mechanicFormsSeen(context, n)
        // Megaevolucion "normal" (y formas especiales tratadas igual: Primigenias, Origen,
        // Corona, Formas Tridente...) - se activa desde el boton de Megaevolucion de la ficha,
        // no desde aqui; esta vitrina es solo para ver TODAS las formas posibles de un vistazo,
        // marcadas en gris hasta activarlas alguna vez de verdad (ver markMechanicFormSeen en
        // activateMega, que ya se llama para CUALQUIER Mega).
        val megaOpts = megaOptionsFor(context, n)
        if (megaOpts.isEmpty()) return null
        // Gigantamax reutiliza el mismo mecanismo/vitrina que Megaevolucion (ver
        // canMegaEvolve/activateMega) - solo cambia el texto cuando TODAS las opciones de
        // esta especie son Gigantamax (name acabado en "-gmax"); las pocas que mezclan
        // Mega y Gigantamax a la vez (Blastoise/Venusaur/Gengar) se quedan con el texto
        // generico de Mega.
        val hasGmax = megaOpts.any { it.name.endsWith("-gmax") }
        val hasMega = megaOpts.any { !it.name.endsWith("-gmax") }
        val allGmax = hasGmax && !hasMega
        val noun = if (allGmax) "Gigantamax" else "Megaevolución"
        // Blastoise/Venusaur/Gengar/Charizard mezclan Mega Y Gigantamax a la vez, cada uno con
        // su propio nivel/duracion/enfriamiento (ver unlockLevelForMegaOption) - se describen
        // los dos por separado en vez de un solo numero que solo valdria para una de las dos.
        val gmaxDesc = "Gigantamax: ${GMAX_DURATION_MS / 3_600_000L}h activo, ${GMAX_COOLDOWN_MS / 3_600_000L}h de enfriamiento (nivel $GIGANTAMAX_UNLOCK_LEVEL)."
        val megaDesc = "Megaevolución: ${MEGA_DURATION_MS / 3_600_000L}h activo, ${MEGA_COOLDOWN_MS / 3_600_000L}h de enfriamiento (nivel $MEGA_UNLOCK_LEVEL)."
        val desc = when {
            hasGmax && hasMega -> "$gmaxDesc $megaDesc En gris hasta activarla alguna vez."
            hasGmax -> "$gmaxDesc En gris hasta activarla alguna vez."
            else -> "$megaDesc En gris hasta activarla alguna vez."
        }
        return MechanicShowcase(
            "$noun de ${cap(n)}",
            desc,
            listOf(MechanicForm(n, "Forma base", ownedSpecies)) +
                megaOpts.map { opt -> MechanicForm(opt.name, opt.label, opt.name in seen) }
        )
    }

    /** Las 3 vitrinas de mecanica de [name], YA clasificadas por categoria (pedido explicito del
     *  usuario: se muestran integradas en el dialogo de cadena evolutiva, en un orden fijo -
     *  shiny, genero, formas, mega/gigantamax - en vez de un boton suelto por cada una). El
     *  "when" de mechanicShowcase solo puede caer en UNA rama por especie, asi que basta mirar si
     *  esa especie esta en GENDER_SPECIES para saber si su resultado (si lo hay) es el de genero o
     *  el de "otra forma" - nunca los dos a la vez. megaMechanicShowcase es siempre independiente
     *  (puede convivir con cualquiera de los otros dos, ej. Raichu: genero + Mega). */
    data class SpeciesMechanics(
        val genderShowcase: MechanicShowcase?,
        val formShowcase: MechanicShowcase?,
        val megaShowcase: MechanicShowcase?
    )

    fun speciesMechanics(context: Context, name: String): SpeciesMechanics {
        val n = name.lowercase()
        val primary = mechanicShowcase(context, n)
        val isGender = n in GENDER_SPECIES
        return SpeciesMechanics(
            genderShowcase = if (isGender) primary else null,
            formShowcase = if (!isGender) primary else null,
            megaShowcase = megaMechanicShowcase(context, n)
        )
    }

}
