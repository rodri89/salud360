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
}
