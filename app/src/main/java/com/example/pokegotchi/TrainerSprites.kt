package com.example.pokegotchi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Los 129 retratos de entrenador seleccionables (carpeta assets/trainers, PNG pixel-art
 *  ~160x160 con fondo transparente) - locales, empaquetados en la app, sin red de por medio.
 *  Originalmente 165: se quitaron 36 en dos rondas, ambas verificadas viendo el PNG de verdad,
 *  no solo el nombre - (1) 30 que resultaron ser PIXEL A PIXEL identicos a otro fichero ya
 *  presente (ROJO1..ROJO5 eran la misma imagen exacta, igual que LIDER#REVANCHA con su LIDER#
 *  base), (2) 6 mas (las variantes POKEMONTRAINER_Rojo y POKEMONTRAINER_Hoja) que eran la MISMA
 *  pose/outfit que ROJO1/HOJA1, solo con un tono de piel distinto - pedido explicito del usuario
 *  tras verlos repetidos en la rejilla.
 *
 *  SIN categorias (Alto Mando/Lider de Gimnasio/etc.) a proposito: se probaron, pero el usuario
 *  detecto un personaje real (un tal "Liam", de Galar) metido en "Entrenadores genericos" solo
 *  porque el nombre del fichero no lo delataba - sin saber de que juego/hack viene cada sprite,
 *  cualquier categoria por rol es una suposicion que puede quedar mal, y eso no es fidedigno.
 *  Pedido explicito del usuario: "quitale la categoria a todos... si no, no va a ser fidedigno".
 *  Por el mismo motivo, displayNameFor tampoco inventa un ROL (nada de "Lider de Gimnasio N" ni
 *  "Alto Mando") - solo pone el nombre real de los personajes que se pueden confirmar sin dudas
 *  (Ash, Oak, Bill, Giovanni, Cynthia, Agatha, Yellow, Rojo/Azul/Hoja/Prisma - protagonistas
 *  conocidos), el resto se queda con una etiqueta neutra derivada del propio fichero. Si el
 *  usuario reconoce a alguien mas, se corrige puntualmente (ver displayNameFor). */
object TrainerSprites {
    fun list(context: Context): List<String> =
        (context.assets.list("trainers") ?: emptyArray())
            .filter { it.endsWith(".png") }
            .map { it.removeSuffix(".png") }
            .sorted()

    fun bitmap(context: Context, key: String): Bitmap? =
        try {
            context.assets.open("trainers/$key.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }

    // Genero verificado VIENDO cada sprite de verdad (montaje de contacto con los 129 retratos,
    // revisado uno a uno) - pedido explicito del usuario tras ver muchos mal ("veo muchos chicos
    // que son chicas y viceversa") con la primera version, que era solo una suposicion por
    // terminacion de nombre (-a/-o) y lo que se sabia de cada personaje SIN mirar el dibujo. Esa
    // primera pasada acertaba mas o menos la mitad por pura coincidencia - esta lista es la
    // corregida contra el dibujo real, no una suposicion.
    private val FEMALE_KEYS = setOf(
        // Protagonistas: Hoja/Leaf y Yellow son chicas en los juegos/manga; Prisma(Crystal)/
        // PrismF tambien (confirmado por el dibujo: pose/atuendo femenino).
        "HOJA1", "POKEMONTRAINER_Yellow", "PRISMA", "PRISMF",
        // Alto Mando: Agatha, dibujo de mujer mayor con vestido. (ALTOMANDO1 confirmado CHICO
        // viendo el sprite - pelo corto, complexion masculina - se habia asumido mal antes).
        "AGATHA1",
        // Lideres de gimnasio: confirmados viendo cada dibujo, no por la suposicion de orden de
        // gimnasio original (que resulto mal para varios Hoenn).
        "LIDER2", "LIDER4", "LIDER6", "LIDER2HOENN", "LIDER3HOENN", "LIDER5HOENN", "LIDER7HOENN",
        // Campeona Cynthia (Cintia).
        "CINTIA",
        // Team Rocket: variante femenina explicita (-A), confirmada por el dibujo.
        "ROCKETA",
        // Personajes especiales: Atenea (mujer con abrigo/sombrero); Surya (chica de pelo rosa,
        // no el dios hindu masculino que sugeria el nombre por si solo).
        "ATENEA1", "SURYA",
        // Entrenadores genericos, confirmados por el dibujo (no solo por terminacion -a/-o, que
        // resulto ser una pista poco fiable - CAMPISTA y MACARRA, pese a acabar en "-a", son
        // dibujos de chico).
        "BRUJITA", "CABALLERA", "CAZABICHAS", "CHICA", "CIENTIFICA", "DAMISELA",
        "ESTUDIANTA", "EXORCISTA", "FOTOGRAFA", "GUAYA", "JOVENRICA", "KARATEKAA",
        "MECANICA", "MEDIUM", "MODELO", "NADADORA", "NINJAA", "PESCADORA", "VETERANA",
        "EXTRA38", "EXTRA39", "POKEMANIACO",
        // TB1..TB36: sin ninguna pista en el nombre, cada uno visto de verdad en el montaje.
        "TB1", "TB6", "TB7", "TB9", "TB11", "TB14", "TB16", "TB17", "TB20", "TB22",
        "TB24", "TB26", "TB30", "TB32", "TB33",
    )

    fun genderFor(key: String): String = if (key in FEMALE_KEYS) "chica" else "chico"

    // Nombre de personaje a mostrar bajo el retrato en la rejilla (pedido explicito del usuario:
    // "ponle el nombre del personaje") - derivado del propio nombre de fichero, con los
    // personajes reconocibles puestos con su nombre real en vez del codigo interno.
    private val ACCENT_FIXES = mapOf(
        "CIENTIFICA" to "Científica", "CIENTIFICO" to "Científico", "TECNICO" to "Técnico",
        "ORNITOLOGO" to "Ornitólogo", "FOTOGRAFA" to "Fotógrafa", "MECANICA" to "Mecánica",
        "MECANICO" to "Mecánico", "MONTANERO" to "Montañero", "LADRON" to "Ladrón",
        "MATON" to "Matón", "JUGON" to "Jugón", "KARATEKA" to "Karateka", "KARATEKAA" to "Karateka",
        "NINJA" to "Ninja", "NINJAA" to "Ninja", "JOVENRICA" to "Joven Rica",
    )

    fun displayNameFor(key: String): String {
        val k = key.uppercase()
        return when {
            // Solo identidades CONFIRMADAS sin dudas (protagonistas/personajes conocidos de los
            // juegos/manga principales) - nada de roles adivinados (Lider de Gimnasio, Alto
            // Mando...), ver comentario de cabecera.
            k == "OAK" -> "Profesor Oak"
            k == "ASH" -> "Ash"
            k == "BILL" -> "Bill"
            k.startsWith("AGATHA") -> "Agatha"
            k.startsWith("GIOVANNI") -> "Giovanni"
            k == "CINTIA" -> "Cynthia"
            k == "POKEMONTRAINER_YELLOW" -> "Yellow"
            k.startsWith("ROJO") -> "Rojo"
            k.startsWith("AZUL") -> "Azul"
            k.startsWith("HOJA") -> "Hoja"
            k.startsWith("PRISM") -> "Prisma"
            k.startsWith("TB") && k.removePrefix("TB").toIntOrNull() != null -> "Entrenador ${k.removePrefix("TB")}"
            k.startsWith("EXTRA") -> "Entrenador"
            else -> ACCENT_FIXES[k] ?: key.lowercase().replaceFirstChar { it.titlecase() }
        }
    }
}
