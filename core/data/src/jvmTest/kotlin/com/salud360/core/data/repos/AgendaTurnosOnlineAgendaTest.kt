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
import com.salud360.core.model.auth.Licencia
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
 * Spec 005, T4 y T5: el panel de administración toma la agenda de turnos (`tiene_agenda`, `visible_en_turnos`) y deja de
 * pisar «visible» cada vez que se abre el panel o se toca «Sincronizar ahora» (RF-12, RF-13, RF-17); y los dos
 * interruptores se guardan en turnos con `PUT medicos/{id}/agenda`, escribiendo la copia local recién con la
 * confirmación (RF-1, RF-2, RF-3, RF-5, RF-16, RF-18).
 *
 * Turnos falso en `127.0.0.1` (nunca el cliente por omisión, que apunta a producción) y base temporal. Todos los
 * ids, nombres y tokens son inventados.
 */
class AgendaTurnosOnlineAgendaTest {
    // ---- turnos informa los dos campos (RF-12) ----

    @Test
    fun los_dos_campos_que_informa_turnos_se_guardan_tal_cual() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(
                TurnosFalsoDeAgenda.MedicoFalso(id = 7, tieneHorarios = 1, tieneAgenda = 1, visibleEnTurnos = 0),
                TurnosFalsoDeAgenda.MedicoFalso(id = 8, tieneHorarios = 0, tieneAgenda = 1, visibleEnTurnos = 1),
                TurnosFalsoDeAgenda.MedicoFalso(id = 9, tieneHorarios = 1, tieneAgenda = 0, visibleEnTurnos = 0),
            )
            val (db, agenda) = escena(turnos)

            agenda.sincronizarAdministracion()

