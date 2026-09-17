package com.example.pokegotchi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import org.w3c.dom.Element
import pl.droidsonroids.gif.GifDrawable
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Descarga y cachea los sprites de cada Pokemon EN EL DISPOSITIVO (runtime).
 * Estilos: "gen5" (GIF animado de Showdown), "pmd" (Mundo Misterioso, hoja + AnimData.xml).
 * styleKey = style + "s" si es shiny (ej. "gen5", "gen5s", "pmd", "pmds").
 * Cache: filesDir/sprites/<styleKey>/<name>/f_000.png + meta.txt (frames,cx,interval,w,h,cy).
 * Llamar SIEMPRE en un hilo de fondo.
 */
object SpriteRepository {

    private const val SCALE = 4
    // Tope de fotogramas por animacion. NO es el limite real (ese es SPRITE_ANIM_BUDGET_BYTES, que
    // ademas ya baja la escala sola si hace falta): es un tope fijo que se aplica ANTES de calcular
    // la escala, asi que con 40 la mayoria de sprites se quedaba usando solo 5-7 MB de los 10
    // disponibles. Medido sobre el paquete local: el 80% de los sprites trae mas de 40 fotogramas
    // (mediana 87), asi que con 40 se descartaban 3 de cada 4 y los movimientos rapidos salian a
    // tirones (reportado por el usuario con el latigazo de cola de Grovyle, que es animacion real
    // del sprite, no un fallo). A 56 caben mas fotogramas SIN bajar la escala de ninguno (Grovyle
    // sigue a 3x, Pikachu a 4x), asi que solo puede verse mas fluido, nunca peor.
    private const val MAX_FRAMES = 56
    // Tope de seguridad de memoria (crash real reproducido: Raichu de Alola, con un lienzo nativo
    // bastante mas grande que el habitual - 105x96 frente a los 74x73 de Raichu normal - x4 de
    // SCALE x 36 fotogramas superaba ENTERO el limite de memoria de una actualizacion de
    // RemoteViews, 23224320 de 19851840 maximo, dejando la app en bucle de crash). El SCALE=4 de
    // siempre asume un lienzo "normal" (~70x70) - si el lienzo nativo de una especie es mas
    // grande, se reduce el SCALE efectivo (nunca por debajo de 1) para que el total quepa
    // siempre, sea cual sea el tamaño real del sprite en Showdown.
    private const val SPRITE_ANIM_BUDGET_BYTES = 10_000_000L
    private fun effectiveScale(w0: Int, h0: Int, frameCount: Int): Int {
        for (s in SCALE downTo 1) {
            val bytes = w0.toLong() * s * h0 * s * 4 * frameCount.coerceAtLeast(1)
            if (bytes <= SPRITE_ANIM_BUDGET_BYTES) return s
        }
        return 1
    }
    // Sube este numero cuando cambie el procesado de sprites: invalida cachés viejas
    // (v2: recorte + normalizado PMD; v3: altura objetivo PMD ~ tamaño Gen5; v4: sombra bajo
    // los pies, horneada en cada frame; v5: sombra suavizada -media movil circular- y sin
    // recorte por el borde inferior del lienzo - ver finalize()).
    // v7: sprites locales (assets/localsprites) en vez de descargarlos siempre de Showdown - hace
    // falta forzar la regeneracion de todo lo ya cacheado para que use la fuente nueva.
    // v8: fix del suavizado (Scale2x) saltandose en silencio cuando effectiveScale bajaba de 4
    // por seguridad de memoria - ver scaledFrame().
    // Subido a 10: vuelta atras del intento de Scale2x x2 (ver scaledFrame) - el usuario lo probo
    // y confirmo que empeoraba detalles finos de una linea (la boca de Snivy). Hace falta subir
    // la version otra vez para que la cache de la version 9 (ya con el intento fallido) se
    // regenere de vuelta al comportamiento de siempre.
    // Subido a 11: MAX_FRAMES 40 -> 56. Sin esto, lo ya cacheado se quedaria con sus 40 fotogramas
    // de siempre (isCurrent compara la version) y el cambio solo se notaria en sprites nuevos.
    // Subido a 12: varios ficheros de assets/localsprites se han reemplazado hoy (gardevoir,
    // cofagrigus, charizard-gmax, 8 shiny de Alcremie, y los sprites de las especies nuevas -
    // meowth-alola/persian-alola/ursaluna-bloodmoon/pikachu-gmax/eevee-gmax/toxtricity-gmax,
    // sustituidos una segunda vez por los del pack del usuario) SIN subir esta version cada vez -
    // el cacheo de SpriteRepository invalida por CACHE_VERSION, nunca por si el PNG de origen ha
    // cambiado de contenido, asi que un movil que ya hubiera visto/cacheado la version vieja de
    // alguno de estos en un estilo/nitidez concreto se quedaba con fotogramas viejos ahi, mientras
    // que otro estilo/nitidez sin cachear aun generaba ya los nuevos - bug real reportado por el
    // usuario (Persian de Alola mostraba un dibujo con suavizado "completo" y OTRO distinto con
    // "normal"). Subir esto fuerza a regenerar TODO desde los ficheros actuales, sea cual sea el
    // estilo/nitidez que cada movil tuviera cacheado.
    // Subido a 13: alcremie-gmax reemplazado otra vez (el de Showdown tenia ruido/dithering
    // visible frente al del paquete pokerogue-assets-beta, estatico pero limpio de verdad).
    private const val CACHE_VERSION = 13
    // Sombra ovalada bajo los pies, como en los juegos originales. Se calcula POR FRAME (no un
    // valor fijo para todo el Pokemon) a partir del propio bounding box opaco de ESE frame, y se
    // hornea directamente en el PNG cacheado - asi seguirla en la animacion sale gratis (es parte
    // del mismo frame que ya reproduce el ViewFlipper solo) sin tener que tocar ninguna vista
    // hermana mientras el flipper esta visible y animando (lo que causaba el bug de reinicio del
    // sprite, ver notas de la ronda 27 del huevo).
    private const val SHADOW_ALPHA = 70          // ~27% opacidad
    private const val SHADOW_WIDTH_FACTOR = 0.62f  // ancho de la sombra = este % del ancho del cuerpo en ese frame
    private const val SHADOW_HEIGHT_FACTOR = 0.34f // alto de la sombra = este % de su propio ancho (ovalo achatado)
    private const val PMD_TARGET_H = 50f   // altura logica del sprite PMD tras recortar (~Gen5)

