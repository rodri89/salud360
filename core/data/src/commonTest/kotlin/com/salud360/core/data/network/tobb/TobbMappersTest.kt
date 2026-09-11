package com.salud360.core.data.network.tobb

import com.salud360.core.model.TobbIds
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.EstadoTurno
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TobbMappersTest {
    private val turnoJson = """
        {"ok":true,"turnos":[
          {"id":123,"paciente_id":45,"medico_id":3,"consultorio_id":1,"dia":2,"horario":"9:20","fecha":"2026-09-08",
           "asistio":1,"sobreturno":0,"primer_control":true,"caja":1500,"comentario":"c","tipo_turno":1,"especialidad":null,
           "otorgado_por":"sec@mail.com","cancelado_por":null,"activo":1,"pago":0,"pago_estado":null,"importe_reserva":"250.50",
           "paciente":{"id":45,"nombre":"Ana","apellido":"Perez","dni":30111222,"telefono":"291555","mail":"a@b.c","obra_social":"OSDE","numero_afiliado":77},
           "created_at":"2026-09-01 10:00:00","updated_at":"2026-09-01 10:00:00"},
          {"id":124,"paciente_id":9,"medico_id":3,"consultorio_id":1,"dia":2,"horario":"09:40","fecha":"2026/09/08",
           "asistio":0,"sobreturno":0,"primer_control":false,"caja":0,"comentario":"","tipo_turno":1,"activo":1,
           "paciente":{"id":9,"nombre":"BLOQUEO","apellido":"BLOQUEO","dni":"99999"}},
          {"id":125,"paciente_id":45,"medico_id":3,"consultorio_id":1,"dia":2,"horario":"10:00","fecha":"2026-09-08",
           "asistio":0,"sobreturno":1,"primer_control":false,"caja":0,"comentario":"","tipo_turno":1,"activo":0,
           "paciente":{"id":45,"nombre":"Ana","apellido":"Perez","dni":30111222}}
        ],
        "slots":[{"horario":"09:20","libre":0},{"horario":"09:40","libre":0},{"horario":"10:00","libre":1},{"horario":"10:20","libre":1}],
        "es_feriado":false,"cantidad_sobreturnos":1,"modulo_caja_comentario":1,"fecha":"2026-09-08","dia":2}
    """.trimIndent()

    private fun agenda(): TobbAgendaDia = tobbJson.decodeFromJsonElement(TobbAgendaDia.serializer(), tobbJson.parseToJsonElement(turnoJson).jsonObject)

    @Test
    fun convierteTurnoConPacienteNumericoYImportesEnTexto() {
        val t = agenda().turnos[0].toTurno()!!
        assertEquals(TobbIds.turno(123), t.id)
        assertEquals(TobbIds.paciente(45), t.pacienteId)
        assertEquals(TobbIds.medico(3), t.medicoId)
        assertEquals(TobbIds.consultorio(1), t.consultorioId)
        assertEquals(LocalDate(2026, 9, 8), t.fecha)
        assertEquals(LocalTime(9, 20), t.horario)
        assertEquals(EstadoTurno.ACTIVO, t.estado)
        assertEquals(Asistencia.ASISTIO, t.asistencia)
        assertTrue(t.primerControl)
        assertEquals(1500.0, t.caja)
        assertEquals(250.5, t.importeReserva)
        assertEquals("Perez, Ana", t.pacienteNombre)
        assertEquals("30111222", t.pacienteDni)
        assertEquals("OSDE", t.pacienteObraSocial)
    }

    @Test
    fun elPacienteDeBloqueoSeConvierteEnHorarioBloqueadoSinPaciente() {
        val t = agenda().turnos[1].toTurno()!!
        assertEquals(EstadoTurno.BLOQUEADO, t.estado)
        assertNull(t.pacienteId)
        assertEquals(LocalDate(2026, 9, 8), t.fecha)
    }

    @Test
    fun activoCeroEsCancelado() {
        val t = agenda().turnos[2].toTurno()!!
        assertEquals(EstadoTurno.CANCELADO, t.estado)
        assertTrue(t.sobreturno)
    }

    @Test
    fun losSlotsSeCruzanConLosTurnosDelDia() {
        val a = agenda()
        val turnos = a.turnos.mapNotNull { it.toTurno() }
        val slots = a.slots.toSlots(turnos, dobles = true)
        assertEquals(listOf(LocalTime(9, 20), LocalTime(9, 40), LocalTime(10, 0), LocalTime(10, 20)), slots.map { it.horario })
        assertFalse(slots[0].libre); assertEquals(TobbIds.turno(123), slots[0].turno?.id)
        assertFalse(slots[1].libre); assertEquals(EstadoTurno.BLOQUEADO, slots[1].turno?.estado)
        // el turno cancelado de las 10:00 no ocupa el horario
        assertTrue(slots[2].libre); assertNull(slots[2].turno)
        assertTrue(slots[2].doble)      // 10:00 y 10:20 libres → admite primer control doble
        assertFalse(slots[3].doble)     // último horario: no hay siguiente
    }

    @Test
    fun elPacienteCompletoNormalizaDniYFechas() {
        val json = """{"id":7,"nombre":"Luis","apellido":"Gomez","dni":12345678,"telefono":291,"domicilio":null,"localidad":"BB","mail":"l@g.com",
            "fecha_nacimiento":"1990-05-03","obra_social":"IOMA","numero_afiliado":"A1","obra_social_plan":null,"afiliado_obligatorio":1,"activo":2}"""
        val p = tobbJson.decodeFromJsonElement(TobbPaciente.serializer(), tobbJson.parseToJsonElement(json).jsonObject).toPaciente()
        assertEquals(TobbIds.paciente(7), p.id)
        assertEquals("12345678", p.dni)
        assertEquals("291", p.telefono)
        assertEquals(LocalDate(1990, 5, 3), p.fechaNacimiento)
        assertTrue(p.afiliadoObligatorio)
        assertFalse(p.activo) // activo = 2: pendiente de activación
    }

    @Test
    fun elCuerpoDeAltaOmiteLosCamposSinValor() {
        val obj = tobbJson.encodeToJsonElement(TobbNuevoPaciente.serializer(), TobbNuevoPaciente(medicoId = 3, dni = "1", nombre = "A", apellido = "B")).jsonObject
        assertEquals(setOf("medico_id", "dni", "nombre", "apellido"), obj.keys)
    }
}
