package com.example.pokegotchi

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Terreno de la mazmorra con las texturas de Pokemon Mundo Misterioso: Exploradores del Cielo
 * (assets/tiles/tsN.png + tsN.json). Solo ESTETICA - no toca nada de la jugabilidad: sigue
 * siendo la misma rejilla de paredes/suelo ([DungeonState.DungeonMap.isWall]), pero cada casilla
 * se pinta con la pieza que corresponde a lo que tiene alrededor (borde de roca donde la pared
 * toca el suelo, esquinas, pared maciza en el interior...) en vez de una sola textura repetida.
 *
 * Traido de PokeMazmorra (TilePicker/TileArt, mismo formato de ficheros). Las texturas son un
 * volcado del ROM del usuario (herramienta tools/extract_tiles.py de PokeMazmorra); el usuario
 * decidio incluirlas en este repo publico y en la release, sabiendolo.
 *
 * Regla del juego original [V, leida del codigo de SkyTemple, graphics/dma/dma_drawer.py]: cada
 * casilla es PARED o SUELO; se calcula un byte con un bit por cada una de las 8 vecinas que vale 1
 * si esa vecina es pared (lo de fuera del mapa cuenta como pared); en una casilla de SUELO el byte
 * se invierte (xor 0xFF). La pieza sale de la tabla del tileset `tabla[tipo][byte]`, con 3
 * variaciones por regla.
 */
object DungeonTilePicker {
    /** Lado de una pieza del atlas, en px, y piezas por fila del atlas. */
    const val TILE_PX = 24
    const val ATLAS_COLS = 16

    private const val SOUTH = 0x01; private const val SOUTH_EAST = 0x02; private const val EAST = 0x04; private const val NORTH_EAST = 0x08
    private const val NORTH = 0x10; private const val NORTH_WEST = 0x20; private const val WEST = 0x40; private const val SOUTH_WEST = 0x80

    /** Byte de vecindad de la casilla (x, y); [isWall] debe devolver true fuera del mapa. */
    fun neighbours(x: Int, y: Int, isWall: (Int, Int) -> Boolean): Int {
        var m = 0
        if (isWall(x, y + 1)) m = m or SOUTH
        if (isWall(x + 1, y + 1)) m = m or SOUTH_EAST
        if (isWall(x + 1, y)) m = m or EAST
        if (isWall(x + 1, y - 1)) m = m or NORTH_EAST
        if (isWall(x, y - 1)) m = m or NORTH
        if (isWall(x - 1, y - 1)) m = m or NORTH_WEST
        if (isWall(x - 1, y)) m = m or WEST
        if (isWall(x - 1, y + 1)) m = m or SOUTH_WEST
        return if (isWall(x, y)) m else m xor 0xFF
    }

    /** Variacion (0..2) estable por casilla: sale de la posicion, asi no parpadea al moverse. */
    fun variation(x: Int, y: Int): Int = Math.floorMod((x * 73856093) xor (y * 19349663), 3)

    /**
     * Cada textura de suelo de PokeGotchi ([DungeonState.DungeonMap.floorTileName], sorteada al
     * azar por piso en DungeonSimulator.generateMap) tiene su tileset - uno por cada una, asi la
     * variedad de pisos es la misma de siempre y el color general se parece al del suelo que tenia.
     * Una textura que no este aqui (o sin ficheros) cae al dibujo plano de siempre.
     */
    private val TILESET_FOR_FLOOR = mapOf(
        "dungeontile_floor_brown_rock" to 12,   // marron anaranjado
        "dungeontile_floor_gray_rock" to 1,     // rosa/violeta con cristales
        "dungeontile_floor_lavender" to 10,     // palido, escarchado
        "dungeontile_floor_pink_swirl" to 3,    // arena clara
        "dungeontile_floor_purple_rock" to 6,   // azulado con cristales morados
        "dungeontile_floor_red_swirl" to 4,     // roca naranja con paredes de hielo
        "dungeontile_floor_slate_blue" to 2,    // azul palido, paredes verde oscuro
        "dungeontile_floor_tan_swirl" to 7,     // arena crema
        "dungeontile_floor_wood_plank" to 5,    // dorado con flores
        "dungeontile_floor_wood_tan" to 8       // gris verdoso, paredes de roca
    )

