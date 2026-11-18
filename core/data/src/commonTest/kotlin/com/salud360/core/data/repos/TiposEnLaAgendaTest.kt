package com.salud360.core.data.repos

import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reglas puras de los tipos de turno en la agenda: cómo se rotula un tipo, cuándo se muestra la etiqueta en un
 * horario o turno y con qué tipo se registra un turno sobre un horario. Todo con datos inventados.
 */
class TiposEnLaAgendaTest {

    private val hora = LocalTime(9, 0)
    private val fecha = LocalDate(2026, 11, 10)

    private fun slot(codigo: Int, h: LocalTime = hora) = SlotAgenda(
        horario = h, libre = true,
        tipoTurno = TipoTurno.porCodigo(codigo), tipoCodigo = TipoTurno.codigoSiNoEsDelEnum(codigo),
    )

    private fun turno(codigo: Int, estado: EstadoTurno = EstadoTurno.ACTIVO) = Turno(
        "t$codigo", "p1", "m1", "c1", fecha, hora,
        tipoTurno = TipoTurno.porCodigo(codigo), estado = estado, tipoCodigo = TipoTurno.codigoSiNoEsDelEnum(codigo),
    )

    // ---- el código real del tipo ----

    @Test
    fun el_codigo_de_un_tipo_conocido_sale_del_enum_y_no_se_guarda_aparte() {
        assertNull(TipoTurno.codigoSiNoEsDelEnum(1))
        assertNull(TipoTurno.codigoSiNoEsDelEnum(24))
        assertEquals(26, TipoTurno.codigoSiNoEsDelEnum(26))
        assertEquals(24, slot(24).codigoDeTipo)
        assertEquals(TipoTurno.DEPORTOLOGIA, slot(24).tipoTurno)
    }

    @Test
    fun un_codigo_desconocido_no_se_pierde_aunque_el_enum_caiga_en_consulta() {
        val s = slot(26)
        assertEquals(TipoTurno.CONSULTA, s.tipoTurno, "el enum cerrado sigue cayendo en Consulta")
        assertEquals(26, s.codigoDeTipo, "pero el código real queda")
        assertEquals(26, turno(26).codigoDeTipo)
        assertEquals(26, HorarioMedico("h", "m", "c", DayOfWeek.MONDAY, hora, tipoCodigo = 26).codigoDeTipo)
    }

    @Test
    fun sin_codigo_aparte_el_modelo_se_comporta_como_siempre() {
        assertEquals(1, SlotAgenda(hora, libre = true).codigoDeTipo)
        assertEquals(1, Turno("t", null, "m", "c", fecha, hora).codigoDeTipo)
        assertEquals(23, Turno("t", null, "m", "c", fecha, hora, TipoTurno.ECOGRAFIA).codigoDeTipo)
        assertEquals(1, HorarioMedico("h", "m", "c", DayOfWeek.MONDAY, hora).codigoDeTipo)
    }

    // ---- etiqueta de un tipo ----

    @Test
    fun el_nombre_que_manda_el_servidor_gana_a_todo() {
        assertEquals("Ecografía abdominal", etiquetaDelTipo(23, "Ecografía abdominal"))
        assertEquals("Control", etiquetaDelTipo(26, "Control"))
    }

    @Test
    fun sin_nombre_del_servidor_un_codigo_conocido_usa_el_del_enum() {
        assertEquals("Consulta", etiquetaDelTipo(1, null))
        assertEquals("Deportología", etiquetaDelTipo(24, null))
        assertEquals("Consulta + Ecografía", etiquetaDelTipo(25))
    }

    @Test
    fun sin_nombre_y_con_codigo_desconocido_se_rotula_con_el_codigo() {
        assertEquals("Tipo 26", etiquetaDelTipo(26, null))
        assertEquals("Tipo 99", etiquetaDelTipo(99))
    }

    @Test
    fun un_nombre_en_blanco_se_trata_como_si_no_viniera() {
        assertEquals("Tipo 26", etiquetaDelTipo(26, "   "))
        assertEquals("Deportología", etiquetaDelTipo(24, ""))
        assertEquals("Control", etiquetaDelTipo(26, "  Control  "))
    }

