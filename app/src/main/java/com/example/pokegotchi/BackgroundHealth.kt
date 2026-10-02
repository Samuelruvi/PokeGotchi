package com.example.pokegotchi

import android.content.Context

/**
 * Salud del segundo plano del programa - pedido explicito del usuario: que el widget no parezca
 * funcionar cuando no lo hace, y que tocar al Pokemon lo reactive (sin ningun icono de aviso).
 *
 * Un widget (RemoteViews) no ejecuta codigo propio: no puede ni detectar ni mostrar que esta
 * parado - solo lo hace el codigo del programa que SIGUE vivo. Se miden dos cosas distintas:
 *
 *  1. SERVICIO DE LA MAZMORRA parado ([DungeonState.isBackgroundStalled]): hay carrera/cooldown y
 *     el servicio no esta corriendo. Es el fallo real mas probable (el proceso muere, las alarmas
 *     del widget lo reviven en uno nuevo, pero Android no deja arrancar el servicio desde la
 *     alarma). Es lo UNICO que oculta la interfaz: cualquier codigo vivo que pueda pintar el
 *     widget tambien puede pintar las barras y el huevo al dia, asi que ocultarlas por eso seria
 *     esconder datos buenos.
 *  2. TICK PERIODICO del widget atrasado ([widgetTickOverdue], ACTION_TICK: avisos, huevo,
 *     alarmas): no oculta nada - lo usan el guardian del servicio (lo relanza) y el toque al
 *     Pokemon (PokeWidgetProvider.recoverBackgroundOnUserAction).
 */
object BackgroundHealth {
    private const val PREFS = "pokegotchi_background_health"
    private const val KEY_LAST_WIDGET_TICK = "last_widget_tick_at"
    private const val KEY_ACK_UNTIL = "recovery_ack_until"
    private const val KEY_INTERFACE_HIDDEN = "interface_hidden"

    /** El tick periodico es cada 15 min: pasado este tiempo sin correr se considera atrasado (un
     *  periodo y algo de holgura para el aplazamiento normal de Doze). */
    const val WATCHDOG_MS = 25 * 60_000L

    /** Tras tocar al Pokemon la interfaz se queda a la vista este tiempo aunque algo siga parado
     *  (p.ej. Android bloqueo reanudar el servicio): sin esto, un servicio que no se puede
     *  reanudar dejaria la interfaz oculta PARA SIEMPRE y no se podria ni dar de comer. */
    private const val ACK_MS = 30 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** El widget acaba de hacer un refresco completo (tick periodico, onUpdate, o reactivado a mano). */
    fun markWidgetTick(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_WIDGET_TICK, System.currentTimeMillis()).apply()
    }

    fun widgetTickOverdue(context: Context, afterMs: Long = WATCHDOG_MS): Boolean {
        val last = prefs(context).getLong(KEY_LAST_WIDGET_TICK, 0L)
        return last > 0L && System.currentTimeMillis() - last > afterMs
    }

    /** true si hay algo parado que un toque del usuario deberia reactivar. */
    fun needsRecovery(context: Context): Boolean =
        DungeonState.isBackgroundStalled(context) || widgetTickOverdue(context)

    /** El jugador ya ha tocado al Pokemon para reactivar - la interfaz vuelve (ver ACK_MS). */
    fun ackRecovery(context: Context) {
        prefs(context).edit().putLong(KEY_ACK_UNTIL, System.currentTimeMillis() + ACK_MS).apply()
    }

    /** true si hay que ocultar TODA la interfaz y dejar solo al Pokemon "dormido" (pedido
     *  explicito del usuario). Solo por el servicio de la mazmorra parado - ver la cabecera. */
    fun shouldHideInterface(context: Context): Boolean =
        DungeonState.isBackgroundStalled(context) && System.currentTimeMillis() > prefs(context).getLong(KEY_ACK_UNTIL, 0L)

    /** Ultimo estado con el que se pinto el widget (ver noteInterfaceState). */
    fun isInterfaceHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_INTERFACE_HIDDEN, false)

    /** Deja en el log cuando la interfaz se oculta o vuelve (solo en el CAMBIO) - sin capturas de
     *  pantalla es la forma de saber que decidio el widget. */
    fun noteInterfaceState(context: Context, hidden: Boolean) {
        if (isInterfaceHidden(context) == hidden) return
        prefs(context).edit().putBoolean(KEY_INTERFACE_HIDDEN, hidden).apply()
        DebugLog.log(
            context,
            if (hidden) "mazmorra: interfaz del widget OCULTA (el servicio de la mazmorra no esta corriendo)"
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
