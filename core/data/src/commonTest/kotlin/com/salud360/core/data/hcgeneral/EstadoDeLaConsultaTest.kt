package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T28 (RF-24, RF-25, RF-26, RF-55): cómo se ve una consulta propia de la HC general por lo que falta enviar, y qué se puede
 * hacer con ella. Puro: sin base ni red. La marca es «Falta enviar» / «En espera: <motivo>» / «No se pudo enviar: <motivo>» /
 * «Enviando el cierre.»; **reabrir** solo mientras el cierre no se haya enviado y no esté viajando (D13); **anular** una abierta, o una
 * cerrada cuyo cierre todavía no se envió, nunca una con el cierre ya enviado (RF-26).
 */
class EstadoDeLaConsultaTest {
    private val fecha = LocalDate.parse("2026-11-06")

    private fun consulta(estado: EstadoConsulta, remotoId: String = "7001") =
        Consulta("c1", "p1", "m1", "clinica", "control", fecha, estado, remotoId = remotoId, propia = true)

    private fun estado(
        consulta: Consulta,
        sucia: Boolean = false,
        cierre: EstadoEnvio? = null,
        envio: EstadoEnvio? = null,
        viajando: Boolean = false,
    ) = estadoDeLaConsulta(consulta, sucia, cierre, envio, viajando)

    // ---- Cuándo el cierre ya se envió ----

    @Test
    fun el_cierre_se_envio_si_esta_cerrada_creada_y_sin_pendiente() {
        assertTrue(cierreEnviado(consulta(EstadoConsulta.CERRADA), filaSucia = false, estadoDelCierre = null))
    }

    @Test
    fun con_la_fila_sucia_el_cierre_todavia_no_se_envio() {
        assertFalse(cierreEnviado(consulta(EstadoConsulta.CERRADA), filaSucia = true, estadoDelCierre = null))
    }

    @Test
    fun una_cerrada_que_nunca_se_creo_en_la_web_no_envio_el_cierre() {
        assertFalse(cierreEnviado(consulta(EstadoConsulta.CERRADA, remotoId = ""), filaSucia = false, estadoDelCierre = null))
    }

    @Test
    fun un_cierre_descartado_no_se_envio_aunque_la_fila_este_limpia() {
        assertFalse(cierreEnviado(consulta(EstadoConsulta.CERRADA), filaSucia = false, estadoDelCierre = EstadoEnvio.Descartado))
    }

    @Test
    fun una_cerrada_con_el_cierre_en_espera_o_rechazado_no_lo_envio() {
        val espera = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "sin conexión")
        val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "no la acepta")

        assertFalse(cierreEnviado(consulta(EstadoConsulta.CERRADA), filaSucia = true, estadoDelCierre = espera))
        assertFalse(cierreEnviado(consulta(EstadoConsulta.CERRADA), filaSucia = true, estadoDelCierre = rechazo))
    }

    @Test
    fun una_abierta_o_anulada_no_tiene_cierre_enviado() {
        assertFalse(cierreEnviado(consulta(EstadoConsulta.ABIERTA), filaSucia = false, estadoDelCierre = null))
        assertFalse(cierreEnviado(consulta(EstadoConsulta.ANULADA), filaSucia = false, estadoDelCierre = null))
    }

    // ---- Qué se puede hacer ----

    @Test
    fun una_abierta_se_puede_anular_y_no_reabrir() {
        val e = estado(consulta(EstadoConsulta.ABIERTA))

        assertFalse(e.puedeReabrir)
        assertTrue(e.puedeAnular)
        assertFalse(e.cierreEnviado)
    }

    @Test
    fun una_cerrada_cuyo_cierre_no_se_envio_se_puede_reabrir_y_anular() {
        val e = estado(consulta(EstadoConsulta.CERRADA), sucia = true, envio = EstadoEnvio.Pendiente)

        assertTrue(e.puedeReabrir)
        assertTrue(e.puedeAnular)
    }

    @Test
    fun una_cerrada_con_el_cierre_enviado_no_se_puede_reabrir_ni_anular() {
        val e = estado(consulta(EstadoConsulta.CERRADA), sucia = false)

        assertTrue(e.cierreEnviado)
        assertFalse(e.puedeReabrir)
        assertFalse(e.puedeAnular)
        assertNull(e.marca, "ya está al día: no hay marca")
    }

    @Test
    fun mientras_el_cierre_viaja_no_se_puede_reabrir_ni_anular_y_la_marca_dice_enviando_el_cierre() {
        val e = estado(consulta(EstadoConsulta.CERRADA), sucia = true, envio = EstadoEnvio.Pendiente, viajando = true)

        assertFalse(e.puedeReabrir)
        assertFalse(e.puedeAnular)
        assertTrue(e.enviandoElCierre)
        assertEquals("Enviando el cierre.", e.marca)
        assertEquals(TEXTO_ENVIANDO_EL_CIERRE, e.marca)
    }

    @Test
    fun una_anulada_no_se_reabre_ni_se_vuelve_a_anular() {
        val e = estado(consulta(EstadoConsulta.ANULADA), sucia = true)

        assertFalse(e.puedeReabrir)
        assertFalse(e.puedeAnular)
    }

    // ---- La marca de la tarjeta ----

    @Test
    fun la_marca_es_falta_enviar_en_espera_o_no_se_pudo_enviar_con_su_motivo() {
        val c = consulta(EstadoConsulta.CERRADA)

        assertEquals("Falta enviar", estado(c, sucia = true, envio = EstadoEnvio.Pendiente).marca)
        assertEquals(
            "En espera: Historia clínica de X: sin conexión",
            estado(c, sucia = true, envio = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "Historia clínica de X: sin conexión")).marca,
        )
        assertEquals(
            "No se pudo enviar: Historia clínica de X: no la acepta",
            estado(c, sucia = true, envio = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "Historia clínica de X: no la acepta")).marca,
        )
    }

    @Test
    fun sin_nada_pendiente_no_hay_marca() {
        assertNull(estado(consulta(EstadoConsulta.ABIERTA)).marca)
    }

    @Test
    fun enviando_el_cierre_manda_sobre_cualquier_otra_marca() {
        val espera = EstadoEnvio.EnEspera(CausaEnEspera.SERVIDOR, "motivo")

        assertEquals("Enviando el cierre.", estado(consulta(EstadoConsulta.CERRADA), sucia = true, envio = espera, viajando = true).marca)
    }
}