    // ---- cuándo se muestra la etiqueta ----

    @Test
    fun un_medico_que_solo_tiene_consultas_no_ve_etiquetas() {
        assertNull(etiquetaVisibleDelTipo(1, setOf(1), null))
        assertNull(etiquetaVisibleDelTipo(1, emptySet(), null), "sin datos del día tampoco")
    }

    @Test
    fun un_tipo_distinto_de_consulta_siempre_lleva_etiqueta() {
        assertEquals("Deportología", etiquetaVisibleDelTipo(24, setOf(24), null), "aunque sea el único tipo del día")
        assertEquals("Tipo 26", etiquetaVisibleDelTipo(26, setOf(26), null))
        assertEquals("Control", etiquetaVisibleDelTipo(26, setOf(1, 26), "Control"))
    }

    @Test
    fun si_el_dia_mezcla_tipos_tambien_la_consulta_lleva_etiqueta() {
        assertEquals("Consulta", etiquetaVisibleDelTipo(1, setOf(1, 24), null))
        assertEquals("Consulta general", etiquetaVisibleDelTipo(1, setOf(1, 26), "Consulta general"))
    }

    @Test
    fun la_etiqueta_corta_entra_en_el_circulo_de_la_semana() {
        assertEquals("Consulta", etiquetaCorta("Consulta"))
        assertEquals("Consulta + …", etiquetaCorta("Consulta + Ecografía"))
        assertEquals(12, etiquetaCorta("Consulta + Ecografía").length)
        assertEquals("Hola", etiquetaCorta("Hola", maximo = 4))
        assertEquals("Hol…", etiquetaCorta("Holas", maximo = 4))
    }

    @Test
    fun los_tipos_del_dia_salen_de_los_horarios_y_de_los_turnos_sin_contar_los_cancelados() {
        val slots = listOf(slot(1), slot(24, LocalTime(10, 0)))
        assertEquals(setOf(1, 24), codigosDelDia(slots, emptyList()))
        assertEquals(setOf(1, 24, 26), codigosDelDia(slots, listOf(turno(26))))
        assertEquals(setOf(1, 24), codigosDelDia(slots, listOf(turno(26, EstadoTurno.CANCELADO))))
        assertEquals(setOf(23), codigosDelDia(emptyList(), listOf(turno(23))))
    }

    // ---- con qué tipo se registra ----

    @Test
    fun el_turno_sobre_un_horario_toma_el_tipo_del_horario() {
        val base = Turno("t", "p1", "m1", "c1", fecha, hora)
        assertEquals(24, base.sobreElSlot(slot(24)).codigoDeTipo)
        assertEquals(TipoTurno.DEPORTOLOGIA, base.sobreElSlot(slot(24)).tipoTurno)
        assertEquals(26, base.sobreElSlot(slot(26)).codigoDeTipo, "un tipo creado por el admin viaja con su código")
        assertEquals(1, base.sobreElSlot(slot(1)).codigoDeTipo)
    }

    @Test
    fun sin_horario_el_turno_queda_como_estaba() {
        val base = Turno("t", "p1", "m1", "c1", fecha, hora, TipoTurno.ECOGRAFIA)
        assertEquals(base, base.sobreElSlot(null))
        assertEquals(1, codigoParaRegistrar(null), "bloquear o registrar sin horario conocido es Consulta, como hasta ahora")
    }

    @Test
    fun el_codigo_para_registrar_o_bloquear_es_el_del_horario() {
        assertEquals(1, codigoParaRegistrar(slot(1)))
        assertEquals(24, codigoParaRegistrar(slot(24)))
        assertEquals(26, codigoParaRegistrar(slot(26)))
    }

    @Test
    fun el_horario_de_un_sobreturno_se_busca_entre_los_horarios_del_dia() {
        val slots = listOf(slot(1, LocalTime(9, 0)), slot(24, LocalTime(10, 0)))
        assertEquals(24, slotDelHorario(slots, LocalTime(10, 0))?.codigoDeTipo)
        assertNull(slotDelHorario(slots, LocalTime(10, 15)), "un horario fuera de la plantilla no tiene tipo propio")
    }
}