    data class SpriteMeta(
        val name: String, val frameCount: Int, val cx: Int, val intervalMs: Int,
        val w: Int = 0, val h: Int = 0, val cy: Int = 0, val ver: Int = 0
    )

    fun styleKey(style: String, shiny: Boolean) = style + if (shiny) "s" else ""

    // Showdown quita SIEMPRE los guiones de nombres compuestos normales (mr-mime -> mrmime,
    // tapu-koko -> tapukoko, nidoran-f -> nidoranf - comprobado contra el CDN real), PERO los
    // conserva cuando van antes de un sufijo de FORMA/variante regional (raichu-alola,
    // meowth-galar, charizard-megax - tambien comprobado; "raichualola" da 404 real). Sin
    // distinguir esto, las formas regionales/mega nunca encontraban su sprite.
    private val FORM_SUFFIXES = setOf(
        "alola", "galar", "hisui", "paldea", "mega", "megax", "megay", "gmax", "totem", "primal"
    )
    fun toShowdownSlug(name: String): String {
        val lower = name.lowercase()
        val parts = lower.split("-")
        // Diferencia de genero (PetState.genderSpriteKey, sufijo "-female" nuestro) -> Showdown
        // usa "-f" con guion conservado (comprobado contra el CDN real: gloom-f, zubat-f...).
        // Ojo, esto NO es lo mismo que "nidoran-f" (esa es una especie propia, aparte, con el
        // guion SI eliminado por Showdown - ver comentario de FORM_SUFFIXES) - aqui solo se
        // dispara con nuestro sufijo largo "-female", nunca con un "-f"/"-m" que ya venga corto.
        if (parts.size > 1 && parts.last() == "female") {
            return parts.dropLast(1).joinToString("").replace(Regex("[^a-z0-9]"), "") + "-f"
        }
        // Unown (28 formas: A-Z, ! y ?) - Showdown conserva el guion para todas (unown-b,
        // unown-em...), comprobado contra el CDN real. Caso especial APARTE de FORM_SUFFIXES:
        // una letra suelta como sufijo ahi chocaria con especies reales que ya acaban en guion+
        // letra (porygon-z, jangmo-o...), que Showdown SI junta sin guion.
        if (parts.size == 2 && parts[0] == "unown") {
            return "unown-${parts[1]}"
        }
        return if (parts.size > 1 && parts.last() in FORM_SUFFIXES) {
            parts.dropLast(1).joinToString("").replace(Regex("[^a-z0-9]"), "") + "-" + parts.last()
        } else {
            lower.replace(Regex("[^a-z0-9]"), "")
        }
    }

