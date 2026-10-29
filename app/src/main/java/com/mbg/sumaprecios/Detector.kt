package com.mbg.sumaprecios

/**
 * Decide cuándo apuntar un número:
 * 1. Confirma: el mismo resultado en varias lecturas seguidas.
 * 2. Pausa: después de apuntar, no detecta nada durante [cooldownMs].
 * 3. Bloqueo: un número apuntado no vuelve a contar mientras siga en la imagen.
 *    Se desbloquea cuando lleva [absentMs] sin verse (apartas la cámara).
 * Si hay un solo número lo apunta solo; si hay varios, pide elegir.
 */
class Detector(
    var cooldownMs: Long = 1000L,
    private val stableFrames: Int = 3,
    private val absentMs: Long = 600L,
) {
    sealed class Result {
        object None : Result()
        data class Add(val candidate: Candidate) : Result()
        data class Choose(val candidates: List<Candidate>) : Result()
    }

    private val blocked = HashMap<Long, Long>() // valor en céntimos -> última vez que se vio
    private var cooldownUntil = 0L
    private val recent = ArrayDeque<List<Long>>()

    fun blockedValues(): Set<Long> = blocked.keys.toSet()

    fun isCooling(now: Long) = now < cooldownUntil

    fun process(cands: List<Candidate>, now: Long): Result {
        for (c in cands) if (blocked.containsKey(c.cents)) blocked[c.cents] = now
        blocked.entries.removeAll { now - it.value > absentMs }

        if (now < cooldownUntil) {
            recent.clear()
            return Result.None
        }

        val free = cands.filter { it.cents !in blocked }
        recent.addLast(free.map { it.cents }.sorted())
        while (recent.size > stableFrames) recent.removeFirst()
        if (recent.size < stableFrames || free.isEmpty()) return Result.None

        if (free.size == 1) {
            val v = free[0].cents
            if (recent.all { it.size == 1 && it[0] == v }) {
                block(cands, now)
                cooldownUntil = now + cooldownMs
                recent.clear()
                return Result.Add(free[0])
            }
        } else if (recent.all { it.size >= 2 }) {
            block(cands, now)
            recent.clear()
            return Result.Choose(free.sortedWith(compareBy({ it.box.top }, { it.box.left })))
        }
        return Result.None
    }

    /** Al volver de elegir: lo que estaba en pantalla sigue bloqueado y empieza la pausa. */
    fun resume(now: Long) {
        for (k in blocked.keys.toList()) blocked[k] = now
        cooldownUntil = now + cooldownMs
        recent.clear()
    }

    fun reset() {
        blocked.clear()
        recent.clear()
        cooldownUntil = 0L
    }

    private fun block(cands: List<Candidate>, now: Long) {
        for (c in cands) blocked[c.cents] = now
    }
}
