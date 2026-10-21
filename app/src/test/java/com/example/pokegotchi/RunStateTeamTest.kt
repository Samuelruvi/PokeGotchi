package com.example.pokegotchi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logica pura del equipo de la mazmorra (RunState): quien va delante, relevo al caer y derrota solo con todos caidos. */
class RunStateTeamTest {

    private fun map() = DungeonState.DungeonMap(
        width = 3, height = 3, walls = BooleanArray(9), startX = 0, startY = 0, exitX = 2, exitY = 2,
        enemies = mutableListOf(), items = mutableListOf()
    )

    private fun run(leader: Float, vararg companions: Float) = DungeonSimulator.RunState(
        floor = 1, hp = leader, map = map(), px = 0, py = 0,
        companions = companions.indices.map { "c$it" to false }, companionHps = companions.toList()
    )

    @Test fun soloExplorerIsTheFront() {
        val r = run(10f)
        assertEquals(1, r.teamSize)
        assertEquals(0, r.frontIndex())
        assertEquals(10f, r.hp, 0f)
        assertFalse(r.isDefeated())
    }

    @Test fun leaderFrontsWhileAlive() {
        val r = run(10f, 20f, 30f)
        assertEquals(3, r.teamSize)
        assertEquals(0, r.frontIndex())
        assertEquals(listOf(10f, 20f, 30f), r.teamHps())
    }

    @Test fun relayPassesToTheNextAliveMemberInOrder() {
        val r = run(0f, 0f, 30f)
        assertEquals(2, r.frontIndex())
        assertEquals(30f, r.hp, 0f)
        val r2 = run(0f, 20f, 30f)
        assertEquals(1, r2.frontIndex())
    }

    @Test fun hpSetterOnlyTouchesTheFrontMember() {
        val r = run(10f, 20f, 30f)
        r.hp = 5f
        assertEquals(listOf(5f, 20f, 30f), r.teamHps())
        r.hp = 0f   // cae el de delante: ahora va el segundo y las curaciones van a el
        assertEquals(1, r.frontIndex())
        r.hp = 50f
        assertEquals(listOf(0f, 50f, 30f), r.teamHps())
    }

    @Test fun defeatedOnlyWhenEveryoneFell() {
        val r = run(0f, 0f, 1f)
        assertFalse(r.isDefeated())
        r.hp = 0f
        assertTrue(r.isDefeated())
        assertEquals(-1, r.frontIndex())
        assertEquals(0f, r.hp, 0f)
        r.hp = 99f   // sin nadie en pie, una "curacion" no resucita a nadie
        assertTrue(r.isDefeated())
    }

    @Test fun followersAreTheAliveOnesBehindTheFront() {
        val r = run(10f, 20f, 0f)
        assertEquals(2, r.aliveCount())
        assertEquals(1, r.aliveFollowers())          // una casilla de fila: los caidos no ocupan
        assertEquals(2, run(10f, 20f, 30f).aliveFollowers())
        assertEquals(0, run(10f).aliveFollowers())
        assertEquals(0, run(0f, 0f).aliveFollowers()) // nadie en pie: sin fila
    }

    @Test fun missingCompanionHpsStartAtZeroAndSetTeamHpsRestoresThem() {
        val r = DungeonSimulator.RunState(1, 10f, map(), 0, 0, companions = listOf("a" to false, "b" to false))
        assertEquals(listOf(10f, 0f, 0f), r.teamHps())
        r.setTeamHps(listOf(7f, 8f, 9f, 99f))   // el valor de sobra se ignora
        assertEquals(listOf(7f, 8f, 9f), r.teamHps())
    }
}
