package com.salud360.core.data.repos

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.tobb.tobbJson
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.turnos.CondicionTipoTurno
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.OpcionTipoTurno
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.TipoTurnoDelMedico
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tercera entrega de «tipos de turno»: los horarios y el asignar usan los tipos habilitados del médico. Lo que se prueba
 * contra un turnos falso en `127.0.0.1` (nunca el cliente por omisión, que apunta a producción):
 *  - la lectura de `GET medicos/{n}/tipos-turno` con el token de cualquier rol, su caché en memoria por médico y su
 *    invalidación;
 *  - la degradación sin romper cuando el servidor es viejo (403/404/405) o no hay red;
 *  - el cuerpo de `POST turnos`, `POST turnos/sobreturno` y `POST horarios` con el tipo elegido;
 *  - los `422` (`tipo_invalido`, `tipo_no_habilitado`) con el mensaje del servidor tal cual.
 * Ids, nombres y tokens inventados.
 */
class AgendaTurnosOnlineTiposParaTurnosTest {

    private val medico = "tobb-m12"
    private val otroMedico = "tobb-m13"
    private val consultorio = "tobb-c1"
    private val fecha = LocalDate(2026, 11, 10)
    private val rutaTipos = "/api/salud360/medicos/12/tipos-turno"

    private val tiposJson = """
        {"ok":true,"medico_id":12,"tipos":[
          {"id":1,"nombre":"Consulta","primer_control":null,"opciones":[{"id":27,"nombre":"Primera vez"},{"id":28,"nombre":"Seguimiento"}]},
          {"id":26,"nombre":"Control","primer_control":1,"opciones":[]}
        ]}
    """.trimIndent()

    private val tiposEsperados = listOf(
        TipoTurnoDelMedico(1, "Consulta", CondicionTipoTurno.SIEMPRE, listOf(OpcionTipoTurno(27, "Primera vez"), OpcionTipoTurno(28, "Seguimiento"))),
        TipoTurnoDelMedico(26, "Control", CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, emptyList()),
    )

    private fun cuerpo(texto: String): JsonObject = tobbJson.parseToJsonElement(texto).jsonObject

    private fun turnoJson(id: Int, hora: String, tipo: Int) =
        """{"id":$id,"paciente_id":7,"medico_id":12,"consultorio_id":1,"horario":"$hora","fecha":"2026-11-10","tipo_turno":$tipo,""" +
            """"activo":1,"paciente":{"id":7,"nombre":"Ana","apellido":"Prueba","dni":1000001}}"""

    private fun slotDe(codigo: Int) = SlotAgenda(
        horario = LocalTime(9, 30), libre = true,
        tipoTurno = TipoTurno.porCodigo(codigo), tipoCodigo = TipoTurno.codigoSiNoEsDelEnum(codigo),
    )

    /** El turno como lo arma el asistente de asignar: sobre el [slot], con la [opcion] elegida (o ninguna). */
    private fun turnoComoLoArmaElAsignar(slot: SlotAgenda, opcion: Int?, tipos: List<TipoTurnoDelMedico>?) = Turno(
        id = "local-1", pacienteId = "tobb-p7", medicoId = medico, consultorioId = consultorio, fecha = fecha, horario = slot.horario,
    ).conTipo(tipoConQueSeRegistra(slot, opcion, tipos))

    // ---- lectura y caché ----

