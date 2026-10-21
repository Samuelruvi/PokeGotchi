package com.example.pokegotchi

import android.content.Context
import org.json.JSONArray
import kotlin.random.Random

/**
 * SOLO PRUEBAS (cuenta SECUNDARIA): arnes que provoca combinaciones raras de estados con la API real del juego y comprueba
 * invariantes (ver docs/PLAN_QA_CUENTA_SECUNDARIA.md). Se lanza por broadcast (PokeWidgetProvider.ACTION_DEBUG_QA) con `--user 999`.
 *
 * SE NIEGA A CORRER en la cuenta principal (usuario Android 0): `Process.myUid() / 100000` es el id de usuario, y esta herramienta
 * BORRA todo el estado del juego al empezar cada caso. Nunca debe poder ejecutarse sobre la partida real.
 */
object QaHarness {

    class Dex(val id: Int, val name: String, val gen: Int)
    class Evo(val id: Int, val name: String, val from: String?, val to: List<PetState.EvoOption>, val rare: Boolean)

    class Reporter {
        val lines = ArrayList<String>()
        var graves = 0; var medias = 0; var bajas = 0
        fun line(s: String) { lines.add(s) }
        fun ok(suite: String, msg: String) { lines.add("  OK    [$suite] $msg") }
        fun info(suite: String, msg: String) { lines.add("  ·     [$suite] $msg") }
        fun problem(sev: String, suite: String, msg: String) {
            when (sev) { "GRAVE" -> graves++; "MEDIA" -> medias++; else -> bajas++ }
            lines.add("  $sev [$suite] $msg")
        }
    }

    /** true si este proceso corre en una cuenta que NO es la principal (usuario 0). */
    fun isSecondaryUser(): Boolean = android.os.Process.myUid() / 100000 != 0

    /** Entorno de pruebas: atajos para montar escenarios y leer el estado. */
    class T(val c: Context, val rnd: Random, val r: Reporter) {
        val dex: List<Dex> = parseDex(c)
        val evo: Map<String, Evo> = parseEvo(c)
        private val idOf: Map<String, Int> = dex.associate { it.name to it.id }

        fun id(name: String): Int = idOf[name] ?: evo[name]?.id ?: error("especie desconocida: $name")
        fun who(name: String, shiny: Boolean) = if (shiny) "$name✨" else name

        /** Borra TODO el estado del juego de esta cuenta y deja a Pikachu como inicial (activo). Nivel de entrenador maximo 50. */
        fun wipe() {
            for (n in listOf("pokegotchi_state", "pokegotchi_dungeon_state")) c.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit()
            PetState.setPokemon(c, "pikachu", 25, false)
            PetState.markStarterChosen(c)
            PetState.debugSetTrainerLevelMax(c, 50)
        }

        /** Crea el individuo [name] (normal/shiny) con el nivel [level]. No lo pone de activo. */
        fun give(name: String, shiny: Boolean = false, level: Int = 1) {
            val existed = PetState.hasIndividual(c, name, shiny) && !PetState.hasEvolvedAway(c, name, shiny)
            PetState.rollFreshIndividual(c, name, shiny, id(name))
            // Si ya existia NO se toca su XP (la prueba no debe ser quien le quite progreso).
            if (!existed && level > 1) PetState.debugSetIndividualStats(c, name, shiny, xp = PetState.xpForLevel(c, level, name) + 1f)
        }

        fun setLevel(name: String, shiny: Boolean, level: Int) {
            PetState.debugSetIndividualStats(c, name, shiny, xp = PetState.xpForLevel(c, level, name) + 1f)
        }

        fun setActive(name: String, shiny: Boolean = false) = PetState.setPokemon(c, name, id(name), shiny)
        fun xp(name: String, shiny: Boolean = false): Float? = PetState.rawStats(c, name, shiny)?.xp
        fun has(name: String, shiny: Boolean = false) = PetState.hasIndividual(c, name, shiny)
        fun away(name: String, shiny: Boolean = false) = PetState.hasEvolvedAway(c, name, shiny)
        fun alive(name: String, shiny: Boolean = false) = has(name, shiny) && !away(name, shiny)
        fun active(): Pair<String, Boolean> = PetState.currentPokemon(c) to PetState.isActiveShiny(c)
        fun st() = c.getSharedPreferences("pokegotchi_state", Context.MODE_PRIVATE)
        fun dst() = c.getSharedPreferences("pokegotchi_dungeon_state", Context.MODE_PRIVATE)

        /** Casos aislados: cada uno borra el estado, y cualquier excepcion se informa como problema (no tumba el resto). */
        fun case(suite: String, title: String, body: () -> Unit) {
            try { body() } catch (e: Throwable) {
                r.problem("GRAVE", suite, "$title -> EXCEPCION ${e.javaClass.simpleName}: ${e.message}  @ ${e.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }}")
            }
        }

