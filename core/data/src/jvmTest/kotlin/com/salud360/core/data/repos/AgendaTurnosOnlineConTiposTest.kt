package com.salud360.core.data.repos

import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.tobb.tobbJson
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La agenda de la app con todos los tipos de turno (segunda entrega de «tipos de turno»): la lectura del día, la semana y
 * las próximas fechas pide `todos_los_tipos=1`, los horarios y turnos conservan el código real del tipo, y los turnos que se
 * registran o bloquean sobre un horario llevan el tipo de ese horario. Un servidor viejo (que ignora el parámetro y no manda
 * los campos nuevos) sigue funcionando.
 *
 * Turnos falso en `127.0.0.1` (nunca el cliente por omisión, que apunta a producción) y base temporal. Ids, nombres y tokens
 * inventados.
 */
class AgendaTurnosOnlineConTiposTest {

    private val medico = "tobb-m3"
    private val consultorio = "tobb-c1"
    private val fecha = LocalDate(2026, 11, 10)

    private fun turnoJson(id: Int, hora: String, tipo: Int, nombre: String? = null) =
        """{"id":$id,"paciente_id":7,"medico_id":3,"consultorio_id":1,"horario":"$hora","fecha":"2026-11-10","tipo_turno":$tipo,""" +
            (nombre?.let { """"tipo_turno_nombre":"$it",""" } ?: "") +
            """"activo":1,"paciente":{"id":7,"nombre":"Ana","apellido":"Prueba","dni":1000001}}"""

    private val diaNuevo = """
        {"ok":true,"fecha":"2026-11-10","dia":2,"es_feriado":false,
         "slots":[
           {"horario":"09:00","libre":1,"tipo_turno":1,"tipo_turno_nombre":"Consulta"},
           {"horario":"09:30","libre":1,"tipo_turno":24,"tipo_turno_nombre":"Deportología"},
           {"horario":"10:00","libre":0,"tipo_turno":26,"tipo_turno_nombre":"Control"},
           {"horario":"10:30","libre":1,"tipo_turno":27}
         ],
         "turnos":[${turnoJson(501, "10:00", 26, "Control")}]}
    """.trimIndent()

    private val diaViejo = """
        {"ok":true,"fecha":"2026-11-10","dia":2,"es_feriado":false,
         "slots":[{"horario":"09:00","libre":1},{"horario":"09:30","libre":0}],
         "turnos":[${turnoJson(502, "09:30", 1)}]}
    """.trimIndent()

    private fun cuerpo(texto: String): JsonObject = tobbJson.parseToJsonElement(texto).jsonObject

    private fun turnoParaRegistrar(codigo: Int) = Turno(
        id = "local-1", pacienteId = "tobb-p7", medicoId = medico, consultorioId = consultorio, fecha = fecha, horario = LocalTime(9, 30),
        tipoTurno = TipoTurno.porCodigo(codigo), tipoCodigo = TipoTurno.codigoSiNoEsDelEnum(codigo),
    )

    // ---- lectura: el parámetro nuevo y los campos nuevos ----

    @Test
    fun el_dia_pide_todos_los_tipos_y_no_pide_un_tipo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("GET", "agenda/dia", 200, diaNuevo)

            agenda.dia(medico, consultorio, fecha)

