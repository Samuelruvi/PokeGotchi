package com.example.pokegotchi

import com.example.pokegotchi.QaHarness.T

/** Bloque F del plan: regalos (ciclo, composicion de la oferta, tasas) y huevo del activo (tasas). SOLO PRUEBAS. */
object QaOffers {
    private const val S = "regalos"
    private const val HOUR_MS = 3_600_000L

    fun run(t: T, n: Int) {
        if (n >= 1000) {
            t.case(S, "composicion de las ofertas") { composition(t, maxOf(n, 1500)) }
            t.case(S, "horario fijo 9:00 / 21:00 con saltos de tiempo") { cycle(t) }
            t.case(S, "agotar la Pokedex: ofertas cuando ya tienes casi todo") { exhaustion(t) }
        }
        t.case(S, "huevo del activo: tasas de puesta y eclosion") { eggs(t) }
    }

    private fun composition(t: T, draws: Int) {
        t.wipe()
        val rare = t.evo.values.filter { it.rare }.map { it.name }.toSet()
        var rareHits = 0; var shinyHits = 0; var slots = 0; var dupe = 0; var notBase = 0; var wrongSize = 0; var owned = 0
        val perSpecies = HashMap<String, Int>()
        repeat(draws) {
            PetState.dismissOffer(t.c); PetState.forceNewOffer(t.c)
            val o = PetState.currentOffer(t.c)
            if (o.size != 5) wrongSize++
            if (o.map { it.name }.toSet().size != o.size) dupe++
            for (m in o) {
                slots++
                if (m.name in rare) rareHits++
                if (m.shiny) shinyHits++
                if (t.evo[m.name]?.from != null) notBase++
                if (PetState.isUnlocked(t.c, m.name)) owned++
                perSpecies[m.name] = (perSpecies[m.name] ?: 0) + 1
            }
        }
        val rareShare = rareHits * 100.0 / slots; val shinyShare = shinyHits * 100.0 / slots
        t.r.info(S, "$draws ofertas ($slots candidatos): legendarios/miticos ${"%.2f".format(rareShare)} % (la memoria dice ~1 % por hueco), shiny ${"%.2f".format(shinyShare)} %, especies distintas ${perSpecies.size} de ${t.evo.values.count { it.from == null }} formas base")
        if (wrongSize > 0) t.r.problem("MEDIA", S, "$wrongSize ofertas sin 5 candidatos")
        if (dupe > 0) t.r.problem("MEDIA", S, "$dupe ofertas con candidatos repetidos")
        if (notBase > 0) t.r.problem("MEDIA", S, "$notBase candidatos que no son formas base")
        if (owned > 0) t.r.problem("MEDIA", S, "$owned candidatos ya desbloqueados")
        if (wrongSize + dupe + notBase + owned == 0) t.r.ok(S, "ofertas siempre de 5 formas base distintas y no tenidas")
        val top = perSpecies.entries.sortedByDescending { it.value }.take(3).map { "${it.key}=${it.value}" }
        val bottom = perSpecies.entries.sortedBy { it.value }.take(3).map { "${it.key}=${it.value}" }
        t.r.info(S, "mas frecuentes $top, menos frecuentes $bottom")
        if (shinyShare !in 0.5..8.0) t.r.problem("BAJA", S, "porcentaje de shiny en el regalo ${"%.2f".format(shinyShare)} % fuera de lo esperado (2-4 %)")
    }

    // (+5 s: el instante se calcula unos milisegundos antes que el reparto, y a las 20:59:59,9 todavia no son las 21)
    private fun localHour(ms: Long) = java.util.Calendar.getInstance().apply { timeInMillis = ms + 5_000L }.get(java.util.Calendar.HOUR_OF_DAY)

