package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.RegistroClinico
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Qué entrada de un estudio con historial se edita en una consulta, qué va a "Consultas previas" y qué se guarda (RF-38, RF-39). */
class EstudioConHistorialTest {
    private fun r(id: String, consulta: String?, fecha: LocalDate?, texto: String) =
        RegistroClinico(id, "pac", consulta, "clinica", "biopsia_hepatica", fecha, mapOf("detalle" to texto))

    private val d1 = LocalDate(2026, 1, 10)
    private val d2 = LocalDate(2026, 3, 5)
    private val d3 = LocalDate(2026, 6, 20)

    // Como las da `registrosDePaciente`: fecha DESC y, a igual fecha, updated_at DESC.
    private val a = r("a", "cA", d3, "texto A")
    private val b = r("b", "cB", d2, "texto B")
    private val c = r("c", "cC", d1, "texto C")
    private val todas = listOf(a, b, c)

    @Test
    fun consulta_sin_entrada_no_edita_nada_y_todas_van_al_historial() {
        assertNull(entradaDeConsulta(todas, "cNueva"))
        assertEquals(listOf(a, b, c), historialEstudio(todas, null))
    }

    @Test
    fun la_entrada_de_la_consulta_no_sale_en_el_historial() {
        assertEquals(b, entradaDeConsulta(todas, "cB"))
        assertEquals(listOf(a, c), historialEstudio(todas, b))
    }

    @Test
    fun con_dos_entradas_en_la_misma_consulta_se_edita_la_mas_reciente_y_la_otra_va_al_historial() {
        val vieja = r("v", "cB", d1, "vieja de B")
        val registros = listOf(a, b, vieja)
        assertEquals(b, entradaDeConsulta(registros, "cB"))
        assertEquals(listOf(a, vieja), historialEstudio(registros, b))
        // Aunque lleguen desordenadas, manda la fecha.
        assertEquals(b, entradaDeConsulta(listOf(vieja, a, b), "cB"))
    }

    @Test
    fun las_vacias_y_en_blanco_no_salen_en_el_historial() {
        val vacia = r("v", "cV", d2, "")
        val blanco = r("bl", "cBl", d2, "   ")
        val sinCampo = RegistroClinico("s", "pac", "cS", "clinica", "biopsia_hepatica", d2)
        assertEquals(listOf(a, c), historialEstudio(listOf(a, vacia, blanco, sinCampo, c), null))
    }

    @Test
    fun historial_por_fecha_y_a_igual_fecha_por_updated_at() {
        val recienteMismaFecha = r("x", "cX", d2, "editada después")
        val viejaMismaFecha = r("y", "cY", d2, "editada antes")
        val sinFecha = r("z", "cZ", null, "sin fecha")
        // Desordenadas por fecha; entre las de d2, la lista trae primero la de updated_at más reciente.
        val registros = listOf(c, sinFecha, recienteMismaFecha, a, viejaMismaFecha)
        assertEquals(listOf(a, recienteMismaFecha, viejaMismaFecha, c, sinFecha), historialEstudio(registros, null))
    }

    @Test
    fun sin_entrada_y_con_texto_se_crea() {
        assertEquals(CambioEstudio.Crear(d2, "nuevo"), cambioEstudio(null, d2, "nuevo"))
    }

    @Test
    fun con_entrada_y_otro_texto_u_otra_fecha_se_actualiza() {
        assertEquals(
            CambioEstudio.Actualizar(b.copy(campos = mapOf("detalle" to "texto B corregido"))),
            cambioEstudio(b, d2, "texto B corregido"),
        )
        assertEquals(CambioEstudio.Actualizar(b.copy(fecha = d3)), cambioEstudio(b, d3, "texto B"))
    }

    @Test
    fun con_entrada_y_texto_en_blanco_se_borra() {
        assertEquals(CambioEstudio.Borrar(b), cambioEstudio(b, d2, ""))
        assertEquals(CambioEstudio.Borrar(b), cambioEstudio(b, d2, "  \n "))
    }

    @Test
    fun sin_cambios_o_sin_entrada_y_en_blanco_no_se_hace_nada() {
        assertEquals(CambioEstudio.Nada, cambioEstudio(b, d2, "texto B"))
        assertEquals(CambioEstudio.Nada, cambioEstudio(null, d2, ""))
        assertEquals(CambioEstudio.Nada, cambioEstudio(null, d2, "   "))
    }
}
