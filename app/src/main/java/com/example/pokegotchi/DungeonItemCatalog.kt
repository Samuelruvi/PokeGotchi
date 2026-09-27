package com.example.pokegotchi

import kotlin.random.Random

/**
 * Catálogo de objetos DECORATIVOS de la mazmorra con nombre en español y categoría de rareza -
 * pedido explícito del usuario: "quiero que los objetos decorativos se puedan coleccionar...
 * podemos crear categorías con porcentajes de salida y que en la pokedex aparezca la probabilidad
 * de salir. así se puede ver si te ha tocado un item raro." Antes un objeto decorativo era
 * indistinguible de otro (mismo icono al azar vía randomOfRole(context,"decor"), mismo texto
 * genérico "Objeto decorativo" al recogerlo, sin nombre ni rareza) - este catálogo le da a cada
 * uno de los 129 objetos del rol "decor" (ver dungeonitems_catalog.json) un nombre real y una
 * categoría, y sustituye el sorteo uniforme por uno PONDERADO por categoría (ver
 * [pickRandomDecorItem], usado en DungeonSimulator en vez de randomOfRole para el rol "decor").
 *
 * Solo cubre el rol "decor" (129 de los 308 objetos catalogados) - el resto de roles
 * (candy/berry/medicine/vitamin/buff) ya tienen su propio efecto mecánico y su propio texto al
 * recogerlos (ver DungeonSimulator.stepOnce), así que no necesitan nombre ni rareza propia: la
 * "colección" solo tiene sentido para los objetos que hasta ahora no hacían nada más que decorar.
 * Los nombres/categorías se generaron una vez a mano (ver scratch_ss) contra la lista real de
 * dungeonitems_catalog.json, verificados 1:1 (129 de 129, sin huecos ni sobrantes).
 */
object DungeonItemCatalog {

    /** [tierWeightPercent]: probabilidad de que, al tocar un objeto decorativo, salga uno de ESTA
     *  categoría (las 5 suman 100). Repartida a mano (no hay ninguna tabla de "rareza real" de
     *  los juegos que encaje aquí - esto es solo flavor coleccionable) para que la probabilidad
     *  POR OBJETO INDIVIDUAL baje con la rareza pese a que Legendario tenga muchos menos objetos
     *  que Común (3 contra 47) - ver [dropChancePercent]: Común≈1,14%, Poco común≈0,68%,
     *  Raro=0,56%, Épico≈0,07%, Legendario≈0,03% por objeto - estrictamente decreciente.
     *  Épico/Legendario bajados aparte (pedido explícito del usuario: "los items de la mazmorra,
     *  los legendarios y epicos hazlos mucho mas raros" - antes 3%/1% de peso de categoría, ahora
     *  0,5%/0,1%, ~6x y ~10x mas raros por objeto que antes) - el hueco que dejan se lo queda
     *  Común, no se reparte entre las tres categorías porque no cambia el orden ni la sensación de
     *  progresión, solo hace que lo comun aparezca un poco mas seguido para compensar. Legendario
     *  (pedido explícito del usuario: "que haya algún item legendario") es SOLO para los 3 orbes
     *  ligados a un legendario concreto (Dialga/Palkia/Giratina) - el resto de objetos con sabor
     *  legendario/mítico (Lámina Legendaria, Corona/Oro Reliquia...) se quedan en Épico, un
     *  escalón por debajo. */
    enum class ItemRarity(val label: String, val tierWeightPercent: Float, val colorHex: String) {
        COMUN("Común", 53.4f, "#8A8D91"),
        POCO_COMUN("Poco común", 32f, "#3E9B4F"),
        RARO("Raro", 14f, "#2E86E0"),
        EPICO("Épico", 0.5f, "#B14FE0"),
        LEGENDARIO("Legendario", 0.1f, "#D9A521"),
    }

    data class DecorItemInfo(val fileName: String, val displayName: String, val rarity: ItemRarity)