    private fun cycle(t: T) {
        t.wipe()
        val now0 = System.currentTimeMillis()
        // Horario fijo: tras elegir/descartar, el siguiente llega en el primer reparto (9:00 / 21:00 locales) posterior a ese momento.
        PetState.dismissOffer(t.c)
        val remaining = PetState.offerCooldownRemainingMs(t.c)
        val expected = OfferSchedule.nextSlotAfter(now0) - now0
        t.r.info(S, "tras descartar la oferta forzada del inicio: siguiente en ${remaining / 60_000} min (esperado ${expected / 60_000} min, a las ${localHour(now0 + remaining)}:00); zona ${java.util.TimeZone.getDefault().id}, ahora ${java.text.SimpleDateFormat("EEE dd HH:mm z", java.util.Locale.US).format(java.util.Date(now0))}, siguiente ${java.text.SimpleDateFormat("EEE dd HH:mm z", java.util.Locale.US).format(java.util.Date(now0 + remaining))}")
        if (kotlin.math.abs(remaining - expected) > 10_000L) t.r.problem("MEDIA", S, "el siguiente regalo no cae en el proximo reparto de las 9:00/21:00 (restan ${remaining / 60_000} min, esperado ${expected / 60_000})")
        else if (localHour(now0 + remaining) !in OfferSchedule.SLOT_HOURS.toList()) t.r.problem("MEDIA", S, "el siguiente regalo cae a las ${localHour(now0 + remaining)}:00, no a las 9 ni a las 21")
        else t.r.ok(S, "el siguiente regalo cae en el proximo reparto (${localHour(now0 + remaining)}:00)")
        if (remaining > 13 * HOUR_MS) t.r.problem("MEDIA", S, "mas de 13 h hasta el siguiente regalo (${remaining / HOUR_MS} h)")
        // Antes de la hora: no aparece. A la hora: aparece.
        if (PetState.maybeGenerateOffer(t.c)) t.r.problem("MEDIA", S, "se genero la oferta antes de su hora")
        t.st().edit().putLong("offer_next_at", System.currentTimeMillis() - 1000L).commit()
        val g1 = PetState.maybeGenerateOffer(t.c)
        if (!g1) t.r.problem("MEDIA", S, "pasada la hora del reparto no se genera la oferta") else t.r.ok(S, "pasada la hora del reparto aparece la oferta")
        // Sin abrir durante 70 h: la oferta pendiente NO desaparece, no se genera otra encima y la hora del siguiente no se mueve sola.
        val pending = PetState.currentOffer(t.c)
        t.st().edit().putLong("offer_next_at", System.currentTimeMillis() - 70 * HOUR_MS).commit()
        val g2 = PetState.maybeGenerateOffer(t.c)
        val pending2 = PetState.currentOffer(t.c)
        if (g2 || pending2 != pending) t.r.problem("MEDIA", S, "con una oferta pendiente y 70 h de retraso se genero otra o cambio: $pending -> $pending2") else t.r.ok(S, "la oferta pendiente se conserva tras 70 h sin abrirla")
        // Resolver tarde: el siguiente va al primer reparto posterior a AHORA (se pierde el que paso mientras estaba sin abrir).
        val now1 = System.currentTimeMillis()
        PetState.resolveOffer(t.c, pending2.first().name, pending2.first().shiny)
        val remAfter = PetState.offerCooldownRemainingMs(t.c)
        t.r.info(S, "tras resolver con retraso: siguiente regalo en ${remAfter / 60_000} min, a las ${localHour(now1 + remAfter)}:00")
        if (kotlin.math.abs(remAfter - (OfferSchedule.nextSlotAfter(now1) - now1)) > 10_000L) t.r.problem("MEDIA", S, "tras resolver tarde el siguiente regalo no cae en el proximo reparto")
        if (PetState.currentOffer(t.c).isNotEmpty() || PetState.maybeGenerateOffer(t.c)) t.r.problem("MEDIA", S, "se genero otra oferta con ${remAfter / 60_000} min de espera por delante")
        // Reloj movido hacia delante (30 dias) y corregido: no congela el regalo, va al proximo reparto.
        t.st().edit().putLong("offer_next_at", System.currentTimeMillis() + 30L * 24 * HOUR_MS).commit()
        val remClock = PetState.offerCooldownRemainingMs(t.c)
        if (remClock > 13 * HOUR_MS) t.r.problem("MEDIA", S, "con el horario 30 dias en el futuro el regalo tardaria ${remClock / HOUR_MS} h") else t.r.ok(S, "un horario movido al futuro se recoloca en el proximo reparto (${remClock / 60_000} min)")
        // Partida con el ciclo ANTIGUO (ancla + 12 h): se convierte una vez al horario fijo.
        t.wipe()
        PetState.dismissOffer(t.c)
        t.st().edit().remove("offer_next_at").remove("offer_slots_migrated").putLong("offer_last_time", System.currentTimeMillis() - 5 * HOUR_MS).putString("offer_species", "").commit()
        val remMig = PetState.offerCooldownRemainingMs(t.c)
        val nextAt = System.currentTimeMillis() + remMig
        t.r.info(S, "ciclo antiguo con el ancla de hace 5 h: siguiente regalo en ${remMig / 60_000} min (${localHour(nextAt)}:00)")
        if (remMig > 25 * HOUR_MS || (remMig > 0 && localHour(nextAt) !in OfferSchedule.SLOT_HOURS.toList())) t.r.problem("MEDIA", S, "la conversion del ciclo antiguo no deja el regalo en un reparto de 9:00/21:00")
        else t.r.ok(S, "el ciclo antiguo se convierte al horario fijo")
    }

