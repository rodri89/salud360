package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.VacunaAplicada
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.model.turnos.Receta
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cartera de pacientes del médico y fusión de la ficha local con la de turnos (`reemplazarPorRemoto`).
 *
 * Base temporal en archivo (nunca la del dispositivo). Todos los ids, DNI y nombres son inventados.
 */
class PacientesRepositoryTest {

    private val medico = "tobb-m1"
    private val otroMedico = "tobb-m2"
    private val fecha = LocalDate(2026, 3, 10)

    // ---- la lista del médico es solo su cartera ----

    @Test
    fun un_paciente_sin_vinculo_no_aparece_en_la_lista_del_medico_pero_si_en_la_del_admin() = runBlocking {
        val (db, repo) = escena()
        repo.guardar(Paciente("p-propio", "30000001", "ANA", "PROPIA"), vincularA = medico)
        // Como los que dejaba la búsqueda global: guardados en el dispositivo, sin vínculo con el médico.
        repo.guardar(Paciente("tobb-p9", "30000009", "BETO", "AJENO"))
        db.pacientesQueries.upsertPaciente(Paciente("tobb-p10", "30000010", "CARLA", "AJENA").toRow(1L, dirty = false))

        assertEquals(listOf("p-propio"), repo.observarPagina(medico, "", 50).first().map { it.id })
        assertEquals(1L, repo.observarTotal(medico, "").first())
        assertEquals(setOf("p-propio", "tobb-p9", "tobb-p10"), repo.observarPagina(null, "", 50).first().map { it.id }.toSet(), "el admin sigue viendo todos")
        assertEquals(3L, repo.observarTotal(null, "").first())
    }

    @Test
    fun la_busqueda_del_medico_no_encuentra_a_los_que_no_son_suyos() = runBlocking {
        val (_, repo) = escena()
        repo.guardar(Paciente("p-propio", "30000001", "ANA", "PRUEBA"), vincularA = medico)
        repo.guardar(Paciente("tobb-p9", "30000009", "BETO", "PRUEBA"))
        repo.guardar(Paciente("p-del-otro", "30000002", "DORA", "PRUEBA"), vincularA = otroMedico)

        assertEquals(listOf("p-propio"), repo.observarPagina(medico, "prueba", 50).first().map { it.id })
        assertEquals(listOf("p-propio"), repo.buscar("PRUEBA", medico).map { it.id })
        assertEquals(1L, repo.observarTotal(medico, "PRUEBA").first())
        assertTrue(repo.observarPagina(medico, "30000009", 50).first().isEmpty(), "ni por DNI")
    }

    @Test
    fun un_vinculo_dado_de_baja_saca_al_paciente_de_la_lista() = runBlocking {
        val (_, repo) = escena()
        repo.guardar(Paciente("p-1", "30000001", "ANA", "PRUEBA"), vincularA = medico)
        repo.desvincular(medico, "p-1")

        assertTrue(repo.observarPagina(medico, "", 50).first().isEmpty())
    }

    // ---- guardar: un DNI que ya existe se asocia, no se pisa ----

    @Test
    fun guardar_con_un_dni_que_ya_esta_en_el_dispositivo_lo_asocia_sin_pisar_sus_datos() = runBlocking {
        val (db, repo) = escena()
        repo.guardar(Paciente("tobb-p9", "30000009", "BETO", "AJENO", telefono = "555", obraSocial = "OS DE TURNOS"))

        val r = repo.guardar(Paciente("uuid-nuevo", "30000009", "otro nombre", "otro apellido", telefono = "111", obraSocial = "OS DEL MEDICO"), vincularA = medico)

        assertEquals("tobb-p9", r.id)
        assertEquals("BETO", r.nombre, "se devuelve la ficha que ya tenía el sistema")
        assertEquals("555", r.telefono)
        assertEquals("OS DE TURNOS", r.obraSocial)
        val enBase = assertNotNull(repo.porId("tobb-p9"))
        assertEquals(r, enBase, "lo escrito por el médico no pisa lo existente")
        assertNull(repo.porId("uuid-nuevo"))
        assertEquals(1, db.filasConDni("30000009"))
        assertEquals(listOf("tobb-p9"), repo.observarPagina(medico, "", 50).first().map { it.id }, "queda asociado al médico")
    }

    @Test
    fun guardar_una_edicion_de_la_propia_ficha_si_actualiza_los_datos() = runBlocking {
        val (_, repo) = escena()
        repo.guardar(Paciente("p-1", "30000001", "ANA", "PRUEBA", telefono = "111"), vincularA = medico)

        repo.guardar(Paciente("p-1", "30000001", "ana", "prueba", telefono = "222"), vincularA = medico)

        assertEquals("222", assertNotNull(repo.porId("p-1")).telefono)
    }