    private fun dir(context: Context, styleKey: String, name: String) =
        File(context.filesDir, "sprites/$styleKey/$name")

    fun readMeta(context: Context, styleKey: String, name: String): SpriteMeta? {
        val f = File(dir(context, styleKey, name.lowercase()), "meta.txt")
        if (!f.exists()) return null
        return try {
            val p = f.readText().trim().split(",")
            SpriteMeta(
                name, p[0].toInt(), p[1].toInt(), p[2].toInt(),
                p.getOrNull(3)?.toInt() ?: 0, p.getOrNull(4)?.toInt() ?: 0, p.getOrNull(5)?.toInt() ?: 0,
                p.getOrNull(6)?.toInt() ?: 0
            )
        } catch (e: Exception) { null }
    }

    fun frameFile(context: Context, styleKey: String, name: String, i: Int): File =
        File(dir(context, styleKey, name.lowercase()), "f_%03d.png".format(i))

    /** ¿El cache existe y es de la version actual del procesado? */
    fun isCurrent(meta: SpriteMeta?): Boolean =
        meta != null && meta.w > 0 && meta.cy > 0 && meta.ver == CACHE_VERSION

    /** Punto de entrada: asegura el sprite del Pokemon en el estilo/shiny/escalado pedidos.
     *  [scaleMode]: "none" (vecino cercano de siempre), "partial" o "full" (Scale2x, ver
     *  scaledFrame) - forma parte de la clave de cache (PetState.currentStyleKey ya la incluye),
     *  asi que cambiar de modo no borra el cache del otro, solo usa una carpeta distinta. */
    fun ensure(context: Context, name: String, id: Int, style: String, shiny: Boolean, scaleMode: String = "none"): SpriteMeta? {
        val key = name.lowercase()
        val sk = styleKey(style, shiny) + "_" + scaleMode
        readMeta(context, sk, key)?.let { if (it.w > 0 && it.cy > 0 && it.ver == CACHE_VERSION) return it }
        val framesAndInterval = when (style) {
            "pmd" -> pmdFrames(id)
            // Sprite LOCAL primero (bundle en assets/localsprites, sin red) - pedido explicito
            // del usuario para no depender de que Showdown siga funcionando dentro de años. Solo
            // cae a la red (gen5Frames) para la especie/variante que falte en el paquete local
            // (verificado contra base_stats.json: ahora mismo solo urshifu-rapid normal falta -
            // su shiny y su Gigantamax si estan).
            else -> localFrames(context, key, shiny) ?: gen5Frames(key, shiny)
        } ?: run { Log.w("PokeGotchi", "sin sprite $style para $key"); return null }
        return finalize(context, sk, key, framesAndInterval.first, framesAndInterval.second, scaleMode)
    }

    // Compat: Gen5 no-shiny directo (usado por onUpdate antes de tener el id).
    fun ensureGen5(context: Context, name: String): SpriteMeta? =
        ensure(context, name, 0, "gen5", false)

    // Fotogramas de idle "respirando" ya recortados por el propio usuario (carpeta assets/
    // localsprites/normal|shiny/<especie>.png), como tira horizontal - CADA fotograma es
    // CUADRADO (ancho de cada uno = alto de la imagen entera), comprobado sin ninguna excepcion
    // en una muestra amplia de ambas carpetas antes de dar esto por bueno. Interval fijo (no hay
    // duracion por fotograma real como en un GIF, es solo una imagen) - 100ms de toda la vida
    // para el resto de animaciones de la app, encaja bien aqui tambien.
    private const val LOCAL_SPRITE_INTERVAL_MS = 100
    private fun localFrames(context: Context, key: String, shiny: Boolean): Pair<List<Bitmap>, Int>? {
        val folder = if (shiny) "shiny" else "normal"
        val path = "localsprites/$folder/$key.png"
        val bytes = try {
            context.assets.open(path).use { it.readBytes() }
        } catch (e: Exception) {
            return null
        }
        val strip = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val h = strip.height
        if (h <= 0 || strip.width % h != 0) { strip.recycle(); return null }
        val total = strip.width / h
        if (total <= 0) { strip.recycle(); return null }
        val step = maxOf(1, (total + MAX_FRAMES - 1) / MAX_FRAMES)
        val list = ArrayList<Bitmap>()
        var idx = 0
        while (idx < total) {
            list.add(Bitmap.createBitmap(strip, idx * h, 0, h, h))
            idx += step
        }
        // Bitmap.createBitmap(source, x, y, w, h) devuelve el MISMO objeto source (sin copiar)
        // cuando el recorte pedido es el lienzo entero (x=0,y=0,w=source.width,h=source.height) -
        // solo puede pasar aqui con total==1 (un unico sprite estatico cuadrado, ej. los Gigamax
        // de Urshifu). Reciclar strip a ciegas entonces reciclaba tambien ese unico fotograma ya
        // devuelto en list, y quien llamaba (loadAnimatedSprite) crasheaba al usarlo
        // ("getPixels() on a recycled bitmap") - nunca se noto antes porque todos los sprites
        // locales anteriores tenian varios fotogramas.
        if (list.none { it === strip }) strip.recycle()
        return list to LOCAL_SPRITE_INTERVAL_MS
    }

