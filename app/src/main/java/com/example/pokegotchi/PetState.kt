package com.example.pokegotchi

import android.content.Context
import org.json.JSONArray
import kotlin.random.Random

/** getFloat que no revienta si la clave quedo guardada con otro tipo (un Int/Long de una version
 *  vieja o de un editor de preferencias): SharedPreferences lanza ClassCastException y, como estas
 *  lecturas estan en el camino de TODA la interfaz, un solo valor mal tipado dejaba la app sin
 *  poder abrir la ficha, la mazmorra o los caramelos (ver QaRobust). NaN/infinito tambien se
 *  tratan como "sin valor" para no propagarse a las barras. */
internal fun android.content.SharedPreferences.getFloatSafe(key: String, def: Float): Float {
    val v = try {
        getFloat(key, def)
    } catch (e: ClassCastException) {
        try { getInt(key, 0).toFloat() } catch (e2: ClassCastException) {
            try { getLong(key, 0L).toFloat() } catch (e3: ClassCastException) {
                try { getString(key, null)?.toFloatOrNull() ?: def } catch (e4: ClassCastException) { def }
            }
        }
    }
    return if (v.isNaN() || v.isInfinite()) def else v
}

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

    private fun bedtimeHour(context: Context): Float = prefs(context).getFloatSafe(KEY_SLEEP_BEDTIME_H, DEFAULT_BEDTIME_H)
    private fun wakeHour(context: Context): Float = prefs(context).getFloatSafe(KEY_SLEEP_WAKE_H, DEFAULT_WAKE_H)

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

    /** Arranque de las 3 barras para un individuo NUEVO (ver setPokemon/rollFreshIndividual):
     *  cada una en un punto aleatorio entre 50 y 100 de por si (ya existia, evita el "100% en
     *  las 3 el dia 1" artificial), PERO una de las 3, elegida al azar, se fuerza por DEBAJO de
     *  su THRESH_MIN_* - pedido explicito del usuario: "quiero que los pokemon nuevos vengan con
     *  una estadistica aleatoria que requiera de atencion, para tener que atenderle nada mas
     *  tenerle". THRESH_MIN_* es el extremo MAS BAJO posible del umbral que le toque despues a
     *  esa stat (ensureThreshold sortea uno nuevo la primera vez que se consulta) - quedar por
     *  debajo de ese minimo garantiza estar por debajo de CUALQUIER umbral que le pueda tocar,
     *  sea cual sea, asi que esa stat SIEMPRE aparece como "necesita atencion" de entrada - pero
     *  SIN quedarse cerca de 0 (probado con iron-jugulis: salio con salud a 4%, demasiado bajo
     *  segun el usuario, "va bien pero no lo dejes tan bajo") - se queda entre el 60% y el 95%
     *  de ese minimo, siempre por debajo del umbral pero con margen de sobra antes de 0. */
    private fun rollFreshStats(): Triple<Float, Float, Float> {
        var health = 50f + Random.nextFloat() * 50f
        var hygiene = 50f + Random.nextFloat() * 50f
        var happiness = 50f + Random.nextFloat() * 50f
        val needyFactor = 0.6f + Random.nextFloat() * 0.35f
        when (Random.nextInt(3)) {
            0 -> health = THRESH_MIN_HEALTH * needyFactor
            1 -> hygiene = THRESH_MIN_HYGIENE * needyFactor
            else -> happiness = THRESH_MIN_HAPPY * needyFactor
        }
        return Triple(health, hygiene, happiness)
    }

    /** Umbral ACTUAL (por especie) de esta stat: si aun no se ha sorteado ninguno (Pokemon nuevo,
     *  o guardado de antes de este cambio), se sortea una vez y se guarda (self-healing). Se
     *  vuelve a sortear en tryApplyAction al satisfacer la accion -
     *  cada ciclo de necesidad tiene su propio umbral, no siempre el mismo. */
    private fun ensureThreshold(context: Context, n: String, key: String): Float {
        val p = prefs(context)
        val v = p.getFloatSafe(k(n, key), -1f)
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

    /** Una marca de tiempo guardada anterior a esto (1 ene 2024, antes de que existiera la app)
     *  es una clave corrupta/sin inicializar, no una fecha real. */
    private const val MIN_VALID_EPOCH_MS = 1_704_067_200_000L
    /** Tope de "tiempo sin abrir" que se acredita de golpe (XP por tiempo, guarderia): un ano. */
    private const val MAX_ABSENCE_MS = 365L * 24L * 3_600_000L

    /** [saved] si es una hora creible (ni anterior a la app ni en el futuro); si no, [now]. */
    private fun sanitizeClock(saved: Long, now: Long): Long =
        if (saved < MIN_VALID_EPOCH_MS || saved > now) now else saved

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
        // Pedido explicito del usuario: el primer regalo esta disponible DE INMEDIATO al elegir
        // el inicial (antes se hacia esperar el intervalo completo a proposito, para que no
        // apareciese de golpe en el primer arranque - invertido ahora: un jugador nuevo recibe
        // la posibilidad de un segundo Pokemon de bienvenida nada mas empezar, en vez de tener
        // que esperar 12h para el primer regalo). Los siguientes regalos siguen el ciclo normal
        // de siempre a partir de este (ver resolveOffer/dismissOffer).
        prefs(context).edit().putBoolean(KEY_STARTER_DONE, true).commit()
        forceNewOffer(context)
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

    /** ¿Tienes AHORA a [name] ([shiny] = normal o shiny, ver slot())? Es decir, un individuo vivo: uno que ya
     *  evoluciono a otra cosa sigue contando para la Pokedex pero no es un Pokemon que tengas. Es lo que marca
     *  la Pokeball del selector de evolucion y lo que avisa antes de que una evolucion pise a quien ya tienes. */
    fun ownsLiveIndividual(context: Context, name: String, shiny: Boolean): Boolean =
        hasIndividual(context, name, shiny) && !hasEvolvedAway(context, name, shiny)

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
        // Un explorador de la mazmorra no puede ser a la vez el activo del widget (la interfaz ya lo bloquea en selectPokemon): la API
        // tampoco lo permite, para que ningun otro camino deje al mismo individuo en los dos sitios (QaConflicts).
        if (DungeonState.isInParty(context, base, shiny) && !(currentPokemon(context) == base && isActiveShiny(context) == shiny)) {
            DebugLog.log(context, "setPokemon($base, shiny=$shiny) ignorado: esta en la mazmorra")
            return
        }
        // Pasar a cuidarlo a mano lo SACA de la guarderia (cobra antes lo que llevara y su reloj
        // vuelve a "ahora": el tiempo que estuvo dentro no cuenta otra vez como XP del activo).
        if (isInNursery(context, base, shiny)) nurseryRemove(context, base, shiny)
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
            // (mismo criterio ya usado en el simulador de economia) - y una de las 3, al azar,
            // arranca ya necesitando esa atencion (ver rollFreshStats).
            val (freshHealth, freshHygiene, freshHappiness) = rollFreshStats()
            e.putFloat(k(n, KEY_HEALTH), freshHealth)
                .putFloat(k(n, KEY_HYGIENE), freshHygiene)
                .putFloat(k(n, KEY_HAPPINESS), freshHappiness)
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
            // Bug real reportado por el usuario ("me aparece que tengo desbloqueados los dos
            // generos de Shinx cuando no los tengo"): mechanic_forms_seen (vitrina de genero/forma,
            // ver mechanicShowcase) es un registro ADITIVO que nunca se borraba - si esta especie ya
            // habia evolucionado antes (o el individuo se re-crea "fresco" por cualquier otro
            // motivo) y el nuevo sorteo de genero da uno DISTINTO al del individuo anterior, los dos
            // se quedaban marcados como "vistos" para siempre, aunque nunca hayan coexistido de
            // verdad. Un individuo fresco no ha visto ninguna de sus formas todavia - se limpia
            // aqui para que la vitrina reflede solo la historia de ESTE individuo en adelante.
            e.remove(k(base, KEY_MECHANIC_SEEN))
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
        return levelOf(context, p.getFloatSafe(k(n, KEY_XP), 0f), base)
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
        prefs(context).getFloatSafe(k(slot(name.lowercase(), shiny), KEY_CARE_HOURS), 0f)

    /** Suma [hours] al tiempo de cuidado del individuo [name]/[shiny] cuando pasa FUERA del widget
     *  (hoy: explorando la mazmorra, ver DungeonSimulator.accrueCareTick) - pedido explicito del
     *  usuario: "si el Pokemon esta en la mazmorra tambien suba el timer cuidandolo". No hace nada
     *  si ese individuo no existe (o ya evoluciono: el tiempo se migro a la forma nueva, no hay que
     *  crear una clave suelta) ni si es el ACTIVO del widget, que ya suma las mismas horas reales
     *  en loadWithDecay (contarlo aqui tambien lo duplicaria). */
    fun addCareHours(context: Context, name: String, shiny: Boolean, hours: Float) {
        if (hours <= 0f) return
        synchronized(statsWriteLock) {
            val base = name.lowercase()
            val p = prefs(context)
            val n = slot(base, shiny)
            if (!p.contains(k(n, KEY_XP)) || hasEvolvedAway(context, base, shiny)) return
            if (currentPokemon(context) == base && isActiveShiny(context) == shiny) return
            p.edit().putFloat(k(n, KEY_CARE_HOURS), p.getFloatSafe(k(n, KEY_CARE_HOURS), 0f) + hours).apply()
        }
    }

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_CARE_CHECK): comprueba addCareHours
     *  con individuos reales y deja el tiempo de cuidado EXACTAMENTE como estaba. Devuelve el
     *  informe. */
    fun debugCareHoursSelfTest(context: Context): String {
        val p = prefs(context)
        val sb = StringBuilder()
        val activeName = currentPokemon(context); val activeShiny = isActiveShiny(context)
        // el activo del widget: NO debe cambiar (ya suma por su cuenta en loadWithDecay)
        run {
            val key = k(slot(activeName, activeShiny), KEY_CARE_HOURS)
            val before = p.getFloatSafe(key, 0f)
            addCareHours(context, activeName, activeShiny, 1f)
            val after = p.getFloatSafe(key, 0f)
            sb.append("activo $activeName(shiny=$activeShiny): $before -> $after ${if (before == after) "OK (no se duplica)" else "FALLO"}" + System.lineSeparator())
        }
        // un individuo NO activo (el explorador de la mazmorra si hay, si no el primero que exista)
        val candidates = listOfNotNull(DungeonState.currentSpecies(context)?.let { it to DungeonState.currentShiny(context) }) +
            ownedIndividuals(context)
        val other = candidates.firstOrNull { (n, s) -> !(n == activeName && s == activeShiny) && hasIndividual(context, n, s) && !hasEvolvedAway(context, n, s) }
        if (other == null) { sb.append("no hay otro individuo para probar"); return sb.toString() }
        val key = k(slot(other.first, other.second), KEY_CARE_HOURS)
        val had = p.contains(key)
        val before = p.getFloatSafe(key, 0f)
        addCareHours(context, other.first, other.second, 1f / 60f)
        val after = p.getFloatSafe(key, 0f)
        sb.append("no activo ${other.first}(shiny=${other.second}): $before -> $after ${if (Math.abs((after - before) - 1f / 60f) < 1e-5f) "OK (+1 min)" else "FALLO"}" + System.lineSeparator())
        if (had) p.edit().putFloat(key, before).commit() else p.edit().remove(key).commit()
        sb.append("restaurado: ${p.getFloatSafe(key, 0f) == before}")
        return sb.toString()
    }

    fun rawStats(context: Context, name: String, shiny: Boolean = false): Stats? {
        val n = slot(name.lowercase(), shiny)
        val p = prefs(context)
        if (!p.contains(k(n, KEY_XP))) return null
        // Fuera de rango (un valor corrupto o guardado a mano) se recorta: las barras valen 0..100 y la XP no es negativa.
        return Stats(
            health = p.getFloatSafe(k(n, KEY_HEALTH), 100f).coerceIn(0f, 100f),
            hygiene = p.getFloatSafe(k(n, KEY_HYGIENE), 100f).coerceIn(0f, 100f),
            happiness = p.getFloatSafe(k(n, KEY_HAPPINESS), 100f).coerceIn(0f, 100f),
            xp = p.getFloatSafe(k(n, KEY_XP), 0f).coerceAtLeast(0f)
        )
    }

    /** Migra el estado antiguo (compartido, sin prefijo) al de pikachu, una sola vez. */
    private fun migrate(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("migrated_v2", false)) return
        val e = p.edit().putBoolean("migrated_v2", true)
        if (p.contains(KEY_XP) && !p.contains(k("pikachu", KEY_XP))) {
            val oldXp = try { p.getFloatSafe(KEY_XP, 0f) } catch (ex: Exception) { p.getInt(KEY_XP, 0).toFloat() }
            e.putFloat(k("pikachu", KEY_HEALTH), p.getFloatSafe(KEY_HEALTH, 100f))
                .putFloat(k("pikachu", KEY_HYGIENE), p.getFloatSafe(KEY_HYGIENE, 100f))
                .putFloat(k("pikachu", KEY_HAPPINESS), p.getFloatSafe(KEY_HAPPINESS, 100f))
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
        // Un reloj guardado imposible (0 / 1970 por una clave corrupta, o en el futuro por un
        // movil con la hora adelantada y luego corregida) NO es "una ausencia de 55 anos": se
        // trata como "ahora" (QaRobust: 1970 regalaba 19,9 M XP de golpe). Una ausencia real,
        // por larga que sea, se respeta hasta un tope de un ano.
        val last0 = sanitizeClock(p.getLong(k(n, KEY_LAST), now), now)
        val hours = (now - last0).coerceIn(0L, MAX_ABSENCE_MS) / 3_600_000f   // para xp (tiempo real)
        val last = last0.coerceAtLeast(now - 48L * 3_600_000L)            // integrar decaimiento max 48h

        // Dentro de la "gracia" (compruebas el Pokemon con cierta frecuencia) el decaimiento
        // pasivo no baja del suelo. Solo si lo abandonas mas tiempo que eso puede llegar a 0.
        val floorNow = if (hours < FLOOR_GRACE_H) FLOOR else 0f
        val (mHealth, mHappy, mHygiene) = personalityMultipliers(context, species)
        val bedH = bedtimeHour(context); val wakeH = wakeHour(context)
        val health = (p.getFloatSafe(k(n, KEY_HEALTH), 100f) - decayOver(last, now, HEALTH_DAY*mHealth, HEALTH_NIGHT*mHealth, bedH, wakeH)).coerceIn(floorNow, 100f)
        val hygiene = (p.getFloatSafe(k(n, KEY_HYGIENE), 100f) - decayOver(last, now, HYGIENE_DAY*mHygiene, HYGIENE_NIGHT*mHygiene, bedH, wakeH)).coerceIn(floorNow, 100f)
        val happiness = (p.getFloatSafe(k(n, KEY_HAPPINESS), 100f) - decayOver(last, now, HAPPY_DAY*mHappy, HAPPY_NIGHT*mHappy, bedH, wakeH)).coerceIn(floorNow, 100f)
        val xpStored = try {
            p.getFloatSafe(k(n, KEY_XP), 0f)
        } catch (e: ClassCastException) {
            p.getInt(k(n, KEY_XP), 0).toFloat()
        }
        val xpRaw = xpStored + TIME_XP_PER_H * hours * xpMultiplier(context, species)   // el nivel sube con el tiempo
        // Al nivel 100 ya no cabe mas: lo que sobra se guarda como XP sobrante (se convierte en caramelos, ver bankOverflowXp).
        val (xp, xpOverflow) = capXpAtMaxLevel(context, species, xpStored, xpRaw)
        bankOverflowXp(context, xpOverflow)

        val stats = Stats(health, hygiene, happiness, xp)
        save(context, stats, now)
        p.edit().putFloat(k(n, KEY_CARE_HOURS), p.getFloatSafe(k(n, KEY_CARE_HOURS), 0f) + hours).apply()
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
    data class ActionResult(val applied: Boolean, val leveledUp: Boolean, val xpGained: Float = 0f)

    // Cerrojo compartido para el patron leer-sumar-escribir de las stats de un individuo (XP
    // sobre todo) - pedido explicito del usuario al auditar si la mazmorra podia aplicar XP real
    // (DungeonSimulator.APPLY_REAL_CANDY_XP): sin esto, dar de comer/acariciar/lavar al compañero
    // activo (aqui) Y que la mazmorra le otorgue XP real (grantXpToIndividual) A LA VEZ - si el
    // explorador es el MISMO individuo que el compañero activo, algo que el propio diseño permite
    // explicitamente - podian pisarse: los dos leen el XP viejo, cada uno suma el suyo, el que
    // escribe el segundo tira el primero (lost update clasico, sin ningun aviso). El caso real mas
    // probable: catchUpIfStalled corriendo en su PROPIO hilo de fondo justo cuando el jugador abre
    // la app y le da de comer al mismo Pokemon que esta explorando. Un Object simple (no hace
    // falta reentrancia) protegido con synchronized en los dos sitios que hacen ese patron.
    private val statsWriteLock = Any()

    fun tryApplyAction(context: Context, action: String): ActionResult {
        var result: ActionResult
        synchronized(statsWriteLock) {
            val s = loadWithDecay(context)
            if (!isNeeded(context, s, action)) { result = ActionResult(false, false); return@synchronized }
            val levelBefore = levelOf(context, s.xp, currentPokemon(context))
            when (action) {
                PokeWidgetProvider.ACTION_FEED -> s.health = (s.health + BOOST_HEALTH).coerceIn(0f, 100f)
                PokeWidgetProvider.ACTION_PET -> s.happiness = (s.happiness + BOOST_HAPPY).coerceIn(0f, 100f)
                PokeWidgetProvider.ACTION_WASH -> s.hygiene = (s.hygiene + BOOST_HYGIENE).coerceIn(0f, 100f)
            }
            val (mHealth, mHappy, mHygiene) = personalityMultipliers(context, currentPokemon(context))
            sideEffectFor(action, mHealth, mHappy, mHygiene)?.invoke(s)
            val xpGained = ACTION_XP * xpMultiplier(context, currentPokemon(context))
            val (xpNew, xpOver) = capXpAtMaxLevel(context, currentPokemon(context), s.xp, s.xp + xpGained)
            s.xp = xpNew
            bankOverflowXp(context, xpOver)
            val leveledUp = levelOf(context, s.xp, currentPokemon(context)) > levelBefore
            // durable=true (commit sincrono): esta escritura tiene que estar en disco YA al volver
            // de onReceive. Con apply() (asincrono) existia una rendija teorica: si MIUI mata el
            // proceso justo despues de pulsar un boton (antes de que el disco recibiera la
            // escritura), una pulsacion posterior en un proceso NUEVO podia leer el estado VIEJO
            // (sin el boost aplicado) y volver a aceptar una accion que ya se habia satisfecho,
            // pareciendo una animacion "encolada". MIUI es conocido por matar procesos en segundo
            // plano de forma agresiva, asi que esto es una hipotesis real, no solo teorica.
            save(context, s, System.currentTimeMillis(), durable = true)
            result = ActionResult(true, leveledUp, xpGained)
        }
        if (!result.applied) return result
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
        return result
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

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_VERIFY_ACTION_XP): comprueba con el
     *  codigo REAL (tryApplyAction, sin simular nada) que comer/acariciar/lavar suben experiencia.
     *  Para cada accion fuerza la necesidad de esa stat a 0, la ejecuta y mide la XP antes/despues
     *  (incluye la XP pasiva de unos milisegundos, despreciable); luego repite la MISMA accion para
     *  comprobar que ahora se RECHAZA sin dar XP. Todo dentro del cerrojo de stats y, al final,
     *  restaura las 3 barras, la XP y los 3 umbrales del Pokemon activo tal como estaban - no
     *  cambia nada de la partida. Se salta las especies con mecanica propia en tryApplyAction
     *  (cambiarian de postura/fase y no se podria deshacer). Devuelve el informe (una linea por
     *  accion). */
    fun debugVerifyActionXp(context: Context): String {
        val species = currentPokemon(context)
        if (species in setOf("cramorant", "aegislash", "solgaleo", "lunala")) {
            return "no probado: $species tiene mecanica propia al aceptar una accion (se activaria de verdad)"
        }
        val report = StringBuilder()
        synchronized(statsWriteLock) {
            val p = prefs(context)
            val original = loadWithDecay(context)
            val originalBg = currentBg(context)
            val threshKeys = listOf(KEY_THRESH_HEALTH, KEY_THRESH_HYGIENE, KEY_THRESH_HAPPY)
            val originalThresholds = threshKeys.associateWith { p.getFloatSafe(k(species, it), -1f) }
            // Un fondo que NO beneficia a esta especie (para medir el x1) - el primero de la
            // lista de fondos por tipo que ninguno de sus tipos tiene asignado.
            val myTypes = typesOf(context, species)
            val nonMatchingBg = BG_FOR_TYPE.values.flatten().distinct()
                .firstOrNull { bg -> myTypes.none { t -> BG_FOR_TYPE[t]?.contains(bg) == true } }

            fun pass(label: String) {
                val mult = xpMultiplier(context, species)
                report.append("[$label] fondo=${currentBg(context)} x$mult -> esperado +${ACTION_XP * mult} por accion aceptada\n")
                for (action in listOf(PokeWidgetProvider.ACTION_FEED, PokeWidgetProvider.ACTION_PET, PokeWidgetProvider.ACTION_WASH)) {
                    val cur = loadWithDecay(context)
                    val needy = Stats(
                        health = if (action == PokeWidgetProvider.ACTION_FEED) 0f else cur.health,
                        hygiene = if (action == PokeWidgetProvider.ACTION_WASH) 0f else cur.hygiene,
                        happiness = if (action == PokeWidgetProvider.ACTION_PET) 0f else cur.happiness,
                        xp = cur.xp
                    )
                    save(context, needy, System.currentTimeMillis(), durable = true)
                    val xp0 = loadWithDecay(context).xp
                    val r1 = tryApplyAction(context, action)
                    val xp1 = loadWithDecay(context).xp
                    val r2 = tryApplyAction(context, action)   // FEED/WASH: ya no hace falta, debe rechazarse
                    val xp2 = loadWithDecay(context).xp
                    report.append("  ${action.substringAfterLast('_')}: aceptada=${r1.applied} xp +%.3f".format(xp1 - xp0))
                        .append(" | repetida: aceptada=${r2.applied} xp +%.3f\n".format(xp2 - xp1))
                }
            }

            // La subida de nivel POR UNA ACCION (la que dispara el sonido de nivel): deja la XP a 1
            // punto del siguiente nivel y comprueba que cada accion cruza el umbral y lo avisa.
            fun levelUpPass() {
                report.append("[subida de nivel por accion: XP a 1 punto del siguiente nivel]\n")
                for (action in listOf(PokeWidgetProvider.ACTION_FEED, PokeWidgetProvider.ACTION_PET, PokeWidgetProvider.ACTION_WASH)) {
                    val cur = loadWithDecay(context)
                    val lvl0 = levelOf(context, cur.xp, species)
                    val nearXp = cumXp(lvl0 + 1, context, species) - 1f
                    save(context, Stats(
                        health = if (action == PokeWidgetProvider.ACTION_FEED) 0f else cur.health,
                        hygiene = if (action == PokeWidgetProvider.ACTION_WASH) 0f else cur.hygiene,
                        happiness = if (action == PokeWidgetProvider.ACTION_PET) 0f else cur.happiness,
                        xp = nearXp
                    ), System.currentTimeMillis(), durable = true)
                    val r = tryApplyAction(context, action)
                    val lvl1 = levelOf(context, loadWithDecay(context).xp, species)
                    report.append("  ${action.substringAfterLast('_')}: aceptada=${r.applied} subioDeNivel=${r.leveledUp} nivel $lvl0 -> $lvl1\n")
                }
            }

            try {
                pass("fondo actual")
                if (nonMatchingBg != null) {
                    setBg(context, nonMatchingBg)
                    pass("fondo que NO le gusta")
                    setBg(context, originalBg)
                } else report.append("[sin fondo que no le guste: no se mide el x1]\n")
                levelUpPass()
            } finally {
                setBg(context, originalBg)
                p.edit().apply { originalThresholds.forEach { (key, v) -> if (v > 0f) putFloat(k(species, key), v) else remove(k(species, key)) } }.commit()
                save(context, original, System.currentTimeMillis(), durable = true)
            }
            report.append("restaurado: fondo=$originalBg salud %.1f higiene %.1f felicidad %.1f xp %.2f".format(original.health, original.hygiene, original.happiness, original.xp))
        }
        return report.toString()
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

    /** Suma [amount] de XP real y permanente a un individuo cualquiera (no hace falta que sea el
     *  activo del widget principal) - usado por la mazmorra al consumir un caramelo encontrado
     *  explorando. NO toca health/hygiene/happiness (la vida EN LA MAZMORRA es un concepto aparte,
     *  ver DungeonState) - parte de rawStats (congeladas, sin decaimiento, igual que
     *  debugSetIndividualStats) en vez de loadWithDecay. Devuelve el nivel resultante. */
    fun grantXpToIndividual(context: Context, name: String, shiny: Boolean, amount: Float, durable: Boolean = true): Int = synchronized(statsWriteLock) {
        val base = name.lowercase()
        val n = slot(base, shiny)
        // Solo a un individuo que EXISTE y sigue vivo como esa especie: antes, con uno inexistente,
        // se escribia una clave de XP suelta y nacia un "individuo fantasma" sin id ni genero (y la
        // especie quedaba desbloqueada, ver hasIndividual).
        if (!prefs(context).contains(k(n, KEY_XP)) || hasEvolvedAway(context, base, shiny)) {
            return@synchronized levelOf(context, 0f, base)
        }
        val current = rawStats(context, base, shiny) ?: Stats(75f, 75f, 75f, 0f)
        // Al nivel 100 ya no cabe mas XP: lo que sobra va al contador de XP sobrante (se convierte en caramelos, ver bankOverflowXp).
        val (newXp, overflow) = capXpAtMaxLevel(context, base, current.xp, (current.xp + amount).coerceAtLeast(0f))
        val editor = prefs(context).edit()
            .putFloat(k(n, KEY_XP), newXp)
            .putLong(k(n, KEY_LAST), System.currentTimeMillis())
        // durable=false (apply): el valor queda al instante en memoria y se escribe a disco en segundo plano; lo usa la puesta al dia de la
        // mazmorra, que concede miles de XP seguidas y esperaba al disco en cada una (con [flushWrites] al terminar).
        if (durable) editor.commit() else editor.apply()
        bankOverflowXp(context, overflow)
        levelOf(context, newXp, base)
    }

    /** Espera a que las escrituras pendientes (apply) lleguen a disco: cierra un lote de [grantXpToIndividual] con durable=false. */
    fun flushWrites(context: Context) {
        prefs(context).edit().commit()
    }

    /** Da [amount] de XP al Pokemon ACTIVO sin perder lo que tuviera pendiente por tiempo:
     *  [grantXpToIndividual] parte de las stats GUARDADAS y pone `last_update = ahora`, asi que sobre
     *  el activo borraba la XP por tiempo y el decaimiento acumulados desde el ultimo guardado.
     *  Aqui se liquida primero (loadWithDecay) y despues se suma, con el mismo patron que
     *  tryApplyAction. Nivel 100: no se aplica nada (applied=false) y la XP va al contador de XP sobrante. */
    fun grantXpToActive(context: Context, amount: Float): ActionResult = synchronized(statsWriteLock) {
        if (amount <= 0f) return@synchronized ActionResult(false, false, 0f)
        val species = currentPokemon(context)
        val s = loadWithDecay(context)
        val levelBefore = levelOf(context, s.xp, species)
        if (levelBefore >= MAX_LEVEL) { bankOverflowXp(context, amount); return@synchronized ActionResult(false, false, 0f) }   // ya no cabe: XP sobrante
        val (xpNew, xpOver) = capXpAtMaxLevel(context, species, s.xp, s.xp + amount)
        s.xp = xpNew
        bankOverflowXp(context, xpOver)
        val leveledUp = levelOf(context, s.xp, species) > levelBefore
        save(context, s, System.currentTimeMillis(), durable = true)
        ActionResult(true, leveledUp, amount)
    }

    // ==================== XP SOBRANTE (NIVEL 100) -> CARAMELOS ====================
    // Un Pokemon al nivel 100 ya no puede subir: su XP (por tiempo, cuidados, guarderia, mazmorra, huevos) se guarda en un contador
    // global y cada OVERFLOW_XP_PER_CANDY se convierte en un caramelo de la mochila, que se puede dar a otro Pokemon. La conversion
    // pierde valor a proposito (un caramelo vale ~5-30 XP al que lo come, ver CANDY_PCT_OF_NEXT_LEVEL): ~5-15 % de lo que se gano.
    private const val KEY_OVERFLOW_XP = "overflow_xp"
    private const val OVERFLOW_XP_PER_CANDY = 200f

    /** XP sobrante acumulada que aun no llega a un caramelo (0 .. OVERFLOW_XP_PER_CANDY). */
    fun overflowXp(context: Context): Float = prefs(context).getFloatSafe(KEY_OVERFLOW_XP, 0f)
    fun overflowXpPerCandy(): Float = OVERFLOW_XP_PER_CANDY

    /** Recorta la XP de [species] a la de nivel 100: devuelve (XP que se guarda, XP sobrante que ya no cabe). Quien ya estaba por encima del
     *  tope (guardado antes de este cambio) conserva su XP y TODO lo que gane es sobrante. */
    private fun capXpAtMaxLevel(context: Context, species: String, before: Float, after: Float): Pair<Float, Float> {
        if (after <= before) return after to 0f
        val cap = cumXp(MAX_LEVEL, context, species)
        if (after <= cap) return after to 0f
        val fit = (cap - before).coerceAtLeast(0f)
        return (if (before >= cap) before else cap) to ((after - before) - fit)
    }

    /** Suma [amount] al contador de XP sobrante y convierte en caramelos cada OVERFLOW_XP_PER_CANDY completo (si la mochila ya esta
     *  desbloqueada; si no, se guarda para cuando lo este). */
    private fun bankOverflowXp(context: Context, amount: Float) {
        if (amount <= 0.001f) return
        synchronized(statsWriteLock) {
            val p = prefs(context)
            var pool = p.getFloatSafe(KEY_OVERFLOW_XP, 0f) + amount
            var candies = 0
            // (candiesUnlocked recorre todas las preferencias: solo se pregunta cuando ya hay para un caramelo)
            if (pool >= OVERFLOW_XP_PER_CANDY && candiesUnlocked(context)) {
                candies = (pool / OVERFLOW_XP_PER_CANDY).toInt()
                pool -= candies * OVERFLOW_XP_PER_CANDY
            }
            val e = p.edit().putFloat(KEY_OVERFLOW_XP, pool)
            if (candies > 0) e.putInt(KEY_CANDY_COUNT, candyCount(context) + candies)
            e.apply()
            if (candies > 0) DebugLog.log(context, "mochila: XP sobrante de Pokemon al nivel 100 convertida en $candies caramelo(s) (quedan ${pool.toInt()} XP)")
        }
    }

    // ==================== MOCHILA Y CARAMELOS ====================
    // Se desbloquea con el nivel de entrenador (UNLOCK_CANDIES_LEVEL). Un caramelo es XP "en
    // lata" para cualquier Pokemon tuyo: vale un % del coste de su SIGUIENTE nivel (proporcional al
    // nivel, como pidio el usuario - un valor fijo seria trivial a nivel alto y regalaria niveles
    // a nivel bajo), con un minimo. Hoy se consigue soltando un huevo (ver releaseEggForCandy); la
    // guarderia dara los de sus huevos. Valores a ajustar con el uso.
    private const val KEY_CANDY_COUNT = "candy_count"
    private const val CANDY_PCT_OF_NEXT_LEVEL = 0.03f
    private const val CANDY_MIN_XP = 5f

    fun candyPctOfNextLevel(): Float = CANDY_PCT_OF_NEXT_LEVEL
    fun candyMinXp(): Float = CANDY_MIN_XP

    fun candiesUnlocked(context: Context): Boolean = trainerLevelMaxFresh(context) >= UNLOCK_CANDIES_LEVEL

    fun candyCount(context: Context): Int = prefs(context).getInt(KEY_CANDY_COUNT, 0)

    fun addCandies(context: Context, n: Int) {
        if (n <= 0) return
        synchronized(statsWriteLock) {
            prefs(context).edit().putInt(KEY_CANDY_COUNT, candyCount(context) + n).commit()
        }
    }

    /** XP que cuesta pasar del nivel en que esta [xp] al siguiente (null si ya es nivel 100). */
    fun costToNextLevel(context: Context, xp: Float, name: String): Float? {
        val l = levelOf(context, xp, name)
        if (l >= MAX_LEVEL) return null
        return cumXp(l + 1, context, name) - cumXp(l, context, name)
    }

    /** XP que daria ahora un caramelo a un Pokemon de especie [name] con [xp] (null si nivel 100). */
    fun candyXpFor(context: Context, name: String, xp: Float): Float? =
        costToNextLevel(context, xp, name)?.let { maxOf(CANDY_MIN_XP, it * CANDY_PCT_OF_NEXT_LEVEL) }

    /** [used] = cuantos caramelos se gastaron (varios de golpe, ver [useCandies]); [xp] es la XP TOTAL de todos ellos. */
    data class CandyUse(val xp: Float, val leveledUp: Boolean, val newLevel: Int, val used: Int = 1)

    /** Lo que pasaria al darle [used] caramelos seguidos a un Pokemon (ver [candyPlan]): XP total y nivel/progreso antes y despues. */
    data class CandyPlan(val used: Int, val xpGained: Float, val levelBefore: Int, val levelAfter: Int, val progressBefore: Float, val progressAfter: Float)

    /** Simula, SIN tocar nada, hasta [n] caramelos seguidos para [name]/[shiny]: cada uno vale el 3 % (min 5 XP) del coste del nivel en
     *  el que esta en ese momento, asi que el total NO es n x el primero. Se para antes si llega al nivel 100 (el caramelo ya no vale:
     *  [used] sale menor que [n]). Null si no existe / ya evoluciono. Para el activo parte de su XP con el tiempo pendiente incluido. */
    fun candyPlan(context: Context, name: String, shiny: Boolean, n: Int): CandyPlan? {
        val base = name.lowercase()
        if (!hasIndividual(context, base, shiny) || hasEvolvedAway(context, base, shiny)) return null
        val isActive = currentPokemon(context) == base && isActiveShiny(context) == shiny
        val xp0 = if (isActive) loadWithDecay(context).xp else (rawStats(context, base, shiny)?.xp ?: return null)
        var xp = xp0
        var used = 0
        while (used < n) {
            val gain = candyXpFor(context, base, xp) ?: break
            xp += gain; used++
        }
        return CandyPlan(used, xp - xp0, levelOf(context, xp0, base), levelOf(context, xp, base), progressOf(context, xp0, base), progressOf(context, xp, base))
    }

    /** Gasta hasta [n] caramelos de golpe en el individuo [name]/[shiny] (los que hagan falta si antes llega al nivel 100). La XP total se
     *  da de una vez (una sola escritura). Null en los mismos casos que [useCandy]. [CandyUse.used] dice cuantos se gastaron de verdad. */
    fun useCandies(context: Context, name: String, shiny: Boolean, n: Int): CandyUse? = synchronized(statsWriteLock) {
        val have = candyCount(context)
        if (have <= 0 || n <= 0) return@synchronized null
        val base = name.lowercase()
        if (!hasIndividual(context, base, shiny) || hasEvolvedAway(context, base, shiny)) return@synchronized null
        val isActive = currentPokemon(context) == base && isActiveShiny(context) == shiny
        val plan = candyPlan(context, base, shiny, minOf(n, have)) ?: return@synchronized null
        if (plan.used <= 0) return@synchronized null
        if (isActive) {
            if (!grantXpToActive(context, plan.xpGained).applied) return@synchronized null
        } else {
            grantXpToIndividual(context, base, shiny, plan.xpGained)
        }
        prefs(context).edit().putInt(KEY_CANDY_COUNT, have - plan.used).commit()
        CandyUse(plan.xpGained, plan.levelAfter > plan.levelBefore, plan.levelAfter, plan.used)
    }

    /** Gasta UN caramelo en el individuo [name]/[shiny]. Null si no hay caramelos, el individuo no
     *  existe o ya evoluciono, o esta en nivel 100. El activo pasa por [grantXpToActive] (no pierde su XP
     *  por tiempo pendiente); el resto, por [grantXpToIndividual]. */
    fun useCandy(context: Context, name: String, shiny: Boolean): CandyUse? = useCandies(context, name, shiny, 1)

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_BAG_CHECK): comprueba el nivel de
     *  entrenador guardado y que [useCandy] suma lo esperado, tanto a un Pokemon NO activo como al
     *  ACTIVO con 1 h de XP por tiempo pendiente (que NO debe perderse). Deja la partida EXACTAMENTE
     *  como estaba (caramelos, XP, barras, reloj y horas de cuidado). Devuelve el informe. */
    fun debugCandySelfTest(context: Context): String {
        val p = prefs(context)
        val nl = System.lineSeparator()
        val sb = StringBuilder()
        val (max, _) = noteTrainerLevel(context)
        sb.append("nivel de entrenador: actual=${trainerLevel(context)} maximo guardado=$max mochila=${if (candiesUnlocked(context)) "desbloqueada" else "BLOQUEADA"}").append(nl)
        val candiesBefore = candyCount(context)
        p.edit().putInt(KEY_CANDY_COUNT, candiesBefore + 2).commit()
        val activeName = currentPokemon(context); val activeShiny = isActiveShiny(context)
        // --- 1) un Pokemon que NO es el activo
        val other = ownedIndividuals(context).firstOrNull { (n, s) ->
            !(n == activeName && s == activeShiny) && (levelForPokemon(context, n, s) ?: 100) < 100
        }
        if (other == null) sb.append("no hay otro Pokemon para probar").append(nl)
        else {
            val xpKey = k(slot(other.first, other.second), KEY_XP)
            val xpBefore = rawStats(context, other.first, other.second)?.xp ?: 0f
            val expected = candyXpFor(context, other.first, xpBefore) ?: 0f
            val use = useCandy(context, other.first, other.second)
            val xpAfter = rawStats(context, other.first, other.second)?.xp ?: 0f
            val ok = use != null && Math.abs(xpAfter - xpBefore - expected) < 0.01f
            sb.append("no activo ${other.first}: xp $xpBefore -> $xpAfter (esperado +$expected) ${if (ok) "OK" else "FALLO"}").append(nl)
            p.edit().putFloat(xpKey, xpBefore).commit()
        }
        // --- 2) el activo, con 1 h de XP por tiempo pendiente
        run {
            val n = currentSlot(context)
            val snap = listOf(KEY_HEALTH, KEY_HYGIENE, KEY_HAPPINESS, KEY_XP, KEY_CARE_HOURS).associateWith { p.getFloatSafe(k(n, it), 0f) }
            val lastSnap = p.getLong(k(n, KEY_LAST), System.currentTimeMillis())
            // El reloj del activo parte de AHORA: si llevaba dias sin cuidarse (cuenta de pruebas), su XP por tiempo pendiente ya era enorme y
            // la comprobacion "1 h pendiente" daba un falso FALLO (esperaba +40 y habia +970 de dias). Se restaura abajo con lastSnap.
            p.edit().putLong(k(n, KEY_LAST), System.currentTimeMillis()).commit()
            debugSkipHours(context, 1f)
            val passive = TIME_XP_PER_H * 1f * xpMultiplier(context, activeName)
            val xpBefore = p.getFloatSafe(k(n, KEY_XP), 0f)
            val use = useCandy(context, activeName, activeShiny)
            val xpAfter = p.getFloatSafe(k(n, KEY_XP), 0f)
            // Tolerancia: loadWithDecay suma el tiempo REAL transcurrido (milisegundos) ademas de la hora simulada.
            val expected = xpBefore + passive + (use?.xp ?: 0f)
            val ok = use != null && Math.abs(xpAfter - expected) < 1f
            sb.append("activo $activeName: xp $xpBefore -> $xpAfter (esperado ~$expected = +${passive.toInt()} por la hora pendiente + ${use?.xp ?: 0f} del caramelo) ${if (ok) "OK (no pierde el XP por tiempo)" else "FALLO"}").append(nl)
            val e = p.edit()
            snap.forEach { (key, v) -> e.putFloat(k(n, key), v) }
            e.putLong(k(n, KEY_LAST), lastSnap).commit()
        }
        p.edit().putInt(KEY_CANDY_COUNT, candiesBefore).commit()
        sb.append("restaurado: caramelos=${candyCount(context)} (antes $candiesBefore)")
        return sb.toString()
    }

    /** SOLO PRUEBAS: comprueba el contador de XP sobrante (nivel 100 -> caramelos) con un Pokemon NO activo y con el ACTIVO, y deja caramelos,
     *  contador, XP, barras y reloj EXACTAMENTE como estaban. Devuelve el informe. */
    fun debugOverflowSelfTest(context: Context): String {
        val p = prefs(context)
        val nl = System.lineSeparator()
        val sb = StringBuilder()
        if (!candiesUnlocked(context)) return "mochila bloqueada: no se puede probar la conversion en caramelos"
        val candiesBefore = candyCount(context); val poolBefore = overflowXp(context)
        val activeName = currentPokemon(context); val activeShiny = isActiveShiny(context)
        fun setPool(v: Float) = p.edit().putFloat(KEY_OVERFLOW_XP, v).commit()
        setPool(0f)
        val other = ownedIndividuals(context).firstOrNull { (n, s) -> !(n == activeName && s == activeShiny) && !isInNursery(context, n, s) && !DungeonState.isInParty(context, n, s) }
        if (other == null) { setPool(poolBefore); return "no hay otro Pokemon para probar" }
        val (on, os) = other
        val xpOther = rawStats(context, on, os)?.xp ?: 0f
        val cap = cumXp(MAX_LEVEL, context, on)
        // 1) ya al nivel 100: TODA la XP es sobrante (1000 XP = 5 caramelos con 200 por caramelo)
        debugSetIndividualStats(context, on, os, xp = cap)
        val c1 = candyCount(context)
        grantXpToIndividual(context, on, os, 1000f)
        val xp1 = rawStats(context, on, os)?.xp ?: 0f
        val ok1 = xp1 == cap && candyCount(context) == c1 + 5 && overflowXp(context) < 0.01f
        sb.append("nivel 100 +1000 XP: xp $xp1 (tope $cap), caramelos ${candyCount(context) - c1} (esperado 5), contador ${overflowXp(context)} ${if (ok1) "OK" else "FALLO"}").append(nl)
        // 2) cruzando el 100: solo lo que no cabe es sobrante (cabian 100 de los 350 -> 250 = 1 caramelo y quedan 50)
        setPool(0f)
        debugSetIndividualStats(context, on, os, xp = cap - 100f)
        val c2 = candyCount(context)
        grantXpToIndividual(context, on, os, 350f)
        val xp2 = rawStats(context, on, os)?.xp ?: 0f
        val ok2 = Math.abs(xp2 - cap) < 0.01f && candyCount(context) == c2 + 1 && Math.abs(overflowXp(context) - 50f) < 0.01f
        sb.append("Nv99 (a 100 XP del tope) +350 XP: xp ${"%.1f".format(xp2)} (tope ${"%.1f".format(cap)}), caramelos ${candyCount(context) - c2} (esperado 1), contador ${overflowXp(context)} (esperado 50) ${if (ok2) "OK" else "FALLO"}").append(nl)
        // 3) el ACTIVO en nivel 100 con 2 h de XP por tiempo pendiente: su XP no sube y el contador recoge ~2 h
        run {
            val n = currentSlot(context)
            val snap = listOf(KEY_HEALTH, KEY_HYGIENE, KEY_HAPPINESS, KEY_XP, KEY_CARE_HOURS).associateWith { p.getFloatSafe(k(n, it), 0f) }
            val lastSnap = p.getLong(k(n, KEY_LAST), System.currentTimeMillis())
            val capA = cumXp(MAX_LEVEL, context, activeName)
            setPool(0f)
            p.edit().putFloat(k(n, KEY_XP), capA).putLong(k(n, KEY_LAST), System.currentTimeMillis()).commit()
            debugSkipHours(context, 2f)
            val s = loadWithDecay(context)
            val expected = TIME_XP_PER_H * 2f * xpMultiplier(context, activeName)
            val ok = s.xp == capA && Math.abs(overflowXp(context) - expected) < 1f
            sb.append("activo $activeName en nivel 100 con 2 h pendientes: xp ${s.xp} (tope $capA), contador ${"%.1f".format(overflowXp(context))} (esperado ~${"%.1f".format(expected)}) ${if (ok) "OK" else "FALLO"}").append(nl)
            val e = p.edit()
            snap.forEach { (key, v) -> e.putFloat(k(n, key), v) }
            e.putLong(k(n, KEY_LAST), lastSnap).commit()
        }
        debugSetIndividualStats(context, on, os, xp = xpOther)
        p.edit().putInt(KEY_CANDY_COUNT, candiesBefore).putFloat(KEY_OVERFLOW_XP, poolBefore).commit()
        sb.append("restaurado: caramelos=${candyCount(context)} (antes $candiesBefore), contador=${overflowXp(context)} (antes $poolBefore)")
        return sb.toString()
    }

    /** SOLO PARA EL MENU DEBUG / adb (ver PokeWidgetProvider.ACTION_DEBUG_NURSERY_CHECK): prueba en el
     *  movil el camino de la guarderia con datos reales y lo deja TODO como estaba. Cubre meter/sacar,
     *  los bloqueos y el cobro de XP por tiempo (adelantando 3 h el reloj de la guarderia). NO prueba los
     *  huevos (podrian crear un shiny de verdad): la puesta/eclosion se cubre con NurseryRulesTest.
     *  Si la guarderia ya tiene Pokemon de verdad, se salta la parte del reloj para no regalarles XP. */
    fun debugNurserySelfTest(context: Context): String {
        val p = prefs(context)
        val nl = System.lineSeparator()
        val sb = StringBuilder()
        val activeName = currentPokemon(context); val activeShiny = isActiveShiny(context)
        // --- instantanea de todo lo que se va a tocar
        val setBefore = p.getStringSet(KEY_NURSERY_SET, null)?.toSet()
        val hadSet = p.contains(KEY_NURSERY_SET)
        val xpLastBefore = p.getLong(KEY_NURSERY_XP_LAST, 0L)
        val eggLastBefore = p.getLong(KEY_NURSERY_EGG_LAST, 0L)
        val careLastBefore = p.getLong(KEY_NURSERY_CARE_LAST, 0L)
        val hadTrainerMax = p.contains(KEY_TRAINER_LEVEL_MAX)
        val trainerMaxBefore = p.getInt(KEY_TRAINER_LEVEL_MAX, 1)
        val candiesBefore = candyCount(context)
        val realMembers = nurseryMembers(context)
        sb.append("nivel de entrenador: actual=${trainerLevel(context)} maximo guardado=$trainerMaxBefore plazas=${nurseryCapacity(context)} guarderia ${if (nurseryUnlocked(context)) "desbloqueada" else "BLOQUEADA (se fuerza el nivel 9 durante la prueba)"}; dentro ahora: ${realMembers.size}").append(nl)
        if (!nurseryUnlocked(context)) p.edit().putInt(KEY_TRAINER_LEVEL_MAX, maxOf(trainerMaxBefore, 9)).commit()

        val candidates = ownedIndividuals(context).filter { (n, s) ->
            !(n == activeName && s == activeShiny) && !isInNursery(context, n, s) &&
                (levelForPokemon(context, n, s) ?: 100) < 100 && nurseryBlockReason(context, n, s) == null
        }
        val parent = candidates.firstOrNull()
        if (parent == null) {
            sb.append("no hay ningun Pokemon libre para probar la guarderia")
        } else {
            val (pn, ps) = parent
            val pSlot = slot(pn, ps)
            val xpKey = k(pSlot, KEY_XP); val lastKey = k(pSlot, KEY_LAST)
            val xpBefore = p.getFloatSafe(xpKey, 0f)
            val lastBefore = p.getLong(lastKey, 0L)
            val statsBefore = rawStats(context, pn, ps)   // las necesidades tambien cambian durante la prueba
            val eggKey = KEY_EGG_DISABLED_PREFIX + pn.lowercase()
            val eggFlagBefore = p.getBoolean(eggKey, false)
            val hadEggFlag = p.contains(eggKey)
            p.edit().putBoolean(eggKey, true).commit()   // sin puesta: asi la prueba no puede crear un shiny de verdad
            sb.append("prueba con $pn${if (ps) " (shiny)" else ""} (nivel ${levelForPokemon(context, pn, ps)}, xp $xpBefore)").append(nl)

            // 1) meter
            val add1 = nurseryAdd(context, pn, ps)
            sb.append("meter: ${add1 ?: "OK"} -> dentro=${isInNursery(context, pn, ps)} ${if (add1 == null && isInNursery(context, pn, ps)) "OK" else "FALLO"}").append(nl)
            // 2) bloqueos
            val dup = nurseryBlockReason(context, pn, ps)
            sb.append("meter otra vez: \"$dup\" ${if (dup == "Ya está en la guardería") "OK" else "FALLO"}").append(nl)
            val act = nurseryBlockReason(context, activeName, activeShiny)
            sb.append("meter al activo: \"$act\" ${if (act != null && act.contains("activo")) "OK" else "FALLO"}").append(nl)
            if (nurseryMembers(context).size >= nurseryCapacity(context)) {
                val other = ownedIndividuals(context).firstOrNull { (n, s) -> !(n == pn && s == ps) && !(n == activeName && s == activeShiny) && !isInNursery(context, n, s) }
                if (other != null) {
                    val full = nurseryBlockReason(context, other.first, other.second)
                    sb.append("meter con plazas llenas: \"$full\" ${if (full == "No quedan plazas libres") "OK" else "FALLO"}").append(nl)
                }
            } else sb.append("(hay mas plazas libres: no se prueba el limite)").append(nl)
            // 3) cobro de XP por tiempo
            if (realMembers.isEmpty() && add1 == null) {
                debugSetIndividualStats(context, pn, ps, health = 100f, hygiene = 100f, happiness = 100f)
                val t3 = System.currentTimeMillis() - 3 * 3_600_000L
                p.edit().putLong(KEY_NURSERY_XP_LAST, t3).putLong(KEY_NURSERY_EGG_LAST, t3).putLong(KEY_NURSERY_CARE_LAST, t3).commit()
                val before = rawStats(context, pn, ps)?.xp ?: 0f
                val rep = settleNursery(context, force = true)
                val after = rawStats(context, pn, ps)?.xp ?: 0f
                // XP por tiempo + el de las acciones que haya cubierto el (con 3 h de decaimiento puede cruzar algun umbral).
                val expected = nurseryXpPerHour() * 3f + (rep?.careXp ?: 0f)
                val ok = rep != null && Math.abs(after - before - expected) < 1f && rep.hatched == 0
                sb.append("3 h de guarderia: xp $before -> $after (esperado +$expected = ${nurseryXpPerHour() * 3f} por tiempo + ${rep?.careXp} de ${rep?.careEvents?.size} necesidades cubiertas, huevos ${rep?.hatched}) ${if (ok) "OK" else "FALLO"}").append(nl)
            } else sb.append("(la guarderia ya tenia Pokemon de verdad: no se prueba el reloj)").append(nl)
            // 3b) un huevo que eclosiona en el primer tick: bono de XP (50 %), caramelo y aviso "siempre".
            //     Si en ese 2 % sale un shiny, es real y se queda (se dice abajo).
            var notifyAlwaysBefore: Boolean? = null
            if (realMembers.isEmpty() && add1 == null) {
                notifyAlwaysBefore = nurseryNotifyAlways(context)
                setNurseryNotifyAlways(context, true)
                // Con el huevo en el ultimo estado cada tick eclosiona SOLO con probabilidad ADVANCE_PROB (50 %):
                // se repite hasta que eclosione (maximo 20 intentos, casi imposible fallar) para que la prueba sea fija.
                var xb = 0f; var xa = 0f; var cb = 0; var ca = 0
                var rep: NurseryReport? = null
                for (attempt in 1..20) {
                    debugSetIndividualStats(context, pn, ps, health = 100f, hygiene = 100f, happiness = 100f)
                    val t = System.currentTimeMillis() - NurseryRules.TICK_MS - 1000L
                    p.edit().putInt(KEY_NURSERY_EGG_STATE + pSlot, NurseryRules.EGG_SUCCESSES_TO_HATCH)
                        .putLong(KEY_NURSERY_XP_LAST, t).putLong(KEY_NURSERY_EGG_LAST, t).putLong(KEY_NURSERY_CARE_LAST, t).commit()
                    xb = rawStats(context, pn, ps)?.xp ?: 0f
                    cb = candyCount(context)
                    rep = settleNursery(context, force = true)
                    xa = rawStats(context, pn, ps)?.xp ?: 0f
                    ca = candyCount(context)
                    if ((rep?.hatched ?: 0) >= 1) break
                }
                p.edit().putInt(KEY_NURSERY_EGG_STATE + pSlot, 0).commit()   // ni un huevo a medias para la prueba siguiente
                val expectedXp = nurseryXpPerHour() * ((NurseryRules.TICK_MS + 1000L) / 3_600_000f) + EGG_HATCH_XP_BONUS * NurseryRules.HATCH_BONUS_FRACTION + (rep?.careXp ?: 0f)
                val ok = rep != null && rep.hatched == 1 && Math.abs(xa - xb - expectedXp) < 1.5f && ca - cb == rep.candies
                sb.append("huevo forzado: eclosionados=${rep?.hatched} caramelos +${ca - cb} (informe ${rep?.candies}) shiny=${rep?.shinies} repetidos=${rep?.duplicateShinies}; xp $xb -> $xa (esperado +$expectedXp) ${if (ok) "OK" else "FALLO"}").append(nl)
                if (rep != null && rep.shinies.isNotEmpty()) sb.append("¡¡SALIO UN SHINY DE VERDAD (${rep.shinies.joinToString()}): se queda en tu Pokedex!!").append(nl)
            }
            // 3c) necesidades: con la vida muy baja el Pokemon come el solo (manzana), gana el XP de la accion y,
            //     por el coste cruzado, se ensucia un poco.
            if (realMembers.isEmpty() && add1 == null) {
                debugSetIndividualStats(context, pn, ps, health = 10f, hygiene = 100f, happiness = 100f)
                val t = System.currentTimeMillis() - 2 * NurseryRules.CARE_STEP_MS - 1000L
                p.edit().putLong(KEY_NURSERY_XP_LAST, t).putLong(KEY_NURSERY_EGG_LAST, t).putLong(KEY_NURSERY_CARE_LAST, t).commit()
                val xb = rawStats(context, pn, ps)?.xp ?: 0f
                val rep = settleNursery(context, force = true)
                val s = rawStats(context, pn, ps)
                val fed = rep?.careEvents?.count { it.action == PokeWidgetProvider.ACTION_FEED && it.slotKey == pSlot } ?: 0
                val expectedXp = nurseryXpPerHour() * ((2 * NurseryRules.CARE_STEP_MS + 1000L) / 3_600_000f) + (rep?.careXp ?: 0f)
                val ok = rep != null && fed >= 1 && s != null && s.health > 40f && s.hygiene < 100f && (rep.careXp) >= 5f &&
                    Math.abs((s.xp - xb) - expectedXp) < 1.5f
                sb.append("necesidades: vida 10 -> ${s?.health} (comidas $fed, otras ${(rep?.careEvents?.size ?: 0) - fed}), higiene ${s?.hygiene}, XP de acciones +${rep?.careXp} ${if (ok) "OK" else "FALLO"}").append(nl)
            }
            // 3d) fondo a juego automatico: x2 de shiny si hay un fondo DESBLOQUEADO que le pegue (calculado aqui
            //     por otro camino: tipos del Pokemon contra los tipos que beneficia cada fondo desbloqueado).
            run {
                val types = typesOf(context, pn, ps)
                val expectedMult = if (unlockedBackgrounds(context).any { bg -> typesBenefitedBy(bg).any { it in types } }) 2f else 1f
                val mult = nurseryShinyMultiplier(context, pn, ps)
                sb.append("fondo a juego en la guarderia: tipos=$types multiplicador=$mult (esperado $expectedMult) ${if (mult == expectedMult) "OK" else "FALLO"}").append(nl)
                // Caso x2: se desbloquea un instante UN fondo que le pegue (si aun no lo esta) y el multiplicador sube
                // a 2 sin tocar el fondo puesto en el widget; despues se restaura la lista de fondos tal cual estaba.
                val candidateBg = types.firstNotNullOfOrNull { t -> BG_FOR_TYPE[t]?.firstOrNull { !isBgUnlocked(context, it) } }
                if (candidateBg != null) {
                    val hadBgSet = p.contains(KEY_UNLOCKED_BGS)
                    val bgSetBefore = p.getStringSet(KEY_UNLOCKED_BGS, null)?.toSet()
                    val widgetBgBefore = currentBg(context)
                    p.edit().putStringSet(KEY_UNLOCKED_BGS, unlockedBackgrounds(context) + candidateBg).commit()
                    val boosted = nurseryShinyMultiplier(context, pn, ps)
                    val widgetBgAfter = currentBg(context)
                    if (hadBgSet) p.edit().putStringSet(KEY_UNLOCKED_BGS, bgSetBefore).commit() else p.edit().remove(KEY_UNLOCKED_BGS).commit()
                    sb.append("con $candidateBg desbloqueado: multiplicador=$boosted, fondo del widget $widgetBgBefore -> $widgetBgAfter ${if (boosted == 2f && widgetBgBefore == widgetBgAfter) "OK" else "FALLO"}; restaurado=${nurseryShinyMultiplier(context, pn, ps) == mult}").append(nl)
                }
            }
            // 4) sacar
            nurseryRemove(context, pn, ps)
            sb.append("sacar: dentro=${isInNursery(context, pn, ps)} ${if (!isInNursery(context, pn, ps)) "OK" else "FALLO"}").append(nl)

            // --- restaurar
            statsBefore?.let { debugSetIndividualStats(context, pn, ps, health = it.health, hygiene = it.hygiene, happiness = it.happiness, xp = xpBefore) }
            val e = p.edit().putFloat(xpKey, xpBefore)
            if (lastBefore != 0L) e.putLong(lastKey, lastBefore) else e.remove(lastKey)
            if (hadEggFlag) e.putBoolean(eggKey, eggFlagBefore) else e.remove(eggKey)
            e.remove(KEY_NURSERY_EGG_STATE + pSlot)
            e.commit()
            notifyAlwaysBefore?.let { setNurseryNotifyAlways(context, it) }
        }
        val e = p.edit()
        if (hadSet) e.putStringSet(KEY_NURSERY_SET, setBefore) else e.remove(KEY_NURSERY_SET)
        e.putLong(KEY_NURSERY_XP_LAST, xpLastBefore).putLong(KEY_NURSERY_EGG_LAST, eggLastBefore).putLong(KEY_NURSERY_CARE_LAST, careLastBefore)
        if (hadTrainerMax) e.putInt(KEY_TRAINER_LEVEL_MAX, trainerMaxBefore) else e.remove(KEY_TRAINER_LEVEL_MAX)
        e.putInt(KEY_CANDY_COUNT, candiesBefore)
        e.commit()
        sb.append("restaurado: dentro=${nurseryMembers(context).size} (antes ${realMembers.size}), caramelos=${candyCount(context)} (antes $candiesBefore), nivel maximo=${trainerLevelMax(context)} (antes $trainerMaxBefore)")
        return sb.toString()
    }

    // ==================== GUARDERIA ====================
    // Se desbloquea con el nivel de entrenador (UNLOCK_NURSERY_LEVEL, plazas segun NurseryRules). Un
    // Pokemon dentro anda suelto por el prado (ver NurseryView) y gana el 50 % del XP por tiempo del
    // activo SIN cuidarlo; ademas tiene LAS MISMAS NECESIDADES que cuando lo cuidas (vida, felicidad e
    // higiene decaen igual) y las cubre el solo - manzanas, pelota y bebedero - ganando el XP de una
    // accion cada vez (ver simulateNurseryCare). Si puede poner huevos, los pone e incuba SOLO: el
    // shiny se queda (y avisa), el resto se convierte en caramelo; el que puso el huevo cobra el 50 %
    // del bono de eclosion de siempre.
    // Todo se LIQUIDA de forma perezosa (settleNursery) al abrir la app y en el tick de 15 min del
    // widget: ni hilos ni alarmas propias. Un Pokemon de la guarderia no puede ser el activo ni
    // explorar la mazmorra a la vez (nurseryBlockReason); al evolucionar o pasar a activo, sale.
    private const val KEY_NURSERY_SET = "nursery_set"                // StringSet de slots ("pikachu", "pikachu#shiny")
    private const val KEY_NURSERY_XP_LAST = "nursery_xp_last"
    private const val KEY_NURSERY_EGG_LAST = "nursery_egg_last"
    private const val KEY_NURSERY_CARE_LAST = "nursery_care_last"   // reloj de las necesidades (pasos de NurseryRules.CARE_STEP_MS)
    private const val KEY_NURSERY_EGG_STATE = "nursery_egg_state."   // + slot -> estado del huevo (ver NurseryRules)
    private const val KEY_NURSERY_NOTIFY_ALWAYS = "nursery_notify_always"
    private const val KEY_NURSERY_EVO_NOTIFIED = "nursery_evo_notified"   // StringSet de slots que ya pueden evolucionar y de los que ya se aviso
    private const val NURSERY_MIN_SETTLE_MS = 60_000L
    private const val NURSERY_DUPLICATE_SHINY_CANDIES = 3

    fun nurseryUnlocked(context: Context): Boolean = trainerLevelMaxFresh(context) >= UNLOCK_NURSERY_LEVEL
    fun nurseryCapacity(context: Context): Int = NurseryRules.slotsForTrainerLevel(trainerLevelMaxFresh(context))
    fun nurseryNextSlotLevel(context: Context): Int? = NurseryRules.nextSlotLevel(trainerLevelMax(context))
    /** XP por hora de cada plaza (la mitad del XP por tiempo del activo, sin multiplicador de fondo). */
    fun nurseryXpPerHour(): Float = NurseryRules.xpPerHour(TIME_XP_PER_H)

    private fun nurserySlotKeys(context: Context): List<String> =
        (prefs(context).getStringSet(KEY_NURSERY_SET, null) ?: emptySet()).sorted()

    private fun parseSlotKey(key: String): Pair<String, Boolean> =
        if (key.endsWith(SHINY_SLOT_SUFFIX)) key.removeSuffix(SHINY_SLOT_SUFFIX) to true else key to false

    /** Los Pokemon (especie, shiny) que estan en la guarderia ahora mismo. */
    fun nurseryMembers(context: Context): List<Pair<String, Boolean>> = nurserySlotKeys(context).map { parseSlotKey(it) }

    fun isInNursery(context: Context, name: String, shiny: Boolean): Boolean =
        slot(name.lowercase(), shiny) in nurserySlotKeys(context)

    /** Las evoluciones que [name]/[shiny] puede hacer YA (nivel alcanzado en alguna rama), o null si ninguna.
     *  Nunca lanza: la guarderia se liquida sola en segundo plano y un valor raro de las preferencias no debe tumbarla. */
    fun evolutionReadyFor(context: Context, name: String, shiny: Boolean): EvoEntry? = try {
        rawStats(context, name, shiny)?.let { pendingEvolutionFor(context, name, it, shiny) }
    } catch (_: Throwable) { null }

    /** Los Pokemon de la guarderia que pueden evolucionar ahora mismo (para marcarlos en la escena). No avisa ni marca nada. */
    fun nurseryEvolvable(context: Context): List<Pair<String, Boolean>> =
        nurseryMembers(context).filter { (n, s) -> evolutionReadyFor(context, n, s) != null }

    // Visitantes del jardin (ver NurseryVisitors): Pokemon que no son del jugador, con nivel parecido a los de dentro.
    @Volatile private var visitorCatalog: NurseryVisitors.Catalog? = null

    private fun nurseryVisitorCatalog(context: Context): NurseryVisitors.Catalog {
        visitorCatalog?.let { return it }
        // Fuerza de cada especie (suma de sus 5 estadisticas base) solo para situar las que no tienen evolucion (Tauros...).
        val strength = HashMap<String, Int>()
        try {
            val obj = org.json.JSONObject(context.assets.open("base_stats.json").bufferedReader().use { it.readText() })
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next(); val s = obj.getJSONObject(name)
                strength[name] = s.optInt("hp") + s.optInt("attack") + s.optInt("defense") + s.optInt("special_attack") + s.optInt("speed")
            }
        } catch (_: Throwable) { /* sin datos: todas cuentan como medias */ }
        val table = loadEvoTable(context).mapValues { (n, e) ->
            NurseryVisitors.Sp(n, e.evolvesFrom, e.evolvesTo.map { it.name to it.level }, e.rare, strength[n] ?: 300)
        }
        return NurseryVisitors.catalog(table).also { visitorCatalog = it }
    }

    /** Los visitantes que andan por la guarderia ahora ([nowMs]): hasta 3 Pokemon que NO son del jugador, de nivel parecido a
     *  los que tiene dentro (o, con la guarderia vacia, al de su activo), que cambian cada 15 minutos. [accept] descarta
     *  especies que no se puedan dibujar. Nunca lanza: un valor raro de las preferencias solo deja la escena sin visitantes. */
    fun nurseryVisitors(context: Context, nowMs: Long = System.currentTimeMillis(), accept: (String) -> Boolean = { true }): List<NurseryVisitors.Visitor> = try {
        val members = nurseryMembers(context)
        val levels = members.mapNotNull { (n, s) -> levelForPokemon(context, n, s) }.ifEmpty {
            listOfNotNull(levelForPokemon(context, currentPokemon(context), isActiveShiny(context)))
        }
        NurseryVisitors.pick(
            nurseryVisitorCatalog(context), levels, members.map { it.first.lowercase() }.toSet(),
            NurseryVisitors.slotOf(nowMs), NurseryVisitors.countFor(members.size), accept
        )
    } catch (_: Throwable) { emptyList() }

    private fun nurseryEvoNotified(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_NURSERY_EVO_NOTIFIED, null) ?: emptySet()

    /** Anota (o quita) a [key] de los "ya avisados de que puede evolucionar". */
    private fun markNurseryEvoNotified(context: Context, key: String, notified: Boolean) {
        val now = nurseryEvoNotified(context)
        if ((key in now) == notified) return
        prefs(context).edit().putStringSet(KEY_NURSERY_EVO_NOTIFIED, if (notified) now + key else now - key).apply()
    }

    // Posicion de los muebles del jardin (bebedero y cesta; la pelota no se guarda), arrastrables desde la
    // escena (NurseryView): se guarda el punto de apoyo en el suelo como fraccion (x, y) del ancho/alto de la vista.
    private const val KEY_NURSERY_FURNITURE = "nursery_furniture."   // + "trough" / "basket" -> "fx,fy"

    fun nurseryFurniturePos(context: Context, kind: String): Pair<Float, Float>? {
        val parts = prefs(context).getString(KEY_NURSERY_FURNITURE + kind, null)?.split(",") ?: return null
        if (parts.size != 2) return null
        return (parts[0].toFloatOrNull() ?: return null) to (parts[1].toFloatOrNull() ?: return null)
    }

    fun setNurseryFurniturePos(context: Context, kind: String, fx: Float, fy: Float) =
        prefs(context).edit().putString(KEY_NURSERY_FURNITURE + kind, "$fx,$fy").apply()

    /** Vuelve a la posicion por defecto de [kind] (solo lo usan las pruebas, para dejar la disposicion como estaba). */
    fun clearNurseryFurniturePos(context: Context, kind: String) =
        prefs(context).edit().remove(KEY_NURSERY_FURNITURE + kind).apply()

    /** Aviso de huevos: true = de todos los huevos (un resumen por tanda), false = solo de los shiny. */
    fun nurseryNotifyAlways(context: Context): Boolean = prefs(context).getBoolean(KEY_NURSERY_NOTIFY_ALWAYS, false)
    fun setNurseryNotifyAlways(context: Context, always: Boolean) =
        prefs(context).edit().putBoolean(KEY_NURSERY_NOTIFY_ALWAYS, always).apply()

    /** Por que NO se puede meter [name]/[shiny] en la guarderia ahora mismo (null = se puede). */
    fun nurseryBlockReason(context: Context, name: String, shiny: Boolean): String? {
        val base = name.lowercase()
        if (!nurseryUnlocked(context)) return "La guardería se desbloquea en el nivel de entrenador $UNLOCK_NURSERY_LEVEL"
        if (!hasIndividual(context, base, shiny) || hasEvolvedAway(context, base, shiny)) return "Ese Pokémon no está disponible"
        if (isInNursery(context, base, shiny)) return "Ya está en la guardería"
        if (nurseryMembers(context).size >= nurseryCapacity(context)) return "No quedan plazas libres"
        if (currentPokemon(context) == base && isActiveShiny(context) == shiny) {
            return "Es tu Pokémon activo: la guardería es para los demás"
        }
        if (DungeonState.isInParty(context, base, shiny)) return "Está en la mazmorra"
        return null
    }

    /** Mete a [name]/[shiny] en la guarderia. Devuelve el motivo si no se pudo, o null si OK. */
    fun nurseryAdd(context: Context, name: String, shiny: Boolean): String? = synchronized(statsWriteLock) {
        nurseryBlockReason(context, name, shiny)?.let { return@synchronized it }
        settleNursery(context, force = true)   // los que ya estaban cobran hasta ahora; el nuevo empieza limpio
        val key = slot(name.lowercase(), shiny)
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putStringSet(KEY_NURSERY_SET, nurserySlotKeys(context).toSet() + key)
            .putInt(KEY_NURSERY_EGG_STATE + key, 0)
            .putLong(KEY_NURSERY_XP_LAST, now)
            .putLong(KEY_NURSERY_EGG_LAST, if (nurserySlotKeys(context).isEmpty()) now else prefs(context).getLong(KEY_NURSERY_EGG_LAST, now))
            .putLong(KEY_NURSERY_CARE_LAST, if (nurserySlotKeys(context).isEmpty()) now else prefs(context).getLong(KEY_NURSERY_CARE_LAST, now))
            .commit()
        // Si ya puede evolucionar al entrar, el jugador lo sabe (acaba de elegirlo): no hace falta avisarle luego.
        if (evolutionReadyFor(context, name, shiny) != null) markNurseryEvoNotified(context, key, true)
        DebugLog.log(context, "guarderia: entra ${name.lowercase()}${if (shiny) " (shiny)" else ""} (plazas ${nurserySlotKeys(context).size}/${nurseryCapacity(context)})")
        null
    }

    /** Saca a [name]/[shiny] de la guarderia (cobra antes lo que llevara). Si tenia un huevo a medias, se pierde. */
    fun nurseryRemove(context: Context, name: String, shiny: Boolean) = synchronized(statsWriteLock) {
        val key = slot(name.lowercase(), shiny)
        if (key !in nurserySlotKeys(context)) return@synchronized
        // Lo que sale (a evolucionar, a ser el activo...) no debe disparar el aviso de "puede evolucionar" en su ultima liquidacion.
        markNurseryEvoNotified(context, key, true)
        settleNursery(context, force = true)
        // Su reloj pasa a AHORA: si despues se cuida como activo, el tiempo que estuvo en la guarderia
        // no debe volver a contar como XP pasiva del activo (loadWithDecay mide desde "last").
        prefs(context).edit()
            .putStringSet(KEY_NURSERY_SET, nurserySlotKeys(context).toSet() - key)
            .remove(KEY_NURSERY_EGG_STATE + key)
            .putLong(k(key, KEY_LAST), System.currentTimeMillis())
            .commit()
        markNurseryEvoNotified(context, key, false)
        if (nurseryEvoNotified(context).isEmpty()) NotificationHelper.cancelNurseryEvolution(context)
        DebugLog.log(context, "guarderia: sale ${name.lowercase()}${if (shiny) " (shiny)" else ""}")
    }

    /** Una necesidad que un Pokemon de la guarderia ha cubierto el solo: [action] es PokeWidgetProvider.ACTION_FEED
     *  (comer una manzana -> vida), ACTION_PET (jugar con la pelota -> felicidad) o ACTION_WASH (beber/refrescarse
     *  en el bebedero -> higiene). [slotKey] identifica al Pokemon (como en nursery_set). */
    class NurseryCareEvent(val slotKey: String, val action: String) {
        val species: String get() = slotKey.removeSuffix(SHINY_SLOT_SUFFIX)
        val shiny: Boolean get() = slotKey.endsWith(SHINY_SLOT_SUFFIX)
    }

    class NurseryReport(
        val hatched: Int, val candies: Int, val shinies: List<String>, val duplicateShinies: Int,
        val timeXpPerSlot: Float, val bonusXp: Float,
        val careEvents: List<NurseryCareEvent> = emptyList(), val careXp: Float = 0f,
        /** Especies de la guarderia que acaban de poder evolucionar en esta liquidacion (solo las NUEVAS, no las ya avisadas). */
        val evolvable: List<String> = emptyList()
    )

    /** Avanza [steps] pasos de [NurseryRules.CARE_STEP_MS] (desde [fromMs]) las necesidades de los miembros
     *  [keys] con LAS MISMAS reglas que el compañero activo (loadWithDecay + tryApplyAction): decaimiento
     *  dia/noche con su personalidad, umbral aleatorio por stat (que se vuelve a sortear al cubrirla),
     *  boost fijo y coste cruzado. Cuando una stat cae bajo su umbral el Pokemon la cubre (la mas hundida
     *  primero) y gana ACTION_XP; las stats resultantes se guardan. Devuelve el XP de acciones de cada
     *  miembro (mismo orden que [keys]) y añade los sucesos a [events]. */
    private fun simulateNurseryCare(context: Context, keys: List<String>, fromMs: Long, steps: Int, events: MutableList<NurseryCareEvent>): FloatArray {
        val p = prefs(context)
        val bedH = bedtimeHour(context); val wakeH = wakeHour(context)
        val xp = FloatArray(keys.size)
        if (steps <= 0) return xp
        val stepMs = NurseryRules.CARE_STEP_MS
        val actions = arrayOf(PokeWidgetProvider.ACTION_FEED, PokeWidgetProvider.ACTION_PET, PokeWidgetProvider.ACTION_WASH)
        val threshKeys = arrayOf(KEY_THRESH_HEALTH, KEY_THRESH_HAPPY, KEY_THRESH_HYGIENE)
        keys.forEachIndexed { i, key ->
            val (species, shiny) = parseSlotKey(key)
            val s = rawStats(context, species, shiny) ?: return@forEachIndexed
            val (mHealth, mHappy, mHygiene) = personalityMultipliers(context, species)
            for (step in 0 until steps) {
                val t0 = fromMs + step * stepMs; val t1 = t0 + stepMs
                s.health = (s.health - decayOver(t0, t1, HEALTH_DAY * mHealth, HEALTH_NIGHT * mHealth, bedH, wakeH)).coerceIn(0f, 100f)
                s.hygiene = (s.hygiene - decayOver(t0, t1, HYGIENE_DAY * mHygiene, HYGIENE_NIGHT * mHygiene, bedH, wakeH)).coerceIn(0f, 100f)
                s.happiness = (s.happiness - decayOver(t0, t1, HAPPY_DAY * mHappy, HAPPY_NIGHT * mHappy, bedH, wakeH)).coerceIn(0f, 100f)
                // Orden: vida, felicidad, higiene (el de `actions`); la mas hundida bajo su umbral va primero.
                val thresholds = FloatArray(3) { ensureThreshold(context, species, threshKeys[it]) }
                val order = NurseryRules.needsInOrder(floatArrayOf(s.health, s.happiness, s.hygiene), thresholds)
                for (idx in order) {
                    // Un coste cruzado de una accion anterior pudo subirla o bajarla: se vuelve a comprobar.
                    val value = when (idx) { 0 -> s.health; 1 -> s.happiness; else -> s.hygiene }
                    if (value >= ensureThreshold(context, species, threshKeys[idx])) continue
                    when (idx) {
                        0 -> s.health = (s.health + BOOST_HEALTH).coerceIn(0f, 100f)
                        1 -> s.happiness = (s.happiness + BOOST_HAPPY).coerceIn(0f, 100f)
                        else -> s.hygiene = (s.hygiene + BOOST_HYGIENE).coerceIn(0f, 100f)
                    }
                    sideEffectFor(actions[idx], mHealth, mHappy, mHygiene)?.invoke(s)
                    xp[i] += ACTION_XP
                    events.add(NurseryCareEvent(key, actions[idx]))
                    rerollThreshold(context, species, threshKeys[idx])
                }
            }
            p.edit().putFloat(k(key, KEY_HEALTH), s.health).putFloat(k(key, KEY_HYGIENE), s.hygiene)
                .putFloat(k(key, KEY_HAPPINESS), s.happiness).commit()
        }
        return xp
    }

    /** Liquida la guarderia hasta AHORA: XP de cada plaza, huevos puestos/eclosionados y su resultado
     *  (shiny -> individuo nuevo, resto -> caramelos), avisando segun el ajuste. [force] salta el
     *  minimo de 1 min entre liquidaciones. Devuelve el resumen, o null si no habia nada que hacer. */
    fun settleNursery(context: Context, force: Boolean = false): NurseryReport? = synchronized(statsWriteLock) {
        val p = prefs(context)
        val keys = nurserySlotKeys(context)
        val now = System.currentTimeMillis()
        if (keys.isEmpty()) {
            p.edit().putLong(KEY_NURSERY_XP_LAST, now).putLong(KEY_NURSERY_EGG_LAST, now).putLong(KEY_NURSERY_CARE_LAST, now).apply()
            return@synchronized null
        }
        val xpLast = p.getLong(KEY_NURSERY_XP_LAST, 0L)
        // Ancla sin inicializar, corrupta (1970) o en el futuro (hora del movil adelantada y luego
        // corregida): no es una ausencia, se re-ancla a ahora (QaRobust/guarderia: 1970 regalaba
        // ~9,96 M XP). Una ausencia real larga se acredita hasta el tope de un ano.
        if (xpLast < MIN_VALID_EPOCH_MS || xpLast > now) {
            p.edit().putLong(KEY_NURSERY_XP_LAST, now).putLong(KEY_NURSERY_EGG_LAST, now).putLong(KEY_NURSERY_CARE_LAST, now).apply()
            return@synchronized null
        }
        if (!force && now - xpLast < NURSERY_MIN_SETTLE_MS) return@synchronized null

        val hours = (now - xpLast).coerceIn(0L, MAX_ABSENCE_MS) / 3_600_000f
        val xpEach = NurseryRules.xpPerHour(TIME_XP_PER_H) * hours
        val eggLast = sanitizeClock(p.getLong(KEY_NURSERY_EGG_LAST, now), now)
        val plan = NurseryRules.planTicks(now - eggLast)
        val states = IntArray(keys.size) { p.getInt(KEY_NURSERY_EGG_STATE + keys[it], 0) }
        val probs = FloatArray(keys.size) { i ->
            val (n, sh) = parseSlotKey(keys[i])
            if (isFinalStage(context, n) && eggLayingEnabled(context, n)) {
                NurseryRules.layProbability(levelForPokemon(context, n, sh) ?: 1, EGG_LAY_BASE_PROB, EGG_LAY_LEVEL_SCALE, EGG_LAY_PROB_MAX)
            } else 0f
        }
        val eggs = NurseryRules.simulateEggs(plan.ticks, states, probs, Random)
        // Necesidades (comer/jugar/beber): mismos pasos y reglas que el compañero activo, ver simulateNurseryCare.
        val careLast = sanitizeClock(p.getLong(KEY_NURSERY_CARE_LAST, now), now)
        val carePlan = NurseryRules.planCareSteps(careLast, now)
        val careEvents = ArrayList<NurseryCareEvent>()
        val careXp = simulateNurseryCare(context, keys, carePlan.fromMs, carePlan.steps, careEvents)
        val edit = p.edit()
        keys.forEachIndexed { i, key -> edit.putInt(KEY_NURSERY_EGG_STATE + key, eggs.finalStates[i]) }
        edit.putLong(KEY_NURSERY_XP_LAST, now).putLong(KEY_NURSERY_EGG_LAST, eggLast + plan.advanceMs)
            .putLong(KEY_NURSERY_CARE_LAST, carePlan.newLastMs).commit()

        var hatched = 0; var candies = 0; var duplicates = 0; var bonusTotal = 0f
        val shinies = ArrayList<String>()
        val gone = ArrayList<String>()
        keys.forEachIndexed { i, key ->
            val (parent, parentShiny) = parseSlotKey(key)
            if (!hasIndividual(context, parent, parentShiny) || hasEvolvedAway(context, parent, parentShiny)) {
                gone.add(key); return@forEachIndexed   // desaparecio (borrado/evoluciono por otro camino): se saca
            }
            val h = eggs.hatches[i]
            val bonus = h * EGG_HATCH_XP_BONUS * NurseryRules.HATCH_BONUS_FRACTION
            bonusTotal += bonus
            val gain = xpEach + bonus + careXp[i]
            if (gain > 0f) grantXpToIndividual(context, parent, parentShiny, gain)
            repeat(h) {
                hatched++
                val base = baseFormOf(context, parent)
                val species = REGIONAL_HATCH_VARIANT[base]?.let { v -> if (Random.nextFloat() < 0.5f) v else base } ?: base
                if (rollNurseryShiny(context, parent, parentShiny)) {
                    val id = evolutionInfo(context, species)?.id
                    val alreadyHad = p.contains(k(slot(species, true), KEY_XP))
                    if (!alreadyHad && id != null) { rollFreshIndividual(context, species, true, id); shinies.add(species) }
                    else { candies += NURSERY_DUPLICATE_SHINY_CANDIES; duplicates++ }
                } else candies++
            }
        }
        if (gone.isNotEmpty()) {
            p.edit().putStringSet(KEY_NURSERY_SET, nurserySlotKeys(context).toSet() - gone.toSet()).commit()
        }
        if (candies > 0) addCandies(context, candies)
        // Evolucion: con la XP ya repartida, los que alcanzan el nivel de alguna rama avisan UNA vez (hasta que evolucionen
        // o salgan); si ninguno puede ya, se retira el aviso que quedara en la barra.
        val readyNow = keys.filter { it !in gone }.filter { key -> val (n, sh) = parseSlotKey(key); evolutionReadyFor(context, n, sh) != null }.toSet()
        val alreadyTold = nurseryEvoNotified(context)
        val freshlyReady = (readyNow - alreadyTold).sorted()
        if (readyNow != alreadyTold) p.edit().putStringSet(KEY_NURSERY_EVO_NOTIFIED, HashSet(readyNow)).apply()
        if (readyNow.isEmpty() && alreadyTold.isNotEmpty()) NotificationHelper.cancelNurseryEvolution(context)
        val report = NurseryReport(hatched, candies, shinies, duplicates, xpEach, bonusTotal, careEvents, careXp.sum(), freshlyReady.map { parseSlotKey(it).first })
        if (careEvents.isNotEmpty()) {
            DebugLog.log(context, "guarderia: necesidades cubiertas ${careEvents.groupingBy { it.action }.eachCount()} (+${careXp.sum().toInt()} XP)")
        }
        if (hatched > 0) {
            DebugLog.log(context, "guarderia: $hatched huevo(s) abiertos -> $candies caramelo(s), shiny=${shinies.joinToString(",")}, shiny repetidos=$duplicates")
        }
        if (freshlyReady.isNotEmpty()) DebugLog.log(context, "guarderia: pueden evolucionar ${freshlyReady.joinToString(", ")}")
        if (hatched > 0 || freshlyReady.isNotEmpty()) NotificationHelper.deliverNurseryReport(context, report)
        report
    }

    /** Suelta el huevo (ver [releaseEgg]) y, si la mochila ya esta desbloqueada, da un caramelo a
     *  cambio. Devuelve true si se dio el caramelo. */
    fun releaseEggForCandy(context: Context): Boolean {
        releaseEgg(context)
        if (!candiesUnlocked(context)) return false
        addCandies(context, 1)
        return true
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
        val (freshHealth, freshHygiene, freshHappiness) = rollFreshStats()
        val e = p.edit()
            .putInt(k(n, KEY_ID), id)
            .putFloat(k(n, KEY_HEALTH), freshHealth)
            .putFloat(k(n, KEY_HYGIENE), freshHygiene)
            .putFloat(k(n, KEY_HAPPINESS), freshHappiness)
            .putFloat(k(n, KEY_XP), 0f)
            .putLong(k(n, KEY_LAST), System.currentTimeMillis())
        if (base in GENDER_SPECIES) e.putString(k(n, KEY_GENDER), rollGender(base))
        // Mismo motivo que en setPokemon - individuo fresco, historial de formas vistas a cero.
        e.remove(k(base, KEY_MECHANIC_SEEN))
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
        // Si estaba en la guarderia o en el grupo de una mazmorra, sale de ahi (antes quedaba un miembro fantasma hasta la
        // siguiente liquidacion / un explorador que ya no existe).
        DungeonState.dropMember(context, base, shiny)
        if (isInNursery(context, base, shiny)) nurseryRemove(context, base, shiny)
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
     * lo desbloquea en la Pokedex, lo deja como Pokemon ACTIVO del widget (salvo si estaba en la
     * GUARDERIA: ahi se queda, ya evolucionado, y el activo no cambia), y marca la especie
     * vieja como "evolucionada" (queda registrada en la Pokedex - se tuvo - pero ya no se puede
     * volver a seleccionar: el individuo que era ya es otra especie). NO descarga el sprite ni
     * refresca el widget - eso lo hace quien llama (necesita red/hilo de fondo). [toName] ya debe
     * venir resuelto (ver resolveEvolutionTarget) - esta funcion migra tal cual, sin sortear nada.
     */
    /** ¿[fromName] evolucionara SIN pasar a ser el Pokemon activo del widget? Si esta en la guarderia o
     *  en el grupo de una mazmorra (o sustituye a alguien que va en ese grupo) se queda donde esta, ya
     *  evolucionado. Misma regla que evolveToLocked - para que la interfaz sepa si [selected] cambia. */
    fun evolvesInPlace(context: Context, fromName: String, toName: String, shiny: Boolean): Boolean {
        val f = fromName.lowercase(); val t = toName.lowercase()
        val fromIsActive = currentPokemon(context) == f && isActiveShiny(context) == shiny
        return isInNursery(context, f, shiny) || DungeonState.isInParty(context, f, shiny) ||
            (DungeonState.isInParty(context, t, shiny) && !fromIsActive)
    }

    fun evolveTo(context: Context, fromName: String, toName: String, toId: Int, shiny: Boolean) =
        // Todo bajo el candado de las estadisticas: la guarderia se liquida en el tick del widget (otro hilo) y no puede ver al
        // Pokemon "evolucionado" a medias (ya sin la especie vieja y aun sin la nueva en la guarderia).
        synchronized(statsWriteLock) { evolveToLocked(context, fromName, toName, toId, shiny) }

    /** XP con la que el individuo llega a la especie nueva. Si las dos suben de nivel al mismo ritmo (casi siempre) es la misma XP. Si no (la linea
     *  de Applin mezcla ritmos: Applin y Hydrapple "erratico", Dipplin "lento"), la misma XP daria OTRO nivel - Applin Nv27 pasaba a Dipplin Nv18 y
     *  Dipplin Nv28 a Hydrapple Nv40 -, asi que se conserva el NIVEL y lo que lleva avanzado dentro de el, como en los juegos. */
    private fun xpForNewSpecies(context: Context, from: String, to: String, xp: Float): Float {
        if (growthRateOf(context, from) == growthRateOf(context, to)) return xp
        val level = levelOf(context, xp, from)
        if (level >= MAX_LEVEL) return cumXp(MAX_LEVEL, context, to)
        val progress = progressOf(context, xp, from)
        val a = cumXp(level, context, to); val b = cumXp(level + 1, context, to)
        val converted = a + progress * (b - a)
        DebugLog.log(context, "evolucion: $from -> $to cambia de ritmo de crecimiento (${growthRateOf(context, from)} -> ${growthRateOf(context, to)}): se conserva el nivel $level (XP ${xp.toInt()} -> ${converted.toInt()})")
        return converted
    }

    private fun evolveToLocked(context: Context, fromName: String, toName: String, toId: Int, shiny: Boolean) {
        val from = fromName.lowercase()
        val to = toName.lowercase()
        if (from == to) return
        // Si estaba en la GUARDERIA se queda ahi, ya evolucionado (pedido del usuario: "si evoluciona en la guarderia aparece
        // evolucionado"), y NO pasa a ser el Pokemon activo. Antes se liquida la guarderia: la XP que llevara hasta ahora se cobra
        // con el nombre viejo, y es la que se migra.
        // Lo mismo si es del grupo de la MAZMORRA (lider o compañero, o si quien sustituye va en ese grupo): sigue ahi con la
        // especie nueva y NO pasa a ser el activo - un explorador no puede ser el activo (QaConflicts: antes quedaban la forma vieja
        // explorando como fantasma y la nueva activa a la vez).
        val fromInParty = DungeonState.isInParty(context, from, shiny)
        var toInParty = DungeonState.isInParty(context, to, shiny)
        // El activo del widget que evoluciona a una especie que ahora explora sustituye a ese explorador (solo puede haber uno): como el
        // activo no puede explorar, ese individuo sale del grupo (si era el lider, la carrera se abandona).
        if (toInParty && !fromInParty && currentPokemon(context) == from && isActiveShiny(context) == shiny) {
            DungeonState.dropMember(context, to, shiny)
            toInParty = false
        }
        // Uno de la guarderia que sustituye a alguien del grupo de la mazmorra toma ese sitio: sale de la guarderia (no puede estar en las dos).
        if (toInParty && isInNursery(context, from, shiny)) nurseryRemove(context, from, shiny)
        val stayInNursery = isInNursery(context, from, shiny)
        val stayInDungeon = !stayInNursery && (fromInParty || toInParty)
        if (stayInNursery) settleNursery(context, force = true)
        // Si ya tenias la especie de destino, el que evoluciona SUSTITUYE a ese individuo (comportamiento acordado: no puede haber dos). Si ese
        // estaba en la guarderia y el que evoluciona va a ser el activo (no estaba en la guarderia), tiene que salir de ella: un Pokemon de la
        // guarderia no puede ser el activo (y si no, quedaria en las dos cosas a la vez).
        if (!stayInNursery && isInNursery(context, to, shiny)) nurseryRemove(context, to, shiny)
        val p = prefs(context)
        // [shiny] es del INDIVIDUO que evoluciona de verdad (normal o shiny, ver slot()) - viene
        // de quien llama, YA NO se asume el activo del widget (bug real: evolucionar un shiny
        // desde su ficha mientras el activo era el normal migraba las stats del NORMAL y dejaba
        // el shiny intacto). El evolucionado SI pasa a ser el nuevo activo (KEY_POKEMON/
        // KEY_ACTIVE_SHINY mas abajo), pero cual de los dos individuos era el que evolucionaba lo
        // decide el llamador, no un flag global.
        val fromSlot = slot(from, shiny)
        val toSlot = slot(to, shiny)
        val health = p.getFloatSafe(k(fromSlot, KEY_HEALTH), 100f)
        val hygiene = p.getFloatSafe(k(fromSlot, KEY_HYGIENE), 100f)
        val happiness = p.getFloatSafe(k(fromSlot, KEY_HAPPINESS), 100f)
        val xp = xpForNewSpecies(context, from, to, p.getFloatSafe(k(fromSlot, KEY_XP), 0f))
        val lastUpdate = p.getLong(k(fromSlot, KEY_LAST), System.currentTimeMillis())
        // Horas de cuidado: MISMO individuo, solo cambia de especie - sigue siendo un unico
        // contador continuo a traves de toda la cadena (pedido explicito del usuario).
        val careHours = p.getFloatSafe(k(fromSlot, KEY_CARE_HOURS), 0f)
        // Favorito: NO hace falta copiar nada aqui - se guarda bajo la RAIZ de la cadena (ver
        // isFavorite/setFavorite/favoriteChainKey), que es la misma antes y despues de
        // evolucionar, asi que ya "sigue" a la linea entera sin ningun paso extra.
        val e = p.edit()
        if (!stayInNursery && !stayInDungeon) e.putString(KEY_POKEMON, to).putBoolean(KEY_ACTIVE_SHINY, shiny)
        e.putInt(k(toSlot, KEY_ID), toId)
            .putFloat(k(toSlot, KEY_HEALTH), health)
            .putFloat(k(toSlot, KEY_HYGIENE), hygiene)
            .putFloat(k(toSlot, KEY_HAPPINESS), happiness)
            .putFloat(k(toSlot, KEY_XP), xp)
            .putLong(k(toSlot, KEY_LAST), lastUpdate)
            .putFloat(k(toSlot, KEY_CARE_HOURS), careHours)
            .putBoolean(k(to, KEY_SHINY), shiny)  // flag legacy (comparte con currentPokemon/isActiveShiny)
            // El individuo que acaba de llegar a [to] esta VIVO como esa especie: si esa misma slot ya
            // se recorrio antes en otra cadena (la cria de un huevo repite una linea ya completada),
            // conservaria el "evolucionado" de la vez anterior y la forma nueva no podria evolucionar
            // ni contaria para el nivel de entrenador.
            .putBoolean(k(toSlot, KEY_EVOLVED_AWAY), false)
            .remove(k(toSlot, KEY_EVOLVED_TO))
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
        if (stayInNursery) {
            // La plaza pasa a la especie nueva (mismo sitio, mismo huevo a medias); el activo del widget no cambia.
            val eggState = p.getInt(KEY_NURSERY_EGG_STATE + fromSlot, 0)
            e.putStringSet(KEY_NURSERY_SET, nurserySlotKeys(context).toSet() - fromSlot + toSlot)
                .remove(KEY_NURSERY_EGG_STATE + fromSlot)
                .putInt(KEY_NURSERY_EGG_STATE + toSlot, eggState)
        }
        e.commit()
        unlock(context, to)
        if (stayInDungeon) {
            if (fromInParty) DungeonState.renameMember(context, from, to, shiny)
            DebugLog.log(context, "mazmorra: $from evoluciona dentro del grupo y sigue como $to${if (shiny) " (shiny)" else ""}")
        }
        if (stayInNursery) {
            markNurseryEvoNotified(context, fromSlot, false)
            if (nurseryEvoNotified(context).isEmpty()) NotificationHelper.cancelNurseryEvolution(context)   // ya no queda nadie por evolucionar
            DebugLog.log(context, "guarderia: $from evoluciona dentro y se queda como $to${if (shiny) " (shiny)" else ""}")
        }
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
    // true en cuanto se resuelve/descarta un regalo por PRIMERA VEZ en la vida de la partida -
    // usado solo por catchUpStarterGiftIfWaiting para distinguir "todavia esperando el primer
    // regalo de siempre" de "ya en su segundo/tercer/... ciclo normal" (ver ese comentario).
    private const val KEY_OFFER_EVER_RESOLVED = "offer_ever_resolved"
    // HORARIO FIJO (pedido del usuario): el regalo aparece a las 9:00 y a las 21:00 de cada dia (ver [OfferSchedule]), no 12 h despues del anterior -
    // con aquello, quien empezaba por la tarde recibia el segundo por la madrugada. [KEY_OFFER_NEXT_AT] guarda cuando toca el siguiente (epoch ms):
    // al elegir/descartar el regalo pendiente pasa al primer reparto POSTERIOR a ese momento (abrir tarde no cambia las horas; solo se pierde el
    // reparto que llego mientras el anterior seguia sin abrir, porque solo cabe un regalo pendiente a la vez).
    private const val KEY_OFFER_NEXT_AT = "offer_next_at"
    private const val KEY_OFFER_SLOTS_MIGRATED = "offer_slots_migrated"   // true una vez convertido el ciclo antiguo (ancla + 12 h) al horario fijo
    private const val OFFER_DUE_NOW = 1L                                   // "ya toca" (un instante muy pasado)
    private const val OFFER_LEGACY_INTERVAL_MS = 12L * 3_600_000L          // intervalo del ciclo antiguo: solo para convertir partidas ya empezadas

    /** [shiny]: este candidato del regalo es la version shiny (sorteado al generar la oferta, ver
     *  maybeGenerateOffer, y guardado con ella: lo que se ve en el dialogo es lo que se obtiene).
     *  En el CSV de KEY_OFFER_SPECIES va como tercer campo opcional "nombre:id:s" - las ofertas
     *  ya guardadas por versiones anteriores ("nombre:id") se leen como no-shiny. */
    data class OfferMon(val name: String, val id: Int, val shiny: Boolean = false)

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

    private const val KEY_STARTER_GIFT_CATCHUP_DONE = "starter_gift_catchup_done"

    /** Migracion puntual (una sola vez por partida): jugadores que ya habian elegido inicial con
     *  la version VIEJA del regalo de bienvenida (ver markStarterChosen, antes hacia esperar el
     *  ciclo completo de 12h) y todavia siguen esperando su PRIMER regalo de siempre reciben
     *  tambien el regalo instantaneo al actualizar - pedido explicito del usuario: "no puedes
     *  detectar cuando un jugador tiene el starter solo para activar un regalo extra... es el
     *  caso de estos jugadores que ya tienen el juego y todavia no han pasado las 12 primeras
     *  horas". KEY_OFFER_EVER_RESOLVED (marcado en resolveOffer/dismissOffer) es la clave: solo
     *  es false para quien NUNCA ha llegado a abrir/descartar ningun regalo - un jugador ya en su
     *  segundo o tercer ciclo normal (que tambien podria estar "a menos de 12h" de su PROPIO
     *  regalo siguiente) no se toca, solo quien de verdad sigue esperando el primero. Llamar
     *  SIEMPRE antes de maybeGenerateOffer (no desde dentro de ella - forceNewOffer ya llama a
     *  maybeGenerateOffer, y encadenarla ahi dentro la reactivaria de forma redundante cada vez
     *  que se fuerza una oferta). */
    fun catchUpStarterGiftIfWaiting(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_STARTER_GIFT_CATCHUP_DONE, false)) return
        p.edit().putBoolean(KEY_STARTER_GIFT_CATCHUP_DONE, true).commit()
        if (!hasChosenStarter(context)) return
        if (p.getBoolean(KEY_OFFER_EVER_RESOLVED, false)) return
        if (currentOffer(context).isNotEmpty()) return
        forceNewOffer(context)
    }

    /** Genera el regalo si ya toca (a las 9:00 y a las 21:00 de la hora local, ver [OfferSchedule]) y no hay otro pendiente. Solo cabe uno
     *  pendiente a la vez: si el de las 9:00 sigue sin abrirse a las 21:00, el de las 21:00 no aparece (se pierde ese reparto) y el
     *  pendiente NO desaparece ni se fuerza. Al elegir/descartar, el siguiente llega en el primer reparto posterior a ese momento (ver
     *  resolveOffer/dismissOffer). Devuelve true solo si de verdad ha generado una oferta nueva (para poder notificar). */
    fun maybeGenerateOffer(context: Context): Boolean {
        val p = prefs(context)
        val hasPending = !p.getString(KEY_OFFER_SPECIES, "").isNullOrEmpty()
        if (hasPending) return false
        if (System.currentTimeMillis() < offerNextAt(context)) return false
        val candidates = loadEvoTable(context).entries
            .filter { (name, entry) -> entry.evolvesFrom == null && !isUnlocked(context, name) }
            .map { (name, entry) -> OfferMon(name, entry.id) to (if (entry.rare) WEIGHT_RARE else WEIGHT_NORMAL) }
        if (candidates.isEmpty()) return false   // ya se tiene absolutamente todo
        // Cada candidato tira SU shiny por separado (misma probabilidad que un huevo, ver rollShiny).
        val picked = weightedSampleWithoutReplacement(candidates, 5)
            .map { it.copy(shiny = rollShiny(context, it.name)) }
        val csv = encodeOffer(picked)
        p.edit().putString(KEY_OFFER_SPECIES, csv).commit()
        return true
    }

    /** Milisegundos que faltan hasta el proximo reparto (9:00 / 21:00) en el que aparecera el regalo; 0 si ya toca (o si hay uno pendiente
     *  sin abrir). Sigue contando siempre (nunca se oculta ni se congela). */
    fun offerCooldownRemainingMs(context: Context): Long =
        (offerNextAt(context) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** La oferta pendiente ahora mismo (vacia si no hay ninguna). */
    fun currentOffer(context: Context): List<OfferMon> {
        return decodeOffer(prefs(context).getString(KEY_OFFER_SPECIES, "") ?: "")
    }

    private fun encodeOffer(offer: List<OfferMon>): String =
        offer.joinToString(",") { "${it.name}:${it.id}" + if (it.shiny) ":s" else "" }

    private fun decodeOffer(csv: String): List<OfferMon> {
        if (csv.isEmpty()) return emptyList()
        return csv.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            val id = parts.getOrNull(1)?.toIntOrNull()
            if (parts.isNotEmpty() && id != null) OfferMon(parts[0], id, parts.getOrNull(2) == "s") else null
        }
    }

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_OFFER_SHINY_CHECK): comprueba, SIN
     *  tocar la partida, que el formato guardado del regalo conserva el shiny (y que una oferta
     *  vieja sin ese campo se sigue leyendo) y que la tirada de shiny del regalo sale con la
     *  misma frecuencia que la del huevo. Devuelve el informe. */
    fun debugOfferShinySelfTest(context: Context, trials: Int = 200_000): String {
        val sb = StringBuilder()
        val sample = listOf(OfferMon("pikachu", 25, true), OfferMon("eevee", 133), OfferMon("mew", 151, true))
        val back = decodeOffer(encodeOffer(sample))
        sb.append("ida y vuelta del CSV: ${encodeOffer(sample)} -> ${if (back == sample) "OK" else "FALLO $back"}" + System.lineSeparator())
        val legacy = decodeOffer("pikachu:25,eevee:133")
        sb.append("oferta vieja sin campo shiny: ${if (legacy == listOf(OfferMon("pikachu", 25), OfferMon("eevee", 133))) "OK" else "FALLO $legacy"}" + System.lineSeparator())
        for (name in listOf("pikachu", "bulbasaur", "gastly")) {
            val expected = EGG_SHINY_CHANCE * bgMatchMultiplier(context, name)
            var hits = 0
            repeat(trials) { if (rollShiny(context, name)) hits++ }
            sb.append("tirada shiny $name (fondo ${currentBg(context)}): esperado ${"%.4f".format(expected)}, observado ${"%.4f".format(hits.toDouble() / trials)} en $trials" + System.lineSeparator())
        }
        return sb.toString().trimEnd()
    }

    /** Cuando toca el siguiente regalo (epoch ms; un valor ya pasado = "ya toca"). Convierte UNA vez el ciclo antiguo (ancla + 12 h) al
     *  horario fijo: el siguiente pasa al primer reparto (9:00/21:00) desde esa hora. Un valor muy en el futuro solo puede venir de un movil
     *  con la hora adelantada y luego corregida (entre dos repartos nunca hay mas de [OfferSchedule.MAX_GAP_MS]): congelaria el regalo todo
     *  ese tiempo (QaRobust: 30 dias), asi que se recoloca en el proximo reparto desde AHORA. */
    private fun offerNextAt(context: Context): Long {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (!p.getBoolean(KEY_OFFER_SLOTS_MIGRATED, false)) {
            val last = p.getLong(KEY_OFFER_LAST, 0L)   // formato antiguo: inicio del ciclo
            val next = when {
                last <= 0L -> OFFER_DUE_NOW
                last > now -> OfferSchedule.nextSlotAfter(now)
                else -> OfferSchedule.firstSlotAtOrAfter(last + OFFER_LEGACY_INTERVAL_MS)
            }
            p.edit().putLong(KEY_OFFER_NEXT_AT, next).putBoolean(KEY_OFFER_SLOTS_MIGRATED, true).commit()
            return next
        }
        val next = p.getLong(KEY_OFFER_NEXT_AT, OFFER_DUE_NOW)
        if (next - now > OfferSchedule.MAX_GAP_MS) {
            val fixed = OfferSchedule.nextSlotAfter(now)
            p.edit().putLong(KEY_OFFER_NEXT_AT, fixed).commit()
            return fixed
        }
        return next
    }

    /** Deja el siguiente regalo para el primer reparto POSTERIOR a ahora (al elegir/descartar el pendiente). */
    private fun scheduleNextOffer(editor: android.content.SharedPreferences.Editor) {
        editor.putLong(KEY_OFFER_NEXT_AT, OfferSchedule.nextSlotAfter(System.currentTimeMillis())).putBoolean(KEY_OFFER_SLOTS_MIGRATED, true)
    }

    /** Elige [name] de la oferta actual: lo desbloquea Y le crea sus valores iniciales de una vez
     *  (nivel 1, barras al azar - ver rollFreshIndividual, pedido explicito del usuario: "cada vez
     *  que obtienes un Pokemon... se registran sus valores iniciales como si te lo hubieses
     *  puesto", aunque no se ponga de compañero al momento - a diferencia de antes, que solo lo
     *  apuntaba en la Pokedex y no le creaba nada hasta la primera vez que se cuidaba de verdad),
     *  sin cambiar el Pokemon activo, y limpia la oferta. El siguiente llega en el primer reparto (9:00 / 21:00) posterior a este momento:
     *  abrir tarde no mueve las horas. */
    fun resolveOffer(context: Context, name: String, shiny: Boolean = false) {
        val n = name.lowercase()
        // Solo se puede elegir a alguien de la oferta PENDIENTE: un doble toque en "Elegir" o un
        // dialogo viejo (la oferta ya se resolvio/descarto/cambio) llegaba aqui otra vez y, sin
        // esta guarda, regalaba un segundo Pokemon y retrasaba el ciclo otras 12 h (QaConflicts).
        val pending = currentOffer(context)
        if (pending.none { it.name == n && it.shiny == shiny }) {
            DebugLog.log(context, "regalo: resolveOffer($n, shiny=$shiny) ignorado - no esta en la oferta pendiente")
            return
        }
        unlock(context, n)
        val id = loadEvoTable(context)[n]?.id
        // [shiny] = el candidato elegido era shiny (ver OfferMon): se crea el individuo shiny.
        if (id != null) rollFreshIndividual(context, n, shiny, id)
        val editor = prefs(context).edit().putString(KEY_OFFER_SPECIES, "").putBoolean(KEY_OFFER_EVER_RESOLVED, true)
        scheduleNextOffer(editor)
        editor.commit()
    }

    /** Descarta la oferta actual sin elegir ninguno (si no gustan los 5, esperar a los
     *  siguientes en vez de dejarla pendiente para siempre). Mismo horario que en resolveOffer: el siguiente, en el primer reparto posterior. */
    fun dismissOffer(context: Context) {
        if (currentOffer(context).isEmpty()) return   // nada que descartar (doble toque): no mover el ciclo
        val editor = prefs(context).edit().putString(KEY_OFFER_SPECIES, "").putBoolean(KEY_OFFER_EVER_RESOLVED, true)
        scheduleNextOffer(editor)
        editor.commit()
    }

    /** Fuerza que aparezca una oferta nueva ahora mismo, sin esperar al reparto de las 9:00/21:00 - en origen pedido explicito del usuario
     *  tras descartar sin querer un regalo real, y ahora tambien usado por [markStarterChosen] para el regalo de bienvenida al elegir inicial
     *  (el siguiente, tras abrirlo, llega en el primer reparto posterior). Tambien se llama desde el broadcast de debug (ver
     *  PokeWidgetProvider.ACTION_DEBUG_FORCE_OFFER). */
    fun forceNewOffer(context: Context) {
        prefs(context).edit().putString(KEY_OFFER_SPECIES, "").putLong(KEY_OFFER_NEXT_AT, OFFER_DUE_NOW).putBoolean(KEY_OFFER_SLOTS_MIGRATED, true).commit()
        maybeGenerateOffer(context)
    }

    /** DEBUG: repara el horario del regalo: el siguiente pasa al primer reparto (9:00/21:00) posterior a AHORA. [hoursAgo] ya no se usa (se
     *  conserva para el broadcast de debug de antes, ver PokeWidgetProvider.ACTION_DEBUG_REPAIR_OFFER_ANCHOR). */
    @Suppress("UNUSED_PARAMETER")
    fun repairOfferAnchor(context: Context, hoursAgo: Float = 0f) {
        val editor = prefs(context).edit()
        scheduleNextOffer(editor)
        editor.commit()
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

    /** Tirada de shiny COMPARTIDA por el huevo (maybeLayEgg) y el regalo (maybeGenerateOffer) -
     *  pedido explicito del usuario: "que en el regalo tambien te pueda salir shiny con la misma
     *  probabilidad que el pokemon". Una sola funcion para que la probabilidad sea la misma por
     *  construccion: EGG_SHINY_CHANCE, x2 si el fondo puesto ahora le pega al tipo de [name] (ver
     *  bgMatchMultiplier). */
    private fun rollShiny(context: Context, name: String): Boolean =
        Random.nextFloat() < EGG_SHINY_CHANCE * bgMatchMultiplier(context, name)

    /** Multiplicador de shiny de un huevo de la GUARDERIA (pedido explicito del usuario): si tienes
     *  DESBLOQUEADO algun fondo que le pegue a algun tipo de [name]/[shiny] (ver BG_FOR_TYPE), se aplica
     *  solo - x2, igual que el fondo puesto en el widget - aunque ese fondo no sea el que tienes puesto
     *  ni se vea en ningun sitio; si no, x1. No toca el fondo del widget. */
    fun nurseryShinyMultiplier(context: Context, name: String, shiny: Boolean): Float {
        val types = typesOf(context, name, shiny)
        return if (types.any { t -> BG_FOR_TYPE[t]?.any { bg -> isBgUnlocked(context, bg) } == true }) 2f else 1f
    }

    private fun rollNurseryShiny(context: Context, name: String, shiny: Boolean): Boolean =
        Random.nextFloat() < EGG_SHINY_CHANCE * nurseryShinyMultiplier(context, name, shiny)
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
        val e = prefs(context).edit()
            .putBoolean(KEY_EGG_ACTIVE, true)
            .putString(KEY_EGG_PARENT, name)
            .putBoolean(KEY_EGG_PARENT_SHINY, isActiveShiny(context))
            .putLong(KEY_EGG_LAID_AT, System.currentTimeMillis())
            .putBoolean(KEY_EGG_SHINY, rollShiny(context, name))
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
    private fun awardEggHatchXpToParent(context: Context) = synchronized(statsWriteLock) {
        val parent = eggParent(context) ?: return@synchronized
        val p = prefs(context)
        val base = parent.lowercase()
        val recordedShiny = p.getBoolean(KEY_EGG_PARENT_SHINY, false)
        val n = listOf(slot(base, recordedShiny), slot(base, !recordedShiny))
            .firstOrNull { p.contains(k(it, KEY_XP)) } ?: return@synchronized
        val xp = try {
            p.getFloatSafe(k(n, KEY_XP), 0f)
        } catch (e: ClassCastException) {
            p.getInt(k(n, KEY_XP), 0).toFloat()
        }
        val (xpNew, xpOver) = capXpAtMaxLevel(context, base, xp, xp + EGG_HATCH_XP_BONUS)
        p.edit().putFloat(k(n, KEY_XP), xpNew).apply()
        bankOverflowXp(context, xpOver)
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
        // Si ese individuo (especie+shiny) esta ahora explorando una mazmorra no puede pasar a ser el activo (setPokemon lo habria
        // activado igualmente y quedaba a la vez de explorador y de compañero activo - QaConflicts): el huevo se queda sin abrir.
        if (DungeonState.isInParty(context, base, shiny)) return null
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
        val until = megaUntil(p, n)
        if (System.currentTimeMillis() >= until) return null
        return p.getString(k(n, KEY_MEGA_ACTIVE_NAME), null)
    }

    /** Fin de la Megaevolucion/Gigantamax activa de [n] (0 si no hay). Ninguna dura mas de
     *  MEGA_DURATION_MS: un fin MAS lejano solo sale de un movil con la hora adelantada y luego
     *  corregida (QaRobust: hasta 399 dias de Mega "activa") - se acorta a la duracion maxima
     *  desde AHORA para que no se quede activa (y sin poder volver a usarla) todo ese tiempo. */
    private fun megaUntil(p: android.content.SharedPreferences, n: String): Long {
        val until = p.getLong(k(n, KEY_MEGA_ACTIVE_UNTIL), 0L)
        val now = System.currentTimeMillis()
        if (until - now > MEGA_DURATION_MS) {
            val fixed = now + MEGA_DURATION_MS
            p.edit().putLong(k(n, KEY_MEGA_ACTIVE_UNTIL), fixed).apply()
            return fixed
        }
        return until
    }

    /** Milisegundos que quedan de Megaevolucion activa (0 si no hay ninguna). */
    fun megaActiveRemainingMs(context: Context, name: String): Long {
        val until = megaUntil(prefs(context), name.lowercase())
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Milisegundos que quedan de espera hasta poder volver a Megaevolucionar/Gigantamaxizar (0
     *  si ya se puede; cuenta desde la ULTIMA vez que se activo, no desde que termino). El
     *  enfriamiento depende de CUAL opcion fue esa ultima vez (KEY_MEGA_ACTIVE_NAME - Gigantamax
     *  se refresca antes que Megaevolucion, ver cooldownMsForMegaOption). */
    fun megaCooldownRemainingMs(context: Context, name: String): Long {
        val n = name.lowercase()
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val lastUsed = p.getLong(k(n, KEY_MEGA_LAST_USED_AT), 0L)
        if (lastUsed == 0L) return 0L
        val lastOption = p.getString(k(n, KEY_MEGA_ACTIVE_NAME), null) ?: return 0L
        // "Ultimo uso" en el futuro (hora del movil movida): se cuenta como si hubiera sido ahora.
        val readyAt = lastUsed.coerceAtMost(now) + cooldownMsForMegaOption(lastOption)
        return (readyAt - now).coerceAtLeast(0L)
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
        // Solo cuentan claves de ESPECIES REALES: una clave "<algo>.xp" suelta (restos de un bug
        // antiguo, otra app, un editor de preferencias) inflaba el nivel de entrenador (QaRobust:
        // 50 claves fantasma -> pozo 7 a 4982) y con el el acceso a mochila/guarderia/exploradores.
        val species = loadEvoTable(context)
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
                if (realSpecies !in species) continue
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
                if (key.removeSuffix(evolvedSuffix).removeSuffix(SHINY_SLOT_SUFFIX) in species) evolutionCount++
            }
        }
        return captureCount * TRAINER_XP_PER_CAPTURE +
            levelSum * TRAINER_XP_PER_MON_LEVEL +
            evolutionCount * TRAINER_XP_PER_EVOLUTION +
            shinyCount * TRAINER_XP_PER_SHINY
    }

    fun trainerLevel(context: Context): Int = trainerLevelOf(trainerXpPool(context))

    /** SOLO PRUEBAS (ACTION_DEBUG_TRAINER_REPORT): de donde sale el pozo del nivel de entrenador y cuanto hace falta para cada nivel. */
    fun debugTrainerReport(context: Context): String {
        val p = prefs(context)
        var captures = 0; var levelSum = 0; var evolutions = 0; var shinies = 0; var maxed = 0
        for ((key, value) in p.all) {
            if (key.endsWith(".$KEY_XP")) {
                val slotName = key.removeSuffix(".$KEY_XP")
                val shinySlot = slotName.endsWith(SHINY_SLOT_SUFFIX)
                val real = if (shinySlot) slotName.removeSuffix(SHINY_SLOT_SUFFIX) else slotName
                if (real !in loadEvoTable(context)) continue
                if (!p.getBoolean(k(slotName, KEY_EVOLVED_AWAY), false)) {
                    captures++
                    val xp = when (value) { is Float -> value; is Int -> value.toFloat(); else -> 0f }
                    val lv = levelOf(context, xp, real)
                    levelSum += lv; if (lv >= 100) maxed++
                    if (shinySlot) shinies++
                }
            } else if (key.endsWith(".$KEY_EVOLVED_AWAY") && value == true) evolutions++
        }
        val pool = trainerXpPool(context)
        val sb = StringBuilder()
        sb.append("pozo actual %.0f = capturas %d x%.0f + suma de niveles %d x%.1f + evoluciones %d x%.0f + shiny %d x%.0f -> nivel de entrenador %d (maximo guardado %d); Pokemon al 100: %d".format(
            pool, captures, TRAINER_XP_PER_CAPTURE, levelSum, TRAINER_XP_PER_MON_LEVEL, evolutions, TRAINER_XP_PER_EVOLUTION, shinies, TRAINER_XP_PER_SHINY,
            trainerLevel(context), trainerLevelMax(context), maxed)).append(System.lineSeparator())
        sb.append("pozo necesario por nivel: ")
        for (l in listOf(11, 15, 20, 25, 30, 35, 50, 75, 100)) sb.append("Nv%d=%.0f  ".format(l, cumXpTrainer(l)))
        sb.append(System.lineSeparator())
        sb.append("comprobacion de la curva: nivel con pozo 9600=${trainerLevelOf(9600f)}, 9700=${trainerLevelOf(9700f)}, 50000=${trainerLevelOf(50000f)}")
        // Con lo que ya tienes: si TODOS tus Pokemon actuales estuvieran al 100, ¿llegarias al 100 de entrenador?
        val ceiling = captures * (TRAINER_XP_PER_CAPTURE + 100 * TRAINER_XP_PER_MON_LEVEL) + evolutions * TRAINER_XP_PER_EVOLUTION + shinies * TRAINER_XP_PER_SHINY
        sb.append(System.lineSeparator()).append("techo con tu coleccion actual (todos al 100): pozo %.0f -> nivel %d".format(ceiling, trainerLevelOf(ceiling)))
        return sb.toString()
    }

    /** Progreso (0f..1f) del nivel de entrenador hacia el siguiente - 1f si ya esta al maximo. */
    fun trainerProgress(context: Context): Float {
        val pool = trainerXpPool(context)
        val lvl = trainerLevelOf(pool)
        if (lvl >= MAX_LEVEL) return 1f
        val a = cumXpTrainer(lvl); val b = cumXpTrainer(lvl + 1)
        return if (b > a) ((pool - a) / (b - a)).coerceIn(0f, 1f) else 0f
    }

    // ==================== FICHA DE ENTRENADOR ====================
    // Nickname + retrato (elegido entre assets/trainers/*.png, ver TrainerSprites) + un Pokemon
    // favorito destacado (elegido de entre los que ya cuentan como favoritos de verdad, ver
    // isFavoriteSpecies en MainActivity - misma condicion centralizada aqui). Pedido explicito
    // del usuario: nickname se pide en el primer arranque (antes de elegir inicial) y se puede
    // cambiar cuando sea; el favorito destacado permite elegir tambien la forma exacta ya vista
    // (mega/genero/formas) via speciesMechanics, no solo la especie+shiny.
    private const val KEY_TRAINER_NAME = "trainer_name"
    private const val KEY_TRAINER_SPRITE = "trainer_sprite"
    private const val KEY_SHOWCASE_MON = "showcase_mon"
    private const val KEY_SHOWCASE_SHINY = "showcase_shiny"
    private const val KEY_SHOWCASE_FORM = "showcase_form"      // spriteKey de MechanicForm, "" = forma base
    const val DEFAULT_TRAINER_SPRITE = "CHICO"                 // existe en assets/trainers

    /** true una vez el jugador ya puso su nickname - gate de primer arranque (ver
     *  TrainerSetupActivity), igual de auto-suficiente que hasChosenStarter/KEY_STARTER_DONE. */
    fun hasTrainerProfile(context: Context): Boolean = trainerName(context).isNotEmpty()

    fun trainerName(context: Context): String = prefs(context).getString(KEY_TRAINER_NAME, "") ?: ""

    /** commit (no apply): el gate de arranque en MainActivity.onCreate lee esto en el mismo
     *  instante tras guardarlo (ver TrainerSetupActivity), no puede arriesgarse a una lectura
     *  desactualizada por una escritura asincrona aun en vuelo. */
    fun setTrainerName(context: Context, name: String) =
        prefs(context).edit().putString(KEY_TRAINER_NAME, name.trim()).commit()

    fun trainerSprite(context: Context): String =
        prefs(context).getString(KEY_TRAINER_SPRITE, DEFAULT_TRAINER_SPRITE) ?: DEFAULT_TRAINER_SPRITE

    fun setTrainerSprite(context: Context, key: String) =
        prefs(context).edit().putString(KEY_TRAINER_SPRITE, key).apply()

    /** Especies+shiny que cuentan como favoritas AHORA MISMO - misma condicion exacta que
     *  MainActivity.isFavoriteSpecies (individuo existe, no evoluciono ya lejos de esta forma, y
     *  esta marcado favorito), centralizada aqui para que la use tambien el selector de favorito
     *  destacado de la ficha de entrenador. */
    fun favoriteIndividuals(context: Context): List<Pair<String, Boolean>> =
        loadEvoTable(context).keys.flatMap { name ->
            listOf(false, true).filter { shiny ->
                hasIndividual(context, name, shiny) && !hasEvolvedAway(context, name, shiny) && isFavorite(context, name, shiny)
            }.map { shiny -> name to shiny }
        }

    /** Todos los individuos (especie+shiny) que el jugador posee de verdad ahora mismo - mismo
     *  criterio que favoriteIndividuals pero SIN el filtro de favorito, para el selector "que
     *  Pokemon mandar a la mazmorra" (cualquiera ya tuyo, no solo los marcados favoritos). */
    fun ownedIndividuals(context: Context): List<Pair<String, Boolean>> =
        loadEvoTable(context).keys.flatMap { name ->
            listOf(false, true).filter { shiny ->
                hasIndividual(context, name, shiny) && !hasEvolvedAway(context, name, shiny)
            }.map { shiny -> name to shiny }
        }

    /** Favorito destacado elegido para la ficha, o null si no se ha elegido ninguno todavia o el
     *  guardado ya no es valido (se dejo de marcar favorito, o evoluciono lejos de esa forma) -
     *  se revalida contra favoriteIndividuals() en cada lectura, nunca se muestra un favorito
     *  "fantasma" que ya no existe de verdad. */
    fun showcaseFavorite(context: Context): Triple<String, Boolean, String>? {
        val name = prefs(context).getString(KEY_SHOWCASE_MON, "") ?: ""
        if (name.isEmpty()) return null
        val shiny = prefs(context).getBoolean(KEY_SHOWCASE_SHINY, false)
        if ((name to shiny) !in favoriteIndividuals(context)) return null
        return Triple(name, shiny, prefs(context).getString(KEY_SHOWCASE_FORM, "") ?: "")
    }

    fun setShowcaseFavorite(context: Context, name: String, shiny: Boolean, form: String) =
        prefs(context).edit()
            .putString(KEY_SHOWCASE_MON, name)
            .putBoolean(KEY_SHOWCASE_SHINY, shiny)
            .putString(KEY_SHOWCASE_FORM, form)
            .apply()

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

    // Total de fondos que se pueden desbloquear (uno por tipo, ver BG_FOR_TYPE): las fichas nunca pasan
    // de los que AUN esten bloqueados - antes, con todos abiertos, desde el nivel 36 seguia saliendo
    // una ficha disponible (flecha y contador fantasma).
    private val ALL_BACKGROUNDS: Set<String> = BG_FOR_TYPE.values.flatten().toSet()

    fun trainerLevelsPerToken(): Int = TRAINER_LEVELS_PER_TOKEN

    fun bgTokensAvailable(context: Context): Int {
        val earned = trainerLevel(context) / TRAINER_LEVELS_PER_TOKEN - (unlockedBackgrounds(context).size - 1)
        val stillLocked = (ALL_BACKGROUNDS - unlockedBackgrounds(context)).size
        return earned.coerceAtLeast(0).coerceAtMost(stillLocked)
    }

    // ==================== NIVEL MAXIMO DE ENTRENADOR + DESBLOQUEOS ====================
    // El pozo del nivel de entrenador NO es monotono (borrar un individuo lo baja, quedarse un huevo
    // cuya base ya evoluciono resta, etc.) y se recalcula recorriendo TODAS las preferencias. Las
    // mecanicas que se desbloquean con el nivel (mochila, guarderia, mas exploradores - ver
    // UNLOCK_*) usan el MAXIMO ya alcanzado, guardado, para que un peldaño no se vuelva a cerrar.
    private const val KEY_TRAINER_LEVEL_MAX = "trainer_level_max"
    private const val KEY_TRAINER_LEVEL_CHECKED_AT = "trainer_level_checked_at"
    private const val TRAINER_LEVEL_RECHECK_MS = 5 * 60_000L

    /** Nivel de entrenador mas alto alcanzado jamas (lectura barata, sin recalcular). */
    fun trainerLevelMax(context: Context): Int = prefs(context).getInt(KEY_TRAINER_LEVEL_MAX, 1)

    /** SOLO PARA LAS AUTOCOMPROBACIONES DE ADB: nivel de entrenador maximo guardado tal cual (null si aun no hay). */
    fun debugRawTrainerLevelMax(context: Context): Int? =
        prefs(context).takeIf { it.contains(KEY_TRAINER_LEVEL_MAX) }?.getInt(KEY_TRAINER_LEVEL_MAX, 1)

    /** SOLO PARA LAS AUTOCOMPROBACIONES DE ADB: fuerza (o con null, borra) el nivel maximo guardado,
     *  para probar mecanicas de niveles que el jugador aun no tiene. Hay que restaurarlo despues. */
    fun debugSetTrainerLevelMax(context: Context, level: Int?) {
        val e = prefs(context).edit()
        if (level == null) e.remove(KEY_TRAINER_LEVEL_MAX) else e.putInt(KEY_TRAINER_LEVEL_MAX, level)
        e.commit()
    }

    /** Recalcula el nivel actual y sube el maximo guardado si procede. Devuelve el maximo y el
     *  nivel desde el que se subio (null si no subio, o si es la PRIMERA vez que se calcula - en esa
     *  primera vez no se avisa de nada: un jugador que ya iba por el nivel 15 no debe ver 14 avisos). */
    fun noteTrainerLevel(context: Context): Pair<Int, Int?> {
        val p = prefs(context)
        val current = trainerLevel(context)
        val firstTime = !p.contains(KEY_TRAINER_LEVEL_MAX)
        val stored = p.getInt(KEY_TRAINER_LEVEL_MAX, 1)
        p.edit().putLong(KEY_TRAINER_LEVEL_CHECKED_AT, System.currentTimeMillis()).apply()
        if (current > stored || firstTime) {
            val newMax = maxOf(current, stored)
            p.edit().putInt(KEY_TRAINER_LEVEL_MAX, newMax).apply()
            return newMax to (if (firstTime) null else stored)
        }
        return stored to null
    }

    /** Como [trainerLevelMax], pero revisando de vez en cuando (cada 5 min como mucho) si el nivel
     *  ha subido - para decisiones de desbloqueo fuera de la pantalla principal (guarderia, huevos...). */
    fun trainerLevelMaxFresh(context: Context): Int {
        val p = prefs(context)
        val age = System.currentTimeMillis() - p.getLong(KEY_TRAINER_LEVEL_CHECKED_AT, 0L)
        return if (!p.contains(KEY_TRAINER_LEVEL_MAX) || age > TRAINER_LEVEL_RECHECK_MS) noteTrainerLevel(context).first
        else trainerLevelMax(context)
    }

    // Niveles de entrenador a los que se desbloquea cada mecanica (escalera acordada con el usuario).
    const val UNLOCK_CANDIES_LEVEL = 4        // mochila y caramelos
    const val UNLOCK_NURSERY_LEVEL = 6        // guarderia (1.a plaza)

    /** Lo que se desbloquea al llegar a [level] (texto para el aviso de subida de nivel), vacio si nada. */
    fun unlocksAtTrainerLevel(level: Int): List<String> = buildList {
        if (level == UNLOCK_CANDIES_LEVEL) add("Mochila y caramelos: soltar un huevo da un caramelo con el que subir a cualquier Pokémon")
        if (level == ExplorerPartyRules.FIRST_COMPANION_LEVEL) add("Compañero de mazmorra: tu explorador puede llevar a otro Pokémon, y se reparten el XP")
        if (level == ExplorerPartyRules.SECOND_COMPANION_LEVEL) add("Segundo compañero de mazmorra: el XP se reparte entre tres")
        if (level == UNLOCK_NURSERY_LEVEL) add("Guardería: un Pokémon dentro gana XP sin que lo cuides")
        else if (level > UNLOCK_NURSERY_LEVEL && NurseryRules.slotsForTrainerLevel(level) > NurseryRules.slotsForTrainerLevel(level - 1)) {
            add("Guardería: ya tienes ${NurseryRules.slotsForTrainerLevel(level)} plazas")
        }
    }

    /** Descripcion de la ficha de fondos para la UI (antes decia "cada 5 niveles" aunque eran 2). */
    fun bgTokenLabelEvery(): String = "cada $TRAINER_LEVELS_PER_TOKEN niveles de entrenador"

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

    /** SOLO PARA PRUEBAS (broadcast de debug): repara un mechanic_forms_seen ya corrupto de ANTES
     *  del arreglo de arriba (setPokemon/rollFreshIndividual ahora lo limpian ellos solos en un
     *  individuo fresco) - pedido explicito del usuario tras reportar "tengo desbloqueados los dos
     *  generos de Shinx cuando no los tengo". Deja SOLO [keep] en el registro (o lo vacia del todo
     *  si [keep] es null). */
    fun repairMechanicFormsSeen(context: Context, name: String, keep: String?) {
        val key = k(name.lowercase(), KEY_MECHANIC_SEEN)
        val edit = prefs(context).edit()
        if (keep != null) edit.putStringSet(key, setOf(keep)) else edit.remove(key)
        edit.commit()
    }

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
