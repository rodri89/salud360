package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Por qué falló la lectura de la historia de la web (RF-56, RF-58): el motivo de la HC con su nombre, incluido el «demasiados
 * pedidos» (el `429` de Laravel llega sin código). Lo traído antes se sigue viendo; la pantalla ofrece el reintento a mano.
 */
class MotivoDeLaLecturaTest {
    private fun motivo(status: Int, codigo: String?, mensaje: String = "Mensaje de la web") =
        motivoDeLaLectura("Clínica", TobbException(status, codigo, mensaje))

    @Test
    fun el_429_dice_demasiados_pedidos_y_nombra_la_hc() {
        val m = motivo(429, null, "Too Many Attempts.")
        assertEquals("Historia clínica de Clínica: demasiados pedidos seguidos. Intentá de nuevo en un momento.", m.texto)
        assertEquals(MotivoHc.Tipo.OTRO, m.tipo)
        assertTrue("Too Many" !in m.texto, "no se muestra el texto en inglés de Laravel")
    }

    @Test
    fun el_429_dice_lo_mismo_con_o_sin_codigo_y_con_cuerpo_que_no_es_json() {
        val sin = motivo(429, null).texto
        assertEquals(sin, motivo(429, "throttle").texto)
        assertEquals(sin, motivoDeLaLectura("Clínica", TobbException(429, null, "")).texto)
    }

    @Test
    fun el_texto_fijo_es_el_del_plan() {
        assertEquals("demasiados pedidos seguidos. Intentá de nuevo en un momento.", TEXTO_DEMASIADOS_PEDIDOS)
    }

    @Test
    fun el_resto_de_los_errores_sale_como_en_el_resto_de_la_hc() {
        listOf(
            TobbException(0, "red", "Sin conexión con la historia clínica de clinica: Failed to fetch"),
            TobbException(503, "tobb_caido", "Turnos caído"),
            TobbException(401, "token", "x"),
            TobbException(401, "sin_token", "x"),
            TobbException(403, "hc_no_habilitada", "x"),
            TobbException(403, "medico_no_vinculado", "x"),
            TobbException(403, "licencia_vencida", "x"),
            TobbException(403, "fuera_de_cartera", "Ese paciente no está en tu cartera."),
            TobbException(404, "consulta_no_encontrada", "Consulta no encontrada."),
            TobbException(422, "datos_invalidos", "Falta el paciente."),
            TobbException(500, "consultas", "No se pudo leer la historia clínica."),
            TobbException(500, null, "Server Error"),
            TobbException(0, "respuesta", "Respuesta inesperada de clinica: JSON cortado"),
        ).forEach { e -> assertEquals(motivoHc("Clínica", e), motivoDeLaLectura("Clínica", e), "${e.status} ${e.codigo}") }
    }

    @Test
    fun sin_conexion_y_sesion_vencida_conservan_sus_textos() {
        assertEquals("Historia clínica de Clínica: sin conexión. Intentá de nuevo en un momento.", motivo(0, "red").texto)
        assertEquals("Historia clínica de Clínica: sesión vencida. Volvé a iniciar sesión.", motivo(401, "token").texto)
        assertEquals(MotivoHc.Tipo.SIN_CONEXION, motivo(0, "red").tipo)
    }

    @Test
    fun otros_errores_conservan_el_mensaje_de_la_web() {
        assertEquals("Historia clínica de Clínica: No se pudo leer la historia clínica.", motivo(500, "consultas", "No se pudo leer la historia clínica.").texto)
    }

    @Test
    fun un_error_que_no_es_de_la_api_dice_su_mensaje_o_error() {
        assertEquals("Historia clínica de Clínica: la base rechazó la fila", motivoDeLaLectura("Clínica", IllegalStateException("la base rechazó la fila")).texto)
        assertEquals("Historia clínica de Clínica: error", motivoDeLaLectura("Clínica", IllegalStateException()).texto)
        assertEquals("Historia clínica de Clínica: error", motivoDeLaLectura("Clínica", IllegalStateException("  ")).texto)
        assertEquals(MotivoHc.Tipo.OTRO, motivoDeLaLectura("Clínica", RuntimeException("x")).tipo)
    }

    @Test
    fun nombra_la_hc_que_recibe() {
        assertTrue(motivoDeLaLectura("Pediatría", TobbException(429, null, "x")).texto.startsWith("Historia clínica de Pediatría:"))
    }
}
