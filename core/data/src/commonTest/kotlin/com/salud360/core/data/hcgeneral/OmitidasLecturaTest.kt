package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.OmitidasConsultasDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cómo se guardan y se leen las consultas que `GET consultas` omite (`hcc_omitidas`, RF-43). */
class OmitidasLecturaTest {

    @Test
    fun va_y_vuelve_sin_perder_ninguna_cuenta() {
        val o = OmitidasConsultasDto(foto = 1, otro = 22, sinFecha = 3)

        assertEquals("1|22|3", omitidasComoTexto(o))
        assertEquals(o, omitidasDeTexto(omitidasComoTexto(o)))
        assertEquals(OmitidasConsultasDto(), omitidasDeTexto(omitidasComoTexto(OmitidasConsultasDto())))
    }

    @Test
    fun lo_que_no_tiene_la_forma_no_se_inventa() {
        listOf(null, "", "1|2", "1|2|3|4", "a|b|c", "1|-2|3", "1||3", "1.5|2|3", " | | ").forEach {
            assertNull(omitidasDeTexto(it), "texto=$it")
        }
    }

    @Test
    fun hay_algo_si_alguna_cuenta_es_mayor_que_cero() {
        assertFalse(OmitidasConsultasDto().hayAlgo())
        assertTrue(OmitidasConsultasDto(foto = 1).hayAlgo())
        assertTrue(OmitidasConsultasDto(otro = 1).hayAlgo())
        assertTrue(OmitidasConsultasDto(sinFecha = 1).hayAlgo())
    }

    @Test
    fun la_clave_es_la_del_plan() {
        assertEquals("hcc_omitidas", CLAVE_OMITIDAS)
    }

    // ---- Cargas de laboratorio y peso sin fecha (D1): otra cuenta, otra clave ----

    @Test
    fun las_cargas_sin_fecha_van_y_vuelven_como_un_numero() {
        assertEquals("hcc_omitidas_laboratorio", CLAVE_OMITIDAS_LABORATORIO)
        assertEquals("0", omitidasLaboratorioComoTexto(0))
        assertEquals("12", omitidasLaboratorioComoTexto(12))
        assertEquals(12, omitidasLaboratorioDeTexto(omitidasLaboratorioComoTexto(12)))
        assertEquals(0, omitidasLaboratorioDeTexto("0"))
    }

    @Test
    fun una_cuenta_de_cargas_que_no_es_un_numero_no_se_inventa() {
        listOf(null, "", " ", "-1", "1.5", "a", "1|2").forEach { assertNull(omitidasLaboratorioDeTexto(it), "texto=$it") }
    }

    @Test
    fun la_cuenta_de_laboratorio_no_cambia_el_formato_de_las_consultas_omitidas() {
        // E7.1 lee `hcc_omitidas` con `omitidasDeTexto`, que exige tres números: la cuenta del historial va en otra fila.
        assertNull(omitidasDeTexto(omitidasLaboratorioComoTexto(3)))
    }

    // ---- Lo que una consulta tiene y esta entrega no muestra (`no_leidas`, RF-43) ----

    @Test
    fun la_clave_de_no_leidas_lleva_el_id_de_la_consulta_de_la_web() {
        assertEquals("hcc_no_leidas_7003", claveNoLeidas(7003))
        assertEquals("hcc_no_leidas_", PREFIJO_CLAVE_NO_LEIDAS)
    }

    @Test
    fun las_no_leidas_van_y_vuelven_en_el_mismo_orden_sin_repetidos() {
        assertEquals("documentos,audio", noLeidasComoTexto(listOf("documentos", "audio")))
        assertEquals(listOf("documentos", "audio"), noLeidasDeTexto("documentos,audio"))
        assertEquals("documentos,audio", noLeidasComoTexto(listOf("documentos", " ", "audio", "documentos")))
        assertEquals(listOf("algo_nuevo"), noLeidasDeTexto(noLeidasComoTexto(listOf("algo_nuevo"))), "un id que la app no conoce se guarda igual")
    }

    @Test
    fun sin_no_leidas_es_un_texto_vacio_y_no_es_lo_mismo_que_no_saber() {
        assertEquals("", noLeidasComoTexto(emptyList()))
        assertEquals(emptyList(), noLeidasDeTexto(""))
        assertNull(noLeidasDeTexto(null), "sin fila no se sabe nada")
    }
}
