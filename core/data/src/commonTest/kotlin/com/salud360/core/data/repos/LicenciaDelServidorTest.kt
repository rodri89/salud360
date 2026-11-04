package com.salud360.core.data.repos

import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.PerfilMedico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las reglas puras de la spec 004: con conexión decide lo que contesta el servidor de la HC; la copia local solo
 * decide cuando el servidor no dio una respuesta concluyente, y se mantiene al día con lo que el servidor contesta.
 */
class LicenciaDelServidorTest {
    private val ayer = hoy().minus(DatePeriod(days = 1)).toString()
    private val manana = hoy().plus(DatePeriod(days = 1)).toString()
    private val lejos = hoy().plus(DatePeriod(years = 1)).toString()
    private val avisoYa = hoy().minus(DatePeriod(days = 10)).toString()

    private val usuario = Usuario("u1", "medico@salud360.com", "Juan", "Pérez", Rol.MEDICO)

    private fun sesion(vence: String?, activa: Boolean = true) = Sesion(
        usuario, token = "t",
        medico = PerfilMedico("tobb-m1", listOf("pediatria"), tieneTurnos = true, licenciaVence = vence, licenciaActiva = activa),
    )

    private val vigente = EstadoLicenciaRemoto.Vigente(vence = lejos, avisoDesde = avisoYa, porVencer = false)

    // ---- licenciaBloqueadaSegun ----

    @Test
    fun vigente_en_el_servidor_no_bloquea_con_ninguna_copia() {
        assertFalse(licenciaBloqueadaSegun(vigente, sesion(vence = manana)))
        assertFalse(licenciaBloqueadaSegun(vigente, sesion(vence = null)))
        // El caso reportado: la copia del navegador está vencida por fecha o dada de baja y el servidor dice vigente.
        assertFalse(licenciaBloqueadaSegun(vigente, sesion(vence = ayer)))
        assertFalse(licenciaBloqueadaSegun(vigente, sesion(vence = manana, activa = false)))
        assertFalse(licenciaBloqueadaSegun(vigente, sesion(vence = "sin fecha")))
    }

    @Test
    fun vencida_en_el_servidor_bloquea_con_ninguna_copia() {
        assertTrue(licenciaBloqueadaSegun(EstadoLicenciaRemoto.Vencida, sesion(vence = manana)))
        assertTrue(licenciaBloqueadaSegun(EstadoLicenciaRemoto.Vencida, sesion(vence = null)))
        assertTrue(licenciaBloqueadaSegun(EstadoLicenciaRemoto.Vencida, sesion(vence = ayer)))
        assertTrue(licenciaBloqueadaSegun(EstadoLicenciaRemoto.Vencida, sesion(vence = manana, activa = false)))
    }

