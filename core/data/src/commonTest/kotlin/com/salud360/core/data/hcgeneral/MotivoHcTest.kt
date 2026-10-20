package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Por qué no se pudo usar una historia clínica, nombrándola (spec 002 RF-9). */
class MotivoHcTest {

    private fun motivo(status: Int, codigo: String?, mensaje: String = "Mensaje de la web") =
        motivoHc("Clínica", TobbException(status, codigo, mensaje))

    @Test
    fun cada_codigo_da_su_motivo_y_nombra_la_hc() {
        val casos = listOf(
            motivo(0, "red") to MotivoHc.Tipo.SIN_CONEXION,
            motivo(503, "tobb_caido") to MotivoHc.Tipo.SIN_CONEXION,
            motivo(401, "token") to MotivoHc.Tipo.SESION_VENCIDA,
            motivo(401, "sin_token") to MotivoHc.Tipo.SESION_VENCIDA,
            motivo(403, "hc_no_habilitada") to MotivoHc.Tipo.HC_NO_HABILITADA,
            motivo(403, "medico_no_vinculado") to MotivoHc.Tipo.MEDICO_NO_VINCULADO,
            motivo(403, "licencia_vencida") to MotivoHc.Tipo.LICENCIA_VENCIDA,
        )
        casos.forEach { (m, tipo) ->
            assertEquals(tipo, m.tipo, m.texto)
            assertTrue("Clínica" in m.texto, m.texto)
        }
    }

    @Test
    fun los_motivos_conocidos_no_usan_el_mensaje_de_la_web() {
        val textos = listOf(
            motivo(0, "red", "Sin conexión con la historia clínica de clinica: Failed to fetch"),
            motivo(401, "token", "Sesión vencida. Volvé a iniciar sesión."),
        ).map { it.texto }
        assertTrue(textos.none { "Failed to fetch" in it || "clinica" in it }, textos.toString())
    }

    @Test
    fun textos_de_cada_motivo() {
        assertEquals("Historia clínica de Clínica: sin conexión. Intentá de nuevo en un momento.", motivo(0, "red").texto)
        assertEquals(motivo(0, "red").texto, motivo(503, "tobb_caido").texto)
        assertEquals("Historia clínica de Clínica: sesión vencida. Volvé a iniciar sesión.", motivo(401, "token").texto)
        assertEquals("Historia clínica de Clínica: no habilitada. Avisale al administrador.", motivo(403, "hc_no_habilitada").texto)
        assertEquals(
            "Historia clínica de Clínica: tu usuario no está vinculado con turnosonlinebb. Avisale al administrador.",
            motivo(403, "medico_no_vinculado").texto,
        )
        assertEquals(
            "Historia clínica de Clínica: licencia vencida. Comunicate con el administrador para renovarla.",
            motivo(403, "licencia_vencida").texto,
        )
    }

    @Test
    fun codigo_desconocido_conserva_el_mensaje_de_la_web() {
        val m = motivo(403, "sin_permiso", "No tenés permiso para esto.")
        assertEquals(MotivoHc.Tipo.OTRO, m.tipo)
        assertEquals("Historia clínica de Clínica: No tenés permiso para esto.", m.texto)
    }

    @Test
    fun ocupado_agotado_es_otro_con_el_mensaje_de_la_web() {
        val m = motivo(503, "ocupado", "Otro pedido está trabajando con ese documento. Probá de nuevo.")
        assertEquals(MotivoHc.Tipo.OTRO, m.tipo)
        assertEquals("Historia clínica de Clínica: Otro pedido está trabajando con ese documento. Probá de nuevo.", m.texto)
    }

    @Test
    fun error_500_sin_codigo_es_otro_con_el_mensaje_de_la_web() {
        val m = motivo(500, null, "Server Error")
        assertEquals(MotivoHc.Tipo.OTRO, m.tipo)
        assertEquals("Historia clínica de Clínica: Server Error", m.texto)
    }

    @Test
    fun sin_mensaje_dice_lo_que_respondio() {
        val m = motivo(500, null, "  ")
        assertEquals(MotivoHc.Tipo.OTRO, m.tipo)
        assertEquals("Historia clínica de Clínica: respondió 500.", m.texto)
    }

    @Test
    fun status_0_sin_codigo_es_sin_conexion() {
        assertEquals(MotivoHc.Tipo.SIN_CONEXION, motivo(0, null).tipo)
    }

    @Test
    fun nombra_la_hc_que_recibe() {
        val m = motivoHc("Pediatría", TobbException(403, "licencia_vencida", "x"))
        assertTrue(m.texto.startsWith("Historia clínica de Pediatría:"), m.texto)
    }
}
