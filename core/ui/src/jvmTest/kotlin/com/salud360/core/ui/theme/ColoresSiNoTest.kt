package com.salud360.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * RF-70 y RF-71: contrastes WCAG 2.1 de las tarjetas SI / NO de serologías, en modo claro y oscuro. El foco va por
 * fuera del botón ("Decidido" C5-4): se mide contra la tarjeta, no contra el relleno.
 */
class ColoresSiNoTest {
    @Test
    fun rellenoYBordeContraLaTarjeta() = enLosDosModos { oscuro, c ->
        assertMinimo(3f, c.relleno, c.fondoTarjeta, "relleno/fondoTarjeta", oscuro)
        assertMinimo(3f, c.borde, c.fondoTarjeta, "borde/fondoTarjeta", oscuro)
    }

    @Test
    fun focoContraLaTarjetaYDistintoDelRelleno() = enLosDosModos { oscuro, c ->
        assertMinimo(3f, c.bordeFoco, c.fondoTarjeta, "bordeFoco/fondoTarjeta", oscuro)
        assertNotEquals(c.relleno, c.bordeFoco, "bordeFoco = relleno (oscuro=$oscuro)")
    }

    @Test
    fun textosContraSuFondo() = enLosDosModos { oscuro, c ->
        assertMinimo(4.5f, c.textoRelleno, c.relleno, "textoRelleno/relleno", oscuro)
        assertMinimo(4.5f, c.texto, c.fondoTarjeta, "texto/fondoTarjeta", oscuro)
    }

    /** "Total: N · Clase X" de Child-Pugh ("Decidido" 33). */
    @Test
    fun totalContraLaTarjeta() = enLosDosModos { oscuro, c ->
        assertMinimo(4.5f, c.textoTotal, c.fondoTarjeta, "textoTotal/fondoTarjeta", oscuro)
    }

    @Test
    fun rellenoDistintoDeLaTarjeta() = enLosDosModos { oscuro, c ->
        assertNotEquals(c.fondoTarjeta, c.relleno, "relleno = fondoTarjeta (oscuro=$oscuro)")
    }

    private fun enLosDosModos(bloque: (Boolean, ColoresSiNo) -> Unit) {
        listOf(false, true).forEach { bloque(it, coloresSiNo(it)) }
    }

    private fun assertMinimo(minimo: Float, a: Color, b: Color, que: String, oscuro: Boolean) {
        val c = contraste(a, b)
        println("contraste $que (oscuro=$oscuro) = ${"%.2f".format(c)}")
        assertTrue(c >= minimo, "$que (oscuro=$oscuro): $c < $minimo")
    }
}
