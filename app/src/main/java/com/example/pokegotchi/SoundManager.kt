package com.example.pokegotchi

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.util.concurrent.Executors
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

    // --- Grito PROPIO de cada Pokemon (pedido del usuario: "los sonidos de los pokemon no cambian,
    // son todos iguales"). cryIds (cry_latest/cry_legacy) son los gritos de PIKACHU, de cuando el
    // juego solo tenia a Pikachu: se quedan como ultimo recurso si no hay archivo de la especie. ---
    // Clave = "<sprite>|<especie>" (la forma Mega/Gigantamax activa tiene su propio grito) ->
    // soundId de SoundPool (cargando o ya listo). Solo se guardan los ultimos [MAX_SPECIES_CRIES]
    // (cada grito decodificado ocupa memoria) - al pasar de ahi se descarga el mas antiguo.
    private val speciesSounds = LinkedHashMap<String, Int>()
    private val readySounds = HashSet<Int>()
    private val failedSounds = HashSet<Int>()
    private val waiting = HashMap<Int, MutableList<(Boolean) -> Unit>>()
    private val lock = Any()
    private const val MAX_SPECIES_CRIES = 4
    // Un solo hilo: resolver el archivo (copiar el asset la primera vez, o descargarlo si falta) y
    // pedir la carga a SoundPool no puede hacerse en el hilo principal.
    private val loader = Executors.newSingleThreadExecutor()

    private fun ensure(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        p.setOnLoadCompleteListener { _, sampleId, status ->
            val callbacks = synchronized(lock) {
                if (status == 0) readySounds.add(sampleId) else failedSounds.add(sampleId)
                waiting.remove(sampleId)
            }
            callbacks?.forEach { it(status == 0) }
        }
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

    /** Precarga los sonidos (llamar al crear/actualizar el widget) - incluido el grito del
     *  Pokemon ACTIVO, para que ya este listo cuando toque sonar (si no, la primera vez tras
     *  arrancar el proceso llegaria con retraso: copiar el archivo + decodificarlo). */
    fun preload(context: Context) {
        ensure(context)
        withActiveCry(context) { }
    }

    /** Resuelve el grito del Pokemon activo y llama a [onResolved] con su soundId de SoundPool, o
     *  con null si no se pudo (sin archivo de esa especie, error de carga) - entonces quien llama
     *  usa el grito generico. Se puede llamar desde cualquier hilo; nunca bloquea al que llama. */
    private fun withActiveCry(context: Context, onResolved: (Int?) -> Unit) {
        val app = context.applicationContext
        val species = PetState.currentPokemon(app)
        val spriteName = PetState.displaySpriteName(app, species)   // la Mega/Gigantamax activa tiene su grito
        val dexId = PetState.currentPokemonId(app)
        val key = "$spriteName|$species"
        val cached = synchronized(lock) { speciesSounds[key] }
        if (cached != null) { whenReady(cached, onResolved); return }
        loader.execute {
            val p = pool
            // Local primero (forma activa, luego la especie), y solo si falta de verdad, la copia
            // descargada de PokeAPI (CryRepository.ensure) - mismo orden que ya usa la evolucion.
            val file = try {
                CryRepository.local(app, spriteName) ?: CryRepository.local(app, species) ?: CryRepository.ensure(app, species, dexId)
            } catch (e: Exception) { null }
            if (p == null || file == null) {
                DebugLog.log(app, "grito: sin archivo para $species ($spriteName) - uso el generico")
                onResolved(null)
                return@execute
            }
            val sid = synchronized(lock) {
                speciesSounds[key] ?: p.load(file.path, 1).also {
                    speciesSounds[key] = it
                    while (speciesSounds.size > MAX_SPECIES_CRIES) {
                        val oldest = speciesSounds.entries.first()
                        speciesSounds.remove(oldest.key)
                        readySounds.remove(oldest.value)
                        failedSounds.remove(oldest.value)
                        p.unload(oldest.value)
                    }
                }
            }
            whenReady(sid, onResolved)
        }
    }

    private fun whenReady(soundId: Int, cb: (Int?) -> Unit) {
        var state = 0   // 1 = lista, -1 = fallo, 0 = aun cargando (avisara el listener de SoundPool)
        synchronized(lock) {
            state = when {
                soundId in readySounds -> 1
                soundId in failedSounds -> -1
                else -> {
                    waiting.getOrPut(soundId) { mutableListOf() }.add { ok -> cb(if (ok) soundId else null) }
                    0
                }
            }
        }
        if (state == 1) cb(soundId) else if (state == -1) cb(null)
    }

    /** Grito del Pokemon ACTIVO (el de su especie/forma, ver withActiveCry) con ligera variacion de
     *  tono, para darle vida. Si no hay archivo de esa especie, el generico de siempre. */
    fun playCry(context: Context) {
        ensure(context)
        val p = pool ?: return
        val rate = 0.9f + Random.nextFloat() * 0.3f   // 0.9 .. 1.2
        withActiveCry(context) { sid -> p.play(sid ?: cryIds.random(), 1f, 1f, 1, 0, rate) }
    }

    /** SOLO PARA PRUEBAS (ver PokeWidgetProvider.ACTION_DEBUG_CRY_CHECK): para cada especie de
     *  [species], que archivo de grito se usaria, su tamaño/huella y si SoundPool lo carga bien.
     *  No cambia el Pokemon activo. Bloquea hasta ~2 s por especie: llamar en un hilo de fondo. */
    fun debugCryReport(context: Context, species: List<String>): String {
        ensure(context)
        val p = pool ?: return "sin SoundPool"
        val sb = StringBuilder()
        for (name in species) {
            val f = CryRepository.local(context, name)
            if (f == null) {
                sb.append("$name: SIN archivo local").append(System.lineSeparator())
                continue
            }
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(f.readBytes())
                .joinToString("") { "%02x".format(it) }.take(8)
            val latch = java.util.concurrent.CountDownLatch(1)
            var ok = false
            val sid = synchronized(lock) {
                p.load(f.path, 1).also { id ->
                    waiting.getOrPut(id) { mutableListOf() }.add { r -> ok = r; latch.countDown() }
                }
            }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            p.unload(sid)
            sb.append("$name: ${f.name} ${f.length()} B md5=$md5 carga=${if (ok) "OK" else "FALLO"}").append(System.lineSeparator())
        }
        return sb.toString().trimEnd()
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
     *  Reutiliza el grito (el de su especie, ver withActiveCry) pero mas grave y flojo, para que
     *  se note distinto de uno normal y no parezca que el boton esta roto (es el Pokemon diciendo
     *  "no hace falta"). */
    fun playReject(context: Context) {
        ensure(context)
        val p = pool ?: return
        withActiveCry(context) { sid -> p.play(sid ?: cryIds.random(), 0.5f, 0.5f, 1, 0, 0.6f) }
    }

}