    fun tilesetFor(floorTileName: String): Int? = TILESET_FOR_FLOOR[floorTileName]
}

/** Tabla de piezas de un tileset: para pared y suelo, 256 reglas x 3 variaciones = indice de la pieza en el atlas. */
class DungeonTileTable(private val wall: IntArray, private val floor: IntArray) {
    init { require(wall.size == SIZE && floor.size == SIZE) { "tabla de tileset incompleta" } }

    /** Indice de la pieza en el atlas; columna = i mod ATLAS_COLS, fila = i / ATLAS_COLS. */
    fun piece(solid: Boolean, mask: Int, variation: Int): Int = (if (solid) wall else floor)[mask * 3 + variation]

    companion object {
        const val SIZE = 256 * 3

        fun parse(json: String): DungeonTileTable {
            val o = JSONObject(json)
            fun arr(k: String) = o.getJSONArray(k).let { a -> IntArray(a.length()) { a.getInt(it) } }
            return DungeonTileTable(arr("w"), arr("f"))
        }
    }
}

/**
 * Dibuja el terreno con las texturas de assets/tiles. Sin esos ficheros (o con un tileset
 * desconocido), [drawCells] devuelve false y la vista usa el dibujo plano de siempre.
 */
class DungeonTileArt(private val assets: AssetManager) {
    private class Loaded(val bmp: Bitmap, val table: DungeonTileTable)

    private val sets = HashMap<Int, Loaded?>()
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }   // pixel art nitido
    private val src = Rect()
    private val dst = Rect()

    private fun load(ts: Int): Loaded? = sets.getOrPut(ts) {
        try {
            val bmp = assets.open("tiles/ts$ts.png").use { BitmapFactory.decodeStream(it) } ?: return@getOrPut null
            val json = assets.open("tiles/ts$ts.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            Loaded(bmp, DungeonTileTable.parse(json))
        } catch (_: Exception) { null }
    }

    /** true si hay texturas para el suelo [floorTileName] (se puede usar [drawCells]). */
    fun available(floorTileName: String): Boolean = DungeonTilePicker.tilesetFor(floorTileName)?.let { load(it) } != null

    /**
     * Dibuja las casillas [firstCol]..[lastCol] x [firstRow]..[lastRow] de [map], cada una de lado
     * [tilePx], con su origen en el (0, 0) del lienzo (la camara ya esta aplicada fuera). Los
     * rectangulos salen de redondear las coordenadas ABSOLUTAS de las esquinas, asi dos casillas
     * contiguas comparten exactamente el mismo borde y no dejan rendijas. false si no hay texturas.
     */
    fun drawCells(
        c: Canvas, map: DungeonState.DungeonMap, tilePx: Float,
        firstCol: Int, lastCol: Int, firstRow: Int, lastRow: Int
    ): Boolean {
        val ts = DungeonTilePicker.tilesetFor(map.floorTileName) ?: return false
        val set = load(ts) ?: return false
        val wall = { ax: Int, ay: Int -> map.isWall(ax, ay) }
        val px = DungeonTilePicker.TILE_PX
        for (y in firstRow..lastRow) for (x in firstCol..lastCol) {
            val i = set.table.piece(wall(x, y), DungeonTilePicker.neighbours(x, y, wall), DungeonTilePicker.variation(x, y))
            val col = i % DungeonTilePicker.ATLAS_COLS; val row = i / DungeonTilePicker.ATLAS_COLS
            src.set(col * px, row * px, (col + 1) * px, (row + 1) * px)
            dst.set((x * tilePx).roundToInt(), (y * tilePx).roundToInt(), ((x + 1) * tilePx).roundToInt(), ((y + 1) * tilePx).roundToInt())
            c.drawBitmap(set.bmp, src, dst, paint)
        }
        return true
    }
}
