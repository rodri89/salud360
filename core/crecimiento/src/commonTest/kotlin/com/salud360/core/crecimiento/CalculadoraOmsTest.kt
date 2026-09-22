package com.salud360.core.crecimiento

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalculadoraOmsTest {
    private fun cerca(esperado: Double, real: Double?, tolerancia: Double) {
        assertNotNull(real)
        assertTrue(abs(esperado - real) <= tolerancia, "esperado $esperado ± $tolerancia, fue $real")
    }

    private fun meses(m: Int) = (m * CalculadoraOms.DIAS_POR_MES).toInt()

    @Test
    fun medianasDeLaOmsDanZCero() {
        // Medianas publicadas por la OMS (patrones 2006): varón 24 m 12.15 kg; nena 12 m 74.0 cm; varón RN PC 34.5 cm.
        cerca(0.0, CalculadoraOms.zScore(Medida.PESO, SexoOms.M, meses(24), 12.15)?.z, 0.05)
        cerca(0.0, CalculadoraOms.zScore(Medida.TALLA, SexoOms.F, meses(12), 74.0)?.z, 0.05)
        cerca(0.0, CalculadoraOms.zScore(Medida.PERIMETRO_CEFALICO, SexoOms.M, meses(0), 34.5)?.z, 0.05)
        // Referencia 2007: varón 120 m, IMC mediana 16.4.
        cerca(50.0, CalculadoraOms.zScore(Medida.IMC, SexoOms.M, meses(120), 16.4)?.percentil, 2.0)
    }

    @Test
    fun percentilosDeZConocidos() {
        cerca(97.0, CalculadoraOms.percentil(1.881), 0.1)
        cerca(3.0, CalculadoraOms.percentil(-1.881), 0.1)
        cerca(50.0, CalculadoraOms.percentil(0.0), 1e-6)
        // Un peso muy bajo queda claramente por debajo de P3.
        val bajo = CalculadoraOms.zScore(Medida.PESO, SexoOms.F, meses(6), 5.0)!!
        assertTrue(bajo.percentil < 3)
    }

    @Test
    fun interpolaEntreMesesYRespetaLosLimites() {
        val m24 = CalculadoraOms.lms(Medida.PESO, SexoOms.M, 24.0)!!.m
        val m25 = CalculadoraOms.lms(Medida.PESO, SexoOms.M, 25.0)!!.m
        val medio = CalculadoraOms.lms(Medida.PESO, SexoOms.M, 24.5)!!.m
        assertTrue(medio > minOf(m24, m25) && medio < maxOf(m24, m25))
        assertNull(CalculadoraOms.lms(Medida.PESO, SexoOms.M, 300.0))
        assertNull(CalculadoraOms.lms(Medida.PERIMETRO_CEFALICO, SexoOms.F, 70.0))
        assertNotNull(CalculadoraOms.lms(Medida.TALLA, SexoOms.F, 228.0))
        assertEquals(60, CalculadoraOms.edadMaximaMeses(Medida.PERIMETRO_CEFALICO))
        assertEquals(120, CalculadoraOms.edadMaximaMeses(Medida.PESO))
    }

    @Test
    fun valorEnZEsLaInversaDelZScore() {
        val valor = CalculadoraOms.valorEnZ(Medida.TALLA, SexoOms.M, 36.0, 1.5)!!
        cerca(1.5, CalculadoraOms.zScoreMeses(Medida.TALLA, SexoOms.M, 36.0, valor)?.z, 1e-6)
        val imc = CalculadoraOms.valorEnZ(Medida.IMC, SexoOms.F, 100.0, -1.0)!!
        cerca(-1.0, CalculadoraOms.zScoreMeses(Medida.IMC, SexoOms.F, 100.0, imc)?.z, 1e-6)
    }

    @Test
    fun etiquetas() {
        assertEquals("<P1", CalculadoraOms.etiquetaPercentil(0.4))
        assertEquals(">P99", CalculadoraOms.etiquetaPercentil(99.6))
        assertEquals("P45", CalculadoraOms.etiquetaPercentil(45.3))
        assertEquals("P50", ResultadoPercentil(0.0, 50.0).etiqueta)
    }

    @Test
    fun valoresInvalidosDanNull() {
        assertNull(CalculadoraOms.zScore(Medida.PESO, SexoOms.M, meses(10), 0.0))
        assertNull(CalculadoraOms.zScore(Medida.PESO, SexoOms.M, meses(10), Double.NaN))
        assertNull(CalculadoraOms.imc(0.0, 80.0))
        cerca(15.0, CalculadoraOms.imc(9.6, 80.0), 0.01)
    }
}
