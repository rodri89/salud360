package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 005, T36 (RF-2, RF-27, RF-62, D1, D2): a quién avisa el autoguardado de una consulta que cambió. La HC general avisa a su cola
 * (que decide qué entra), las demás a su motor de siempre, y lo de la web a nadie. Puro; datos inventados.
 */
class AvisoDeCambiosTest {
    private fun consulta(
        id: String,
        especialidad: String = "clinica",
        estado: EstadoConsulta = EstadoConsulta.ABIERTA,
        remotoId: String = "",
        propia: Boolean = false,
    ) = Consulta(id, "p1", "m1", especialidad, "control", LocalDate.parse("2026-11-06"), estado, remotoId = remotoId, propia = propia)

    @Test
    fun una_consulta_propia_de_la_hc_general_avisa_a_la_cola_sin_enviar_y_ya_enviada() {
        assertEquals(AvisoDeCambios.COLA_DE_LA_HC_GENERAL, avisoDeCambios(consulta("u1", propia = true), deLaWeb = false))
        // Ya enviada (RF-62): sigue siendo propia, editable, y avisa igual.
        assertEquals(AvisoDeCambios.COLA_DE_LA_HC_GENERAL, avisoDeCambios(consulta("u1", remotoId = "7001", propia = true), deLaWeb = false))
    }

    @Test
    fun una_consulta_del_historico_de_la_hc_general_tambien_avisa_a_la_cola_que_la_ignora() {
        assertEquals(AvisoDeCambios.COLA_DE_LA_HC_GENERAL, avisoDeCambios(consulta("h1"), deLaWeb = false))
    }

    @Test
    fun una_consulta_de_la_web_no_avisa_a_nadie() {
        assertEquals(AvisoDeCambios.NINGUNO, avisoDeCambios(consulta(idConsultaWeb(7003), remotoId = "7003"), deLaWeb = true))
        assertEquals(AvisoDeCambios.NINGUNO, avisoDeCambios(consulta("u1", remotoId = "7003"), deLaWeb = true))
    }

    @Test
    fun pediatria_y_las_demas_siguen_con_su_motor() {
        for (especialidad in listOf("pediatria", "ginecologia", "cardiologia", "endocrinologia", "hematologia")) {
            assertEquals(AvisoDeCambios.MOTOR_DE_LA_ESPECIALIDAD, avisoDeCambios(consulta("u1", especialidad), deLaWeb = false), especialidad)
            // Con id en la API (ya enviada) siguen igual.
            assertEquals(AvisoDeCambios.MOTOR_DE_LA_ESPECIALIDAD, avisoDeCambios(consulta("u1", especialidad, remotoId = "88"), deLaWeb = false), especialidad)
        }
    }
}