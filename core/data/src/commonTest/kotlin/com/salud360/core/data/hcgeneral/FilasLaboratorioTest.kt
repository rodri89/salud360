package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.AnalitoDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Qué filas de un laboratorio se ven y cómo cambian las ocultas de un médico (RF-44, RF-45, RF-47, RF-48, RF-49). */
class FilasLaboratorioTest {
    private val hb = AnalitoDef("hb", "Hemoglobina", "g/dL", grupo = "Hemograma")
    private val gb = AnalitoDef("gb", "Glóbulos blancos", grupo = "Hemograma")
    private val plaq = AnalitoDef("plaq", "Plaquetas", grupo = "Hemograma")
    private val glu = AnalitoDef("glu", "Glucemia", "mg/dL", grupo = "Química")
    private val urea = AnalitoDef("urea", "Urea", grupo = "Química")
    private val analitos = listOf(hb, gb, plaq, glu, urea)
    private val todas = analitos.map { it.clave }.toSet()

    @Test
    fun la_clave_es_por_especialidad_y_por_laboratorio() {
        assertEquals("filas_ocultas_clinica_laboratorio_general", clavePreferenciaFilasOcultas("clinica", "laboratorio_general"))
        assertEquals("filas_ocultas_clinica_laboratorio_reducido", clavePreferenciaFilasOcultas("clinica", "laboratorio_reducido"))
    }

    @Test
    fun la_preferencia_se_lee_como_conjunto_sin_vacios() {
        assertEquals(emptySet(), ocultasDePreferencia(null))
        assertEquals(emptySet(), ocultasDePreferencia(""))
        assertEquals(setOf("a", "b"), ocultasDePreferencia("a,,b"))
    }

    @Test
    fun sin_ocultas_se_ven_todas_en_orden() {
        assertEquals(analitos, filasVisibles(analitos, emptySet()))
    }

    @Test
    fun una_oculta_no_se_ve_y_el_resto_sigue_en_orden() {
        assertEquals(listOf(hb, plaq, glu, urea), filasVisibles(analitos, setOf("gb")))
    }

    @Test
    fun si_lo_guardado_las_oculta_todas_se_ven_todas() {
        assertEquals(analitos, filasVisibles(analitos, todas))
        assertEquals(analitos, filasVisibles(analitos, todas + "otra"))
    }

    @Test
    fun una_clave_que_el_laboratorio_no_tiene_se_ignora() {
        assertEquals(analitos, filasVisibles(analitos, setOf("otra")))
        assertEquals(listOf(hb, gb, plaq, urea), filasVisibles(analitos, setOf("otra", "glu")))
    }

    @Test
    fun alternar_oculta_y_muestra() {
        assertEquals(setOf("glu"), alternarFila(analitos, emptySet(), "glu", visible = false))
        assertEquals(setOf("hb"), alternarFila(analitos, setOf("hb", "glu"), "glu", visible = true))
    }

    @Test
    fun alternar_no_oculta_la_ultima_visible() {
        val casiTodas = setOf("hb", "gb", "plaq", "glu")
        assertEquals(casiTodas, alternarFila(analitos, casiTodas, "urea", visible = false))
    }

    @Test
    fun alternar_conserva_las_claves_de_afuera() {
        assertEquals(setOf("otra", "glu"), alternarFila(analitos, setOf("otra"), "glu", visible = false))
        assertEquals(setOf("otra"), alternarFila(analitos, setOf("otra", "glu"), "glu", visible = true))
    }

    @Test
    fun la_ultima_visible_es_la_unica_que_queda() {
        val casiTodas = setOf("hb", "gb", "plaq", "glu")
        assertTrue(esUltimaVisible(analitos, casiTodas, "urea"))
        assertFalse(esUltimaVisible(analitos, casiTodas, "glu"))
        assertFalse(esUltimaVisible(analitos, setOf("hb"), "urea"))
        assertFalse(esUltimaVisible(analitos, emptySet(), "urea"))
    }

    @Test
    fun mostrar_todas_quita_solo_las_de_este_laboratorio() {
        assertEquals(setOf("otra"), mostrarTodasLasFilas(analitos, setOf("otra", "hb", "glu")))
        assertEquals(emptySet(), mostrarTodasLasFilas(analitos, setOf("hb")))
    }

    @Test
    fun el_boton_dice_cuantas_estan_ocultas() {
        assertEquals("Elegir filas", textoBotonFilas(analitos, emptySet()))
        assertEquals("Elegir filas (3 ocultas)", textoBotonFilas(analitos, setOf("hb", "gb", "glu")))
        assertEquals("Elegir filas (1 oculta)", textoBotonFilas(analitos, setOf("hb", "otra")))
        assertEquals("Elegir filas", textoBotonFilas(analitos, setOf("otra")))
    }

    @Test
    fun solo_la_hc_general_con_mas_de_una_fila_puede_elegir() {
        assertTrue(puedeElegirFilas("clinica", listOf(hb, gb)))
        assertFalse(puedeElegirFilas("clinica", listOf(AnalitoDef("peso", "Peso", "kg"))))
        assertFalse(puedeElegirFilas("pediatria", listOf(hb, gb)))
    }
}
