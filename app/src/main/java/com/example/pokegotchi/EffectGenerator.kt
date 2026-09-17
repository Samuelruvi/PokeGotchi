package com.example.pokegotchi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dibuja los frames de los efectos de reaccion EN EL MOMENTO, ajustados al tamaño (w,h) y
 * centro (cx) del Pokemon actual (px logicos). Se upscalea x4 para casar con los sprites.
 * Fase 1: corazones (comun a los 3). Luego se añaden baya/agua/mano.
 */
object EffectGenerator {
    private const val SCALE = 4

    private val HEART = arrayOf(
        "0110110", "1111111", "1121111", "3111113", "0311130", "0031300", "0003000"
    )
    private const val C_BODY = 0xFFFF5C94.toInt()
    private const val C_HI = 0xFFFFD2E4.toInt()
    private const val C_EDGE = 0xFFBE285A.toInt()

    fun frameFor(kind: String, i: Int, nf: Int, w: Int, h: Int, cx: Int, cy: Int, cryFrame: Int = 0): Bitmap {
        val arr = IntArray(w * h)
        val cyy = if (cy in 1 until h) cy else h / 2
        when (kind) {
            "feed" -> drawFeed(arr, w, h, cx, cyy, i, nf)
            "pet" -> drawPet(arr, w, h, cx, cyy, i, nf, cryFrame)
            "wash" -> drawWash(arr, w, h, cx, i, nf)
        }
        val small = Bitmap.createBitmap(arr, w, h, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(small, w * SCALE, h * SCALE, false)
        small.recycle()
        return big
    }

    // ---- primitivas ----
    private fun blend(arr: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int) {
        if (x < 0 || x >= w || y < 0 || y >= h) return
        val a = (color ushr 24) and 0xFF
        if (a == 0) return
        val idx = y * w + x
        if (a == 255) { arr[idx] = color; return }
        val dst = arr[idx]
        val da = (dst ushr 24) and 0xFF
        val na = a + da * (255 - a) / 255
        if (na == 0) return
        val sr = (color ushr 16) and 0xFF; val sg = (color ushr 8) and 0xFF; val sb = color and 0xFF
        val dr = (dst ushr 16) and 0xFF; val dg = (dst ushr 8) and 0xFF; val db = dst and 0xFF
        val nr = (sr * a + dr * da * (255 - a) / 255) / na
        val ng = (sg * a + dg * da * (255 - a) / 255) / na
        val nb = (sb * a + db * da * (255 - a) / 255) / na
        arr[idx] = (na shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun stampHeart(arr: IntArray, w: Int, h: Int, cx: Int, cy: Int, alpha: Float, hs: Int) {
        for (j in HEART.indices) for (i in HEART[j].indices) {
            val ch = HEART[j][i]
            if (ch == '0') continue
            val col = when (ch) { '1' -> C_BODY; '2' -> C_HI; else -> C_EDGE }
            val a = (((col ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255)
            if (a == 0) continue
            val c = (a shl 24) or (col and 0xFFFFFF)
            val bx = cx + (i - 3) * hs; val by = cy + (j - 3) * hs
            for (dy in 0 until hs) for (dx in 0 until hs) blend(arr, w, h, bx + dx, by + dy, c)
        }
    }

    // ---- corazones en dos hileras (reutilizable) ----
    private class HP(val xl: Int, val xr: Int, val cy: Int, val rise: Int, val spread: Int, val hs: Int)
    private fun heartsParams(w: Int, h: Int, cx: Int): HP {
        val u = h / 46f
        fun r(v: Float) = (v * u).roundToInt()
        return HP(cx - r(7f), cx + r(7f), r(34f), r(20f), r(11f), max(1, u.roundToInt()))
    }
    private fun heartsRows(arr: IntArray, w: Int, h: Int, cx: Int, f: Int, spawns: IntArray, life: Int) {
        val p0 = heartsParams(w, h, cx)
        for (sp in spawns) {
            val p = (f - sp).toFloat() / life
            if (p < 0f || p > 1f) continue
            val a = sin(p * Math.PI).toFloat()
            val y = (p0.cy - p * p0.rise).toInt()
            stampHeart(arr, w, h, (p0.xl - p * p0.spread).toInt(), y, a, p0.hs)
            stampHeart(arr, w, h, (p0.xr + p * p0.spread).toInt(), y, a, p0.hs)
        }
    }

    private fun setPx(arr: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) arr[y * w + x] = c
    }
    private fun disc(arr: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int, c: Int,
                    skip: List<IntArray> = emptyList()) {
        for (dy in -r..r) for (dx in -r..r) {
            if (dx * dx + dy * dy > r * r) continue
            val x = cx + dx; val y = cy + dy
            if (skip.any { (x - it[0]) * (x - it[0]) + (y - it[1]) * (y - it[1]) <= it[2] * it[2] }) continue
            setPx(arr, w, h, x, y, c)
        }
    }

    // ---- COMER: baya a mordiscos + corazones ----
    private const val C_BERRY = 0xFFE13C3C.toInt()
    private const val C_BERRY_HI = 0xFFFFAAAA.toInt()
    private const val C_LEAF = 0xFF5AB446.toInt()
    private fun drawFeed(arr: IntArray, w: Int, h: Int, cx: Int, cy: Int, f: Int, nf: Int) {
        val u = h / 46f
        val eatEnd = (nf * 0.45f).toInt().coerceAtLeast(1)
        if (f <= eatEnd) {
            val pe = f.toFloat() / eatEnd
            val r = max(2, (4 * u).roundToInt())
            val by = cy + (sin(pe * 9.0) * u).toInt()   // baya en el centro del cuerpo
            val bites = ArrayList<IntArray>()
            if (pe > 0.2f) bites.add(intArrayOf(cx + r, by, minOf((6 * u).roundToInt(), (1 + pe * 6).roundToInt())))
            if (pe > 0.5f) bites.add(intArrayOf(cx - r, by, minOf((6 * u).roundToInt(), (1 + (pe - 0.3f) * 6).roundToInt())))
            disc(arr, w, h, cx, by, r, C_BERRY, bites)
            if (bites.size < 2) setPx(arr, w, h, cx - 1, by - 1, C_BERRY_HI)
            setPx(arr, w, h, cx, by - r, C_LEAF); setPx(arr, w, h, cx + 1, by - r - 1, C_LEAF)
        }
        val spawns = IntArray(6) { k -> eatEnd + (k * (nf - eatEnd) / 6f).toInt() }
        heartsRows(arr, w, h, cx, f, spawns, (nf * 0.32f).toInt())
    }

    // ---- ACARICIAR: mano (emoji recoloreado) deslizando + lineas de movimiento + corazones ----
    private val handCache = HashMap<Int, Triple<IntArray, Int, Int>>()  // hw -> (pixels, w, h)
    private fun handPixels(hw: Int): Triple<IntArray, Int, Int> {
        handCache[hw]?.let { return it }
        val s = 140
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s * 0.8f; textAlign = Paint.Align.CENTER }
        cv.drawText("✋", s / 2f, s * 0.75f, paint)
        val px = IntArray(s * s); bmp.getPixels(px, 0, s, 0, 0, s, s); bmp.recycle()
        var minx = s; var miny = s; var maxx = -1; var maxy = -1
        for (y in 0 until s) for (x in 0 until s) if (((px[y * s + x] ushr 24) and 0xFF) > 20) {
            if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y
        }
        if (maxx < minx) { val t = Triple(IntArray(1), 1, 1); handCache[hw] = t; return t }
        val bw = maxx - minx + 1; val bh = maxy - miny + 1
        val crop = IntArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val c = px[(miny + y) * s + (minx + x)]
            val a = (c ushr 24) and 0xFF
            if (a < 20) continue
            val r = (c ushr 16) and 0xFF; val g = (c ushr 8) and 0xFF; val b = c and 0xFF
            val lum = (r * 77 + g * 151 + b * 28) shr 8  // luminancia 0..255
            val fr = 120 + (255 - 120) * lum / 255
            val fg = 70 + (228 - 70) * lum / 255
            val fb = 45 + (196 - 45) * lum / 255
            crop[y * bw + x] = (a shl 24) or (fr shl 16) or (fg shl 8) or fb
        }
        val hh = max(1, hw * bh / bw)
        val src = Bitmap.createBitmap(crop, bw, bh, Bitmap.Config.ARGB_8888)
        val small = Bitmap.createScaledBitmap(src, hw, hh, true)
        src.recycle()
        val out = IntArray(hw * hh); small.getPixels(out, 0, hw, 0, 0, hw, hh); small.recycle()
        val t = Triple(out, hw, hh); handCache[hw] = t; return t
    }

    // Referencia FIJA para la velocidad del vaiven de la mano (2 ciclos completos en 48
    // "fotogramas logicos") - independiente de [nf] (ver PET_HAND_FREEZE_FRAME/drawPet).
    private const val PET_REF_FRAMES = 48f

    // Ultimo frame en el que la mano SIGUE avanzando su vaiven - mas alla de este se queda
    // CONGELADA en esa misma postura (nunca vuelve a mostrar el vaiven a medias, que fue
    // justamente lo que se recorto en su dia, ver PokeWidgetProvider.fxFor).
    private const val PET_HAND_FREEZE_FRAME = 39

    // Rafaga final "de golpe" al grito, como al terminar de comer (ver drawFeed: varios grupos
    // escalonados, no una pareja sola) - pedido explicito del usuario. Arranca justo en el frame
    // del grito (cryFrame) y se reparte en PET_BURST_SPAN fotogramas, cada corazon con
    // PET_BURST_LIFE de vida - los dos primeros grupos (durante la caricia en si) NO cambian,
    // siguen el ritmo de la mano de siempre. Ajustado (segunda vuelta, pedido explicito del
    // usuario) para que la rafaga entera dure lo que dura el grito de verdad (~0.8-1s, ver
    // cry_latest.ogg/cry_legacy.ogg con el pitch aleatorio de SoundManager.playCry) en vez de
    // alargarse mas de 2s por su cuenta - y con menos corazones a la vez (4, no 6).
    private const val PET_BURST_SPAWNS = 4
    private const val PET_BURST_SPAN = 5
    private const val PET_BURST_LIFE = 7

    /** Ultimo frame que hace falta renderizar para que la rafaga completa (arrancada en
     *  [cryFrame]) se vea entera, incluida su caida - lo usa PokeWidgetProvider para saber hasta
     *  donde extender el bucle de render en "pet". */
    fun petBurstEndFrame(cryFrame: Int): Int = cryFrame + PET_BURST_SPAN + PET_BURST_LIFE

    private fun drawPet(arr: IntArray, w: Int, h: Int, cx: Int, cy: Int, f: Int, nf: Int, cryFrame: Int) {
        val u = h / 46f
        val (hand, hw, hh) = handPixels(max(8, (16 * u).roundToInt()))
        val amp = w * 0.22f
        val handF = minOf(f, PET_HAND_FREEZE_FRAME)
        val ang = 2.0 * Math.PI * 2.0 * handF / PET_REF_FRAMES
        val xc = cx + (amp * sin(ang)).toInt()
        val topY = (cy - hh / 2) + (sin(2 * ang) * u).toInt()   // mano centrada en el cuerpo
        val cyLine = topY + hh / 2
        val vel = cos(ang)
        // lineas de movimiento detras de la mano - solo mientras la mano SIGUE en marcha (si esta
        // ya congelada/desvaneciendo, no tiene sentido seguir dibujando estela detras de una mano
        // quieta).
        if (f <= PET_HAND_FREEZE_FRAME && abs(vel) > 0.35) {
            val len = max(2, (5 * u).roundToInt())
            val c = 0xF0F5F5FF.toInt()
            if (vel > 0) { val x1 = xc - hw / 2 - 2; for (off in intArrayOf(-2, 0, 2)) for (l in 0 until len) setPx(arr, w, h, x1 - l, cyLine + off, c) }
            else { val x1 = xc + hw / 2 + 2; for (off in intArrayOf(-2, 0, 2)) for (l in 0 until len) setPx(arr, w, h, x1 + l, cyLine + off, c) }
        }
        // La mano ya NO se queda congelada quieta hasta el grito (pedido explicito del usuario,
        // se veia raro) - en cuanto termina su vaiven se desvanece poco a poco, del todo justo
        // para cuando llega el grito (cryFrame), asi la ráfaga de corazones se ve sobre el
        // Pokemon solo, sin una mano parada estorbando.
        val fadeSpan = (cryFrame - PET_HAND_FREEZE_FRAME).coerceAtLeast(1)
        val handAlpha = 1f - ((f - PET_HAND_FREEZE_FRAME).toFloat() / fadeSpan).coerceIn(0f, 1f)
        if (handAlpha > 0f) {
            val hx = xc - hw / 2
            for (y in 0 until hh) for (x in 0 until hw) {
                val c = hand[y * hw + x]
                val a0 = (c ushr 24) and 0xFF; if (a0 == 0) continue
                val a = (a0 * handAlpha).toInt().coerceIn(0, 255); if (a == 0) continue
                blend(arr, w, h, hx + x, topY + y, (a shl 24) or (c and 0x00FFFFFF))
            }
        }
        // Grupos 1 y 2 de corazones: durante la caricia en si, sin cambios - siguen el ritmo de
        // la mano de siempre (basados en handTotal=40, no en [nf]).
        val handTotal = PET_HAND_FREEZE_FRAME + 1
        val earlyLife = max(2, (handTotal * 0.28f).toInt())
        val earlySpawns = intArrayOf((handTotal * 0.15f).toInt(), (handTotal * 0.45f).toInt())
        heartsRows(arr, w, h, cx, f, earlySpawns, earlyLife)

        // Rafaga final: varios corazones escalonados arrancando justo en el grito (ver
        // petBurstEndFrame/PET_BURST_*), para que salgan "de golpe" en vez de solo uno.
        val burstSpawns = IntArray(PET_BURST_SPAWNS) { k -> cryFrame + k * PET_BURST_SPAN / (PET_BURST_SPAWNS - 1) }
        heartsRows(arr, w, h, cx, f, burstSpawns, PET_BURST_LIFE)
    }

    // ---- DUCHA: chorro en cono desde arriba + espuma abajo, luego corazones ----
    private const val C_DROP = 0xFF5AB9FF.toInt()
    private const val C_DROP_HI = 0xFFE6F8FF.toInt()
    private const val C_FOAM = 0xFFF8FAFC.toInt()
    private fun drawWash(arr: IntArray, w: Int, h: Int, cx: Int, f: Int, nf: Int) {
        val u = h / 46f
        val se = (nf * 0.6f).toInt().coerceAtLeast(1)
        if (f <= se) {
            val srcY = (3 * u).roundToInt(); val fall = (40 * u).roundToInt()
            val dlife = max(2, (nf * 0.16f).toInt())
            val streams = arrayOf(-4f to -22f, -1.5f to -8f, 1.5f to 8f, 4f to 22f)
            for ((s0, s1) in streams) {
                var sp = 0
                while (sp <= se) {
                    val p = (f - sp).toFloat() / dlife
                    if (p in 0f..1f && p < 0.9f) {
                        val x = cx + ((s0 + (s1 - s0) * p) * u).toInt()
                        val y = srcY + (p * fall).toInt()
                        // gota (cruz azul + brillo)
                        setPx(arr, w, h, x, y, C_DROP); setPx(arr, w, h, x - 1, y, C_DROP)
                        setPx(arr, w, h, x + 1, y, C_DROP); setPx(arr, w, h, x, y + 1, C_DROP)
                        setPx(arr, w, h, x, y - 1, C_DROP_HI)
                    }
                    sp += 2
                }
            }
            // espuma en dos filas abajo con leve bob
            val r = max(1, (2 * u).roundToInt())
            var fx = cx - (15 * u).roundToInt()
            var toggle = 0
            while (fx <= cx + (15 * u).roundToInt()) {
                val fy = h - (if (toggle == 0) 3 else 6) * u.roundToInt()
                val bob = (sin(f * 0.5 + fx.toDouble())).toInt()
                disc(arr, w, h, fx, fy + bob, r, C_FOAM)
                fx += (6 * u).roundToInt(); toggle = 1 - toggle
            }
        }
        val spawns = IntArray(4) { k -> se + (k * (nf - se) / 4f).toInt() }
        heartsRows(arr, w, h, cx, f, spawns, (nf * 0.32f).toInt())
    }

    // ---- NUBE DE NECESIDAD: nube pixel-art fija (no ligada al tamaño del sprite), con una
    // colita de puntitos que sale del centro (donde esta el Pokemon debajo) EN DIAGONAL hacia
    // un lado, para colgarla flotando por encima. El contenido (icono, uno solo: el de mayor
    // necesidad, o el rechazo) no tiene por que ser pixel-art: se renderiza a mas resolucion y
    // se reduce, igual que la mano de acariciar, pero SIN recolorear (colores propios). ----
    private const val CLOUD_BLOB_R = 24
    private const val CLOUD_ICON_H = 24  // tamaño del icono FIJO: no crece con la nube
    // Rango real de cloudScale que deja elegir el simulador visual (ver FIELD_DEFS alli, min 0.2
    // max 1.4) y tope de memoria seguro (ver needCloud) - juntos definen el reescalado lineal que
    // evita que un valor alto se recorte en seco al mismo tamaño que cualquier otro por encima del
    // tope.
    private const val CLOUD_SCALE_MAX = 1.4f
    private const val MEMORY_SAFE_SCALE_CAP = 0.70f
    private val C_CLOUD_FILL = 0xFFFFFFFF.toInt()
    private val C_CLOUD_EDGE = 0xFF46464F.toInt()

    private val iconCache = HashMap<String, Triple<IntArray, Int, Int>>()  // "$texto#$alto" -> (pixels,w,h)
    /** Renderiza un emoji/texto a pixeles, recortado a su contenido y reducido a [targetH] de
     *  alto, SIN recolorear (a diferencia de handPixels, pensado para la mano de acariciar). */
    private fun iconPixels(text: String, targetH: Int): Triple<IntArray, Int, Int> {
        val key = "$text#$targetH"
        iconCache[key]?.let { return it }
        val s = 140
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s * 0.8f; textAlign = Paint.Align.CENTER }
        cv.drawText(text, s / 2f, s * 0.75f, paint)
        val px = IntArray(s * s); bmp.getPixels(px, 0, s, 0, 0, s, s); bmp.recycle()
        var minx = s; var miny = s; var maxx = -1; var maxy = -1
        for (y in 0 until s) for (x in 0 until s) if (((px[y * s + x] ushr 24) and 0xFF) > 20) {
            if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y
        }
        if (maxx < minx) { val t = Triple(IntArray(1), 1, 1); iconCache[key] = t; return t }
        val bw = maxx - minx + 1; val bh = maxy - miny + 1
        val crop = IntArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) crop[y * bw + x] = px[(miny + y) * s + (minx + x)]
        val th = max(1, targetH); val tw = max(1, targetH * bw / bh)
        val src = Bitmap.createBitmap(crop, bw, bh, Bitmap.Config.ARGB_8888)
        val small = Bitmap.createScaledBitmap(src, tw, th, true)
        src.recycle()
        val out = IntArray(tw * th); small.getPixels(out, 0, tw, 0, 0, tw, th); small.recycle()
        val t = Triple(out, tw, th); iconCache[key] = t; return t
    }

