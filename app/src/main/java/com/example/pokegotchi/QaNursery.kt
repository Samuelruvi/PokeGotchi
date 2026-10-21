package com.example.pokegotchi

import com.example.pokegotchi.QaHarness.T

/** Bloque D del plan: guarderia (XP por horas, necesidades, huevos, saltos de tiempo). SOLO PRUEBAS. */
object QaNursery {
    private const val S = "guarderia"
    private const val HOUR_MS = 3_600_000L

    fun run(t: T, n: Int) {
        t.case(S, "liquidaciones tras ausencias de distinta duracion") { longAbsences(t) }
        t.case(S, "reloj hacia atras / ancla corrupta") { clockOddities(t) }
        t.case(S, "liquidar muchas veces seguidas (sin ganancia espuria)") { repeatedSettles(t, n) }
        t.case(S, "dos hilos liquidando a la vez") { concurrentSettles(t) }
        t.case(S, "miembros a nivel 100") { maxedMembers(t) }
        t.case(S, "aviso de evolucion de los miembros") { evolutionAlert(t) }
        t.case(S, "visitantes: nivel parecido, sin legendarios, cambian cada 15 min") { visitors(t) }
    }

    /** Visitantes de la guarderia (pedido del usuario): con datos reales y la ruta real (PetState.nurseryVisitors). */
    private fun visitors(t: T) {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 50)
        PetState.setTrainerName(t.c, "QA")
        t.give("charmander", false, 5); t.setActive("charmander")
        val rare = t.evo.values.filter { it.rare }.map { it.name }.toSet()
        val tooStrongForLow = setOf("charizard", "blastoise", "venusaur", "raichu", "alakazam", "gengar", "dragonite", "tyranitar", "garchomp", "salamence", "metagross", "hydreigon", "goodra", "kommo-o", "snorlax", "lapras", "gyarados")
        val babies = setOf("pichu", "bulbasaur", "charmander", "squirtle", "caterpie", "weedle", "pidgey", "rattata", "magikarp", "togepi", "azurill", "riolu")
        val base = System.currentTimeMillis()
        val all = HashSet<String>()
        for (lvl in listOf(1, 5, 10, 16, 25, 36, 50, 70, 100)) {
            t.give("pichu", false, lvl); t.setLevel("pichu", false, lvl)
            PetState.nurseryAdd(t.c, "pichu", false)?.let { t.r.problem("MEDIA", S, "no entra pichu Nv$lvl: $it") }
            val seen = HashMap<String, Int>(); var bad = 0; var noSprite = 0; var sizes = 0; var nonDet = 0; var sameAsNext = 0
            var prev: List<NurseryVisitors.Visitor>? = null
            for (k in 0 until 300) {
                val now = base + k * NurseryVisitors.SLOT_MS
                val v = PetState.nurseryVisitors(t.c, now)
                if (v != PetState.nurseryVisitors(t.c, now + 1000L)) nonDet++   // misma franja -> mismos visitantes
                if (prev != null && prev == v) sameAsNext++
                prev = v; sizes += v.size
                for (x in v) {
                    seen.merge(x.name, 1, Int::plus); all.add(x.name)
                    if (x.name in rare) { bad++; t.r.problem("MEDIA", S, "visitante legendario/mitico ${x.name} (Nv$lvl)") }
                    if (x.name == "pichu") { bad++; t.r.problem("MEDIA", S, "el visitante es la especie de un miembro (pichu)") }
                    if (kotlin.math.abs(x.level - lvl) > 10) { bad++; t.r.problem("MEDIA", S, "${x.name} Nv${x.level} lejos del Nv$lvl de referencia") }
                    if (lvl <= 10 && x.name in tooStrongForLow) { bad++; t.r.problem("MEDIA", S, "${x.name} de visitante con un miembro de Nv$lvl") }
                    if (lvl >= 70 && x.name in babies) { bad++; t.r.problem("MEDIA", S, "${x.name} (cria) de visitante con un miembro de Nv$lvl") }
                    if (DungeonSpriteRepository.frames(t.c, x.name, false, DungeonSpriteRepository.Direction.DOWN).isNullOrEmpty()) { noSprite++; t.r.problem("MEDIA", S, "${x.name} sin sprite de mazmorra (visitante invisible)") }
                }
            }
            if (nonDet > 0) t.r.problem("MEDIA", S, "Nv$lvl: $nonDet franjas dieron visitantes distintos en la misma franja")
            if (sameAsNext > 15) t.r.problem("BAJA", S, "Nv$lvl: ${sameAsNext} de 299 franjas seguidas repiten exactamente los mismos visitantes")
            val top = seen.entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key}x${it.value}" }
            t.r.info(S, "miembro Nv$lvl: ${seen.size} especies distintas en 300 franjas, media ${"%.2f".format(sizes / 300.0)} visitantes/franja; ejemplos $top")
            if (bad == 0 && noSprite == 0 && nonDet == 0) t.r.ok(S, "visitantes con un miembro de Nv$lvl: nivel parecido, sin legendarios ni repetir al miembro, con sprite")
            PetState.nurseryRemove(t.c, "pichu", false)
        }
        t.r.info(S, "especies distintas que han salido como visitantes en total: ${all.size}")
        // Guarderia vacia: toma el nivel del activo; sin activo con nivel no hay visitantes (y no debe lanzar).
        val empty = PetState.nurseryVisitors(t.c, base)
        t.r.info(S, "guarderia vacia (activo charmander Nv5): visitantes ${empty.map { "${it.name} Nv${it.level}" }}")
        if (empty.isEmpty()) t.r.problem("BAJA", S, "con la guarderia vacia no hay visitantes aunque el activo tiene nivel")
        t.reportInvariants(S, "tras probar los visitantes")
    }

    /** Deja al activo con MUCHA necesidad de caricias (felicidad 20): una caricia sube 25 y el umbral minimo es 60, asi que tras una
     *  caricia SIEMPRE sigue necesitandolo -> sirve para comprobar dos pulsaciones seguidas del boton del widget (ver la prueba por adb en
     *  la memoria). No limpia nada. */
    fun prepareHungryActive(t: T) {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 50)
        PetState.setTrainerName(t.c, "QA")
        t.give("charmander", false, 5); t.setActive("charmander")
        PetState.debugSetIndividualStats(t.c, "charmander", false, health = 100f, hygiene = 100f, happiness = 20f)
        t.r.info(S, "activo charmander con felicidad 20 (tras una caricia sigue necesitando otra)")
    }

    /** Deja la cuenta con una guarderia para REVISAR A OJO el aviso de evolucion (volcado de la escena y de la tarjeta):
     *  bulbasaur Nv30 (una evolucion lista), eevee Nv30 (varias ramas listas), pidgey Nv5 (aun no), y en la coleccion un vaporeon
     *  vivo (para que eevee marque su Poke Ball). No limpia nada. */
    fun prepareEvolutionScene(t: T) {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 50)
        PetState.setTrainerName(t.c, "QA")   // sin perfil de entrenador MainActivity redirige al asistente y no se podria abrir la guarderia
        t.give("charmander", false, 5); t.setActive("charmander")
        t.give("vaporeon", false, 20)
        t.give("pikachu", false, 30); t.setLevel("pikachu", false, 30)   // pikachu -> raichu con raichu ya tenido: aviso de "Ya tienes a Raichu"
        t.give("raichu", false, 60); t.setLevel("raichu", false, 60)
        for ((n, lvl) in listOf("bulbasaur" to 30, "eevee" to 30, "pidgey" to 5)) {
            t.give(n, false, lvl); t.setLevel(n, false, lvl)
            PetState.nurseryAdd(t.c, n, false)?.let { t.r.problem("MEDIA", S, "no entra $n: $it") }
        }
        val ready = PetState.nurseryEvolvable(t.c).map { it.first }
        t.r.info(S, "guarderia preparada: ${PetState.nurseryMembers(t.c).map { it.first }}, pueden evolucionar $ready")
    }

    /** Pedido del usuario: la guarderia avisa cuando un miembro puede evolucionar (UNA vez, hasta que evolucione o salga). */
    private fun evolutionAlert(t: T) {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 50)
        t.give("charmander", false, 5); t.setActive("charmander")
        t.give("bulbasaur", false, 5)
        PetState.nurseryAdd(t.c, "bulbasaur", false)?.let { t.r.problem("MEDIA", S, "no entra bulbasaur: $it") }
        val r0 = PetState.settleNursery(t.c, force = true)
        if (r0?.evolvable?.isNotEmpty() == true || PetState.nurseryEvolvable(t.c).isNotEmpty()) t.r.problem("MEDIA", S, "bulbasaur Nv5 ya figura como evolucionable: ${r0?.evolvable} / ${PetState.nurseryEvolvable(t.c)}")
        else t.r.ok(S, "un miembro que aun no llega al nivel no avisa")
        t.setLevel("bulbasaur", false, 30)
        val r1 = PetState.settleNursery(t.c, force = true)
        if (r1?.evolvable != listOf("bulbasaur")) t.r.problem("MEDIA", S, "bulbasaur Nv30 en la guarderia: el informe deberia avisar de [bulbasaur] y trae ${r1?.evolvable}")
        else t.r.ok(S, "al alcanzar el nivel dentro de la guarderia el informe avisa de bulbasaur")
        if (PetState.nurseryEvolvable(t.c).none { it.first == "bulbasaur" }) t.r.problem("MEDIA", S, "nurseryEvolvable no incluye a bulbasaur Nv30")
        val r2 = PetState.settleNursery(t.c, force = true)
        if (r2?.evolvable?.isNotEmpty() == true) t.r.problem("MEDIA", S, "el aviso se repite en la siguiente liquidacion: ${r2.evolvable}") else t.r.ok(S, "el aviso no se repite mientras siga sin evolucionar")
        // Entra ya listo (el jugador lo sabe): no avisa, pero si cuenta como evolucionable en la escena.
        t.give("charmeleon", false, 50)
        PetState.nurseryAdd(t.c, "charmeleon", false)?.let { t.r.problem("MEDIA", S, "no entra charmeleon: $it") }
        val r3 = PetState.settleNursery(t.c, force = true)
        if (r3?.evolvable?.contains("charmeleon") == true) t.r.problem("MEDIA", S, "avisa de un miembro que ya estaba listo al meterlo (charmeleon Nv50)") else t.r.ok(S, "un miembro que entra ya listo no dispara aviso")
        if (PetState.nurseryEvolvable(t.c).none { it.first == "charmeleon" }) t.r.problem("MEDIA", S, "charmeleon Nv50 no figura como evolucionable en la escena")
        // Evoluciona (porque el jugador lo pide): se QUEDA en la guarderia ya evolucionado, el activo del widget no cambia, ya no
        // avisa y el destino cuenta como tenido; el origen deja de contar como tenido.
        val xpBeforeEvolve = t.xp("bulbasaur")!!
        PetState.evolveTo(t.c, "bulbasaur", "ivysaur", 2, false)
        if (PetState.isInNursery(t.c, "bulbasaur", false)) t.r.problem("MEDIA", S, "bulbasaur sigue en la guarderia tras evolucionar (deberia haber pasado a ivysaur)")
        if (!PetState.isInNursery(t.c, "ivysaur", false)) t.r.problem("MEDIA", S, "ivysaur NO esta en la guarderia tras evolucionar alli: ${PetState.nurseryMembers(t.c)}")
        else t.r.ok(S, "evolucionar dentro de la guarderia deja al evolucionado en su sitio")
        if (t.active().first != "charmander") t.r.problem("MEDIA", S, "evolucionar en la guarderia cambio el activo del widget a ${t.active()}")
        else t.r.ok(S, "evolucionar en la guarderia no cambia el compañero activo")
        val xpAfterEvolve = t.xp("ivysaur") ?: -1f
        if (xpAfterEvolve < xpBeforeEvolve - 0.5f) t.r.problem("MEDIA", S, "la XP baja al evolucionar en la guarderia: $xpBeforeEvolve -> $xpAfterEvolve")
        if (PetState.nurseryMembers(t.c).size != PetState.nurseryMembers(t.c).distinct().size) t.r.problem("MEDIA", S, "miembros repetidos en la guarderia: ${PetState.nurseryMembers(t.c)}")
        // Fuera de la guarderia evolucionar sigue dejando al evolucionado como activo (comportamiento de siempre).
        t.give("squirtle", false, 30)
        PetState.evolveTo(t.c, "squirtle", "wartortle", 8, false)
        if (t.active().first != "wartortle") t.r.problem("MEDIA", S, "evolucionar fuera de la guarderia ya no deja al evolucionado como activo: ${t.active()}")
        else t.r.ok(S, "evolucionar fuera de la guarderia sigue activando al evolucionado")
        if (!PetState.ownsLiveIndividual(t.c, "ivysaur", false)) t.r.problem("MEDIA", S, "ownsLiveIndividual(ivysaur) = false tras evolucionar a ivysaur")
        if (PetState.ownsLiveIndividual(t.c, "bulbasaur", false)) t.r.problem("MEDIA", S, "ownsLiveIndividual(bulbasaur) = true aunque ya evoluciono")
        if (PetState.ownsLiveIndividual(t.c, "venusaur", false)) t.r.problem("MEDIA", S, "ownsLiveIndividual(venusaur) = true sin tenerlo")
        if (PetState.ownsLiveIndividual(t.c, "ivysaur", true)) t.r.problem("MEDIA", S, "ownsLiveIndividual(ivysaur shiny) = true: normal y shiny son individuos distintos")
        val r4 = PetState.settleNursery(t.c, force = true)
        if (r4?.evolvable?.isNotEmpty() == true) t.r.problem("MEDIA", S, "tras evolucionar a bulbasaur vuelve a avisar: ${r4.evolvable}")
        else t.r.ok(S, "tras evolucionar: fuera de la guarderia, sin avisos y propiedad correcta")
        t.reportInvariants(S, "tras el aviso de evolucion de la guarderia")
    }

    private fun finals(t: T, count: Int): List<String> =
        t.evo.values.filter { it.to.isEmpty() && it.from != null && !it.rare }.map { it.name }.shuffled(t.rnd).take(count)

    private fun fill(t: T, levelFor: (Int) -> Int): List<String> {
        t.wipe(); PetState.debugSetTrainerLevelMax(t.c, 50)
        t.give("charmander", false, 5); t.setActive("charmander")
        val names = finals(t, 6)
        names.forEachIndexed { i, nm -> t.give(nm, false, levelFor(i)); val e = PetState.nurseryAdd(t.c, nm, false); if (e != null) t.r.problem("MEDIA", S, "no entra $nm: $e") }
        return names
    }

    private fun longAbsences(t: T) {
        for (days in listOf(0.02, 1.0, 30.0, 365.0, 3650.0)) {
            val names = fill(t) { 10 + it * 10 }
            val xpBefore = names.associateWith { t.xp(it)!! }
            val candiesBefore = PetState.candyCount(t.c)
            val poolBefore = PetState.overflowXp(t.c)
            val ownedBefore = PetState.ownedIndividuals(t.c).size
            t.nurseryTimeTravel((days * 24 * HOUR_MS).toLong())
            val t0 = System.currentTimeMillis()
            val rep = PetState.settleNursery(t.c, force = true)
            val ms = System.currentTimeMillis() - t0
            // Un ancla anterior a la propia app (aqui 10 años atras) es una clave corrupta, no una ausencia: se re-ancla sin regalar XP.
            val beforeTheApp = System.currentTimeMillis() - (days * 24 * HOUR_MS).toLong() < 1_704_067_200_000L
            if (rep == null && beforeTheApp) {
                val gained = names.sumOf { (t.xp(it)!! - xpBefore[it]!!).toDouble() }
                if (gained > 1.0) t.r.problem("MEDIA", S, "ancla anterior a la app ($days dias) re-anclada pero con ${"%.0f".format(gained)} XP regalada")
                else t.r.ok(S, "ancla de $days dias (anterior a la app): se re-ancla sin regalar XP")
                continue
            }
            if (rep == null) { t.r.problem("MEDIA", S, "settleNursery devolvio null tras $days dias con 6 miembros"); continue }
            val gains = names.map { t.xp(it)!! - xpBefore[it]!! }
            // La XP por tiempo se acredita como mucho de un año (PetState.MAX_ABSENCE_MS).
            val expectedBase = PetState.nurseryXpPerHour() * (minOf(days, 365.0) * 24).toFloat()
            val sumGain = gains.sum()
            val expectedSum = 6 * rep.timeXpPerSlot + rep.bonusXp + rep.careXp
            val candiesGot = PetState.candyCount(t.c) - candiesBefore
            // XP que ya no cabia (algun miembro llego al nivel 100): se guarda como XP sobrante y se convierte en caramelos (200 por caramelo).
            val perCandy = PetState.overflowXpPerCandy()
            val extraCandies = candiesGot - rep.candies
            val overflowXp = extraCandies * perCandy + (PetState.overflowXp(t.c) - poolBefore)
            val newShinies = PetState.ownedIndividuals(t.c).size - ownedBefore
            t.r.info(S, "$days dias: ${ms} ms; XP por plaza ${"%.0f".format(rep.timeXpPerSlot)} (esperado base ${"%.0f".format(expectedBase)}), huevos ${rep.hatched} -> ${candiesGot} caramelos + $newShinies shiny nuevo(s) (${rep.duplicateShinies} repetidos); suma XP ${"%.0f".format(sumGain)} vs informe ${"%.0f".format(expectedSum)}")
            if (gains.any { it < 0f }) t.r.problem("GRAVE", S, "XP negativa tras $days dias: $gains")
            // Conservacion de la XP: lo repartido + lo que sobro por estar al 100 (convertido en caramelos o en el contador) = lo del informe.
            if (extraCandies < 0 || kotlin.math.abs(sumGain + overflowXp - expectedSum) > 1f + expectedSum * 0.001f) t.r.problem("MEDIA", S, "tras $days dias la XP repartida (${"%.1f".format(sumGain)}) + la sobrante (${"%.1f".format(overflowXp)}) no coincide con la del informe (${"%.1f".format(expectedSum)})")
            else if (overflowXp > 0f) t.r.info(S, "tras $days dias parte de la XP no cabia (nivel 100): ${"%.0f".format(overflowXp)} XP sobrantes -> $extraCandies caramelos de mas")
            if (kotlin.math.abs(rep.timeXpPerSlot - expectedBase) > 2f + expectedBase * 0.002f) t.r.problem("MEDIA", S, "XP por tiempo de plaza ${rep.timeXpPerSlot} != esperado $expectedBase tras $days dias")
            if (extraCandies < 0) t.r.problem("MEDIA", S, "caramelos ganados $candiesGot < informe ${rep.candies}")
            if (newShinies != rep.shinies.size) t.r.problem("MEDIA", S, "individuos nuevos $newShinies != shinies del informe ${rep.shinies.size}")
            if (ms > 3000) t.r.problem("MEDIA", S, "settleNursery tardo $ms ms tras $days dias")
            t.reportInvariants(S, "tras $days dias de ausencia")
        }
    }

    private fun clockOddities(t: T) {
        val names = fill(t) { 20 }
        val xp0 = names.map { t.xp(it)!! }
        // Reloj en el futuro (hora del movil retrasada): no debe dar XP ni romper nada.
        t.nurseryTimeTravel(-5 * 24 * HOUR_MS)
        PetState.settleNursery(t.c, force = true)
        val xp1 = names.map { t.xp(it)!! }
        val diff = xp1.zip(xp0).map { (a, b) -> a - b }
        if (diff.any { it > 1f }) t.r.problem("MEDIA", S, "con el reloj en el futuro la guarderia dio XP: $diff") else t.r.ok(S, "reloj en el futuro: sin XP espuria")
        // Tras volver a la normalidad, ¿recupera XP de golpe por el ancla adelantada? (informativo)
        val before = names.map { t.xp(it)!! }
        PetState.settleNursery(t.c, force = true)
        val after = names.map { t.xp(it)!! }
        t.r.info(S, "segunda liquidacion tras el reloj futuro: XP extra ${after.zip(before).map { (a, b) -> "%.1f".format(a - b) }}")
        // Ancla corrupta (muy en el pasado): 1 ms desde 1970.
        val e = t.st().edit()
        e.putLong("nursery_xp_last", 1L).putLong("nursery_egg_last", 1L).putLong("nursery_care_last", 1L).commit()
        val b2 = names.map { t.xp(it)!! }
        val t0 = System.currentTimeMillis()
        PetState.settleNursery(t.c, force = true)
        val ms = System.currentTimeMillis() - t0
        val g2 = names.mapIndexed { i, nm -> t.xp(nm)!! - b2[i] }
        t.r.info(S, "ancla en 1970: ${ms} ms, XP por miembro ${g2.map { "%.0f".format(it) }}")
        if (g2.any { it > 2_000_000f }) t.r.problem("MEDIA", S, "un ancla corrupta (1970) regala ${"%.0f".format(g2.max())} XP en una liquidacion (sin tope de XP por ausencia)") else t.r.ok(S, "ancla en 1970 acotada")
        t.reportInvariants(S, "tras relojes raros")
    }

    private fun repeatedSettles(t: T, n: Int) {
        val names = fill(t) { 20 }
        val xp0 = names.sumOf { t.xp(it)!!.toDouble() }
        val t0 = System.currentTimeMillis()
        repeat(maxOf(n, 200) * 5) { PetState.settleNursery(t.c, force = true) }
        val ms = System.currentTimeMillis() - t0
        val xp1 = names.sumOf { t.xp(it)!!.toDouble() }
        val expected = names.size * PetState.nurseryXpPerHour() * (ms / HOUR_MS.toDouble())
        t.r.info(S, "${maxOf(n, 200) * 5} liquidaciones forzadas en $ms ms: XP total +${"%.2f".format(xp1 - xp0)} (solo por tiempo se esperaria ~${"%.2f".format(expected)}; el resto son acciones de necesidades)")
        if (xp1 - xp0 > 6 * 200f) t.r.problem("MEDIA", S, "liquidar muchas veces seguidas genera mucha XP (+${"%.0f".format(xp1 - xp0)}) en ${ms} ms")
        t.reportInvariants(S, "tras liquidaciones repetidas")
    }

    private fun concurrentSettles(t: T) {
        val names = fill(t) { 30 }
        t.nurseryTimeTravel(10 * 24 * HOUR_MS)
        val xp0 = names.sumOf { t.xp(it)!!.toDouble() }
        val errs = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val reports = java.util.concurrent.ConcurrentLinkedQueue<PetState.NurseryReport>()
        val threads = (1..4).map { Thread { try { repeat(30) { PetState.settleNursery(t.c, force = true)?.let { r -> reports.add(r) } } } catch (e: Throwable) { errs.add(e) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val gained = names.sumOf { t.xp(it)!!.toDouble() } - xp0
        // La XP por tiempo solo puede repartirse UNA vez (el primer hilo se lleva todo el hueco): suma de los informes vs esperado.
        val timeXp = reports.sumOf { (it.timeXpPerSlot * names.size).toDouble() }
        val reported = reports.sumOf { (it.timeXpPerSlot * names.size + it.bonusXp + it.careXp).toDouble() }
        val expectedTime = names.size * PetState.nurseryXpPerHour() * 10 * 24.0
        if (errs.isNotEmpty()) t.r.problem("GRAVE", S, "excepciones con 4 hilos liquidando: ${errs.first()}")
        if (timeXp > expectedTime * 1.02 + 50) t.r.problem("GRAVE", S, "4 hilos liquidando a la vez DUPLICAN la XP por tiempo: informes ${"%.0f".format(timeXp)} vs esperado ${"%.0f".format(expectedTime)}")
        else t.r.ok(S, "4 hilos liquidando: XP por tiempo ${"%.0f".format(timeXp)} (esperado ${"%.0f".format(expectedTime)}), sin excepciones")
        if (kotlin.math.abs(gained - reported) > 2.0 + reported * 0.001) t.r.problem("MEDIA", S, "la XP realmente repartida (${"%.0f".format(gained)}) no coincide con la de los informes (${"%.0f".format(reported)}) con 4 hilos")
        t.reportInvariants(S, "tras liquidar en paralelo")
    }

    private fun maxedMembers(t: T) {
        val names = fill(t) { 100 }
        t.nurseryTimeTravel(30 * 24 * HOUR_MS)
        PetState.settleNursery(t.c, force = true)
        val lv = names.map { PetState.levelForPokemon(t.c, it) }
        if (lv.any { it != 100 }) t.r.problem("MEDIA", S, "miembros de nivel 100 con niveles $lv")
        else t.r.ok(S, "miembros de nivel 100 siguen en 100 tras 30 dias (XP acumulada ${"%.0f".format(t.xp(names[0])!!)})")
        t.reportInvariants(S, "tras liquidar miembros al 100")
    }
}
