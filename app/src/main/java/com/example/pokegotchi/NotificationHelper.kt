package com.example.pokegotchi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notificaciones base: un unico canal, y un aviso por cada condicion silenciosa que ya se
 * calculaba en el codigo (evolucion lista, cuidado urgente) pero que antes solo se veia si el
 * jugador abria la app/el widget por su cuenta. Tocar cualquier notificacion abre la app.
 */
object NotificationHelper {
    private const val CHANNEL_ID = "pokegotchi_alerts"
    private const val NOTIF_ID_EVOLUTION = 1001
    private const val NOTIF_ID_URGENT = 1002
    private const val NOTIF_ID_EGG_LAID = 1003
    private const val NOTIF_ID_EGG_HATCHED = 1004
    private const val NOTIF_ID_OFFER_READY = 1005
    const val NOTIF_ID_DUNGEON = 1006
    // Canal APARTE (no el mismo pokegotchi_alerts de arriba) con importancia BAJA a proposito -
    // pedido explicito del usuario: "que no te notifique, es decir que no te salte la
    // notificacion, sino que cuando tu vas a ver la notificacion puedas ver el estado". Con
    // IMPORTANCE_LOW nunca hace sonido/vibra/aparece flotante al actualizarse, solo se queda
    // siempre visible en la barra con el contenido al dia.
    private const val CHANNEL_ID_DUNGEON = "pokegotchi_dungeon"

