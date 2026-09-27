package com.example.pokegotchi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Dibuja el mapa de la mazmorra (DungeonState.DungeonMap) a un Bitmap - pedido explicito del
 * usuario tras ver solo un sprite fijo sobre fondo gris: "que se vea el mapa real... en verde el
 * Pokemon activo, en rojo los Pokemon enemigos, en amarillo los objetos... y la escalera de
 * subida y la inicial". Una foto fija por render (nunca un bucle de animacion - RemoteViews no
 * puede permitirselo, ver plan) que solo cambia de verdad cuando el explorador se mueve/pisa
 * algo o sube de piso - encaja bien con que el widget solo se repinta una vez por tick (~15 min).
 */
object DungeonMapRenderer {
    private const val TILE = 22
    private const val C_WALL = 0xFF232028.toInt()
    private const val C_FLOOR = 0xFF716C7A.toInt()
    private const val C_FLOOR_EDGE = 0xFF5D5866.toInt()
    private const val C_START = 0xFF3E7CB1.toInt()
    private const val C_EXIT = 0xFFC9A227.toInt()
    private const val C_PLAYER = 0xFF3CD34C.toInt()
    private const val C_ENEMY = 0xFFE0393E.toInt()
    private const val C_ITEM = 0xFFF4D93E.toInt()

    fun render(map: DungeonState.DungeonMap, playerX: Int, playerY: Int): Bitmap {
        val w = map.width * TILE; val h = map.height * TILE
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val fill = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

        for (y in 0 until map.height) for (x in 0 until map.width) {
            fill.color = if (map.isWall(x, y)) C_WALL else C_FLOOR
            cv.drawRect(x * TILE.toFloat(), y * TILE.toFloat(), (x + 1) * TILE.toFloat(), (y + 1) * TILE.toFloat(), fill)
        }
        // Reticula sutil sobre el suelo (para que se note la casilla, no un bloque liso).
        fill.color = C_FLOOR_EDGE
        for (y in 0 until map.height) for (x in 0 until map.width) {
            if (map.isWall(x, y)) continue
            cv.drawRect(x * TILE.toFloat(), y * TILE.toFloat(), (x + 1) * TILE.toFloat(), y * TILE + 1.5f, fill)
            cv.drawRect(x * TILE.toFloat(), y * TILE.toFloat(), x * TILE + 1.5f, (y + 1) * TILE.toFloat(), fill)
        }

        fill.color = C_START
        cv.drawRect(map.startX * TILE.toFloat(), map.startY * TILE.toFloat(), (map.startX + 1) * TILE.toFloat(), (map.startY + 1) * TILE.toFloat(), fill)
        fill.color = C_EXIT
        cv.drawRect(map.exitX * TILE.toFloat(), map.exitY * TILE.toFloat(), (map.exitX + 1) * TILE.toFloat(), (map.exitY + 1) * TILE.toFloat(), fill)

        fun dot(x: Int, y: Int, color: Int, radiusFactor: Float) {
            fill.color = color
            cv.drawCircle(x * TILE + TILE / 2f, y * TILE + TILE / 2f, TILE * radiusFactor, fill)
        }
        for (item in map.items) dot(item.x, item.y, C_ITEM, 0.24f)
        for (enemy in map.enemies) dot(enemy.x, enemy.y, C_ENEMY, 0.32f)
        dot(playerX, playerY, C_PLAYER, 0.36f)

        return bmp
    }
}
