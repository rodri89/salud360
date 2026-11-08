package com.salud360.core.data.repos

import com.salud360.core.data.network.TobbException
import com.salud360.core.model.turnos.CondicionTipoTurno
import com.salud360.core.model.turnos.OpcionTipoTurno
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.TipoTurnoDelMedico
import com.salud360.core.model.turnos.Turno
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reglas puras de la tercera entrega de «tipos de turno»: qué tipos se ofrecen al cargar un horario, qué opciones se
 * ofrecen al asignar un turno sobre un horario, con qué tipo se registra y cómo se rotula. Datos inventados.
 */
class TiposParaHorariosYTurnosTest {

    private fun tipo(id: Int, nombre: String, vararg opciones: Pair<Int, String>) =
        TipoTurnoDelMedico(id, nombre, CondicionTipoTurno.SIEMPRE, opciones.map { OpcionTipoTurno(it.first, it.second) })

    private fun slot(codigo: Int, libre: Boolean = true) = SlotAgenda(
        horario = LocalTime(9, 0), libre = libre,
        tipoTurno = TipoTurno.porCodigo(codigo), tipoCodigo = TipoTurno.codigoSiNoEsDelEnum(codigo),
    )

    // Un médico con Consulta (1) con dos opciones, Control (26) sin opciones y Ecografía (23) con una.
    private val habilitados = listOf(
        tipo(1, "Consulta", 27 to "Primera vez", 28 to "Seguimiento"),
        tipo(26, "Control"),
        tipo(23, "Ecografías", 29 to "Mamaria"),
    )

    // ---- (i) tipos que se ofrecen al cargar un horario ----

    @Test
    fun se_ofrecen_los_tipos_habilitados_del_medico_con_su_nombre_y_en_su_orden() {
        val r = tiposOfrecidosParaHorario(habilitados)
        assertEquals(listOf(1, 26, 23), r.map { it.codigo })
        assertEquals(listOf("Consulta", "Control", "Ecografías"), r.map { it.nombre })
        assertTrue(r.all { it.habilitado })
    }

    @Test
    fun no_se_ofrece_el_enum_cerrado_completo_si_el_medico_tiene_tipos() {
        val r = tiposOfrecidosParaHorario(listOf(tipo(26, "Control")))
        assertEquals(listOf(26), r.map { it.codigo })
    }

    @Test
    fun sin_tipos_habilitados_o_sin_poder_leerlos_se_ofrece_solo_consulta() {
        assertEquals(listOf(1), tiposOfrecidosParaHorario(emptyList()).map { it.codigo })
        assertEquals(listOf(1), tiposOfrecidosParaHorario(null).map { it.codigo })
        assertEquals("Consulta", tiposOfrecidosParaHorario(null).single().nombre)
    }

    @Test
    fun el_nombre_de_consulta_sin_lista_sale_del_que_informo_turnos_si_lo_hay() {
        assertEquals("Atención", tiposOfrecidosParaHorario(null, nombresDeTipos = mapOf(1 to "Atención")).single().nombre)
    }

    @Test
    fun el_tipo_actual_que_ya_no_esta_habilitado_se_incluye_marcado_como_tal() {
        val r = tiposOfrecidosParaHorario(listOf(tipo(1, "Consulta")), tipoActual = 24, nombresDeTipos = mapOf(24 to "Deportología"))
        assertEquals(listOf(1, 24), r.map { it.codigo })
        assertTrue(r[0].habilitado)
        assertFalse(r[1].habilitado)
        assertEquals("Deportología", r[1].nombre)
        assertEquals("Deportología (ya no habilitado)", r[1].etiqueta)
        assertEquals("Consulta", r[0].etiqueta)
    }

    @Test
    fun el_tipo_actual_habilitado_no_se_duplica() {
        val r = tiposOfrecidosParaHorario(habilitados, tipoActual = 26)
        assertEquals(listOf(1, 26, 23), r.map { it.codigo })
    }

    @Test
    fun el_tipo_actual_de_un_codigo_desconocido_se_rotula_con_su_codigo() {
        val r = tiposOfrecidosParaHorario(emptyList(), tipoActual = 31)
        assertEquals(listOf(1, 31), r.map { it.codigo })
        assertEquals("Tipo 31 (ya no habilitado)", r[1].etiqueta)
    }

    @Test
    fun sin_poder_leer_los_tipos_se_ofrece_el_tipo_actual_mas_consulta_sin_decir_que_no_esta_habilitado() {
        val r = tiposOfrecidosParaHorario(null, tipoActual = 26, nombresDeTipos = mapOf(26 to "Control"))
        assertEquals(listOf(1, 26), r.map { it.codigo })
        assertTrue(r.all { it.habilitado }, "no se sabe si sigue habilitado: no se lo marca")
        assertEquals("Control", r[1].etiqueta)
    }

    @Test
    fun un_medico_local_conserva_todos_los_tipos_del_enum() {
        val r = tiposOfrecidosParaHorario(null, medicoRemoto = false)
        assertEquals(TipoTurno.entries.map { it.codigo }, r.map { it.codigo })
        assertEquals(TipoTurno.entries.map { it.etiqueta }, r.map { it.nombre })
    }

