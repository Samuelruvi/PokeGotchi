package com.example.pokegotchi

import android.content.Context
import java.io.File

/**
 * Grito corto de cada especie/forma. Primero mira en el paquete LOCAL (carpeta assets/cries -
 * 1025 especies reales + las 146 formas de Megaevolucion/Gigantamax/fusiones (Necrozma/Kyurem/
 * Calyrex/Primigenios/Origen/Corona/Therian...) de megas.json, cada una revisada uno a uno contra
 * PokeAPI antes de meterla, pedido explicito del usuario para no depender de internet); solo si
 * falta esa forma en concreto (formas regionales - Alola/Galar/Hisui/Paldea - que comparten el
 * grito de su forma base en los juegos reales, igual que aqui) cae a la copia cacheada/descargada
 * por id de PokeAPI como hasta ahora. Llamar SIEMPRE en un hilo de fondo.
 *
 * OJO (correccion real, ver conversacion): los archivos "_1"/"_2"... del paquete ORIGINAL del
 * usuario NO se usaron (esos sufijos resultaron ser formas regionales/genero/color de cada
 * especie, no las Megas/Gigantamax) - pero SI existe un grito real y DISTINTO para cada
 * Megaevolucion/Gigantamax en PokeAPI (comprobado con hashes: Mega Gengar y Gigantamax Gengar NO
 * comparten audio ni con Gengar base ni entre si) - la suposicion inicial de que "ninguna Mega/
 * Gigantamax tiene grito propio" era incorrecta y se corrigio verificandolo, no se descarto sin
 * mas. Los 146 se descargaron directamente de PokeAPI (emparejando cada nombre de megas.json con
 * su "variety" real, con un par de casos especiales de nombre - ej. necrozma-dusk/-dawn,
 * eternatus-eternamax, urshifu-*-strike-gmax) y se guardaron en assets/cries/<nombre-de-la-
 * opcion>.ogg (ej. "gengar-mega.ogg", "gengar-gmax.ogg").
 */
object CryRepository {
    private val REGIONAL_SUFFIXES = listOf("-alola", "-galar", "-hisui", "-paldea")

    /** Nombre de la forma BASE a efectos de grito (comparte el mismo audio en los juegos reales):
     *  quita el sufijo regional, o "urshifu" para su estilo Furia Torrencial. */
    private fun baseNameForCry(name: String): String = when {
        name == "urshifu-rapid" -> "urshifu"
        else -> REGIONAL_SUFFIXES.firstOrNull { name.endsWith(it) }?.let { name.removeSuffix(it) } ?: name
    }

    private fun localAssetPath(name: String) = "cries/${name.lowercase()}.ogg"

    /** Copia el asset a un archivo real (MediaPlayer.setDataSource necesita una ruta, no puede
     *  leer de assets directamente) - una sola vez, igual que ya se cachea la version de red. */
    private fun copyLocal(context: Context, assetPath: String, name: String): File? {
        return try {
            val f = File(context.filesDir, "cries_local/$name.ogg")
            if (!f.exists() || f.length() == 0L) {
                f.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            f
        } catch (e: Exception) {
            null
        }
    }

    private fun tryLocal(context: Context, name: String): File? {
        val n = name.lowercase()
        val direct = localAssetPath(n)
        val hasDirect = try { context.assets.open(direct).use { true } } catch (e: Exception) { false }
        if (hasDirect) return copyLocal(context, direct, n)
        val base = baseNameForCry(n)
        if (base == n) return null
        val basePath = localAssetPath(base)
        val hasBase = try { context.assets.open(basePath).use { true } } catch (e: Exception) { false }
        return if (hasBase) copyLocal(context, basePath, base) else null
    }

    private fun networkFile(context: Context, id: Int) = File(context.filesDir, "cries/$id.ogg")

    /** Devuelve el archivo del cry de [name] (pokedex id [id] solo para el fallback de red) -
     *  local si esta en el paquete bundleado, si no descargandolo. Null si todo falla (sin
     *  conexion Y sin copia local, especie sin cry, etc). */
    fun ensure(context: Context, name: String, id: Int): File? {
        tryLocal(context, name)?.let { return it }
        val f = networkFile(context, id)
        if (f.exists() && f.length() > 0) return f
        val bytes = SpriteRepository.download(
            "https://raw.githubusercontent.com/PokeAPI/cries/main/cries/pokemon/latest/$id.ogg"
        ) ?: return null
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }
}
