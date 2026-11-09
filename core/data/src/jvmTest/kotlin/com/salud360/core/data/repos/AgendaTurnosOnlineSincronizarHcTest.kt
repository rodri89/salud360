package com.salud360.core.data.repos

import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
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
import kotlin.test.assertNotNull

/**
 * El sync de `catalogos` (panel de administración) usa la misma regla que el perfil para las historias clínicas: lo que
 * turnos manda es la lista (aunque sea vacía: una baja del admin se propaga) y un servidor viejo que no manda el campo
 * no borra la copia. Turnos falso en `127.0.0.1` y base temporal; ids y tokens inventados.
 */
class AgendaTurnosOnlineSincronizarHcTest {

    @Test
    fun la_lista_que_manda_el_catalogo_reemplaza_a_la_copia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, historiasClinicas = listOf("clinica")))
            val (db, agenda) = escena(turnos)
            db.sembrar(7, listOf("pediatria"))

            agenda.sincronizarAdministracion()

            assertEquals(listOf("clinica"), db.hc(7))
        }
    }

    @Test
    fun la_lista_vacia_del_catalogo_borra_la_copia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, historiasClinicas = emptyList()))
            val (db, agenda) = escena(turnos)
            db.sembrar(7, listOf("pediatria", "clinica"))

            agenda.sincronizarAdministracion()

            assertEquals(emptyList(), db.hc(7), "turnos informó que no tiene ninguna: es una baja, no un campo ausente")
        }
    }

    @Test
    fun si_el_catalogo_no_trae_el_campo_se_conserva_la_copia() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, historiasClinicas = null))
            val (db, agenda) = escena(turnos)
            db.sembrar(7, listOf("pediatria"))

            agenda.sincronizarAdministracion()

            assertEquals(listOf("pediatria"), db.hc(7), "servidor viejo: no se pisa lo que ya había")
        }
    }

    @Test
    fun un_medico_nuevo_sin_el_campo_queda_sin_historias() = runBlocking {
        TurnosFalsoDeAgenda().use { turnos ->
            turnos.medicos = listOf(TurnosFalsoDeAgenda.MedicoFalso(id = 7, historiasClinicas = null))
            val (db, agenda) = escena(turnos)

            agenda.sincronizarAdministracion()

            assertEquals(emptyList(), db.hc(7))
        }
    }

    // ---- Armado ----

    private suspend fun escena(turnos: TurnosFalsoDeAgenda): Pair<Salud360Db, AgendaTurnosOnline> {
        // El cliente va al turnos falso de `127.0.0.1`: nunca al de por omisión, que apunta a producción.
        val cliente = TurnosOnlineClient(turnos.url).apply { token = "tok-de-prueba" }
        val archivo = File.createTempFile("salud360-sync-hc", ".db").also { it.delete(); it.deleteOnExit() }
        val db = createDatabase(DriverFactory(archivo.absolutePath))
        return db to AgendaTurnosOnline(db, cliente)
    }

    private suspend fun Salud360Db.hc(tobbId: Long): List<String> =
        assertNotNull(authQueries.medicoPorId("tobb-m$tobbId").uno { it.toModel() }).especialidadesHc

    private suspend fun Salud360Db.sembrar(tobbId: Long, historias: List<String>) {
        authQueries.upsertMedico(
            Medico("tobb-m$tobbId", "tobb-mu$tobbId", "Medico$tobbId", "Prueba", especialidadesHc = historias).toRow(1L, dirty = false),
        )
    }
}