    private fun stampIcon(arr: IntArray, w: Int, h: Int, cx: Int, cy: Int, text: String, targetH: Int) {
        val (px, iw, ih) = iconPixels(text, targetH)
        val x0 = cx - iw / 2; val y0 = cy - ih / 2
        for (y in 0 until ih) for (x in 0 until iw) {
            val c = px[y * iw + x]; if ((c ushr 24) == 0) continue
            blend(arr, w, h, x0 + x, y0 + y, c)
        }
    }

    /** Racimo de circulos superpuestos (relativos al centro, en fracciones de [r]) que forman
     *  la silueta ABULTADA de una nube de comic: un bulto central + varios alrededor de radio
     *  distinto. Al dibujar TODOS los bordes primero y TODOS los rellenos despues (misma tecnica
     *  que ya se usaba para blob+cola), los solapes se funden y sale un contorno unico festoneado
     *  en vez de un simple circulo. */
    private fun cloudBumps(r: Float): List<Triple<Float, Float, Float>> = listOf(
        Triple(0f, 0f, r * 1.00f),
        Triple(-r * 0.66f, -r * 0.38f, r * 0.62f),
        Triple(r * 0.66f, -r * 0.38f, r * 0.62f),
        Triple(0f, -r * 0.80f, r * 0.50f),
        Triple(-r * 0.52f, r * 0.50f, r * 0.58f),
        Triple(r * 0.52f, r * 0.50f, r * 0.58f),
        Triple(0f, r * 0.62f, r * 0.55f)
    )

