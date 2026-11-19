package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Consultas previas" de una sección de texto con historial, como el motivo de consulta (RF-83, RF-84, RF-85). */
class TextoConHistorialTest {
    private fun c(id: String, fecha: LocalDate, estado: EstadoConsulta = EstadoConsulta.CERRADA, tipo: String = "consulta") =
        Consulta(id, "pac", "med", "clinica", tipo, fecha, estado)

    private val d1 = LocalDate(2026, 1, 10)
    private val d2 = LocalDate(2026, 3, 5)
    private val d3 = LocalDate(2026, 6, 20)

    @Test
    fun todas_menos_la_actual_con_la_fecha_de_su_consulta() {
        val consultas = listOf(c("cA", d3), c("cB", d2, tipo = "telemedicina"), c("cC", d1))
        val valores = mapOf("cA" to "motivo A", "cB" to "motivo B", "cC" to "motivo C")
        assertEquals(
            listOf(EntradaTexto("cA", d3, "motivo A"), EntradaTexto("cC", d1, "motivo C")),
            historialTexto(consultas, valores, "cB"),
        )
    }

    @Test
    fun tambien_las_posteriores_a_la_actual_de_la_mas_reciente_a_la_mas_antigua() {
        // Llegan desordenadas: manda la fecha de la consulta.
        val consultas = listOf(c("cC", d1), c("cA", d3), c("cB", d2))
        val valores = mapOf("cA" to "motivo A", "cB" to "motivo B", "cC" to "motivo C")
        assertEquals(listOf("cA", "cB"), historialTexto(consultas, valores, "cC").map { it.consultaId })
    }

    @Test
    fun una_anulada_no_sale_aunque_tenga_texto() {
        val consultas = listOf(c("cA", d3, EstadoConsulta.ANULADA), c("cB", d2))
        val valores = mapOf("cA" to "motivo A", "cB" to "motivo B")
        assertEquals(listOf("cB"), historialTexto(consultas, valores, "cX").map { it.consultaId })
    }

    @Test
    fun texto_vacio_o_en_blanco_no_sale() {
        val consultas = listOf(c("c1", d3), c("c2", d3), c("c3", d2), c("c4", d1))
        val valores = mapOf("c1" to "", "c2" to "   ", "c3" to "\n", "c4" to "motivo")
        assertEquals(listOf(EntradaTexto("c4", d1, "motivo")), historialTexto(consultas, valores, "cX"))
    }

    @Test
    fun una_consulta_sin_valor_no_sale_y_un_valor_sin_consulta_se_ignora() {
        val consultas = listOf(c("cA", d3), c("cB", d2))
        val valores = mapOf("cB" to "motivo B", "otra" to "de una consulta que no está")
        assertEquals(listOf(EntradaTexto("cB", d2, "motivo B")), historialTexto(consultas, valores, "cX"))
    }

    @Test
    fun a_igual_fecha_queda_el_orden_de_entrada() {
        // Como las da `consultasDePaciente`: a igual fecha, la modificada más recientemente primero.
        val consultas = listOf(c("cNueva", d2), c("cVieja", d2), c("cA", d3))
        val valores = mapOf("cNueva" to "nueva", "cVieja" to "vieja", "cA" to "A")
        assertEquals(listOf("cA", "cNueva", "cVieja"), historialTexto(consultas, valores, "cX").map { it.consultaId })
    }

    @Test
    fun sin_consultas_o_sin_valores_no_hay_historial() {
        assertEquals(emptyList(), historialTexto(emptyList(), mapOf("cA" to "A"), "cA"))
        assertEquals(emptyList(), historialTexto(listOf(c("cA", d3)), emptyMap(), "cX"))
        // Solo la actual: nada.
        assertEquals(emptyList(), historialTexto(listOf(c("cA", d3)), mapOf("cA" to "A"), "cA"))
    }
}