        /** Violaciones de las invariantes globales ahora mismo (lista vacia = todo coherente). */
        fun invariants(): List<String> {
            val out = ArrayList<String>()
            val cur = active()
            if (PetState.hasChosenStarter(c)) {
                if (!has(cur.first, cur.second)) out.add("el activo ${who(cur.first, cur.second)} no tiene individuo")
                else if (away(cur.first, cur.second)) out.add("el activo ${who(cur.first, cur.second)} esta evolucionado-away")
            }
            val members = PetState.nurseryMembers(c)
            for ((n, s) in members) {
                if (!alive(n, s)) out.add("guarderia: miembro fantasma ${who(n, s)}")
                if (n == cur.first && s == cur.second) out.add("guarderia: el activo ${who(n, s)} esta dentro")
                if (DungeonState.isInParty(c, n, s)) out.add("${who(n, s)} esta a la vez en la guarderia y en la mazmorra")
            }
            if (members.size > PetState.nurseryCapacity(c)) out.add("guarderia: ${members.size} miembros para ${PetState.nurseryCapacity(c)} plazas")
            if (DungeonState.status(c) != DungeonState.STATUS_IDLE) {
                for ((n, s) in DungeonState.party(c)) {
                    if (!alive(n, s)) out.add("mazmorra: ${who(n, s)} esta en el grupo pero ya no existe / evoluciono")
                    if (n == cur.first && s == cur.second) out.add("mazmorra: ${who(n, s)} es el activo del widget y a la vez del grupo")
                }
            }
            for ((n, s) in PetState.ownedIndividuals(c)) {
                val stats = PetState.rawStats(c, n, s)
                if (stats == null) { out.add("${who(n, s)}: sin stats"); continue }
                if (stats.xp < 0f || stats.xp.isNaN()) out.add("${who(n, s)}: xp invalida ${stats.xp}")
                for ((label, v) in listOf("salud" to stats.health, "higiene" to stats.hygiene, "felicidad" to stats.happiness)) {
                    if (v < 0f || v > 100f || v.isNaN()) out.add("${who(n, s)}: $label fuera de rango $v")
                }
                val lvl = PetState.levelForPokemon(c, n, s)
                if (lvl == null || lvl !in 1..100) out.add("${who(n, s)}: nivel invalido $lvl")
            }
            if (PetState.candyCount(c) < 0) out.add("caramelos negativos ${PetState.candyCount(c)}")
            return out
        }

        fun reportInvariants(suite: String, label: String, sev: String = "MEDIA"): Boolean {
            val v = invariants()
            v.forEach { r.problem(sev, suite, "$label -> $it") }
            return v.isEmpty()
        }

        /** Mueve hacia atras los relojes de la guarderia (para simular ausencias largas sin esperar). */
        fun nurseryTimeTravel(ms: Long) {
            val e = st().edit()
            for (k in listOf("nursery_xp_last", "nursery_egg_last", "nursery_care_last")) e.putLong(k, st().getLong(k, System.currentTimeMillis()) - ms)
            e.commit()
        }

        fun evoOptionsOf(name: String): List<PetState.EvoOption> = evo[name]?.to ?: emptyList()
    }

    private fun parseDex(c: Context): List<Dex> {
        val arr = JSONArray(c.assets.open("pokedex.json").bufferedReader().use { it.readText() })
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); Dex(o.getInt("id"), o.getString("name"), o.getInt("gen")) }
    }

    private fun parseEvo(c: Context): Map<String, Evo> {
        val arr = JSONArray(c.assets.open("evolutions.json").bufferedReader().use { it.readText() })
        val map = LinkedHashMap<String, Evo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val toArr = o.getJSONArray("evolves_to")
            val opts = (0 until toArr.length()).map { j ->
                val e = toArr.getJSONObject(j); PetState.EvoOption(e.getInt("id"), e.getString("name"), e.getInt("level"), e.getString("method"))
            }
            map[o.getString("name")] = Evo(o.getInt("id"), o.getString("name"), if (o.isNull("evolves_from")) null else o.getString("evolves_from"), opts, o.optBoolean("rare", false))
        }
        return map
    }

    /** Punto de entrada. [suites]: lista de nombres (ver `when` de abajo). Devuelve el informe completo. */
    fun run(context: Context, suites: List<String>, seed: Long, n: Int): String {
        if (!isSecondaryUser()) return "QA BLOQUEADO: esta es la cuenta PRINCIPAL (usuario 0). Solo puede correr en la secundaria."
        val r = Reporter()
        val t = T(context, Random(seed), r)
        r.line("QA arrancado: suites=$suites seed=$seed n=$n uid=${android.os.Process.myUid()}")
        for (s in suites) {
            val t0 = System.currentTimeMillis()
            r.line("=== suite $s ===")
            try {
                when (s) {
                    "conflicts" -> QaConflicts.run(t)
                    "data" -> QaData.run(t)
                    "curves" -> QaData.curves(t)
                    "fuzz" -> QaFuzz.run(t, n)
                    "dungeon" -> QaDungeon.run(t, n)
                    "nursery" -> QaNursery.run(t, n)
                    "nurseryprep" -> QaNursery.prepareEvolutionScene(t)
                    "hungryprep" -> QaNursery.prepareHungryActive(t)
                    "forms" -> QaForms.run(t, n)
                    "populate" -> QaData.populate(t, n)
                    "dexfull" -> QaData.dexFull(t)
                    "ownall" -> QaData.ownAll(t)
                    "candies" -> QaData.candies(t)
                    "balance" -> QaDungeon.balance(t, n)
                    "perf" -> QaPerf.run(t, n)
                    "robust" -> QaRobust.run(t, n)
                    "cries" -> QaData.cries(t)
                    "evolutions" -> QaData.evolutions(t)
                    "sprites" -> QaData.sprites(t)
                    "offers" -> QaOffers.run(t, n)
                    else -> r.line("  suite desconocida: $s")
                }
            } catch (e: Throwable) {
                r.problem("GRAVE", s, "la suite entera lanzo ${e.javaClass.simpleName}: ${e.message}  @ ${e.stackTrace.take(5).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }}")
            }
            r.line("--- $s: ${System.currentTimeMillis() - t0} ms")
        }
        r.line("RESUMEN: ${r.graves} graves, ${r.medias} medias, ${r.bajas} bajas")
        return r.lines.joinToString("\n")
    }
}
