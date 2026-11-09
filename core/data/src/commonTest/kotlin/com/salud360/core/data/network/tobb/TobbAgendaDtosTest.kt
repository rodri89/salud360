package com.salud360.core.data.network.tobb

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Los DTO de la agenda por médico (spec 005): el pedido de `PUT medicos/{id}/agenda`, su respuesta y los
 * campos nuevos de `GET catalogos`. Un nombre de campo equivocado no rompe nada: llega como «no informa».
 */
class TobbAgendaDtosTest {

    private fun cuerpoDe(pedido: TobbAgendaRequest) =
        tobbJson.encodeToJsonElement(TobbAgendaRequest.serializer(), pedido).jsonObject

    private fun respuesta(json: String) = tobbJson.decodeFromString(TobbAgendaResponse.serializer(), json)

    private fun catalogo(json: String) = tobbJson.decodeFromString(TobbMedicoCatalogo.serializer(), json)

    @Test
    fun elPedidoViajaSoloConLoQueSePidio() {
        val soloAgenda = cuerpoDe(TobbAgendaRequest(tieneAgenda = false))
        assertEquals(setOf("tiene_agenda"), soloAgenda.keys, "no se manda el campo que el administrador no tocó")
        assertEquals("false", soloAgenda["tiene_agenda"]?.jsonPrimitive?.content, "false es un pedido: tiene que viajar")

        val soloVisible = cuerpoDe(TobbAgendaRequest(visibleEnTurnos = true))
        assertEquals(setOf("visible_en_turnos"), soloVisible.keys)
        assertEquals("true", soloVisible["visible_en_turnos"]?.jsonPrimitive?.content)

        val ambos = cuerpoDe(TobbAgendaRequest(tieneAgenda = true, visibleEnTurnos = false))
        assertEquals(setOf("tiene_agenda", "visible_en_turnos"), ambos.keys)
        assertEquals("false", ambos["visible_en_turnos"]?.jsonPrimitive?.content)
    }

    @Test
    fun laRespuestaDecodificaElEjemploDelBrief() {
        val r = respuesta("""{"medico_id":12,"tiene_agenda":false,"visible_en_turnos":false,"turnos_futuros":3}""")
        assertEquals(12L, r.medicoId)
        assertEquals(false, r.tieneAgenda)
        assertEquals(false, r.visibleEnTurnos)
        assertEquals(3, r.turnosFuturos)
    }

    @Test
    fun laRespuestaSinMedicoNiTurnosFuturosCaeEnCero() {
        val r = respuesta("""{"tiene_agenda":true,"visible_en_turnos":true}""")
        assertEquals(0L, r.medicoId)
        assertEquals(0, r.turnosFuturos)
        assertEquals(true, r.tieneAgenda)
        assertEquals(true, r.visibleEnTurnos)
    }

    @Test
    fun laRespuestaSinLosBooleanosFalla() {
        // Un 200 que no dice en qué quedó la agenda no confirma nada: no se toma por buena.
        assertFailsWith<SerializationException> { respuesta("""{"medico_id":12,"visible_en_turnos":false,"turnos_futuros":0}""") }
        assertFailsWith<SerializationException> { respuesta("""{"medico_id":12,"tiene_agenda":true,"turnos_futuros":0}""") }
        assertFailsWith<SerializationException> { respuesta("""{}""") }
    }

    @Test
    fun elCatalogoTomaLosCamposNuevosCuandoVienen() {
        val m = catalogo("""{"id":3,"nombre":"Ana","apellido":"Vera","tiene_horarios":1,"tiene_agenda":0,"visible_en_turnos":1}""")
        assertEquals(0, m.tieneAgenda)
        assertEquals(1, m.visibleEnTurnos)
        assertEquals(1, m.tieneHorarios, "tiene_horarios se sigue leyendo: es el respaldo")
    }

    @Test
    fun elCatalogoSinLosCamposNuevosLosDejaEnNull() {
        val m = catalogo("""{"id":3,"nombre":"Ana","apellido":"Vera","tiene_horarios":0}""")
        assertNull(m.tieneAgenda, "null = el servidor no lo informa (distinto de 0)")
        assertNull(m.visibleEnTurnos)
        assertEquals(0, m.tieneHorarios)
        assertNull(m.historiasClinicas, "null = el servidor no informa las historias clínicas (distinto de [])")
    }

    @Test
    fun elCatalogoDistingueLasHistoriasClinicasVaciasDeLasAusentes() {
        assertEquals(emptyList(), catalogo("""{"id":3,"nombre":"Ana","apellido":"Vera","historias_clinicas":[]}""").historiasClinicas)
        assertEquals(listOf("pediatria"), catalogo("""{"id":3,"nombre":"Ana","apellido":"Vera","historias_clinicas":["pediatria"]}""").historiasClinicas)
        assertNull(catalogo("""{"id":3,"nombre":"Ana","apellido":"Vera","historias_clinicas":null}""").historiasClinicas)
    }
}
