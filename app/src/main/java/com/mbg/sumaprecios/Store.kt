package com.mbg.sumaprecios

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

data class SaleItem(val id: Long, var cents: Long, var discount: Int = 0) {
    val finalCents: Long
        get() = if (discount <= 0) cents else (cents * (100 - discount) / 100.0).roundToLong()
}

private val ES = Locale("es", "ES")

/** 1250,5 € -> "1.250,50" */
fun money(cents: Long): String = String.format(ES, "%,.2f", cents / 100.0)

/** Guarda en el propio móvil: la venta en curso, el historial y los ajustes. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("suma_precios", Context.MODE_PRIVATE)

    var pauseMs: Long
        get() = p.getLong(KEY_PAUSE, DEFAULT_PAUSE_MS)
        set(v) = p.edit().putLong(KEY_PAUSE, v).apply()

    var longPressMs: Long
        get() = p.getLong(KEY_LONG, DEFAULT_LONG_PRESS_MS)
        set(v) = p.edit().putLong(KEY_LONG, v).apply()

    var mode: NumberMode
        get() = runCatching { NumberMode.valueOf(p.getString(KEY_MODE, null) ?: "AUTO") }
            .getOrDefault(NumberMode.AUTO)
        set(v) = p.edit().putString(KEY_MODE, v.name).apply()

    /** Restablecer ajustes: no toca la venta en curso ni el historial. */
    fun resetSettings() {
        p.edit().remove(KEY_PAUSE).remove(KEY_LONG).remove(KEY_MODE).apply()
    }

    fun loadItems(): List<SaleItem> = runCatching {
        val a = JSONArray(p.getString(KEY_ITEMS, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SaleItem(o.getLong("id"), o.getLong("c"), o.optInt("d", 0))
        }
    }.getOrDefault(emptyList())

    fun saveItems(items: List<SaleItem>) {
        val a = JSONArray()
        for (it in items) a.put(JSONObject().put("id", it.id).put("c", it.cents).put("d", it.discount))
        p.edit().putString(KEY_ITEMS, a.toString()).apply()
    }

    /** Totales de ventas, la más reciente primero. */
    fun loadHistory(): List<Long> = runCatching {
        val a = JSONArray(p.getString(KEY_HISTORY, "[]"))
        (0 until a.length()).map { a.getLong(it) }
    }.getOrDefault(emptyList())

    fun addHistory(total: Long) {
        val list = (listOf(total) + loadHistory()).take(HISTORY_SIZE)
        p.edit().putString(KEY_HISTORY, JSONArray(list).toString()).apply()
    }

    companion object {
        const val DEFAULT_PAUSE_MS = 1000L
        const val DEFAULT_LONG_PRESS_MS = 1000L
        const val HISTORY_SIZE = 15
        private const val KEY_PAUSE = "pause_ms"
        private const val KEY_LONG = "long_press_ms"
        private const val KEY_MODE = "mode"
        private const val KEY_ITEMS = "items"
        private const val KEY_HISTORY = "history"
    }
}
