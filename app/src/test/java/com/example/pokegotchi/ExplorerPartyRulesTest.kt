package com.example.pokegotchi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerPartyRulesTest {

    @Test
    fun companionsFollowTheAgreedLevels() {
        val expected = mapOf(1 to 0, 9 to 0, 10 to 1, 14 to 1, 15 to 2, 100 to 2)
        for ((level, n) in expected) assertEquals("nivel $level", n, ExplorerPartyRules.maxCompanions(level))
    }

    @Test
    fun neverMoreThanTheCap() {
        assertEquals(ExplorerPartyRules.MAX_COMPANIONS, ExplorerPartyRules.maxCompanions(10_000))
    }

    @Test
    fun levelForCompanionMatchesMaxCompanions() {
        for (n in 1..ExplorerPartyRules.MAX_COMPANIONS) {
            val level = ExplorerPartyRules.levelForCompanion(n)!!
            assertEquals(n, ExplorerPartyRules.maxCompanions(level))
            assertEquals(n - 1, ExplorerPartyRules.maxCompanions(level - 1))
        }
        assertNull(ExplorerPartyRules.levelForCompanion(0))
        assertNull(ExplorerPartyRules.levelForCompanion(3))
    }

    @Test
    fun theShareAddsUpToWhatWasEarned() {
        for (members in 1..3) {
            val share = ExplorerPartyRules.sharePerMember(30f, members)
            assertEquals(30f, share * members, 1e-4f)
        }
        assertEquals(10f, ExplorerPartyRules.sharePerMember(30f, 3), 1e-6f)
        // Un grupo "vacio" no divide por cero.
        assertEquals(30f, ExplorerPartyRules.sharePerMember(30f, 0), 1e-6f)
    }

    @Test
    fun moreFightersMeanMoreEnemies() {
        assertEquals(1f, ExplorerPartyRules.enemyMultiplier(1), 0f)
        assertTrue(ExplorerPartyRules.enemyMultiplier(2) > 1f)
        assertTrue(ExplorerPartyRules.enemyMultiplier(3) >= ExplorerPartyRules.enemyMultiplier(2))
        // Solo no cambia nada, ni con la peor tirada; con equipo nunca baja del numero base y crece con el equipo.
        for (base in 1..5) for (roll in listOf(0f, 0.5f, 0.999f)) {
            assertEquals(base, ExplorerPartyRules.scaledEnemyCount(base, 1, roll))
            assertTrue(ExplorerPartyRules.scaledEnemyCount(base, 2, roll) >= base)
            assertTrue(ExplorerPartyRules.scaledEnemyCount(base, 3, roll) >= ExplorerPartyRules.scaledEnemyCount(base, 2, roll))
        }
        // El redondeo probabilistico: la media sobre muchas tiradas es base x multiplicador (aqui con 3 Pokemon).
        var sum = 0
        val rolls = 1000
        for (i in 0 until rolls) sum += ExplorerPartyRules.scaledEnemyCount(1, 3, (i + 0.5f) / rolls)
        assertEquals(ExplorerPartyRules.enemyMultiplier(3), sum.toFloat() / rolls, 0.01f)
    }

    @Test
    fun smallXpIsShownWithADecimalInsteadOfZero() {
        assertEquals("0,3", ExplorerPartyRules.formatXp(ExplorerPartyRules.sharePerMember(1f, 3)))   // un caramelo de ~1 XP entre tres
        assertEquals("0,5", ExplorerPartyRules.formatXp(0.5f))
        assertEquals("1", ExplorerPartyRules.formatXp(1.0f))
        assertEquals("9,6", ExplorerPartyRules.formatXp(9.6f))
        assertEquals("12", ExplorerPartyRules.formatXp(12.7f))
    }

    private val max = listOf(100f, 100f, 100f)

    @Test
    fun frontStaysWhileAboveTheLimit() {
        assertEquals(0, ExplorerPartyRules.nextFront(listOf(100f, 100f, 100f), max, 0))
        assertEquals(0, ExplorerPartyRules.nextFront(listOf(25f, 100f, 100f), max, 0))   // justo el 25 %: aguanta
        assertEquals(1, ExplorerPartyRules.nextFront(listOf(5f, 30f, 90f), max, 1))      // el actual es otro, con 30 %: aguanta
    }

    @Test
    fun frontChangesBelowTwentyFivePercentToTheHealthiestOne() {
        assertEquals(2, ExplorerPartyRules.nextFront(listOf(24f, 60f, 90f), max, 0))
        assertEquals(1, ExplorerPartyRules.nextFront(listOf(24f, 60f, 30f), max, 0))
        // Se compara la FRACCION, no la vida suelta: 50/200 = 25 % no supera al 60 % de 60/100.
        assertEquals(1, ExplorerPartyRules.nextFront(listOf(10f, 60f, 50f), listOf(100f, 100f, 200f), 0))
    }

    @Test
    fun noOneHealthyKeepsTheCurrentOneAliveAndRelaysWhenItFalls() {
        // Todos por debajo del 25 %: cambiar a otro igual de tocado no arregla nada.
        assertEquals(0, ExplorerPartyRules.nextFront(listOf(20f, 10f, 15f), max, 0))
        // Pero si el actual cae, entra el que mejor esté.
        assertEquals(2, ExplorerPartyRules.nextFront(listOf(0f, 10f, 15f), max, 0))
    }

    @Test
    fun frontIsNeverAFallenOneAndNoOneAliveMeansMinusOne() {
        assertEquals(1, ExplorerPartyRules.nextFront(listOf(0f, 50f, 0f), max, 0))
        assertEquals(-1, ExplorerPartyRules.nextFront(listOf(0f, 0f, 0f), max, 0))
        assertEquals(0, ExplorerPartyRules.nextFront(listOf(50f), listOf(100f), 7))   // indice actual invalido
    }

    @Test
    fun onlyEligibleMembersCanTakeTheFrontWhileTheCurrentOneIsStillAlive() {
        // El de delante (0) esta al 10 %; el mejor (2) no esta pegado a el: entra el 1 (30 %, el unico elegible).
        assertEquals(1, ExplorerPartyRules.nextFront(listOf(10f, 30f, 90f), max, 0) { it == 1 })
        // Ninguno elegible: se queda el actual aunque este bajo.
        assertEquals(0, ExplorerPartyRules.nextFront(listOf(10f, 30f, 90f), max, 0) { false })
        // Si el de delante ya cayo, la elegibilidad no cuenta: entra el mejor.
        assertEquals(2, ExplorerPartyRules.nextFront(listOf(0f, 30f, 90f), max, 0) { false })
    }

    @Test
    fun itemsGoToTheMostHurtAliveMember() {
        assertEquals(1, ExplorerPartyRules.mostHurtAlive(listOf(80f, 20f, 50f), max))
        assertEquals(2, ExplorerPartyRules.mostHurtAlive(listOf(80f, 0f, 50f), max))   // el caido no cuenta
        assertEquals(-1, ExplorerPartyRules.mostHurtAlive(listOf(0f, 0f), listOf(100f, 100f)))
    }

    @Test
    fun followersFollowInASnake() {
        val tiles = listOf(1 to 1, 2 to 2)
        assertEquals(listOf(5 to 5, 1 to 1), ExplorerPartyRules.followTheLeader(tiles, 5 to 5))
        assertEquals(listOf(5 to 5), ExplorerPartyRules.followTheLeader(listOf(1 to 1), 5 to 5))
        assertEquals(emptyList<Pair<Int, Int>>(), ExplorerPartyRules.followTheLeader(emptyList(), 5 to 5))
    }

    @Test
    fun leaderStepsOnAFollowerAndTheyChangePlaces() {
        // El lider vuelve sobre sus pasos: de (5,5) a (1,1), donde esta el primer compañero.
        assertEquals(listOf(5 to 5, 2 to 2), ExplorerPartyRules.followTheLeader(listOf(1 to 1, 2 to 2), 5 to 5, 1 to 1))
        // Pisa al segundo: solo ese pasa al sitio que deja el lider; el primero no se mueve.
        assertEquals(listOf(1 to 1, 5 to 5), ExplorerPartyRules.followTheLeader(listOf(1 to 1, 2 to 2), 5 to 5, 2 to 2))
        // Un paso normal (destino libre) sigue siendo la serpiente de siempre.
        assertEquals(listOf(5 to 5, 1 to 1), ExplorerPartyRules.followTheLeader(listOf(1 to 1, 2 to 2), 5 to 5, 9 to 9))
    }

    @Test
    fun noTwoFollowersNorTheLeaderEverShareATile() {
        // Recorrido al azar sobre una rejilla pequeña: tras cada paso las casillas son todas distintas.
        val rnd = java.util.Random(7)
        var leader = 3 to 3
        var tiles = listOf(2 to 3, 1 to 3)
        repeat(500) {
            val to = (leader.first + rnd.nextInt(3) - 1) to (leader.second + rnd.nextInt(3) - 1)
            if (to == leader) return@repeat
            tiles = ExplorerPartyRules.followTheLeader(tiles, leader, to)
            leader = to
            val all = tiles + leader
            assertEquals("paso $it: $all", all.size, all.toSet().size)
        }
    }

    @Test
    fun aFollowerLeftAheadOfTheLeaderAfterACombatDoesNotTeleportBackwards() {
        // Tras un combate un compañero quedo delante del lider (QaDungeon: salto de 3 casillas): lider (12,14)->(13,14), compañeros
        // [(11,14) detras, (14,14) delante]. La serpiente de siempre mandaba al segundo a (11,14); ahora se queda donde esta.
        val tiles = listOf(11 to 14, 14 to 14)
        assertEquals(listOf(12 to 14, 11 to 14), ExplorerPartyRules.followTheLeader(tiles, 12 to 14, 13 to 14))
        assertEquals(listOf(12 to 14, 14 to 14), ExplorerPartyRules.followTheLeaderTiles(tiles, 12 to 14, 13 to 14))
        // Una fila normal (contigua) se comporta igual que la serpiente de siempre.
        assertEquals(listOf(5 to 5, 4 to 5), ExplorerPartyRules.followTheLeaderTiles(listOf(4 to 5, 3 to 5), 5 to 5, 6 to 5))
        // Si el lider pisa a un compañero se cambian de sitio, tambien igual que antes.
        assertEquals(listOf(1 to 1, 5 to 5), ExplorerPartyRules.followTheLeaderTiles(listOf(1 to 1, 2 to 2), 5 to 5, 2 to 2))
    }

    @Test
    fun followerTilesNeverCollideOnARandomWalkFromAScatteredStart() {
        val rnd = java.util.Random(11)
        repeat(200) { trial ->
            var leader = 10 to 10
            // Formacion dispersa como la que deja un combate: alrededor del lider, algunos delante.
            var tiles = listOf(10 + rnd.nextInt(5) - 2 to 10 + rnd.nextInt(5) - 2, 10 + rnd.nextInt(5) - 2 to 10 + rnd.nextInt(5) - 2)
                .filter { it != leader }.distinct()
            repeat(60) { step ->
                val to = (leader.first + rnd.nextInt(3) - 1) to (leader.second + rnd.nextInt(3) - 1)
                if (to == leader) return@repeat
                val next = ExplorerPartyRules.followTheLeaderTiles(tiles, leader, to)
                val all = next + to
                assertEquals("prueba $trial paso $step: $all", all.size, all.toSet().size)
                leader = to; tiles = next
            }
        }
    }

    // ---- Los enemigos van a por cualquiera del equipo ----

    @Test
    fun chaseTarget_goesForTheNearestMemberNotJustTheLeader() {
        // Lider a 8 casillas, compañero a 2: persigue al compañero (indice 1).
        assertEquals(1, ExplorerPartyRules.chaseTarget(10 to 6, listOf(2 to 6, 8 to 6)))
        // Lider a 2, compañero a 8: persigue al lider.
        assertEquals(0, ExplorerPartyRules.chaseTarget(4 to 6, listOf(2 to 6, 12 to 6)))
        // El tercero de la fila es el mas cercano.
        assertEquals(2, ExplorerPartyRules.chaseTarget(6 to 3, listOf(2 to 3, 12 to 3, 6 to 5)))
    }

    @Test
    fun chaseTarget_tieGoesToTheFirstAndNobodyBeyondTheRadius() {
        assertEquals(0, ExplorerPartyRules.chaseTarget(6 to 6, listOf(3 to 6, 9 to 6)))
        // Justo en el radio (3) persigue; a 4, no.
        assertEquals(0, ExplorerPartyRules.chaseTarget(6 to 6, listOf(3 to 6)))
        assertNull(ExplorerPartyRules.chaseTarget(6 to 6, listOf(2 to 6, 10 to 6)))
        assertNull(ExplorerPartyRules.chaseTarget(6 to 6, emptyList()))
    }

    @Test
    fun enemyStrikeTarget_firstStrikeGoesToWhoItReachedThenAnyoneTouching() {
        // El primer golpe, al alcanzado si lo toca.
        assertEquals(2, ExplorerPartyRules.enemyStrikeTarget(listOf(0, 1, 2), preferred = 2, pick = { 0 }))
        // Si el alcanzado no lo toca (se aparto), al azar entre los que si.
        assertEquals(1, ExplorerPartyRules.enemyStrikeTarget(listOf(0, 1), preferred = 2, pick = { 1 }))
        // Sin preferido: cualquiera de los que lo tocan, y solo ellos.
        val seen = (0 until 3).map { k -> ExplorerPartyRules.enemyStrikeTarget(listOf(0, 2, 3), pick = { k }) }
        assertEquals(listOf(0, 2, 3), seen)
        // Nadie lo toca: no golpea.
        assertEquals(-1, ExplorerPartyRules.enemyStrikeTarget(emptyList(), preferred = 1))
        // Un pick fuera de rango no revienta.
        assertEquals(3, ExplorerPartyRules.enemyStrikeTarget(listOf(0, 2, 3), pick = { 99 }))
    }
}
