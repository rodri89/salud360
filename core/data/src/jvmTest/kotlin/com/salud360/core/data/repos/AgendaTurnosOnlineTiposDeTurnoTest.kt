package com.salud360.core.data.repos

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.turnos.CondicionTipoTurno
import com.salud360.core.model.turnos.OpcionTipoTurno
import com.salud360.core.model.turnos.TipoTurnoCatalogo
import com.salud360.core.model.turnos.TipoTurnoDelMedico
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Los tipos de turno que el administrador ve y configura desde el panel: el catálogo (`GET`/`POST tipos-turno`) y los
 * habilitados de cada médico con su condición y sus opciones (`GET`/`PUT medicos/{n}/tipos-turno`). Turnos es el dueño
 * del dato: no se escribe nada en la base del dispositivo, así que acá se mira qué sale a la red y cómo se traduce lo
 * que contesta.
 *
 * Turnos falso en `127.0.0.1` (nunca el cliente por omisión, que apunta a producción). Ids, nombres y tokens inventados.
 */
class AgendaTurnosOnlineTiposDeTurnoTest {

    private val rutaCatalogo = "/api/salud360/tipos-turno"
    private val rutaDelMedico = "/api/salud360/medicos/12/tipos-turno"

    private val catalogoJson = """{"ok":true,"tipos":[{"id":26,"nombre":"Consulta","activo":1},{"id":27,"nombre":"Ecografía","activo":1},{"id":28,"nombre":"Viejo","activo":0}]}"""

    // ---- GET tipos-turno ----

    @Test
    fun el_catalogo_sale_con_get_token_y_sin_cuerpo_y_se_decodifica_con_los_inactivos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeCatalogoDeTipos = 200 to catalogoJson

            val r = agenda.tiposDeTurno()

