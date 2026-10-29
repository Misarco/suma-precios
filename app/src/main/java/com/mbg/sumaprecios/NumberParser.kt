package com.mbg.sumaprecios

import kotlin.math.max
import kotlin.math.min

/** Rectángulo en coordenadas de pantalla (sin clases de Android, para poder probarlo). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun contains(x: Float, y: Float, pad: Float = 0f) =
        x >= left - pad && x <= right + pad && y >= top - pad && y <= bottom + pad
}

/** Una palabra que ha leído el lector de texto, con su posición. */
data class Word(val text: String, val box: Box)

/** Un número que podría sumarse. El valor va en céntimos para no tener errores de redondeo. */
data class Candidate(val cents: Long, val box: Box, val raw: String)

enum class NumberMode { AUTO, ENTEROS, CENTIMOS }

/**
 * Reglas acordadas:
 * - Cada grupo de cifras separado de otro es un número distinto. Dos trozos se juntan
 *   solo si el hueco entre ellos es menor que el ancho de una cifra.
 * - , ; * . ' ’ y € en medio cuentan como coma decimal. Si hay varios, solo el último es decimal.
 * - - ^ / separan números distintos.
 * - Sin coma es un entero.
 * - Se ignoran fechas, horas, porcentajes, medidas (500 g, 1 l...) y cantidades (x3, 3x).
 * - Se ignoran las tiradas de 7 o más cifras (teléfonos, códigos).
 */
object NumberParser {

    private const val DECIMAL_SEPS = ",;*.'’`´€"

    private val UNITS = setOf(
        "g", "gr", "grs", "kg", "kgs", "mg", "ml", "cl", "dl", "l", "lt", "lts",
        "cm", "mm", "km", "ud", "uds", "un", "unid", "x", "×", "%"
    )
    private val UNIT_SUFFIX = Regex(
        """^[0-9.,]+\s*(g|gr|grs|kg|kgs|mg|ml|cl|dl|l|lt|lts|cm|mm|km|ud|uds|un|x|×)$""",
        RegexOption.IGNORE_CASE
    )
    private val QTY_PREFIX = Regex("""^[x×]\d+$""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""^\d{1,2}:\d{2}$""")
    private val DATE_SLASH = Regex("""^(\d{1,2})/(\d{1,2})(/(\d{2}|\d{4}))?$""")
    private val DATE_DASH = Regex("""^(\d{1,2})-(\d{1,2})-(\d{2}|\d{4})$""")
    /** Letras que el lector confunde con cifras (opción "Comparar con letras" en Ajustes). */
    val LETTER_MAP: Map<Char, Char> = mapOf(
        'y' to '4', 'Y' to '4', 'u' to '4', 'h' to '4', 'A' to '4',
        'b' to '6', 'G' to '6',
        'T' to '7', 'F' to '7', 'f' to '7', '+' to '7',
        'O' to '0', 'o' to '0', 'D' to '0',
        'I' to '1', 'l' to '1', '|' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'B' to '8',
        'g' to '9', 'q' to '9',
    )
    private val SPLIT = Regex("""[^0-9,;*.'’`´€]+""")
    private val LONG_RUN = Regex("""\d{7,}""")

