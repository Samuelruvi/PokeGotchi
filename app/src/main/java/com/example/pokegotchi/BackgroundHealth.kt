package com.example.pokegotchi

import android.content.Context
import android.content.Intent

/**
 * Salud del segundo plano EN GENERAL (no solo la mazmorra) - pedido explicito del usuario: que las
 * barras no se queden quietas, que el huevo cambie de fase... y que, cuando el widget se vea
 * parado, tocar al Pokemon lo reactive (sin ningun icono de aviso).
 *
 * Un widget (RemoteViews) no ejecuta codigo propio: no puede ni detectar ni avisar de que esta
 * parado - eso lo nota el jugador al verlo. Lo que se mide aqui lo usa el codigo que SI corre:
 *  - el servicio de la mazmorra late cada ciclo (DungeonState.markHeartbeat), y
 *  - el tick periodico del widget (ACTION_TICK: baja las barras, avanza el huevo, manda los
 *    avisos) deja su hora para saber si lleva demasiado sin correr.
 * Con eso, un toque al Pokemon (PokeWidgetProvider.recoverBackgroundOnUserAction) reactiva lo que
 * falte, y el servicio de la mazmorra vigila el tick por si la alarma se pierde (guardian).
 */
object BackgroundHealth {
    private const val PREFS = "pokegotchi_background_health"
    private const val KEY_LAST_WIDGET_TICK = "last_widget_tick_at"

    /** El tick periodico es cada 15 min: pasado este tiempo sin correr se considera atrasado (un
     *  periodo y algo de holgura para el aplazamiento normal de Doze). */
    const val WATCHDOG_MS = 25 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** El tick periodico del widget acaba de arrancar (o se ha reactivado a mano). */
    fun markWidgetTick(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_WIDGET_TICK, System.currentTimeMillis()).apply()
    }

    /** Primera vez (instalacion/actualizacion): arranca el reloj para poder medir atrasos despues.
     *  NUNCA pisa un valor real - si el refresco periodico del sistema lo reescribiera, taparia
     *  justo el atraso que se quiere detectar. */
    fun initIfUnset(context: Context) {
        if (prefs(context).getLong(KEY_LAST_WIDGET_TICK, 0L) <= 0L) markWidgetTick(context)
    }

    fun widgetTickOverdue(context: Context, afterMs: Long = WATCHDOG_MS): Boolean {
        val last = prefs(context).getLong(KEY_LAST_WIDGET_TICK, 0L)
        return last > 0L && System.currentTimeMillis() - last > afterMs
    }

    /** true si hay algo parado que un toque del usuario deberia reactivar. */
    fun needsRecovery(context: Context): Boolean =
        DungeonState.isBackgroundStalled(context) || widgetTickOverdue(context)

    private const val KEY_ACK_UNTIL = "recovery_ack_until"
    private const val KEY_INTERFACE_HIDDEN = "interface_hidden"
    /** Tras tocar al Pokemon la interfaz se queda a la vista este tiempo aunque algo siga parado
     *  (p.ej. Android bloqueo reanudar el servicio): sin esto, un servicio que no se puede
     *  reanudar dejaria la interfaz oculta PARA SIEMPRE y no se podria ni dar de comer. */
    private const val ACK_MS = 30 * 60_000L

    /** El jugador ya ha tocado al Pokemon para reactivar - la interfaz vuelve (ver ACK_MS). */
    fun ackRecovery(context: Context) {
        prefs(context).edit().putLong(KEY_ACK_UNTIL, System.currentTimeMillis() + ACK_MS).apply()
    }

    /** true si hay que ocultar TODA la interfaz y dejar solo al Pokemon (pedido explicito del
     *  usuario: "ocultas toda la interfaz y dejas solo el pokemon, y asi le tienes que dar al
     *  pokemon para que aparezca la interfaz ya actualizada"). */
    fun shouldHideInterface(context: Context): Boolean =
        needsRecovery(context) && System.currentTimeMillis() > prefs(context).getLong(KEY_ACK_UNTIL, 0L)

    /** Deja en el log cuando la interfaz se oculta o vuelve (solo en el CAMBIO) - sin capturas de
     *  pantalla es la forma de saber que decidio el widget. */
    fun noteInterfaceState(context: Context, hidden: Boolean) {
        val p = prefs(context)
        if (p.getBoolean(KEY_INTERFACE_HIDDEN, false) == hidden) return
        p.edit().putBoolean(KEY_INTERFACE_HIDDEN, hidden).apply()
        DebugLog.log(
            context,
            if (hidden) "mazmorra: interfaz del widget OCULTA (servicio parado=${DungeonState.isBackgroundStalled(context)}, tick atrasado=${widgetTickOverdue(context)})"
            else "mazmorra: interfaz del widget visible de nuevo"
        )
    }

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_BG_STALL). */
    fun debugSetLastTick(context: Context, minutesAgo: Long) {
        prefs(context).edit().putLong(KEY_LAST_WIDGET_TICK, System.currentTimeMillis() - minutesAgo * 60_000L).apply()
    }

    /** SOLO PARA PRUEBAS: quita el acuse de un toque anterior para que la interfaz se pueda volver
     *  a ocultar sin esperar a que pasen los ACK_MS. */
    fun debugClearAck(context: Context) {
        prefs(context).edit().putLong(KEY_ACK_UNTIL, 0L).apply()
    }
}
