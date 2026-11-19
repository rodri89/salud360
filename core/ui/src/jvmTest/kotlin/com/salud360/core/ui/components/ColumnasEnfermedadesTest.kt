package com.salud360.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 003 RF-17: la grilla de "Enfermedades" usa [columnasPara] (PD4), 2 casillas por fila con menos de 600 puntos de
 * ancho disponible y 4 desde 600. Repite el caso de [SiNoVacioTest] a propósito (E9): si las serologías cambian de
 * borde, este test avisa que enfermedades también cambia.
 */
class ColumnasEnfermedadesTest {
    @Test
    fun dosColumnasMenosDe600YCuatroDesde600() {
        assertEquals(2, columnasPara(320f))
        assertEquals(2, columnasPara(599.9f))
        assertEquals(4, columnasPara(600f))
        assertEquals(4, columnasPara(1200f))
    }
}
