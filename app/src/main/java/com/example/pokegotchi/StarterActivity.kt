package com.example.pokegotchi

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest

/**
 * Pantalla de bienvenida: elegir el Pokemon inicial (los 3 de cada generacion + Pikachu/Eevee).
 * Se muestra SOLO la primera vez (PetState.hasChosenStarter). Al elegir uno, se descarga su
 * sprite, se marca como Pokemon activo (lo que ya lo desbloquea en la Pokedex) y se vuelve a
 * MainActivity, que a partir de ahora ya no redirigira aqui.
 */
class StarterActivity : AppCompatActivity() {

    private data class Starter(val id: Int, val name: String, val gen: String)

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Mon(val s: Starter) : Row()
    }

    // Los 3 iniciales de cada generacion (Kanto..Paldea) + Pikachu y Eevee, con permiso
    // explicito del usuario para incluirlos junto a los iniciales "de verdad".
    private val starters = listOf(
        Starter(1, "bulbasaur", "Gen. 1"), Starter(4, "charmander", "Gen. 1"), Starter(7, "squirtle", "Gen. 1"),
        Starter(152, "chikorita", "Gen. 2"), Starter(155, "cyndaquil", "Gen. 2"), Starter(158, "totodile", "Gen. 2"),
        Starter(252, "treecko", "Gen. 3"), Starter(255, "torchic", "Gen. 3"), Starter(258, "mudkip", "Gen. 3"),
        Starter(387, "turtwig", "Gen. 4"), Starter(390, "chimchar", "Gen. 4"), Starter(393, "piplup", "Gen. 4"),
        Starter(495, "snivy", "Gen. 5"), Starter(498, "tepig", "Gen. 5"), Starter(501, "oshawott", "Gen. 5"),
        Starter(650, "chespin", "Gen. 6"), Starter(653, "fennekin", "Gen. 6"), Starter(656, "froakie", "Gen. 6"),
        Starter(722, "rowlet", "Gen. 7"), Starter(725, "litten", "Gen. 7"), Starter(728, "popplio", "Gen. 7"),
        Starter(810, "grookey", "Gen. 8"), Starter(813, "scorbunny", "Gen. 8"), Starter(816, "sobble", "Gen. 8"),
        Starter(906, "sprigatito", "Gen. 9"), Starter(909, "fuecoco", "Gen. 9"), Starter(912, "quaxly", "Gen. 9"),
        Starter(25, "pikachu", "Especiales"), Starter(133, "eevee", "Especiales")
    )

    private val rows = mutableListOf<Row>()
    private var picking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_starter)

        var lastGen = ""
        for (s in starters) {
            if (s.gen != lastGen) { rows.add(Row.Header(s.gen)); lastGen = s.gen }
            rows.add(Row.Mon(s))
        }

        val rv = findViewById<RecyclerView>(R.id.rv_starters)
        val glm = GridLayoutManager(this, 3)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (rows[position] is Row.Header) 3 else 1
        }
        rv.layoutManager = glm
        rv.adapter = StarterAdapter()
    }

    private fun onPick(s: Starter) {
        if (picking) return
        picking = true
        // Sin Toast de "preparando": el sprite viene de assets/localsprites (sin red) casi
        // siempre, y hasta cuando de verdad hace falta red tarda tan poco que avisar de ello
        // sobra - pedido explicito del usuario.
        Thread {
            val meta = SpriteRepository.ensure(this, s.name, s.id, PetState.currentStyle(this), PetState.isShiny(this, s.name), PetState.spriteScaleMode(this))
            runOnUiThread {
                if (meta != null) {
                    PetState.setPokemon(this, s.name, s.id)
                    PetState.markStarterChosen(this)
                    // Fondo a juego con el tipo del inicial elegido, gratis (pedido explicito
                    // del usuario - ver PetState.unlockStarterBackground).
                    PetState.unlockStarterBackground(this, s.name)
                    WidgetRefresh.updateWidgets(this)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    picking = false
                    Toast.makeText(this, "Sin sprite animado: ${s.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Mismo criterio que MainActivity: paquete LOCAL primero (sin red), y solo si esa especie
     *  no esta ahi se llama a [onNetworkFallback]. [isStillValid] protege la celda reciclada.
     *  Ya escalada segun el modo de suavizado elegido - ver loadLocalOrNetworkThumb de
     *  MainActivity, mismo motivo. */
    private fun loadLocalOrNetworkThumb(
        imageView: ImageView, name: String,
        isStillValid: () -> Boolean = { true },
        onNetworkFallback: () -> Unit
    ) {
        Thread {
            val bmp = SpriteRepository.localFirstFrameScaled(imageView.context, name, shiny = false, PetState.spriteScaleMode(imageView.context))
            runOnUiThread {
                if (!isStillValid()) return@runOnUiThread
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = false
                } else onNetworkFallback()
            }
        }.start()
    }

    private inner class StarterAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0
        private val TYPE_MON = 1

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) TYPE_HEADER else TYPE_MON

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER)
                HeaderVH(inf.inflate(R.layout.item_header, parent, false))
            else MonVH(inf.inflate(R.layout.item_pokemon, parent, false))
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).title.text = row.title
                is Row.Mon -> {
                    val vh = holder as MonVH
                    vh.name.text = "#${row.s.id} ${row.s.name}"
                    vh.lvl.text = ""
                    vh.thumb.setImageDrawable(null)
                    // Mismo fix que en MainActivity: peticion "a mano" + tag, para que una
                    // respuesta tardia de OTRO Pokemon sobre esta celda reciclada no se aplique.
                    val expected = row.s.name
                    vh.thumb.tag = expected
                    loadLocalOrNetworkThumb(vh.thumb, row.s.name, isStillValid = { vh.thumb.tag == expected }) {
                        val req = ImageRequest.Builder(this@StarterActivity)
                            .data("https://play.pokemonshowdown.com/sprites/gen5/${SpriteRepository.toShowdownSlug(row.s.name)}.png")
                            .target(
                                onSuccess = { d -> if (vh.thumb.tag == expected) vh.thumb.setImageDrawable(d) },
                                onError = { if (vh.thumb.tag == expected) vh.thumb.setImageResource(android.R.drawable.ic_menu_help) }
                            )
                            .build()
                        vh.thumb.context.imageLoader.enqueue(req)
                    }
                    vh.root.setOnClickListener { onPick(row.s) }
                }
            }
        }
    }

    private inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.header)
    }

    private inner class MonVH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.item_root)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val lvl: TextView = v.findViewById(R.id.lvl)
    }
}
