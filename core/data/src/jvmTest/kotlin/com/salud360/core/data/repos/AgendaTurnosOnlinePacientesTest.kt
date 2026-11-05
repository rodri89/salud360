package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El alta de un paciente en turnos (`POST pacientes`) no puede dejar dos fichas con el mismo DNI en el dispositivo:
 * si turnos contesta que el DNI ya existía, la ficha que escribió el médico se reemplaza por la de turnos y el
 * paciente queda en la lista del médico.
 *
 * Turnos falso en `127.0.0.1` (nunca el cliente por omisión, que apunta a producción) y base temporal. Todos los
 * ids, DNI, nombres y tokens son inventados.
 */
class AgendaTurnosOnlinePacientesTest {

    private val medico = "tobb-m1"

    @Test
    fun si_el_dni_ya_existia_en_turnos_queda_una_sola_ficha_con_los_datos_de_turnos_y_asociada_al_medico() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda, repo) = escena(turnos)
            repo.guardar(Paciente("uuid-1", "30111222", "x", "y", telefono = "111", mail = "medico@ejemplo.test"), vincularA = medico)
            turnos.respuestaDeAltaDePaciente = 200 to respuestaAlta(creado = false, id = 7, nombre = "Ana Maria", telefono = "222")

            val r = agenda.guardarPacienteRemoto("uuid-1", medico, consultorioId = null)

            assertTrue(r.yaExistia)
            assertEquals("tobb-p7", r.id)
            assertEquals("Ana Maria", r.paciente.nombre, "mandan los datos de turnos")
            assertEquals("222", r.paciente.telefono)
            assertEquals("medico@ejemplo.test", r.paciente.mail, "turnos no trae mail: no se pisa con vacío")
            assertEquals(listOf("tobb-p7"), db.pacientesQueries.pacientes().lista { it.id }, "una sola ficha en el dispositivo")
            assertNull(repo.porId("uuid-1"))
            assertEquals(listOf("tobb-p7"), repo.observarPagina(medico, "", 50).first().map { it.id }, "aparece en la lista del médico")
            assertEquals(r.paciente, repo.porId("tobb-p7"))
            assertEquals(1, turnos.pedidosA("/api/salud360/pacientes").size)
        }
    }

    @Test
    fun el_alta_manda_los_datos_del_medico_con_su_token() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda, repo) = escena(turnos)
            repo.guardar(Paciente("uuid-1", "30111222", "Ana", "Prueba", telefono = "111"), vincularA = medico)
            turnos.respuestaDeAltaDePaciente = 200 to respuestaAlta(creado = false, id = 7)

            agenda.guardarPacienteRemoto("uuid-1", medico, consultorioId = null)

            val pedido = turnos.pedidosA("/api/salud360/pacientes").single()
            assertEquals("POST", pedido.metodo)
            assertEquals(TOKEN, pedido.token)
            assertTrue("\"dni\":\"30111222\"" in pedido.cuerpo, pedido.cuerpo)
            assertTrue("\"medico_id\":1" in pedido.cuerpo, pedido.cuerpo)
        }
    }

    @Test
    fun si_turnos_lo_creo_se_conserva_la_ficha_local_y_se_anota_el_id_de_turnos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda, repo) = escena(turnos)
            repo.guardar(Paciente("uuid-1", "30111222", "ANA", "PRUEBA", telefono = "111"), vincularA = medico)
            turnos.respuestaDeAltaDePaciente = 201 to respuestaAlta(creado = true, id = 7, nombre = "Ana", telefono = "111")

            val r = agenda.guardarPacienteRemoto("uuid-1", medico, consultorioId = null)

            assertFalse(r.yaExistia)
            assertEquals("uuid-1", r.id, "comportamiento de siempre: la ficha local se queda")
            assertEquals(listOf("uuid-1"), db.pacientesQueries.pacientes().lista { it.id })
            val extra = db.pacientesQueries.extrasDePaciente("uuid-1", AgendaTurnosOnline.EXTRA_TURNOS).lista { it }.single()
            assertEquals(AgendaTurnosOnline.EXTRA_TOBB_ID, extra.clave)
            assertEquals("7", extra.valor)
            assertEquals(1L, extra.dirty, "el vínculo viaja por la sincronización normal")
            assertEquals(listOf("uuid-1"), repo.observarPagina(medico, "", 50).first().map { it.id })
        }
    }

    @Test
    fun si_el_post_falla_queda_la_ficha_local_y_el_reintento_la_fusiona() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda, repo) = escena(turnos)
            repo.guardar(Paciente("uuid-1", "30111222", "ANA", "PRUEBA", telefono = "111"), vincularA = medico)
            turnos.respuestaDeAltaDePaciente = 503 to """{"ok":false,"mensaje":"Servicio no disponible"}"""

            assertFailsWith<TobbException> { agenda.guardarPacienteRemoto("uuid-1", medico, consultorioId = null) }

            assertEquals(listOf("uuid-1"), db.pacientesQueries.pacientes().lista { it.id }, "sin fusión: sigue la ficha local")
            assertTrue(db.pacientesQueries.extrasDePaciente("uuid-1", AgendaTurnosOnline.EXTRA_TURNOS).lista { it }.isEmpty())
            assertEquals(listOf("uuid-1"), repo.observarPagina(medico, "", 50).first().map { it.id })

            turnos.respuestaDeAltaDePaciente = 200 to respuestaAlta(creado = false, id = 7, nombre = "Ana Maria")
            val r = agenda.guardarPacienteRemoto("uuid-1", medico, consultorioId = null)

            assertTrue(r.yaExistia)
            assertEquals(listOf("tobb-p7"), db.pacientesQueries.pacientes().lista { it.id }, "el reintento deja una sola ficha")
            assertEquals(listOf("tobb-p7"), repo.observarPagina(medico, "", 50).first().map { it.id })
            assertEquals(2, turnos.pedidosA("/api/salud360/pacientes").size)
        }
    }

    @Test
    fun sin_conexion_con_turnos_no_se_pierde_la_ficha_local() = runBlocking {
        val turnos = TurnosFalsoDeAgenda()
        val (db, agenda, repo) = escena(turnos)
        repo.guardar(Paciente("uuid-1", "30111222", "ANA", "PRUEBA"), vincularA = medico)
        turnos.close()

        val e = assertFailsWith<TobbException> { agenda.guardarPacienteRemoto("uuid-1", medico, consultorioId = null) }

        assertEquals("red", e.codigo)
        assertEquals(listOf("uuid-1"), db.pacientesQueries.pacientes().lista { it.id })
        assertEquals(listOf("uuid-1"), repo.observarPagina(medico, "", 50).first().map { it.id })
    }

    @Test
    fun el_alta_de_un_dni_que_ya_esta_en_el_dispositivo_como_ficha_de_turnos_manda_el_post_y_no_pisa_sus_datos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda, repo) = escena(turnos)
            // La ficha de otro médico que el dispositivo guardó al buscar en la agenda: sin vínculo con este médico.
            db.pacientesQueries.upsertPaciente(Paciente("tobb-p7", "30111222", "ANA MARIA", "PRUEBA", telefono = "222").toRow(1L, dirty = false))
            val guardado = repo.guardar(Paciente("uuid-1", "30111222", "otro", "otro", telefono = "111"), vincularA = medico)
            assertEquals("tobb-p7", guardado.id, "el chequeo local por DNI reutiliza la ficha")
            turnos.respuestaDeAltaDePaciente = 200 to respuestaAlta(creado = false, id = 7, nombre = "Ana Maria", telefono = "222")

            val r = agenda.guardarPacienteRemoto(guardado.id, medico, consultorioId = null, esAlta = true)

            assertTrue(r.yaExistia)
            assertEquals("tobb-p7", r.id)
            assertEquals("222", r.paciente.telefono)
            assertEquals(listOf("tobb-p7"), db.pacientesQueries.pacientes().lista { it.id })
            assertEquals(listOf("POST"), turnos.pedidosA("/api/salud360/pacientes").map { it.metodo }, "el POST es lo que asocia al médico en turnos")
            assertTrue(turnos.pedidos().none { it.metodo == "PUT" }, "no se manda un PUT que pise los datos de turnos")
            assertEquals(listOf("tobb-p7"), repo.observarPagina(medico, "", 50).first().map { it.id })
        }
    }

    @Test
    fun editar_una_ficha_que_ya_conoce_turnos_sigue_siendo_un_put() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda, repo) = escena(turnos)
            db.pacientesQueries.upsertPaciente(Paciente("tobb-p7", "30111222", "ANA", "PRUEBA", telefono = "333").toRow(1L, dirty = false))
            db.pacientesQueries.upsertMedicoPaciente(MedicoPaciente(medico, "tobb-p7").toRow(1L, dirty = false))
            turnos.respuestaDeEdicionDePaciente = 200 to """{"ok":true}"""

            val r = agenda.guardarPacienteRemoto("tobb-p7", medico, consultorioId = null)

            assertFalse(r.yaExistia)
            assertEquals("tobb-p7", r.id)
            assertEquals("333", assertNotNull(repo.porId("tobb-p7")).telefono)
            val pedido = turnos.pedidos().single { it.ruta.startsWith("/api/salud360/pacientes") }
            assertEquals("PUT", pedido.metodo)
            assertEquals("/api/salud360/pacientes/7", pedido.ruta)
        }
    }

    // ---- armado ----

    private data class Escena(val db: Salud360Db, val agenda: AgendaTurnosOnline, val repo: PacientesRepository)

    private suspend fun escena(turnos: TurnosFalsoDeAgenda): Escena {
        val archivo = File.createTempFile("salud360-alta-paciente", ".db").also { it.delete(); it.deleteOnExit() }
        val db = createDatabase(DriverFactory(archivo.absolutePath))
        // El cliente va al turnos falso de `127.0.0.1`: nunca al de por omisión, que apunta a producción.
        val cliente = TurnosOnlineClient(turnos.url).apply { token = TOKEN }
        return Escena(db, AgendaTurnosOnline(db, cliente), PacientesRepository(db))
    }

    /** `POST pacientes` tal como lo contesta turnos: la ficha y si la acaba de crear. */
    private fun respuestaAlta(creado: Boolean, id: Long, nombre: String = "Ana", telefono: String? = null): String {
        val tel = telefono?.let { ""","telefono":"$it"""" }.orEmpty()
        return """{"ok":true,"creado":$creado,"paciente":{"id":$id,"nombre":"$nombre","apellido":"Prueba","dni":"30111222"$tel}}"""
    }

    private companion object {
        const val TOKEN = "tok-de-prueba"
    }
}
