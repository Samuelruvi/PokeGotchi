package com.example.pokegotchi

import kotlin.math.abs

/**
 * Geometría del combate de equipo en la mazmorra (lógica pura, con tests JVM: TeamCombatGeometryTest):
 * quién puede golpear a quién y cómo se acerca al enemigo el que no llega. Pedido del usuario: "si el compañero no
 * puede pegar, no pega; en una sala amplia, el compañero se mueve para acercarse al enemigo" (en un pasillo de una
 * casilla de ancho el segundo de la fila no llega al enemigo y no golpea).
 */
object TeamCombatGeometry {

    /**
     * ¿Puede alguien en ([ax],[ay]) golpear a otro en ([bx],[by])? Casillas contiguas en 8 direcciones; una diagonal solo
     * cuenta si las dos casillas de la esquina están libres (no se pega a través de la esquina de una pared, como ya
     * pasa al andar). La misma casilla cuenta (el enemigo pisó a ese miembro).
     */
    fun canStrike(isWall: (Int, Int) -> Boolean, ax: Int, ay: Int, bx: Int, by: Int): Boolean {
        val dx = bx - ax; val dy = by - ay
        if (abs(dx) > 1 || abs(dy) > 1) return false
        if (dx != 0 && dy != 0) return !isWall(ax + dx, ay) && !isWall(ax, ay + dy)
        return true
    }

    /** Vecinas transitables de ([x],[y]) en 8 direcciones (la diagonal solo si las dos casillas de la esquina están libres). */
    private fun neighbors(isWall: (Int, Int) -> Boolean, x: Int, y: Int): List<Pair<Int, Int>> {
        val n = !isWall(x, y - 1); val s = !isWall(x, y + 1); val w = !isWall(x - 1, y); val e = !isWall(x + 1, y)
        val out = ArrayList<Pair<Int, Int>>(8)
        if (n) out.add(x to y - 1)
        if (s) out.add(x to y + 1)
        if (w) out.add(x - 1 to y)
        if (e) out.add(x + 1 to y)
        if (n && w && !isWall(x - 1, y - 1)) out.add(x - 1 to y - 1)
        if (n && e && !isWall(x + 1, y - 1)) out.add(x + 1 to y - 1)
        if (s && w && !isWall(x - 1, y + 1)) out.add(x - 1 to y + 1)
        if (s && e && !isWall(x + 1, y + 1)) out.add(x + 1 to y + 1)
        return out
    }

    /**
     * Primer paso del camino más corto desde [from] hasta una casilla desde la que se pueda golpear a [target], sin pisar
     * paredes ni [blocked] (los demás del equipo, el propio enemigo y otros enemigos). Devuelve null si ya puede golpear
     * desde donde está o si no hay camino (p. ej. otro miembro le tapa el pasillo): entonces se queda quieto.
     * [maxSteps] acota la búsqueda.
     */
    fun approachStep(
        isWall: (Int, Int) -> Boolean, from: Pair<Int, Int>, target: Pair<Int, Int>, blocked: Set<Pair<Int, Int>>, maxSteps: Int = 40
    ): Pair<Int, Int>? {
        if (canStrike(isWall, from.first, from.second, target.first, target.second)) return null
        val parent = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val depth = HashMap<Pair<Int, Int>, Int>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(from); depth[from] = 0
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = depth.getValue(cur)
            if (d >= maxSteps) continue
            for (nb in neighbors(isWall, cur.first, cur.second)) {
                if (nb in depth || nb in blocked || nb == target) continue
                depth[nb] = d + 1; parent[nb] = cur
                if (canStrike(isWall, nb.first, nb.second, target.first, target.second)) {
                    var step = nb
                    while (parent.getValue(step) != from) step = parent.getValue(step)
                    return step
                }
                queue.add(nb)
            }
        }
        return null
    }
}