    // ---- reemplazarPorRemoto ----

    @Test
    fun reemplazar_repunta_vinculos_extras_y_todas_las_tablas_con_paciente_id() = runBlocking {
        val (db, repo) = escena()
        db.sembrarFichaConTodo("uuid-1")

        val r = repo.reemplazarPorRemoto("uuid-1", remota())

        assertEquals("tobb-p7", r.id)
        assertNull(repo.porId("uuid-1"), "la ficha local sobrante desaparece")
        assertEquals(1, db.filasConDni("30111222"), "una sola fila con ese DNI")
        assertEquals("tobb-p7", repo.porDni("30111222")?.id)
        // vínculos
        assertTrue(repo.vinculosDe("uuid-1").isEmpty())
        val vinculo = db.pacientesQueries.vinculo(medico, "tobb-p7").uno { it }
        assertNotNull(vinculo, "conserva el vínculo con el médico")
        assertEquals(1L, vinculo.activo)
        assertEquals(0L, vinculo.dirty, "el vínculo con un paciente de turnos no queda dirty")
        assertEquals(listOf("tobb-p7"), repo.observarPagina(medico, "", 50).first().map { it.id })
        // paciente_extra
        assertTrue(db.pacientesQueries.extrasDePaciente("uuid-1", "pediatria").lista { it }.isEmpty())
        assertEquals("12", db.pacientesQueries.extrasDePaciente("tobb-p7", "pediatria").lista { it }.single { it.clave == "hc_paciente_id" }.valor)
        // el resto de las tablas con paciente_id
        assertEquals(0, db.referenciasA("uuid-1").values.sum(), "nada apunta al id viejo: ${db.referenciasA("uuid-1")}")
        assertEquals(TABLAS_CON_PACIENTE_ID.associateWith { 1 }, db.referenciasA("tobb-p7").filterKeys { it in TABLAS_CON_PACIENTE_ID }, "todo apunta al id de turnos")
    }

    @Test
    fun reemplazar_conserva_los_datos_de_turnos_y_no_pisa_con_vacios_lo_local() = runBlocking {
        val (_, repo) = escena()
        repo.guardar(
            Paciente("uuid-1", "30111222", "ANA", "PRUEBA", telefono = "111", mail = "local@ejemplo.test", obraSocial = "OS LOCAL", nombreMadre = "MADRE DE PRUEBA"),
            vincularA = medico,
        )

        val r = repo.reemplazarPorRemoto("uuid-1", Paciente("tobb-p7", "30111222", "ANA MARIA", "PRUEBA", telefono = "222", obraSocial = "OS TURNOS"))

        assertEquals("ANA MARIA", r.nombre)
        assertEquals("222", r.telefono, "mandan los datos de turnos")
        assertEquals("OS TURNOS", r.obraSocial)
        assertEquals("local@ejemplo.test", r.mail, "turnos no trae mail: no se pisa con vacío")
        assertEquals("MADRE DE PRUEBA", r.nombreMadre, "un dato que turnos no conoce se conserva")
        assertEquals(r, repo.porId("tobb-p7"))
    }

    @Test
    fun reemplazar_deja_la_ficha_de_turnos_sin_dirty() = runBlocking {
        val (db, repo) = escena()
        repo.guardar(Paciente("uuid-1", "30111222", "ANA", "PRUEBA"), vincularA = medico)

        repo.reemplazarPorRemoto("uuid-1", remota())

        assertFalse(db.pacientesQueries.pacientesDirty().lista { it.id }.contains("tobb-p7"), "el dueño es turnos: no queda dirty")
    }

    @Test
    fun reemplazar_es_idempotente() = runBlocking {
        val (db, repo) = escena()
        db.sembrarFichaConTodo("uuid-1")

        val primera = repo.reemplazarPorRemoto("uuid-1", remota())
        val vinculosDespues = db.pacientesQueries.vinculosDePaciente("tobb-p7").lista { it.toModel() }
        val referenciasDespues = db.referenciasA("tobb-p7")
        val segunda = repo.reemplazarPorRemoto("uuid-1", remota())

        assertEquals(primera, segunda)
        assertEquals(1, db.filasConDni("30111222"))
        assertEquals(vinculosDespues, db.pacientesQueries.vinculosDePaciente("tobb-p7").lista { it.toModel() })
        assertEquals(referenciasDespues, db.referenciasA("tobb-p7"))
        assertEquals(0, db.referenciasA("uuid-1").values.sum())
    }

