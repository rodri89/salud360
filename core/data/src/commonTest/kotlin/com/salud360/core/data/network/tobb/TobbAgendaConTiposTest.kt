package com.salud360.core.data.network.tobb

import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.model.TobbIds
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La agenda con todos los tipos de turno: los campos `tipo_turno` y `tipo_turno_nombre` de los horarios, los turnos y los
 * horarios fijos se leen sin perder el código real, y un servidor que todavía no los manda sigue funcionando.
 * Datos inventados.
 */
class TobbAgendaConTiposTest {

    private fun dia(json: String): TobbAgendaDia =
        tobbJson.decodeFromJsonElement(TobbAgendaDia.serializer(), tobbJson.parseToJsonElement(json).jsonObject)

    private val diaNuevo = """
        {"ok":true,"fecha":"2026-11-10","dia":2,"es_feriado":false,
         "slots":[
           {"horario":"09:00","libre":1,"tipo_turno":1,"tipo_turno_nombre":"Consulta"},
           {"horario":"09:30","libre":1,"tipo_turno":24,"tipo_turno_nombre":"Deportología"},
           {"horario":"10:00","libre":0,"tipo_turno":26,"tipo_turno_nombre":"Control"},
           {"horario":"10:30","libre":1,"tipo_turno":27}
         ],
         "turnos":[
           {"id":501,"paciente_id":7,"medico_id":3,"consultorio_id":1,"horario":"10:00","fecha":"2026-11-10","tipo_turno":26,"tipo_turno_nombre":"Control","activo":1,
            "paciente":{"id":7,"nombre":"Ana","apellido":"Prueba","dni":1000001}}
         ]}
    """.trimIndent()

    private val diaViejo = """
        {"ok":true,"fecha":"2026-11-10","dia":2,"es_feriado":false,
         "slots":[{"horario":"09:00","libre":1},{"horario":"09:30","libre":0}],
         "turnos":[{"id":501,"paciente_id":7,"medico_id":3,"consultorio_id":1,"horario":"09:30","fecha":"2026-11-10","tipo_turno":1,"activo":1}]}
    """.trimIndent()

    // ---- DTOs ----

    @Test
    fun el_slot_trae_el_tipo_y_su_nombre() {
        val slots = dia(diaNuevo).slots
        assertEquals(listOf(1, 24, 26, 27), slots.map { it.tipoTurno })
        assertEquals(listOf("Consulta", "Deportología", "Control", null), slots.map { it.tipoTurnoNombre })
    }

    @Test
    fun un_servidor_viejo_que_no_manda_los_campos_nuevos_se_lee_como_tipo_1_sin_nombre() {
        val d = dia(diaViejo)
        assertEquals(listOf(1, 1), d.slots.map { it.tipoTurno })
        assertEquals(listOf<String?>(null, null), d.slots.map { it.tipoTurnoNombre })
        assertNull(d.turnos.single().tipoTurnoNombre)
    }

    @Test
    fun un_nombre_o_un_tipo_en_null_se_leen_como_si_no_vinieran() {
        val slot = tobbJson.decodeFromString(TobbSlot.serializer(), """{"horario":"09:00","libre":1,"tipo_turno":null,"tipo_turno_nombre":null}""")
        assertEquals(1, slot.tipoTurno)
        assertNull(slot.tipoTurnoNombre)
    }

    @Test
    fun el_horario_fijo_acepta_el_nombre_del_tipo_y_tambien_que_no_venga() {
        val con = tobbJson.decodeFromString(TobbHorarioFijo.serializer(), """{"id":1,"dia":2,"horario":"09:00","tipo_turno":26,"tipo_turno_nombre":"Control"}""")
        val sin = tobbJson.decodeFromString(TobbHorarioFijo.serializer(), """{"id":2,"dia":2,"horario":"09:30","tipo_turno":26}""")
        assertEquals("Control", con.tipoTurnoNombre)
        assertNull(sin.tipoTurnoNombre)
        assertEquals(26, sin.tipoTurno)
    }

    // ---- mapeo al modelo ----

    @Test
    fun los_slots_conservan_el_codigo_real_del_tipo() {
        val d = dia(diaNuevo)
        val slots = d.slots.toSlots(d.turnos.mapNotNull { it.toTurno() }, dobles = false)
        assertEquals(listOf(1, 24, 26, 27), slots.map { it.codigoDeTipo })
        assertEquals(TipoTurno.DEPORTOLOGIA, slots[1].tipoTurno)
        assertEquals(TipoTurno.CONSULTA, slots[3].tipoTurno, "el enum cerrado cae en Consulta con un código desconocido…")
        assertEquals(27, slots[3].codigoDeTipo, "…pero el código real no se pierde")
        assertEquals(listOf(true, true, false, true), slots.map { it.libre })
    }