    @Test
    fun la_lectura_sale_con_get_a_la_ruta_del_numero_de_turnos_y_con_el_token_de_quien_este_logueado() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos, token = "tok-de-una-secretaria")
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson

            val r = repo.refrescarTiposDelMedico(medico)

            assertEquals(LecturaDeTiposDelMedico.Leidos(tiposEsperados), r)
            val pedido = turnos.pedidosA(rutaTipos).single()
            assertEquals("GET", pedido.metodo)
            assertEquals("tok-de-una-secretaria", pedido.token)
            assertEquals("", pedido.cuerpo)
        }
    }

    @Test
    fun lo_leido_queda_en_memoria_por_medico() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            assertNull(repo.tiposEnMemoria(medico))
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson

            repo.refrescarTiposDelMedico(medico)

            assertEquals(tiposEsperados, repo.tiposEnMemoria(medico))
            assertNull(repo.tiposEnMemoria(otroMedico), "la lista de un médico no se le muestra a otro")
            assertEquals(tiposEsperados, repo.tiposHabilitados.value[medico])
        }
    }

    @Test
    fun cada_medico_tiene_su_propia_lista_y_cambiar_de_medico_no_cruza_los_tipos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson
            repo.refrescarTiposDelMedico(medico)
            turnos.respuestaDeTiposDelMedico = 200 to """{"ok":true,"medico_id":13,"tipos":[]}"""

            repo.refrescarTiposDelMedico(otroMedico)

            assertEquals(tiposEsperados, repo.tiposEnMemoria(medico))
            assertEquals(emptyList(), repo.tiposEnMemoria(otroMedico))
            assertEquals(1, turnos.pedidosA("/api/salud360/medicos/13/tipos-turno").size)
        }
    }

    @Test
    fun cada_apertura_vuelve_a_leer_y_se_queda_con_lo_ultimo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson
            repo.refrescarTiposDelMedico(medico)
            turnos.respuestaDeTiposDelMedico = 200 to """{"ok":true,"medico_id":12,"tipos":[{"id":26,"nombre":"Control","primer_control":null,"opciones":[]}]}"""

            repo.refrescarTiposDelMedico(medico)

            assertEquals(2, turnos.pedidosA(rutaTipos).size)
            assertEquals(listOf(26), repo.tiposEnMemoria(medico)?.map { it.id })
        }
    }

    @Test
    fun un_medico_sin_tipos_habilitados_queda_con_la_lista_vacia_y_no_es_un_error() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to """{"ok":true,"medico_id":12,"tipos":[]}"""

            val r = repo.refrescarTiposDelMedico(medico)

            assertEquals(LecturaDeTiposDelMedico.Leidos(emptyList()), r)
            assertEquals(emptyList(), repo.tiposEnMemoria(medico))
            assertEquals(listOf(1), tiposOfrecidosParaHorario(repo.tiposEnMemoria(medico)).map { it.codigo }, "ofrece solo Consulta")
        }
    }

    @Test
    fun los_nombres_de_los_tipos_y_de_las_opciones_quedan_a_mano_para_rotular() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson

            repo.refrescarTiposDelMedico(medico)

            assertEquals(mapOf(1 to "Consulta", 27 to "Primera vez", 28 to "Seguimiento", 26 to "Control"), repo.nombresDeTipos.value)
        }
    }

    @Test
    fun guardar_los_tipos_desde_el_admin_invalida_la_lista_de_ese_medico_y_no_la_de_otro() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson
            repo.refrescarTiposDelMedico(medico)
            turnos.respuestaDeTiposDelMedico = 200 to """{"ok":true,"medico_id":13,"tipos":[{"id":26,"nombre":"Control","primer_control":null,"opciones":[]}]}"""
            repo.refrescarTiposDelMedico(otroMedico)
            turnos.respuestaDeGuardarTiposDelMedico = 200 to """{"ok":true,"medico_id":12,"tipos":[{"id":26,"nombre":"Control","primer_control":null,"opciones":[]}]}"""

            repo.guardarTiposDelMedico(medico, listOf(ConfigTipoTurno(26, CondicionTipoTurno.SIEMPRE, null)))

            assertNull(repo.tiposEnMemoria(medico), "se vuelve a leer al abrir Horarios o Asignar")
            assertEquals(listOf(26), repo.tiposEnMemoria(otroMedico)?.map { it.id })
        }
    }

    @Test
    fun invalidar_borra_solo_la_lista_de_ese_medico() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson
            repo.refrescarTiposDelMedico(medico)
            repo.refrescarTiposDelMedico(otroMedico)

            repo.invalidarTiposDelMedico(medico)

            assertNull(repo.tiposEnMemoria(medico))
            assertEquals(tiposEsperados, repo.tiposEnMemoria(otroMedico))
        }
    }

    // ---- degradación ----

    @Test
    fun un_servidor_viejo_que_contesta_403_al_no_admin_degrada_sin_romper() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 403 to """{"ok":false,"codigo":"sin_permiso","mensaje":"Solo el administrador puede ver esto"}"""

            val r = repo.refrescarTiposDelMedico(medico)

            assertEquals(LecturaDeTiposDelMedico.NoDisponible, r)
            assertNull(repo.tiposEnMemoria(medico))
            assertEquals(emptyList(), opcionesParaSlot(repo.tiposEnMemoria(medico), 1), "asignar sigue como hoy: sin opciones")
            assertEquals(listOf(1), tiposOfrecidosParaHorario(repo.tiposEnMemoria(medico)).map { it.codigo })
        }
    }

    @Test
    fun sin_ruta_404_o_405_degrada_igual() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            listOf(404 to """{"ok":false,"mensaje":"Not Found"}""", 405 to "<html>Method Not Allowed</html>").forEach { respuesta ->
                turnos.respuestaDeTiposDelMedico = respuesta
                assertEquals(LecturaDeTiposDelMedico.NoDisponible, repo.refrescarTiposDelMedico(medico), "${respuesta.first}")
                assertNull(repo.tiposEnMemoria(medico))
            }
        }
    }

    @Test
    fun un_403_despues_de_haber_leido_descarta_la_lista_vieja() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson
            repo.refrescarTiposDelMedico(medico)
            turnos.respuestaDeTiposDelMedico = 403 to """{"ok":false,"codigo":"sin_permiso","mensaje":"No tenés permiso"}"""

            assertEquals(LecturaDeTiposDelMedico.NoDisponible, repo.refrescarTiposDelMedico(medico))

            assertNull(repo.tiposEnMemoria(medico))
        }
    }

    @Test
    fun sin_red_se_avisa_la_falta_de_conexion_y_se_conserva_lo_que_ya_se_habia_leido() = runBlocking {
        val turnos = TurnosFalsoDeAgenda()
        val (repo, _) = escena(turnos)
        turnos.respuestaDeTiposDelMedico = 200 to tiposJson
        repo.refrescarTiposDelMedico(medico)
        turnos.close()

        val r = repo.refrescarTiposDelMedico(medico)

        assertTrue(r is LecturaDeTiposDelMedico.SinConexion)
        assertEquals(tiposEsperados, repo.tiposEnMemoria(medico), "sin red valen las opciones que ya se habían leído")
        assertNull(avisoDeTiposNoLeidos(r, hayEnMemoria = repo.tiposEnMemoria(medico) != null))
    }

    @Test
    fun sin_red_y_sin_nada_en_memoria_queda_el_aviso_discreto() = runBlocking {
        val turnos = TurnosFalsoDeAgenda()
        val (repo, _) = escena(turnos)
        turnos.close()

        val r = repo.refrescarTiposDelMedico(medico)

        assertTrue(r is LecturaDeTiposDelMedico.SinConexion)
        assertNull(repo.tiposEnMemoria(medico))
        assertTrue(avisoDeTiposNoLeidos(r, hayEnMemoria = false).orEmpty().contains("sin conexión"))
    }

    @Test
    fun un_medico_que_no_es_de_turnos_no_sale_a_la_red() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)

            val r = repo.refrescarTiposDelMedico("medico-local")

            assertEquals(LecturaDeTiposDelMedico.NoDisponible, r)
            assertTrue(turnos.pedidos().isEmpty())
        }
    }

    // ---- asignar: el cuerpo de POST turnos / sobreturno con la opción ----

    @Test
    fun asignar_con_una_opcion_manda_el_codigo_de_la_opcion() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson
            repo.refrescarTiposDelMedico(medico)
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(700, "09:30", 27)}]}""")
            val tipos = repo.tiposEnMemoria(medico)
            assertEquals(listOf(27, 28), opcionesParaSlot(tipos, slotDe(1).codigoDeTipo).map { it.id })

            val r = repo.registrarTurno(turnoComoLoArmaElAsignar(slotDe(1), opcion = 27, tipos = tipos))

            assertTrue(r is ResultadoTurno.Ok)
            assertEquals(27, cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo).getValue("tipo_turno").jsonPrimitive.int)
            assertEquals(27, r.turno.codigoDeTipo)
        }
    }

    @Test
    fun asignar_sin_opcion_sobre_un_horario_de_consulta_no_manda_tipo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson
            repo.refrescarTiposDelMedico(medico)
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(701, "09:30", 1)}]}""")

            repo.registrarTurno(turnoComoLoArmaElAsignar(slotDe(1), opcion = null, tipos = repo.tiposEnMemoria(medico)))

            assertFalse("tipo_turno" in cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo), "el tipo 1 no viaja")
        }
    }

    @Test
    fun asignar_sin_opcion_sobre_un_horario_de_otro_tipo_manda_el_tipo_del_horario() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(702, "09:30", 26)}]}""")

            repo.registrarTurno(turnoComoLoArmaElAsignar(slotDe(26), opcion = null, tipos = null))

            assertEquals(26, cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo).getValue("tipo_turno").jsonPrimitive.int)
        }
    }

    @Test
    fun con_el_servidor_viejo_asignar_queda_como_hoy_aunque_se_intente_una_opcion() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 403 to """{"ok":false,"codigo":"sin_permiso","mensaje":"No"}"""
            repo.refrescarTiposDelMedico(medico)
            turnos.responderA("POST", "turnos", 200, """{"ok":true,"turnos":[${turnoJson(703, "09:30", 1)}]}""")

            repo.registrarTurno(turnoComoLoArmaElAsignar(slotDe(1), opcion = 27, tipos = repo.tiposEnMemoria(medico)))

            assertFalse("tipo_turno" in cuerpo(turnos.pedidosA("/api/salud360/turnos").single().cuerpo))
        }
    }

    @Test
    fun el_sobreturno_con_opcion_manda_el_codigo_de_la_opcion() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to tiposJson
            repo.refrescarTiposDelMedico(medico)
            turnos.responderA("POST", "turnos/sobreturno", 200, """{"ok":true,"turno":${turnoJson(704, "09:30", 28)}}""")

            val t = turnoComoLoArmaElAsignar(slotDe(1), opcion = 28, tipos = repo.tiposEnMemoria(medico)).copy(sobreturno = true)
            val r = repo.registrarSobreturno(t)

            assertTrue(r is ResultadoTurno.Ok)
            assertEquals(28, cuerpo(turnos.pedidosA("/api/salud360/turnos/sobreturno").single().cuerpo).getValue("tipo_turno").jsonPrimitive.int)
        }
    }

    @Test
    fun un_422_tipo_invalido_llega_con_el_mensaje_del_servidor_tal_cual() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            val mensaje = "El tipo elegido no corresponde al horario de ese día."
            turnos.responderA("POST", "turnos", 422, """{"ok":false,"codigo":"tipo_invalido","mensaje":"$mensaje"}""")
            turnos.responderA("POST", "turnos/sobreturno", 422, """{"ok":false,"codigo":"tipo_invalido","mensaje":"$mensaje"}""")
            val t = turnoComoLoArmaElAsignar(slotDe(1), opcion = 27, tipos = tiposEsperados)

            assertEquals(ResultadoTurno.Error(mensaje), repo.registrarTurno(t))
            assertEquals(ResultadoTurno.Error(mensaje), repo.registrarSobreturno(t.copy(sobreturno = true)))
        }
    }

    // ---- horarios: el cuerpo de POST horarios con el tipo del selector ----

    private fun horarioNuevo(codigo: Int) = HorarioMedico(
        "local-h", medico, consultorio, DayOfWeek.TUESDAY, LocalTime(9, 0),
        tipoTurno = TipoTurno.porCodigo(codigo), tipoCodigo = TipoTurno.codigoSiNoEsDelEnum(codigo),
    )

    @Test
    fun el_alta_de_un_horario_manda_el_tipo_elegido_en_el_selector() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.responderA("POST", "horarios", 201, """{"ok":true,"id":55}""")

            assertTrue(repo.agregarHorario(horarioNuevo(26)))

            val enviado = cuerpo(turnos.pedidosA("/api/salud360/horarios").single().cuerpo)
            assertEquals(26, enviado.getValue("tipo_turno").jsonPrimitive.int)
            assertEquals(12, enviado.getValue("medico_id").jsonPrimitive.int)
            assertEquals(26, repo.horarios(medico).single().codigoDeTipo, "queda en la caché con su código real")
        }
    }

    @Test
    fun generar_un_rango_manda_el_tipo_elegido_en_cada_horario() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.responderA("POST", "horarios", 201, """{"ok":true,"id":56}""")

            val creados = repo.generarHorarios(medico, consultorio, DayOfWeek.TUESDAY, LocalTime(9, 0), LocalTime(10, 0), 30, tipoCodigo = 23)

            assertEquals(2, creados)
            val pedidos = turnos.pedidosA("/api/salud360/horarios")
            assertEquals(2, pedidos.size)
            assertTrue(pedidos.all { cuerpo(it.cuerpo).getValue("tipo_turno").jsonPrimitive.int == 23 })
        }
    }

    @Test
    fun un_horario_de_consulta_sigue_mandando_el_tipo_1() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            turnos.responderA("POST", "horarios", 201, """{"ok":true,"id":57}""")

            repo.agregarHorario(horarioNuevo(1))

            assertEquals(1, cuerpo(turnos.pedidosA("/api/salud360/horarios").single().cuerpo).getValue("tipo_turno").jsonPrimitive.int)
        }
    }

    @Test
    fun un_422_tipo_no_habilitado_llega_con_el_mensaje_del_servidor_tal_cual() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (repo, _) = escena(turnos)
            val mensaje = "El tipo de turno no está habilitado para este médico."
            turnos.responderA("POST", "horarios", 422, """{"ok":false,"codigo":"tipo_no_habilitado","mensaje":"$mensaje"}""")

            val e = assertFailsWith<TobbException> { repo.agregarHorario(horarioNuevo(24)) }

            assertEquals(422, e.status)
            assertEquals("tipo_no_habilitado", e.codigo)
            assertEquals(mensaje, e.message)
            assertTrue(repo.horarios(medico).isEmpty(), "no queda nada en la caché")
        }
    }

    // ---- armado ----

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-tipos-para-turnos", ".db").also { it.delete(); it.deleteOnExit() }
        return createDatabase(DriverFactory(archivo.absolutePath))
    }

    private suspend fun escena(turnos: TurnosFalsoDeAgenda, token: String = "tok-de-prueba"): Pair<TurnosRepository, AgendaTurnosOnline> {
        // El cliente va al turnos falso de `127.0.0.1`: nunca al de por omisión, que apunta a producción.
        val cliente = TurnosOnlineClient(turnos.url).apply { this.token = token }
        val db = baseTemporal()
        val agenda = AgendaTurnosOnline(db, cliente)
        return TurnosRepository(db, agenda) to agenda
    }
}
