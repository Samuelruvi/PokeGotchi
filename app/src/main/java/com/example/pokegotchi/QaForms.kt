package com.example.pokegotchi

import com.example.pokegotchi.QaHarness.T

/** Bloque G del plan: formas, megas y el sprite que el juego dice mostrar en cada estado. SOLO PRUEBAS. */
object QaForms {
    private const val S = "formas"

    fun run(t: T, n: Int) {
        t.case(S, "sprite mostrado por especie / salud / fondo / nivel") { spritesByState(t) }
        t.case(S, "megas, gigamax y fusiones") { megas(t) }
        t.case(S, "formas desbloqueables y decorativas") { forms(t) }
        t.case(S, "tipos, fondos sugeridos y mecanicas por especie") { typesAndMechanics(t) }
        t.case(S, "tiempos de megaevolucion: activa, expira, enfriamiento") { megaTiming(t) }
    }

    private fun spriteSet(t: T, shiny: Boolean): Set<String> =
        (t.c.assets.list(if (shiny) "localsprites/shiny" else "localsprites/normal") ?: emptyArray()).map { it.removeSuffix(".png").lowercase() }.toSet()

    private fun backgrounds(): List<String> =
        R.drawable::class.java.fields.map { it.name }.filter { it.startsWith("bg_") && !it.contains("guarderia") }.sorted()

    private fun spritesByState(t: T) {
        t.wipe()
        val normal = spriteSet(t, false); val shiny = spriteSet(t, true)
        val bgs = backgrounds()
        t.r.info(S, "${bgs.size} fondos: $bgs")
        val originalBg = PetState.currentBg(t.c)
        val missing = LinkedHashMap<String, String>()
        var calls = 0
        val t0 = System.currentTimeMillis()
        // Un solo individuo a la vez (las preferencias se reescriben ENTERAS en cada commit: con miles de individuos tardaria horas).
        for (d in t.dex) {
            if (d.name == "pikachu") continue
            for (sh in listOf(false, true)) {
                t.give(d.name, sh, 1)
                t.setActive(d.name, sh)
                val set = if (sh) shiny else normal
                for (level in listOf(1, 36, 100)) {
                    t.setLevel(d.name, sh, level)
                    for (health in listOf(100f, 5f)) {
                        PetState.debugSetIndividualStats(t.c, d.name, sh, health = health)
                        for (bg in bgs) {
                            PetState.setBg(t.c, bg)
                            val s = PetState.displaySpriteName(t.c, d.name, sh).lowercase()
                            calls++
                            if (s !in set && missing.size < 40) missing.putIfAbsent("${d.name}${if (sh) "✨" else ""}", "$s (nivel $level, salud $health, fondo $bg)")
                        }
                    }
                }
                t.setActive("pikachu", false)
                PetState.debugDeleteIndividual(t.c, d.name, sh)
            }
        }
        PetState.setBg(t.c, originalBg)
        if (missing.isEmpty()) t.r.ok(S, "displaySpriteName siempre apunta a un sprite existente ($calls combinaciones, ${System.currentTimeMillis() - t0} ms)")
        else t.r.problem("MEDIA", S, "${missing.size}+ combinaciones apuntan a un sprite inexistente: ${missing.entries.take(15).joinToString { "${it.key} -> ${it.value}" }}")
    }

