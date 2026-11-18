package com.salud360.core.data.repos

import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.tobb.TobbConsultorio
import com.salud360.core.model.tobb.TobbMedico
import com.salud360.core.model.tobb.TobbPerfil
import com.salud360.core.model.tobb.TobbSecretaria
import com.salud360.core.model.tobb.TobbUsuario
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T3: el importador toma la agenda de turnos (`tiene_agenda`, `visible_en_turnos`) y no la deduce del
 * consultorio ni de los horarios cuando turnos la informa. Base temporal, sin red; fixtures inventados.
 */
class TurnosImportadorAgendaTest {
    private val consultorio = TobbConsultorio(5, "Consultorio de prueba")

    private fun medico(
        id: Long = 7,
        tieneHorarios: Int = 1,
        tieneAgenda: Int? = null,
        visible: Int? = null,
        consultorioId: Long? = 5,
    ) = TobbMedico(
        id = id, nombre = "Medico$id", apellido = "Prueba",
        consultorioId = consultorioId, consultorio = consultorio.takeIf { consultorioId == 5L },
        tieneHorarios = tieneHorarios, tieneAgenda = tieneAgenda, visibleEnTurnos = visible,
    )

    private fun perfilMedico(m: TobbMedico) =
        TobbPerfil(TobbUsuario(12, "Medico", "medico@ejemplo.test", 2), "medico", medico = m)

