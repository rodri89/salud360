package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CampoExamenFisico
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
 * Spec 005, T37 (RF-3, RF-4, RF-24, RF-25, RF-26, RF-30, RF-55): lo que la pantalla de una consulta propia de la HC general dice y ofrece
 * según su estado de envío, y los avisos de lo que no se envía. Puro; la pantalla (Compose) dibuja lo que esto decide. Datos inventados.
 */
class EstadoEnPantallaTest {
    private val dia = LocalDate.parse("2026-11-06")

    private fun consulta(
        id: String = "u1", especialidad: String = "clinica", estado: EstadoConsulta = EstadoConsulta.ABIERTA, remotoId: String = "", propia: Boolean = true,
    ) = Consulta(id, "p1", "m1", especialidad, "control", dia, estado, remotoId = remotoId, propia = propia)

    private fun estado(
        envio: EstadoEnvio? = null,
        enviandoElCierre: Boolean = false,
        cierreEnviado: Boolean = false,
        puedeReabrir: Boolean = false,
        puedeAnular: Boolean = true,
        descartadas: Set<String> = emptySet(),
    ) = EstadoDeLaConsulta(envio, enviandoElCierre, cierreEnviado, puedeReabrir, puedeAnular, descartadas)

    private val enEspera = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "Sin conexión con la historia clínica de Clínica general")
    private val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "la web no acepta lo que escribiste en Nota")

    // ---- Las franjas de estado ----

    @Test
    fun sin_estado_o_sin_nada_pendiente_no_hay_franja() {
        assertEquals(emptyList(), bannersDeEnvio(null), "el histórico, lo de la web y las demás historias no tienen estado")
        assertEquals(emptyList(), bannersDeEnvio(estado()), "nada que mandar")
        assertEquals(emptyList(), bannersDeEnvio(estado(cierreEnviado = true)), "al enviarse el cierre la marca desaparece (RF-24)")
    }

    @Test
    fun falta_enviar_es_una_franja_sin_acciones() {
        val b = bannersDeEnvio(estado(EstadoEnvio.Pendiente)).single()
        assertEquals(TipoDeBanner.FALTA_ENVIAR, b.tipo)
        assertEquals("Falta enviar", b.texto)
        assertFalse(b.puedeReintentar)
        assertFalse(b.puedeDescartar)
    }

    @Test
    fun en_espera_dice_el_motivo_y_deja_reintentar_pero_no_descartar() {
        val b = bannersDeEnvio(estado(enEspera)).single()
        assertEquals(TipoDeBanner.EN_ESPERA, b.tipo)
        assertEquals("En espera: Sin conexión con la historia clínica de Clínica general", b.texto)
        assertTrue(b.puedeReintentar, "RF-6: se reintenta sola y a mano si el médico lo pide")
        assertFalse(b.puedeDescartar, "una espera no se descarta: se perdería lo que falta mandar")
    }

    @Test
    fun no_se_pudo_enviar_dice_el_motivo_y_ofrece_reintentar_y_descartar() {
        val b = bannersDeEnvio(estado(rechazo)).single()
        assertEquals(TipoDeBanner.NO_SE_PUDO_ENVIAR, b.tipo)
        assertEquals("No se pudo enviar: la web no acepta lo que escribiste en Nota", b.texto)
        assertTrue(b.puedeReintentar)
        assertTrue(b.puedeDescartar)
    }

    @Test
    fun mientras_el_cierre_viaja_solo_se_dice_enviando_el_cierre_sin_acciones() {
        val b = bannersDeEnvio(estado(EstadoEnvio.Pendiente, enviandoElCierre = true, descartadas = setOf("secciones"))).single()
        assertEquals(TipoDeBanner.ENVIANDO_EL_CIERRE, b.tipo)
        assertEquals("Enviando el cierre.", b.texto)
        assertFalse(b.puedeReintentar)
        assertFalse(b.puedeDescartar)
    }

    @Test
    fun lo_descartado_se_dice_aparte_y_despues_de_lo_pendiente() {
        val solo = bannersDeEnvio(estado(descartadas = setOf("secciones"))).single()
        assertEquals(TipoDeBanner.DESCARTADO, solo.tipo)
        assertEquals("Descartado: no se envió a la web", solo.texto)
        assertFalse(solo.puedeReintentar)
        assertFalse(solo.puedeDescartar)

        val junto = bannersDeEnvio(estado(rechazo, descartadas = setOf("examen")))
        assertEquals(listOf(TipoDeBanner.NO_SE_PUDO_ENVIAR, TipoDeBanner.DESCARTADO), junto.map { it.tipo })
    }

    @Test
    fun los_textos_son_los_de_la_tabla_de_la_spec() {
        assertEquals("Falta enviar", TEXTO_FALTA_ENVIAR)
        assertEquals("Descartado: no se envió a la web", TEXTO_DESCARTADO)
        assertEquals("Enviando el cierre.", TEXTO_ENVIANDO_EL_CIERRE)
        assertEquals("No se envía a la web", AVISO_NO_SE_ENVIA)
        assertEquals("Esta sección todavía no se envía a la web", AVISO_TODAVIA_NO_SE_ENVIA)
        assertEquals("Descartar", TEXTO_DESCARTAR)
    }

    // ---- Anular, con la confirmación ----

    @Test
    fun anular_una_que_nunca_se_creo_solo_avisa_lo_basico() {
        val c = confirmacionDeAnular(consulta(), estado())
        assertEquals(listOf(AVISO_ANULAR_BASE), c.avisos)
        assertEquals(TEXTO_ANULAR_CONSULTA, c.titulo)
        assertEquals(TEXTO_CONFIRMAR_ANULAR, c.textoConfirmar)
        assertEquals(AVISO_ANULAR_BASE, c.mensaje)
    }

    @Test
    fun anular_una_ya_creada_avisa_que_se_da_de_baja_en_la_web_y_que_los_datos_del_paciente_no_se_borran() {
        val c = confirmacionDeAnular(consulta(remotoId = "7001"), estado())
        assertEquals(listOf(AVISO_ANULAR_BASE, AVISO_ANULAR_YA_EN_LA_WEB, AVISO_ANULAR_DATOS_DEL_PACIENTE), c.avisos)
    }

    @Test
    fun anular_una_cerrada_cuyo_cierre_no_salio_avisa_que_se_pierde_ese_cierre() {
        val c = confirmacionDeAnular(consulta(estado = EstadoConsulta.CERRADA, remotoId = "7001"), estado(EstadoEnvio.Pendiente))
        assertTrue(AVISO_ANULAR_CIERRE_PENDIENTE in c.avisos, "T28 decisión (f)")
        assertTrue(AVISO_ANULAR_PENDIENTE in c.avisos)
        // Una abierta no tiene cierre que perder.
        assertFalse(AVISO_ANULAR_CIERRE_PENDIENTE in confirmacionDeAnular(consulta(remotoId = "7001"), estado(EstadoEnvio.Pendiente)).avisos)
    }

    @Test
    fun anular_con_algo_pendiente_avisa_que_no_se_manda_pero_queda_en_el_dispositivo() {
        val c = confirmacionDeAnular(consulta(), estado(enEspera))
        assertEquals(listOf(AVISO_ANULAR_BASE, AVISO_ANULAR_PENDIENTE), c.avisos)
    }

    // ---- Qué consulta se envía y qué avisos lleva cada parte ----

    @Test
    fun solo_una_propia_de_la_hc_general_se_envia_a_la_web() {
        assertTrue(consulta().seEnviaALaWeb())
        assertTrue(consulta(remotoId = "7001").seEnviaALaWeb(), "ya enviada, sigue siendo propia (RF-62)")
        assertFalse(consulta(propia = false).seEnviaALaWeb(), "el histórico: «Solo en este dispositivo», nada sale solo")
        assertFalse(consulta(id = idConsultaWeb(7003), remotoId = "7003", propia = false).seEnviaALaWeb(), "lo de la web solo se lee")
        assertFalse(consulta(especialidad = "pediatria").seEnviaALaWeb(), "pediatría no cambia")
    }

    @Test
    fun una_seccion_sin_envio_dice_por_que_solo_si_la_consulta_se_envia() {
        // En esta versión se envían los textos, el examen y las enfermedades (RF-4); el resto «todavía no».
        assertNull(avisoDeEnvioDeLaSeccion("nota", seEnvia = true))
        assertNull(avisoDeEnvioDeLaSeccion("examen_fisico", seEnvia = true))
        assertNull(avisoDeEnvioDeLaSeccion("enfermedades", seEnvia = true))
        assertEquals(AVISO_TODAVIA_NO_SE_ENVIA, avisoDeEnvioDeLaSeccion("laboratorio_general", seEnvia = true))
        assertEquals(AVISO_TODAVIA_NO_SE_ENVIA, avisoDeEnvioDeLaSeccion("veda", seEnvia = true))
        // Lo que la web no guarda nunca (RF-3).
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeEnvioDeLaSeccion("diagnostico", seEnvia = true))
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeEnvioDeLaSeccion("audio", seEnvia = true))
        // Una consulta que no se envía (histórico, pediatría, la web) se ve como siempre.
        for (id in listOf("nota", "laboratorio_general", "diagnostico")) assertNull(avisoDeEnvioDeLaSeccion(id, seEnvia = false), id)
    }

    @Test
    fun la_casilla_y_el_campo_que_la_web_no_guarda_dicen_no_se_envia_a_la_web() {
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeEnvioDeLaCasilla(sinEnvio = true, seEnvia = true), "tabaquismo")
        assertNull(avisoDeEnvioDeLaCasilla(sinEnvio = false, seEnvia = true))
        assertNull(avisoDeEnvioDeLaCasilla(sinEnvio = true, seEnvia = false))
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeEnvioDelCampoDelExamen(CampoExamenFisico.FRECUENCIA_CARDIACA, seEnvia = true))
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeEnvioDelCampoDelExamen(CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL, seEnvia = true))
        assertNull(avisoDeEnvioDelCampoDelExamen(CampoExamenFisico.PESO, seEnvia = true))
        assertNull(avisoDeEnvioDelCampoDelExamen(CampoExamenFisico.FRECUENCIA_CARDIACA, seEnvia = false))
    }
}