    /**
     * Nube "de pensamiento" junto al Pokemon, con UN solo icono dentro (el de mayor necesidad, o
     * el rechazo). La nube grande es un RACIMO de circulos (silueta abultada tipo comic, no un
     * circulo liso) y 2 burbujitas pequeñas la conectan con el Pokemon. El icono dentro tiene
     * tamaño FIJO (no crece con la nube): basta con que se entienda, no hace falta que llene el
     * hueco. La nube ahora vive SIEMPRE en el hueco de la izquierda del Pokemon (ver
     * PokeWidgetProvider.applyAdaptiveEggAndCloudLayout), asi que la colita sale de la nube
     * (arriba-izquierda) y baja EN DIAGONAL hacia la derecha, donde esta el Pokemon - antes
     * colgaba hacia abajo porque la nube estaba encima de su cabeza.
     * [scale] reduce el tamaño final (1f = tamaño de siempre) para que no se vea desproporcionada
     * cuando el propio sprite se encoge en un widget pequeño. [tailVertical] (0f..1f) controla
     * cuanto BAJA la colita ademas de ir hacia la derecha: 1f = diagonal de siempre (widgets
     * altos, con sitio de sobra debajo de la nube); valores bajos = colita casi horizontal, para
     * widgets muy bajos (2-3 filas) donde la diagonal normal se saldria del hueco por abajo -
     * pedido explicito del usuario tras ver la nube "apuntando hacia las barras" en esos tamaños.
     */
    fun needCloud(icon: String, scale: Float = 1f, tailVertical: Float = 1f): Bitmap {
        val blobR = CLOUD_BLOB_R
        val bumps = cloudBumps(blobR.toFloat())
        val maxReach = bumps.maxOf { (bx, _, br) -> abs(bx) + br }
        val margin = 2
        val dot2R = max(3, (blobR * 0.30f).roundToInt())
        val dot1R = max(2, (blobR * 0.17f).roundToInt())
        val gap = 1
        // La nube grande queda a la izquierda del lienzo; la colita (dot2 luego dot1, cada vez
        // mas pequeña y mas cerca del Pokemon) baja hacia la derecha - tailVertical achata esa
        // bajada (mas horizontal cuanto mas bajo).
        val blobX = margin + maxReach.roundToInt()
        val blobY = margin + maxReach.roundToInt()
        val dot2X = blobX + (blobR * 1.05f).roundToInt()
        val dot2Y = blobY + (blobR * 1.05f * tailVertical).roundToInt()
        val dot1X = dot2X + (blobR * 0.65f).roundToInt()
        val dot1Y = dot2Y + (blobR * 0.65f * tailVertical).roundToInt()
        // El bitmap tiene que ser tan grande como para contener el racimo (blob) ENTERO, no solo
        // hasta donde llegue la colita - con tailVertical bajo (colita casi horizontal, usada en
        // widgets bajos) la colita apenas baja, y el alto basado solo en ella (dot1Y+dot1R) podia
        // quedar mas pequeño que el propio racimo (sus bultos de abajo, ver cloudBumps, bajan mas
        // que eso) - el array del bitmap cortaba esos bultos en seco, con un borde recto, justo el
        // bug real reportado ("la nube se ve cortada por debajo") en tamaños de widget pequeños.
        val maxDownReach = bumps.maxOf { (_, by, br) -> by + br }
        val maxRightReach = bumps.maxOf { (bx, _, br) -> bx + br }
        val cloudW = maxOf(dot1X + dot1R + margin, blobX + maxRightReach.roundToInt() + margin)
        val cloudH = maxOf(dot1Y + dot1R + margin, blobY + maxDownReach.roundToInt() + margin)
        val arr = IntArray(cloudW * cloudH)
        // colita + racimo de la nube grande: TODO junto, borde primero y relleno despues, para
        // que el conjunto se funda en una silueta continua (sin costuras entre circulos).
        val points = mutableListOf(Triple(dot1X, dot1Y, dot1R), Triple(dot2X, dot2Y, dot2R))
        for ((bx, by, br) in bumps) points.add(Triple(blobX + bx.roundToInt(), blobY + by.roundToInt(), br.roundToInt()))
        for ((x, y, r) in points) disc(arr, cloudW, cloudH, x, y, r + 1, C_CLOUD_EDGE)
        for ((x, y, r) in points) disc(arr, cloudW, cloudH, x, y, r, C_CLOUD_FILL)
        stampIcon(arr, cloudW, cloudH, blobX, blobY, icon, CLOUD_ICON_H)
        val small = Bitmap.createBitmap(arr, cloudW, cloudH, Bitmap.Config.ARGB_8888)
        // Tope de seguridad en el bitmap generado (independiente de lo que pida [scale]): un
        // escalado de 1.22 (uno de los tamaños ajustados por el usuario) sumado a los fotogramas
        // del huevo superaba el limite real de memoria por actualizacion de RemoteViews y
        // CRASHEABA la app en segundo plano de verdad, no solo en pruebas (ver logs con horas de
        // diferencia). El tope se puso primero en 0.85 pero eso resulto estar literalmente al
        // borde del limite real (crash reproducido: 19874992 de 19851840 maximo, solo ~0.1% de
        // margen) - crasheaba o no dependiendo de cuantos fotogramas llevase el huevo en ese
        // instante.
        //
        // Recortar [scale] en seco a ese tope (coerceAtMost) era otro bug real: el usuario tuneo
        // cloudScale en un rango relativo de ~0.47 a 1.40 en el simulador (numero elegido a ojo
        // para cada tamaño de widget, mas grande = nube mas grande), pero CUALQUIER valor por
        // encima del tope de memoria salia clavado en EXACTAMENTE el mismo tamaño - como casi
        // toda la tabla esta por encima de ese tope, la nube parecia no cambiar nunca de tamaño
        // entre formatos (bug reportado: "siempre tiene el mismo tamaño"). En vez de recortar, se
        // reescala LINEALMENTE todo el rango tuneado (0..CLOUD_SCALE_MAX, el maximo real que deja
        // elegir el simulador) para que quepa entero por debajo del tope de memoria, conservando
        // las proporciones relativas que el usuario si eligio a proposito.
        val safeScale = (scale / CLOUD_SCALE_MAX) * MEMORY_SAFE_SCALE_CAP
        val finalW = (cloudW * SCALE * safeScale).roundToInt().coerceAtLeast(1)
        val finalH = (cloudH * SCALE * safeScale).roundToInt().coerceAtLeast(1)
        val big = Bitmap.createScaledBitmap(small, finalW, finalH, false)
        small.recycle()
        return big
    }