    private fun exhaustion(t: T) {
        t.wipe()
        // Desbloquea todas las formas base menos 3 y pide ofertas: solo deben ofrecer esas 3 (menos de 5 candidatos).
        val bases = t.evo.values.filter { it.from == null }.map { it.name }
        val e = t.st().edit()
        val keep = bases.shuffled(t.rnd).take(3).toSet()
        val unlocked = HashSet(t.st().getStringSet("unlocked_species", emptySet()) ?: emptySet())
        bases.filter { it !in keep }.forEach { unlocked.add(it) }
        e.putStringSet("unlocked_species", unlocked).commit()
        PetState.dismissOffer(t.c); PetState.forceNewOffer(t.c)
        val o = PetState.currentOffer(t.c)
        t.r.info(S, "con solo 3 formas base sin tener, la oferta trae ${o.size}: ${o.map { it.name }} (esperado: esas 3 = $keep)")
        if (o.size != 3) t.r.problem("BAJA", S, "oferta con ${o.size} candidatos cuando solo quedan 3 sin desbloquear")
        // Todas desbloqueadas: no hay oferta (y no debe petar).
        unlocked.addAll(bases); t.st().edit().putStringSet("unlocked_species", unlocked).commit()
        PetState.dismissOffer(t.c)
        val g = PetState.maybeGenerateOffer(t.c); val forced = try { PetState.forceNewOffer(t.c); PetState.currentOffer(t.c) } catch (ex: Throwable) { t.r.problem("GRAVE", S, "forceNewOffer con todo desbloqueado lanzo $ex"); emptyList() }
        t.r.info(S, "con TODAS las formas base desbloqueadas: generado=$g, oferta forzada=${forced.size} candidatos")
    }

    private fun eggs(t: T) {
        t.wipe()
        for ((sp, lvl) in listOf("raichu" to 5, "raichu" to 30, "raichu" to 60, "raichu" to 100)) {
            t.give(sp, false, lvl); t.setLevel(sp, false, lvl); t.setActive(sp)
            val st0 = PetState.loadWithDecay(t.c)
            t.r.info(S, "preparado $sp Nv$lvl: levelForPokemon=${PetState.levelForPokemon(t.c, sp, false)}, xp(loadWithDecay)=${"%.0f".format(st0.xp)} -> nivel ${PetState.levelOf(t.c, st0.xp, sp)}, activo=${t.active()}, huevo activo=${PetState.hasActiveEgg(t.c)}")
            if (PetState.hasActiveEgg(t.c)) PetState.releaseEgg(t.c)
            var layTicks = 0; var layCount = 0; var hatchTicks = 0; var hatchCount = 0
            repeat(20000) {
                if (!PetState.hasActiveEgg(t.c)) { layTicks++; if (PetState.maybeLayEgg(t.c, PetState.loadWithDecay(t.c))) layCount++ }
                else { hatchTicks++; PetState.maybeHatchEgg(t.c); if (PetState.isEggReady(t.c)) { hatchCount++; PetState.releaseEgg(t.c) } }
            }
            val pLay = layCount.toDouble() / layTicks; val ticksPerEgg = (20000.0 / maxOf(hatchCount, 1))
            t.r.info(S, "$sp Nv$lvl: prob. de puesta por tick ${"%.3f".format(pLay)} (formula ${"%.3f".format(minOf(0.4, 0.01 + 0.01 * lvl))}), ${"%.1f".format(ticksPerEgg)} ticks por huevo completo = ${"%.1f".format(ticksPerEgg * 15 / 60)} h => ${"%.1f".format(24 / (ticksPerEgg * 15 / 60))} huevos/dia si se resuelven al instante")
        }
    }
}
