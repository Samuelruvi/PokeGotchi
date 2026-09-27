package com.example.pokegotchi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Decodifica los sprites cenitales (assets/dungeonchars, assets/dungeonchars_shiny) del segundo
 * widget de mazmorra - formato totalmente distinto al de SpriteRepository (una hoja de 256x256
 * por especie, rejilla 4x4: filas = direccion, columnas = fotograma del ciclo de paso, en vez de
 * una tira horizontal de una sola animacion). Se apoya en SpriteRepository.scaledFrame para el
 * escalado final (pixel-art, mismo criterio "none"/"partial"/"full" que el resto de la app), pero
 * NO reutiliza su cache en disco ni su logica de presupuesto de memoria (effectiveScale, privada
 * y pensada para tiras de hasta 56 fotogramas descargadas de red) - aqui son solo 4 fotogramas de
 * un asset local, mucho mas barato de re-decodificar cada vez; basta una cache en memoria.
 *
 * Filas confirmadas viendo el PNG real (Pikachu/Caterpie): 0=abajo/de frente, 1=perfil izquierda,
 * 2=perfil derecha, 3=arriba/de espaldas (convencion clasica de charset).
 *
 * Cobertura verificada a fondo (pedido explicito del usuario: "mira si estan todos, si no no
 * sirve") cruzando cada una de las 1083 entradas de assets/pokedex.json contra los ficheros
 * reales de Followers/ - 1083/1083 (el 100%) tienen sprite. 3 nombres no encajaban con la
 * normalizacion generica pese a existir de verdad, con un nombre de fichero irregular - ver
 * NAME_ALIASES.
 */
object DungeonSpriteRepository {
    // Escala ADAPTATIVA segun el tamaño real de la celda (ver mas abajo) - antes CELL=64 fijo,
    // asumiendo que TODA hoja media 256x256. Bug real reportado por el usuario ("la animacion de
    // algunos Pokemon esta rota, se ve fea"): verificado que 69 hojas de Followers/ NO miden
    // 256x256 (legendarios como Arceus/Dialga/Giratina/Groudon/Kyogre/Ho-Oh/Eternatus a 512x512,
    // otras a 320x320/280x280/384x384, e incluso una asimetrica 256x384) - con CELL=64 fijo, esas
    // hojas se recortaban mal (solo se leia una esquina de la rejilla real). Ahora se deriva del
    // propio bitmap decodificado, celda = tamaño_hoja/4 (todas las vistas son multiplo exacto de
    // 4), ancho y alto por separado (no asumir celda cuadrada, por el caso 256x384).

    enum class Direction(val row: Int) { DOWN(0), LEFT(1), RIGHT(2), UP(3) }

    // Los 3 casos confirmados a mano (ver cabecera): la normalizacion generica (quitar todo lo
    // que no sea letra/numero) no los encuentra porque el fichero real usa una abreviatura de
    // genero/forma irregular, no el propio nombre de especie.
    private val NAME_ALIASES = mapOf(
        "nidoranf" to "NIDORANfE",
        "nidoranm" to "NIDORANmA",
        "mrmimegalar" to "MRMIME_1",
    )

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    @Volatile private var fileIndex: Map<String, String>? = null

    /** normalizado -> nombre real de fichero (sin extension) - construido una sola vez a partir
     *  de assets/dungeonchars (Followers y Followers shiny comparten exactamente los mismos
     *  nombres de fichero, solo cambia la carpeta contenedora, verificado). */
    private fun index(context: Context): Map<String, String> {
        fileIndex?.let { return it }
        val names = context.assets.list("dungeonchars") ?: emptyArray()
        val map = HashMap<String, String>(names.size)
        for (f in names) {
            if (!f.endsWith(".png")) continue
            val base = f.removeSuffix(".png")
            map.putIfAbsent(normalize(base), base)
        }
        fileIndex = map
        return map
    }

    private fun resolveFileName(context: Context, species: String): String? {
        val norm = normalize(species)
        NAME_ALIASES[norm]?.let { return it }
        val idx = index(context)
        idx[norm]?.let { return it }
        // Reserva: probar el nombre BASE sin sufijo de forma ("-galar", "-alola"...) - cubre
        // formas regionales/especiales que comparten dibujo con la especie base en este pack.
        val baseSpecies = species.substringBefore('-')
        return idx[normalize(baseSpecies)]
    }

    private val memCache = LruCache<String, List<Bitmap>>(8)

    /** Los 4 fotogramas del ciclo de paso de [species] (especie+shiny) mirando hacia [dir], o
     *  null si de verdad no hay sprite para ella (no deberia pasar nunca en la practica -
     *  cobertura verificada 1083/1083, ver cabecera - pero un widget no puede permitirse un
     *  crash si algun dia falta uno). */
    fun frames(context: Context, species: String, shiny: Boolean, dir: DungeonSpriteRepository.Direction): List<Bitmap>? {
        val fileName = resolveFileName(context, species) ?: return null
        val scaleMode = PetState.spriteScaleMode(context)
        val cacheKey = "$fileName#$shiny#${dir.row}#$scaleMode"
        memCache.get(cacheKey)?.let { return it }
        val folder = if (shiny) "dungeonchars_shiny" else "dungeonchars"
        val sheet = try {
            context.assets.open("$folder/$fileName.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null } ?: return null
        // Celda real = tamaño de la hoja / 4 (rejilla 4x4 siempre, pero la hoja en si NO siempre
        // mide 256x256 - ver cabecera). Ancho y alto por separado, nunca asumir cuadrada.
        val cellW = sheet.width / 4; val cellH = sheet.height / 4
        if (cellW <= 0 || cellH <= 0) { sheet.recycle(); return null }
        // Escala INTEGER adaptada para que el resultado final ronde ~300-350px sea cual sea el
        // tamaño nativo de la celda (una hoja de 512x512, celda 128, con la escala fija de antes
        // habria salido a 640px - carisimo en memoria con varios enemigos grandes en pantalla a
        // la vez).
        val scale = when {
            cellW <= 64 -> 5
            cellW <= 80 -> 4
            cellW <= 100 -> 3
            else -> 2
        }
        val frames = (0 until 4).map { col ->
            val cell = Bitmap.createBitmap(sheet, col * cellW, dir.row * cellH, cellW, cellH)
            val scaled = SpriteRepository.scaledFrame(cell, scaleMode, cellW, cellH, scale)
            if (scaled !== cell) cell.recycle()
            scaled
        }
        sheet.recycle()
        memCache.put(cacheKey, frames)
        return frames
    }

    private val itemCache = LruCache<String, Bitmap>(24)

    /** Icono estatico (assets/dungeonitems/[fileName].png, 48x48, sin recorte) de un caramelo/
     *  baya/objeto decorativo - para dibujarlo de verdad en la vista en vivo en vez de un punto
     *  amarillo generico (pedido explicito del usuario: "que se puedan poner los sprites"). */
    fun itemIcon(context: Context, fileName: String): Bitmap? {
        itemCache.get(fileName)?.let { return it }
        val bmp = try {
            context.assets.open("dungeonitems/$fileName.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null } ?: return null
        itemCache.put(fileName, bmp)
        return bmp
    }
}
