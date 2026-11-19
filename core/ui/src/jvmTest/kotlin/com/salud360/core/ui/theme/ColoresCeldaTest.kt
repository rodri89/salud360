package com.salud360.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** RF-50 a RF-52: contrastes WCAG 2.1 de la celda editable del laboratorio, en modo claro y oscuro. */
class ColoresCeldaTest {
    // Fondos donde se apoya la celda: la tarjeta (`surface`) y la fila gris (`surfaceVariant` al 50 % sobre `surface`).
    private val superficies = mapOf(
        false to Salud360Colors.Surface,
        true to Salud360Colors.DarkSurface,
    )
    private val filasGrises = mapOf(
        false to Salud360Colors.SurfaceVariant.copy(alpha = 0.5f).compositeOver(Salud360Colors.Surface),
        true to Salud360Colors.DarkSurfaceVariant.copy(alpha = 0.5f).compositeOver(Salud360Colors.DarkSurface),
    )

    @Test
    fun negroContraBlancoEs21() {
        assertEquals(21f, contraste(Color.Black, Color.White), 0.1f)
        assertEquals(21f, contraste(Color.White, Color.Black), 0.1f)
        assertEquals(1f, contraste(Color.White, Color.White), 0.01f)
    }

    @Test
    fun bordesContraElFondoDeLaCelda() = enLosDosModos { oscuro, c ->
        assertMinimo(3f, c.borde, c.fondo, "borde/fondo", oscuro)
        assertMinimo(3f, c.bordeFoco, c.fondo, "bordeFoco/fondo", oscuro)
    }

    @Test
    fun bordeContraLaFila() = enLosDosModos { oscuro, c ->
        assertMinimo(3f, c.borde, superficies.getValue(oscuro), "borde/surface", oscuro)
        assertMinimo(3f, c.borde, filasGrises.getValue(oscuro), "borde/fila gris", oscuro)
    }

    @Test
    fun textoYAyudaContraElFondo() = enLosDosModos { oscuro, c ->
        assertMinimo(4.5f, c.texto, c.fondo, "texto/fondo", oscuro)
        assertMinimo(4.5f, c.ayuda, c.fondo, "ayuda/fondo", oscuro)
    }

    @Test
    fun fondoPropioDistintoDeLaTarjeta() = enLosDosModos { oscuro, c ->
        assertNotEquals(superficies.getValue(oscuro), c.fondo, "fondo = surface (oscuro=$oscuro)")
    }

    @Test
    fun focoDeOtroColor() = enLosDosModos { _, c ->
        assertNotEquals(c.borde, c.bordeFoco)
    }

    private fun enLosDosModos(bloque: (Boolean, ColoresCelda) -> Unit) {
        listOf(false, true).forEach { bloque(it, coloresCelda(it)) }
    }

    private fun assertMinimo(minimo: Float, a: Color, b: Color, que: String, oscuro: Boolean) {
        val c = contraste(a, b)
        println("contraste $que (oscuro=$oscuro) = ${"%.2f".format(c)}")
        assertTrue(c >= minimo, "$que (oscuro=$oscuro): $c < $minimo")
    }
}
