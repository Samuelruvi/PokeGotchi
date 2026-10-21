package com.example.pokegotchi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamCombatGeometryTest {

    /** Mapa de texto: '#' pared, cualquier otra cosa suelo. */
    private fun wallsOf(vararg rows: String): (Int, Int) -> Boolean = { x, y ->
        y !in rows.indices || x !in rows[y].indices || rows[y][x] == '#'
    }

    // Pasillo de UNA casilla de ancho, de izquierda a derecha.
    private val corridor = wallsOf(
        "#########",
        "#.......#",
        "#########"
    )

    // Sala abierta de 7x5.
    private val room = wallsOf(
        "#########",
        "#.......#",
        "#.......#",
        "#.......#",
        "#.......#",
        "#.......#",
        "#########"
    )

    @Test fun adjacentTilesCanStrikeIncludingDiagonalsInTheOpen() {
        assertTrue(TeamCombatGeometry.canStrike(room, 3, 3, 4, 3))
        assertTrue(TeamCombatGeometry.canStrike(room, 3, 3, 4, 4))
        assertTrue(TeamCombatGeometry.canStrike(room, 3, 3, 3, 3))   // el enemigo pisó a este miembro
    }

    @Test fun twoTilesApartCannotStrikeInACorridor() {
        assertTrue(TeamCombatGeometry.canStrike(corridor, 3, 1, 4, 1))
        assertFalse(TeamCombatGeometry.canStrike(corridor, 2, 1, 4, 1))   // el caso del usuario: a dos bloques no pega
        assertFalse(TeamCombatGeometry.canStrike(room, 1, 1, 4, 4))
    }

    @Test fun noStrikingAcrossAWallCorner() {
        val corner = wallsOf(
            "#####",
            "#.#.#",
            "#...#",   // la diagonal (1,1)-(2,2) no vale si (2,1) es pared
            "#####"
        )
        assertFalse(TeamCombatGeometry.canStrike(corner, 1, 1, 2, 2))
        assertTrue(TeamCombatGeometry.canStrike(corner, 1, 2, 2, 2))
    }

    @Test fun someoneWhoAlreadyReachesDoesNotMove() {
        assertNull(TeamCombatGeometry.approachStep(room, 3 to 3, 4 to 3, emptySet()))
    }

    @Test fun inARoomTheOneWhoDoesNotReachWalksTowardTheEnemy() {
        val step = TeamCombatGeometry.approachStep(room, 1 to 1, 5 to 3, emptySet())!!
        // Un paso (en 8 direcciones) que lo acerca: dista menos del enemigo que antes.
        val before = maxOf(Math.abs(1 - 5), Math.abs(1 - 3))
        val after = maxOf(Math.abs(step.first - 5), Math.abs(step.second - 3))
        assertTrue("de $before a $after", after < before)
        assertTrue(Math.abs(step.first - 1) <= 1 && Math.abs(step.second - 1) <= 1)
    }

    @Test fun inACorridorTheSecondOneBehindCannotPassTheFirst() {
        // Enemigo en (5,1); el primero en (4,1) golpea; el segundo en (3,1) no llega y el pasillo esta tapado.
        val blocked = setOf(4 to 1, 5 to 1)
        assertNull(TeamCombatGeometry.approachStep(corridor, 3 to 1, 5 to 1, blocked))
        assertFalse(TeamCombatGeometry.canStrike(corridor, 3, 1, 5, 1))
    }

    @Test fun neverStepsOnBlockedTilesAndReachesAFreeSpotNextToTheEnemy() {
        // Sala: dos compañeros ya pegados al enemigo; el tercero busca hueco libre.
        val enemy = 4 to 3
        val blocked = setOf(3 to 3, 3 to 2, 4 to 3)
        var pos = 1 to 1
        repeat(10) {
            val step = TeamCombatGeometry.approachStep(room, pos, enemy, blocked) ?: return@repeat
            assertFalse("pisó una casilla ocupada: $step", step in blocked)
            pos = step
        }
        assertTrue(TeamCombatGeometry.canStrike(room, pos.first, pos.second, enemy.first, enemy.second))
        assertFalse(pos in blocked)
    }

    @Test fun noPathMeansStandingStill() {
        val sealed = wallsOf(
            "#######",
            "#.#...#",
            "#######"
        )
        assertNull(TeamCombatGeometry.approachStep(sealed, 1 to 1, 4 to 1, emptySet()))
    }
}