    private fun perfilSecretaria(vararg medicos: TobbMedico) = TobbPerfil(
        TobbUsuario(30, "Secretaria", "secretaria@ejemplo.test", 3), "secretaria",
        secretaria = TobbSecretaria(4, "Secretaria", "Prueba", consultorios = listOf(consultorio), medicos = medicos.toList()),
    )

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-importador-agenda", ".db").also { it.delete(); it.deleteOnExit() }
        return createDatabase(DriverFactory(archivo.absolutePath))
    }

    private suspend fun Salud360Db.local(tobbId: Long): Medico? =
        authQueries.medicoPorId("tobb-m$tobbId").uno { it.toModel() }

    /** Deja en el dispositivo una copia previa del médico, como la que dejó un ingreso anterior. */
    private suspend fun Salud360Db.sembrar(tobbId: Long, tieneTurnos: Boolean, visible: Boolean) {
        authQueries.upsertMedico(
            Medico(
                "tobb-m$tobbId", "tobb-mu$tobbId", "Medico$tobbId", "Prueba",
                tieneTurnos = tieneTurnos, visibleEnTurnos = visible,
            ).toRow(1L, dirty = false),
        )
    }

    // ---- Médico: lo que informa turnos manda ----

    @Test
    fun agenda_y_visible_en_cero_mandan_aunque_tiene_horarios_diga_uno() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(perfilMedico(medico(tieneHorarios = 1, tieneAgenda = 0, visible = 0)))
        val m = assertNotNull(db.local(7))
        assertFalse(m.tieneTurnos)
        assertFalse(m.visibleEnTurnos)
    }

    @Test
    fun agenda_en_uno_manda_aunque_no_tenga_horarios_cargados() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(perfilMedico(medico(tieneHorarios = 0, tieneAgenda = 1, visible = 1)))
        val m = assertNotNull(db.local(7))
        assertTrue(m.tieneTurnos)
        assertTrue(m.visibleEnTurnos)
    }

    @Test
    fun agenda_interna_queda_con_agenda_y_sin_visible() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(perfilMedico(medico(tieneAgenda = 1, visible = 0)))
        val m = assertNotNull(db.local(7))
        assertTrue(m.tieneTurnos)
        assertFalse(m.visibleEnTurnos)
    }

    @Test
    fun visible_sin_agenda_se_guarda_tal_cual_lo_informa_turnos() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(perfilMedico(medico(tieneAgenda = 0, visible = 1)))
        val m = assertNotNull(db.local(7))
        assertFalse(m.tieneTurnos)
        assertTrue(m.visibleEnTurnos, "turnos lo informa así: la app no lo corrige sola")
    }

    @Test
    fun con_consultorio_cero_y_agenda_en_uno_tiene_turnos() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(perfilMedico(medico(tieneAgenda = 1, consultorioId = 0)))
        val m = assertNotNull(db.local(7))
        assertTrue(m.tieneTurnos, "el consultorio ya no entra en tieneTurnos: lo miran las pantallas")
        assertNull(m.consultorioId)
    }

    // ---- Médico: turnos no informa los campos (servidor sin actualizar, RF-13 y RF-17) ----

    @Test
    fun sin_campos_y_con_horarios_tiene_turnos_y_visible_es_true_sin_previo() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(perfilMedico(medico(tieneHorarios = 1)))
        val m = assertNotNull(db.local(7))
        assertTrue(m.tieneTurnos)
        assertTrue(m.visibleEnTurnos)
    }

    @Test
    fun sin_campos_y_con_horarios_conserva_el_visible_previo() = runBlocking {
        val db = baseTemporal()
        db.sembrar(7, tieneTurnos = true, visible = false)
        TurnosImportador(db).importar(perfilMedico(medico(tieneHorarios = 1)))
        val m = assertNotNull(db.local(7))
        assertTrue(m.tieneTurnos)
        assertFalse(m.visibleEnTurnos, "no vuelve a true: eso pisaba el interruptor")
    }

    @Test
    fun sin_campos_y_sin_horarios_no_tiene_turnos() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(perfilMedico(medico(tieneHorarios = 0)))
        val m = assertNotNull(db.local(7))
        assertFalse(m.tieneTurnos)
        assertTrue(m.visibleEnTurnos)
    }

    @Test
    fun el_estado_informado_pisa_al_previo_y_repetir_la_importacion_no_cambia_nada() = runBlocking {
        val db = baseTemporal()
        db.sembrar(7, tieneTurnos = true, visible = true)
        val importador = TurnosImportador(db)
        val perfil = perfilMedico(medico(tieneHorarios = 1, tieneAgenda = 0, visible = 0))
        importador.importar(perfil)
        val primera = assertNotNull(db.local(7))
        importador.importar(perfil)
        assertEquals(primera, db.local(7))
        assertFalse(primera.tieneTurnos)
        assertFalse(primera.visibleEnTurnos)
    }

    // ---- RF-15: login y restaurar importan y rearman la sesión con resolverPerfil ----

    @Test
    fun resolverPerfil_toma_el_estado_nuevo_despues_de_importar() = runBlocking {
        val db = baseTemporal()
        val importador = TurnosImportador(db)

        val usuario: Usuario = importador.importar(perfilMedico(medico(tieneAgenda = 1, visible = 1)))
        assertEquals(true, resolverPerfil(db, usuario)?.medico?.tieneTurnos)

        // El administrador apaga la agenda; el médico vuelve a entrar (o recarga) y se importa el perfil nuevo.
        importador.importar(perfilMedico(medico(tieneAgenda = 0, visible = 0)))
        assertEquals(false, resolverPerfil(db, usuario)?.medico?.tieneTurnos)

        // Y la vuelve a prender: sin horarios cargados igual ve todo lo de turnos.
        importador.importar(perfilMedico(medico(tieneHorarios = 0, tieneAgenda = 1, visible = 0)))
        assertEquals(true, resolverPerfil(db, usuario)?.medico?.tieneTurnos)
    }

    // ---- Secretaria (Q2): sus médicos pasan por la misma regla ----

    @Test
    fun secretaria_guarda_a_cada_medico_con_el_estado_que_informa_turnos() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(
            perfilSecretaria(
                medico(id = 7, tieneAgenda = 1, visible = 0),
                medico(id = 8, tieneHorarios = 1, tieneAgenda = 0, visible = 0),
                medico(id = 9, tieneAgenda = 1, visible = 1),
            ),
        )
        val conAgendaInterna = assertNotNull(db.local(7))
        val sinAgenda = assertNotNull(db.local(8))
        val conAgendaPublica = assertNotNull(db.local(9))
        assertTrue(conAgendaInterna.tieneTurnos); assertFalse(conAgendaInterna.visibleEnTurnos)
        assertFalse(sinAgenda.tieneTurnos); assertFalse(sinAgenda.visibleEnTurnos)
        assertTrue(conAgendaPublica.tieneTurnos); assertTrue(conAgendaPublica.visibleEnTurnos)
    }

    @Test
    fun secretaria_con_un_medico_sin_campos_cae_en_tiene_horarios_y_conserva_el_visible_previo() = runBlocking {
        val db = baseTemporal()
        db.sembrar(8, tieneTurnos = true, visible = false)
        db.sembrar(9, tieneTurnos = true, visible = false)
        TurnosImportador(db).importar(
            perfilSecretaria(
                medico(id = 7, tieneAgenda = 0, visible = 0),
                medico(id = 8, tieneHorarios = 1),
                medico(id = 9, tieneHorarios = 0),
            ),
        )
        val informado = assertNotNull(db.local(7))
        val conHorarios = assertNotNull(db.local(8))
        val sinHorarios = assertNotNull(db.local(9))
        assertFalse(informado.tieneTurnos)
        assertTrue(conHorarios.tieneTurnos); assertFalse(conHorarios.visibleEnTurnos)
        assertFalse(sinHorarios.tieneTurnos); assertFalse(sinHorarios.visibleEnTurnos)
    }
}