    /** P3: sin ninguna fila en pediatría no se bloquea el ingreso (solo la HC de pediatría, que frena el servidor). */
    @Test
    fun sin_licencia_en_el_servidor_nunca_bloquea_el_ingreso() {
        assertFalse(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinLicencia, sesion(vence = manana)))
        assertFalse(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinLicencia, sesion(vence = null)))
        assertFalse(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinLicencia, sesion(vence = ayer)))
        assertFalse(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinLicencia, sesion(vence = manana, activa = false)))
    }

    @Test
    fun sin_dato_decide_la_copia_local_con_la_regla_de_hoy() {
        assertFalse(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinDato, sesion(vence = manana)))
        assertTrue(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinDato, sesion(vence = ayer)))
        assertTrue(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinDato, sesion(vence = manana, activa = false)))
        // Sin copia (P1) y con una fecha ilegible: no bloquea, como `LicenciaTest`.
        assertFalse(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinDato, sesion(vence = null)))
        assertFalse(licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinDato, sesion(vence = "sin fecha")))
    }

    @Test
    fun sin_dato_es_exactamente_la_regla_de_la_sesion() {
        for (s in listOf(sesion(manana), sesion(ayer), sesion(null), sesion(manana, activa = false), sesion("sin fecha"))) {
            assertEquals(s.licenciaBloqueada(), licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinDato, s))
        }
    }

    // ---- copiaDeLicencia ----

    private fun copia(vence: String, aviso: String, importe: Double = 0.0, activo: Boolean = true) =
        Licencia("tobb-m1", vence, aviso, importe, activo)

    @Test
    fun vigente_sobre_copia_ausente_crea_la_copia_con_importe_cero() {
        assertEquals(copia(lejos, avisoYa, importe = 0.0, activo = true), copiaDeLicencia(vigente, previa = null, medicoId = "tobb-m1"))
    }

    @Test
    fun vigente_sobre_copia_vencida_o_dada_de_baja_la_deja_vigente_y_conserva_el_importe() {
        val vieja = copia(vence = ayer, aviso = "2020-01-01", importe = 1500.5, activo = false)
        assertEquals(copia(lejos, avisoYa, importe = 1500.5, activo = true), copiaDeLicencia(vigente, vieja, "tobb-m1"))
    }

    @Test
    fun vigente_sin_fecha_de_aviso_conserva_la_previa() {
        val previa = copia(vence = ayer, aviso = "2020-01-01", importe = 10.0, activo = false)
        val sinAviso = EstadoLicenciaRemoto.Vigente(vence = lejos, avisoDesde = "", porVencer = false)
        assertEquals(copia(lejos, "2020-01-01", importe = 10.0, activo = true), copiaDeLicencia(sinAviso, previa, "tobb-m1"))
    }

    @Test
    fun vigente_sin_fecha_de_aviso_y_sin_previa_deja_el_aviso_vacio() {
        val sinAviso = EstadoLicenciaRemoto.Vigente(vence = lejos, avisoDesde = "", porVencer = false)
        assertEquals(copia(lejos, ""), copiaDeLicencia(sinAviso, previa = null, medicoId = "tobb-m1"))
    }

    @Test
    fun vigente_con_vence_vacio_no_es_concluyente_y_no_escribe() {
        val sinVence = EstadoLicenciaRemoto.Vigente(vence = "", avisoDesde = avisoYa, porVencer = true)
        assertNull(copiaDeLicencia(sinVence, previa = null, medicoId = "tobb-m1"))
        assertNull(copiaDeLicencia(sinVence, previa = copia(manana, avisoYa, 5.0), medicoId = "tobb-m1"))
    }

    @Test
    fun vigente_igual_a_la_previa_no_reescribe() {
        val igual = copia(lejos, avisoYa, importe = 99.0, activo = true)
        assertNull(copiaDeLicencia(vigente, igual, "tobb-m1"))
    }

    @Test
    fun por_vencer_no_cambia_lo_que_se_guarda() {
        val porVencer = EstadoLicenciaRemoto.Vigente(vence = lejos, avisoDesde = avisoYa, porVencer = true)
        assertEquals(copiaDeLicencia(vigente, null, "tobb-m1"), copiaDeLicencia(porVencer, null, "tobb-m1"))
    }

    @Test
    fun la_copia_se_identifica_con_el_medico_pedido() {
        assertEquals("tobb-m7", copiaDeLicencia(vigente, previa = null, medicoId = "tobb-m7")?.medicoId)
    }

    @Test
    fun vencida_sobre_copia_activa_la_pone_inactiva_con_sus_fechas_e_importe() {
        val activa = copia(vence = manana, aviso = avisoYa, importe = 700.0, activo = true)
        assertEquals(activa.copy(activo = false), copiaDeLicencia(EstadoLicenciaRemoto.Vencida, activa, "tobb-m1"))
    }

    @Test
    fun vencida_sobre_copia_ya_inactiva_no_reescribe() {
        assertNull(copiaDeLicencia(EstadoLicenciaRemoto.Vencida, copia(ayer, avisoYa, activo = false), "tobb-m1"))
    }

    @Test
    fun vencida_sin_copia_no_crea_una_fila_sin_fechas() {
        assertNull(copiaDeLicencia(EstadoLicenciaRemoto.Vencida, previa = null, medicoId = "tobb-m1"))
    }

    @Test
    fun sin_licencia_no_toca_la_copia_con_o_sin_previa() {
        assertNull(copiaDeLicencia(EstadoLicenciaRemoto.SinLicencia, previa = null, medicoId = "tobb-m1"))
        assertNull(copiaDeLicencia(EstadoLicenciaRemoto.SinLicencia, copia(manana, avisoYa, 3.0), "tobb-m1"))
        assertNull(copiaDeLicencia(EstadoLicenciaRemoto.SinLicencia, copia(ayer, avisoYa, 3.0, activo = false), "tobb-m1"))
    }

    @Test
    fun sin_dato_no_toca_la_copia_con_o_sin_previa() {
        assertNull(copiaDeLicencia(EstadoLicenciaRemoto.SinDato, previa = null, medicoId = "tobb-m1"))
        assertNull(copiaDeLicencia(EstadoLicenciaRemoto.SinDato, copia(manana, avisoYa, 3.0), "tobb-m1"))
        assertNull(copiaDeLicencia(EstadoLicenciaRemoto.SinDato, copia(ayer, avisoYa, 3.0, activo = false), "tobb-m1"))
    }
}