    val DECOR_ITEMS: List<DecorItemInfo> = listOf(
        DecorItemInfo("ADAMANTORB", "Orbe Adamante", ItemRarity.LEGENDARIO),
        DecorItemInfo("AMULETCOIN", "Moneda Amuleto", ItemRarity.COMUN),
        DecorItemInfo("ARMORFOSSIL", "Fósil Armadura", ItemRarity.RARO),
        DecorItemInfo("BALMMUSHROOM", "Hongo Bálsamo", ItemRarity.COMUN),
        DecorItemInfo("BIGMUSHROOM", "Hongo Grande", ItemRarity.COMUN),
        DecorItemInfo("BIGNUGGET", "Pepita Grande", ItemRarity.RARO),
        DecorItemInfo("BIGPEARL", "Perla Grande", ItemRarity.COMUN),
        DecorItemInfo("BLACKAPRICORN", "Manzabaya Negra", ItemRarity.COMUN),
        DecorItemInfo("BLACKBELT", "Cinturón Negro", ItemRarity.COMUN),
        DecorItemInfo("BLACKGLASSES", "Gafas Negras", ItemRarity.COMUN),
        DecorItemInfo("BLACKSLUDGE", "Lodo Negro", ItemRarity.COMUN),
        DecorItemInfo("BLANKPLATE", "Lámina Vacía", ItemRarity.POCO_COMUN),
        DecorItemInfo("BLUEAPRICORN", "Manzabaya Azul", ItemRarity.COMUN),
        DecorItemInfo("BOTTLECAP", "Tapón de Botella", ItemRarity.COMUN),
        DecorItemInfo("CHARCOAL", "Carbón", ItemRarity.COMUN),
        DecorItemInfo("CHOICEBAND", "Cinta Elegida", ItemRarity.POCO_COMUN),
        DecorItemInfo("CHOICESCARF", "Bufanda Elegida", ItemRarity.POCO_COMUN),
        DecorItemInfo("CHOICESPECS", "Lentes Elegidos", ItemRarity.POCO_COMUN),
        DecorItemInfo("CLAWFOSSIL", "Fósil Garra", ItemRarity.RARO),
        DecorItemInfo("COVERFOSSIL", "Fósil Cubierta", ItemRarity.RARO),
        DecorItemInfo("DAWNSTONE", "Piedra Alba", ItemRarity.POCO_COMUN),
        DecorItemInfo("DEEPSEASCALE", "Escama Abisal", ItemRarity.RARO),
        DecorItemInfo("DEEPSEATOOTH", "Colmillo Abisal", ItemRarity.RARO),
        DecorItemInfo("DOMEFOSSIL", "Fósil Cúpula", ItemRarity.RARO),
        DecorItemInfo("DRACOPLATE", "Lámina Dracónica", ItemRarity.POCO_COMUN),
        DecorItemInfo("DRAGONFANG", "Colmillo Dragón", ItemRarity.POCO_COMUN),
        DecorItemInfo("DRAGONSCALE", "Escama Dragón", ItemRarity.POCO_COMUN),
        DecorItemInfo("DREADPLATE", "Lámina Siniestra", ItemRarity.POCO_COMUN),
        DecorItemInfo("DUBIOUSDISC", "Disco Extraño", ItemRarity.POCO_COMUN),
        DecorItemInfo("DUSKSTONE", "Piedra Umbría", ItemRarity.POCO_COMUN),
        DecorItemInfo("EARTHPLATE", "Lámina Terrestre", ItemRarity.POCO_COMUN),
        DecorItemInfo("ELECTIRIZER", "Electrizador", ItemRarity.POCO_COMUN),
        DecorItemInfo("EVERSTONE", "Piedra Firme", ItemRarity.POCO_COMUN),
        DecorItemInfo("EXPERTBELT", "Cinta Experta", ItemRarity.POCO_COMUN),
        DecorItemInfo("FIRESTONE", "Piedra Fuego", ItemRarity.POCO_COMUN),
        DecorItemInfo("FISTPLATE", "Lámina Puño", ItemRarity.POCO_COMUN),
        DecorItemInfo("FLAMEPLATE", "Lámina Llama", ItemRarity.POCO_COMUN),
        DecorItemInfo("FOCUSBAND", "Banda Focus", ItemRarity.COMUN),
        DecorItemInfo("FOCUSSASH", "Cinta Focus", ItemRarity.POCO_COMUN),
        DecorItemInfo("FOSSILIZEDBIRD", "Fósil de Ave", ItemRarity.RARO),
        DecorItemInfo("FOSSILIZEDDINO", "Fósil de Dinosaurio", ItemRarity.RARO),
        DecorItemInfo("FOSSILIZEDDRAKE", "Fósil de Dragón", ItemRarity.RARO),
        DecorItemInfo("FOSSILIZEDFISH", "Fósil de Pez", ItemRarity.RARO),
        DecorItemInfo("GOLDBOTTLECAP", "Tapón Dorado", ItemRarity.EPICO),
        DecorItemInfo("GREENAPRICORN", "Manzabaya Verde", ItemRarity.COMUN),
        DecorItemInfo("GRISEOUSORB", "Orbe Griseo", ItemRarity.LEGENDARIO),
        DecorItemInfo("HARDSTONE", "Piedra Dura", ItemRarity.COMUN),
        DecorItemInfo("HEARTSCALE", "Escama Bella", ItemRarity.COMUN),
        DecorItemInfo("HELIXFOSSIL", "Fósil Hélix", ItemRarity.RARO),
        DecorItemInfo("HISUIANAPRICORN", "Manzabaya de Hisui", ItemRarity.COMUN),
        DecorItemInfo("ICESTONE", "Piedra Hielo", ItemRarity.POCO_COMUN),
        DecorItemInfo("ICICLEPLATE", "Lámina Glacial", ItemRarity.POCO_COMUN),
        DecorItemInfo("INSECTPLATE", "Lámina Insecto", ItemRarity.POCO_COMUN),
        DecorItemInfo("IRONPLATE", "Lámina de Hierro", ItemRarity.POCO_COMUN),
        DecorItemInfo("JAWFOSSIL", "Fósil Mandíbula", ItemRarity.RARO),
        DecorItemInfo("KINGSROCK", "Roca del Rey", ItemRarity.POCO_COMUN),
        DecorItemInfo("LEAFSTONE", "Piedra Hoja", ItemRarity.POCO_COMUN),
        DecorItemInfo("LEFTOVERS", "Restos", ItemRarity.COMUN),
        DecorItemInfo("LEGENDPLATE", "Lámina Legendaria", ItemRarity.EPICO),
        DecorItemInfo("LIFEORB", "Orbe Vida", ItemRarity.POCO_COMUN),
        DecorItemInfo("LINKINGCORD", "Cuerda de Unión", ItemRarity.EPICO),
        DecorItemInfo("LUCKYEGG", "Huevo Suerte", ItemRarity.COMUN),
        DecorItemInfo("LUSTROUSORB", "Orbe Lustroso", ItemRarity.LEGENDARIO),
        DecorItemInfo("MAGMARIZER", "Magmatizador", ItemRarity.POCO_COMUN),
        DecorItemInfo("MAGNET", "Imán", ItemRarity.COMUN),
        DecorItemInfo("MEADOWPLATE", "Lámina Pradera", ItemRarity.POCO_COMUN),
        DecorItemInfo("METALCOAT", "Recubrimiento Metálico", ItemRarity.POCO_COMUN),
        DecorItemInfo("METRONOME", "Metrónomo", ItemRarity.COMUN),
        DecorItemInfo("MINDPLATE", "Lámina Mental", ItemRarity.POCO_COMUN),
        DecorItemInfo("MIRACLESEED", "Semilla Milagro", ItemRarity.POCO_COMUN),
        DecorItemInfo("MOONSTONE", "Piedra Lunar", ItemRarity.POCO_COMUN),
        DecorItemInfo("MUSCLEBAND", "Banda Músculo", ItemRarity.COMUN),
        DecorItemInfo("MYSTICWATER", "Agua Mística", ItemRarity.COMUN),
        DecorItemInfo("NEVERMELTICE", "Hielo Eterno", ItemRarity.COMUN),
        DecorItemInfo("NUGGET", "Pepita", ItemRarity.COMUN),
        DecorItemInfo("ODDKEYSTONE", "Piedra Clave Rara", ItemRarity.EPICO),
        DecorItemInfo("OLDAMBER", "Ámbar Antiguo", ItemRarity.RARO),
        DecorItemInfo("OLDSEAMAP", "Mapa Marino Viejo", ItemRarity.RARO),
        DecorItemInfo("OVALSTONE", "Piedra Oval", ItemRarity.POCO_COMUN),
        DecorItemInfo("PEARL", "Perla", ItemRarity.COMUN),
        DecorItemInfo("PEARLSTRING", "Sarta de Perlas", ItemRarity.RARO),
        DecorItemInfo("PEATBLOCK", "Bloque de Turba", ItemRarity.COMUN),
        DecorItemInfo("PINKAPRICORN", "Manzabaya Rosa", ItemRarity.COMUN),
        DecorItemInfo("PIXIEPLATE", "Lámina Hada", ItemRarity.POCO_COMUN),
        DecorItemInfo("PLUMEFOSSIL", "Fósil Plumón", ItemRarity.RARO),
        DecorItemInfo("POISONBARB", "Púa Veneno", ItemRarity.COMUN),
        DecorItemInfo("PRISMSCALE", "Escama Prisma", ItemRarity.COMUN),
        DecorItemInfo("PROTECTOR", "Protector", ItemRarity.POCO_COMUN),
        DecorItemInfo("QUICKCLAW", "Garra Rápida", ItemRarity.COMUN),
        DecorItemInfo("RAREBONE", "Hueso Raro", ItemRarity.COMUN),
        DecorItemInfo("RAZORCLAW", "Garra Filo", ItemRarity.COMUN),
        DecorItemInfo("RAZORFANG", "Colmillo Filo", ItemRarity.COMUN),
        DecorItemInfo("REAPERCLOTH", "Tela Sudario", ItemRarity.POCO_COMUN),
        DecorItemInfo("REDAPRICORN", "Manzabaya Roja", ItemRarity.COMUN),
        DecorItemInfo("RELICBAND", "Brazalete Reliquia", ItemRarity.RARO),
        DecorItemInfo("RELICCOPPER", "Cobre Reliquia", ItemRarity.RARO),
        DecorItemInfo("RELICCROWN", "Corona Reliquia", ItemRarity.EPICO),
        DecorItemInfo("RELICGOLD", "Oro Reliquia", ItemRarity.EPICO),
        DecorItemInfo("RELICSILVER", "Plata Reliquia", ItemRarity.RARO),
        DecorItemInfo("RELICSTATUE", "Estatuilla Reliquia", ItemRarity.EPICO),
        DecorItemInfo("RELICVASE", "Vasija Reliquia", ItemRarity.RARO),
        DecorItemInfo("ROOTFOSSIL", "Fósil Raíz", ItemRarity.RARO),
        DecorItemInfo("SAILFOSSIL", "Fósil Vela", ItemRarity.RARO),
        DecorItemInfo("SHARPBEAK", "Pico Afilado", ItemRarity.COMUN),
        DecorItemInfo("SHELLBELL", "Cascabel", ItemRarity.COMUN),
        DecorItemInfo("SHINYSTONE", "Piedra Brillante", ItemRarity.POCO_COMUN),
        DecorItemInfo("SHOALSALT", "Sal de Banco", ItemRarity.COMUN),
        DecorItemInfo("SHOALSHELL", "Concha de Banco", ItemRarity.COMUN),
        DecorItemInfo("SILVERPOWDER", "Polvo Plata", ItemRarity.COMUN),
        DecorItemInfo("SKULLFOSSIL", "Fósil Cráneo", ItemRarity.RARO),
        DecorItemInfo("SKYPLATE", "Lámina Celeste", ItemRarity.POCO_COMUN),
        DecorItemInfo("SOFTSAND", "Arena Suave", ItemRarity.COMUN),
        DecorItemInfo("SPELLTAG", "Amuleto Maldito", ItemRarity.COMUN),
        DecorItemInfo("SPLASHPLATE", "Lámina Acuática", ItemRarity.POCO_COMUN),
        DecorItemInfo("SPOOKYPLATE", "Lámina Fantasma", ItemRarity.POCO_COMUN),
        DecorItemInfo("STARDUST", "Polvo Estelar", ItemRarity.COMUN),
        DecorItemInfo("STARPIECE", "Fragmento Estelar", ItemRarity.RARO),
        DecorItemInfo("STONEPLATE", "Lámina Rocosa", ItemRarity.POCO_COMUN),
        DecorItemInfo("SUNSTONE", "Piedra Solar", ItemRarity.POCO_COMUN),
        DecorItemInfo("THUNDERSTONE", "Piedra Trueno", ItemRarity.POCO_COMUN),
        DecorItemInfo("TINYMUSHROOM", "Hongo Pequeño", ItemRarity.COMUN),
        DecorItemInfo("TOXICPLATE", "Lámina Tóxica", ItemRarity.POCO_COMUN),
        DecorItemInfo("TWISTEDSPOON", "Cuchara Torcida", ItemRarity.COMUN),
        DecorItemInfo("UPGRADE", "Mejora", ItemRarity.POCO_COMUN),
        DecorItemInfo("WATERSTONE", "Piedra Agua", ItemRarity.POCO_COMUN),
        DecorItemInfo("WHITEAPRICORN", "Manzabaya Blanca", ItemRarity.COMUN),
        DecorItemInfo("WISEGLASSES", "Gafas Sabias", ItemRarity.COMUN),
        DecorItemInfo("YELLOWAPRICORN", "Manzabaya Amarilla", ItemRarity.COMUN),
        DecorItemInfo("ZAPPLATE", "Lámina Eléctrica", ItemRarity.POCO_COMUN),
    )

