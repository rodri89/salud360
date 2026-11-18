package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.auth.Medico
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El administrador habilita o quita historias clínicas de un médico y eso se guarda en turnos
 * (`PUT medicos/{id}/historias-clinicas`, lista exacta); la copia del dispositivo cambia recién con la confirmación.
 * Antes solo se escribía en el dispositivo y al volver a entrar turnos lo pisaba.
 *
 * Turnos falso en `127.0.0.1` (nunca el cliente por omisión, que apunta a producción) y base temporal. Todos los
 * ids, nombres y tokens son inventados.
 */
class AgendaTurnosOnlineHistoriasClinicasTest {

    private val ruta = "/api/salud360/medicos/7/historias-clinicas"

    @Test
    fun el_put_sale_a_la_ruta_con_metodo_token_y_la_lista_exacta() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.respuestaDeHistoriasClinicas = 200 to """{"ok":true,"medico_id":7,"historias_clinicas":["pediatria","clinica"]}"""

            agenda.guardarHistoriasClinicas("tobb-m7", listOf("pediatria", "clinica"))

            val pedidos = turnos.pedidosA(ruta)
            assertEquals(1, pedidos.size)
            assertEquals("PUT", pedidos[0].metodo)
            assertEquals(TOKEN, pedidos[0].token, "lleva el token de la sesión (nunca uno real)")
            assertEquals("""{"historias_clinicas":["pediatria","clinica"]}""", pedidos[0].cuerpo)
        }
    }

    @Test
    fun la_lista_vacia_viaja_en_el_cuerpo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, listOf("pediatria"))
            turnos.respuestaDeHistoriasClinicas = 200 to """{"ok":true,"medico_id":7,"historias_clinicas":[]}"""

            val r = agenda.guardarHistoriasClinicas("tobb-m7", emptyList())

            assertEquals("""{"historias_clinicas":[]}""", turnos.pedidosA(ruta).single().cuerpo, "«ninguna» es un pedido: tiene que viajar")
            assertEquals(emptyList(), r)
            assertEquals(emptyList(), assertNotNull(db.local(7)).especialidadesHc)
        }
    }

    @Test
    fun con_200_la_copia_toma_la_lista_de_turnos_y_queda_limpia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, listOf("clinica"), dirty = true)
            turnos.respuestaDeHistoriasClinicas = 200 to """{"ok":true,"medico_id":7,"historias_clinicas":["pediatria","clinica"]}"""

            val r = agenda.guardarHistoriasClinicas("tobb-m7", listOf("clinica", "pediatria"))

            assertEquals(listOf("pediatria", "clinica"), r, "devuelve la lista final que informó turnos")
            assertEquals(listOf("pediatria", "clinica"), assertNotNull(db.local(7)).especialidadesHc)
            assertTrue(db.authQueries.medicosDirty().lista { it.id }.isEmpty(), "el dueño es turnos: la copia no queda dirty")
        }
    }

    @Test
    fun la_copia_sigue_lo_que_informo_turnos_aunque_difiera_del_pedido() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, listOf("clinica"))
            turnos.respuestaDeHistoriasClinicas = 200 to """{"ok":true,"medico_id":7,"historias_clinicas":["pediatria"]}"""

            val r = agenda.guardarHistoriasClinicas("tobb-m7", listOf("clinica", "gineco"))

            assertEquals(listOf("pediatria"), r)
            assertEquals(listOf("pediatria"), assertNotNull(db.local(7)).especialidadesHc, "se guarda lo de turnos, no lo que se pidió")
        }
    }

    @Test
    fun guardar_las_historias_clinicas_no_toca_ningun_otro_campo_del_medico() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            val antes = Medico(
                "tobb-m7", "tobb-mu7", "Medico7", "Prueba",
                telefono = "000-0000", mail = "medico7@ejemplo.test", sexo = "F", consultorioId = "tobb-c5", especialidadId = "tobb-e2",
                especialidadesHc = listOf("clinica"), tieneTurnos = true, visibleEnTurnos = false, activo = true,
            )
            db.authQueries.upsertMedico(antes.toRow(1L, dirty = false))
            turnos.respuestaDeHistoriasClinicas = 200 to """{"ok":true,"medico_id":7,"historias_clinicas":["clinica","pediatria"]}"""

            agenda.guardarHistoriasClinicas("tobb-m7", listOf("clinica", "pediatria"))

            assertEquals(antes.copy(especialidadesHc = listOf("clinica", "pediatria")), assertNotNull(db.local(7)))
        }
    }

    @Test
    fun los_errores_de_turnos_lanzan_con_su_mensaje_y_la_copia_no_cambia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, listOf("clinica"))
            db.sembrar(8, listOf("gineco"))

            val casos = listOf(
                CasoDeError(403, """{"ok":false,"codigo":"sin_permiso","mensaje":"Solo el administrador puede cambiar las historias clínicas"}""", "sin_permiso"),
                CasoDeError(404, """{"ok":false,"codigo":"no_encontrado","mensaje":"El médico no existe"}""", "no_encontrado"),
                CasoDeError(422, """{"ok":false,"codigo":"datos_invalidos","mensaje":"Códigos desconocidos: xx. Códigos válidos: pediatria, clinica."}""", "datos_invalidos"),
                CasoDeError(500, """{"ok":false,"mensaje":"Error interno"}""", null),
                // Un 200 que no dice en qué quedó la lista no confirma nada: no se toma por buena.
                CasoDeError(200, """{"ok":true,"medico_id":7}""", "respuesta"),
            )

            casos.forEach { caso ->
                turnos.respuestaDeHistoriasClinicas = caso.estado to caso.cuerpo
                val e = assertFailsWith<TobbException>("${caso.estado} ${caso.cuerpo}") {
                    agenda.guardarHistoriasClinicas("tobb-m7", listOf("pediatria"))
                }
                assertEquals(caso.codigo, e.codigo, "el motivo que informa turnos llega tal cual")
                if (caso.estado == 422) {
                    assertEquals("Códigos desconocidos: xx. Códigos válidos: pediatria, clinica.", e.message, "el mensaje del servidor llega tal cual")
                }

                assertEquals(listOf("clinica"), assertNotNull(db.local(7)).especialidadesHc, "después del error ${caso.estado} la copia no cambia")
                assertEquals(listOf("gineco"), assertNotNull(db.local(8)).especialidadesHc)
            }
        }
    }

    @Test
    fun sin_red_lanza_y_la_copia_no_cambia() = runBlocking {
        val turnos = TurnosFalsoDeAgenda()
        val (db, agenda) = escena(turnos)
        db.sembrar(7, listOf("clinica"))
        turnos.close()

        val e = assertFailsWith<TobbException> { agenda.guardarHistoriasClinicas("tobb-m7", listOf("pediatria")) }

        assertEquals(0, e.status, "es un fallo de red, no una respuesta de la API")
        assertEquals("red", e.codigo)
        assertEquals(listOf("clinica"), assertNotNull(db.local(7)).especialidadesHc)
    }

    @Test
    fun si_la_web_no_tiene_la_ruta_lo_dice_en_castellano() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, listOf("clinica"))

            listOf(404 to """{"ok":false,"mensaje":"Not Found"}""", 405 to """{"ok":false,"mensaje":"Method Not Allowed"}""").forEach { (estado, cuerpo) ->
                turnos.respuestaDeHistoriasClinicas = estado to cuerpo
                val e = assertFailsWith<TobbException>("$estado sin codigo") {
                    agenda.guardarHistoriasClinicas("tobb-m7", listOf("pediatria"))
                }
                assertEquals(estado, e.status)
                assertEquals("sin_ruta", e.codigo)
                val texto = e.message.orEmpty()
                assertTrue("todavía no permite cambiar las historias clínicas de un médico" in texto, texto)
                assertTrue("falta actualizar turnosonlinebb" in texto, texto)
                assertEquals(listOf("clinica"), assertNotNull(db.local(7)).especialidadesHc, "con la web vieja la copia no cambia")
            }
        }
    }

    @Test
    fun si_el_medico_no_esta_en_el_dispositivo_devuelve_la_lista_sin_inventar_la_ficha() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            turnos.respuestaDeHistoriasClinicas = 200 to """{"ok":true,"medico_id":99,"historias_clinicas":["pediatria"]}"""

            val r = agenda.guardarHistoriasClinicas("tobb-m99", listOf("pediatria"))

            assertEquals(listOf("pediatria"), r)
            assertNull(db.local(99))
            assertTrue(db.authQueries.medicos().lista { it.id }.isEmpty(), "sin copia local no se inventa una ficha")
        }
    }

    @Test
    fun un_medico_que_no_es_de_turnos_no_sale_a_la_red() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)

            val e = assertFailsWith<TobbException> { agenda.guardarHistoriasClinicas("medico-local", listOf("pediatria")) }

            assertEquals("medico", e.codigo)
            assertTrue(turnos.pedidos().isEmpty(), "no se manda nada a turnos")
        }
    }

    @Test
    fun sin_conexion_con_turnos_el_repositorio_no_guarda() = runBlocking {
        val repo = TurnosRepository(baseTemporal())

        val e = assertFailsWith<IllegalStateException> { repo.guardarHistoriasClinicas("tobb-m7", listOf("pediatria")) }

        assertEquals("Cambiar las historias clínicas necesita conexión con turnos.", e.message)
    }

    // ---- Armado ----

    private data class Escena(val db: Salud360Db, val agenda: AgendaTurnosOnline)

    /** Un caso de error del `PUT`: el estado y el cuerpo que contesta turnos, y el `codigo` que debe llegar. */
    private class CasoDeError(val estado: Int, val cuerpo: String, val codigo: String?)

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-admin-hc", ".db").also { it.delete(); it.deleteOnExit() }
        return createDatabase(DriverFactory(archivo.absolutePath))
    }

    private suspend fun escena(turnos: TurnosFalsoDeAgenda): Escena {
        // El cliente va al turnos falso de `127.0.0.1`: nunca al de por omisión, que apunta a producción.
        val cliente = TurnosOnlineClient(turnos.url).apply { token = TOKEN }
        val db = baseTemporal()
        return Escena(db, AgendaTurnosOnline(db, cliente))
    }

    private suspend fun Salud360Db.local(tobbId: Long): Medico? =
        authQueries.medicoPorId("tobb-m$tobbId").uno { it.toModel() }

    private suspend fun Salud360Db.sembrar(tobbId: Long, especialidadesHc: List<String>, dirty: Boolean = false) {
        authQueries.upsertMedico(
            Medico("tobb-m$tobbId", "tobb-mu$tobbId", "Medico$tobbId", "Prueba", especialidadesHc = especialidadesHc).toRow(1L, dirty = dirty),
        )
    }

    private companion object {
        const val TOKEN = "tok-de-prueba"
    }
}
