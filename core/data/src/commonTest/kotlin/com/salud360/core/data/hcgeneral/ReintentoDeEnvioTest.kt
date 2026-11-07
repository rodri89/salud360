package com.salud360.core.data.hcgeneral

import com.salud360.core.data.sync.HcApiSync
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 005, T27 (RF-29): cuánto se espera para volver a intentar lo que quedó «En espera». Puro. Las causas pasajeras (sin conexión, la
 * web caída, «demasiados pedidos») reintentan a los 15 s, al minuto y a los 5 minutos —las esperas del envío diferido que ya usa
 * pediatría— y después cada 5 minutos. Las que dependen de una cuenta o de una decisión de alguien (sesión vencida, HC no habilitada,
 * licencia, ficha por elegir…) no se arreglan en segundos: se vuelven a mirar cada 5 minutos. Nunca más rápido: con 60 pedidos por minuto
 * por IP, un consultorio entero comparte el límite.
 */
class ReintentoDeEnvioTest {
    private fun espera(causa: CausaEnEspera) = EstadoEnvio.EnEspera(causa, "motivo")

    private val pasajeras = listOf(CausaEnEspera.SIN_CONEXION, CausaEnEspera.DEMASIADOS_PEDIDOS, CausaEnEspera.SERVIDOR)

    private val de_cuenta = CausaEnEspera.entries - pasajeras.toSet()

    @Test
    fun las_esperas_son_las_del_envio_diferido_de_pediatria() {
        assertEquals(HcApiSync.ESPERAS, ESPERAS_DE_REINTENTO)
        assertEquals(listOf(15_000L, 60_000L, 300_000L), ESPERAS_DE_REINTENTO)
        assertEquals(300_000L, ESPERA_PERIODICA)
    }

    @Test
    fun una_causa_pasajera_reintenta_a_los_15_s_al_minuto_a_los_5_minutos_y_despues_cada_5_minutos() {
        for (causa in pasajeras) {
            val esperas = (0..6).map { esperaDeReintento(espera(causa), it) }
            assertEquals(listOf(15_000L, 60_000L, 300_000L, 300_000L, 300_000L, 300_000L, 300_000L), esperas, causa.name)
        }
    }

    @Test
    fun una_causa_de_cuenta_o_de_alguien_se_vuelve_a_mirar_cada_5_minutos_desde_el_primer_intento() {
        for (causa in de_cuenta) {
            assertEquals(List(5) { 300_000L }, (0..4).map { esperaDeReintento(espera(causa), it) }, causa.name)
        }
    }

    @Test
    fun todas_las_causas_estan_en_una_de_las_dos_familias() {
        assertEquals(CausaEnEspera.entries.size, pasajeras.size + de_cuenta.size)
        assertTrue(CausaEnEspera.SESION_VENCIDA in de_cuenta && CausaEnEspera.ELEGIR_FICHA in de_cuenta && CausaEnEspera.PACIENTE_DE_BAJA in de_cuenta)
    }

    @Test
    fun nunca_se_espera_menos_de_15_s_ni_con_un_intento_raro() {
        for (causa in CausaEnEspera.entries) {
            for (intento in listOf(-5, 0, 1, 2, 3, 100, Int.MAX_VALUE)) {
                assertTrue(esperaDeReintento(espera(causa), intento) >= 15_000L, "$causa / $intento")
            }
        }
    }
}