    private fun megas(t: T) {
        t.wipe()
        val normal = spriteSet(t, false); val shiny = spriteSet(t, true)
        var options = 0; var bad = 0
        for (d in t.dex) {
            val opts = PetState.megaOptionsFor(t.c, d.name)
            if (opts.isEmpty()) continue
            for (sh in listOf(false, true)) {
                t.give(d.name, sh, 100); t.setActive(d.name, sh)
                for (o in opts) {
                    options++
                    PetState.activateMega(t.c, d.name, o.name)
                    val s = PetState.displaySpriteName(t.c, d.name, sh).lowercase()
                    val active = PetState.activeMegaSpriteName(t.c, d.name)
                    val set = if (sh) shiny else normal
                    if (s !in set) { bad++; if (bad <= 15) t.r.problem("MEDIA", S, "${d.name}${if (sh) "✨" else ""} con ${o.name} activa: displaySpriteName='$s' no existe (activeMegaSpriteName=$active)") }
                    if (PetState.megaActiveRemainingMs(t.c, d.name) <= 0L) { bad++; if (bad <= 15) t.r.problem("MEDIA", S, "${d.name}/${o.name}: tras activar, tiempo restante <= 0") }
                    PetState.debugDeactivateMega(t.c, d.name)
                }
                if (PetState.megaOptionsEligible(t.c, d.name, 100).isEmpty() && opts.isNotEmpty() && d.name !in listOf("terapagos")) {
                    bad++; if (bad <= 15) t.r.problem("BAJA", S, "${d.name}: tiene ${opts.size} opciones pero ninguna elegible a nivel 100")
                }
            }
        }
        if (bad == 0) t.r.ok(S, "$options opciones de mega/gigamax/fusion activadas: sprite existente y duracion > 0")
    }

    private fun megaTiming(t: T) {
        t.wipe()
        for (sp in listOf("charizard", "venusaur", "absol", "kyurem")) {
            if (PetState.megaOptionsFor(t.c, sp).isEmpty()) { t.r.info(S, "$sp sin opciones de mega"); continue }
            t.give(sp, false, 100); t.setActive(sp)
            if (sp == "kyurem") { t.give("reshiram", false, 100); t.give("zekrom", false, 100); t.setActive(sp) }
            val opts = PetState.megaOptionsEligible(t.c, sp, 100)
            if (opts.isEmpty()) { t.r.problem("BAJA", S, "$sp a nivel 100 sin ninguna opcion elegible"); continue }
            val o = opts.first()
            if (!PetState.canMegaEvolve(t.c, sp, 100)) t.r.problem("MEDIA", S, "$sp (Nv100, sin uso previo): canMegaEvolve=false")
            PetState.activateMega(t.c, sp, o.name)
            val rem = PetState.megaActiveRemainingMs(t.c, sp); val cd = PetState.megaCooldownRemainingMs(t.c, sp)
            t.r.info(S, "$sp/${o.name}: activa ${rem / 60000} min, enfriamiento ${cd / 3_600_000} h, canMegaEvolve=${PetState.canMegaEvolve(t.c, sp, 100)}, sprite=${PetState.displaySpriteName(t.c, sp, false)}")
            if (rem <= 0L) t.r.problem("MEDIA", S, "$sp: tras activar la mega no queda tiempo")
            if (PetState.canMegaEvolve(t.c, sp, 100)) t.r.problem("MEDIA", S, "$sp: se puede volver a megaevolucionar durante el enfriamiento")
            // Expira: el sprite vuelve al normal y el enfriamiento sigue.
            t.st().edit().putLong("$sp.mega_active_until", System.currentTimeMillis() - 1000L).commit()
            val spriteAfter = PetState.displaySpriteName(t.c, sp, false)
            if (PetState.megaActiveRemainingMs(t.c, sp) != 0L || spriteAfter.contains("mega") || spriteAfter.contains("gmax")) t.r.problem("MEDIA", S, "$sp: tras expirar la mega sigue mostrando '$spriteAfter'")
            if (PetState.canMegaEvolve(t.c, sp, 100)) t.r.problem("MEDIA", S, "$sp: expiro la mega pero NO el enfriamiento y ya se puede repetir")
            // Enfriamiento completo: vuelve a poder.
            t.st().edit().putLong("$sp.mega_last_used_at", System.currentTimeMillis() - 13L * 3_600_000L).commit()
            if (!PetState.canMegaEvolve(t.c, sp, 100)) t.r.problem("MEDIA", S, "$sp: pasadas 13 h sigue sin poder megaevolucionar (enfriamiento ${PetState.megaCooldownRemainingMs(t.c, sp) / 3_600_000} h)")
            else t.r.ok(S, "$sp: ciclo activa -> expira -> enfriamiento -> repetible correcto")
        }
        // Un relojito movido al futuro: la mega no debe quedarse activa para siempre.
        t.give("charizard", false, 100); t.setActive("charizard")
        PetState.activateMega(t.c, "charizard", PetState.megaOptionsEligible(t.c, "charizard", 100).first().name)
        t.st().edit().putLong("charizard.mega_active_until", System.currentTimeMillis() + 400L * 24 * 3_600_000L).commit()
        val remDays = PetState.megaActiveRemainingMs(t.c, "charizard") / (24 * 3_600_000L)
        if (remDays > 30) t.r.problem("BAJA", S, "con la hora del movil adelantada, la mega puede quedar activa ${remDays} dias (no hay tope contra relojes movidos)")
    }