    /** Recibe las líneas leídas (cada una, lista de palabras) y devuelve los números válidos. */
    fun extract(lines: List<List<Word>>, mode: NumberMode, letters: Boolean = true): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (line in lines) {
            val words = mergeClose(line, letters)
            for ((i, w) in words.withIndex()) {
                val next = words.getOrNull(i + 1)?.text?.trim()?.lowercase()
                // "500 g", "3 x", "20 %": el número va seguido de una unidad -> no es precio
                if (next != null && next in UNITS) continue
                out += parseWord(w, mode, letters)
            }
        }
        return out
    }

    /** Junta trozos de cifras muy pegados (p. ej. "3" y "50" escritos casi juntos). */
    fun mergeClose(words: List<Word>, letters: Boolean = true): List<Word> {
        val res = mutableListOf<Word>()
        for (w in words.sortedBy { it.box.left }) {
            val last = res.lastOrNull()
            if (last != null && isNumberLike(last.text, letters) && isNumberLike(w.text, letters)) {
                val gap = w.box.left - last.box.right
                val digitWidth = min(last.box.height, w.box.height) * 0.55f
                if (gap <= digitWidth) {
                    res[res.size - 1] = Word(last.text + w.text, last.box.union(w.box))
                    continue
                }
            }
            res += w
        }
        return res
    }

    /** Es "casi un número": solo cifras, separadores y (si está activado) letras parecidas, con al menos una cifra. */
    private fun isNumberLike(t: String, letters: Boolean): Boolean {
        if (t.none { it.isDigit() }) return false
        return t.all { it.isDigit() || it in DECIMAL_SEPS || (letters && it in LETTER_MAP) }
    }

    /** Convierte las letras parecidas en cifras. Un "+" al principio se deja (puede ser un signo). */
    private fun lettersToDigits(t: String): String =
        t.mapIndexed { i, c ->
            when {
                c == '+' && i == 0 -> c
                else -> LETTER_MAP[c] ?: c
            }
        }.joinToString("")

    fun parseWord(w: Word, mode: NumberMode, letters: Boolean = true): List<Candidate> {
        var t = w.text.trim()
        if (t.isEmpty() || '%' in t) return emptyList()
        if (t.none { it.isDigit() }) return emptyList()
        if (UNIT_SUFFIX.matches(t) || QTY_PREFIX.matches(t)) return emptyList()
        if (TIME.matches(t) || isDate(t)) return emptyList()

        // Solo si la palabra es "casi un número" (p. ej. "3,b0" o "1T"), nunca en palabras normales
        if (letters && isNumberLike(t, true)) t = lettersToDigits(t)

        val parts = t.split(SPLIT).filter { p -> p.any { it.isDigit() } }
        val res = mutableListOf<Candidate>()
        var from = 0
        for (p in parts) {
            val idx = t.indexOf(p, from).coerceAtLeast(0)
            from = idx + p.length
            if (LONG_RUN.containsMatchIn(p)) continue
            val cents = toCents(p, mode) ?: continue
            if (cents <= 0) continue
            val box = if (parts.size == 1) w.box else subBox(w.box, idx, p.length, t.length)
            res += Candidate(cents, box, p)
        }
        return res
    }

    /** Convierte el texto de un número a céntimos según el tipo de números elegido en Ajustes. */
    fun toCents(raw: String, mode: NumberMode): Long? {
        var p = raw.trim().trim('€')
        p = p.trimEnd { it in DECIMAL_SEPS }
        if (p.isEmpty()) return null
        if (p[0] in DECIMAL_SEPS) p = "0$p"

        val digits = p.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 9) return null
        val lastSep = p.indexOfLast { it in DECIMAL_SEPS }

        return when (mode) {
            NumberMode.CENTIMOS -> digits.toLong()

            NumberMode.ENTEROS -> {
                if (lastSep < 0) {
                    digits.toLong() * 100
                } else {
                    val before = p.substring(0, lastSep).filter { it.isDigit() }
                    val after = p.substring(lastSep + 1).filter { it.isDigit() }
                    // "25,00" -> 25 ; "1.250" -> 1250
                    val euros = if (after.length <= 2) before else before + after
                    (if (euros.isEmpty()) 0L else euros.toLong()) * 100
                }
            }

            NumberMode.AUTO -> {
                if (lastSep < 0) {
                    digits.toLong() * 100
                } else {
                    val before = p.substring(0, lastSep).filter { it.isDigit() }
                    val after = p.substring(lastSep + 1).filter { it.isDigit() }
                    val euros = if (before.isEmpty()) 0L else before.toLong()
                    val c = when (after.length) {
                        0 -> 0L
                        1 -> after.toLong() * 10
                        else -> after.substring(0, 2).toLong()
                    }
                    euros * 100 + c
                }
            }
        }
    }

    private fun isDate(t: String): Boolean {
        val m = DATE_SLASH.matchEntire(t) ?: DATE_DASH.matchEntire(t) ?: return false
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        return d in 1..31 && mo in 1..12
    }

    private fun subBox(b: Box, start: Int, len: Int, total: Int): Box {
        if (total <= 0) return b
        val cw = b.width / total
        return Box(b.left + cw * start, b.top, b.left + cw * (start + len), b.bottom)
    }
}