    private val byTier: Map<ItemRarity, List<DecorItemInfo>> by lazy { DECOR_ITEMS.groupBy { it.rarity } }
    private val byFileName: Map<String, DecorItemInfo> by lazy { DECOR_ITEMS.associateBy { it.fileName } }

    fun infoFor(fileName: String?): DecorItemInfo? = fileName?.let { byFileName[it] }

    /** Probabilidad (%) de que salga ESTE objeto EN CONCRETO cuando toca un decorativo - el peso
     *  de su categoría repartido a partes iguales entre todos los objetos de esa categoría (ver
     *  comentario de [ItemRarity]). Esto es lo que se enseña en la pokedex de objetos junto a
     *  cada uno - pedido explícito del usuario: "que en la pokedex aparezca la probabilidad de
     *  salir". */
    fun dropChancePercent(info: DecorItemInfo): Float {
        val tierCount = (byTier[info.rarity]?.size ?: 1).coerceAtLeast(1)
        return info.rarity.tierWeightPercent / tierCount
    }

    /** Sorteo ponderado por categoría (Común > Poco común > Raro > Épico) y uniforme dentro de
     *  ella - sustituye al sorteo uniforme de siempre (randomOfRole(context,"decor")), que
     *  trataba los 129 objetos exactamente igual de probables. */
    fun pickRandomDecorItem(): String {
        val total = ItemRarity.values().sumOf { it.tierWeightPercent.toDouble() }
        var r = Random.nextDouble() * total
        var tier = ItemRarity.values().last()
        for (t in ItemRarity.values()) {
            r -= t.tierWeightPercent
            if (r <= 0.0) { tier = t; break }
        }
        return byTier.getValue(tier).random().fileName
    }
}