    private fun forms(t: T) {
        t.wipe()
        val normal = spriteSet(t, false)
        var bad = 0; var checked = 0
        for (d in t.dex) {
            val unl = PetState.unlockableFormOptions(d.name)
            val dec = PetState.decorativeFormsOptions(d.name)
            if (unl.isEmpty() && dec.isEmpty()) continue
            t.give(d.name, false, 100); t.setActive(d.name)
            for (u in unl) {
                try {
                    PetState.setUnlockableForm(t.c, d.name, u.key, 100, false)
                    val key = PetState.unlockableSpriteKey(d.name, PetState.unlockableForm(t.c, d.name, false)).lowercase()
                    val shown = PetState.displaySpriteName(t.c, d.name, false).lowercase()
                    checked++
                    if (key !in normal) { bad++; if (bad <= 15) t.r.problem("MEDIA", S, "${d.name} forma desbloqueable ${u.key}: sprite '$key' no existe") }
                    if (shown !in normal) { bad++; if (bad <= 15) t.r.problem("MEDIA", S, "${d.name} forma ${u.key}: displaySpriteName '$shown' no existe") }
                } catch (e: Throwable) { bad++; if (bad <= 15) t.r.problem("GRAVE", S, "${d.name} forma ${u.key}: ${e.javaClass.simpleName}: ${e.message}") }
            }
            for (f in dec) {
                try {
                    PetState.debugSetDecorativeFormOwned(t.c, d.name, f, true)
                    PetState.selectDecorativeForm(t.c, d.name, f)
                    val key = PetState.decorativeSpriteKey(d.name, PetState.decorativeForm(t.c, d.name)).lowercase()
                    val shown = PetState.displaySpriteName(t.c, d.name, false).lowercase()
                    checked++
                    if (key !in normal) { bad++; if (bad <= 15) t.r.problem("MEDIA", S, "${d.name} forma decorativa $f: sprite '$key' no existe") }
                    if (shown !in normal) { bad++; if (bad <= 15) t.r.problem("MEDIA", S, "${d.name} forma decorativa $f: displaySpriteName '$shown' no existe") }
                } catch (e: Throwable) { bad++; if (bad <= 15) t.r.problem("GRAVE", S, "${d.name} forma decorativa $f: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        if (bad == 0) t.r.ok(S, "$checked formas desbloqueables/decorativas con sprite existente")
    }

    private fun typesAndMechanics(t: T) {
        t.wipe()
        var noTypes = 0; var noBg = 0; var mechFail = 0
        for (d in t.dex) {
            try {
                val types = PetState.typesOf(t.c, d.name, false)
                if (types.isEmpty()) { noTypes++; if (noTypes <= 8) t.r.problem("BAJA", S, "${d.name}: sin tipos") }
                val bgs = PetState.suggestedBackgrounds(t.c, d.name, false)
                if (bgs.isEmpty()) noBg++
                PetState.speciesMechanics(t.c, d.name)
            } catch (e: Throwable) { mechFail++; if (mechFail <= 10) t.r.problem("GRAVE", S, "${d.name}: typesOf/suggestedBackgrounds/speciesMechanics lanzo ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }}") }
        }
        t.r.info(S, "${t.dex.size} especies: sin tipos $noTypes, sin fondo sugerido $noBg, fallos de mecanicas $mechFail")
        if (noTypes == 0 && mechFail == 0) t.r.ok(S, "tipos, fondos sugeridos y mecanicas por especie sin excepciones")
    }
}
