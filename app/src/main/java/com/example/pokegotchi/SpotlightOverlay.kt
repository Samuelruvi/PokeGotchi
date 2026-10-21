package com.example.pokegotchi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Pista sobre la interfaz REAL (pedido del usuario, 2026-10-17: "que se vea la propia interfaz y que resalte esos botones" en vez
 * de una ventana aparte): una capa que oscurece la pantalla dejando un hueco con borde dorado sobre el elemento que se explica, y
 * una tarjeta con el texto y "Siguiente". Cada paso apunta a una vista distinta (o a ninguna: tarjeta centrada); el hueco se
 * desliza de un elemento al siguiente. Se añade a la ventana de la actividad o de un dialogo (ver [TutorialTips.show]).
 *
 * La capa bloquea los toques de lo que hay debajo mientras dura (hay que pulsar "Siguiente"/"¡Entendido!" o "Saltar"). El
 * hueco se recalcula en cada fotograma desde la vista de destino, asi que sigue al elemento si se mueve o la pantalla cambia.
 */
class SpotlightOverlay(
    context: Context,
    private val tip: TutorialTips.Tip,
    private val steps: List<TutorialTips.Step>,
    private val resolve: (String) -> View?,
    private val onEnd: (finished: Boolean) -> Unit
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    var index = 0
        private set
    private var target: View? = null
    private var prevRect: RectF? = null
    private var holeT = 1f
    private var holeAnim: ValueAnimator? = null
    private var pulse = 0f
    private var pulseAnim: ValueAnimator? = null
    private var ended = false
    private var downInHole = false
    private var eventArg: String? = null   // dato que trae el suceso que desbloquea un paso (p. ej. el nombre del Pokemon elegido)

    /** Que hacer al terminar de desvanecerse: por defecto quitarse de su ventana; si vive en un dialogo propio, cerrarlo. */
    var onRemove: (() -> Unit)? = null

    private val scrimPaint = Paint().apply { color = Color.argb(178, 12, 10, 18) }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#C9A227"); strokeWidth = dp(3f) }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#C9A227") }

    private val card = LinearLayout(context)
    private val titleView = TextView(context)
    private val bodyView = TextView(context)
    private val counterView = TextView(context)
    private val skipView = TextView(context)
    private val nextButton = Button(context)

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)

        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f).toInt(), dp(14f).toInt(), dp(18f).toInt(), dp(12f).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26222E"))
                cornerRadius = dp(18f)
                setStroke(dp(2f).toInt(), Color.parseColor("#C9A227"))
            }
            elevation = dp(8f)
        }
        titleView.apply {
            text = "${tip.emoji}  ${tip.title}"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        bodyView.apply {
            textSize = 14.5f
            setTextColor(Color.parseColor("#E6E1EF"))
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(8f).toInt(), 0, dp(10f).toInt())
        }
        counterView.apply { textSize = 12f; setTextColor(Color.parseColor("#9A93AB")) }
        skipView.apply {
            text = "Saltar"
            textSize = 13f
            setTextColor(Color.parseColor("#B8B0C8"))
            setPadding(dp(4f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            setOnClickListener { finish(finished = true) }
        }
        nextButton.apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#1D1B22"))
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#C9A227")); cornerRadius = dp(12f) }
            minHeight = 0; minimumHeight = 0
            setPadding(dp(18f).toInt(), dp(8f).toInt(), dp(18f).toInt(), dp(8f).toInt())
            setOnClickListener { advance() }
        }
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(counterView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(skipView)
            addView(nextButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f).toInt()))
        }
        card.addView(titleView)
        card.addView(bodyView)
        card.addView(bottom)
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // El borde del hueco "respira" para que se vea que es lo que se esta explicando.
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
        showStep(0, animate = false)
    }

    /** Pasa al paso [i]: nueva vista de destino, texto y botones; el hueco se desliza desde donde estaba. */
    private fun showStep(i: Int, animate: Boolean) {
        index = i.coerceIn(0, steps.size - 1)
        val step = steps[index]
        prevRect = if (animate) shownRect() else null
        target = step.target?.let { resolve(it) }
        bodyView.text = step.text.replace("{arg}", eventArg ?: "el Pokémon elegido")
        val last = index == steps.size - 1
        // Un paso "toca": hay que pulsar de verdad el elemento resaltado (se le pasa el toque); no hay "Siguiente".
        val tapStep = step.tap && target != null
        nextButton.text = if (last) "¡Entendido!" else "Siguiente ▸"
        nextButton.visibility = if (tapStep) View.GONE else View.VISIBLE
        skipView.visibility = if ((last && !tapStep) || (steps.size == 1 && !tapStep)) View.GONE else View.VISIBLE
        counterView.text = if (tapStep) (step.hint ?: "👆 Toca lo resaltado") else if (steps.size > 1) "${index + 1} / ${steps.size}" else ""
        holeAnim?.cancel()
        if (animate) {
            holeT = 0f
            holeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320; interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { holeT = it.animatedValue as Float; invalidate() }
                start()
            }
        } else holeT = 1f
        requestLayout()
        invalidate()
        // La tarjeta entra con un pequeño deslizamiento y fundido en cada paso.
        card.alpha = if (animate) 0f else 1f
        card.animate().cancel()
        if (animate) card.animate().alpha(1f).setDuration(260L).setStartDelay(80L).start()
        else {
            // Primera vez: aparece la capa entera con un fundido.
            alpha = 0f
            animate().alpha(1f).setDuration(260L).start()
        }
    }

    /** Para las pruebas: salta directamente al paso [i] sin animaciones (y deja la capa a opacidad completa). */
    fun debugGoTo(i: Int) {
        animate().cancel(); alpha = 1f
        showStep(i, animate = false)
        card.animate().cancel(); card.alpha = 1f
    }

    val stepCount: Int get() = steps.size

    /** Para las pruebas: hace lo mismo que un toque dentro del hueco en un paso "toca" (cierra la pista y pulsa el elemento). */
    fun debugTapTarget() {
        val t = target
        finish(finished = true)
        t?.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // En un paso "toca" el toque dentro del hueco llega al elemento de verdad; fuera del hueco no pasa nada. Si el hueco es un
        // contenedor (p. ej. la rejilla de candidatos del regalo) se pulsa lo que haya justo debajo del dedo.
        val step = steps[index]
        if (step.tap && target != null) {
            val hole = targetRect()
            val inHole = hole != null && hole.contains(event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downInHole = inHole
                MotionEvent.ACTION_UP -> if (downInHole && inHole) {
                    val root = target
                    val hit = if (root != null) findClickableAt(root, event.rawX.toInt(), event.rawY.toInt()) ?: root else null
                    // Con "waitFor" la pista sigue ahi (lo pulsado puede pedir confirmacion y se puede cancelar) hasta que ocurra
                    // el suceso; sin el, pulsar es lo que cierra este paso.
                    if (step.waitFor == null) finish(finished = true)
                    hit?.performClick()
                }
                MotionEvent.ACTION_CANCEL -> downInHole = false
            }
        }
        return true
    }

    /** Lo pulsable mas profundo de [v] que cae en el punto de pantalla ([x], [y]), o null. */
    private fun findClickableAt(v: View, x: Int, y: Int): View? {
        if (!v.isShown) return null
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (x < loc[0] || x > loc[0] + v.width || y < loc[1] || y > loc[1] + v.height) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) findClickableAt(v.getChildAt(i), x, y)?.let { return it }
        }
        return if (v.isClickable) v else null
    }

    /** Un suceso de la app (p. ej. "gift_picked"): si el paso actual lo estaba esperando, pasa al siguiente con [arg] en su texto. */
    fun onEvent(name: String, arg: String?) {
        if (ended || steps[index].waitFor != name) return
        eventArg = arg
        if (index < steps.size - 1) showStep(index + 1, animate = true) else finish(finished = true)
    }

    private fun advance() {
        if (index >= steps.size - 1) finish(finished = true) else showStep(index + 1, animate = true)
    }

    private fun finish(finished: Boolean) {
        if (ended) return
        ended = true
        pulseAnim?.cancel(); holeAnim?.cancel()
        onEnd(finished)
        animate().alpha(0f).setDuration(180L).withEndAction { onRemove?.invoke() ?: (parent as? ViewGroup)?.removeView(this) }.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnim?.cancel(); holeAnim?.cancel()
        // Si la pantalla se cierra con la pista a medias, no cuenta como vista: volvera a salir.
        if (!ended) { ended = true; onEnd(false) }
    }

    // ---- geometria ----

    /** Rectangulo (en coordenadas de esta capa) de la vista de destino con un poco de margen, o null si no hay o no se ve. */
    private fun targetRect(): RectF? {
        val t = target ?: return null
        if (!t.isShown || t.width <= 0 || t.height <= 0) return null
        val loc = IntArray(2); t.getLocationOnScreen(loc)
        val me = IntArray(2); getLocationOnScreen(me)
        val pad = dp(6f)
        val r = RectF(loc[0] - me[0] - pad, loc[1] - me[1] - pad, loc[0] - me[0] + t.width + pad, loc[1] - me[1] + t.height + pad)
        return if (r.intersect(dp(3f), dp(3f), width - dp(3f), height - dp(3f))) r else null
    }

    /** El hueco tal como se dibuja ahora: el del destino, o a medio camino desde el del paso anterior. */
    private fun shownRect(): RectF? {
        val cur = targetRect()
        val prev = prevRect
        if (holeT >= 1f || (prev == null && cur == null)) return cur
        val k = holeT
        fun around(r: RectF) = RectF(r.centerX(), r.centerY(), r.centerX(), r.centerY())
        val a = prev ?: around(cur!!)
        val b = cur ?: around(prev!!)
        return RectF(a.left + (b.left - a.left) * k, a.top + (b.top - a.top) * k, a.right + (b.right - a.right) * k, a.bottom + (b.bottom - a.bottom) * k)
            .takeIf { cur != null || k < 0.98f }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Tarjeta de ancho comodo en el movil y sin pasarse en pantallas anchas.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val compact = w < dp(380f)   // p. ej. la ventana flotante de la mazmorra
        bodyView.textSize = if (compact) 13.5f else 14.5f
        titleView.textSize = if (compact) 15.5f else 17f
        (card.layoutParams as LayoutParams).apply {
            width = min(w - dp(32f).toInt(), dp(440f).toInt()).coerceAtLeast(dp(220f).toInt())
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionCard()
    }

    /** Coloca la tarjeta debajo del hueco si este esta en la mitad de arriba, o encima si esta abajo; centrada si no hay hueco. */
    private fun positionCard() {
        val h = height.toFloat()
        if (h <= 0f || card.height <= 0) return
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(this)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val safeTop = (insets?.top ?: 0) + dp(12f)
        val safeBottom = h - (insets?.bottom ?: 0) - dp(12f)
        val cardH = card.height.toFloat()
        val hole = targetRect()
        var y: Float
        if (hole == null) y = (h - cardH) / 2f
        else {
            // Debajo o encima del hueco, donde quepa sin taparlo; si cabe en los dos, segun la mitad de la pantalla en que este; si en
            // ninguno, donde haya mas sitio (se tapara un poco).
            val gap = dp(14f)
            val spaceAbove = hole.top - gap - safeTop
            val spaceBelow = safeBottom - (hole.bottom + gap)
            val below = when {
                spaceBelow >= cardH && spaceAbove >= cardH -> hole.centerY() < h / 2f
                spaceBelow >= cardH -> true
                spaceAbove >= cardH -> false
                else -> spaceBelow >= spaceAbove
            }
            y = if (below) hole.bottom + gap else hole.top - gap - cardH
        }
        y = y.coerceIn(safeTop, (safeBottom - cardH).coerceAtLeast(safeTop))
        card.translationY = y - card.top
    }

    override fun dispatchDraw(canvas: Canvas) {
        // El hueco se sigue recalculando solo; la tarjeta se recoloca con el (puede haberse movido el destino).
        positionCard()
        super.dispatchDraw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val hole = shownRect()
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
        val radius = if (hole != null) min(dp(14f), min(hole.width(), hole.height()) / 2f) else 0f
        if (hole != null) canvas.drawRoundRect(hole, radius, radius, clearPaint)
        canvas.restoreToCount(layer)
        if (hole != null) {
            // Aro dorado + halo que pulsa hacia fuera.
            haloPaint.strokeWidth = dp(2f)
            haloPaint.alpha = (150 * (1f - pulse)).toInt()
            val grow = dp(2f) + dp(6f) * pulse
            canvas.drawRoundRect(RectF(hole.left - grow, hole.top - grow, hole.right + grow, hole.bottom + grow), radius + grow, radius + grow, haloPaint)
            canvas.drawRoundRect(hole, radius, radius, ringPaint)
        }
    }
}
