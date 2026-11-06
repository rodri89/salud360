package com.salud360.core.data.repos

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Qué consultas previas de un texto se muestran junto al área de escritura. Todo puro. */
class ConsultasPreviasTest {

    private fun h(fecha: String, consulta: String, valor: String) = ValorHistorico(LocalDate.parse(fecha), consulta, valor)

    @Test
    fun ordenaDeLaMasRecienteALaMasAntigua() {
        val historial = listOf(h("2026-01-10", "a", "vieja"), h("2026-03-05", "b", "nueva"), h("2026-02-01", "c", "media"))
        assertEquals(listOf("nueva", "media", "vieja"), consultasPrevias(historial, consultaActualId = "x").map { it.valor })
    }

    @Test
    fun laUltimaEsLaDeFechaMasReciente() {
        val historial = listOf(h("2026-01-10", "a", "vieja"), h("2026-03-05", "b", "nueva"))
        assertEquals("nueva", ultimaConsultaPrevia(historial, consultaActualId = "x")?.valor)
    }

    @Test
    fun conLaMismaFechaConservaElOrdenDeEntrada() {
        // La base ya entrega el desempate por última modificación: no se lo pisa.
        val historial = listOf(h("2026-03-05", "b", "segunda del día"), h("2026-03-05", "a", "primera del día"))
        assertEquals(listOf("segunda del día", "primera del día"), consultasPrevias(historial, "x").map { it.valor })
    }

    @Test
    fun excluyeLaConsultaQueSeEstaEscribiendo() {
        val historial = listOf(h("2026-03-05", "actual", "lo que escribo ahora"), h("2026-01-10", "a", "previa"))
        assertEquals(listOf("previa"), consultasPrevias(historial, consultaActualId = "actual").map { it.valor })
    }

    @Test
    fun noMuestraPreviasSinTexto() {
        val historial = listOf(h("2026-03-05", "b", ""), h("2026-02-05", "c", "   \n "), h("2026-01-10", "a", "con texto"))
        assertEquals(listOf("con texto"), consultasPrevias(historial, "x").map { it.valor })
    }

    @Test
    fun sinPreviasNoHayUltima() {
        assertEquals(emptyList(), consultasPrevias(emptyList(), "x"))
        assertNull(ultimaConsultaPrevia(emptyList(), "x"))
        assertNull(ultimaConsultaPrevia(listOf(h("2026-03-05", "actual", "solo la actual")), "actual"))
    }
}