            val pedido = turnos.pedidosA("/api/salud360/agenda/dia").single()
            assertEquals("GET", pedido.metodo)
            assertEquals("1", pedido.parametros["todos_los_tipos"])
            assertFalse("tipo_turno" in pedido.parametros, "con todos los tipos no se pide uno solo")
            assertEquals("2026-11-10", pedido.parametros["fecha"])
            assertEquals("3", pedido.parametros["medico_id"])
        }
    }

    @Test
    fun el_dia_trae_los_horarios_de_todos_los_tipos_con_su_codigo_real() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("GET", "agenda/dia", 200, diaNuevo)

            val d = agenda.dia(medico, consultorio, fecha)

            assertEquals(listOf(1, 24, 26, 27), d.slots.map { it.codigoDeTipo })
            assertEquals(listOf(true, true, false, true), d.slots.map { it.libre })
            assertEquals(26, d.turnos.single().codigoDeTipo)
        }
    }

    @Test
    fun los_nombres_que_manda_el_servidor_quedan_a_mano_para_rotular() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("GET", "agenda/dia", 200, diaNuevo)

            agenda.dia(medico, consultorio, fecha)

            assertEquals(mapOf(1 to "Consulta", 24 to "Deportología", 26 to "Control"), agenda.nombresDeTipos.value)
        }
    }

    @Test
    fun el_turno_de_un_tipo_nuevo_queda_en_la_base_con_su_codigo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, agenda) = escena(turnos)
            turnos.responderA("GET", "agenda/dia", 200, diaNuevo)

            agenda.dia(medico, consultorio, fecha)

            assertEquals(26, repo.turno("tobb-t501")?.codigoDeTipo)
        }
    }

    @Test
    fun un_servidor_viejo_sigue_funcionando_con_tipo_1_y_sin_nombres() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("GET", "agenda/dia", 200, diaViejo)

            val d = agenda.dia(medico, consultorio, fecha)

            assertEquals(listOf(1, 1), d.slots.map { it.codigoDeTipo })
            assertEquals(listOf(true, false), d.slots.map { it.libre })
            assertEquals(1, d.turnos.single().codigoDeTipo)
            assertTrue(agenda.nombresDeTipos.value.isEmpty())
        }
    }

    @Test
    fun pedir_un_tipo_puntual_sigue_mandando_ese_tipo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("GET", "agenda/dia", 200, diaViejo)

            agenda.dia(medico, consultorio, fecha, TipoTurno.ECOGRAFIA)

            val pedido = turnos.pedidosA("/api/salud360/agenda/dia").single()
            assertEquals("23", pedido.parametros["tipo_turno"])
            assertFalse("todos_los_tipos" in pedido.parametros)
        }
    }

    @Test
    fun la_semana_pide_todos_los_tipos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("GET", "agenda/semana", 200, """{"ok":true,"desde":"2026-11-10","dias":[$diaNuevo]}""")

            val dias = agenda.semana(medico, consultorio, fecha)

            val pedido = turnos.pedidosA("/api/salud360/agenda/semana").single()
            assertEquals("1", pedido.parametros["todos_los_tipos"])
            assertEquals("5", pedido.parametros["cantidad"])
            assertEquals(listOf(1, 24, 26, 27), dias.single().slots.map { it.codigoDeTipo })
        }
    }

    @Test
    fun las_proximas_fechas_piden_todos_los_tipos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("GET", "agenda/proximas-fechas", 200, """{"ok":true,"fechas":["2026-11-12","2026-11-19"]}""")

            val fechas = agenda.proximasFechas(medico, consultorio, fecha)

            val pedido = turnos.pedidosA("/api/salud360/agenda/proximas-fechas").single()
            assertEquals("1", pedido.parametros["todos_los_tipos"])
            assertEquals(listOf(LocalDate(2026, 11, 12), LocalDate(2026, 11, 19)), fechas)
        }
    }

    @Test
    fun el_repositorio_lee_la_agenda_del_dia_con_todos_los_tipos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.responderA("GET", "agenda/dia", 200, diaNuevo)

            val slots = repo.slotsDelDia(medico, consultorio, fecha)
            repo.refrescarDia(medico, consultorio, fecha)

            assertEquals(listOf(1, 24, 26, 27), slots.map { it.codigoDeTipo })
            val pedidos = turnos.pedidosA("/api/salud360/agenda/dia")
            assertEquals(2, pedidos.size)
            assertTrue(pedidos.all { it.parametros["todos_los_tipos"] == "1" && "tipo_turno" !in it.parametros })
            assertEquals("Deportología", repo.nombresDeTipos.value[24])
        }
    }

    @Test
    fun el_repositorio_lee_la_semana_y_las_proximas_fechas_con_todos_los_tipos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.responderA("GET", "agenda/semana", 200, """{"ok":true,"desde":"2026-11-10","dias":[$diaNuevo]}""")
            turnos.responderA("GET", "agenda/proximas-fechas", 200, """{"ok":true,"fechas":["2026-11-12"]}""")

            repo.semana(medico, consultorio, fecha)
            repo.proximasFechasLibres(medico, consultorio, fecha)

            assertEquals("1", turnos.pedidosA("/api/salud360/agenda/semana").single().parametros["todos_los_tipos"])
            assertEquals("1", turnos.pedidosA("/api/salud360/agenda/proximas-fechas").single().parametros["todos_los_tipos"])
        }
    }

    // ---- horarios fijos ----

    private val horariosJson = """
        {"ok":true,"medico_id":3,"consultorio_id":1,"horarios_fijos":[
          {"id":4,"dia":2,"horario":"09:00","tipo_turno":26,"tipo_turno_nombre":"Control"},
          {"id":5,"dia":2,"horario":"09:30","tipo_turno":23},
          {"id":6,"dia":2,"horario":"10:00"}
        ]}
    """.trimIndent()

    @Test
    fun los_horarios_fijos_se_guardan_con_su_codigo_real() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, agenda) = escena(turnos)
            turnos.responderA("GET", "horarios", 200, horariosJson)

            agenda.sincronizarHorarios(medico, consultorio)

            val porHora = repo.horarios(medico).associateBy { it.horario }
            assertEquals(26, porHora.getValue(LocalTime(9, 0)).codigoDeTipo)
            assertEquals(23, porHora.getValue(LocalTime(9, 30)).codigoDeTipo)
            assertEquals(TipoTurno.ECOGRAFIA, porHora.getValue(LocalTime(9, 30)).tipoTurno)
            assertEquals(1, porHora.getValue(LocalTime(10, 0)).codigoDeTipo, "un servidor viejo no manda el tipo: queda 1")
            assertEquals("Control", agenda.nombresDeTipos.value[26])
        }
    }

    @Test
    fun editar_un_horario_de_un_tipo_desconocido_no_lo_reescribe_como_consulta() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, agenda) = escena(turnos)
            turnos.responderA("GET", "horarios", 200, horariosJson)
            turnos.responderA("PUT", "horarios/4", 200, """{"ok":true}""")
            agenda.sincronizarHorarios(medico, consultorio)
            val h = repo.horarios(medico).first { it.horario == LocalTime(9, 0) }

            repo.guardarHorario(h.copy(quincenal = true))

            val despues = repo.horarios(medico).first { it.horario == LocalTime(9, 0) }
            assertEquals(26, despues.codigoDeTipo)
            assertTrue(despues.quincenal)
            val put = turnos.pedidosA("/api/salud360/horarios/4").single()
            assertFalse("tipo_turno" in cuerpo(put.cuerpo), "editar la vigencia nunca manda el tipo")
        }
    }

    // ---- escritura: el tipo del horario ----

    @Test
    fun un_turno_sobre_un_horario_de_otro_tipo_lleva_ese_tipo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(600, "09:30", 24, "Deportología")}]}""")

            val r = agenda.registrar(turnoParaRegistrar(24))

            assertTrue(r is ResultadoTurno.Ok)
            assertEquals(24, cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo).getValue("tipo_turno").jsonPrimitive.int)
        }
    }

    @Test
    fun un_turno_sobre_un_horario_de_un_tipo_creado_por_el_admin_lleva_su_codigo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(601, "09:30", 26, "Control")}]}""")

            val r = agenda.registrar(turnoParaRegistrar(26))

            assertTrue(r is ResultadoTurno.Ok)
            assertEquals(26, cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo).getValue("tipo_turno").jsonPrimitive.int)
            assertEquals(26, r.turno.codigoDeTipo)
        }
    }

    @Test
    fun un_turno_de_tipo_1_no_manda_tipo_y_el_servidor_usa_su_valor_por_omision() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(602, "09:30", 1)}]}""")

            agenda.registrar(turnoParaRegistrar(1))

            assertFalse("tipo_turno" in cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo))
        }
    }

    @Test
    fun el_sobreturno_lleva_el_tipo_del_horario() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.responderA("POST", "turnos/sobreturno", 200, """{"ok":true,"turno":${turnoJson(603, "09:30", 24, "Deportología")}}""")

            val r = agenda.sobreturno(turnoParaRegistrar(24).copy(sobreturno = true))

            assertTrue(r is ResultadoTurno.Ok)
            assertEquals(24, cuerpo(turnos.pedidosA("/api/salud360/turnos/sobreturno").single().cuerpo).getValue("tipo_turno").jsonPrimitive.int)
        }
    }

    @Test
    fun bloquear_un_horario_de_otro_tipo_manda_el_tipo_del_horario() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.responderA("GET", "pacientes/buscar", 200, """{"ok":true,"pacientes":[{"id":77,"dni":"99999"}]}""")
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(604, "09:30", 24)}]}""")

            val r = repo.bloquearHorario(medico, consultorio, fecha, LocalTime(9, 30), por = "operador", tipoCodigo = 24)

            assertTrue(r is ResultadoTurno.Ok)
            val enviado = cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo)
            assertEquals(24, enviado.getValue("tipo_turno").jsonPrimitive.int)
            assertEquals(77, enviado.getValue("paciente_id").jsonPrimitive.int)
        }
    }

    @Test
    fun bloquear_sin_indicar_tipo_sigue_como_siempre() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.responderA("GET", "pacientes/buscar", 200, """{"ok":true,"pacientes":[{"id":77,"dni":"99999"}]}""")
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(605, "09:30", 1)}]}""")

            repo.bloquearHorario(medico, consultorio, fecha, LocalTime(9, 30), por = "operador")

            assertNull(cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo)["tipo_turno"])
        }
    }

    // ---- armado ----

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-agenda-tipos", ".db").also { it.delete(); it.deleteOnExit() }
        return createDatabase(DriverFactory(archivo.absolutePath))
    }

    private suspend fun escena(turnos: TurnosFalsoDeAgenda): Pair<TurnosRepository, AgendaTurnosOnline> {
        // El cliente va al turnos falso de `127.0.0.1`: nunca al de por omisión, que apunta a producción.
        val cliente = TurnosOnlineClient(turnos.url).apply { token = "tok-de-prueba" }
        val db = baseTemporal()
        val agenda = AgendaTurnosOnline(db, cliente)
        return TurnosRepository(db, agenda) to agenda
    }
}