    fun ensureDungeonChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID_DUNGEON, "Mazmorra (estado)", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Piso y vida de tu explorador - se actualiza en silencio, nunca avisa"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openDungeonPI(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, NOTIF_ID_DUNGEON, Intent(context, DungeonActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** [ongoing]=true (mientras explora) la deja fija, sin poder deslizarla para quitarla - una
     *  vez termina la carrera se vuelve a llamar con ongoing=false (el resumen final) para que SI
     *  se pueda descartar. setOnlyAlertOnce evita cualquier sonido/aviso en las actualizaciones
     *  aunque el canal cambiase de importancia mas adelante - cinturon y tirantes. Extraido de
     *  updateDungeonNotification para que DungeonService.onCreate pueda pedir el mismo objeto
     *  Notification para startForeground sin tener que publicarlo primero via notify(). */
    private fun buildNotification(context: Context, title: String, text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID_DUNGEON)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openDungeonPI(context))
            .build()

    private fun updateDungeonNotification(context: Context, title: String, text: String, ongoing: Boolean) {
        if (!hasPermission(context)) return
        ensureDungeonChannel(context)
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_DUNGEON, buildNotification(context, title, text, ongoing))
        } catch (_: SecurityException) {
        }
    }

    fun cancelDungeonNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_ID_DUNGEON)

    /** Titulo/texto/ongoing segun el estado actual - compartido por [refreshDungeonNotification]
     *  (publica la notificacion) y [buildDungeonNotification] (se la da a startForeground sin
     *  publicarla aparte, DungeonService ya se encarga de mostrarla). */
    private fun dungeonContent(context: Context): Triple<String, String, Boolean> {
        if (DungeonState.isExploring(context)) {
            val species = DungeonState.currentSpecies(context)
            if (species != null) {
                val shiny = DungeonState.currentShiny(context)
                val floor = DungeonState.currentFloor(context)
                val hp = DungeonState.currentHp(context).toInt()
                val maxHp = DungeonSimulator.maxHp(context, species, shiny)
                return Triple(
                    "🗺️ ${PetState.displayLabel(species)} explorando",
                    "Piso $floor/${DungeonState.MAX_FLOOR} · HP $hp/$maxHp", true
                )
            }
        }
        // Cierre global tras una carrera en modo continuo (ver DungeonSimulator.finishRun/
        // COOLDOWN_MS_PER_FLOOR) - pedido explicito del usuario: "el cooldown... es como que la
        // mazmorra esta cerrada... no porque [el Pokemon] tenga que descansar". [species] puede
        // ser null si se canceló a quien iba a entrar (ver DungeonState.cancelQueuedExplorer).
        if (DungeonState.isInCooldown(context)) {
            val species = DungeonState.currentSpecies(context)
            val label = species?.let { PetState.displayLabel(it) } ?: "nadie"
            val remainingMin = ((DungeonState.cooldownUntil(context) - System.currentTimeMillis())
                .coerceAtLeast(0L) / 60_000L) + 1
            return Triple(
                "🔒 Mazmorra cerrada",
                "Reabre en ~$remainingMin min (llegó al piso ${DungeonState.cooldownFloor(context)}) · entrará: $label",
                true
            )
        }
        val summary = DungeonState.lastRunSummary(context)
        return Triple("Mazmorra", summary ?: "", false)
    }

    /** Un solo punto de entrada para mantener la notificacion de la mazmorra al dia - llamado
     *  tras cada avance de fondo (DungeonService) y tras cada paso en vivo (DungeonView), asi que
     *  ninguno de los dos tiene que saber construir el texto por su cuenta. */
    fun refreshDungeonNotification(context: Context) {
        if (!DungeonState.isExploring(context) && !DungeonState.isInCooldown(context) &&
            DungeonState.lastRunSummary(context) == null) {
            cancelDungeonNotification(context)
            return
        }
        val (title, text, ongoing) = dungeonContent(context)
        updateDungeonNotification(context, title, text, ongoing)
    }

    /** Notificacion lista para startForeground (DungeonService.onCreate) - MISMO contenido que
     *  refreshDungeonNotification, pero devuelta en vez de publicada (startForeground la publica
     *  el sistema). */
    fun buildDungeonNotification(context: Context): Notification {
        ensureDungeonChannel(context)
        val (title, text, ongoing) = dungeonContent(context)
        return buildNotification(context, title, text, ongoing)
    }

    /** Idempotente: crear un canal que ya existe (mismo id) no hace nada. Llamar siempre antes
     *  de notificar (o al abrir la app), nunca hace falta comprobar si "ya existe" a mano. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Avisos de PokeGotchi", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Evolucion lista, cuidado urgente y huevos" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppPI(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (!hasPermission(context)) return
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppPI(context, id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notif)
        } catch (_: SecurityException) {
            // Permiso revocado justo entre el check y el notify: se ignora sin más.
        }
    }

    fun notifyEvolutionReady(context: Context, name: String) =
        notify(context, NOTIF_ID_EVOLUTION, "¡$name puede evolucionar!", "Toca para verlo en tu Pokédex.")

    fun notifyUrgentCare(context: Context, name: String) =
        notify(context, NOTIF_ID_URGENT, "$name necesita atención", "Alguna de sus barras está muy baja.")

    /** Retira la notificacion (si estaba mostrandose) al dejar de cumplirse la condicion, para
     *  que no se quede colgada en la barra despues de resuelta. */
    fun cancelEvolutionReady(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_ID_EVOLUTION)
    fun cancelUrgentCare(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_ID_URGENT)

    fun notifyEggLaid(context: Context, name: String) =
        notify(context, NOTIF_ID_EGG_LAID, "¡$name ha puesto un huevo!", "Toca para verlo en la app.")

    fun notifyEggHatched(context: Context) =
        notify(context, NOTIF_ID_EGG_HATCHED, "¡El huevo ha eclosionado!", "Abre la app para descubrir qué te ha salido.")

    /** A diferencia de setAutoCancel (que solo retira el aviso si el jugador TOCA esa
     *  notificacion concreta), esto la retira aunque abra la app por otro lado - hace falta
     *  llamarlo a mano en cuanto el huevo deja de estar en ese estado (eclosiona, se suelta o se
     *  queda), para que no se quede colgada en la barra despues de resuelta. */
    fun cancelEggLaid(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_ID_EGG_LAID)
    fun cancelEggHatched(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_ID_EGG_HATCHED)

    fun notifyOfferReady(context: Context) =
        notify(context, NOTIF_ID_OFFER_READY, "¡Regalo disponible!", "Hay un Pokémon nuevo esperando en tu Pokédex.")

    /** Igual que cancelEggLaid/cancelEggHatched: hace falta llamarlo a mano al resolver o
     *  descartar el regalo, para que no se quede colgada en la barra despues de abierto. */
    fun cancelOfferReady(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_ID_OFFER_READY)
}