    // ---- HUEVO: parte de un sprite pixel-art REAL (32x32, mismo estilo/epoca -Gen III-V- que
    // los sprites Gen 5 de Showdown que ya usa el resto del juego), escalado SIN suavizar (igual
    // que SpriteRepository hace con los sprites de los Pokemon) para que se vea nitido y no
    // borroso. Las grietas se dibujan encima como mini-bloques (sin antialiasing, del tamaño de
    // un pixel del propio sprite) para que combinen con el estilo pixel-art, no como lineas lisas.
    // El "palpito" (mas seguido cuanto mas cerca de eclosionar) es una RAFAGA real de traslacion
    // horizontal de varios segundos (ver PokeWidgetProvider.playEggPulse), no una simple muestra
    // estatica en cada refresco. ----
    // Escalado bastante mayor que el resto de sprites (SpriteRepository usa x4): el huevo se
    // muestra en una caja de tamaño FIJO en dp (ver widget_pokegotchi.xml, egg_overlay), asi que
    // conviene generarlo grande para que no se vea borroso al reescalar el sistema hacia esa
    // caja en pantallas de alta densidad.
    private const val EGG_SCALE = 11
    private val C_EGG_EDGE = 0xFF3A3A3A.toInt()

    // Grietas y huevo "abierto" DIBUJADOS A MANO por el usuario, pixel a pixel, en un editor a
    // medida (una pagina HTML con el sprite real ampliado y una rejilla clicable) - NO generados
    // por formula/simetria como los intentos anteriores. Coordenadas en el sistema de CONTENIDO
    // YA RECORTADO (ver EGG_CONTENT mas abajo: 0..13 horizontal, 0..16 vertical). Cada fase es un
    // dibujo independiente (no una progresion calculada): fase1=pocas grietas pequeñas cerca del
    // borde, fase2=las mismas mas grandes/con alguna mas, fase3=aun mas grandes y con alguna
    // conexion - tal cual las diseño el usuario.
    private val EGG_CRACK_PHASE1 = listOf(
        2 to 12, 3 to 11, 2 to 5, 3 to 5, 4 to 6, 12 to 10, 11 to 10, 10 to 9
    )
    private val EGG_CRACK_PHASE2 = listOf(
        2 to 12, 3 to 11, 2 to 5, 3 to 5, 4 to 6, 12 to 10, 11 to 10, 10 to 9,
        4 to 10, 5 to 10, 6 to 10, 5 to 6, 6 to 7, 9 to 8, 9 to 13, 8 to 12
    )
    private val EGG_CRACK_PHASE3 = listOf(
        2 to 12, 3 to 11, 2 to 5, 3 to 5, 4 to 6, 12 to 10, 11 to 10, 10 to 9,
        4 to 10, 5 to 10, 6 to 10, 5 to 6, 6 to 7, 9 to 8, 9 to 13, 8 to 12,
        7 to 10, 7 to 9, 8 to 8, 7 to 8, 7 to 11
    )
    // Pixeles a QUITAR (transparentes) para el huevo "listo para eclosionar" - deja solo la
    // copa de abajo, con un borde irregular natural dibujado a mano (no un triangulo/pico
    // procedural). Coordenadas en pixeles NATIVOS (grid del contenido recortado, ver
    // EGG_CONTENT) - se aplican en eggSourceUpscaled(hatched=true), ANTES de escalar/suavizar,
    // no despues (ver comentario alli sobre por que).
    private val EGG_OPENED_MASK = listOf(
        3 to 10, 4 to 9, 5 to 8, 6 to 9, 7 to 9, 8 to 10, 9 to 9, 10 to 8, 11 to 9, 2 to 9,
        1 to 8, 1 to 7, 2 to 7, 2 to 8, 2 to 6, 3 to 5, 2 to 5, 3 to 4, 3 to 3, 4 to 3,
        4 to 2, 6 to 2, 5 to 2, 6 to 1, 7 to 1, 7 to 2, 8 to 2, 9 to 2, 9 to 3, 10 to 3,
        10 to 4, 10 to 5, 11 to 5, 11 to 6, 11 to 7, 12 to 7, 11 to 8, 12 to 8, 10 to 7,
        10 to 6, 9 to 6, 9 to 8, 9 to 7, 8 to 7, 8 to 8, 8 to 9, 7 to 8, 6 to 8, 6 to 7,
        7 to 7, 5 to 7, 4 to 7, 4 to 8, 3 to 8, 3 to 9, 3 to 7, 3 to 6, 4 to 6, 4 to 5,
        4 to 4, 5 to 4, 5 to 5, 6 to 6, 5 to 6, 6 to 5, 6 to 4, 5 to 3, 6 to 3, 7 to 3,
        8 to 3, 7 to 4, 8 to 4, 9 to 4, 8 to 5, 9 to 5, 7 to 5, 7 to 6, 8 to 6, 7 to 10
    )

