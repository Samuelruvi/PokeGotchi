package com.example.pokegotchi

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/**
 * Reproduce sonidos cortos con SoundPool. Los gritos se reproducen a un tono/velocidad
 * ligeramente aleatorio (parametro rate) para simular "varios gritos" a partir del real.
 * El SoundPool vive en el proceso; se recrea solo si el proceso fue eliminado.
 */
object SoundManager {

    private var pool: SoundPool? = null
    private val cryIds = mutableListOf<Int>()
    private val actionIds = mutableMapOf<String, Int>()   // feed/pet/wash -> soundId
    private var eggCrackId = 0
    private var shinyId = 0
    private var levelUpId = 0

    private fun ensure(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        cryIds.clear()
        cryIds.add(p.load(context, R.raw.cry_latest, 1))
        cryIds.add(p.load(context, R.raw.cry_legacy, 1))
        actionIds.clear()
        actionIds["feed"] = p.load(context, R.raw.sfx_eat, 1)
        actionIds["pet"] = p.load(context, R.raw.sfx_pet, 1)
        actionIds["wash"] = p.load(context, R.raw.sfx_wash, 1)
        eggCrackId = p.load(context, R.raw.sfx_egg_crack, 1)
        shinyId = p.load(context, R.raw.sfx_shiny, 1)
        levelUpId = p.load(context, R.raw.sfx_level_up, 1)
        pool = p
    }

    /** Sonido del huevo: se reutiliza EL MISMO tanto para el crujido (al empezar a incubar/al
     *  eclosionar) como para cada palpito de movimiento - a proposito, para no complicar con
     *  varios sonidos distintos para el mismo "personaje". */
    fun playEggCrack(context: Context) {
        ensure(context)
        val p = pool ?: return
        if (eggCrackId != 0) p.play(eggCrackId, 0.8f, 0.8f, 1, 0, 1f)
    }

    /** Chiste sonoro al revelar un shiny (huevo) - distinto de cualquier otro sonido del juego,
     *  para que sea imposible pasarlo por alto aunque el sprite casi no cambie de color. */
    fun playShiny(context: Context) {
        ensure(context)
        val p = pool ?: return
        if (shinyId != 0) p.play(shinyId, 1f, 1f, 1, 0, 1f)
    }

    /** Sonido de la accion. loud=true lo reproduce por partida doble para subir el volumen. */
    fun playAction(context: Context, kind: String, loud: Boolean = false) {
        ensure(context)
        val p = pool ?: return
        val id = actionIds[kind] ?: return
        p.play(id, 1f, 1f, 1, 0, 1f)
        if (loud) p.play(id, 1f, 1f, 1, 0, 1f)  // segunda voz simultanea => ~+6 dB
    }

    private val petStreams = intArrayOf(0, 0)
    /**
     * Swish de la caricia: corta el swish anterior antes de lanzar el nuevo (retrigger),
     * asi cada pasada suena limpia sin solaparse. loud => doble voz simultanea (~+6 dB).
     */
    fun playPet(context: Context, loud: Boolean) {
        ensure(context)
        val p = pool ?: return
        val id = actionIds["pet"] ?: return
        for (i in petStreams.indices) {
            if (petStreams[i] != 0) p.stop(petStreams[i])
            petStreams[i] = 0
        }
        petStreams[0] = p.play(id, 1f, 1f, 1, 0, 1f)
        if (loud) petStreams[1] = p.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** Precarga los sonidos (llamar al crear/actualizar el widget). */
    fun preload(context: Context) = ensure(context)

    /** Grito al azar con ligera variacion de tono, para darle vida. */
    fun playCry(context: Context) {
        ensure(context)
        val p = pool ?: return
        val id = cryIds.random()
        val rate = 0.9f + Random.nextFloat() * 0.3f   // 0.9 .. 1.2
        p.play(id, 1f, 1f, 1, 0, rate)
    }

    /** El Pokemon activo acaba de subir de nivel de verdad (solo al aceptar una accion de
     *  comer/acariciar/lavar desde el widget - pedido explicito del usuario: nunca en segundo
     *  plano sin que hayas tocado nada, ver PetState.tryApplyAction/ActionResult.leveledUp). */
    fun playLevelUp(context: Context) {
        ensure(context)
        val p = pool ?: return
        if (levelUpId != 0) p.play(levelUpId, 1f, 1f, 1, 0, 1f)
    }

    /** Sonido de "rechazo": se pulsa una accion que el Pokemon NO necesita ahora mismo.
     *  Reutiliza el grito pero mas grave y flojo, para que se note distinto de uno normal
     *  y no parezca que el boton esta roto (es el Pokemon diciendo "no hace falta"). */
    fun playReject(context: Context) {
        ensure(context)
        val p = pool ?: return
        val id = cryIds.random()
        p.play(id, 0.5f, 0.5f, 1, 0, 0.6f)
    }

}