            val pedido = turnos.pedidosA(rutaCatalogo).single()
            assertEquals("GET", pedido.metodo)
            assertEquals(TOKEN, pedido.token)
            assertEquals("", pedido.cuerpo)
            assertEquals(
                listOf(TipoTurnoCatalogo(26, "Consulta", true), TipoTurnoCatalogo(27, "Ecografía", true), TipoTurnoCatalogo(28, "Viejo", false)),
                r,
            )
        }
    }

    @Test
    fun un_catalogo_vacio_es_valido() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeCatalogoDeTipos = 200 to """{"ok":true,"tipos":[]}"""
            assertEquals(emptyList(), agenda.tiposDeTurno())
        }
    }

    // ---- POST tipos-turno ----

    @Test
    fun el_alta_sale_con_post_token_y_el_nombre_recortado() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeAltaDeTipo = 201 to """{"ok":true,"tipo":{"id":29,"nombre":"Control","activo":1}}"""

            val creado = agenda.crearTipoTurno("  Control  ")

            val pedido = turnos.pedidosA(rutaCatalogo).single()
            assertEquals("POST", pedido.metodo)
            assertEquals(TOKEN, pedido.token)
            assertEquals("""{"nombre":"Control"}""", pedido.cuerpo)
            assertEquals(TipoTurnoCatalogo(29, "Control", true), creado)
        }
    }

    @Test
    fun un_nombre_repetido_lanza_con_el_mensaje_del_servidor() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeAltaDeTipo = 409 to
                """{"ok":false,"codigo":"nombre_repetido","mensaje":"Ya existe un tipo de turno con ese nombre","tipo":{"id":26,"nombre":"Consulta","activo":1}}"""

            val e = assertFailsWith<TobbException> { agenda.crearTipoTurno("Consulta") }

            assertEquals(409, e.status)
            assertEquals("nombre_repetido", e.codigo)
            assertEquals("Ya existe un tipo de turno con ese nombre", e.message)
        }
    }

    @Test
    fun un_nombre_invalido_llega_con_el_motivo_del_servidor() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeAltaDeTipo = 422 to """{"ok":false,"codigo":"datos_invalidos","mensaje":"El nombre no puede tener más de 60 caracteres"}"""

            val e = assertFailsWith<TobbException> { agenda.crearTipoTurno("x") }

            assertEquals("datos_invalidos", e.codigo)
            assertEquals("El nombre no puede tener más de 60 caracteres", e.message)
        }
    }

    // ---- GET medicos/{n}/tipos-turno ----

    @Test
    fun los_tipos_del_medico_salen_a_la_ruta_del_numero_de_turnos_y_se_decodifican() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to
                """{"ok":true,"medico_id":12,"tipos":[
                    {"id":26,"nombre":"Consulta","primer_control":null,"opciones":[{"id":27,"nombre":"Ecografía"}]},
                    {"id":29,"nombre":"Control","primer_control":1,"opciones":[]},
                    {"id":30,"nombre":"Seguimiento","primer_control":0,"opciones":[]}
                ]}"""

            val r = agenda.tiposDelMedico("tobb-m12")

            val pedido = turnos.pedidosA(rutaDelMedico).single()
            assertEquals("GET", pedido.metodo)
            assertEquals(TOKEN, pedido.token)
            assertEquals("", pedido.cuerpo)
            assertEquals(
                listOf(
                    TipoTurnoDelMedico(26, "Consulta", CondicionTipoTurno.SIEMPRE, listOf(OpcionTipoTurno(27, "Ecografía"))),
                    TipoTurnoDelMedico(29, "Control", CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, emptyList()),
                    TipoTurnoDelMedico(30, "Seguimiento", CondicionTipoTurno.SOLO_SEGUIMIENTO, emptyList()),
                ),
                r,
            )
        }
    }

    @Test
    fun un_medico_sin_tipos_habilitados_devuelve_la_lista_vacia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeTiposDelMedico = 200 to """{"ok":true,"medico_id":12,"tipos":[]}"""
            assertEquals(emptyList(), agenda.tiposDelMedico("tobb-m12"))
        }
    }

    // ---- PUT medicos/{n}/tipos-turno ----

    @Test
    fun el_put_sale_con_metodo_token_y_la_lista_exacta() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeGuardarTiposDelMedico = 200 to """{"ok":true,"medico_id":12,"tipos":[],"avisos":[]}"""

            agenda.guardarTiposDelMedico(
                "tobb-m12",
                listOf(
                    ConfigTipoTurno(26, CondicionTipoTurno.SIEMPRE, listOf(27, 28)),
                    ConfigTipoTurno(29, CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, null),
                    ConfigTipoTurno(30, CondicionTipoTurno.SOLO_SEGUIMIENTO, emptyList()),
                ),
            )

            val pedido = turnos.pedidosA(rutaDelMedico).single()
            assertEquals("PUT", pedido.metodo)
            assertEquals(TOKEN, pedido.token)
            assertEquals(
                """{"tipos":[{"id":26,"primer_control":null,"opciones":[27,28]},{"id":29,"primer_control":1},{"id":30,"primer_control":0,"opciones":[]}]}""",
                pedido.cuerpo,
            )
        }
    }

    @Test
    fun la_lista_vacia_viaja_en_el_cuerpo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeGuardarTiposDelMedico = 200 to """{"ok":true,"medico_id":12,"tipos":[],"avisos":[]}"""

            val r = agenda.guardarTiposDelMedico("tobb-m12", emptyList())

            assertEquals("""{"tipos":[]}""", turnos.pedidosA(rutaDelMedico).single().cuerpo, "«ninguno» es un pedido: tiene que viajar")
            assertEquals(GuardadoTipos(emptyList(), emptyList()), r)
        }
    }

    @Test
    fun con_200_devuelve_los_tipos_que_informo_turnos_y_los_avisos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeGuardarTiposDelMedico = 200 to
                """{"ok":true,"medico_id":12,"tipos":[{"id":29,"nombre":"Control","primer_control":0,"opciones":[{"id":27,"nombre":"Ecografía"}]}],
                    "avisos":["El tipo Consulta sigue teniendo 17 horarios activos."]}"""

            val r = agenda.guardarTiposDelMedico("tobb-m12", listOf(ConfigTipoTurno(29, CondicionTipoTurno.SOLO_SEGUIMIENTO, listOf(27))))

            assertEquals(
                GuardadoTipos(
                    tipos = listOf(TipoTurnoDelMedico(29, "Control", CondicionTipoTurno.SOLO_SEGUIMIENTO, listOf(OpcionTipoTurno(27, "Ecografía")))),
                    avisos = listOf("El tipo Consulta sigue teniendo 17 horarios activos."),
                ),
                r,
                "lo que queda es lo que informó turnos, no lo pedido",
            )
        }
    }

    @Test
    fun una_respuesta_sin_avisos_no_tiene_avisos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeGuardarTiposDelMedico = 200 to """{"ok":true,"medico_id":12,"tipos":[]}"""
            assertEquals(emptyList(), agenda.guardarTiposDelMedico("tobb-m12", emptyList()).avisos)
        }
    }

    @Test
    fun un_422_del_put_llega_con_el_motivo_tal_cual() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            turnos.respuestaDeGuardarTiposDelMedico = 422 to """{"ok":false,"codigo":"datos_invalidos","mensaje":"El tipo 99 no existe en el catálogo."}"""

            val e = assertFailsWith<TobbException> { agenda.guardarTiposDelMedico("tobb-m12", listOf(ConfigTipoTurno(99, CondicionTipoTurno.SIEMPRE, null))) }

            assertEquals("datos_invalidos", e.codigo)
            assertEquals("El tipo 99 no existe en el catálogo.", e.message)
        }
    }

    // ---- Los errores de las cuatro operaciones ----

    @Test
    fun los_errores_de_turnos_lanzan_con_su_codigo_y_su_mensaje() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            val casos = listOf(
                CasoDeError(403, """{"ok":false,"codigo":"sin_permiso","mensaje":"Solo el administrador puede ver los tipos de turno"}""", "sin_permiso", "Solo el administrador puede ver los tipos de turno"),
                CasoDeError(404, """{"ok":false,"codigo":"no_encontrado","mensaje":"El médico no existe"}""", "no_encontrado", "El médico no existe"),
                CasoDeError(401, """{"ok":false,"codigo":"sin_token","mensaje":"Falta iniciar sesión"}""", "sin_token", "Falta iniciar sesión"),
                CasoDeError(422, """{"ok":false,"codigo":"datos_invalidos","mensaje":"Motivo del 422"}""", "datos_invalidos", "Motivo del 422"),
                CasoDeError(500, """{"ok":false,"mensaje":"Error interno"}""", null, "Error interno"),
                // Un 200 que no trae la lista no confirma nada: no se toma por buena.
                CasoDeError(200, """{"ok":true}""", "respuesta", null),
            )

            operaciones().forEach { op ->
                casos.forEach { caso ->
                    op.fijar(turnos, caso.estado to caso.cuerpo)
                    val e = assertFailsWith<TobbException>("${op.nombre} ${caso.estado}") { op.llamar(agenda) }
                    assertEquals(caso.codigo, e.codigo, "${op.nombre} ${caso.estado}")
                    if (caso.mensaje != null) assertEquals(caso.mensaje, e.message, "${op.nombre} ${caso.estado}: el mensaje del servidor llega tal cual")
                }
            }
        }
    }

    @Test
    fun sin_red_lanzan_con_codigo_red() = runBlocking {
        val turnos = TurnosFalsoDeAgenda()
        val agenda = escena(turnos)
        turnos.close()

        operaciones().forEach { op ->
            val e = assertFailsWith<TobbException>(op.nombre) { op.llamar(agenda) }
            assertEquals(0, e.status, "${op.nombre}: es un fallo de red, no una respuesta de la API")
            assertEquals("red", e.codigo, op.nombre)
        }
    }

    @Test
    fun si_la_web_no_tiene_la_ruta_lo_dice_en_castellano() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)

            operaciones().forEach { op ->
                listOf(404 to """{"ok":false,"mensaje":"Not Found"}""", 405 to """{"ok":false,"mensaje":"Method Not Allowed"}""").forEach { (estado, cuerpo) ->
                    op.fijar(turnos, estado to cuerpo)
                    val e = assertFailsWith<TobbException>("${op.nombre} $estado sin codigo") { op.llamar(agenda) }
                    assertEquals(estado, e.status, op.nombre)
                    assertEquals("sin_ruta", e.codigo, op.nombre)
                    val texto = e.message.orEmpty()
                    assertTrue("todavía no permite ${op.frase}" in texto, texto)
                    assertTrue("falta actualizar turnosonlinebb" in texto, texto)
                }
            }
        }
    }

    @Test
    fun por_omision_el_servidor_falso_no_tiene_las_rutas_y_la_app_lo_traduce() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)
            operaciones().forEach { op ->
                val e = assertFailsWith<TobbException>(op.nombre) { op.llamar(agenda) }
                assertEquals("sin_ruta", e.codigo, op.nombre)
            }
        }
    }

    // ---- Médico que no es de turnos ----

    @Test
    fun un_medico_que_no_es_de_turnos_no_sale_a_la_red() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val agenda = escena(turnos)

            val ver = assertFailsWith<TobbException> { agenda.tiposDelMedico("medico-local") }
            val guardar = assertFailsWith<TobbException> { agenda.guardarTiposDelMedico("medico-local", emptyList()) }

            assertEquals("medico", ver.codigo)
            assertEquals("medico", guardar.codigo)
            assertTrue(turnos.pedidos().isEmpty(), "no se manda nada a turnos")
        }
    }

    // ---- Sin conexión con turnos ----

    @Test
    fun sin_conexion_con_turnos_el_repositorio_no_hace_nada() = runBlocking {
        val repo = TurnosRepository(baseTemporal())

        assertEquals("Ver los tipos de turno necesita conexión con turnos.", assertFailsWith<IllegalStateException> { repo.tiposDeTurno() }.message)
        assertEquals("Crear un tipo de turno necesita conexión con turnos.", assertFailsWith<IllegalStateException> { repo.crearTipoTurno("Control") }.message)
        assertEquals("Ver los tipos de turno de un médico necesita conexión con turnos.", assertFailsWith<IllegalStateException> { repo.tiposDelMedico("tobb-m12") }.message)
        assertEquals("Cambiar los tipos de turno necesita conexión con turnos.", assertFailsWith<IllegalStateException> { repo.guardarTiposDelMedico("tobb-m12", emptyList()) }.message)
    }

    // ---- Armado ----

    /** Un caso de error: el estado y el cuerpo que contesta turnos, y el `codigo` (y el `mensaje`, si se compara) que debe llegar. */
    private class CasoDeError(val estado: Int, val cuerpo: String, val codigo: String?, val mensaje: String?)

    /** Una de las cuatro operaciones: cómo se configura su respuesta en el servidor falso y cómo se llama. */
    private class Operacion(
        val nombre: String,
        val frase: String,
        val fijar: (TurnosFalsoDeAgenda, Pair<Int, String>) -> Unit,
        val llamar: suspend (AgendaTurnosOnline) -> Any?,
    )

    private fun operaciones() = listOf(
        Operacion("GET tipos-turno", "ver los tipos de turno", { t, r -> t.respuestaDeCatalogoDeTipos = r }) { it.tiposDeTurno() },
        Operacion("POST tipos-turno", "crear tipos de turno", { t, r -> t.respuestaDeAltaDeTipo = r }) { it.crearTipoTurno("Control") },
        Operacion("GET medicos/12/tipos-turno", "ver los tipos de turno de un médico", { t, r -> t.respuestaDeTiposDelMedico = r }) { it.tiposDelMedico("tobb-m12") },
        Operacion("PUT medicos/12/tipos-turno", "cambiar los tipos de turno de un médico", { t, r -> t.respuestaDeGuardarTiposDelMedico = r }) {
            it.guardarTiposDelMedico("tobb-m12", listOf(ConfigTipoTurno(26, CondicionTipoTurno.SIEMPRE, null)))
        },
    )

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-admin-tipos", ".db").also { it.delete(); it.deleteOnExit() }
        return createDatabase(DriverFactory(archivo.absolutePath))
    }

    private suspend fun escena(turnos: TurnosFalsoDeAgenda): AgendaTurnosOnline {
        // El cliente va al turnos falso de `127.0.0.1`: nunca al de por omisión, que apunta a producción.
        val cliente = TurnosOnlineClient(turnos.url).apply { token = TOKEN }
        return AgendaTurnosOnline(baseTemporal(), cliente)
    }

    private companion object {
        const val TOKEN = "tok-de-prueba"
    }
}
