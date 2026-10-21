package com.example.pokegotchi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NurseryVisitorsTest {

    // Una tabla pequeña con los casos que importan: cadena de 3 (charmander), cria que evoluciona por amistad/piedra al mismo
    // nivel (pichu/pikachu/raichu), el dato raro de marill (evoluciona MAS TEMPRANO de lo que se alcanza), una especie suelta
    // fuerte (tauros), una debil (pachirisu) y un legendario.
    private fun sp(name: String, from: String?, evo: List<Pair<String, Int>>, rare: Boolean = false, strength: Int = 300) =
        NurseryVisitors.Sp(name, from, evo, rare, strength)

    private val table: Map<String, NurseryVisitors.Sp> = listOf(
        sp("charmander", null, listOf("charmeleon" to 16)),
        sp("charmeleon", "charmander", listOf("charizard" to 36)),
        sp("charizard", "charmeleon", emptyList()),
        sp("pichu", null, listOf("pikachu" to 25)),
        sp("pikachu", "pichu", listOf("raichu" to 25)),
        sp("raichu", "pikachu", emptyList()),
        sp("azurill", null, listOf("marill" to 25)),
        sp("marill", "azurill", listOf("azumarill" to 18)),
        sp("azumarill", "marill", emptyList()),
        sp("psyduck", null, listOf("golduck" to 33)),
        sp("golduck", "psyduck", emptyList()),
        sp("tauros", null, emptyList(), strength = 420),
        sp("pachirisu", null, emptyList(), strength = 315),
        sp("mewtwo", null, emptyList(), rare = true, strength = 600),
    ).associateBy { it.name }

    private val catalog = NurseryVisitors.catalog(table)

    private fun range(name: String) = catalog.ranges.getValue(name).let { it.min to it.max }

    @Test
    fun naturalRangesFollowTheEvolutionChain() {
        assertEquals(1 to 16, range("charmander"))
        assertEquals(16 to 36, range("charmeleon"))
        assertEquals(36 to 101, range("charizard"))
        assertEquals(1 to 25, range("pichu"))
    }

    @Test
    fun anEmptyStageGetsATenLevelSpan() {
        // Pikachu se alcanza a Nv25 y evoluciona a Nv25: sin arreglo la etapa estaria vacia.
        assertEquals(25 to 35, range("pikachu"))
        assertEquals(25 to 101, range("raichu"))
    }

    @Test
    fun aChainThatEvolvesEarlierThanItIsReachedStaysMonotonic() {
        // marill se alcanza a 25 y azumarill "a 18": azumarill nunca puede empezar antes que marill.
        assertEquals(25 to 35, range("marill"))
        assertEquals(25, range("azumarill").first)
    }

    @Test
    fun loneSpeciesStartAtTheirStrength() {
        assertEquals(42, range("tauros").first)
        assertEquals(16, range("pachirisu").first)
        assertEquals(1, NurseryVisitors.loneMin(100))
        assertEquals(60, NurseryVisitors.loneMin(900))
    }

    @Test
    fun aLowLevelPokemonNeverMeetsAnAdultOrAStrongSpecies() {
        for (slot in 0L until 600L) {
            val names = NurseryVisitors.pick(catalog, listOf(10), emptySet(), slot, 3).map { it.name }
            for (forbidden in listOf("charizard", "raichu", "azumarill", "tauros", "mewtwo")) assertFalse("$forbidden a Nv10 (franja $slot)", forbidden in names)
        }
    }

    @Test
    fun aHighLevelPokemonNeverMeetsABaby() {
        for (slot in 0L until 600L) {
            val names = NurseryVisitors.pick(catalog, listOf(60), emptySet(), slot, 3).map { it.name }
            // (pachirisu no entra en la lista: no tiene preevolucion ni evolucion, asi que a Nv60 es una especie adulta valida)
            for (forbidden in listOf("charmander", "pichu", "azurill", "charmeleon", "mewtwo")) assertFalse("$forbidden a Nv60 (franja $slot)", forbidden in names)
        }
    }

    @Test
    fun legendariesAndExcludedSpeciesNeverAppear() {
        for (slot in 0L until 600L) {
            val names = NurseryVisitors.pick(catalog, listOf(50, 50, 50), setOf("tauros"), slot, 3).map { it.name }
            assertFalse(names.toString(), "mewtwo" in names || "tauros" in names)
        }
    }

    @Test
    fun visitorLevelsStayNearTheReferenceAndInsideTheStage() {
        for (slot in 0L until 600L) for (ref in listOf(3, 12, 27, 44, 70, 99)) {
            for (v in NurseryVisitors.pick(catalog, listOf(ref), emptySet(), slot, 3)) {
                val r = catalog.ranges.getValue(v.name)
                assertTrue("${v.name} Nv${v.level} fuera de su etapa ${r.min}-${r.max}", v.level >= r.min && v.level < r.max)
                assertTrue("${v.name} Nv${v.level} lejos del Nv$ref", kotlin.math.abs(v.level - ref) <= NurseryVisitors.LEVEL_TOLERANCE + NurseryVisitors.LEVEL_BUCKET)
            }
        }
    }

    @Test
    fun aStageThatOnlyTouchesTheWindowGetsTheNearestLevelInsideIt() {
        // Psyduck (Nv1-32) con un miembro de Nv36: entra en la ventana (31-41) por un pelo; jamas debe salir con nivel 1.
        var seen = 0
        for (slot in 0L until 2000L) for (v in NurseryVisitors.pick(catalog, listOf(36), emptySet(), slot, 3)) if (v.name == "psyduck") {
            seen++
            assertTrue("psyduck Nv${v.level} con un miembro de Nv36", v.level in 28..32)
        }
        assertTrue("psyduck nunca salio: el caso no se ha probado", seen > 0)
    }

    @Test
    fun theSameSlotAlwaysGivesTheSameVisitorsAndANewSlotChangesThem() {
        val a = NurseryVisitors.pick(catalog, listOf(30), emptySet(), 1234L, 3)
        assertEquals(a, NurseryVisitors.pick(catalog, listOf(30), emptySet(), 1234L, 3))
        val distinct = (1000L until 1060L).map { NurseryVisitors.pick(catalog, listOf(30), emptySet(), it, 3) }.toSet()
        assertTrue("los visitantes no cambian entre franjas", distinct.size > 20)
    }

    @Test
    fun levelingUpInsideABucketDoesNotShuffleTheVisitors() {
        // 10, 11, 12 y 14 caen en la misma tanda de 5 (10..14): los mismos visitantes.
        val base = NurseryVisitors.pick(catalog, listOf(10), emptySet(), 77L, 3).map { it.name to it.level }
        for (lvl in listOf(11, 12, 14)) assertEquals(base, NurseryVisitors.pick(catalog, listOf(lvl), emptySet(), 77L, 3).map { it.name to it.level })
    }

    @Test
    fun visitorsAreDistinctAndTheirCountShrinksWithACrowdedGarden() {
        for (slot in 0L until 200L) {
            val v = NurseryVisitors.pick(catalog, listOf(30), emptySet(), slot, 3).map { it.name }
            assertEquals(v.toString(), v.toSet().size, v.size)
        }
        assertEquals(3, NurseryVisitors.countFor(0))
        assertEquals(3, NurseryVisitors.countFor(5))
        assertEquals(2, NurseryVisitors.countFor(6))
        assertEquals(1, NurseryVisitors.countFor(7))
        assertEquals(1, NurseryVisitors.countFor(20))
    }

    @Test
    fun noReferenceLevelMeansNoVisitors() {
        assertTrue(NurseryVisitors.pick(catalog, emptyList(), emptySet(), 1L, 3).isEmpty())
    }

    @Test
    fun speciesTheCallerCannotDrawAreSkipped() {
        for (slot in 0L until 300L) {
            val names = NurseryVisitors.pick(catalog, listOf(30), emptySet(), slot, 3, accept = { it != "pikachu" }).map { it.name }
            assertFalse("pikachu" in names)
        }
    }

    @Test
    fun slotTimeMath() {
        assertEquals(NurseryVisitors.slotOf(0L), NurseryVisitors.slotOf(NurseryVisitors.SLOT_MS - 1))
        assertNotEquals(NurseryVisitors.slotOf(0L), NurseryVisitors.slotOf(NurseryVisitors.SLOT_MS))
        assertEquals(NurseryVisitors.SLOT_MS, NurseryVisitors.msLeftInSlot(0L))
        assertEquals(1L, NurseryVisitors.msLeftInSlot(NurseryVisitors.SLOT_MS - 1))
    }
}