    @Test
    fun el_selector_solo_se_muestra_si_hay_mas_de_un_tipo() {
        assertFalse(hayQueElegirTipo(tiposOfrecidosParaHorario(null)))
        assertFalse(hayQueElegirTipo(tiposOfrecidosParaHorario(listOf(tipo(26, "Control")))))
        assertTrue(hayQueElegirTipo(tiposOfrecidosParaHorario(habilitados)))
    }

    @Test
    fun sin_elegir_se_usa_el_primer_tipo_ofrecido_y_si_se_eligio_ese() {
        val sinConsulta = tiposOfrecidosParaHorario(listOf(tipo(26, "Control"), tipo(23, "Ecografías")))
        assertEquals(26, tipoElegidoParaHorario(sinConsulta, null), "un médico sin Consulta no arranca en Consulta")
        assertEquals(23, tipoElegidoParaHorario(sinConsulta, 23))
        assertEquals(1, tipoElegidoParaHorario(tiposOfrecidosParaHorario(null), null))
    }

    // ---- (ii) opciones de un horario ----

    @Test
    fun las_opciones_de_un_horario_son_las_de_su_tipo() {
        assertEquals(listOf(OpcionTipoTurno(27, "Primera vez"), OpcionTipoTurno(28, "Seguimiento")), opcionesParaSlot(habilitados, 1))
        assertEquals(listOf(OpcionTipoTurno(29, "Mamaria")), opcionesParaSlot(habilitados, 23))
    }

    @Test
    fun un_tipo_sin_opciones_o_desconocido_o_sin_lista_no_ofrece_opciones() {
        assertTrue(opcionesParaSlot(habilitados, 26).isEmpty())
        assertTrue(opcionesParaSlot(habilitados, 99).isEmpty())
        assertTrue(opcionesParaSlot(null, 1).isEmpty())
        assertTrue(opcionesParaSlot(emptyList(), 1).isEmpty())
    }

    @Test
    fun un_tipo_no_es_opcion_de_si_mismo() {
        val raros = listOf(tipo(1, "Consulta", 1 to "Consulta", 27 to "Primera vez"))
        assertEquals(listOf(27), opcionesParaSlot(raros, 1).map { it.id })
    }

    // ---- (iii) tipo con que se registra ----

    @Test
    fun sin_opcion_se_registra_con_el_tipo_del_horario() {
        assertEquals(1, tipoConQueSeRegistra(slot(1), null, habilitados))
        assertEquals(26, tipoConQueSeRegistra(slot(26), null, habilitados))
    }

    @Test
    fun una_opcion_valida_del_tipo_del_horario_se_registra_con_su_codigo() {
        assertEquals(27, tipoConQueSeRegistra(slot(1), 27, habilitados))
        assertEquals(29, tipoConQueSeRegistra(slot(23), 29, habilitados))
    }

    @Test
    fun una_opcion_de_otro_tipo_o_inexistente_se_ignora_y_queda_el_tipo_del_horario() {
        assertEquals(1, tipoConQueSeRegistra(slot(1), 29, habilitados), "29 es opción de Ecografías, no de Consulta")
        assertEquals(26, tipoConQueSeRegistra(slot(26), 27, habilitados))
        assertEquals(1, tipoConQueSeRegistra(slot(1), 99, habilitados))
    }

    @Test
    fun sin_lista_de_tipos_no_hay_opciones_validas() {
        assertEquals(1, tipoConQueSeRegistra(slot(1), 27, null))
        assertEquals(1, tipoConQueSeRegistra(slot(1), 27, emptyList()))
    }

    @Test
    fun sin_horario_se_registra_como_consulta_y_la_opcion_no_cuenta() {
        assertEquals(1, tipoConQueSeRegistra(null, null, habilitados))
        assertEquals(1, tipoConQueSeRegistra(null, 27, habilitados))
    }

    @Test
    fun el_turno_toma_el_codigo_elegido_y_conserva_el_real_si_el_enum_no_lo_conoce() {
        val base = Turno("t", "p", "m", "c", LocalDate(2026, 11, 10), LocalTime(9, 0))
        val conOpcion = base.conTipo(27)
        assertEquals(27, conOpcion.codigoDeTipo)
        assertEquals(27, conOpcion.tipoCodigo)
        val deEnum = base.conTipo(23)
        assertEquals(TipoTurno.ECOGRAFIA, deEnum.tipoTurno)
        assertNull(deEnum.tipoCodigo)
        assertEquals(23, deEnum.codigoDeTipo)
        val vuelta = conOpcion.conTipo(1)
        assertEquals(1, vuelta.codigoDeTipo)
        assertNull(vuelta.tipoCodigo)
    }

    // ---- (iv) textos ----

    @Test
    fun una_opcion_se_rotula_con_su_tipo_padre_si_es_conocido() {
        assertEquals("Consulta: Primera vez", etiquetaDelTipoDelTurno(27, emptyMap(), habilitados))
        assertEquals("Ecografías: Mamaria", etiquetaDelTipoDelTurno(29, mapOf(29 to "Mamaria"), habilitados))
    }

