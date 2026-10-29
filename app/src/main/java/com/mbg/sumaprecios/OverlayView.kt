package com.mbg.sumaprecios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/** Dibuja sobre la cámara: zona de lectura, recuadros de números, pausa y selección. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    val zone = RectF()

    private var live: List<Candidate> = emptyList()
    private var blockedValues: Set<Long> = emptySet()
    private var choices: List<Candidate> = emptyList()
    private val chosen = mutableSetOf<Int>()
    private var cooldownStart = 0L
    private var cooldownEnd = 0L
    private var flashBox: Box? = null
    private var flashStart = 0L

    var hint: String = "Apunta a un precio"
        set(v) { field = v; invalidate() }

    var onChoiceTap: ((Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val ok = ContextCompat.getColor(context, R.color.ok)
    private val muted = ContextCompat.getColor(context, R.color.muted)

    private val dimPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4 * dp; strokeCap = Paint.Cap.ROUND
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val barBg = Paint().apply { color = Color.argb(90, 255, 255, 255) }
    private val barFg = Paint().apply { color = accent }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        zone.set(w * 0.07f, h * 0.14f, w * 0.93f, h * 0.80f)
    }

    fun setLive(c: List<Candidate>, blocked: Set<Long>) {
        live = c; blockedValues = blocked; invalidate()
    }

    fun setChoices(c: List<Candidate>) {
        choices = c; chosen.clear(); invalidate()
    }

    fun setChosen(i: Int, on: Boolean) {
        if (on) chosen += i else chosen -= i
        invalidate()
    }

    fun startCooldown(ms: Long) {
        cooldownStart = SystemClock.elapsedRealtime()
        cooldownEnd = cooldownStart + ms
        postInvalidateOnAnimation()
    }

    fun flash(b: Box) {
        flashBox = b; flashStart = SystemClock.elapsedRealtime(); postInvalidateOnAnimation()
    }

    private fun Box.rect(pad: Float = 0f) = RectF(left - pad, top - pad, right + pad, bottom + pad)

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val r = 14 * dp

        // Oscurece fuera de la zona de lectura
        canvas.save()
        canvas.clipOutRect(zone)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restore()

        // Esquinas de la zona
        val c = 26 * dp
        with(zone) {
            canvas.drawLine(left, top + c, left, top, cornerPaint); canvas.drawLine(left, top, left + c, top, cornerPaint)
            canvas.drawLine(right - c, top, right, top, cornerPaint); canvas.drawLine(right, top, right, top + c, cornerPaint)
            canvas.drawLine(right, bottom - c, right, bottom, cornerPaint); canvas.drawLine(right, bottom, right - c, bottom, cornerPaint)
            canvas.drawLine(left + c, bottom, left, bottom, cornerPaint); canvas.drawLine(left, bottom, left, bottom - c, cornerPaint)
        }

        val cooling = now < cooldownEnd

        if (choices.isNotEmpty()) {
            // Modo elegir: imagen congelada, recuadros que se pueden tocar
            choices.forEachIndexed { i, cand ->
                val rect = cand.box.rect(6 * dp)
                val on = i in chosen
                fillPaint.color = if (on) withAlpha(ok, 90) else withAlpha(accent, 55)
                canvas.drawRoundRect(rect, 8 * dp, 8 * dp, fillPaint)
                boxPaint.color = if (on) ok else accent
                canvas.drawRoundRect(rect, 8 * dp, 8 * dp, boxPaint)

                val label = (if (on) "✓ " else "") + money(cand.cents)
                val tw = labelPaint.measureText(label) + 16 * dp
                val ly = (rect.top - 8 * dp).coerceAtLeast(26 * dp)
                fillPaint.color = if (on) ok else accent
                canvas.drawRoundRect(rect.centerX() - tw / 2, ly - 22 * dp, rect.centerX() + tw / 2, ly + 4 * dp, r, r, fillPaint)
                labelPaint.color = Color.BLACK
                canvas.drawText(label, rect.centerX(), ly - 3 * dp, labelPaint)
            }
        } else {
            // Modo en vivo: recuadros de lo que está viendo
            for (cand in live) {
                boxPaint.color = when {
                    cand.cents in blockedValues -> muted
                    cooling -> muted
                    else -> accent
                }
                canvas.drawRoundRect(cand.box.rect(4 * dp), 6 * dp, 6 * dp, boxPaint)
            }
        }

        // Destello verde al apuntar un número
        flashBox?.let { fb ->
            val t = (now - flashStart) / 450f
            if (t < 1f) {
                fillPaint.color = withAlpha(ok, (150 * (1 - t)).toInt())
                canvas.drawRoundRect(fb.rect(8 * dp), 8 * dp, 8 * dp, fillPaint)
                postInvalidateOnAnimation()
            } else flashBox = null
        }

        // Barra de pausa
        if (cooling && choices.isEmpty()) {
            val frac = ((now - cooldownStart).toFloat() / (cooldownEnd - cooldownStart)).coerceIn(0f, 1f)
            val bh = 5 * dp
            val y = zone.bottom + 12 * dp
            canvas.drawRoundRect(zone.left, y, zone.right, y + bh, bh, bh, barBg)
            canvas.drawRoundRect(zone.left, y, zone.left + zone.width() * frac, y + bh, bh, bh, barFg)
            postInvalidateOnAnimation()
        }

        // Texto de ayuda encima de la zona
        val msg = when {
            choices.isNotEmpty() -> "Toca los precios que quieras sumar"
            cooling -> "Espera…"
            else -> hint
        }
        val tw = textPaint.measureText(msg) + 24 * dp
        val ty = zone.top - 16 * dp
        fillPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRoundRect(width / 2f - tw / 2, ty - 22 * dp, width / 2f + tw / 2, ty + 8 * dp, r, r, fillPaint)
        canvas.drawText(msg, width / 2f, ty - 2 * dp, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (choices.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val pad = 18 * dp
            val i = choices.indexOfFirst { it.box.contains(event.x, event.y, pad) }
            if (i >= 0) onChoiceTap?.invoke(i)
        }
        return true
    }

    private fun withAlpha(color: Int, a: Int) =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