    @Test
    fun reemplazar_cuando_la_ficha_de_turnos_ya_estaba_en_el_dispositivo_las_fusiona() = runBlocking {
        val (db, repo) = escena()
        // La remota llegó antes por una sincronización: con su vínculo de otro médico, uno inactivo del propio y un extra de HC.
        db.pacientesQueries.upsertPaciente(Paciente("tobb-p7", "30111222", "ANA", "PRUEBA", telefono = "999").toRow(1L, dirty = false))
        db.pacientesQueries.upsertMedicoPaciente(MedicoPaciente(otroMedico, "tobb-p7").toRow(1L, dirty = false))
        db.pacientesQueries.upsertMedicoPaciente(MedicoPaciente(medico, "tobb-p7", activo = false).toRow(1L, dirty = false))
        db.pacientesQueries.upsertPacienteExtra(PacienteExtra("tobb-p7", "pediatria", "hc_paciente_id", "12").toRow(1L, dirty = false))
        db.sembrarFichaConTodo("uuid-1", hcPacienteId = "77")

        val r = repo.reemplazarPorRemoto("uuid-1", remota())

        assertEquals("tobb-p7", r.id)
        assertEquals(1, db.filasConDni("30111222"), "queda una sola fila")
        assertNull(repo.porId("uuid-1"))
        val vinculos = db.pacientesQueries.vinculosDePaciente("tobb-p7").lista { it.toModel() }.associateBy { it.medicoId }
        assertEquals(setOf(medico, otroMedico), vinculos.keys, "ni se pierde el del otro médico ni se duplica el propio")
        assertTrue(assertNotNull(vinculos[medico]).activo, "el médico que dio el alta queda activo")
        assertEquals(1, db.vinculosEnBruto("tobb-p7", medico), "la clave (médico, paciente) no se duplica")
        assertEquals("12", db.pacientesQueries.extrasDePaciente("tobb-p7", "pediatria").lista { it }.single { it.clave == "hc_paciente_id" }.valor, "ante un mismo extra gana el que ya estaba con turnos")
        assertEquals(0, db.referenciasA("uuid-1").values.sum(), "nada apunta al id viejo: ${db.referenciasA("uuid-1")}")
        assertEquals(TABLAS_CON_PACIENTE_ID.associateWith { 1 }, db.referenciasA("tobb-p7").filterKeys { it in TABLAS_CON_PACIENTE_ID })
    }

    @Test
    fun reemplazar_si_la_ficha_local_ya_no_esta_solo_guarda_la_de_turnos() = runBlocking {
        val (db, repo) = escena()

        val r = repo.reemplazarPorRemoto("uuid-que-no-existe", remota())

        assertEquals(remota(), r)
        assertEquals(1, db.filasConDni("30111222"))
        assertTrue(repo.vinculosDe("tobb-p7").isEmpty(), "no inventa vínculos")
    }

    @Test
    fun reemplazar_no_toca_a_otros_pacientes() = runBlocking {
        val (db, repo) = escena()
        db.sembrarFichaConTodo("uuid-1")
        db.sembrarFichaConTodo("uuid-otro", dni = "30999888", sufijo = "-otro")

        repo.reemplazarPorRemoto("uuid-1", remota())

        assertNotNull(repo.porId("uuid-otro"))
        assertEquals(TABLAS_CON_PACIENTE_ID.associateWith { 1 }, db.referenciasA("uuid-otro").filterKeys { it in TABLAS_CON_PACIENTE_ID })
        assertEquals(1, db.pacientesQueries.vinculosDePaciente("uuid-otro").lista { it }.size)
    }

    @Test
    fun reemplazar_con_el_mismo_id_solo_actualiza_la_ficha() = runBlocking {
        val (db, repo) = escena()
        db.pacientesQueries.upsertPaciente(Paciente("tobb-p7", "30111222", "ANA", "PRUEBA", telefono = "111").toRow(1L))
        db.pacientesQueries.upsertMedicoPaciente(MedicoPaciente(medico, "tobb-p7").toRow(1L, dirty = false))

        val r = repo.reemplazarPorRemoto("tobb-p7", remota())

        assertEquals("222", r.telefono)
        assertEquals(listOf("tobb-p7"), repo.observarPagina(medico, "", 50).first().map { it.id })
        assertEquals(1, db.filasConDni("30111222"))
    }

    // ---- armado ----

    private fun remota() = Paciente("tobb-p7", "30111222", "ANA MARIA", "PRUEBA", telefono = "222", obraSocial = "OS TURNOS")

    private data class Escena(val db: Salud360Db, val repo: PacientesRepository)