    @Test
    fun una_opcion_sin_padre_conocido_se_rotula_con_su_nombre() {
        assertEquals("Mamaria", etiquetaDelTipoDelTurno(29, mapOf(29 to "Mamaria"), null))
        assertEquals("Mamaria", etiquetaDelTipoDelTurno(29, mapOf(29 to "Mamaria"), emptyList()))
        assertEquals("Tipo 29", etiquetaDelTipoDelTurno(29, emptyMap(), null))
    }

    @Test
    fun un_tipo_habilitado_se_rotula_solo_aunque_tambien_sea_opcion_de_otro() {
        val ambiguo = listOf(tipo(1, "Consulta", 26 to "Control"), tipo(26, "Control"))
        assertEquals("Control", etiquetaDelTipoDelTurno(26, emptyMap(), ambiguo))
    }

    @Test
    fun una_opcion_de_dos_padres_no_adivina_cual_es_y_usa_su_nombre() {
        val dos = listOf(tipo(1, "Consulta", 27 to "Primera vez"), tipo(23, "Ecografías", 27 to "Primera vez"))
        assertEquals("Primera vez", etiquetaDelTipoDelTurno(27, emptyMap(), dos))
    }

    @Test
    fun los_tipos_comunes_se_rotulan_como_siempre() {
        assertEquals("Consulta", etiquetaDelTipoDelTurno(1, emptyMap(), null))
        assertEquals("Deportología", etiquetaDelTipoDelTurno(24, emptyMap(), habilitados))
        assertEquals("Control", etiquetaDelTipoDelTurno(26, mapOf(26 to "Control"), habilitados))
    }

    @Test
    fun el_nombre_que_informo_turnos_gana_al_de_la_lista() {
        assertEquals("Consulta: Primera vez (nueva)", etiquetaDelTipoDelTurno(27, mapOf(27 to "Primera vez (nueva)"), habilitados))
    }

    @Test
    fun el_aviso_de_turno_registrado_nombra_la_opcion_solo_si_se_eligio_una() {
        assertEquals("Turno registrado", avisoDeTurnoRegistrado(1, slot(1), emptyMap(), habilitados))
        assertEquals("Turno registrado", avisoDeTurnoRegistrado(1, null, emptyMap(), habilitados))
        assertEquals("Turno registrado · Consulta: Primera vez", avisoDeTurnoRegistrado(27, slot(1), emptyMap(), habilitados))
    }

    @Test
    fun la_ayuda_de_la_opcion_dice_con_que_se_registra() {
        assertEquals("Se registra como Consulta", ayudaDeOpcion("Consulta", null))
        assertEquals("Se registra como Consulta: Primera vez", ayudaDeOpcion("Consulta", OpcionTipoTurno(27, "Primera vez")))
        assertEquals("Sin especificar", TEXTO_SIN_ESPECIFICAR)
    }

    // ---- lectura de los tipos del médico: qué pasa cuando falla ----

    @Test
    fun sin_conexion_es_un_caso_aparte_de_no_disponible() {
        val red = lecturaDeTiposFallida(TobbException(0, "red", "Sin conexión con turnosonlinebb: caído"))
        assertTrue(red is LecturaDeTiposDelMedico.SinConexion)
        assertEquals("Sin conexión con turnosonlinebb: caído", red.motivo)
    }

    @Test
    fun un_servidor_viejo_o_sin_permiso_es_no_disponible() {
        listOf(
            TobbException(403, "sin_permiso", "No tenés permiso"),
            TobbException(403, null, "Forbidden"),
            TobbException(404, "sin_ruta", "La web de turnos todavía no permite ver los tipos"),
            TobbException(405, null, "Method Not Allowed"),
            TobbException(500, "respuesta", "turnosonlinebb respondió pero no informó los tipos"),
            TobbException(400, "medico", "El médico no pertenece a turnosonlinebb"),
        ).forEach { e -> assertEquals(LecturaDeTiposDelMedico.NoDisponible, lecturaDeTiposFallida(e), e.message) }
    }

    @Test
    fun solo_se_avisa_la_falta_de_conexion_y_si_no_habia_nada_en_memoria() {
        val sinRed = LecturaDeTiposDelMedico.SinConexion("caído")
        assertEquals(
            "No se pudieron leer los tipos de turno habilitados del médico (sin conexión): por ahora se ofrece solo «Consulta».",
            avisoDeTiposNoLeidos(sinRed, hayEnMemoria = false),
        )
        assertNull(avisoDeTiposNoLeidos(sinRed, hayEnMemoria = true))
        assertNull(avisoDeTiposNoLeidos(LecturaDeTiposDelMedico.NoDisponible, hayEnMemoria = false), "un servidor viejo degrada sin avisar")
        assertNull(avisoDeTiposNoLeidos(LecturaDeTiposDelMedico.Leidos(habilitados), hayEnMemoria = true))
    }
}
