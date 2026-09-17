package com.example.pokegotchi

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.io.File
import kotlin.math.roundToInt

/**
 * Proveedor del widget PokeGotchi.
 *
 * Reacciones: cada accion muestra un efecto animado (ViewFlipper superpuesto). Los efectos
 * tienen autoStart=false y se reproducen UNA sola vez avanzando los frames a mano
 * (setDisplayedChild) desde el frame 0 hasta el ultimo, y luego se ocultan. Asi no dependen
 * del reloj ni hacen bucle: la baya se come y DESPUES salen los corazones, sin desordenarse.
 */
class PokeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_FEED = "com.example.pokegotchi.ACTION_FEED"
        const val ACTION_PET = "com.example.pokegotchi.ACTION_PET"
        const val ACTION_WASH = "com.example.pokegotchi.ACTION_WASH"
        const val ACTION_TICK = "com.example.pokegotchi.ACTION_TICK"
        const val ACTION_CRY = "com.example.pokegotchi.ACTION_CRY"
        const val ACTION_CRY_CHECK = "com.example.pokegotchi.ACTION_CRY_CHECK"
        // SOLO PARA PRUEBAS (disparado a mano por adb, nunca desde la propia app): deshace una
        // evolucion entera, ver PetState.debugRevertEvolution. Extras: "from" y "to" (nombres en
        // ingles minusculas). Ejemplo:
        // adb shell am broadcast -n com.example.pokegotchi/.PokeWidgetProvider \
        //   -a com.example.pokegotchi.ACTION_DEBUG_REVERT --es from pikachu --es to raichu
        const val ACTION_DEBUG_REVERT = "com.example.pokegotchi.ACTION_DEBUG_REVERT"
        // SOLO PARA PRUEBAS (quitar cuando ya no haga falta): vuelca egg_bitmap/needCloud reales
        // a PNG en filesDir, para poder sacarlos por adb y usarlos en el simulador visual.
        const val ACTION_DEBUG_DUMP_ASSETS = "com.example.pokegotchi.ACTION_DEBUG_DUMP_ASSETS"

        // Refresco periodico del decaimiento.
        private const val TICK_INTERVAL_MS = 15 * 60 * 1000L
        // Cada cuanto se comprueba el "palpito" del huevo (ver ACTION_CRY_CHECK) - el nombre se
        // quedo del grito autonomo que usaba este mismo ciclo, ya retirado (ver mas abajo).
        private const val CRY_CHECK_INTERVAL_MS = 90 * 1000L
        // Con la pantalla apagada se espacia mucho (ahorro de bateria).
        private const val CRY_CHECK_OFF_MS = 15 * 60 * 1000L

        // Causa raiz del bug "RemoteViews exceeds maximum bitmap memory usage" (confirmado con
        // crashes reales en dumpsys dropbox antes de este fix, y con el fallback disparandose
        // cada 8-12h despues): renderStatsOnly usa partiallyUpdateAppWidget en cada TICK (cada 15
        // min) y tras cada rechazo, y cada uno de esos parciales lleva su propio bitmap pequeño
        // (nube de necesidad, icono del huevo) que SE ACUMULA en la cuenta que lleva Android para
        // ese widget - solo un updateAppWidget COMPLETO (ver PetState.markFullRender, llamado
        // dentro de render()) la resetea. Si pasan muchas horas sin ninguna accion aceptada (que
        // ya fuerza un render completo), esa cuenta puede acercarse al limite real del
        // dispositivo. Forzar un render completo cada pocas horas, aunque no haga falta por
        // ninguna otra razon, mantiene esa cuenta siempre lejos del limite - a cambio de un
        // pequeño coste visible: la animacion idle se reinicia en ese instante (mismo efecto que
        // reaccionar), igual que ya pasa organicamente cada vez que se acepta una accion.
        private const val FULL_RENDER_RESET_INTERVAL_MS = 4 * 60 * 60 * 1000L

        // Cerrojo REAL en memoria de si hay una reaccion completa animandose ahora mismo.
        // El bloqueo por SharedPreferences (PetState.activeReaction, basado en un timestamp
        // "nominal" de duracion) podia liberarse un poco ANTES de que el hilo de la animacion
        // terminase de verdad (decodificar+componer+actualizar el widget frame a frame tiene
        // coste real, variable segun el dispositivo, y el margen nominal no siempre alcanzaba),
        // dejando una rendija en la que un segundo boton se aceptaba y su animacion se veia
        // "encolada" justo detras de la primera. Este flag se pone a true justo antes de lanzar
        // el hilo y a false en su finally (el momento REAL en que termina), sin depender de
        // ningun calculo de tiempo. Es @Volatile porque se lee desde el hilo principal
        // (onReceive) y se escribe desde el hilo de la animacion.
        @Volatile private var reactionRunning = false

        // Mismo tipo de cerrojo que reactionRunning, pero para la rafaga de "palpito" del huevo
        // (ver playEggPulse): evita lanzar una segunda rafaga solapada si ACTION_CRY_CHECK volviera
        // a disparar antes de que la anterior termine.
        @Volatile private var eggPulseRunning = false

        // Cooldown REAL (en memoria) tras TERMINAR una animacion aceptada: reactionRunning de
        // arriba solo evita que se SOLAPEN dos animaciones, pero un segundo toque del MISMO
        // boton, ya sin solape (justo despues de que la primera termine), puede volver a ser
        // ACEPTADO de verdad - el boost fijo de cada stat (BOOST_HAPPY, etc. en PetState) no
        // siempre supera el umbral aleatorio que se vuelve a sortear tras cada aceptacion, asi
        // que la necesidad puede reaparecer casi al instante. Confirmado con una prueba real en
        // el dispositivo (dos PET sin solape, ambos ACEPTADO en el log, uno justo detras del
        // otro) - es justo lo que el usuario describio como animaciones "encadenadas", aunque el
        // candado de solape funcione bien. Se marca DESDE playReactionOnce, al terminar de
        // verdad la animacion (no al aceptar el toque), para que ACTION_COOLDOWN_MS sea un
        // margen de silencio REAL despues del gesto, no una ventana que ya se solapa con su
        // propia duracion. Es POR TIPO de accion (tocar "lavar" no bloquea "dar de comer").
        private const val ACTION_COOLDOWN_MS = 6000L
        @Volatile private var lastAcceptedFeed = 0L
        @Volatile private var lastAcceptedPet = 0L
        @Volatile private var lastAcceptedWash = 0L
        private fun lastAcceptedAt(action: String): Long = when (action) {
            ACTION_FEED -> lastAcceptedFeed
            ACTION_PET -> lastAcceptedPet
            ACTION_WASH -> lastAcceptedWash
            else -> 0L
        }
        private fun markAccepted(action: String) {
            val now = System.currentTimeMillis()
            when (action) {
                ACTION_FEED -> lastAcceptedFeed = now
                ACTION_PET -> lastAcceptedPet = now
                ACTION_WASH -> lastAcceptedWash = now
            }
        }

        // Diagnostico de todas las mecanicas (pid incluido) - PERSISTENTE ademas de en logcat
        // (ver DebugLog: logcat se vacia solo en menos de una hora, no vale para "revisar los
        // logs del dia"). Filtrar con adb logcat -s PokeGotchiDbg, o sacar el archivo entero con
        // adb shell run-as com.example.pokegotchi cat files/pokegotchi_debug.log
        private fun dbg(context: Context, msg: String) = DebugLog.log(context, msg)

        private fun kindFor(action: String): String = when (action) {
            ACTION_FEED -> "feed"
            ACTION_PET -> "pet"
            ACTION_WASH -> "wash"
            else -> ""
        }
    }

    /**
     * Efecto por tipo: (nº de frames "de accion", ms/frame, frame del grito). cryFrame = frame en
     * el que suena el grito - para comer/ducha coincide con el frame en el que salen los corazones
     * (fin de comer / fin de ducha, ya en rafaga de varios grupos escalonados, ver
     * EffectGenerator.drawFeed/drawWash); para acariciar cae DESPUES de los [frames] de la mano
     * (ver comentario de mas abajo) y dispara su PROPIA rafaga final de corazones "de golpe" (ver
     * EffectGenerator.petBurstEndFrame) - antes esos corazones ya se habian apagado del todo antes
     * de que sonara el grito de verdad (bug real reportado por el usuario: "deberían aparecer los
     * corazones justo cuando el Pokémon grita, como al terminar de comer").
     */
    private data class Fx(val frames: Int, val interval: Long, val cryFrame: Int)
    // 48 frames x ~95 ms = ~4.6 s (feed/wash). "pet" se recorta a 40 fotogramas PARA LA MANO: el
    // ultimo vaiven completo (centro-lado-centro) acaba en el fotograma 36 (ver
    // EffectGenerator.drawPet, PET_REF_FRAMES), y los que seguian (37..47) solo mostraban ese
    // vaiven a MEDIAS, sin llegar a completarlo - se veia raro, pedido explicito del usuario
    // ("recortar la animacion de la mano al final"). El recorte usa una referencia de velocidad
    // FIJA (PET_REF_FRAMES=48) asi que el vaiven en si no se acelera, solo se deja de mostrar el
    // final a medias. cryFrame=47 mantiene el mismo hueco de ~665ms tras acabar el vaiven que ya
    // se habia afinado (antes 55 sobre 48 fotogramas; ahora 47 sobre 40) - la mano se queda
    // CONGELADA en su ultima postura mas alla del frame 39 (ver EffectGenerator.
    // PET_HAND_FREEZE_FRAME), y justo en cryFrame arranca la rafaga final de corazones (ver
    // playReactionOnce: el bucle visual se extiende hasta EffectGenerator.petBurstEndFrame para
    // que se vea entera, no solo hasta frames).
    /** Nombre a usar para TODO lo relacionado con el sprite (cache/meta/frames): el de la
     *  Megaevolucion si hay una activa ahora mismo, si no el de la especie real (o su forma
     *  actual, ver PetState.displaySpriteName - Tatsugiri/Zygarde). Solo para visual - nivel/xp/
     *  stats siguen SIEMPRE con PetState.currentPokemon (puramente decorativo, no toca nada mas). */
    private fun activeSpriteName(context: Context): String =
        PetState.displaySpriteName(context, PetState.currentPokemon(context))

    private fun fxFor(kind: String): Fx = when (kind) {
        "feed" -> Fx(48, 95L, 21)
        "pet" -> Fx(40, 95L, 47)
        "wash" -> Fx(48, 95L, 28)
        else -> Fx(48, 95L, 21)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // checkAlerts PRIMERO: puede cambiar el estado del huevo (poner uno/avanzar de fase/
        // quedar listo) - si se pintara antes, ese cambio no se veria en el widget hasta el
        // siguiente redibujado (aunque la notificacion si llegara a tiempo - bug real reportado
        // por el usuario: "me avisa de que ha eclosionado pero el huevo se ve como antes").
        checkAlerts(context)
        for (id in appWidgetIds) render(context, appWidgetManager, id)
        scheduleTick(context)
        scheduleCryCheck(context)
        SoundManager.preload(context)
        ensureCurrentSprite(context)
        // Misma comprobacion que en ACTION_CRY_CHECK: otra oportunidad de disparar el palpito del
        // huevo (ej. al reinstalar/reabrir la app), no solo depender del ciclo de ~90s.
        if (PetState.activeIsEggParent(context) && !PetState.isEggReady(context)) {
            val stage = PetState.eggCrackStage(context)
            if (PetState.eggPulseDue(context, stage)) {
                PetState.markEggPulseStarted(context)
                playEggPulse(context, stage)
            }
        }
    }

    /** Redibuja al instante al cambiar el tamaño del widget (arrastrando las asas) - si no,
     *  huevo/nube se quedan con la posicion/tamaño calculados para el tamaño ANTERIOR hasta el
     *  siguiente refresco normal. */
    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        render(context, appWidgetManager, appWidgetId)
    }

    /** Si el Pokemon actual no esta cacheado, lo descarga en segundo plano y refresca. */
    private fun ensureCurrentSprite(context: Context) {
        if (!PetState.hasChosenStarter(context)) return   // aun no hay Pokemon activo que cachear
        val name = activeSpriteName(context)
        if (SpriteRepository.isCurrent(SpriteRepository.readMeta(context, PetState.currentStyleKey(context), name))) return
        val pending = goAsync()
        Thread {
            try {
                SpriteRepository.ensure(
                    context, name, PetState.currentPokemonId(context),
                    PetState.currentStyle(context), PetState.isShiny(context), PetState.spriteScaleMode(context)
                )
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, PokeWidgetProvider::class.java))
                ids.forEach { render(context, mgr, it) }   // rebuild el flipper con los frames
            } catch (_: Throwable) {
            } finally { pending.finish() }
        }.start()
    }

    override fun onEnabled(context: Context) {
        scheduleTick(context)
        scheduleCryCheck(context)
    }

    override fun onDisabled(context: Context) {
        cancelTick(context)
        cancelCryCheck(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val mgr = AppWidgetManager.getInstance(context)
        when (intent.action) {
            ACTION_FEED, ACTION_PET, ACTION_WASH -> {
                val action = intent.action!!
                // Cualquier pulsacion (aceptada, rechazada, o ignorada por estar ya ocupado) es
                // evidencia REAL de que el usuario esta despierto - ver PetState.recordInteraction
                // (aprendizaje de la ventana de noche adaptativa).
                PetState.recordInteraction(context)
                // Ignora COMPLETAMENTE si ya hay una reaccion o una nube de rechazo en pantalla
                // (no solo la reaccion "grande"): mientras se este mostrando cualquier cosa de
                // una pulsacion anterior, un segundo boton no debe tener NINGUN efecto - ni
                // sonido ni cambio de nube - para que no parezca que las pulsaciones se "encolan"
                // y se ejecutan una detras de otra. reactionRunning es el cerrojo REAL (en
                // memoria, ligado al hilo de verdad); activeReaction/activeRejectFlash son el
                // respaldo por si el proceso muriese y se reiniciase a mitad de una reaccion.
                val ar = PetState.activeReaction(context); val arf = PetState.activeRejectFlash(context)
                // eggPulseRunning tambien cuenta: la rafaga del huevo usa el mismo fx_overlay que
                // una reaccion normal (sprite compuesto a mano con el flipper oculto), asi que no
                // pueden solaparse.
                val busy = reactionRunning || eggPulseRunning || ar != null || arf != null
                // Ademas del solape, EL MISMO boton en cooldown (ver ACTION_COOLDOWN_MS arriba):
                // sin esto, un segundo toque ya fuera de la ventana de solape podia ser aceptado
                // de verdad y encadenar una segunda animacion completa detras de la primera.
                val onCooldown = System.currentTimeMillis() - lastAcceptedAt(action) < ACTION_COOLDOWN_MS
                dbg(context, "recibido $action | reactionRunning=$reactionRunning eggPulseRunning=$eggPulseRunning activeReaction=$ar activeRejectFlash=$arf onCooldown=$onCooldown -> busy=$busy")
                if (!busy && !onCooldown) {
                    val result = PetState.tryApplyAction(context, action)
                    if (result.applied) {
                        // El Pokemon SI lo necesitaba: reaccion completa. Solo AQUI (necesidad de
                        // verdad atendida) se quita el aviso de "necesita atencion" al instante,
                        // sin esperar al siguiente ciclo de checkAlerts (~15min) - discriminado a
                        // proposito del resto de avisos (huevo/oferta/evolucion), que no tienen
                        // nada que ver con dar de comer/acariciar/lavar y NO deben desaparecer solo
                        // por eso - pedido explicito del usuario (ej. un huevo eclosionado no debe
                        // quitarse porque acaricies a tu Pokemon activo).
                        NotificationHelper.cancelUrgentCare(context)
                        dbg(context, "$action ACEPTADO -> playReactionOnce" + if (result.leveledUp) " (subio de nivel)" else "")
                        if (result.leveledUp) SoundManager.playLevelUp(context)
                        // markAccepted se llama DENTRO de playReactionOnce, al terminar de verdad
                        // la animacion (no aqui, al empezarla) - asi ACTION_COOLDOWN_MS da un
                        // margen real DESPUES del gesto, en vez de solaparse con su propia duracion.
                        playReactionOnce(context, action, kindFor(action))
                    } else {
                        dbg(context, "$action RECHAZADO (no hacia falta)")
                        // No hace falta ahora mismo: se rechaza (sin cambiar stats) con un
                        // sonido distinto Y una nube "no hace falta" sobre su cabeza, para dejar
                        // claro que NO es un fallo de la app: es el Pokemon diciendo que no lo
                        // necesita ahora. IMPORTANTE: NO usar render() completo aqui (rehace el
                        // ViewFlipper con removeAllViews+addView y reinicia la animacion del
                        // sprite). Solo se refrescan barras/botones/nubes con un update PARCIAL.
                        SoundManager.playReject(context)
                        val until = PetState.setRejectFlash(context, action)
                        renderStatsOnly(context, mgr)
                        val pending = goAsync()
                        Handler(Looper.getMainLooper()).postDelayed({
                            PetState.clearRejectFlashIfStill(context, until)
                            renderStatsOnly(context, mgr)
                            pending.finish()
                        }, PetState.REJECT_FLASH_MS + 100)
                    }
                }
            }
            ACTION_TICK -> {
                // checkAlerts PRIMERO: puede cambiar el estado del huevo (poner uno/avanzar de
                // fase/quedar listo) - si se pintara antes, ese cambio no se veria hasta el
                // siguiente redibujado (aunque la notificacion si llegara a tiempo - bug real
                // reportado por el usuario).
                checkAlerts(context)
                // Cada FULL_RENDER_RESET_INTERVAL_MS, un render COMPLETO en vez de parcial - ver
                // la constante de arriba (resetea la cuenta de memoria de bitmaps antes de que se
                // acerque al limite real). El resto del tiempo, parcial de siempre (no reinicia
                // la animacion del sprite).
                if (PetState.msSinceLastFullRender(context) >= FULL_RENDER_RESET_INTERVAL_MS) {
                    renderAll(context, mgr)
                } else {
                    renderStatsOnly(context, mgr)
                }
                scheduleTick(context)
            }
            ACTION_DEBUG_REVERT -> {
                val from = intent.getStringExtra("from")
                val to = intent.getStringExtra("to")
                if (from != null && to != null) {
                    PetState.debugRevertEvolution(context, from, to)
                    renderAll(context, mgr)
                }
            }
            ACTION_DEBUG_DUMP_ASSETS -> {
                // SOLO PARA PRUEBAS: vuelca a ficheros PNG los bitmaps REALES del huevo (fase 0)
                // y de la nube (con el angulo de cola que se pida por extra, para sacar varias
                // variantes reales), para poder sacarlos por adb y meterlos tal cual en el
                // simulador visual - de quitar en cuanto deje de hacer falta. Extra opcional
                // "tailVertical" (float, 1f si no se pasa) y "outName" (nombre del fichero de la
                // nube, "debug_cloud.png" si no se pasa) para poder pedir varias de una tacada.
                val tailVertical = intent.getFloatExtra("tailVertical", 1f)
                val outName = intent.getStringExtra("outName") ?: "debug_cloud.png"
                try {
                    File(context.filesDir, "debug_egg.png").outputStream().use {
                        EffectGenerator.eggBitmap(context, 0, hatched = false).compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    File(context.filesDir, "debug_egg_hatched.png").outputStream().use {
                        EffectGenerator.eggBitmap(context, 0, hatched = true).compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    File(context.filesDir, outName).outputStream().use {
                        EffectGenerator.needCloud("🍎", 1f, tailVertical).compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } catch (e: Exception) {
                    Log.w("PokeGotchiDbg", "debug dump fallo: $e")
                }
            }
            ACTION_CRY -> {
                // Tocar al Pokemon: grito con variacion de tono. goAsync mantiene vivo
                // el proceso mientras suena. Solo grito, no cuida nada de verdad - no quita
                // ningun aviso (ver discusion en ACTION_FEED/PET/WASH).
                PetState.recordInteraction(context)
                SoundManager.playCry(context)
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 1200)
            }
            ACTION_CRY_CHECK -> {
                // El grito AUTONOMO (sin tocar nada) se quito a peticion del usuario: sonaba
                // aunque estuviera usando OTRA app (WhatsApp, etc.), porque un widget no tiene
                // forma de saber si esta viendolo en ese momento - Android no le avisa de su
                // propia visibilidad. Sin ese grito de "reclamo", el aviso de que el Pokemon
                // necesita algo ya lo cubre el sistema de notificaciones (ver checkAlerts). El
                // grito SIGUE sonando al tocar al Pokemon a proposito (ACTION_CRY, mas arriba).
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val interactive = pm.isInteractive
                // La pantalla encendida (aunque sea en OTRA app) tambien es evidencia real de que
                // el usuario esta despierto - sin esto, la ventana de noche adaptativa solo
                // aprenderia de quien toca ESTE widget a menudo, y a nadie mas (ver
                // PetState.recordInteraction). No hace falta un servicio nuevo: ya se comprueba
                // en este mismo ciclo periodico de siempre.
                if (interactive) PetState.recordInteraction(context)
                // Aprovechamos este mismo ciclo (cada ~90s con la pantalla encendida) para
                // comprobar si toca un "palpito" del huevo (rafaga real de unos segundos, no una
                // simple muestra estatica - ver playEggPulse). La frecuencia del palpito depende
                // de la fase (mas seguido cuanto mas cerca de eclosionar).
                if (interactive && PetState.activeIsEggParent(context) && !PetState.isEggReady(context)) {
                    val stage = PetState.eggCrackStage(context)
                    if (PetState.eggPulseDue(context, stage)) {
                        PetState.markEggPulseStarted(context)
                        playEggPulse(context, stage)
                    }
                }
                // Pantalla apagada -> comprobar mucho menos a menudo (ahorro de bateria).
                scheduleCryCheck(context, if (interactive) CRY_CHECK_INTERVAL_MS else CRY_CHECK_OFF_MS)
            }
        }
    }

    /** Comprueba las 2 condiciones ya calculadas en cada refresco (evolucion lista, cuidado
     *  urgente) y dispara una notificacion la PRIMERA vez que cada una se cumple; no vuelve a
     *  avisar hasta que la condicion se resuelva y vuelva a aparecer (ver PetState). */
    private fun checkAlerts(context: Context) {
        if (!PetState.hasChosenStarter(context)) return
        val s = PetState.loadWithDecay(context)

        val canEvolve = PetState.pendingEvolution(context, s) != null
        if (canEvolve && !PetState.wasNotifiedEvolutionReady(context)) {
            dbg(context, "evolucion: aviso disparado para ${PetState.currentPokemon(context)}")
            NotificationHelper.notifyEvolutionReady(context, PetState.currentPokemon(context))
            PetState.setNotifiedEvolutionReady(context, true)
        } else if (!canEvolve && PetState.wasNotifiedEvolutionReady(context)) {
            dbg(context, "evolucion: aviso cancelado (ya no puede evolucionar o evoluciono)")
            PetState.setNotifiedEvolutionReady(context, false)
            NotificationHelper.cancelEvolutionReady(context)
        }

        val needy = PetState.hasAnyNeed(context, s)
        // Ventana de "noche" ADAPTATIVA (antes fija en 22-8, ver PetState.isNightNow): no se
        // dispara ningun aviso (ni nuevo ni repetido) mientras dure. Si la necesidad ya estaba
        // antes de dormirse, saltara en el primer chequeo tras despertar. Mientras siga sin
        // resolverse, se recuerda cada URGENT_REPEAT_HOURS (no solo la primera vez) - para no
        // dejar de insistir si se ignora.
        if (needy && !PetState.isNightNow(context) && (!PetState.wasNotifiedUrgent(context) || PetState.urgentRepeatDue(context))) {
            dbg(context, "atencion urgente: aviso disparado para ${PetState.currentPokemon(context)} (vida=${s.health.toInt()} higiene=${s.hygiene.toInt()} felicidad=${s.happiness.toInt()})")
            NotificationHelper.notifyUrgentCare(context, PetState.currentPokemon(context))
            PetState.setNotifiedUrgent(context, true)
            PetState.markUrgentNotified(context)
        } else if (!needy && PetState.wasNotifiedUrgent(context)) {
            dbg(context, "atencion urgente: aviso cancelado (ya no hace falta nada)")
            PetState.setNotifiedUrgent(context, false)
            NotificationHelper.cancelUrgentCare(context)
        }

        if (PetState.maybeGenerateOffer(context)) {
            dbg(context, "regalo: oferta nueva generada")
            NotificationHelper.notifyOfferReady(context)
        }

        // Huevo: como mucho eggRollsDue() tiradas reales por EGG_ROLL_INTERVAL_MS transcurrido de
        // verdad, pase lo que pase mientras tanto con refrescos del widget que no sean el tick
        // real (cambiar de Pokemon activo, evolucionar...) - si no, cada refresco colaria una
        // tirada de mas. Dentro de cada tirada: primero se comprueba si el que YA esta pendiente
        // eclosiona; solo si no hay ninguno se puede poner uno nuevo (nunca los dos a la vez).
        val eggRolls = PetState.eggRollsDue(context)
        if (eggRolls == 0) return
        // SOLO PARA DIAGNOSTICO (pedido explicito del usuario): registra cada tirada real del
        // huevo, con la fase antes/despues - la hora de logcat en cada linea deja ver el hueco
        // real entre tiradas (deberia ser ~15min, ver EGG_ROLL_INTERVAL_MS) y asi distinguir mala
        // suerte (huecos de 15min de verdad, solo que la moneda fallo muchas veces) de que el
        // propio tick se este retrasando de mas (ahorro de bateria/Doze - ver scheduleTick).
        // Filtrar con: adb logcat -s PokeGotchiDbg | findstr huevo
        //
        // Si toca MAS de una tirada de golpe (el chequeo periodico se retraso de verdad - caso
        // real detectado por el usuario, hueco de 2h20m visto en este mismo log), se repite el
        // cuerpo de "una tirada" tantas veces como toque, en vez de colapsarlas en una sola: asi
        // el huevo avanza (o incluso eclosiona y llega a poner uno NUEVO que a su vez progresa
        // dentro del mismo hueco recuperado) igual que si el chequeo hubiera llegado puntual cada
        // vez, no solo la ultima. `sNow` se recarga en cada vuelta (no se reusa el `s` de arriba)
        // porque el bono de xp al eclosionar puede subir de nivel al padre a mitad del hueco, y
        // eso SI afecta a la probabilidad de la siguiente puesta si el padre es el activo.
        if (eggRolls > 1) dbg(context, "huevo: recuperando $eggRolls tiradas atrasadas (chequeo retrasado)")
        repeat(eggRolls) {
            val sNow = PetState.loadWithDecay(context)
            val hadEggBefore = PetState.hasActiveEgg(context)
            val stageBefore = PetState.eggCrackStage(context)
            val justHatched = PetState.maybeHatchEgg(context)
            if (hadEggBefore) {
                val stageAfter = PetState.eggCrackStage(context)
                val resultado = when {
                    justHatched -> "LISTO (especie ${PetState.eggHatchSpecies(context)}, shiny=${PetState.eggIsShiny(context)}), padre ${PetState.eggParent(context)} +xp bono huevo"
                    stageAfter > stageBefore -> "avanza a fase $stageAfter"
                    else -> "sin cambio (sigue en fase $stageBefore)"
                }
                dbg(context, "huevo: tirada real -> $resultado")
            }
            if (justHatched) {
                NotificationHelper.cancelEggLaid(context)
                NotificationHelper.notifyEggHatched(context)
            } else if (PetState.maybeLayEgg(context, sNow)) {
                dbg(context, "huevo: puesto nuevo (padre ${PetState.currentPokemon(context)}, shiny=${PetState.eggIsShiny(context)})")
                NotificationHelper.notifyEggLaid(context, PetState.currentPokemon(context))
            }
        }
    }

    private fun renderAll(context: Context, mgr: AppWidgetManager) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, PokeWidgetProvider::class.java))
        ids.forEach { render(context, mgr, it) }
    }

    /**
     * Actualizacion PARCIAL: solo barras, nivel/xp y el texto de los botones (con su aviso
     * ❗). NO toca el ViewFlipper del sprite (no hace removeAllViews/addView), asi que la
     * animacion del idle sigue corriendo sin reiniciarse. Usar para refrescos que no cambian
     * el Pokemon ni su sprite (rechazo de accion, tick periodico).
     */
    private fun renderStatsOnly(context: Context, mgr: AppWidgetManager) {
        if (!PetState.hasChosenStarter(context)) return   // aun en la pantalla de bienvenida
        val s = PetState.loadWithDecay(context)
        val views = RemoteViews(context.packageName, R.layout.widget_pokegotchi)
        setStatBars(views, s.health.toInt(), s.hygiene.toInt(), s.happiness.toInt())
        applyLevelAndEvolveCta(context, views, s)
        views.setProgressBar(R.id.xp_bar, 100, (PetState.progressOf(context, s.xp, PetState.currentPokemon(context)) * 100).toInt(), false)
        applyNeedIndicators(context, views, s)
        applyEggIndicator(context, views)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PokeWidgetProvider::class.java))
        ids.firstOrNull()?.let { applyAdaptiveEggAndCloudLayout(context, views, mgr, it, s) }
        // Bug real (confirmado en el dispositivo con toques normales, no solo en pruebas):
        // partiallyUpdateAppWidget FUSIONA esta actualizacion con el arbol YA comprometido en el
        // lanzador - varias actualizaciones parciales seguidas (rechazar accion tras accion) sin
        // ninguna reconstruccion COMPLETA de por medio pueden hacer que el propio servicio de
        // Android (no este codigo) acumule una estimacion de memoria de bitmaps que termine
        // superando el limite real del dispositivo, y crashea con "RemoteViews for widget update
        // exceeds maximum bitmap memory usage" - visto en logcat viniendo del lado del sistema
        // (AppWidgetServiceImpl), no de aqui. updateAppWidget (usado por render(), la
        // reconstruccion COMPLETA) SUSTITUYE el arbol entero en vez de fusionarlo, lo que reinicia
        // esa cuenta - se usa aqui como red de seguridad: si la parcial falla, se reintenta con una
        // completa en vez de dejar que la excepcion mate el proceso. Como mucho se pierde la
        // animacion idle en curso justo esa vez (se reconstruye igualmente en el render completo),
        // nunca se crashea ni se queda el widget colgado.
        ids.forEach {
            try {
                mgr.partiallyUpdateAppWidget(it, views)
            } catch (e: Exception) {
                dbg(context, "renderStatsOnly: partiallyUpdateAppWidget fallo ($e) - reintentando con render completo")
                try { render(context, mgr, it) } catch (e2: Exception) { dbg(context, "renderStatsOnly: render completo tambien fallo: $e2") }
            }
        }
    }

    /** Rellena las 2 variantes de barras de estadisticas (apilada Y en linea, ver
     *  widget_pokegotchi.xml) con los mismos valores - solo una de las dos esta VISIBLE a la vez
     *  (ver applyChromeSize), pero rellenar ambas siempre es mas simple que acordarse de cual
     *  toca en cada sitio que ya actualizaba las barras. */
    private fun setStatBars(views: RemoteViews, health: Int, hygiene: Int, happiness: Int) {
        views.setProgressBar(R.id.stat_health, 100, health, false)
        views.setProgressBar(R.id.stat_hygiene, 100, hygiene, false)
        views.setProgressBar(R.id.stat_happiness, 100, happiness, false)
        views.setProgressBar(R.id.stat_health_row, 100, health, false)
        views.setProgressBar(R.id.stat_hygiene_row, 100, hygiene, false)
        views.setProgressBar(R.id.stat_happiness_row, 100, happiness, false)
    }

    /** Texto de nivel (+ aviso de evolucion si toca) y su click para abrir la app, mas el boton
     *  dedicado en forma de Poke Ball (mismo destino, ambos por separado - pedido explicito del
     *  usuario para tener un boton claro de abrir la app, no solo el texto de nivel). Compartido
     *  entre render() y renderStatsOnly(). */
    private fun applyLevelAndEvolveCta(context: Context, views: RemoteViews, s: PetState.Stats) {
        val canEvolve = PetState.pendingEvolution(context, s) != null
        views.setTextViewText(
            R.id.widget_level,
            "Nv. ${PetState.levelOf(context, s.xp, PetState.currentPokemon(context))}" + if (canEvolve) " · ✨¡Evoluciona!" else ""
        )
        val openApp = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_level, openApp)
        views.setOnClickPendingIntent(R.id.btn_open_app, openApp)
    }

    /**
     * Reproduce el efecto una sola vez. Los frames se GENERAN al vuelo (EffectGenerator) al
     * tamaño/centro del Pokemon actual y se pintan en fx_overlay (setImageViewBitmap) desde un
     * hilo de fondo. Los sonidos van por Handler (timing preciso). goAsync mantiene el proceso.
     */
    private fun playReactionOnce(context: Context, action: String, kind: String) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PokeWidgetProvider::class.java))
        val fx = fxFor(kind)
        val pkg = context.packageName
        val poke = activeSpriteName(context)
        val styleKey = PetState.currentStyleKey(context)
        val meta = SpriteRepository.readMeta(context, styleKey, poke)
        val w = meta?.w?.takeIf { it > 0 } ?: 50
        val h = meta?.h?.takeIf { it > 0 } ?: 46
        val cx = meta?.cx ?: (w / 2)
        val cy = meta?.cy?.takeIf { it > 0 } ?: (h / 2)
        val spriteFC = (meta?.frameCount ?: 1).coerceAtLeast(1)

        // reactionRunning es el cerrojo REAL (no depende de calcular cuanto va a durar el hilo).
        // setReaction (SharedPreferences) es solo un respaldo con margen GENEROSO por si el
        // proceso muriese a mitad de la animacion (entonces reactionRunning se perderia). Todo
        // el montaje (sonidos + lanzar el hilo) va en un try/catch: si algo falla ANTES de que
        // el hilo llegue a arrancar, se libera el cerrojo aqui mismo para no quedar bloqueado
        // para siempre (el hilo, si SI llega a arrancar, ya libera el suyo en su propio finally).
        reactionRunning = true
        dbg(context, "playReactionOnce($kind) empieza, reactionRunning=true")
        try {
            PetState.setReaction(context, kind, fx.frames * fx.interval + 4000L)

            // --- Sonidos (Handler) ---
            val handler = Handler(Looper.getMainLooper())
            when (kind) {
                "feed" -> SoundManager.playAction(context, "feed")
                // El swish de la caricia SI se mantiene durante la animacion (sonido de la
                // caricia en si, no del Pokemon) - lo que se movio al final fue solo el GRITO del
                // Pokemon (ver fxFor), que antes sonaba a la vez que el primer swish y quedaba
                // raro ("grita al principio, luego no, y grita" - reportado por el usuario).
                // La mano cruza el centro del Pokemon (visualmente) justo en estos 4 frames (ver
                // EffectGenerator.drawPet: xc=cx cuando sin(ang)=0, en f/nf=0,0.25,0.5,0.75). Antes
                // se restaban 200ms para compensar el silencio inicial del audio VIEJO (ya
                // recortado, ver sfx_pet.mp3) - sin eso, disparar justo en el frame ya cae bien.
                "pet" -> for (pf in intArrayOf(0, 12, 24, 36)) {
                    handler.postDelayed({ SoundManager.playPet(context, loud = true) }, pf * fx.interval)
                }
                "wash" -> {
                    val mp = MediaPlayer.create(context, R.raw.sfx_wash)
                    if (mp != null) {
                        try { mp.seekTo(500) } catch (_: Exception) {}
                        mp.setOnCompletionListener { it.release() }
                        mp.start()
                        handler.postDelayed({
                            try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
                            try { mp.release() } catch (_: Exception) {}
                        }, fx.cryFrame * fx.interval)
                    }
                }
            }
            if (fx.cryFrame <= 0) SoundManager.playCry(context)
            else handler.postDelayed({ SoundManager.playCry(context) }, fx.cryFrame * fx.interval)
        } catch (_: Throwable) {
            reactionRunning = false
            PetState.clearReaction(context)
            return
        }

        // --- Visuales (hilo de fondo: genera cada frame y lo pinta) ---
        val pending = goAsync()
        // "pet": el bucle tiene que seguir mas alla de [fx.frames] para que la rafaga final de
        // corazones (arrancada en el grito, ver EffectGenerator.petBurstEndFrame) se vea entera,
        // no cortada a medias. El resto de kinds no cambia (cryFrame ya cae dentro de frames).
        val renderFrames = if (kind == "pet") maxOf(fx.frames, EffectGenerator.petBurstEndFrame(fx.cryFrame) + 1) else fx.frames
        Thread {
            try {
                // Los tiempos de sonido (Handler.postDelayed, arriba) se cuentan desde ESTE mismo
                // instante - el bucle visual tiene que seguir el mismo reloj real, no solo dormir
                // [fx.interval] fijo por vuelta. Decodificar el PNG de cada frame + componer el
                // Canvas + el update de RemoteViews (IPC al launcher) tardan un tiempo real que
                // antes se sumaba ENCIMA del sleep, así que el video se iba retrasando cada vez
                // más frente al sonido (bug real reportado: "la mano no coincide con el sonido").
                // Ahora cada vuelta duerme solo lo que falte para llegar al instante exacto que le
                // toca, calculado desde el arranque - así no se acumula retraso.
                val t0 = System.currentTimeMillis()
                for (i in 0 until renderFrames) {
                    // Compone: frame del idle (ciclando) + efecto, en un solo bitmap.
                    val effect = EffectGenerator.frameFor(kind, i, renderFrames, w, h, cx, cy, fx.cryFrame)
                    val comp = Bitmap.createBitmap(effect.width, effect.height, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(comp)
                    val spf = BitmapFactory.decodeFile(
                        SpriteRepository.frameFile(context, styleKey, poke, i % spriteFC).absolutePath
                    )
                    if (spf != null) {
                        cv.drawBitmap(spf, null, Rect(0, 0, comp.width, comp.height), null)
                        spf.recycle()
                    }
                    cv.drawBitmap(effect, 0f, 0f, null)
                    effect.recycle()

                    // update COMPLETO (no parcial) con el composite en el overlay, para no
                    // chocar con el addView del flipper.
                    ids.forEach { render(context, mgr, it, comp) }
                    val targetElapsed = (i + 1) * fx.interval
                    val actualElapsed = System.currentTimeMillis() - t0
                    val sleepMs = targetElapsed - actualElapsed
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                }
            } catch (t: Throwable) {
                dbg(context, "playReactionOnce($kind) EXCEPCION en el bucle: $t")
            } finally {
                // SIEMPRE se liberan el cerrojo y se vuelve al idle, pase lo que pase dentro del
                // bucle (incluso si algo lanzo una excepcion a medias) - asi nunca se queda
                // bloqueado para futuras acciones.
                reactionRunning = false
                PetState.clearReaction(context)
                markAccepted(action)
                dbg(context, "playReactionOnce($kind) termina, reactionRunning=false")
                try { ids.forEach { render(context, mgr, it) } } catch (_: Throwable) {}
                pending.finish()
            }
        }.start()
    }

    /** Antes de elegir inicial (primer arranque) el widget no tiene Pokemon activo: en vez de
     *  mostrar un Pikachu por defecto sin que el jugador lo haya elegido, muestra un mensaje y
     *  al tocar abre la app (que redirige a StarterActivity). */
    private fun renderWelcome(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_pokegotchi)
        val bgRes = context.resources.getIdentifier(PetState.currentBg(context), "drawable", context.packageName)
        if (bgRes != 0) views.setImageViewResource(R.id.widget_bg, bgRes)
        views.removeAllViews(R.id.widget_pokemon)
        views.setViewVisibility(R.id.widget_pokemon, View.GONE)
        views.setViewVisibility(R.id.fx_overlay, View.GONE)
        views.setViewVisibility(R.id.need_cloud, View.GONE)
        views.setTextViewText(R.id.widget_level, "Toca para elegir tu Pokémon inicial")
        views.setProgressBar(R.id.xp_bar, 100, 0, false)
        setStatBars(views, 0, 0, 0)
        views.setTextViewText(R.id.btn_feed, "🍎")
        views.setTextViewText(R.id.btn_pet, "✋")
        views.setTextViewText(R.id.btn_wash, "🚿")
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.pokemon_area, openApp)
        views.setOnClickPendingIntent(R.id.btn_open_app, openApp)
        mgr.updateAppWidget(appWidgetId, views)
    }

    /** overlayBmp != null durante una reaccion (o una rafaga de palpito del huevo): pinta ese
     *  composite (Pokemon+efecto) en el overlay y oculta el flipper. null = idle normal (flipper
     *  con los frames). [eggShakeAngleDeg] rota el huevo del `egg_overlay` normal (0 = reposo) -
     *  se sigue dibujando por SU CUENTA, con su posicionamiento de siempre (no embebido dentro de
     *  overlayBmp): al estar el flipper YA oculto en ambos casos, tocar este hermano suyo no
     *  reintroduce el bug de reinicio del sprite, y asi el huevo no salta de sitio al empezar o
     *  acabar la rafaga (embeberlo dentro de overlayBmp, que tiene la proporcion del SPRITE y no
     *  la del hueco real del widget, podia dejar un margen de letterbox que lo desplazaba). */
    private fun render(
        context: Context, mgr: AppWidgetManager, appWidgetId: Int, overlayBmp: Bitmap? = null,
        eggShakeAngleDeg: Float = 0f
    ) {
        if (!PetState.hasChosenStarter(context)) { renderWelcome(context, mgr, appWidgetId); return }
        val s = PetState.loadWithDecay(context)
        val views = RemoteViews(context.packageName, R.layout.widget_pokegotchi)

        // Fondo elegido
        val bgRes = context.resources.getIdentifier(PetState.currentBg(context), "drawable", context.packageName)
        if (bgRes != 0) views.setImageViewResource(R.id.widget_bg, bgRes)

        if (overlayBmp == null) {
            // Idle: rellena el ViewFlipper con los frames cacheados (animacion suave sola).
            views.removeAllViews(R.id.widget_pokemon)
            val poke = activeSpriteName(context)
            val styleKey = PetState.currentStyleKey(context)
            val spriteMeta = SpriteRepository.readMeta(context, styleKey, poke)
            // Si la cache en disco es de una version antigua (o esta corrupta/incompleta), NO se
            // cargan esos frames a ciegas: un cache viejo de un sprite con lienzo nativo grande
            // (caso real: Raichu de Alola) podia superar el limite de memoria de RemoteViews y
            // dejar la app en bucle de crash en cada onUpdate, ANTES de que ensureCurrentSprite
            // (que corre despues, de forma asincrona) tuviera ocasion de regenerarlo. Se deja el
            // flipper vacio hasta que ese proceso en segundo plano termine y vuelva a llamar a
            // render() con los frames ya regenerados dentro del presupuesto de memoria.
            val fc = if (SpriteRepository.isCurrent(spriteMeta)) spriteMeta?.frameCount ?: 0 else 0
            for (i in 0 until fc) {
                val bmp = BitmapFactory.decodeFile(
                    SpriteRepository.frameFile(context, styleKey, poke, i).absolutePath
                ) ?: continue
                val frame = RemoteViews(context.packageName, R.layout.item_sprite_frame)
                frame.setImageViewBitmap(R.id.frame_img, bmp)
                views.addView(R.id.widget_pokemon, frame)
            }
            views.setViewVisibility(R.id.widget_pokemon, View.VISIBLE)
            views.setViewVisibility(R.id.fx_overlay, View.GONE)
        } else {
            // Reaccion: flipper oculto y overlay con el Pokemon+efecto ya compuesto.
            views.setViewVisibility(R.id.widget_pokemon, View.GONE)
            views.setViewVisibility(R.id.fx_overlay, View.VISIBLE)
            views.setImageViewBitmap(R.id.fx_overlay, overlayBmp)
        }

        setStatBars(views, s.health.toInt(), s.hygiene.toInt(), s.happiness.toInt())
        applyLevelAndEvolveCta(context, views, s)
        views.setProgressBar(R.id.xp_bar, 100, (PetState.progressOf(context, s.xp, PetState.currentPokemon(context)) * 100).toInt(), false)
        applyNeedIndicators(context, views, s)
        applyEggIndicator(context, views, eggShakeAngleDeg)
        applyAdaptiveEggAndCloudLayout(context, views, mgr, appWidgetId, s)

        views.setOnClickPendingIntent(R.id.btn_feed, actionPI(context, ACTION_FEED, appWidgetId))
        views.setOnClickPendingIntent(R.id.btn_pet, actionPI(context, ACTION_PET, appWidgetId))
        views.setOnClickPendingIntent(R.id.btn_wash, actionPI(context, ACTION_WASH, appWidgetId))
        // Tocar al Pokemon -> grito
        views.setOnClickPendingIntent(R.id.pokemon_area, actionPI(context, ACTION_CRY, appWidgetId))

        mgr.updateAppWidget(appWidgetId, views)
        // updateAppWidget (completo, no parcial) es lo unico que resetea la cuenta de memoria de
        // bitmaps del widget en Android - ver PetState.markFullRender/FULL_RENDER_RESET_INTERVAL_MS.
        PetState.markFullRender(context)
    }

    private fun iconFor(action: String): String = when (action) {
        ACTION_FEED -> "🍎"; ACTION_PET -> "🥱"; ACTION_WASH -> "💧"; else -> "🍎"
    }

    /** Tamaño/posicion del huevo y la nube, AJUSTADOS A MANO por el usuario en el simulador
     *  visual (uno por cada altura de pokemon_area real que probo) - ver conversacion. Para
     *  cualquier altura que no sea exactamente una de estas, se interpola entre las dos mas
     *  cercanas (misma logica que la tarjeta "interpolada" del propio simulador). */
    private data class LayoutBreakpoint(
        val areaH: Float, val spriteSize: Float, val spriteLeft: Float, val spriteTop: Float,
        val eggSize: Float, val eggLeft: Float, val eggTop: Float,
        val cloudScale: Float, val cloudLeft: Float, val cloudTop: Float, val cloudTail: Float
    )

    // Sprite de referencia usado por el simulador visual (Raichu, 74x73dp) para derivar el ALTO
    // del Pokemon a partir de spriteSize (el ancho, unico que se ajusta con slider) - el sprite
    // real de cada especie mantiene su propia proporcion via scaleType="fitCenter" DENTRO de esta
    // caja, asi que solo hace falta una caja con la proporcion de referencia, no la del Pokemon
    // actual.
    private val SPRITE_REF_W = 74f
    private val SPRITE_REF_H = 73f

    // IMPORTANTE: la fila usa la MISMA formula de alto sin importar cuantas columnas tenga el
    // widget, asi que la altura real (areaH) se REPITE entre familias de ancho distintas (ej.
    // 337f es tanto "3 columnas x 6 filas" como "4 columnas x 6 filas", con huevo/nube en sitios
    // bien distintos en cada una). Meter las 13 en una sola tabla ordenada solo por alto (como se
    // hizo al principio) era un bug real: para areaH=337 elegia SIEMPRE la entrada de 3 columnas
    // (por venir antes en la lista), asi que un widget de 4 columnas se pintaba con los valores
    // ajustados para 3 - el usuario lo detecto en el propio dispositivo. Ahora hay UNA tabla por
    // familia de ancho, y solo se interpola alto DENTRO de la familia que le toca al ancho real
    // del widget (ver layoutFamilyFor). No hay familia de 5 columnas: el usuario confirmo que su
    // launcher no permite un widget tan ancho (maximo real 4 columnas), asi que cualquier ancho
    // mayor cae en la familia de 4 columnas (la mas cercana). min*W = ancho REAL medido en el
    // dispositivo (ver conversacion, sesion de medicion completa).
    //
    // Las alturas (areaH) de esta tabla TAMBIEN se corrigieron: se habian estimado con una formula
    // (48dp por fila) que resulto estar mal - el usuario redimensiono el widget real por las 6
    // alturas de cada ancho y las reales resultaron ser 48, 144, 241, 337, 433, 530 (no 48, 96,
    // 144... ni las estimadas 145/193/241/289/337/385). De esas 6, "241" y "337" YA eran reales
    // desde el principio (las dos unicas medidas directamente en el dispositivo, con las que se
    // afino la formula original) y sus valores tuneados en el simulador seguian siendo validos.
    // "145" tambien resulto casi identico al real "144" (1dp de diferencia, sin repercusion). Las
    // otras tres (48, 433, 530) nunca se habian tuneado contra un tamaño real - de momento llevan
    // el mismo valor que su vecino real mas cercano como punto de partida (nunca se han visto asi
    // en el simulador todavia), a la espera de que el usuario las afine alli como el resto.
    private val FAMILY_3COL_MINW = 252f
    private val FAMILY_4COL_MINW = 347f

    // areaH RECALCULADO tras meter el chrome dinamico (ver chromeHeightDp): con chrome fijo
    // (128dp) minH=176 daba areaH=48 y minH=272 daba areaH=144 - ahora que el chrome se encoge en
    // tamaños bajos, esos mismos minH dejan bastante MAS hueco real (el chrome compacto son 70dp
    // apilado / 60dp en linea, no 128). Los eggSize/cloudScale de estas filas son solo el punto de
    // partida anterior (pensado para un hueco mas pequeño del que hay ahora) - hace falta
    // retunearlos en el simulador contra estos areaH nuevos.
    private val LAYOUT_BREAKPOINTS_3COL = listOf(
        LayoutBreakpoint(areaH = 106f, spriteSize = 107.5f, spriteLeft = 64.3f, spriteTop = 0.0f, eggSize = 65.7f, eggLeft = 174.0f, eggTop = 35.0f, cloudScale = 0.880f, cloudLeft = 9.6f, cloudTop = 2.0f, cloudTail = 0.44f), // 3col·48
        LayoutBreakpoint(areaH = 173f, spriteSize = 135.0f, spriteLeft = 55.0f, spriteTop = 33.0f, eggSize = 84.0f, eggLeft = 157.0f, eggTop = 77.0f, cloudScale = 1.040f, cloudLeft = 4.5f, cloudTop = 2.0f, cloudTail = 0.44f), // 3col·144
        LayoutBreakpoint(areaH = 241f, spriteSize = 161.0f, spriteLeft = 30.0f, spriteTop = 76.0f, eggSize = 88.0f, eggLeft = 155.0f, eggTop = 138.0f, cloudScale = 1.240f, cloudLeft = 0.0f, cloudTop = 21.0f, cloudTail = 0.81f), // 3col·241
        LayoutBreakpoint(areaH = 337f, spriteSize = 187.0f, spriteLeft = 6.0f, spriteTop = 100.0f, eggSize = 107.0f, eggLeft = 138.0f, eggTop = 216.0f, cloudScale = 1.340f, cloudLeft = 6.0f, cloudTop = 36.0f, cloudTail = 1.00f), // 3col·337
        LayoutBreakpoint(areaH = 433f, spriteSize = 192.0f, spriteLeft = 4.0f, spriteTop = 160.0f, eggSize = 98.0f, eggLeft = 143.0f, eggTop = 304.0f, cloudScale = 1.400f, cloudLeft = 0.0f, cloudTop = 88.0f, cloudTail = 1.00f), // 3col·433
        LayoutBreakpoint(areaH = 530f, spriteSize = 196.0f, spriteLeft = 0.0f, spriteTop = 212.0f, eggSize = 103.0f, eggLeft = 140.0f, eggTop = 356.0f, cloudScale = 1.400f, cloudLeft = 0.0f, cloudTop = 148.6f, cloudTail = 1.00f), // 3col·530
    ).sortedBy { it.areaH }

    private val LAYOUT_BREAKPOINTS_4COL = listOf(
        LayoutBreakpoint(areaH = 116f, spriteSize = 117.6f, spriteLeft = 106.7f, spriteTop = 0.0f, eggSize = 72.0f, eggLeft = 247.0f, eggTop = 26.0f, cloudScale = 1.060f, cloudLeft = 35.0f, cloudTop = 2.0f, cloudTail = 0.44f), // 4col·48
        LayoutBreakpoint(areaH = 188f, spriteSize = 190.7f, spriteLeft = 70.1f, spriteTop = 0.0f, eggSize = 84.0f, eggLeft = 252.0f, eggTop = 77.0f, cloudScale = 1.400f, cloudLeft = 10.5f, cloudTop = 2.0f, cloudTail = 0.44f), // 4col·144
        LayoutBreakpoint(areaH = 261f, spriteSize = 225.0f, spriteLeft = 33.2f, spriteTop = 30.0f, eggSize = 110.0f, eggLeft = 235.0f, eggTop = 138.0f, cloudScale = 1.260f, cloudLeft = 5.0f, cloudTop = 2.0f, cloudTail = 0.81f), // 4col·241
        LayoutBreakpoint(areaH = 357f, spriteSize = 251.0f, spriteLeft = 21.0f, spriteTop = 100.0f, eggSize = 150.0f, eggLeft = 203.0f, eggTop = 198.0f, cloudScale = 1.400f, cloudLeft = 0.0f, cloudTop = 36.0f, cloudTail = 1.00f), // 4col·337
        LayoutBreakpoint(areaH = 453f, spriteSize = 270.0f, spriteLeft = 0.0f, spriteTop = 133.0f, eggSize = 148.0f, eggLeft = 201.0f, eggTop = 286.0f, cloudScale = 1.400f, cloudLeft = 0.0f, cloudTop = 63.2f, cloudTail = 1.00f), // 4col·433
        LayoutBreakpoint(areaH = 550f, spriteSize = 282.0f, spriteLeft = 0.0f, spriteTop = 167.0f, eggSize = 152.0f, eggLeft = 199.0f, eggTop = 356.0f, cloudScale = 1.400f, cloudLeft = 9.0f, cloudTop = 94.0f, cloudTail = 1.00f), // 4col·530
    ).sortedBy { it.areaH }

    /** Familia (tabla de columnas) mas cercana al ancho REAL del widget ahora mismo. */
    private fun layoutFamilyFor(minW: Int): List<LayoutBreakpoint> {
        val families = listOf(
            FAMILY_3COL_MINW to LAYOUT_BREAKPOINTS_3COL,
            FAMILY_4COL_MINW to LAYOUT_BREAKPOINTS_4COL,
        )
        return families.minBy { kotlin.math.abs(minW - it.first) }.second
    }

    /** true si el ancho real del widget esta mas cerca de la familia de 4 columnas que de la de
     *  3 - misma logica de "familia mas cercana" que layoutFamilyFor, pero como booleano (hace
     *  falta en applyChromeSize para elegir barras apiladas vs en linea). */
    private fun isFourColFamily(minW: Int): Boolean =
        kotlin.math.abs(minW - FAMILY_4COL_MINW) < kotlin.math.abs(minW - FAMILY_3COL_MINW)

    private fun interpolatedLayout(pts: List<LayoutBreakpoint>, areaH: Float): LayoutBreakpoint {
        if (pts.size == 1) return pts[0]
        // EXTRAPOLA mas alla del primer/ultimo punto (misma pendiente que el tramo mas cercano)
        // en vez de quedarse pegado al valor del extremo - antes se quedaba clavado en los
        // numeros de 4x6 para cualquier altura mayor (4x7 y en adelante), bug real reportado por
        // el usuario ("no se actualiza, se queda en su posicion anterior").
        val (lo, hi) = when {
            areaH <= pts.first().areaH -> pts.getOrElse(0) { pts[0] } to pts.getOrElse(1) { pts[0] }
            areaH >= pts.last().areaH -> pts.getOrElse(pts.size - 2) { pts.last() } to pts.last()
            else -> {
                var l = pts.first(); var h = pts.last()
                for (i in 0 until pts.size - 1) {
                    if (pts[i].areaH <= areaH && pts[i + 1].areaH >= areaH) { l = pts[i]; h = pts[i + 1]; break }
                }
                l to h
            }
        }
        val t = if (hi.areaH == lo.areaH) 0f else (areaH - lo.areaH) / (hi.areaH - lo.areaH)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return LayoutBreakpoint(
            areaH, mix(lo.spriteSize, hi.spriteSize), mix(lo.spriteLeft, hi.spriteLeft), mix(lo.spriteTop, hi.spriteTop),
            mix(lo.eggSize, hi.eggSize), mix(lo.eggLeft, hi.eggLeft), mix(lo.eggTop, hi.eggTop),
            mix(lo.cloudScale, hi.cloudScale), mix(lo.cloudLeft, hi.cloudLeft), mix(lo.cloudTop, hi.cloudTop),
            mix(lo.cloudTail, hi.cloudTail)
        )
    }

    /** Tamaños de las barras/botones/texto (todo lo que NO es pokemon_area) - ver applyChromeSize.
     *  NORMAL es el tamaño de siempre (XML), COMPACT es para el widget mas bajo posible (4x2/3x2,
     *  minH=176): las barras y botones tenian un tamaño FIJO sin importar cuanto widget quedase
     *  despues de restarlos, asi que en un widget muy bajo se comian casi todo el hueco y dejaban
     *  al Pokemon minusculo (bug real reportado por el usuario). */
    private data class ChromeSize(
        val barHeightDp: Float, val buttonHeightDp: Float,
        val levelTextSp: Float, val iconTextSp: Float, val buttonTextSp: Float,
        val statRowMarginDp: Float, val buttonRowMarginDp: Float, val rootPaddingDp: Float
    )
    private val CHROME_COMPACT = ChromeSize(
        barHeightDp = 4f, buttonHeightDp = 22f, levelTextSp = 9f, iconTextSp = 8f, buttonTextSp = 11f,
        statRowMarginDp = 1f, buttonRowMarginDp = 2f, rootPaddingDp = 4f
    )
    private val CHROME_NORMAL = ChromeSize(
        barHeightDp = 8f, buttonHeightDp = 40f, levelTextSp = 14f, iconTextSp = 12f, buttonTextSp = 16f,
        statRowMarginDp = 2f, buttonRowMarginDp = 6f, rootPaddingDp = 8f
    )

    /** Interpola entre CHROME_COMPACT (minH=176, el mas bajo real) y CHROME_NORMAL (minH=369, la
     *  primera altura real que ya se veia bien de sobra sin tocar nada) - fuera de ese rango se
     *  queda clavado en el extremo mas cercano (no hace falta extrapolar: nunca hizo falta un
     *  "mas compacto que compacto" ni un "mas grande que normal"). */
    private fun chromeSizeFor(minH: Int): ChromeSize {
        val t = chromeSizeT(minH)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return ChromeSize(
            mix(CHROME_COMPACT.barHeightDp, CHROME_NORMAL.barHeightDp),
            mix(CHROME_COMPACT.buttonHeightDp, CHROME_NORMAL.buttonHeightDp),
            mix(CHROME_COMPACT.levelTextSp, CHROME_NORMAL.levelTextSp),
            mix(CHROME_COMPACT.iconTextSp, CHROME_NORMAL.iconTextSp),
            mix(CHROME_COMPACT.buttonTextSp, CHROME_NORMAL.buttonTextSp),
            mix(CHROME_COMPACT.statRowMarginDp, CHROME_NORMAL.statRowMarginDp),
            mix(CHROME_COMPACT.buttonRowMarginDp, CHROME_NORMAL.buttonRowMarginDp),
            mix(CHROME_COMPACT.rootPaddingDp, CHROME_NORMAL.rootPaddingDp)
        )
    }

    private fun chromeSizeT(minH: Int): Float = ((minH - 176f) / (369f - 176f)).coerceIn(0f, 1f)

    // Alto REAL (medido en el dispositivo) de todo lo que NO es pokemon_area, en el tamaño mas
    // compacto (STACKED, minH=176) y en el normal de siempre (minH>=369, sin tocar - es el mismo
    // WIDGET_CHROME_HEIGHT_DP que ya llevaba tiempo funcionando bien). ROW (barras en linea, 4
    // columnas) resta el alto de las 2 filas de estadisticas que ya no hacen falta (2 barras + 2
    // margenes entre filas) sobre esos mismos anchors reales - no medido pixel a pixel para esa
    // variante todavia, pero el margen de error solo mueve al Pokemon uno o dos dp, no hay riesgo
    // de que se solape con las barras (mejor quedarse corto que largo aqui).
    private val CHROME_HEIGHT_STACKED_COMPACT = 70f
    private val CHROME_HEIGHT_STACKED_NORMAL = 128f
    private val CHROME_HEIGHT_ROW_COMPACT = 60f
    private val CHROME_HEIGHT_ROW_NORMAL = 108f

    private fun chromeHeightDp(minH: Int, isFourCol: Boolean): Float {
        val t = chromeSizeT(minH)
        val compact = if (isFourCol) CHROME_HEIGHT_ROW_COMPACT else CHROME_HEIGHT_STACKED_COMPACT
        val normal = if (isFourCol) CHROME_HEIGHT_ROW_NORMAL else CHROME_HEIGHT_STACKED_NORMAL
        return compact + (normal - compact) * t
    }

    /** Encoge barras/botones/texto/margenes cuanto mas bajo sea el widget real, para que dejen de
     *  comerse casi todo el hueco en los tamaños pequeños (ver ChromeSize), y alterna entre las 2
     *  variantes de estadisticas (apiladas/en linea, ver widget_pokegotchi.xml) segun el ancho -
     *  pedido explicito del usuario: en 4 columnas sobra ancho para meter las 3 barras en una sola
     *  fila y ganar alto para el Pokemon; en 3 columnas se quedan apiladas como siempre. Solo
     *  Android 12+ (setViewLayoutWidth/Height/Margin) - en versiones mas viejas se queda con el
     *  XML fijo (apiladas, tamaño normal). */
    private fun applyChromeSize(context: Context, views: RemoteViews, minH: Int, isFourCol: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val c = chromeSizeFor(minH)
        views.setViewVisibility(R.id.stats_stacked, if (isFourCol) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.stats_row, if (isFourCol) View.VISIBLE else View.GONE)
        val barIds = intArrayOf(
            R.id.xp_bar, R.id.stat_health, R.id.stat_hygiene, R.id.stat_happiness,
            R.id.stat_health_row, R.id.stat_hygiene_row, R.id.stat_happiness_row
        )
        for (id in barIds) views.setViewLayoutHeight(id, c.barHeightDp, TypedValue.COMPLEX_UNIT_DIP)
        for (id in intArrayOf(R.id.btn_feed, R.id.btn_pet, R.id.btn_wash)) {
            views.setViewLayoutHeight(id, c.buttonHeightDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, c.buttonTextSp)
        }
        views.setTextViewTextSize(R.id.widget_level, TypedValue.COMPLEX_UNIT_SP, c.levelTextSp)
        val iconIds = intArrayOf(
            R.id.icon_health, R.id.icon_hygiene, R.id.icon_happiness,
            R.id.icon_health_row, R.id.icon_hygiene_row, R.id.icon_happiness_row
        )
        for (id in iconIds) views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, c.iconTextSp)
        views.setViewLayoutMargin(R.id.row_hygiene, RemoteViews.MARGIN_TOP, c.statRowMarginDp, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutMargin(R.id.row_happiness, RemoteViews.MARGIN_TOP, c.statRowMarginDp, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutMargin(R.id.button_row, RemoteViews.MARGIN_TOP, c.buttonRowMarginDp, TypedValue.COMPLEX_UNIT_DIP)
        // setViewPadding pide PIXELS de verdad (no admite unidad como los demas setViewLayout*).
        val density = context.resources.displayMetrics.density
        val paddingPx = (c.rootPaddingDp * density).roundToInt()
        views.setViewPadding(R.id.content_root, paddingPx, paddingPx, paddingPx, paddingPx)
    }

    /**
     * El huevo y la nube tenian tamaño/posicion FIJOS que se veian desproporcionados o mal
     * colocados segun el tamaño real del widget. Esto calcula el area real de pokemon_area
     * (segun el tamaño REAL del widget ahora mismo, via getAppWidgetOptions) e interpola entre
     * los puntos ajustados a mano en el simulador visual (ver LAYOUT_BREAKPOINTS). Solo Android
     * 12+ (setViewLayoutWidth/Margin) - en versiones mas viejas se queda con el tamaño/posicion
     * fijos definidos en el XML.
     */
    private fun applyAdaptiveEggAndCloudLayout(context: Context, views: RemoteViews, mgr: AppWidgetManager, appWidgetId: Int, s: PetState.Stats) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val opts = try { mgr.getAppWidgetOptions(appWidgetId) } catch (e: Exception) { return }
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        if (minW <= 0 || minH <= 0) return
        val isFourCol = isFourColFamily(minW)
        applyChromeSize(context, views, minH, isFourCol)
        val areaHeightDp = (minH - chromeHeightDp(minH, isFourCol)).coerceAtLeast(40f)

        val v = interpolatedLayout(layoutFamilyFor(minW), areaHeightDp)
        dbg(context, "layout: minW=$minW minH=$minH areaH=$areaHeightDp -> spriteSize=${v.spriteSize} spriteLeft=${v.spriteLeft} spriteTop=${v.spriteTop} eggSize=${v.eggSize} eggLeft=${v.eggLeft} eggTop=${v.eggTop} cloudScale=${v.cloudScale} cloudLeft=${v.cloudLeft} cloudTop=${v.cloudTop} cloudTail=${v.cloudTail}")

        // Pokemon: antes SIN parametrizar (solo XML - ImageView match_parent + scaleType=
        // "fitCenter" en item_sprite_frame.xml/fx_overlay, se encogia/centraba el solo dentro de
        // TODO el hueco). Ahora usa el mismo breakpoint ajustado a mano en el simulador visual que
        // ya usan huevo/nube. Se aplica a los DOS contenedores que pueden mostrar el sprite -
        // widget_pokemon (idle, ViewFlipper) y fx_overlay (reaccion, ver playReactionOnce) - para
        // que ambos coincidan y no "salte" de tamaño/sitio al iniciar/acabar una reaccion. El alto
        // se deriva del ancho via la proporcion del sprite de referencia (ver SPRITE_REF_W/H) -
        // igual que en el simulador: no tiene sentido un slider de alto aparte para una imagen que
        // dentro de esta caja siempre mantiene su propia proporcion real via fitCenter.
        //
        // OJO: se sospecho en un primer momento que este bloque causaba un crash real de memoria
        // (RemoteViews exceeds maximum bitmap memory usage) visto en el movil - se descarto EN
        // FIRME: el mismo crash, con los MISMOS numeros exactos de memoria usada, se reprodujo
        // tambien con este bloque totalmente desactivado. Es un bug preexistente y ajeno a esto
        // (parece estar cerca del tope de memoria de RemoteViews del dispositivo ya de por si, y
        // acumularse con repetidas actualizaciones parciales via renderStatsOnly) - hace falta
        // investigarlo aparte, no bloquea esta funcionalidad.
        val spriteDispH = v.spriteSize * (SPRITE_REF_H / SPRITE_REF_W)

        // INTENTO REVERTIDO (probado y descartado por el usuario, "creo que es mejor dejarlo
        // como estaba"): encoger la caja del sprite segun su tamaño nativo real, para que
        // especies pequeñas (Snivy) se vean mas chicas que las grandes (Serperior) en vez de
        // estirarse todas al mismo hueco. Volvio a como estaba: TODOS los sprites usan el mismo
        // spriteSize/spriteDispH del breakpoint, sin factor por especie.
        for (id in intArrayOf(R.id.widget_pokemon, R.id.fx_overlay)) {
            views.setViewLayoutWidth(id, v.spriteSize, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(id, spriteDispH, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(id, RemoteViews.MARGIN_START, v.spriteLeft, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, v.spriteTop, TypedValue.COMPLEX_UNIT_DIP)
        }

        for (id in intArrayOf(R.id.egg_overlay, R.id.egg_flipper)) {
            views.setViewLayoutWidth(id, v.eggSize, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(id, v.eggSize, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(id, RemoteViews.MARGIN_START, v.eggLeft, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, v.eggTop, TypedValue.COMPLEX_UNIT_DIP)
        }

        val icon = currentNeedIcon(context, s)
        if (icon != null) {
            // Colita de la nube: en widgets muy bajos (2-3 filas) la diagonal normal se salia del
            // hueco por abajo y apuntaba hacia las barras en vez de al Pokemon - pedido explicito
            // del usuario. Ya no se deriva de una formula aparte: v.cloudTail viene del mismo
            // breakpoint ajustado a mano en el simulador visual (ver LAYOUT_BREAKPOINTS), asi que
            // el angulo de cola que el usuario elige y ve en la herramienta es el mismo que sale
            // en el widget real.
            val cloudBmp = EffectGenerator.needCloud(icon, v.cloudScale, v.cloudTail)
            views.setImageViewBitmap(R.id.need_cloud, cloudBmp)
            // cloudBmp.width/height son PIXELS del bitmap, no dp - hay que dividir por la densidad
            // real de pantalla para saber cuanto va a medir en dp (en este movil, densidad=3, asi
            // que un bitmap de 272px mide unos 90dp, no 272dp). La primera vez que esto se escribio
            // se paso por alto esa conversion y se trataron los pixeles como si ya fueran dp, lo
            // que inflaba el tamaño real x3 - eso, no el cloudScale en si, era la causa de que la
            // nube pareciera enorme al forzar aqui un tamaño explicito.
            val density = context.resources.displayMetrics.density
            val cloudDpW = cloudBmp.width / density
            val cloudDpH = cloudBmp.height / density
            // pokemon_area es un FrameLayout (clipChildren=true por defecto) con la altura REAL
            // del hueco disponible - si el bitmap de la nube (su tamaño no dependia de cuanto
            // hueco quedaba, solo de cloudScale) es mas alto que ese hueco, se recortaba por abajo
            // sin avisar (bug real reportado por el usuario en tamaños pequeños). El huevo ya
            // tenia este mismo tipo de limite via setViewLayoutWidth/Height; a la nube le faltaba.
            // Si no cabe, se encoge proporcionalmente (fitCenter en el XML) en vez de recortarse.
            // SIEMPRE se fija un tamaño explicito (no solo cuando hay que encoger): renderStatsOnly
            // usa partiallyUpdateAppWidget, que fusiona con el arbol YA inflado en vez de partir de
            // cero - si aqui solo se tocara el tamaño cuando hace falta encoger, un widget que
            // pasase de un tamaño pequeño (encogido) a uno grande (donde no haría falta) podria
            // quedarse pegado al tamaño encogido anterior, al no haber ninguna instruccion nueva
            // que lo devolviera al wrap_content de la XML. Fijarlo siempre evita depender de ese
            // comportamiento de fusion.
            val maxCloudH = (areaHeightDp - v.cloudTop).coerceAtLeast(1f)
            if (cloudDpH > maxCloudH) {
                val shrink = maxCloudH / cloudDpH
                views.setViewLayoutWidth(R.id.need_cloud, cloudDpW * shrink, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.need_cloud, maxCloudH, TypedValue.COMPLEX_UNIT_DIP)
            } else {
                views.setViewLayoutWidth(R.id.need_cloud, cloudDpW, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.need_cloud, cloudDpH, TypedValue.COMPLEX_UNIT_DIP)
            }
            views.setViewLayoutMargin(R.id.need_cloud, RemoteViews.MARGIN_START, v.cloudLeft, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(R.id.need_cloud, RemoteViews.MARGIN_TOP, v.cloudTop, TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    /** El icono a mostrar en la nube de necesidad ahora mismo (el rechazo tiene prioridad sobre
     *  cualquier necesidad real, para dejar claro que NO es un fallo de la app), o null si no
     *  hay nada que mostrar. */
    private fun currentNeedIcon(context: Context, s: PetState.Stats): String? {
        val rejected = PetState.activeRejectFlash(context)
        return if (rejected != null) "🚫" else PetState.mostNeededAction(context, s)?.let { iconFor(it) }
    }

    /**
     * "❗" en el boton de la accion que hace falta ahora mismo, y visibilidad de la nube de
     * necesidad (el bitmap/tamaño/posicion real de la nube los fija
     * applyAdaptiveEggAndCloudLayout, que ya sabe el hueco real que deja el sprite).
     */
    private fun applyNeedIndicators(context: Context, views: RemoteViews, s: PetState.Stats) {
        val needFeed = PetState.isNeeded(context, s, ACTION_FEED)
        val needPet = PetState.isNeeded(context, s, ACTION_PET)
        val needWash = PetState.isNeeded(context, s, ACTION_WASH)
        views.setTextViewText(R.id.btn_feed, if (needFeed) "🍎❗" else "🍎")
        views.setTextViewText(R.id.btn_pet, if (needPet) "✋❗" else "✋")
        views.setTextViewText(R.id.btn_wash, if (needWash) "🚿❗" else "🚿")
        views.setViewVisibility(R.id.need_cloud, if (currentNeedIcon(context, s) == null) View.GONE else View.VISIBLE)
    }

    // Palpito CONTINUO por fase (1..3, ver egg_flipper): cada uno es un vaiven "poco a mas y de
    // mas a poco" (empieza y acaba en 0) seguido de un tramo de reposo (angulo 0 sostenido) antes
    // de repetir - salvo la ULTIMA fase, que no descansa nunca. Menos oscilaciones y amplitud
    // cuanto mas lejos de eclosionar. El intervalo de cada fase es mas LARGO cuanto antes este
    // (fase 1/2), asi el tramo de reposo no necesita un numero enorme de fotogramas identicos -
    // cada fotograma es un bitmap completo, y Android limita cuanta memoria de bitmaps puede
    // llevar UNA actualizacion de RemoteViews (ya crasheo una vez con 32 fotogramas de golpe, ver
    // excepcion real "exceeds maximum bitmap memory usage" - con esto el total por fase se queda
    // muy por debajo de eso).
    private val EGG_STAGE_FLIP_MS = intArrayOf(0, 750, 550, 130)
    private val EGG_STAGE_AMP_DEG = floatArrayOf(0f, 10f, 13f, 16f)
    private val EGG_STAGE_WOBBLE_CYCLES = intArrayOf(0, 1, 2, 3)
    private val EGG_STAGE_WOBBLE_FRAMES = intArrayOf(0, 8, 10, 14)
    private val EGG_STAGE_PAUSE_MS = longArrayOf(0L, 15_000L, 5_000L, 0L)  // fase 3 no descansa nunca

    private fun eggShakeLoopDeg(stage: Int): FloatArray {
        val wobbleFrames = EGG_STAGE_WOBBLE_FRAMES[stage]
        val amp = EGG_STAGE_AMP_DEG[stage]
        val cycles = EGG_STAGE_WOBBLE_CYCLES[stage]
        val wobble = FloatArray(wobbleFrames) { i ->
            val t = i / wobbleFrames.toFloat()
            val envelope = kotlin.math.sin(Math.PI.toFloat() * t)
            amp * envelope * kotlin.math.sin(2f * Math.PI.toFloat() * cycles * t)
        }
        val restFrames = (EGG_STAGE_PAUSE_MS[stage] / EGG_STAGE_FLIP_MS[stage].toLong()).toInt()
        return wobble + FloatArray(restFrames) { 0f }
    }

    /** Huevo junto al Pokemon: oculto si no hay ninguno pendiente; si lo hay, la fase de rotura
     *  (o ya abierto, si esta listo para revelar) generada al vuelo. [shakeAngleDeg] rota el huevo
     *  sobre su propio centro (fijo, sin desplazarse): 0 = en reposo; distinto de 0 solo durante
     *  una rafaga de palpito real (ver playEggPulse) - ya SOLO se usa para la fase 0 (nunca
     *  dispara ninguna, EGG_PULSE_PERIOD_MS[0]=nunca, asi que en la practica no se llega a usar).
     *  Las fases 1..3 ya no disparan rafagas (ver eggPulseDue) y se animan ellas solas, sin
     *  parar, con egg_flipper (autoStart nativo, sin ningun hilo de fondo sostenido - evita el
     *  bug de congelacion que ya se dio al intentar sostener esto con Thread/goAsync, ver
     *  playEggPulse). */
    private fun applyEggIndicator(context: Context, views: RemoteViews, shakeAngleDeg: Float = 0f) {
        if (!PetState.activeIsEggParent(context)) {
            views.setViewVisibility(R.id.egg_overlay, View.GONE)
            views.setViewVisibility(R.id.egg_flipper, View.GONE)
            return
        }
        val ready = PetState.isEggReady(context)
        // Huevo YA eclosionado del todo (listo para revelar): tocarlo abre la app para ver la
        // animacion de eclosion (MainActivity.showEggHatchDialog, se dispara sola al abrir con un
        // huevo listo) - pedido explicito del usuario. Mismo PendingIntent (misma Activity, mismo
        // requestCode=1) que ya usan widget_level/btn_open_app, asi que no hace falta uno nuevo.
        // Mientras el huevo SIGUE incubando (no listo) no se pone ningun click - null lo QUITA
        // explicitamente por si antes (en una actualizacion parcial previa) llego a estar listo.
        views.setOnClickPendingIntent(
            R.id.egg_overlay,
            if (ready) PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ) else null
        )
        val stage = PetState.eggCrackStage(context)
        if (!ready && stage >= 1 && shakeAngleDeg == 0f) {
            views.setViewVisibility(R.id.egg_overlay, View.GONE)
            views.removeAllViews(R.id.egg_flipper)
            views.setInt(R.id.egg_flipper, "setFlipInterval", EGG_STAGE_FLIP_MS[stage])
            // Muchos angulos se repiten (todo el tramo de reposo es el mismo 0 grados una y otra
            // vez) - generar un bitmap NUEVO por cada fotograma agoto la memoria que Android deja
            // llevar a una actualizacion de RemoteViews (crash real, "exceeds maximum bitmap
            // memory usage", con 28 fotogramas de fase 1). Reutilizando el MISMO objeto Bitmap
            // para angulos repetidos, RemoteViews lo detecta (cache por identidad, no por
            // contenido) y solo lo cuenta una vez.
            val bitmapCache = HashMap<Float, Bitmap>()
            for (angle in eggShakeLoopDeg(stage)) {
                val bmp = bitmapCache.getOrPut(angle) { EffectGenerator.eggBitmap(context, stage, hatched = false, shakeAngleDeg = angle) }
                val frame = RemoteViews(context.packageName, R.layout.item_sprite_frame)
                frame.setImageViewBitmap(R.id.frame_img, bmp)
                views.addView(R.id.egg_flipper, frame)
            }
            views.setViewVisibility(R.id.egg_flipper, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.egg_flipper, View.GONE)
            val bmp = if (ready) EffectGenerator.eggBitmap(context, 0, hatched = true)
            else EffectGenerator.eggBitmap(context, stage, hatched = false, shakeAngleDeg = shakeAngleDeg)
            views.setImageViewBitmap(R.id.egg_overlay, bmp)
            views.setViewVisibility(R.id.egg_overlay, View.VISIBLE)
        }
    }

    /** Rafaga REAL de "palpito" del huevo: unos segundos con varios vaivenes, meciendose sobre su
     *  propia base (como un huevo de verdad: la parte de arriba oscila, la base no se mueve del
     *  sitio) - NO usa actualizaciones repetidas del `egg_overlay` normal, porque se confirmo en
     *  el dispositivo que refrescar esa vista sola (aunque sea con actualizaciones parciales)
     *  interfiere con la animacion del ViewFlipper del sprite (se veia "reiniciar" todo el rato).
     *  En su lugar, reutiliza EXACTAMENTE el mismo mecanismo ya probado de playReactionOnce: el
     *  flipper se oculta y cada frame compone a mano el sprite (ciclando sus frames idle, para que
     *  siga viendose "vivo") + el huevo encima, en un unico bitmap pintado en fx_overlay via
     *  render(...,overlayBmp). Asi el flipper nunca se toca durante la rafaga. No se solapa con
     *  una reaccion normal ni con otra rafaga en curso (eggPulseRunning / reactionRunning). */
    private fun playEggPulse(context: Context, stage: Int) {
        if (eggPulseRunning || reactionRunning) return
        eggPulseRunning = true
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PokeWidgetProvider::class.java))
        val poke = activeSpriteName(context)
        val styleKey = PetState.currentStyleKey(context)
        val meta = SpriteRepository.readMeta(context, styleKey, poke)
        val w = meta?.w?.takeIf { it > 0 } ?: 50
        val h = meta?.h?.takeIf { it > 0 } ?: 46
        val spriteFC = (meta?.frameCount ?: 1).coerceAtLeast(1)
        val scale = 4  // mismo criterio de escala que EffectGenerator usa para el resto de efectos
        val compW = w * scale; val compH = h * scale
        val spriteFrameMs = 180L  // igual al flipInterval real del idle, para que siga "vivo" igual

        val durationMs = 6000L
        val frameMs = 140L
        val frames = (durationMs / frameMs).toInt()
        val cycles = 4 + stage             // mas vaivenes cuanto mas cerca de eclosionar
        val ampDeg = 9f + stage * 6f       // fase 1 => +-15 grados, fase 3 => +-27 grados
        // SONIDO QUITADO A PROPOSITO (temporalmente): ni con Handler al principal ni con un hilo
        // aparte se soluciono la congelacion del sprite/huevo, y el usuario ni siquiera llego a
        // OIR el sonido - confirmado que la congelacion segue IGUAL incluso sin sonido de por
        // medio, asi que el sonido NUNCA fue la causa. No se ha borrado SoundManager.playEggCrack
        // ni el recurso sfx_egg_crack.mp3 (quedan listos para retomarlo cuando se resuelva esto).
        //
        // BUCLE de la fase final QUITADO TAMBIEN (temporalmente), por el mismo motivo: es la
        // UNICA diferencia real entre esta rafaga y playReactionOnce (que nunca ha dado problemas)
        // aparte del sonido ya descartado - mantener goAsync/el hilo activo de forma sostenida
        // durante hasta 24s seguidos (fase final) es mucho mas que los ~4.6s de una reaccion
        // normal, y es sospechoso de que MIUI aplique algun tipo de limite/throttling de
        // ejecucion en segundo plano a partir de cierta duracion sostenida. Se vuelve a UNA
        // rafaga simple por disparo (sin repetirse sola) para las 4 fases, para aislar si esto
        // es la causa real de la congelacion.
        val pending = goAsync()
        Thread {
            try {
                for (f in 0 until frames) {
                    val t = f.toFloat() / frames
                    // Envolvente (sube y baja, empieza y acaba en 0) para que el palpito entre y
                    // salga suave del reposo, en vez de arrancar/parar de golpe.
                    val envelope = kotlin.math.sin((t * Math.PI.toFloat())).coerceAtLeast(0f)
                    val angle = ampDeg * envelope * kotlin.math.sin(2f * Math.PI.toFloat() * cycles * t)

                    val comp = Bitmap.createBitmap(compW, compH, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(comp)
                    // Indice basado en el RELOJ ABSOLUTO (no en cuanto lleva corriendo esta
                    // rafaga): el ViewFlipper real tambien avanza por tiempo real a su propio
                    // ritmo, asi que anclarse al reloj (en vez de empezar siempre en el frame 0 de
                    // la rafaga) evita un salto visible en el fotograma justo al ocultar/mostrar
                    // el flipper (se notaba como si el sprite "subiera y bajara" al empezar/acabar
                    // cada rafaga).
                    val spriteIdx = ((System.currentTimeMillis() / spriteFrameMs) % spriteFC).toInt()
                    val spf = BitmapFactory.decodeFile(
                        SpriteRepository.frameFile(context, styleKey, poke, spriteIdx).absolutePath
                    )
                    if (spf != null) {
                        cv.drawBitmap(spf, null, Rect(0, 0, compW, compH), null)
                        spf.recycle()
                    }
                    // El huevo NO se dibuja aqui dentro de comp: comp tiene la proporcion del
                    // SPRITE (w:h), no la del hueco real del widget, asi que al reescalarlo
                    // (fitCenter) podia dejar un margen que desplazaba el huevo de su sitio real.
                    // Se sigue pintando por su cuenta en `egg_overlay` (parametro eggShakeAngleDeg
                    // de render), que ya tiene el posicionamiento correcto de siempre - el flipper
                    // esta oculto de todas formas durante toda la rafaga, asi que tocar ese
                    // hermano suyo no reintroduce el bug de reinicio del sprite.
                    ids.forEach { render(context, mgr, it, comp, eggShakeAngleDeg = angle) }
                    Thread.sleep(frameMs)
                }
            } catch (_: Throwable) {
            } finally {
                eggPulseRunning = false
                // Vuelve al idle normal: flipper de nuevo visible y egg_overlay estatico (si sigue
                // habiendo huevo) - deja de suprimirse porque ya no hay composite que lo duplique.
                try { ids.forEach { render(context, mgr, it) } } catch (_: Throwable) {}
                pending.finish()
            }
        }.start()
    }

    private fun actionPI(context: Context, action: String, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, PokeWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("pokegotchi://widget/$appWidgetId/$action")
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Bug real reportado: con RTC (no-wakeup) + set() (normal, sin permiso de "mientras el
    // dispositivo esta inactivo"), Doze/el ahorro de bateria de MIUI puede posponer esta alarma
    // horas sin avisar - las barras dejan de refrescarse en el widget (aunque el calculo en si
    // sigue siendo correcto: al abrir la app, loadWithDecay recupera todo el tiempo real pasado
    // de golpe, por eso "explota" de repente). RTC_WAKEUP + setAndAllowWhileIdle SI atraviesa
    // Doze sin pedir ningun permiso especial (a diferencia de setExactAndAllowWhileIdle, que en
    // Android 12+ exigiria SCHEDULE_EXACT_ALARM) - encaja bien aqui porque no hace falta el
    // segundo exacto, solo que no se quede parado horas.
    private fun scheduleTick(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + TICK_INTERVAL_MS, tickPI(context))
    }

    private fun cancelTick(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(tickPI(context))
    }

    private fun tickPI(context: Context): PendingIntent {
        val intent = Intent(context, PokeWidgetProvider::class.java).apply { action = ACTION_TICK }
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleCryCheck(context: Context, intervalMs: Long = CRY_CHECK_INTERVAL_MS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs, cryCheckPI(context))
    }

    private fun cancelCryCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(cryCheckPI(context))
    }

    private fun cryCheckPI(context: Context): PendingIntent {
        val intent = Intent(context, PokeWidgetProvider::class.java).apply { action = ACTION_CRY_CHECK }
        return PendingIntent.getBroadcast(
            context, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
