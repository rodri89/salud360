package com.salud360.core.data.repos

import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.tobb.TobbConsultorio
import com.salud360.core.model.tobb.TobbMedico
import com.salud360.core.model.tobb.TobbPerfil
import com.salud360.core.model.tobb.TobbSecretaria
import com.salud360.core.model.tobb.TobbUsuario
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Las historias clínicas de un médico las decide el administrador en turnos (`historias_clinicas` del perfil): lo que
 * llega manda, una lista vacía es una baja, un campo ausente (servidor viejo) no toca la copia y ya no se deduce nada
 * de la especialidad. Base temporal, sin red; fixtures inventados.
 */
class TurnosImportadorHistoriasClinicasTest {
    private val consultorio = TobbConsultorio(5, "Consultorio de prueba")

    private fun medico(id: Long = 7, historias: List<String>? = null, especialidad: String? = null) = TobbMedico(
        id = id, nombre = "Medico$id", apellido = "Prueba", especialidad = especialidad,
        consultorioId = 5, consultorio = consultorio, historiasClinicas = historias,
    )

    private fun perfilMedico(m: TobbMedico) =
        TobbPerfil(TobbUsuario(12, "Medico", "medico@ejemplo.test", 2), "medico", medico = m)

    private fun perfilSecretaria(vararg medicos: TobbMedico) = TobbPerfil(
        TobbUsuario(30, "Secretaria", "secretaria@ejemplo.test", 3), "secretaria",
        secretaria = TobbSecretaria(4, "Secretaria", "Prueba", consultorios = listOf(consultorio), medicos = medicos.toList()),
    )

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-importador-hc", ".db").also { it.delete(); it.deleteOnExit() }
        return createDatabase(DriverFactory(archivo.absolutePath))
    }

    private suspend fun Salud360Db.hc(tobbId: Long): List<String> =
        assertNotNull(authQueries.medicoPorId("tobb-m$tobbId").uno { it.toModel() }).especialidadesHc

    /** Deja en el dispositivo la copia que dejó un ingreso anterior (con la HC habilitada o deducida entonces). */
    private suspend fun Salud360Db.sembrar(tobbId: Long, historias: List<String>) {
        authQueries.upsertMedico(
            Medico("tobb-m$tobbId", "tobb-mu$tobbId", "Medico$tobbId", "Prueba", especialidadesHc = historias).toRow(1L, dirty = false),
        )
    }

    @Test
    fun la_lista_que_manda_turnos_queda_normalizada() = runBlocking {
        val db = baseTemporal()
        TurnosImportador(db).importar(perfilMedico(medico(historias = listOf(" Pediatria ", "clinica", "PEDIATRIA"))))
        assertEquals(listOf("pediatria", "clinica"), db.hc(7))
    }

    @Test
    fun la_lista_de_turnos_reemplaza_a_la_previa() = runBlocking {
        val db = baseTemporal()
        db.sembrar(7, listOf("pediatria"))
        TurnosImportador(db).importar(perfilMedico(medico(historias = listOf("clinica"))))
        assertEquals(listOf("clinica"), db.hc(7))
    }

    @Test
    fun la_lista_vacia_de_turnos_borra_las_previas_la_baja_del_admin_se_propaga() = runBlocking {
        val db = baseTemporal()
        db.sembrar(7, listOf("pediatria", "clinica"))
        TurnosImportador(db).importar(perfilMedico(medico(historias = emptyList())))
        assertEquals(emptyList(), db.hc(7))
    }

    @Test
    fun si_turnos_no_manda_el_campo_se_conservan_las_previas() = runBlocking {
        val db = baseTemporal()
        db.sembrar(7, listOf("pediatria"))
        TurnosImportador(db).importar(perfilMedico(medico(historias = null)))
        assertEquals(listOf("pediatria"), db.hc(7), "servidor viejo: no se sabe nada nuevo, no se borra")
    }

    @Test
    fun sin_fallback_un_pediatra_sin_historia_informada_queda_sin_historia() = runBlocking {
        val db = baseTemporal()
        val importador = TurnosImportador(db)

        importador.importar(perfilMedico(medico(historias = emptyList(), especialidad = "Pediatría")))
        assertEquals(emptyList(), db.hc(7), "el admin no le habilitó nada: la especialidad no habilita la HC")

        // Y con el campo ausente y sin previas tampoco se deduce.
        importador.importar(perfilMedico(medico(id = 8, historias = null, especialidad = "Pediatría")))
        assertEquals(emptyList(), db.hc(8))
    }

    @Test
    fun la_copia_contaminada_por_el_fallback_se_corrige_con_la_lista_real_de_turnos() = runBlocking {
        val db = baseTemporal()
        // Antes la app le dejaba «pediatria» a todo pediatra por su especialidad; el admin nunca se la habilitó.
        db.sembrar(7, listOf("pediatria"))
        val usuario = TurnosImportador(db).importar(perfilMedico(medico(historias = emptyList(), especialidad = "Pediatría")))
        assertEquals(emptyList(), resolverPerfil(db, usuario)?.medico?.especialidades, "la sesión ya no trae la HC deducida")
    }

    @Test
    fun la_secretaria_importa_a_sus_medicos_con_la_misma_regla() = runBlocking {
        val db = baseTemporal()
        db.sembrar(8, listOf("pediatria"))
        db.sembrar(9, listOf("clinica"))
        TurnosImportador(db).importar(
            perfilSecretaria(
                medico(id = 7, historias = listOf("clinica")),
                medico(id = 8, historias = emptyList()),
                medico(id = 9, historias = null),
            ),
        )
        assertEquals(listOf("clinica"), db.hc(7))
        assertEquals(emptyList(), db.hc(8))
        assertEquals(listOf("clinica"), db.hc(9))
    }
}
