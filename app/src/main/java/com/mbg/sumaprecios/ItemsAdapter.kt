package com.mbg.sumaprecios

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** El cuadro de números de abajo. Tocar = editar. Mantener pulsado = menú. */
class ItemsAdapter(
    private val items: List<SaleItem>,
    private val onTap: (Int) -> Unit,
    private val onLongPress: (Int) -> Unit,
    private val longPressMs: () -> Long,
) : RecyclerView.Adapter<ItemsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.itemText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_number, parent, false)
        return VH(v).also { attachTouch(it) }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = format(items[position])
    }

    private fun format(item: SaleItem): CharSequence {
        if (item.discount <= 0) return money(item.cents)
        val sb = SpannableStringBuilder()
        sb.append(money(item.cents), StrikethroughSpan(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ")
        sb.append(money(item.finalCents))
        sb.append("\n−${item.discount}%", RelativeSizeSpan(0.65f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(h: VH) {
        val handler = Handler(Looper.getMainLooper())
        val slop = ViewConfiguration.get(h.itemView.context).scaledTouchSlop
        var fired = false
        var moved = false
        var downX = 0f
        var downY = 0f
        val longRunnable = Runnable {
            fired = true
            h.itemView.isPressed = false
            h.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongPress(p)
        }
        h.itemView.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false; moved = false; downX = e.x; downY = e.y
                    v.isPressed = true
                    handler.postDelayed(longRunnable, longPressMs())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                        moved = true
                        handler.removeCallbacks(longRunnable)
                        v.isPressed = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRunnable)
                    v.isPressed = false
                    if (!fired && !moved) {
                        val p = h.bindingAdapterPosition
                        if (p != RecyclerView.NO_POSITION) onTap(p)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRunnable)
                    v.isPressed = false
                }
            }
            true
        }
    }
}