            val interna = assertNotNull(db.local(7))
            val publica = assertNotNull(db.local(8))
            val sinAgenda = assertNotNull(db.local(9))
            assertEquals(true, interna.tieneTurnos, "agenda interna: tiene agenda")
            assertEquals(false, interna.visibleEnTurnos)
            assertEquals(true, publica.tieneTurnos, "agenda pública: aunque no tenga horarios cargados")
            assertEquals(true, publica.visibleEnTurnos)
            assertEquals(false, sinAgenda.tieneTurnos, "la agenda apagada manda aunque tenga horarios")
            assertEquals(false, sinAgenda.visibleEnTurnos)
        }
    }

    @Test
    fun visible_sin_agenda_se_guarda_tal_cual_lo_informa_turnos() = runBlocking {
        // El caso límite de la spec: la web vieja lo muestra a los pacientes y no tiene agenda. La app no lo corrige sola.
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, tieneAgenda = 0, visibleEnTurnos = 1))
            val (db, agenda) = escena(turnos)

            agenda.sincronizarAdministracion()

            val m = assertNotNull(db.local(7))
            assertEquals(false, m.tieneTurnos)
            assertEquals(true, m.visibleEnTurnos)
        }
    }

    @Test
    fun el_estado_nuevo_pisa_la_copia_vieja_del_dispositivo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(
                TurnosFalsoDeAgenda.MedicoFalso(id = 7, tieneAgenda = 0, visibleEnTurnos = 0),
                TurnosFalsoDeAgenda.MedicoFalso(id = 8, tieneAgenda = 1, visibleEnTurnos = 1),
            )
            val (db, agenda) = escena(turnos)
            db.sembrar(7, tieneTurnos = true, visible = true)
            db.sembrar(8, tieneTurnos = false, visible = false)

            agenda.sincronizarAdministracion()

            assertEquals(false, assertNotNull(db.local(7)).tieneTurnos, "el administrador apagó la agenda")
            assertEquals(false, assertNotNull(db.local(7)).visibleEnTurnos)
            assertEquals(true, assertNotNull(db.local(8)).tieneTurnos, "el administrador la prendió")
            assertEquals(true, assertNotNull(db.local(8)).visibleEnTurnos)
        }
    }

    // ---- turnos no informa los campos (RF-13, RF-17) ----

    @Test
    fun sin_campos_la_agenda_sale_de_tiene_horarios_y_visible_conserva_el_previo() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(
                TurnosFalsoDeAgenda.MedicoFalso(id = 7, tieneHorarios = 1),
                TurnosFalsoDeAgenda.MedicoFalso(id = 8, tieneHorarios = 0),
            )
            val (db, agenda) = escena(turnos)
            db.sembrar(7, tieneTurnos = true, visible = false)
            db.sembrar(8, tieneTurnos = true, visible = true)

            agenda.sincronizarAdministracion()

            val conHorarios = assertNotNull(db.local(7))
            val sinHorarios = assertNotNull(db.local(8))
            assertEquals(true, conHorarios.tieneTurnos, "sin campos rige el criterio de siempre")
            assertEquals(false, conHorarios.visibleEnTurnos, "no vuelve a true: ese era el defecto que pisaba el interruptor")
            assertEquals(false, sinHorarios.tieneTurnos)
            assertEquals(true, sinHorarios.visibleEnTurnos, "sin campo y con previo true se queda en true")
        }
    }

    @Test
    fun sin_campos_y_sin_copia_previa_el_visible_arranca_en_true() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, tieneHorarios = 1))
            val (db, agenda) = escena(turnos)

            agenda.sincronizarAdministracion()

            val m = assertNotNull(db.local(7))
            assertTrue(m.tieneTurnos)
            assertTrue(m.visibleEnTurnos, "es el valor por omisión de siempre")
        }
    }

    // ---- Repetir la sincronización (RF-17, HU-3) ----

    @Test
    fun repetir_la_sincronizacion_no_cambia_nada() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(
                TurnosFalsoDeAgenda.MedicoFalso(id = 7, tieneHorarios = 1, tieneAgenda = 1, visibleEnTurnos = 0),
                TurnosFalsoDeAgenda.MedicoFalso(id = 8, tieneHorarios = 1),
                TurnosFalsoDeAgenda.MedicoFalso(id = 9, tieneAgenda = 0, visibleEnTurnos = 1),
            )
            val (db, agenda) = escena(turnos)
            db.sembrar(8, tieneTurnos = true, visible = false)

            agenda.sincronizarAdministracion()
            val primera = listOf(7L, 8L, 9L).map { assertNotNull(db.local(it)) }

            agenda.sincronizarAdministracion()
            val segunda = listOf(7L, 8L, 9L).map { assertNotNull(db.local(it)) }

            assertEquals(primera, segunda, "sincronizar de nuevo no puede cambiar los estados")
            assertEquals(false, segunda[0].visibleEnTurnos)
            assertEquals(false, segunda[1].visibleEnTurnos, "«visible» no se vuelve a prender al repetir")
        }
    }

    @Test
    fun la_copia_del_panel_no_queda_pendiente_de_envio() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, tieneAgenda = 0, visibleEnTurnos = 0))
            val (db, agenda) = escena(turnos)

            agenda.sincronizarAdministracion()

            assertTrue(db.authQueries.medicosDirty().lista { it.id }.isEmpty(), "el dueño es turnos: nada queda dirty")
        }
    }

    // ---- El helper: lo que sale hacia turnos ----

    @Test
    fun anota_el_metodo_la_ruta_y_el_token_de_cada_pedido() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7))
            val (_, agenda) = escena(turnos)

            agenda.sincronizarAdministracion()

            val pedidos = turnos.pedidos()
            assertEquals(
                listOf("/api/salud360/catalogos", "/api/salud360/usuarios", "/api/salud360/secretarias"),
                pedidos.map { it.ruta },
            )
            assertTrue(pedidos.all { it.metodo == "GET" }, "los tres son GET: ${pedidos.map { it.metodo }}")
            assertTrue(pedidos.all { it.token == TOKEN }, "todos llevan el token de la sesión (nunca uno real)")
        }
    }

    // ---- Guardar la agenda en turnos (T5: RF-1, RF-2, RF-3, RF-5, RF-13, RF-16, RF-18) ----

    @Test
    fun el_put_sale_a_la_ruta_con_metodo_token_y_solo_el_campo_pedido() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (_, agenda) = escena(turnos)
            turnos.respuestaDeAgenda = 200 to """{"medico_id":7,"tiene_agenda":false,"visible_en_turnos":true,"turnos_futuros":0}"""

            agenda.guardarAgenda("tobb-m7", tieneAgenda = false, visible = null)
            agenda.guardarAgenda("tobb-m7", tieneAgenda = null, visible = true)

            val pedidos = turnos.pedidosA("/api/salud360/medicos/7/agenda")
            assertEquals(2, pedidos.size, "un PUT por interruptor")
            assertTrue(pedidos.all { it.metodo == "PUT" }, "la agenda se cambia con PUT: ${pedidos.map { it.metodo }}")
            assertTrue(pedidos.all { it.token == TOKEN }, "todos llevan el token de la sesión (nunca uno real)")
            assertEquals("""{"tiene_agenda":false}""", pedidos[0].cuerpo, "solo viaja el campo que el administrador tocó")
            assertEquals("""{"visible_en_turnos":true}""", pedidos[1].cuerpo)
        }
    }

    @Test
    fun con_200_la_copia_toma_el_estado_que_informo_turnos_y_queda_limpia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, tieneTurnos = false, visible = false, dirty = true)
            turnos.respuestaDeAgenda = 200 to """{"medico_id":7,"tiene_agenda":true,"visible_en_turnos":true,"turnos_futuros":4}"""

            val r = agenda.guardarAgenda("tobb-m7", tieneAgenda = true, visible = null)

            assertEquals(AgendaGuardada(tieneAgenda = true, visible = true, turnosFuturos = 4), r, "el resultado trae los turnos futuros")
            val m = assertNotNull(db.local(7))
            assertTrue(m.tieneTurnos)
            assertTrue(m.visibleEnTurnos)
            assertTrue(db.authQueries.medicosDirty().lista { it.id }.isEmpty(), "el dueño es turnos: la copia no queda dirty")
        }
    }

    @Test
    fun la_copia_sigue_lo_que_informo_turnos_aunque_difiera_del_pedido() = runBlocking {
        // El servidor puede agrandar el cambio (por ejemplo, apagar la agenda apaga «visible»): manda lo que contestó.
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, tieneTurnos = false, visible = false)
            turnos.respuestaDeAgenda = 200 to """{"medico_id":7,"tiene_agenda":false,"visible_en_turnos":false,"turnos_futuros":0}"""

            agenda.guardarAgenda("tobb-m7", tieneAgenda = true, visible = null)

            val m = assertNotNull(db.local(7))
            assertEquals(false, m.tieneTurnos, "se guarda el estado final de turnos, no lo que se pidió")
            assertEquals(false, m.visibleEnTurnos)
        }
    }

    @Test
    fun apagar_la_agenda_deja_al_medico_no_visible() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, tieneTurnos = true, visible = true)
            turnos.respuestaDeAgenda = 200 to """{"medico_id":7,"tiene_agenda":false,"visible_en_turnos":false,"turnos_futuros":3}"""

            val r = agenda.guardarAgenda("tobb-m7", tieneAgenda = false, visible = null)

            assertEquals(3, r.turnosFuturos)
            val m = assertNotNull(db.local(7))
            assertEquals(false, m.tieneTurnos)
            assertEquals(false, m.visibleEnTurnos, "la respuesta de turnos lo deja no visible (RF-5)")
        }
    }

    @Test
    fun los_errores_de_turnos_lanzan_y_la_copia_no_cambia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, tieneTurnos = true, visible = true)
            db.sembrar(8, tieneTurnos = false, visible = false)

            val casos = listOf(
                CasoDeError(422, """{"ok":false,"codigo":"agenda_requerida","mensaje":"Sin agenda no puede estar visible"}""", "agenda_requerida"),
                CasoDeError(422, """{"ok":false,"codigo":"datos_invalidos","mensaje":"Datos inválidos"}""", "datos_invalidos"),
                CasoDeError(403, """{"ok":false,"codigo":"sin_permiso","mensaje":"Solo el administrador puede cambiar la agenda"}""", "sin_permiso"),
                CasoDeError(404, """{"ok":false,"codigo":"no_encontrado","mensaje":"El médico no existe"}""", "no_encontrado"),
                CasoDeError(500, """{"ok":false,"mensaje":"Error interno"}""", null),
                // Un 200 que no dice en qué quedó la agenda no confirma nada: no se toma por buena.
                CasoDeError(200, """{"medico_id":7,"turnos_futuros":0}""", "respuesta"),
            )

            casos.forEach { caso ->
                turnos.respuestaDeAgenda = caso.estado to caso.cuerpo
                val e = assertFailsWith<TobbException>("${caso.estado} ${caso.cuerpo}") {
                    agenda.guardarAgenda("tobb-m7", tieneAgenda = false, visible = null)
                }
                assertEquals(caso.codigo, e.codigo, "el motivo que informa turnos llega tal cual")

                val m = assertNotNull(db.local(7))
                assertTrue(m.tieneTurnos, "después del error ${caso.estado} la copia no cambia")
                assertTrue(m.visibleEnTurnos)
                val otro = assertNotNull(db.local(8))
                assertEquals(false, otro.tieneTurnos)
                assertEquals(false, otro.visibleEnTurnos)
            }
        }
    }

    @Test
    fun sin_red_lanza_y_la_copia_no_cambia() = runBlocking {
        val turnos = TurnosFalsoDeAgenda()
        val (db, agenda) = escena(turnos)
        db.sembrar(7, tieneTurnos = true, visible = true)
        turnos.close()

        val e = assertFailsWith<TobbException> { agenda.guardarAgenda("tobb-m7", tieneAgenda = false, visible = null) }

        assertEquals(0, e.status, "es un fallo de red, no una respuesta de la API")
        assertEquals("red", e.codigo)
        assertTrue(assertNotNull(db.local(7)).tieneTurnos, "sin conexión el interruptor queda como estaba")
    }

    @Test
    fun si_la_web_no_tiene_la_ruta_lo_dice_en_castellano() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, tieneTurnos = true, visible = true)

            listOf(404 to """{"ok":false,"mensaje":"Not Found"}""", 405 to """{"ok":false,"mensaje":"Method Not Allowed"}""").forEach { (estado, cuerpo) ->
                turnos.respuestaDeAgenda = estado to cuerpo
                val e = assertFailsWith<TobbException>("$estado sin codigo") {
                    agenda.guardarAgenda("tobb-m7", tieneAgenda = false, visible = null)
                }
                assertEquals(estado, e.status)
                assertEquals("sin_ruta", e.codigo)
                val texto = e.message.orEmpty()
                assertTrue("todavía no permite" in texto, texto)
                assertTrue("falta actualizar turnosonlinebb" in texto, texto)
                assertTrue(assertNotNull(db.local(7)).tieneTurnos, "con la web vieja la copia no cambia (RF-13)")
            }
        }
    }

    @Test
    fun guardar_la_agenda_no_toca_las_historias_clinicas_ni_la_licencia() = runBlocking {
        // RF-16: el dueño de la agenda es turnos; las HC y la licencia viven en la base de la especialidad.
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            db.sembrar(7, tieneTurnos = true, visible = true, especialidadesHc = listOf("pediatria", "clinica"))
            db.authQueries.upsertLicencia(
                Licencia("tobb-m7", fechaExpiracion = "2031-12-31", fechaAviso = "2031-12-01", importe = 1500.0, activo = true).toRow(1L, dirty = false),
            )
            val licenciaAntes = assertNotNull(db.authQueries.licenciaPorMedico("tobb-m7").uno { it.toModel() })
            turnos.respuestaDeAgenda = 200 to """{"medico_id":7,"tiene_agenda":false,"visible_en_turnos":false,"turnos_futuros":2}"""

            agenda.guardarAgenda("tobb-m7", tieneAgenda = false, visible = null)

            val m = assertNotNull(db.local(7))
            assertEquals(false, m.tieneTurnos, "la agenda sí cambió")
            assertEquals(listOf("pediatria", "clinica"), m.especialidadesHc, "las HC habilitadas quedan idénticas")
            assertEquals(licenciaAntes, assertNotNull(db.authQueries.licenciaPorMedico("tobb-m7").uno { it.toModel() }))
        }
    }

    @Test
    fun si_el_medico_no_esta_en_el_dispositivo_devuelve_el_estado_sin_escribir() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            val (db, agenda) = escena(turnos)
            turnos.respuestaDeAgenda = 200 to """{"medico_id":99,"tiene_agenda":true,"visible_en_turnos":false,"turnos_futuros":1}"""

            val r = agenda.guardarAgenda("tobb-m99", tieneAgenda = true, visible = null)

            assertEquals(AgendaGuardada(tieneAgenda = true, visible = false, turnosFuturos = 1), r)
            assertNull(db.local(99))
            assertTrue(db.authQueries.medicos().lista { it.id }.isEmpty(), "sin copia local no se inventa una ficha")
        }
    }

    @Test
    fun sin_conexion_con_turnos_el_repositorio_no_guarda() = runBlocking {
        val repo = TurnosRepository(baseTemporal())

        val e = assertFailsWith<IllegalStateException> { repo.guardarAgenda("tobb-m7", tieneAgenda = true, visible = null) }

        assertEquals("Cambiar la agenda necesita conexión con turnos.", e.message)
    }

    // ---- Armado ----

    private data class Escena(val db: Salud360Db, val agenda: AgendaTurnosOnline)

    /** Un caso de error del `PUT`: el estado y el cuerpo que contesta turnos, y el `codigo` que debe llegar. */
    private class CasoDeError(val estado: Int, val cuerpo: String, val codigo: String?)

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-admin-agenda", ".db").also { it.delete(); it.deleteOnExit() }
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

    /** Deja en el dispositivo la copia que dejó el panel (o un ingreso anterior) antes de sincronizar. */
    private suspend fun Salud360Db.sembrar(
        tobbId: Long,
        tieneTurnos: Boolean,
        visible: Boolean,
        especialidadesHc: List<String> = emptyList(),
        dirty: Boolean = false,
    ) {
        authQueries.upsertMedico(
            Medico(
                "tobb-m$tobbId", "tobb-mu$tobbId", "Medico$tobbId", "Prueba",
                especialidadesHc = especialidadesHc, tieneTurnos = tieneTurnos, visibleEnTurnos = visible,
            ).toRow(1L, dirty = dirty),
        )
    }

    private companion object {
        const val TOKEN = "tok-de-prueba"
    }
}
