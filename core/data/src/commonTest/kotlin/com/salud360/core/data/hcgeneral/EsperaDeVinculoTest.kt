package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteCandidato
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T26 (RF-6, RF-13, RF-19): en qué estado queda la creación de una consulta cuando no se pudo vincular al paciente con su
 * ficha de la web. Puro: del resultado de `PacientesHcRepository.vinculo` (spec 002/003) al estado «En espera: <motivo>», con el motivo
 * en palabras del usuario **nombrando la HC**. Si quedó vinculado no hay estado: se envía.
 */
class EsperaDeVinculoTest {
    private val hc = "Clínica general"

    private fun enEspera(resultado: ResultadoVinculo): EstadoEnvio.EnEspera = assertIs<EstadoEnvio.EnEspera>(estadoDeVinculo(resultado, hc))

    @Test
    fun si_quedo_vinculado_no_hay_estado_y_se_envia() {
        assertNull(estadoDeVinculo(ResultadoVinculo.Vinculado(5001), hc))
    }

    @Test
    fun hay_que_elegir_ficha() {
        val e = enEspera(ResultadoVinculo.ElegirFicha(listOf(HcPacienteCandidato(1), HcPacienteCandidato(2))))

        assertEquals(CausaEnEspera.ELEGIR_FICHA, e.causa)
        assertTrue(e.motivo.startsWith("Historia clínica de $hc: "), e.motivo)
        assertTrue("elegí" in e.motivo, "dice qué tiene que hacer el médico: ${e.motivo}")
    }

    @Test
    fun el_documento_no_sirve_y_dice_cual() {
        val mensaje = mensajeDocumentoInvalido("12")

        val e = enEspera(ResultadoVinculo.DocumentoInvalido(mensaje))

        assertEquals(CausaEnEspera.DOCUMENTO_INVALIDO, e.causa)
        assertEquals("Historia clínica de $hc: el documento «12» no sirve para la historia clínica: corregilo en la ficha del paciente", e.motivo)
    }

    @Test
    fun un_documento_invalido_sin_mensaje_del_servidor_igual_tiene_un_motivo_legible() {
        val e = enEspera(ResultadoVinculo.DocumentoInvalido(""))

        assertEquals(CausaEnEspera.DOCUMENTO_INVALIDO, e.causa)
        assertTrue("documento" in e.motivo && e.motivo.startsWith("Historia clínica de $hc: "), e.motivo)
    }

    @Test
    fun la_ficha_es_de_otro_paciente_de_turnos() {
        val e = enEspera(
            ResultadoVinculo.FichaDeOtroPaciente(
                "La ficha de la web con ese documento ya corresponde a otro paciente de turnosonlinebb: avisale al administrador",
            ),
        )

        assertEquals(CausaEnEspera.FICHA_DE_OTRO_PACIENTE, e.causa)
        assertEquals(
            "Historia clínica de $hc: la ficha de la web con ese documento ya corresponde a otro paciente de turnosonlinebb: avisale al administrador",
            e.motivo,
        )
    }

    @Test
    fun una_ficha_elegida_que_ya_no_es_posible_se_vuelve_a_elegir() {
        assertEquals(CausaEnEspera.ELEGIR_FICHA, enEspera(ResultadoVinculo.FichaNoElegible).causa)
    }

    @Test
    fun una_causa_ajena_al_vincular_se_clasifica_como_cualquier_otra_del_envio() {
        val sinRed = enEspera(ResultadoVinculo.NoSePudo(TobbException(0, "red", "Sin conexión")))
        val sesion = enEspera(ResultadoVinculo.NoSePudo(TobbException(401, "token", "Sesión vencida")))
        val noHabilitada = enEspera(ResultadoVinculo.NoSePudo(TobbException(403, "hc_no_habilitada", "No habilitada")))

        assertEquals(CausaEnEspera.SIN_CONEXION, sinRed.causa)
        assertEquals(CausaEnEspera.SESION_VENCIDA, sesion.causa)
        assertEquals(CausaEnEspera.HC_NO_HABILITADA, noHabilitada.causa)
        assertTrue(listOf(sinRed, sesion, noHabilitada).all { it.motivo.contains(hc) })
    }

    @Test
    fun un_rechazo_de_la_web_al_vincular_sigue_siendo_un_rechazo() {
        val e = estadoDeVinculo(ResultadoVinculo.NoSePudo(TobbException(422, "datos_invalidos", "No sirve")), hc)

        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, assertIs<EstadoEnvio.NoSePudoEnviar>(e).causa)
    }

    @Test
    fun las_causas_nuevas_se_guardan_y_se_leen_como_cualquier_estado() {
        for (causa in listOf(CausaEnEspera.ELEGIR_FICHA, CausaEnEspera.DOCUMENTO_INVALIDO, CausaEnEspera.FICHA_DE_OTRO_PACIENTE)) {
            val estado = EstadoEnvio.EnEspera(causa, "Historia clínica de $hc: motivo | con barra")
            val texto = estadoDeEnvioComoTexto(estado, kotlinx.datetime.LocalDate.parse("2026-11-06"))

            assertEquals(estado, estadoDeEnvioDeTexto(texto)?.estado, causa.name)
        }
    }
}
