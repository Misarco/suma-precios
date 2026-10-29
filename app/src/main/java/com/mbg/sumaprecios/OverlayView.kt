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

    /** Zona de lectura guardada, en fracciones de la pantalla (0..1). */
    private var zoneFractions: FloatArray = Store.DEFAULT_ZONE.copyOf()

    /** Se llama al soltar el dedo tras mover o redimensionar la zona. */
    var onZoneChanged: ((FloatArray) -> Unit)? = null

    private var dragMode = DRAG_NONE
    private var lastX = 0f
    private var lastY = 0f
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun setZoneFractions(f: FloatArray) {
        zoneFractions = f.copyOf()
        applyFractions()
        invalidate()
    }

    private fun applyFractions() {
        if (width == 0 || height == 0) return
        val f = zoneFractions
        zone.set(f[0] * width, f[1] * height, f[2] * width, f[3] * height)
        clampZone()
    }

    private fun currentFractions() = floatArrayOf(
        zone.left / width, zone.top / height, zone.right / width, zone.bottom / height
    )

    private fun clampZone() {
        val minW = 90 * dp
        val minH = 60 * dp
        val topLimit = 56 * dp // deja sitio al texto de ayuda
        if (zone.width() < minW) zone.right = zone.left + minW
        if (zone.height() < minH) zone.bottom = zone.top + minH
        if (zone.left < 0f) zone.offset(-zone.left, 0f)
        if (zone.right > width) zone.offset(width - zone.right, 0f)
        if (zone.top < topLimit) zone.offset(0f, topLimit - zone.top)
        if (zone.bottom > height - 24 * dp) zone.offset(0f, height - 24 * dp - zone.bottom)
        zone.left = zone.left.coerceAtLeast(0f)
        zone.right = zone.right.coerceAtMost(width.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        applyFractions()
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
            if (choices.isEmpty()) {
                val hr = (if (dragMode != DRAG_NONE) 9 else 6) * dp
                canvas.drawCircle(left, top, hr, handlePaint); canvas.drawCircle(right, top, hr, handlePaint)
                canvas.drawCircle(left, bottom, hr, handlePaint); canvas.drawCircle(right, bottom, hr, handlePaint)
            }
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
        if (choices.isNotEmpty()) {
            // Modo elegir: los toques eligen números
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val pad = 18 * dp
                val i = choices.indexOfFirst { it.box.contains(event.x, event.y, pad) }
                if (i >= 0) onChoiceTap?.invoke(i)
            }
            return true
        }

        // Modo normal: arrastrar esquinas para cambiar el tamaño, o el interior para mover la zona
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val grab = 34 * dp
                dragMode = when {
                    near(x, y, zone.left, zone.top, grab) -> DRAG_TL
                    near(x, y, zone.right, zone.top, grab) -> DRAG_TR
                    near(x, y, zone.left, zone.bottom, grab) -> DRAG_BL
                    near(x, y, zone.right, zone.bottom, grab) -> DRAG_BR
                    zone.contains(x, y) -> DRAG_MOVE
                    else -> DRAG_NONE
                }
                lastX = x; lastY = y
                return dragMode != DRAG_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DRAG_NONE) return false
                val dx = x - lastX
                val dy = y - lastY
                lastX = x; lastY = y
                val minW = 90 * dp
                val minH = 60 * dp
                when (dragMode) {
                    DRAG_MOVE -> zone.offset(dx, dy)
                    DRAG_TL -> { zone.left = (zone.left + dx).coerceAtMost(zone.right - minW); zone.top = (zone.top + dy).coerceAtMost(zone.bottom - minH) }
                    DRAG_TR -> { zone.right = (zone.right + dx).coerceAtLeast(zone.left + minW); zone.top = (zone.top + dy).coerceAtMost(zone.bottom - minH) }
                    DRAG_BL -> { zone.left = (zone.left + dx).coerceAtMost(zone.right - minW); zone.bottom = (zone.bottom + dy).coerceAtLeast(zone.top + minH) }
                    DRAG_BR -> { zone.right = (zone.right + dx).coerceAtLeast(zone.left + minW); zone.bottom = (zone.bottom + dy).coerceAtLeast(zone.top + minH) }
                }
                clampZone()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode == DRAG_NONE) return false
                dragMode = DRAG_NONE
                zoneFractions = currentFractions()
                onZoneChanged?.invoke(zoneFractions.copyOf())
                invalidate()
                return true
            }
        }
        return dragMode != DRAG_NONE
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float, r: Float) =
        (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_MOVE = 1
        private const val DRAG_TL = 2
        private const val DRAG_TR = 3
        private const val DRAG_BL = 4
        private const val DRAG_BR = 5
    }

    private fun withAlpha(color: Int, a: Int) =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
