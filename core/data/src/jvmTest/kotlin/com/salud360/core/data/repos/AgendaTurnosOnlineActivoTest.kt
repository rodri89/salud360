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
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Usuario
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El administrador activa o desactiva a un médico desde la pestaña Usuarios y eso se guarda en turnos
 * (`PUT medicos/{id}/activo`); la copia del dispositivo (médico y usuario) cambia recién con la confirmación.
 * Además, un médico desactivado deja de venir en `GET catalogos` pero sigue en `GET usuarios`: tiene que seguir en el
 * dispositivo para poder reactivarlo.
 *
 * Turnos falso en `127.0.0.1` (nunca el cliente por omisión, que apunta a producción) y base temporal. Todos los
 * ids, nombres y tokens son inventados.
 */
class AgendaTurnosOnlineActivoTest {

    private val ruta = "/api/salud360/medicos/7/activo"

    // ---- guardarActivoMedico ----

    @Test
    fun el_put_sale_a_la_ruta_con_metodo_token_y_el_booleano() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)

            turnos.respuestaDeActivo = 200 to """{"ok":true,"medico_id":7,"activo":false}"""
            agenda.guardarActivoMedico("tobb-m7", activo = false)
            turnos.respuestaDeActivo = 200 to """{"ok":true,"medico_id":7,"activo":true}"""
            agenda.guardarActivoMedico("tobb-m7", activo = true)

            val pedidos = turnos.pedidosA(ruta)
            assertEquals(2, pedidos.size)
            assertTrue(pedidos.all { it.metodo == "PUT" })
            assertTrue(pedidos.all { it.token == TOKEN }, "lleva el token de la sesión (nunca uno real)")
            assertEquals("""{"activo":false}""", pedidos[0].cuerpo, "false es un pedido: tiene que viajar")
            assertEquals("""{"activo":true}""", pedidos[1].cuerpo)
        }
    }

    @Test
    fun con_200_el_medico_y_su_usuario_toman_el_estado_de_turnos_y_quedan_limpios() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, activo = true, dirty = true)
            turnos.respuestaDeActivo = 200 to """{"ok":true,"medico_id":7,"activo":false}"""

            val r = agenda.guardarActivoMedico("tobb-m7", activo = false)

            assertEquals(false, r, "devuelve el estado final que informó turnos")
            assertEquals(false, assertNotNull(db.medico(7)).activo)
            assertEquals(false, assertNotNull(db.usuario(7)).activo, "el usuario vinculado sigue al médico")
            assertTrue(db.authQueries.medicosDirty().lista { it.id }.isEmpty(), "el dueño es turnos: el médico no queda dirty")
            assertTrue(db.authQueries.usuariosDirty().lista { it.id }.isEmpty(), "el usuario tampoco queda dirty")
        }
    }

    @Test
    fun la_copia_sigue_lo_que_informo_turnos_aunque_difiera_del_pedido() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, activo = true)
            turnos.respuestaDeActivo = 200 to """{"ok":true,"medico_id":7,"activo":true}"""

            val r = agenda.guardarActivoMedico("tobb-m7", activo = false)

            assertEquals(true, r)
            assertEquals(true, assertNotNull(db.medico(7)).activo, "se guarda lo de turnos, no lo que se pidió")
            assertEquals(true, assertNotNull(db.usuario(7)).activo)
        }
    }

    @Test
    fun reactivar_vuelve_a_dejar_activos_al_medico_y_a_su_usuario() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, activo = false)
            turnos.respuestaDeActivo = 200 to """{"ok":true,"medico_id":7,"activo":true}"""

            assertEquals(true, agenda.guardarActivoMedico("tobb-m7", activo = true))

            assertEquals(true, assertNotNull(db.medico(7)).activo)
            assertEquals(true, assertNotNull(db.usuario(7)).activo)
        }
    }

    @Test
    fun cambiar_el_activo_no_toca_ningun_otro_campo_del_medico_ni_del_usuario() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            val medicoAntes = Medico(
                "tobb-m7", "tobb-u1007", "Medico7", "Prueba",
                telefono = "000-0000", mail = "medico7@ejemplo.test", sexo = "F", foto = "foto-de-prueba", consultorioId = "tobb-c5",
                especialidadId = "tobb-e2", especialidadesHc = listOf("clinica", "pediatria"), tieneTurnos = true, visibleEnTurnos = false,
                activo = true,
            )
            val usuarioAntes = Usuario("tobb-u1007", "medico7@ejemplo.test", "Medico7", "Prueba", Rol.MEDICO, foto = "foto-de-prueba", activo = true)
            db.authQueries.upsertMedico(medicoAntes.toRow(1L, dirty = false))
            db.authQueries.upsertUsuario(usuarioAntes.toRow(1L, dirty = false))
            turnos.respuestaDeActivo = 200 to """{"ok":true,"medico_id":7,"activo":false}"""

            agenda.guardarActivoMedico("tobb-m7", activo = false)

            assertEquals(medicoAntes.copy(activo = false), assertNotNull(db.medico(7)))
            assertEquals(usuarioAntes.copy(activo = false), assertNotNull(db.usuario(7)))
        }
    }

    @Test
    fun los_errores_de_turnos_lanzan_con_su_mensaje_y_la_copia_no_cambia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, activo = true)

            val casos = listOf(
                CasoDeError(401, """{"ok":false,"codigo":"sin_token","mensaje":"Falta iniciar sesión"}""", "sin_token"),
                CasoDeError(403, """{"ok":false,"codigo":"sin_permiso","mensaje":"Solo el administrador puede activar o desactivar médicos"}""", "sin_permiso"),
                CasoDeError(404, """{"ok":false,"codigo":"no_encontrado","mensaje":"El médico no existe"}""", "no_encontrado"),
                CasoDeError(422, """{"ok":false,"codigo":"datos_invalidos","mensaje":"El campo activo debe ser verdadero o falso"}""", "datos_invalidos"),
                CasoDeError(500, """{"ok":false,"mensaje":"Error interno"}""", null),
                // Un 200 que no dice en qué quedó el médico no confirma nada: no se toma por bueno.
                CasoDeError(200, """{"ok":true,"medico_id":7}""", "respuesta"),
            )

            casos.forEach { caso ->
                turnos.respuestaDeActivo = caso.estado to caso.cuerpo
                val e = assertFailsWith<TobbException>("${caso.estado} ${caso.cuerpo}") { agenda.guardarActivoMedico("tobb-m7", activo = false) }
                assertEquals(caso.codigo, e.codigo, "el motivo que informa turnos llega tal cual")
                if (caso.estado == 403) {
                    assertEquals("Solo el administrador puede activar o desactivar médicos", e.message, "el mensaje del servidor llega tal cual")
                }

                assertEquals(true, assertNotNull(db.medico(7)).activo, "después del error ${caso.estado} el médico no cambia")
                assertEquals(true, assertNotNull(db.usuario(7)).activo, "ni su usuario")
            }
        }
    }

    @Test
    fun sin_red_lanza_y_la_copia_no_cambia() = runBlocking {
        val turnos = TurnosFalsoDeAgenda()
        val (db, agenda) = escena(turnos)
        db.sembrar(7, activo = true)
        turnos.close()

        val e = assertFailsWith<TobbException> { agenda.guardarActivoMedico("tobb-m7", activo = false) }

        assertEquals(0, e.status, "es un fallo de red, no una respuesta de la API")
        assertEquals("red", e.codigo)
        assertEquals(true, assertNotNull(db.medico(7)).activo)
        assertEquals(true, assertNotNull(db.usuario(7)).activo)
    }

    @Test
    fun si_la_web_no_tiene_la_ruta_lo_dice_en_castellano() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, activo = true)

            listOf(404 to """{"ok":false,"mensaje":"Not Found"}""", 405 to """{"ok":false,"mensaje":"Method Not Allowed"}""").forEach { (estado, cuerpo) ->
                turnos.respuestaDeActivo = estado to cuerpo
                val e = assertFailsWith<TobbException>("$estado sin codigo") { agenda.guardarActivoMedico("tobb-m7", activo = false) }
                assertEquals(estado, e.status)
                assertEquals("sin_ruta", e.codigo)
                val texto = e.message.orEmpty()
                assertTrue("todavía no permite activar o desactivar a un médico" in texto, texto)
                assertTrue("falta actualizar turnosonlinebb" in texto, texto)
                assertEquals(true, assertNotNull(db.medico(7)).activo, "con la web vieja la copia no cambia")
            }
        }
    }

    @Test
    fun si_el_medico_no_esta_en_el_dispositivo_devuelve_el_estado_sin_inventar_fichas() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            turnos.respuestaDeActivo = 200 to """{"ok":true,"medico_id":99,"activo":false}"""

            val r = agenda.guardarActivoMedico("tobb-m99", activo = false)

            assertEquals(false, r)
            assertNull(db.medico(99))
            assertTrue(db.authQueries.medicos().lista { it.id }.isEmpty(), "sin copia local no se inventa una ficha de médico")
            assertTrue(db.authQueries.usuarios().lista { it.id }.isEmpty(), "ni una de usuario")
        }
    }

    @Test
    fun si_el_usuario_no_esta_en_el_dispositivo_solo_cambia_el_medico() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.authQueries.upsertMedico(Medico("tobb-m7", "tobb-u1007", "Medico7", "Prueba").toRow(1L, dirty = false))
            turnos.respuestaDeActivo = 200 to """{"ok":true,"medico_id":7,"activo":false}"""

            agenda.guardarActivoMedico("tobb-m7", activo = false)

            assertEquals(false, assertNotNull(db.medico(7)).activo)
            assertTrue(db.authQueries.usuarios().lista { it.id }.isEmpty(), "no se inventa la ficha del usuario")
        }
    }

    @Test
    fun un_medico_que_no_es_de_turnos_no_sale_a_la_red() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)

            val e = assertFailsWith<TobbException> { agenda.guardarActivoMedico("medico-local", activo = false) }

            assertEquals("medico", e.codigo)
            assertTrue(turnos.pedidos().isEmpty(), "no se manda nada a turnos")
        }
    }

    @Test
    fun sin_conexion_con_turnos_el_repositorio_no_guarda() = runBlocking {
        val repo = TurnosRepository(baseTemporal())

        val e = assertFailsWith<IllegalStateException> { repo.guardarActivoMedico("tobb-m7", activo = false) }

        assertEquals("Activar o desactivar a un médico necesita conexión con turnos.", e.message)
    }

    // ---- sincronizarAdministracion: el médico desactivado no viene en catalogos ----

    @Test
    fun un_medico_desactivado_con_ficha_previa_queda_inactivo_y_conserva_sus_campos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            val antes = Medico(
                "tobb-m7", "", "Medico7", "Prueba",
                telefono = "000-0000", mail = "medico7@ejemplo.test", sexo = "F", consultorioId = "tobb-c5", especialidadId = "tobb-e2",
                especialidadesHc = listOf("clinica"), tieneTurnos = true, visibleEnTurnos = false, activo = true,
            )
            db.authQueries.upsertMedico(antes.toRow(1L, dirty = false))
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, activo = 0))

            agenda.sincronizarAdministracion()

            assertEquals(antes.copy(usuarioId = "tobb-u1007", activo = false), assertNotNull(db.medico(7)), "solo cambian activo y el vínculo con el usuario")
            assertEquals(false, assertNotNull(db.usuario(7)).activo, "el usuario también viene inactivo")
            assertTrue(db.authQueries.medicosDirty().lista { it.id }.isEmpty())
        }
    }

    @Test
    fun un_medico_desactivado_sin_ficha_previa_se_crea_con_una_fila_minima() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, activo = 0))

            agenda.sincronizarAdministracion()

            assertEquals(
                Medico(id = "tobb-m7", usuarioId = "tobb-u1007", nombre = "Medico7", apellido = "Prueba", activo = false),
                assertNotNull(db.medico(7)),
            )
            assertTrue(db.authQueries.medicosDirty().lista { it.id }.isEmpty(), "es una copia de turnos: no queda dirty")
        }
    }

    @Test
    fun el_medico_desactivado_vuelve_a_activo_cuando_reaparece_en_catalogos() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, activo = 0))
            agenda.sincronizarAdministracion()
            assertEquals(false, assertNotNull(db.medico(7)).activo)

            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, activo = 1))
            agenda.sincronizarAdministracion()

            assertEquals(true, assertNotNull(db.medico(7)).activo)
            assertEquals(true, assertNotNull(db.usuario(7)).activo)
        }
    }

    @Test
    fun los_medicos_que_si_vienen_en_catalogos_siguen_como_hasta_ahora() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            turnos.medicos = listOf(
                TurnosFalsoDeAgenda.MedicoFalso(id = 7, tieneAgenda = 1, visibleEnTurnos = 1, consultorioId = 5),
                TurnosFalsoDeAgenda.MedicoFalso(id = 8, activo = 0),
            )

            agenda.sincronizarAdministracion()

            val activo = assertNotNull(db.medico(7))
            assertEquals(true, activo.activo)
            assertEquals(true, activo.tieneTurnos)
            assertEquals("tobb-c5", activo.consultorioId, "el médico del catálogo se guarda completo, como siempre")
            assertEquals("tobb-u1007", activo.usuarioId)
            assertEquals(false, assertNotNull(db.medico(8)).activo)
            assertEquals(2, db.authQueries.medicos().lista { it.id }.size)
        }
    }

    // ---- Armado ----

    private data class Escena(val db: Salud360Db, val agenda: AgendaTurnosOnline)

    /** Un caso de error del `PUT`: el estado y el cuerpo que contesta turnos, y el `codigo` que debe llegar. */
    private class CasoDeError(val estado: Int, val cuerpo: String, val codigo: String?)

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-admin-activo", ".db").also { it.delete(); it.deleteOnExit() }
        return createDatabase(DriverFactory(archivo.absolutePath))
    }

    private suspend fun escena(turnos: TurnosFalsoDeAgenda): Escena {
        // El cliente va al turnos falso de `127.0.0.1`: nunca al de por omisión, que apunta a producción.
        val cliente = TurnosOnlineClient(turnos.url).apply { token = TOKEN }
        val db = baseTemporal()
        return Escena(db, AgendaTurnosOnline(db, cliente))
    }

    private suspend fun Salud360Db.medico(tobbId: Long): Medico? =
        authQueries.medicoPorId("tobb-m$tobbId").uno { it.toModel() }

    /** El usuario de ese médico en el armado de los tests: el id que le da el turnos falso (`1000 + n`). */
    private suspend fun Salud360Db.usuario(tobbId: Long): Usuario? =
        authQueries.usuarioPorId("tobb-u${1000 + tobbId}").uno { it.toModel() }

    /** Deja en el dispositivo el médico y su usuario, como los dejó una sincronización anterior del panel. */
    private suspend fun Salud360Db.sembrar(tobbId: Long, activo: Boolean, dirty: Boolean = false) {
        authQueries.upsertMedico(
            Medico("tobb-m$tobbId", "tobb-u${1000 + tobbId}", "Medico$tobbId", "Prueba", activo = activo).toRow(1L, dirty = dirty),
        )
        authQueries.upsertUsuario(
            Usuario("tobb-u${1000 + tobbId}", "medico$tobbId@ejemplo.test", "Medico$tobbId", "Prueba", Rol.MEDICO, activo = activo).toRow(1L, dirty = dirty),
        )
    }

    private companion object {
        const val TOKEN = "tok-de-prueba"
    }
}
