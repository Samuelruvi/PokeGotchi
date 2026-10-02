package com.example.pokegotchi

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Avance de la mazmorra EN SEGUNDO PLANO, mientras la pantalla de la mazmorra (DungeonActivity)
 * no esta abierta - sustituye a DungeonTickReceiver/AlarmManager (retirado). Un AlarmManager
 * normal se verifico funcionando bien en pruebas cortas (dumpsys alarm mostraba disparos
 * puntuales cada ~1-2 min sin fallos), pero el usuario reporto varias veces seguidas que "sigue
 * sin funcionar en segundo plano" tras ratos largos con la app cerrada - un servicio en PRIMER
 * PLANO con notificacion permanente (la misma silenciosa que ya existia,
 * NotificationHelper.refreshDungeonNotification) es mucho mas resistente a que MIUI/Doze mate el
 * proceso en periodos de inactividad largos, porque mantiene el proceso vivo el mismo (bucle
 * propio de Handler) en vez de depender de que el sistema despierte el proceso puntualmente para
 * cada disparo de alarma.
 *
 * Se arranca/reprograma desde MainActivity.onCreate (por si el proceso se reinicio con una
 * carrera ya en curso) y desde DungeonActivity.onPause (al salir de la vista en vivo).
 * DungeonActivity.onResume no lo PARA (la notificacion debe seguir viva) - solo activa
 * [pauseForLiveView] para que deje de simular pasos mientras la vista en vivo hace stepOnce por
 * su cuenta, evitando que los dos muten el mismo estado a la vez.
 */
class DungeonService : Service() {
    companion object {
        private const val TICK_INTERVAL_MS = 60_000L
        @Volatile private var liveViewActive = false

        /** true mientras exista la instancia del servicio EN ESTE PROCESO. Es la señal fiable de
         *  "la mazmorra tiene su segundo plano vivo" (ver DungeonState.isBackgroundStalled): un
         *  proceso nuevo (el anterior murio) arranca con false, mientras que con Doze el proceso
         *  sigue vivo y el servicio tambien aunque su Handler duerma. NO se puede usar el latido
         *  (tiempo desde el ultimo tick): en 126 h de log real, el 54 % del tiempo el latido
         *  estaba a mas de 4 min con el MISMO proceso vivo (345 huecos asi frente a 3 procesos
         *  muertos de verdad). */
        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DungeonService::class.java))
        }

        fun pauseForLiveView() { liveViewActive = true }
        fun resumeBackgroundTicks() { liveViewActive = false }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            // COOLDOWN (pedido explicito del usuario: "el reintento... debe tener un cooldown que
            // dependera de la cantidad de pisos") NUNCA se salta por liveViewActive - a diferencia
            // de runTick (que si podria chocar con la vista en vivo simulando el mismo RunState),
            // resolver un cooldown solo llama a DungeonState.assign, que no tiene nada que ver con
            // lo que la vista en vivo esta animando. Sin esto, dejar la pantalla de la mazmorra
            // abierta mientras el cooldown corre lo dejaria esperando para siempre.
            val status = DungeonState.status(this@DungeonService)
            // Guardian de la alarma del widget (pedido explicito del usuario: prevenir que las
            // barras/el huevo se queden parados): este servicio es mucho mas resistente a Doze/MIUI
            // que una alarma, asi que si el tick periodico del widget lleva de sobra sin correr
            // (alarma perdida o aplazada), lo lanza el servicio - ese tick repinta, avanza el huevo,
            // manda los avisos y REPROGRAMA la alarma. Solo actua mientras haya mazmorra en marcha
            // (el servicio no corre si no).
            if (BackgroundHealth.widgetTickOverdue(this@DungeonService, BackgroundHealth.WATCHDOG_MS)) {
                DebugLog.log(this@DungeonService, "mazmorra: el tick del widget lleva mas de 25 min sin correr - lo lanza el servicio")
                sendBroadcast(Intent(this@DungeonService, PokeWidgetProvider::class.java).setAction(PokeWidgetProvider.ACTION_TICK))
            }
            // Latido de diagnostico (pedido explicito del usuario: "logs... que nos ayude a
            // diagnosticar si no esta funcionando en segundo plano") - un hueco de varios minutos
            // entre dos lineas de latido consecutivas en el log es la prueba directa de que el
            // proceso murio entremedias; el [pid=] que DebugLog ya añade a cada linea confirma
            // ademas si al volver es un proceso nuevo. Se registra ANTES de actuar (si status es
            // EXPLORING con liveViewActive, no se llama a runTick, pero el latido en si ya deja
            // constancia de que el propio Handler/servicio sigue vivo).
            DebugLog.log(this@DungeonService, "mazmorra: tick piso=${DungeonState.currentFloor(this@DungeonService)} estado=$status")
            when (status) {
                DungeonState.STATUS_IDLE -> { stopSelf(); return }
                DungeonState.STATUS_COOLDOWN -> {
                    DungeonSimulator.resolveCooldownIfReady(this@DungeonService)
                    NotificationHelper.refreshDungeonNotification(this@DungeonService)
                }
                else -> if (!liveViewActive) {
                    DungeonSimulator.runTick(this@DungeonService)
                    NotificationHelper.refreshDungeonNotification(this@DungeonService)
                }
            }
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLog.log(this, "mazmorra: servicio arrancado")
        isRunning = true
        // Si el widget estaba con la interfaz oculta ("dormido") por este servicio parado, ya no
        // procede - se repinta YA en vez de esperar al siguiente tick del widget (cada 15 min).
        // Cubre cualquier forma de reanudarlo: toque al Pokemon, abrir la app o un arranque
        // pedido por el tick.
        if (BackgroundHealth.isInterfaceHidden(this)) WidgetRefresh.updateWidgets(this)
        NotificationHelper.ensureDungeonChannel(this)
        startForeground(NotificationHelper.NOTIF_ID_DUNGEON, NotificationHelper.buildDungeonNotification(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DungeonState.status(this) == DungeonState.STATUS_IDLE) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Pedido explicito del usuario: "si por lo que sea no corre en segundo plano... calcule
        // automaticamente las recompensas" - el servicio (re)arrancando aqui es la señal mas
        // fiable de "puede que llevara un rato sin correr" (lo mato el sistema, force-stop,
        // MIUI...), asi que es el sitio correcto para comprobarlo. En un hilo aparte - recuperar
        // muchos ciclos de golpe no debe bloquear onStartCommand (riesgo de ANR).
        Thread { DungeonSimulator.catchUpIfStalled(applicationContext, TICK_INTERVAL_MS) }.start()
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        DebugLog.log(this, "mazmorra: servicio detenido")
        handler.removeCallbacks(tickRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