    // El lienzo de 32x32 del sprite base tiene MUCHO margen transparente alrededor del ovalo del
    // huevo (medido a mano sobre egg_base.png: el contenido opaco real ocupa solo x=10..21,
    // y=12..26, menos de la mitad del lienzo). Si se escalase el lienzo entero, el huevo visible
    // ocuparia solo una fraccion pequeña de la caja final por mucho que se aumente el factor de
    // escala - por eso se RECORTA primero al contenido real (con 1px de margen) y se escala DESPUES,
    // asi el huevo aprovecha todo el tamaño disponible en vez de perderlo en margen vacio.
    private val EGG_CONTENT = android.graphics.Rect(9, 11, 23, 28)  // x0,y0,x1,y1 (exclusivo)

    /** Decodifica el sprite base a su resolucion NATIVA (sin que el sistema lo reescale solo por
     *  la densidad de pantalla), lo recorta a su contenido real (ver EGG_CONTENT) y lo amplia
     *  x[EGG_SCALE] segun el modo de suavizado elegido por el usuario (mismo Scale2x que
     *  SpriteRepository usa para los sprites de los Pokemon - antes el huevo se quedaba siempre
     *  con el filtro "nearest" puro, sin respetar "parcial"/"total").
     *  [hatched]=true perfora EGG_OPENED_MASK en la imagen NATIVA, ANTES de escalar - NUNCA
     *  despues (ver eggBitmap, version anterior). Perforar despues del Scale2x dejaba "puntitos"
     *  residuales sin borrar (bug real reportado por el usuario): Scale2x mezcla el color de cada
     *  pixel nativo con sus vecinos en un borde que no es un bloque limpio de EGG_SCALE px, y
     *  ademas el paso final de esta funcion reescala ese resultado por scale/2 (5.5, FRACCIONARIO
     *  con EGG_SCALE=11) - un rectangulo de borrado alineado a bloques de EGG_SCALE ya no coincide
     *  con ese borde difuminado y fraccionario, dejando restos de color justo fuera del
     *  rectangulo. Perforando aqui, en pixeles nativos reales, Scale2x ve un hueco genuinamente
     *  transparente desde el principio y lo suaviza de forma coherente en todo el pipeline, sin
     *  dejar ningun resto. */
    private fun eggSourceUpscaled(context: Context, hatched: Boolean = false): Bitmap {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val src = BitmapFactory.decodeResource(context.resources, R.drawable.egg_base, opts)
        var cropped = Bitmap.createBitmap(src, EGG_CONTENT.left, EGG_CONTENT.top, EGG_CONTENT.width(), EGG_CONTENT.height())
        // Bitmap.createBitmap devuelve el MISMO objeto (sin copiar) cuando el recorte pedido es el
        // lienzo entero - hoy EGG_CONTENT (14x17) nunca coincide con egg_base.png entero (32x32),
        // pero si algun dia se ampliara para cubrirlo del todo, reciclar src a ciegas reciclaria
        // tambien "cropped" (el mismo objeto) y todo lo que viene despues crashearia con
        // "getPixels() on a recycled bitmap" - mismo bug real ya visto dos veces en SpriteRepository.
        if (cropped !== src) src.recycle()
        if (hatched) {
            val punched = cropped.copy(Bitmap.Config.ARGB_8888, true)
            cropped.recycle()
            cropped = punched
            val clearPaint = Paint().apply { isAntiAlias = false; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            val cv = Canvas(cropped)
            for ((px, py) in EGG_OPENED_MASK) cv.drawRect(px.toFloat(), py.toFloat(), px + 1f, py + 1f, clearPaint)
        }
        val out = SpriteRepository.scaledFrame(cropped, PetState.spriteScaleMode(context), cropped.width, cropped.height, EGG_SCALE)
        cropped.recycle()
        return out
    }

    /** Pinta el pixel-art de grietas dibujado a mano para la fase [stage] (0=sin grietas). Cada
     *  entrada de la lista es un pixel NATIVO (una casilla del contenido recortado, ver
     *  EGG_CONTENT) que se pinta como un bloque solido de [pixel] x [pixel], sin antialiasing -
     *  1:1 con lo dibujado en el editor, sin ninguna formula/escalado de por medio. */
    private fun drawEggCracks(cv: Canvas, left: Float, top: Float, stage: Int, pixel: Float) {
        val points = when (stage.coerceIn(0, PetState.EGG_CRACK_STAGES - 1)) {
            1 -> EGG_CRACK_PHASE1
            2 -> EGG_CRACK_PHASE2
            3 -> EGG_CRACK_PHASE3
            else -> return
        }
        val paint = Paint().apply { color = C_EGG_EDGE; isAntiAlias = false; style = Paint.Style.FILL }
        for ((px, py) in points) {
            val x = left + px * pixel; val y = top + py * pixel
            cv.drawRect(x, y, x + pixel, y + pixel, paint)
        }
    }

    /** Bitmap del huevo en la fase [stage] (0=intacto .. EGG_CRACK_STAGES-1=muy agrietado). Si
     *  [hatched] es true, dibuja el huevo YA ABIERTO (2 mitades de cascara, recortadas del mismo
     *  sprite real) en su lugar. [shakeAngleDeg] rota el huevo sobre su propia BASE (que se
     *  mantiene fija, como un pendulo): usado SOLO durante una rafaga real de palpito (ver
     *  PokeWidgetProvider.playEggPulse) - en reposo vale 0 (huevo quieto). Un huevo de verdad
     *  puesto en el suelo, al moverse, se mece por su forma sin resbalar - la parte de ARRIBA
     *  oscila, la base no se desplaza. */
    /** Sombra ovalada + nido de paja/hierba bajo el huevo, pedido por el usuario ("lo tipico que
     *  se deja el huevo en mucho pasto"). Se dibuja PRIMERO (antes del huevo y ANTES de rotar/
     *  mecer), en la posicion FIJA de la base ([groundY]) - asi el nido no se mueve con el
     *  vaiven, solo el huevo se inclina por encima de el, como si estuviera de verdad posado ahi. */
    private fun drawEggGround(cv: Canvas, cx: Float, groundY: Float, ew: Float) {
        val shadowW = ew * 0.95f
        val shadowH = shadowW * 0.30f
        val shadowPaint = Paint().apply { color = 0x46000000.toInt(); isAntiAlias = true }
        cv.drawOval(cx - shadowW / 2f, groundY - shadowH / 2f, cx + shadowW / 2f, groundY + shadowH / 2f, shadowPaint)

        val nestW = ew * 1.05f
        val nestH = nestW * 0.32f
        val nestTop = groundY - nestH * 0.55f
        val nestPaint = Paint().apply { color = 0xFFC2A050.toInt(); isAntiAlias = false }
        val nestEdge = Paint().apply {
            color = 0xFF8B6914.toInt(); isAntiAlias = false
            style = Paint.Style.STROKE; strokeWidth = ew * 0.025f
        }
        cv.drawOval(cx - nestW / 2f, nestTop, cx + nestW / 2f, nestTop + nestH, nestPaint)
        cv.drawOval(cx - nestW / 2f, nestTop, cx + nestW / 2f, nestTop + nestH, nestEdge)

        // Briznas de hierba asomando a los lados del nido (trazos diagonales, sin antialiasing
        // para que combinen con el resto del pixel-art).
        val bladePaint = Paint().apply {
            color = 0xFF6FA83C.toInt(); isAntiAlias = false; strokeWidth = ew * 0.035f
        }
        for (fx in listOf(-0.46f, -0.34f, -0.20f, 0.20f, 0.34f, 0.46f)) {
            val bx = cx + fx * nestW
            val by = nestTop + nestH * 0.4f
            val dir = if (fx < 0) -1f else 1f
            cv.drawLine(bx, by, bx + dir * ew * 0.07f, by - ew * 0.17f, bladePaint)
        }
    }

    fun eggBitmap(context: Context, stage: Int, hatched: Boolean, shakeAngleDeg: Float = 0f): Bitmap {
        val egg = eggSourceUpscaled(context, hatched)
        val ew = egg.width; val eh = egg.height
        val canvasSize = (eh * 1.6f).toInt()
        val out = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val cx = canvasSize / 2f
        val groundY = canvasSize - eh * 0.15f  // misma base fija que usan los 2 pivotes de abajo
        drawEggGround(cv, cx, groundY, ew.toFloat())

        if (!hatched) {
            // Pivote en la BASE del huevo (no en su centro): asi al rotar, la base se queda fija
            // en el sitio y solo se mece la parte de arriba, como un huevo de verdad.
            val pivotX = cx; val pivotY = groundY
            val left = pivotX - ew / 2f; val top = pivotY - eh
            cv.save()
            cv.rotate(shakeAngleDeg, pivotX, pivotY)
            cv.drawBitmap(egg, left, top, null)
            drawEggCracks(cv, left, top, stage, EGG_SCALE.toFloat())
            cv.restore()
        } else {
            // Listo para eclosionar: [egg] YA viene con EGG_OPENED_MASK perforado en resolucion
            // nativa (ver eggSourceUpscaled(hatched=true)) - deja solo la copa de abajo con un
            // borde irregular natural. Aqui ya no hace falta (ni conviene) recortar nada mas: si
            // se recortase AQUI, sobre el bitmap ya escalado/suavizado, volveria el bug de los
            // "puntitos" residuales que este mismo cambio corrige (ver comentario de arriba).
            val pivotX = cx; val pivotY = groundY
            val left = pivotX - ew / 2f; val top = pivotY - eh
            cv.drawBitmap(egg, left, top, null)
        }
        egg.recycle()
        return out
    }

    // ==================== MEGAEVOLUCION / GIGANTAMAX ====================
    // mega_evo_sheet.png: recurso de terceros (paquete "Animations pokemon" revisado y aprobado
    // por el usuario - carpeta de Descargas, PRAS- MegaEvo.png), cuadricula de 192x192 x 13
    // frames en orden de lectura (5 filas... en realidad 5+5+3): crece un orbe pequeño -> se
    // agrieta -> se ennegrece por completo, tapando al Pokemon. NO hay arte propio de Gigantamax
    // en el paquete (comprobado, ver GEN8-*.png) - el propio paquete YA reutiliza este mismo
    // dibujo re-tiñendolo para otra mecanica distinta (Ultra Burst, en amarillo-verde en vez de
    // morado), asi que aqui se hace igual: mismo dibujo, retiñido a rojo via tintByValue cuando
    // [gmax] es true.
    private const val MEGA_EVO_CELL = 192
    const val MEGA_EVO_FRAMES = 13
    private const val MEGA_EVO_COLS = 5
    private const val GMAX_TINT = 0xFFE0273B.toInt()

    /** Los MEGA_EVO_FRAMES frames YA recortados y escalados a [size]x[size] (retiñidos a rojo si
     *  [gmax]), en orden. Decodifica la hoja UNA sola vez para los 13 - antes se recargaba de
     *  recursos y se reescalaba a pantalla entera en CADA frame (llamando repetidas veces a un
     *  megaEvoFrame de un solo indice), lo bastante lento en el hilo principal como para que la
     *  animacion real durase mucho mas que el sonido de fondo, aunque el calculo del reparto de
     *  tiempo (ver MainActivity.playMegaEvolveAnimation) fuera correcto - bug real reportado por
     *  el usuario. Llamar desde un hilo de fondo (como SpriteRepository.ensure), nunca en el
     *  principal. */
    fun megaEvoFrames(context: Context, size: Int, gmax: Boolean): List<Bitmap> {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val sheet = BitmapFactory.decodeResource(context.resources, R.drawable.mega_evo_sheet, opts)
        val out = ArrayList<Bitmap>(MEGA_EVO_FRAMES)
        for (idx in 0 until MEGA_EVO_FRAMES) {
            val row = idx / MEGA_EVO_COLS; val col = idx % MEGA_EVO_COLS
            val cell = Bitmap.createBitmap(sheet, col * MEGA_EVO_CELL, row * MEGA_EVO_CELL, MEGA_EVO_CELL, MEGA_EVO_CELL)
            val recolored = if (gmax) tintByValue(cell, GMAX_TINT) else cell
            if (gmax) cell.recycle()
            // A diferencia del pixel art de los sprites, este dibujo ya trae degradados/
            // antialiasing propios, asi que aqui conviene el filtro bilineal normal (no el
            // Scale2x/nearest de SpriteRepository.scaledFrame).
            val scaled = Bitmap.createScaledBitmap(recolored, size, size, true)
            if (recolored !== scaled) recolored.recycle()
            out.add(scaled)
        }
        sheet.recycle()
        return out
    }

    /** Reduce [src] a su "valor" (el mayor de sus 3 canales, invariante al tono de origen) y lo
     *  re-tiñe multiplicando por [colorArgb] - sirve para recolorear un dibujo casi monocromo
     *  (como mega_evo_sheet.png, morado sobre blanco/negro) a otro tono sin perder sus propios
     *  degradados de luz/sombra. El alpha de cada pixel no se toca. */
    private fun tintByValue(src: Bitmap, colorArgb: Int): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tr = (colorArgb ushr 16 and 0xFF) / 255f
        val tg = (colorArgb ushr 8 and 0xFF) / 255f
        val tb = (colorArgb and 0xFF) / 255f
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            if (a == 0) continue
            val r = p ushr 16 and 0xFF; val g = p ushr 8 and 0xFF; val b = p and 0xFF
            val v = max(r, max(g, b)) / 255f
            val nr = (v * tr * 255f).roundToInt().coerceIn(0, 255)
            val ng = (v * tg * 255f).roundToInt().coerceIn(0, 255)
            val nb = (v * tb * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
