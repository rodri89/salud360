package com.salud360.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** RF-66 a RF-68: toque de los botones SI / NO con vuelta a "sin cargar" y columnas de la grilla. */
class SiNoVacioTest {
    @Test
    fun tocarElegidoLoDejaSinCargarYElOtroLoCambia() {
        assertEquals("SI", alternarSiNo("", "SI"))
        assertEquals("NO", alternarSiNo("", "NO"))
        assertEquals("", alternarSiNo("SI", "SI"))
        assertEquals("NO", alternarSiNo("SI", "NO"))
        assertEquals("SI", alternarSiNo("NO", "SI"))
        assertEquals("", alternarSiNo("NO", "NO"))
    }

    @Test
    fun nuncaDaNoSinTocarNo() {
        listOf("", "SI", "NO", "Positivo").forEach { actual ->
            assertEquals(false, alternarSiNo(actual, "SI") == "NO", "actual=$actual")
        }
    }

    @Test
    fun valorDesconocidoLoReemplazaLoTocado() {
        assertEquals("SI", alternarSiNo("Positivo", "SI"))
        assertEquals("NO", alternarSiNo("Positivo", "NO"))
    }

    @Test
    fun dosColumnasHasta600YCuatroDesde600() {
        assertEquals(2, columnasPara(320f))
        assertEquals(2, columnasPara(599.9f))
        assertEquals(4, columnasPara(600f))
        assertEquals(4, columnasPara(1200f))
    }
}
