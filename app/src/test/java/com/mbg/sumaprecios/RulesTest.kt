package com.mbg.sumaprecios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    private val b = Box(0f, 0f, 40f, 20f)
    private fun read(text: String, mode: NumberMode = NumberMode.AUTO) =
        NumberParser.extract(listOf(listOf(Word(text, b))), mode).map { it.cents }

    @Test fun separadoresDecimales() {
        for (t in listOf("3,50", "3.50", "3'50", "3€50", "3;50", "3*50", "3,50€", "€3,50", "3,5")) {
            assertEquals(t, listOf(350L), read(t))
        }
    }

    @Test fun enterosYMiles() {
        assertEquals(listOf(2000L), read("20"))
        assertEquals(listOf(35000L), read("350"))
        assertEquals(listOf(125050L), read("1.250,50"))
        assertEquals(listOf(125L), read("1.250"))
        assertEquals(listOf(50L), read("0,50"))
    }

    @Test fun noSonPrecios() {
        for (t in listOf("12/03", "12-03-2026", "12:30", "20%", "500g", "1l", "x3", "3x", "612345678", "Oferta")) {
            assertTrue(t, read(t).isEmpty())
        }
        val conUnidad = NumberParser.extract(listOf(listOf(Word("500", b), Word("g", Box(45f, 0f, 55f, 20f)))), NumberMode.AUTO)
        assertTrue(conUnidad.isEmpty())
    }

    @Test fun separadoresDeNumerosDistintos() {
        assertEquals(listOf(300L, 5000L), read("3-50"))
        assertEquals(listOf(300L, 5000L), read("3^50"))
        assertEquals(listOf(350L), read("Pollo3,50"))
        assertEquals(listOf(1000L), read("1O"))
    }

    @Test fun cifrasPegadasSeJuntan() {
        val pegados = NumberParser.extract(
            listOf(listOf(Word("3", Box(0f, 0f, 10f, 20f)), Word("50", Box(12f, 0f, 30f, 20f)))),
            NumberMode.AUTO
        ).map { it.cents }
        assertEquals(listOf(35000L), pegados)

        val separados = NumberParser.extract(
            listOf(listOf(Word("3", Box(0f, 0f, 10f, 20f)), Word("50", Box(60f, 0f, 80f, 20f)))),
            NumberMode.AUTO
        ).map { it.cents }
        assertEquals(listOf(300L, 5000L), separados)
    }

    @Test fun modos() {
        assertEquals(listOf(2500L), read("25,00", NumberMode.ENTEROS))
        assertEquals(listOf(125000L), read("1.250", NumberMode.ENTEROS))
        assertEquals(listOf(250L), read("250", NumberMode.CENTIMOS))
        assertEquals(listOf(250L), read("2,50", NumberMode.CENTIMOS))
    }

    private fun c(cents: Long, x: Float = 0f) = Candidate(cents, Box(x, 0f, x + 10f, 10f), "")

    @Test fun unNumeroSeApuntaUnaVez() {
        val d = Detector(cooldownMs = 1000)
        var t = 0L
        val adds = mutableListOf<Long>()
        // 5 segundos mirando el mismo 2,50
        repeat(50) {
            val r = d.process(listOf(c(250)), t)
            if (r is Detector.Result.Add) adds += r.candidate.cents
            t += 100
        }
        assertEquals(listOf(250L), adds)

        // Aparta la cámara 1 s y vuelve: cuenta otra vez
        repeat(10) { d.process(emptyList(), t); t += 100 }
        repeat(10) {
            val r = d.process(listOf(c(250)), t)
            if (r is Detector.Result.Add) adds += r.candidate.cents
            t += 100
        }
        assertEquals(listOf(250L, 250L), adds)
    }

    @Test fun pausaIgnoraOtroPrecio() {
        val d = Detector(cooldownMs = 1000)
        var t = 0L
        repeat(3) { d.process(listOf(c(250)), t); t += 100 } // apunta 2,50
        // Durante la pausa aparece 1,20 en vez de 2,50: no se apunta
        var added = false
        repeat(5) { if (d.process(listOf(c(120)), t) is Detector.Result.Add) added = true; t += 100 }
        assertEquals(false, added)
        // Pasada la pausa, sí
        repeat(10) { if (d.process(listOf(c(120)), t) is Detector.Result.Add) added = true; t += 100 }
        assertEquals(true, added)
    }

    @Test fun variosNumerosPidenElegir() {
        val d = Detector()
        var r: Detector.Result = Detector.Result.None
        var t = 0L
        repeat(3) { r = d.process(listOf(c(250, 0f), c(120, 50f)), t); t += 100 }
        assertTrue(r is Detector.Result.Choose)
        // Al volver de elegir, siguen en pantalla: no vuelve a pedir elegir
        d.resume(t)
        var again = false
        repeat(30) { if (d.process(listOf(c(250, 0f), c(120, 50f)), t) !is Detector.Result.None) again = true; t += 100 }
        assertEquals(false, again)
    }
}