    /** Primer fotograma (cuadrado) del sprite LOCAL de [name], para las miniaturas estaticas
     *  (Pokedex, ficha, dialogos de oferta/evolucion) - no pasa por el escalado/sombra del
     *  widget, es solo una vista previa ligera. null si esa especie no esta en el paquete local
     *  (quien llama cae entonces a la miniatura de red, mismo criterio que el sprite animado).
     *  Segura de llamar desde un hilo de fondo (no toca vistas). */
    fun localFirstFrame(context: Context, name: String, shiny: Boolean = false): Bitmap? {
        val folder = if (shiny) "shiny" else "normal"
        val path = "localsprites/$folder/${name.lowercase()}.png"
        return try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            val strip = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val h = strip.height
            if (h <= 0 || strip.width < h) { strip.recycle(); return null }
            val frame = Bitmap.createBitmap(strip, 0, 0, h, h)
            // Mismo caso que en localFrames: si el sprite es un unico fotograma cuadrado
            // (strip.width == h, ej. los Gigamax de Urshifu), createBitmap devuelve el MISMO
            // objeto strip sin copiar - reciclarlo a ciegas invalidaba tambien "frame".
            if (frame !== strip) strip.recycle()
            frame
        } catch (e: Exception) {
            null
        }
    }

    /** Igual que [localFirstFrame], pero ya escalada segun el modo de suavizado elegido por el
     *  usuario (mismo algoritmo Scale2x que el sprite animado - ver scaledFrame) - antes estas
     *  miniaturas (Pokedex, Mis Pokemon, dialogos de oferta/evolucion) se dejaban a resolucion
     *  nativa y el propio ImageView las desenfocaba con su filtro bilineal por defecto al
     *  estirarlas, sin respetar "ninguno/parcial/total" en absoluto. Quien llama debe ademas
     *  desactivar el filtro del ImageView (ver isFilterBitmap) para que no se vuelva a desenfocar
     *  por encima de este resultado. Segura de llamar desde un hilo de fondo (no toca vistas). */
    fun localFirstFrameScaled(context: Context, name: String, shiny: Boolean, scaleMode: String): Bitmap? {
        val frame = localFirstFrame(context, name, shiny) ?: return null
        val scale = effectiveScale(frame.width, frame.height, 1)
        val out = scaledFrame(frame, scaleMode, frame.width, frame.height, scale)
        frame.recycle()
        return out
    }

    /** Todos los fotogramas ANIMADOS del sprite LOCAL de [name] (assets/localsprites), ya
     *  escalados segun [scaleMode] igual que [localFirstFrameScaled] - null si esa especie no
     *  esta en el paquete local (hoy en dia, solo urshifu-rapid normal), quien llama cae entonces al
     *  GIF de red como antes. Pedido explicito del usuario: el dialogo de oferta/evolucion no
     *  debia depender de la red para animarse, ya que el resto del juego (widget, miniaturas) ya
     *  es local-first - se detecto al desactivar WiFi/datos de la app para probar el modo
     *  offline, con el sprite animado cayendo silenciosamente a estatico. Segura de llamar desde
     *  un hilo de fondo (no toca vistas). */
    fun localAnimatedFramesScaled(context: Context, name: String, shiny: Boolean, scaleMode: String): Pair<List<Bitmap>, Int>? {
        val (frames, interval) = localFrames(context, name.lowercase(), shiny) ?: return null
        val scaled = frames.map { f ->
            val out = scaledFrame(f, scaleMode, f.width, f.height)
            f.recycle()
            out
        }
        return scaled to interval
    }

    // ---------------- extractores (devuelven frames originales + intervalMs) ----------------
    private fun gen5Frames(key: String, shiny: Boolean): Pair<List<Bitmap>, Int>? {
        val variant = if (shiny) "gen5ani-shiny" else "gen5ani"
        val bytes = download("https://play.pokemonshowdown.com/sprites/$variant/${toShowdownSlug(key)}.gif")
            ?: return null
        val gif = try { GifDrawable(bytes) } catch (e: Exception) { return null }
        val total = gif.numberOfFrames
        if (total <= 0) { gif.recycle(); return null }
        val step = maxOf(1, (total + MAX_FRAMES - 1) / MAX_FRAMES)
        val list = ArrayList<Bitmap>()
        var idx = 0
        while (idx < total) {
            val f = gif.seekToFrameAndGet(idx)
            list.add(f.copy(Bitmap.Config.ARGB_8888, false))
            f.recycle(); idx += step
        }
        val interval = (gif.duration / total).coerceIn(60, 300)
        gif.recycle()
        return list to interval
    }

    private fun pmdFrames(id: Int): Pair<List<Bitmap>, Int>? {
        if (id <= 0) return null
        val base = "https://raw.githubusercontent.com/PMDCollab/SpriteCollab/master/sprite/%04d".format(id)
        val xml = download("$base/AnimData.xml") ?: return null
        val sheetBytes = download("$base/Idle-Anim.png") ?: return null
        val idle = parseIdle(xml) ?: return null
        val (fw, fh, durs) = idle
        val sheet = BitmapFactory.decodeByteArray(sheetBytes, 0, sheetBytes.size) ?: return null
        val cols = sheet.width / fw
        val n = minOf(cols, durs.size).coerceAtLeast(1)
        val totalDur = durs.take(n).sum().coerceAtLeast(1)
        val dscale = minOf(1f, 20f / totalDur)   // limita el total de frames
        val list = ArrayList<Bitmap>()
        for (c in 0 until n) {
            val rep = max(1, (durs[c] * dscale).roundToInt())
            for (k in 0 until rep) list.add(Bitmap.createBitmap(sheet, c * fw, 0, fw, fh)) // fila 0 = frente
        }
        sheet.recycle()
        return normalizePmd(list) to 120
    }

    /**
     * Los frames PMD llevan mucho transparente alrededor y el personaje es diminuto, asi que
     * se ve pequeño/descentrado y los efectos (tamaño fijo) quedan enormes. Aqui se recorta al
     * contenido (bbox comun a todos los frames) y se escala a una altura tipo Gen5, para que el
     * sprite llene el widget, quede centrado y los efectos guarden la misma proporcion.
     */
    private fun normalizePmd(list: List<Bitmap>): List<Bitmap> {
        if (list.isEmpty()) return list
        val w = list[0].width; val h = list[0].height
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (bm in list) for (y in 0 until h) for (x in 0 until w)
            if ((bm.getPixel(x, y) ushr 24) > 16) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        if (maxX < minX) return list
        val bw = maxX - minX + 1; val bh = maxY - minY + 1
        val s = (PMD_TARGET_H / bh).coerceIn(1f, 6f)   // altura logica objetivo ~ tamaño Gen5
        val out = ArrayList<Bitmap>(list.size)
        for (bm in list) {
            val crop = Bitmap.createBitmap(bm, minX, minY, bw, bh)
            val nw = (bw * s).toInt().coerceAtLeast(1); val nh = (bh * s).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            Canvas(scaled).drawBitmap(crop, Matrix().apply { setScale(s, s) },
                Paint().apply { isFilterBitmap = false; isAntiAlias = false })
            crop.recycle(); bm.recycle()
            out.add(scaled)
        }
        return out
    }

    private fun parseIdle(xml: ByteArray): Triple<Int, Int, IntArray>? = try {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val anims = doc.getElementsByTagName("Anim")
        var result: Triple<Int, Int, IntArray>? = null
        for (i in 0 until anims.length) {
            val el = anims.item(i) as Element
            if (el.getElementsByTagName("Name").item(0)?.textContent?.trim() == "Idle") {
                val fw = el.getElementsByTagName("FrameWidth").item(0)?.textContent?.trim()?.toInt()
                val fh = el.getElementsByTagName("FrameHeight").item(0)?.textContent?.trim()?.toInt()
                if (fw != null && fh != null) {
                    val dn = el.getElementsByTagName("Duration")
                    val durs = IntArray(dn.length) { dn.item(it).textContent.trim().toInt() }
                    result = Triple(fw, fh, if (durs.isEmpty()) intArrayOf(1) else durs)
                }
                break
            }
        }
        result
    } catch (e: Exception) { Log.w("PokeGotchi", "parseIdle: $e"); null }

    // ---------------- escalado de pixel art (prueba pedida por el usuario) ----------------
    // Vecino cercano puro (lo de siempre, SCALE=4) deja los bordes en escalera tal cual. Scale2x
    // (AdvMAME2x, algoritmo publico) mira los 4 vecinos directos de cada pixel y "redondea" la
    // esquina cuando dos de ellos coinciden - sin difuminar como un filtro bilineal normal.
    // Aplicarlo DOS veces (hasta x4) se probo primero y quedaba demasiado suavizado, perdia el
    // aspecto pixel-art original - por eso solo se aplica UNA vez (x2) y el resto (hasta x4) se
    // rellena con vecino cercano normal, sin suavizar mas.
    internal fun scale2x(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        fun at(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, w - 1); val cy = y.coerceIn(0, h - 1)
            return srcPixels[cy * w + cx]
        }
        val ow = w * 2
        val out = IntArray(ow * h * 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = at(x, y); val a = at(x, y - 1); val b = at(x + 1, y); val c = at(x - 1, y); val dd = at(x, y + 1)
                val e0 = if (c == a && a != dd && c != b) a else p
                val e1 = if (a == b && a != c && b != dd) b else p
                val e2 = if (dd == c && dd != b && c != a) c else p
                val e3 = if (dd == b && dd != a && b != c) b else p
                out[(y * 2) * ow + x * 2] = e0
                out[(y * 2) * ow + x * 2 + 1] = e1
                out[(y * 2 + 1) * ow + x * 2] = e2
                out[(y * 2 + 1) * ow + x * 2 + 1] = e3
            }
        }
        val result = Bitmap.createBitmap(ow, h * 2, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, ow, 0, 0, ow, h * 2)
        return result
    }

    /** [frame] al tamaño final w0*scale x h0*scale, segun [scaleMode]:
     *  "none" = vecino cercano puro (igual que siempre).
     *  "full" = una pasada de Scale2x (x2) + vecino cercano para completar hasta scale.
     *  "partial" = mezcla al 50% entre las dos anteriores (punto intermedio pedido).
     *  [scale] normalmente es SCALE (4), pero puede ser menor cuando [effectiveScale] lo reduce
     *  por seguridad de memoria para un sprite con lienzo nativo grande (esto pasa con bastante
     *  mas frecuencia desde que los sprites vienen del paquete LOCAL, que suele traer bastantes
     *  mas fotogramas por especie que los GIF de Showdown de antes). Bug encontrado por el
     *  usuario: antes esto exigia scale == SCALE (4) EXACTO para suavizar, asi que en cuanto el
     *  presupuesto de memoria bajaba el scale a 2 o 3 el suavizado se saltaba en silencio aunque
     *  el modo siguiera en "partial"/"full". Ahora Scale2x (que dobla el lienzo nativo x2) se
     *  completa con un escalado nearest-neighbour por el factor que haga falta (scale/2, puede
     *  ser fraccionario) para cualquier scale >= 2 - solo se salta a scale == 1, donde no hay
     *  margen para doblar y luego encajar el resto. */
    internal fun scaledFrame(frame: Bitmap, scaleMode: String, w0: Int, h0: Int, scale: Int = SCALE): Bitmap {
        val targetW = w0 * scale; val targetH = h0 * scale
        val noFilter = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        fun nearestScaled(): Bitmap {
            val b = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            Canvas(b).drawBitmap(frame, Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) }, noFilter)
            return b
        }
        if (scaleMode == "none" || scale < 2) return nearestScaled()

        // INTENTO REVERTIDO (probado y rechazado por el usuario): aplicar Scale2x dos veces
        // seguidas en vez de una. La hipotesis (medida en % de pixeles distintos en TODO el
        // lienzo entre fotogramas consecutivos) decia que mejoraba - pero esa metrica global no
        // era sensible a lo que de verdad importaba: detalles finos de UNA sola linea (la boca de
        // Snivy), que pesan poquisimo en el total del lienzo pero se ven fatal de cerca. El
        // usuario confirmo con capturas reales que la segunda pasada deformaba la boca en vez de
        // estabilizarla - a una linea de 1 pixel de grosor, la regla de Scale2x (redondear una
        // esquina si 2 vecinos coinciden) no tiene margen para "acertar" siempre igual, y
        // aplicarla dos veces solo compone el error. Vuelto a UNA sola pasada de Scale2x + vecino
        // cercano para el resto (comportamiento de siempre) - ver CACHE_VERSION mas abajo para la
        // vuelta atras real en cache.
        val once = scale2x(frame)   // w0*2 x h0*2
        val smoothed = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Canvas(smoothed).drawBitmap(once, Matrix().apply { setScale(scale / 2f, scale / 2f) }, noFilter)
        once.recycle()
        if (scaleMode == "full") return smoothed

        // "partial": mezcla 50/50 canal a canal con la version sin suavizar.
        val plain = nearestScaled()
        val plainPixels = IntArray(targetW * targetH); plain.getPixels(plainPixels, 0, targetW, 0, 0, targetW, targetH)
        val smoothPixels = IntArray(targetW * targetH); smoothed.getPixels(smoothPixels, 0, targetW, 0, 0, targetW, targetH)
        plain.recycle(); smoothed.recycle()
        val blended = IntArray(targetW * targetH)
        for (i in blended.indices) {
            val p1 = plainPixels[i]; val p2 = smoothPixels[i]
            fun ch(shift: Int) = (((p1 ushr shift) and 0xFF) + ((p2 ushr shift) and 0xFF)) / 2
            blended[i] = (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        out.setPixels(blended, 0, targetW, 0, 0, targetW, targetH)
        return out
    }

    // ---------------- procesado comun (upscale + cx/cy + guardar) ----------------
    private fun finalize(
        context: Context, styleKey: String, key: String, frames: List<Bitmap>, interval: Int, scaleMode: String = "none"
    ): SpriteMeta? {
        if (frames.isEmpty()) return null
        val d = dir(context, styleKey, key).apply { mkdirs() }
        d.listFiles()?.forEach { it.delete() }
        var w0 = 0; var h0 = 0
        var colThick: IntArray? = null
        var sumY = 0L; var cnt = 0L
        val shadowPaint = Paint().apply { color = (SHADOW_ALPHA shl 24); isAntiAlias = true }

        // PRIMERA PASADA: bounding box opaco de CADA frame por separado (no acumulado con los
        // demas, a diferencia de colThick/cx/cy) - de ahi sale donde tocan los pies en cada
        // instante.
        val n = frames.size
        val hasContent = BooleanArray(n)
        val rawCx = FloatArray(n); val rawW = FloatArray(n); val rawFeetY = FloatArray(n)
        frames.forEachIndexed { i, frame ->
            w0 = frame.width; h0 = frame.height
            if (colThick == null || colThick!!.size != w0) colThick = IntArray(w0)
            var fMinX = w0; var fMaxX = -1; var fMaxY = -1
            for (x in 0 until w0) {
                var c = 0
                for (y in 0 until h0) if ((frame.getPixel(x, y) ushr 24) > 16) {
                    c++; sumY += y; cnt++
                    if (x < fMinX) fMinX = x; if (x > fMaxX) fMaxX = x; if (y > fMaxY) fMaxY = y
                }
                if (c > colThick!![x]) colThick!![x] = c
            }
            hasContent[i] = fMaxX >= fMinX
            if (hasContent[i]) {
                rawCx[i] = (fMinX + fMaxX + 1) / 2f
                rawW[i] = (fMaxX - fMinX + 1).toFloat()
                rawFeetY[i] = (fMaxY + 1).toFloat()
            }
        }
        // Suavizado circular (media movil, el bucle idle no tiene principio/fin): el usuario penso
        // que la sombra se movia demasiado siguiendo cada mini-rebote del idle frame a frame -
        // sigue el vaiven real (no un valor fijo), pero amortiguado en vez de saltar de golpe.
        // Ventana pequeña y proporcional al numero de frames (no aplana animaciones cortas de
        // pocos frames, suaviza mas las largas).
        val window = (n / 6).coerceIn(1, 4)
        fun smoothed(raw: FloatArray): FloatArray {
            val out = FloatArray(n)
            for (i in 0 until n) {
                var sum = 0f; var c2 = 0
                for (o in -window..window) {
                    val j = ((i + o) % n + n) % n
                    if (hasContent[j]) { sum += raw[j]; c2++ }
                }
                out[i] = if (c2 > 0) sum / c2 else raw[i]
            }
            return out
        }
        val smCx = smoothed(rawCx); val smW = smoothed(rawW); val smFeetY = smoothed(rawFeetY)
        val scale = effectiveScale(w0, h0, n)

        // SEGUNDA PASADA: dibuja la sombra (suavizada) y el sprite encima, y guarda el PNG.
        frames.forEachIndexed { i, frame ->
            val scaled = Bitmap.createBitmap(w0 * scale, h0 * scale, Bitmap.Config.ARGB_8888)
            val cv = Canvas(scaled)
            if (hasContent[i]) {
                val shW = smW[i] * scale * SHADOW_WIDTH_FACTOR
                val shH = shW * SHADOW_HEIGHT_FACTOR
                val shCx = smCx[i] * scale
                val feetY = smFeetY[i] * scale
                // El bitmap del frame NO tiene margen debajo del contenido opaco (los pies suelen
                // tocar el borde inferior) - si la sombra se centrase en feetY se saldria del
                // lienzo por abajo y se veria "cortada". Se recorta el borde inferior al tamaño
                // real del lienzo, desplazando la sombra hacia arriba en vez de dejarla clipeada.
                val bottom = (feetY + shH / 2f).coerceAtMost((h0 * scale).toFloat())
                val top = bottom - shH
                cv.drawOval(shCx - shW / 2f, top, shCx + shW / 2f, bottom, shadowPaint)
            }
            val spriteBmp = scaledFrame(frame, scaleMode, w0, h0, scale)
            cv.drawBitmap(spriteBmp, 0f, 0f, null)
            spriteBmp.recycle()
            frameFile(context, styleKey, key, i).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            scaled.recycle(); frame.recycle()
        }
        val ct = colThick ?: IntArray(w0.coerceAtLeast(1))
        val maxT = (ct.maxOrNull() ?: 1).coerceAtLeast(1)
        val core = ct.indices.filter { ct[it] >= 0.6 * maxT }
        val cx = if (core.isNotEmpty()) core.sum() / core.size else w0 / 2
        val cy = if (cnt > 0) (sumY / cnt).toInt() else h0 / 2
        val meta = SpriteMeta(key, frames.size, cx, interval, w0, h0, cy, CACHE_VERSION)
        File(d, "meta.txt").writeText("${meta.frameCount},${meta.cx},${meta.intervalMs},${meta.w},${meta.h},${meta.cy},${meta.ver}")
        Log.i("PokeGotchi", "cacheado $key [$styleKey]: $meta")
        return meta
    }

    fun download(urlStr: String): ByteArray? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return try {
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w("PokeGotchi", "descarga fallo: $e"); null
        } finally { conn.disconnect() }
    }

    // ==================== ANIMACION DE EVOLUCION (silueta + disolucion) ====================
    // Fiel a los juegos originales: silueta BLANCA solida (no un simple oscurecido), y la
    // transicion entre sprites es una disolucion "a bloques" con una trama fija (Bayer 4x4),
    // igual que las pantallas de Game Boy/GBA - NO un fundido liso, que no se parece nada al
    // original.

    /** Blanco solido donde habia opacidad (conserva el canal alpha) - la silueta clasica. */
    fun toWhiteSilhouette(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            pixels[i] = (a shl 24) or 0xFFFFFF
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Gris (luminancia estandar) donde habia opacidad, conservando el canal alpha - a diferencia
     *  de toWhiteSilhouette (blanco solido, sin ninguna sombra), esto deja ver el propio sprite
     *  "apagado" mientras una animacion pasa por encima (ver MainActivity.playMegaEvolveAnimation,
     *  pedido explicito del usuario: el Pokemon en gris hasta que termina de transformarse). */
    fun toGrayscale(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF; val g = (p ushr 8) and 0xFF; val b = p and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (y shl 16) or (y shl 8) or y
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private val BAYER4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** Mezcla [a] y [b] (mismo tamaño) segun [progress] (0f=solo a, 1f=solo b) con la trama de
     *  Bayer: en cada paso un patron FIJO de pixeles cambia de a a b, no liso ni aleatorio. */
    fun ditherDissolve(a: Bitmap, b: Bitmap, progress: Float): Bitmap {
        val w = a.width; val h = a.height
        val pa = IntArray(w * h); a.getPixels(pa, 0, w, 0, 0, w, h)
        val pb = IntArray(w * h); b.getPixels(pb, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val threshold = (progress * 16f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val bay = BAYER4[(y % 4) * 4 + (x % 4)]
                out[i] = if (bay < threshold) pb[i] else pa[i]
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Centra [src] en un lienzo transparente de [w]x[h] - para poder disolver entre dos
     *  sprites de tamaños distintos (especie antigua/nueva) dentro del mismo marco. */
    fun centeredOn(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawBitmap(src, (w - src.width) / 2f, (h - src.height) / 2f, null)
        return out
    }
}
