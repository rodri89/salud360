package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Laboratorio
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Columnas y acciones de las cargas de un laboratorio de la HC general (RF-55, RF-57, RF-60 a RF-64). */
class CargasLaboratorioTest {
    private val esta = "c1"

    private fun carga(id: String, consultaId: String?, dia: Int, mes: Int = 10, activo: Boolean = true) =
        Laboratorio(id, "p1", consultaId, "clinica", "laboratorio_general", LocalDate(2026, mes, dia), mapOf("hb" to "13"), activo)

    @Test
    fun todas_intercaladas_por_fecha_descendente() {
        val historico = listOf(
            carga("a", "c2", 20),
            carga("b", esta, 15),
            carga("c", "c3", 10),
            carga("d", esta, 5),
            carga("e", "c4", 1),
        ).reversed()
        val columnas = columnasLaboratorio(historico, esta, editable = true)
        assertEquals(listOf("a", "b", "c", "d", "e"), columnas.map { it.carga.id })
        assertEquals(listOf(false, true, false, true, false), columnas.map { it.deEstaConsulta })
    }

    @Test
    fun a_igual_fecha_se_conserva_el_orden_de_entrada() {
        // La consulta las trae a igual fecha por updated_at DESC: ese orden no se toca.
        val historico = listOf(
            carga("nueva", "c2", 10),
            carga("vieja", "c2", 10),
            carga("de_esta", esta, 10),
            carga("antes", "c2", 2),
        )
        assertEquals(
            listOf("nueva", "vieja", "de_esta", "antes"),
            columnasLaboratorio(historico, esta, editable = true).map { it.carga.id },
        )
    }

    @Test
    fun una_inactiva_no_sale() {
        val historico = listOf(carga("a", esta, 10), carga("b", "c2", 9, activo = false), carga("c", "c2", 8))
        assertEquals(listOf("a", "c"), columnasLaboratorio(historico, esta, editable = true).map { it.carga.id })
    }

    @Test
    fun ocho_de_otras_y_todas_las_de_esta_en_su_lugar() {
        val otras = (1..12).map { carga("o$it", "c2", 30 - it) }
        val deEsta = listOf(carga("e1", esta, 1), carga("e2", esta, 25, mes = 9))
        val columnas = columnasLaboratorio(otras + deEsta, esta, editable = true)
        assertEquals((1..8).map { "o$it" } + listOf("e1", "e2"), columnas.map { it.carga.id })

        // Con fecha vieja en medio de las otras: queda en su lugar y las otras siguen siendo 8.
        val enMedio = listOf(carga("e3", esta, 25)) + otras
        assertEquals(
            listOf("o1", "o2", "o3", "o4", "e3", "o5", "o6", "o7", "o8"),
            columnasLaboratorio(enMedio, esta, editable = true).map { it.carga.id },
        )
    }

    @Test
    fun el_tacho_va_en_todas_o_en_ninguna() {
        val historico = listOf(carga("a", esta, 10), carga("b", "c2", 9))
        assertTrue(columnasLaboratorio(historico, esta, editable = true).all { it.puedeBorrar })
        assertTrue(columnasLaboratorio(historico, esta, editable = false).none { it.puedeBorrar })
    }

    @Test
    fun una_carga_de_la_web_nunca_lleva_tacho_aunque_la_consulta_se_pueda_editar() {
        // RF-62: borrarla acá no llegaría a la web y los dos lados quedarían distintos.
        val web = Laboratorio(idCargaWeb(TIPO_LABORATORIO_GENERAL, 1797), "p1", null, "clinica", TIPO_LABORATORIO_GENERAL, LocalDate(2026, 10, 9), mapOf("hb" to "12"))
        val peso = Laboratorio(idCargaWeb(TIPO_PESO, 55), "p1", null, "clinica", TIPO_PESO, LocalDate(2026, 10, 8), mapOf("peso" to "70"))
        val historico = listOf(carga("a", esta, 10), web, carga("b", "c2", 7), peso)
        val columnas = columnasLaboratorio(historico, esta, editable = true)
        assertEquals(listOf("a", web.id, peso.id, "b"), columnas.map { it.carga.id })
        assertEquals(listOf(true, false, false, true), columnas.map { it.puedeBorrar })
        // Se siguen mostrando (están en la lista de arriba), solo que sin tacho.
    }

    @Test
    fun una_carga_de_la_web_en_una_consulta_cerrada_tampoco_lleva_tacho() {
        val web = Laboratorio(idCargaWeb(TIPO_LABORATORIO_REDUCIDO, 3), "p1", null, "clinica", "laboratorio_reducido", LocalDate(2026, 10, 9), mapOf("hb" to "12"))
        assertTrue(columnasLaboratorio(listOf(web, carga("a", "c2", 5)), esta, editable = false).none { it.puedeBorrar })
    }

    @Test
    fun puedeBorrarCarga_exige_consulta_editable_y_carga_local() {
        val web = Laboratorio(idCargaWeb(TIPO_LABORATORIO_GENERAL, 1), "p1", "hcc-c9", "clinica", TIPO_LABORATORIO_GENERAL, LocalDate(2026, 10, 9))
        val local = carga("3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b", esta, 10)
        assertTrue(web.esDeLaWeb())
        assertFalse(local.esDeLaWeb())
        assertFalse(puedeBorrarCarga(web, editable = true))
        assertFalse(puedeBorrarCarga(web, editable = false))
        assertTrue(puedeBorrarCarga(local, editable = true))
        assertFalse(puedeBorrarCarga(local, editable = false))
    }

    @Test
    fun nueva_carga_mientras_se_pueda_editar_y_no_haya_una_abierta() {
        assertTrue(mostrarNuevaCarga(editable = true, abierta = false))
        assertFalse(mostrarNuevaCarga(editable = true, abierta = true))
        assertFalse(mostrarNuevaCarga(editable = false, abierta = false))
    }

    @Test
    fun nueva_carga_aunque_la_consulta_ya_tenga_cargas() {
        val columnas = columnasLaboratorio(listOf(carga("a", esta, 10)), esta, editable = true)
        assertTrue(columnas.any { it.deEstaConsulta })
        assertTrue(mostrarNuevaCarga(editable = true, abierta = false))
    }

    @Test
    fun una_carga_sin_valores_no_vale() {
        assertFalse(cargaNuevaValida(emptyMap()))
        assertFalse(cargaNuevaValida(mapOf("a" to "  ")))
        assertTrue(cargaNuevaValida(mapOf("a" to "  ", "hb" to "13")))
    }

    @Test
    fun varias_cargas_solo_en_la_hc_general() {
        assertTrue(cargasMultiples("clinica"))
        assertFalse(cargasMultiples("pediatria"))
    }
}
