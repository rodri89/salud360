package com.salud360.core.data.network.hc

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * La ficha que devuelve `POST pacientes/resolver` se lee con su `sexo` (pediatría lo informa) y sigue
 * leyéndose si el servidor no lo manda. Misma configuración de `Json` que el cliente de la HC. Datos inventados.
 */
class HcPacienteDtoTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = true }

    private fun leer(texto: String) = json.decodeFromString(HcPaciente.serializer(), texto)

    @Test
    fun con_sexo_lo_lee() {
        val p = leer("""{"id":7,"paciente_id_tobb":12,"nombre":"Ana","apellido":"Prueba","dni":"30000001","sexo":"F"}""")
        assertEquals("F", p.sexo)
        assertEquals(7L, p.id)
        assertEquals(12L, p.pacienteIdTobb)
    }

    @Test
    fun sin_la_clave_sexo_queda_null_servidor_viejo() {
        val p = leer("""{"id":7,"paciente_id_tobb":12,"nombre":"Ana","apellido":"Prueba","dni":"30000001"}""")
        assertNull(p.sexo)
        assertEquals(7L, p.id)
    }

    @Test
    fun con_sexo_nulo_queda_null() {
        assertNull(leer("""{"id":7,"sexo":null}""").sexo)
    }

    @Test
    fun con_sexo_vacio_se_conserva_vacio_y_lo_descarta_la_regla() {
        assertEquals("", leer("""{"id":7,"sexo":""}""").sexo)
    }

    @Test
    fun con_claves_que_no_conoce_sigue_leyendo() {
        val p = leer("""{"id":7,"sexo":"M","grupo_sanguineo":"0+","extra":{"a":1}}""")
        assertEquals("M", p.sexo)
    }
}