    @Test
    fun el_turno_conserva_el_codigo_real_aunque_el_enum_no_lo_conozca() {
        val t = dia(diaNuevo).turnos.single().toTurno()!!
        assertEquals(26, t.codigoDeTipo)
        assertEquals(TipoTurno.CONSULTA, t.tipoTurno)
    }

    @Test
    fun un_turno_de_un_tipo_conocido_no_guarda_codigo_aparte() {
        val d = dia("""{"fecha":"2026-11-10","turnos":[{"id":9,"medico_id":3,"consultorio_id":1,"horario":"09:00","fecha":"2026-11-10","tipo_turno":24}]}""")
        val t = d.turnos.single().toTurno()!!
        assertEquals(TipoTurno.DEPORTOLOGIA, t.tipoTurno)
        assertNull(t.tipoCodigo)
        assertEquals(24, t.codigoDeTipo)
    }

    @Test
    fun el_horario_fijo_conserva_el_codigo_real() {
        val h = tobbJson.decodeFromString(TobbHorarioFijo.serializer(), """{"id":4,"dia":3,"horario":"08:00","tipo_turno":26}""").toHorario("tobb-m3", "tobb-c1")!!
        assertEquals(TobbIds.horario(4), h.id)
        assertEquals(26, h.codigoDeTipo)
        val conocido = tobbJson.decodeFromString(TobbHorarioFijo.serializer(), """{"id":5,"dia":3,"horario":"08:30","tipo_turno":23}""").toHorario("tobb-m3", "tobb-c1")!!
        assertEquals(TipoTurno.ECOGRAFIA, conocido.tipoTurno)
        assertEquals(23, conocido.codigoDeTipo)
    }

    // ---- nombres de los tipos ----

    @Test
    fun los_nombres_se_juntan_de_los_horarios_y_los_turnos_del_dia() {
        assertEquals(mapOf(1 to "Consulta", 24 to "Deportología", 26 to "Control"), dia(diaNuevo).nombresDeTipos())
    }

    @Test
    fun un_dia_de_un_servidor_viejo_no_aporta_nombres() {
        assertEquals(emptyMap(), dia(diaViejo).nombresDeTipos())
    }

    @Test
    fun los_nombres_de_los_horarios_fijos_tambien_se_aprenden() {
        val r = tobbJson.decodeFromString(
            TobbHorariosResponse.serializer(),
            """{"horarios_fijos":[{"id":1,"dia":2,"horario":"09:00","tipo_turno":28,"tipo_turno_nombre":"Eco"},{"id":2,"dia":2,"horario":"09:30","tipo_turno":28}]}""",
        )
        assertEquals(mapOf(28 to "Eco"), r.nombresDeTipos())
    }

    @Test
    fun un_nombre_en_blanco_no_cuenta() {
        val d = dia("""{"fecha":"2026-11-10","slots":[{"horario":"09:00","libre":1,"tipo_turno":26,"tipo_turno_nombre":"  "}]}""")
        assertTrue(d.nombresDeTipos().isEmpty())
    }

    // ---- ida y vuelta por la base local (el esquema no cambia: `tipo_turno` es un entero) ----

    @Test
    fun el_turno_de_un_tipo_desconocido_vuelve_de_la_base_con_su_codigo() {
        val t = Turno("t1", "p1", "m1", "c1", LocalDate(2026, 11, 10), LocalTime(9, 0), tipoCodigo = 26)
        val fila = t.toRow(updatedAt = 1)
        assertEquals(26L, fila.tipo_turno)
        assertEquals(26, fila.toModel().codigoDeTipo)
        assertEquals(t, fila.toModel())
    }

    @Test
    fun el_turno_de_siempre_vuelve_igual_de_la_base() {
        val t = Turno("t1", "p1", "m1", "c1", LocalDate(2026, 11, 10), LocalTime(9, 0))
        assertEquals(1L, t.toRow(updatedAt = 1).tipo_turno)
        assertEquals(t, t.toRow(updatedAt = 1).toModel())
        val eco = t.copy(tipoTurno = TipoTurno.ECOGRAFIA)
        assertEquals(23L, eco.toRow(updatedAt = 1).tipo_turno)
        assertEquals(eco, eco.toRow(updatedAt = 1).toModel())
    }

    @Test
    fun el_horario_de_un_tipo_desconocido_vuelve_de_la_base_con_su_codigo() {
        val h = HorarioMedico("h1", "m1", "c1", DayOfWeek.TUESDAY, LocalTime(9, 0), tipoCodigo = 26)
        val fila = h.toRow(updatedAt = 1)
        assertEquals(26L, fila.tipo_turno)
        assertEquals(h, fila.toModel())
    }
}