    private suspend fun escena(): Escena {
        val archivo = File.createTempFile("salud360-pacientes", ".db").also { it.delete(); it.deleteOnExit() }
        val db = createDatabase(DriverFactory(archivo.absolutePath))
        return Escena(db, PacientesRepository(db))
    }

    /** Filas no borradas con ese DNI. */
    private suspend fun Salud360Db.filasConDni(dni: String): Int =
        pacientesQueries.pacientes().lista { it }.count { it.dni == dni }

    /** Filas de `medico_paciente` (incluso las borradas) de ese par. */
    private suspend fun Salud360Db.vinculosEnBruto(pacienteId: String, medicoId: String): Int =
        pacientesQueries.vinculosDePaciente(pacienteId).lista { it }.count { it.medico_id == medicoId }

    /** Cuántas filas de cada tabla con `paciente_id` apuntan a ese paciente (con las consultas que ve el resto del código). */
    private suspend fun Salud360Db.referenciasA(id: String): Map<String, Int> = mapOf(
        "turno" to turnosQueries.turnosDePaciente(id).lista { it }.size,
        "receta" to turnosQueries.recetasDeMedico(medico).lista { it.paciente_id }.count { it == id },
        "consulta" to historiaClinicaQueries.consultasDePacienteTodas(id).lista { it }.size,
        "antecedente" to historiaClinicaQueries.antecedentesDePaciente(id, "pediatria").lista { it }.size,
        "registro_clinico" to historiaClinicaQueries.registrosDePacienteTodos(id, "pediatria").lista { it }.size,
        "laboratorio" to historiaClinicaQueries.laboratoriosDePaciente(id, "pediatria", "general").lista { it }.size,
        "archivo" to historiaClinicaQueries.archivosDePacienteTodos(id).lista { it }.size,
        "pendiente" to historiaClinicaQueries.pendientesDePaciente(id, medico).lista { it }.size,
        "vacuna_aplicada" to historiaClinicaQueries.vacunasDePaciente(id).lista { it }.size,
    )

    /**
     * Deja una ficha local con un vínculo, dos extras y una fila en cada tabla que referencia a `paciente_id`.
     * Los ids de las filas llevan el sufijo para poder sembrar dos pacientes en la misma base.
     */
    private suspend fun Salud360Db.sembrarFichaConTodo(id: String, dni: String = "30111222", sufijo: String = "", hcPacienteId: String = "12") {
        val ahora = 1L
        pacientesQueries.upsertPaciente(Paciente(id, dni, "ANA", "PRUEBA", telefono = "111").toRow(ahora))
        pacientesQueries.upsertMedicoPaciente(MedicoPaciente(medico, id).toRow(ahora))
        pacientesQueries.upsertPacienteExtra(PacienteExtra(id, "pediatria", "hc_paciente_id", hcPacienteId).toRow(ahora))
        pacientesQueries.upsertPacienteExtra(PacienteExtra(id, "pediatria", "solo_local", "dato").toRow(ahora))
        turnosQueries.upsertTurno(Turno("turno$sufijo", id, medico, "tobb-c1", fecha, LocalTime(9, 0)).toRow(ahora))
        turnosQueries.upsertReceta(Receta("receta$sufijo", id, medico, null, "motivo de prueba").toRow(ahora))
        historiaClinicaQueries.upsertConsulta(Consulta("consulta$sufijo", id, medico, "pediatria", "control", fecha).toRow(ahora))
        historiaClinicaQueries.upsertAntecedente(Antecedente("antecedente$sufijo", id, "pediatria", "personales", "asma").toRow(ahora))
        historiaClinicaQueries.upsertRegistro(RegistroClinico("registro$sufijo", id, null, "pediatria", "estudio").toRow(ahora))
        historiaClinicaQueries.upsertLaboratorio(Laboratorio("laboratorio$sufijo", id, null, "pediatria", "general", fecha).toRow(ahora))
        historiaClinicaQueries.upsertArchivo(Archivo("archivo$sufijo", id, null, null, "estudios", "estudio.pdf", "application/pdf").toRow(ahora))
        historiaClinicaQueries.upsertPendiente(Pendiente("pendiente$sufijo", id, medico, "pedir estudio").toRow(ahora))
        historiaClinicaQueries.upsertVacuna(VacunaAplicada("vacuna$sufijo", id, "BCG").toRow(ahora))
    }

    private companion object {
        /** Las tablas locales con una columna `paciente_id` que la fusión tiene que repuntar (sin contar `paciente_extra` ni `medico_paciente`). */
        val TABLAS_CON_PACIENTE_ID = setOf(
            "turno", "receta", "consulta", "antecedente", "registro_clinico", "laboratorio", "archivo", "pendiente", "vacuna_aplicada",
        )
    }
}
