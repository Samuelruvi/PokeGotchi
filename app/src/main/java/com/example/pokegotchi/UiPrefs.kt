package com.example.pokegotchi

import android.content.Context

/**
 * Preferencias de la INTERFAZ que tienen que sobrevivir a reiniciar la app: el orden y los filtros que el jugador deja puestos
 * (orden de la lista principal, orden del selector de la mazmorra, orden y filtros del selector de la guardería). Pedido del
 * usuario (2026-10-17): "los filtros no se guardan; si filtro por nivel y la aplicación se reinicia, se quita".
 *
 * Es un fichero aparte de las preferencias del juego a proposito: son comodidades de pantalla, no estado de la partida, y asi no
 * se mezclan con lo que recorre PetState (ni se cuentan como Pokemon, huevos, etc.). Texto que se escribe en un buscador NO se guarda.
 */
object UiPrefs {
    private const val FILE = "pokegotchi_ui"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun string(context: Context, key: String, default: String): String = prefs(context).getString(key, default) ?: default
    fun putString(context: Context, key: String, value: String) { prefs(context).edit().putString(key, value).apply() }

    fun bool(context: Context, key: String, default: Boolean): Boolean = prefs(context).getBoolean(key, default)
    fun putBool(context: Context, key: String, value: Boolean) { prefs(context).edit().putBoolean(key, value).apply() }

    fun int(context: Context, key: String, default: Int): Int = prefs(context).getInt(key, default)
    fun putInt(context: Context, key: String, value: Int) { prefs(context).edit().putInt(key, value).apply() }

    // Claves (todas aqui para no repetir cadenas sueltas por las pantallas).
    const val MAIN_SORT = "main.sort"                  // "id" | "level" | "date" | "name" | "shiny"
    const val DUNGEON_SORT_DESC = "dungeon.sortDesc"   // true = nivel de mayor a menor
    const val DUNGEON_SORT_MODE = "dungeon.sort"       // 0 = nivel ▼, 1 = nivel ▲, 2 = nombre (si falta, se deduce de DUNGEON_SORT_DESC)
    const val DUNGEON_ONLY_FREE = "dungeon.onlyFree"
    const val DUNGEON_ONLY_SHINY = "dungeon.onlyShiny"
    const val NURSERY_SORT = "nursery.sort"            // 0 = nivel ▲, 1 = nivel ▼, 2 = nombre
    const val NURSERY_ONLY_FREE = "nursery.onlyFree"
    const val NURSERY_ONLY_LAYERS = "nursery.onlyLayers"
    const val NURSERY_ONLY_SHINY = "nursery.onlyShiny"

    /** Ordenes validos de la lista principal (lo guardado y ya no valido, p. ej. de una version futura, cae al orden por numero). */
    val MAIN_SORTS = setOf("id", "level", "date", "name", "shiny")
}
