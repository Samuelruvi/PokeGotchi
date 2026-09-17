package com.example.pokegotchi

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
