package com.salud360.core.data.repos

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.tobb.tobbJson
import com.salud360.core.data.lista
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.tobb.TobbPerfil
import com.salud360.core.model.turnos.EspecialidadTurnos
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 001 RF-29 (tanda D, T48): una habilitación de `hepatologia` que viene de turnos cuenta como la HC general en
 * todo lo que lee la base, y lo que la app guarda queda limpio. Sobre una base SQLite de verdad en un archivo
 * temporal; las dos escrituras de `AgendaTurnosOnline` necesitan red y no se prueban acá.
 */
class HistoriasHabilitadasTest {

    private fun conBase(bloque: suspend (Salud360Db, SqlDriver) -> Unit) {
        val archivo = File.createTempFile("salud360-hc-habilitadas", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(Salud360Db(driver), driver)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    /** Un médico como lo dejó una versión anterior de la app: el CSV de la base tal cual lo informó turnos. */
    private suspend fun medicoGuardado(db: Salud360Db, id: String, vararg hc: String) {
        db.authQueries.upsertUsuario(Usuario("u-$id", "$id@prueba.test", "Prueba", "Medico", Rol.MEDICO).toRow(0, dirty = false))
        db.authQueries.upsertMedico(
            Medico(id, "u-$id", "Prueba", "Medico", especialidadesHc = hc.toList()).toRow(0, dirty = false),
        )
    }

    private fun perfil(medico: String): TobbPerfil = tobbJson.decodeFromString(
        TobbPerfil.serializer(),
        """{"usuario":{"id":12,"nombre":"Prueba","email":"Prueba@Medico.test","tipo":2},"rol":"medico","medico":$medico}""",
    )

    @Test
    fun hepatologia_de_turnos_se_lee_como_la_hc_general() = conBase { db, driver ->
        medicoGuardado(db, "tobb-m1", "hepatologia")

        val m = db.authQueries.medicoPorId("tobb-m1").uno { it.toModel() }!!
        assertEquals(listOf("clinica"), m.especialidadesHc)

        val usuario = db.authQueries.usuarioPorEmail("tobb-m1@prueba.test").uno { it.toModel() }!!
        assertEquals(listOf("clinica"), resolverPerfil(db, usuario)!!.medico!!.especialidades)

        // Leer no reescribe nada: la fila queda como la informó turnos y sin `dirty`.
        assertEquals("hepatologia", texto(driver, "SELECT especialidades_hc FROM medico WHERE id = 'tobb-m1'"))
        assertEquals(0L, numero(driver, "SELECT dirty FROM medico WHERE id = 'tobb-m1'"))
    }

    @Test
    fun sin_repetidos_y_sin_perder_las_otras_hc() = conBase { db, _ ->
        medicoGuardado(db, "tobb-m1", "pediatria", "hepatologia", "clinica")
        medicoGuardado(db, "tobb-m2", "pediatria", "hepatologia", "hematologia")

        assertEquals(listOf("pediatria", "clinica"), db.authQueries.medicoPorId("tobb-m1").uno { it.toModel() }!!.especialidadesHc)
        assertEquals(
            listOf("pediatria", "clinica", "hematologia"),
            db.authQueries.medicoPorId("tobb-m2").uno { it.toModel() }!!.especialidadesHc,
        )
        // Las listas que usan el administrador y las carteras salen del mismo `toModel`.
        assertEquals(
            listOf(listOf("pediatria", "clinica"), listOf("pediatria", "clinica", "hematologia")),
            db.authQueries.medicos().lista { it.toModel().especialidadesHc },
        )
    }

    @Test
    fun la_especialidad_con_codigo_hepatologia_se_lee_como_clinica() = conBase { db, _ ->
        db.authQueries.upsertEspecialidad(EspecialidadTurnos("tobb-e5", "Hepatología", codigoHc = "hepatologia").toRow(0, dirty = false))
        db.authQueries.upsertEspecialidad(EspecialidadTurnos("tobb-e6", "Pediatría", codigoHc = "pediatria").toRow(0, dirty = false))
        db.authQueries.upsertEspecialidad(EspecialidadTurnos("tobb-e7", "Otra", codigoHc = null).toRow(0, dirty = false))

        assertEquals("clinica", db.authQueries.especialidadPorId("tobb-e5").uno { it.toModel() }!!.codigoHc)
        assertEquals("pediatria", db.authQueries.especialidadPorId("tobb-e6").uno { it.toModel() }!!.codigoHc)
        assertEquals(null, db.authQueries.especialidadPorId("tobb-e7").uno { it.toModel() }!!.codigoHc)
    }

    @Test
    fun importar_deja_la_base_limpia_con_lo_que_informa_turnos() = conBase { db, driver ->
        val p = perfil("""{"id":1,"nombre":"Ana","apellido":"Prueba","consultorio_id":0,"historias_clinicas":["hepatologia","pediatria"]}""")
        TurnosImportador(db).importar(p)

        assertEquals("clinica,pediatria", texto(driver, "SELECT especialidades_hc FROM medico WHERE id = 'tobb-m1'"))
        assertEquals(0L, numero(driver, "SELECT dirty FROM medico WHERE id = 'tobb-m1'"))
        assertEquals(listOf("clinica", "pediatria"), db.authQueries.medicoPorId("tobb-m1").uno { it.toModel() }!!.especialidadesHc)
    }

    @Test
    fun importar_con_las_dos_juntas_deja_una_sola_clinica() = conBase { db, driver ->
        val p = perfil("""{"id":1,"nombre":"Ana","apellido":"Prueba","consultorio_id":0,"historias_clinicas":[" Hepatologia ","clinica","HEPATOLOGIA"]}""")
        TurnosImportador(db).importar(p)

        assertEquals("clinica", texto(driver, "SELECT especialidades_hc FROM medico WHERE id = 'tobb-m1'"))
    }

    @Test
    fun sin_historias_clinicas_en_el_perfil_vale_la_especialidad_de_turnos() = conBase { db, driver ->
        val p = perfil("""{"id":1,"nombre":"Ana","apellido":"Prueba","consultorio_id":0,"especialidad":"Hepatología"}""")
        TurnosImportador(db).importar(p)

        assertEquals("clinica", texto(driver, "SELECT especialidades_hc FROM medico WHERE id = 'tobb-m1'"))
    }

    @Test
    fun sin_historias_clinicas_en_el_perfil_se_conserva_lo_del_dispositivo_ya_limpio() = conBase { db, driver ->
        medicoGuardado(db, "tobb-m1", "pediatria", "hepatologia")
        val p = perfil("""{"id":1,"nombre":"Ana","apellido":"Prueba","consultorio_id":0}""")
        TurnosImportador(db).importar(p)

        assertEquals("pediatria,clinica", texto(driver, "SELECT especialidades_hc FROM medico WHERE id = 'tobb-m1'"))
    }

    @Test
    fun importar_la_especialidad_previa_con_hepatologia_la_guarda_como_clinica() = conBase { db, driver ->
        db.authQueries.upsertEspecialidad(EspecialidadTurnos("tobb-e5", "Hepatología", codigoHc = "hepatologia").toRow(0, dirty = false))
        val p = perfil("""{"id":1,"nombre":"Ana","apellido":"Prueba","consultorio_id":0,"especialidad_id":5,"especialidad":"Hepatología"}""")
        TurnosImportador(db).importar(p)

        assertEquals("clinica", texto(driver, "SELECT codigo_hc FROM especialidad WHERE id = 'tobb-e5'"))
        assertEquals(0L, numero(driver, "SELECT dirty FROM especialidad WHERE id = 'tobb-e5'"))
    }

    @Test
    fun una_especialidad_nueva_de_hepatologia_nace_como_clinica_y_las_otras_no_cambian() = conBase { db, driver ->
        TurnosImportador(db).importar(perfil("""{"id":1,"nombre":"Ana","apellido":"Prueba","consultorio_id":0,"especialidad_id":5,"especialidad":"Hepatología"}"""))
        TurnosImportador(db).importar(perfil("""{"id":2,"nombre":"Beto","apellido":"Prueba","consultorio_id":0,"especialidad_id":6,"especialidad":"Pediatría"}"""))

        assertEquals("clinica", texto(driver, "SELECT codigo_hc FROM especialidad WHERE id = 'tobb-e5'"))
        assertEquals("pediatria", texto(driver, "SELECT codigo_hc FROM especialidad WHERE id = 'tobb-e6'"))
    }

    private fun numero(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value

    private fun texto(driver: SqlDriver, sql: String): String? =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value
}
