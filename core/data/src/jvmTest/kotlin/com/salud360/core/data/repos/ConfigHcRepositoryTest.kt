package com.salud360.core.data.repos

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.ConfigGuardada
import com.salud360.core.data.hcgeneral.EstadoConfigHc
import com.salud360.core.data.network.TobbException
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Caché de la configuración del médico (RF-23, RF-25, RF-26) sobre una base SQLite de verdad en un archivo
 * temporal, con una API falsa: sin red ni servidor.
 */
class ConfigHcRepositoryTest {

    private val medico = "tobb-m1"
    private val perfilA = PerfilHc(
        7, "Perfil de prueba A",
        listOf(PestaniaPerfil("Consulta", listOf(SeccionPerfil("nota", "Notas", setOf(CONSULTA, TELEMEDICINA))))),
    )
    private val perfilB = PerfilHc(
        7, "Perfil de prueba B",
        listOf(PestaniaPerfil("Otra", listOf(SeccionPerfil("conductas", "Conducta", setOf(CONSULTA))))),
    )

    /** API falsa: devuelve [respuesta] o lanza [fallo]; cuenta las llamadas. */
    private class ApiFalsa : ConfigHcApi {
        var respuesta: PerfilHc? = null
        var fallo: TobbException? = null
        var llamadas = 0
        override suspend fun config(): PerfilHc? {
            llamadas++
            fallo?.let { throw it }
            return respuesta
        }
    }

    private fun conBase(bloque: suspend (Salud360Db, SqlDriver) -> Unit) {
        val archivo = File.createTempFile("salud360-config", ".db").also { it.delete() }
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

    private val sinRed get() = TobbException(0, "red", "Sin conexión con la historia clínica de clinica")

    @Test
    fun guarda_y_relee_con_la_api_caida() = conBase { db, _ ->
        val api = ApiFalsa().apply { respuesta = perfilA }
        val repo = ConfigHcRepository(db, api)
        assertEquals(perfilA, repo.refrescar(medico))
        assertEquals(ConfigGuardada(perfilA), repo.guardada(medico))

        api.fallo = sinRed
        assertEquals(EstadoConfigHc.ConPerfil(perfilA), repo.estado(medico, conRed = true))
        // Sin red ni se pregunta: vale lo guardado.
        val antes = api.llamadas
        assertEquals(EstadoConfigHc.ConPerfil(perfilA), repo.estado(medico, conRed = false))
        assertEquals(antes, api.llamadas)
    }

    @Test
    fun sin_fila_y_sin_red_es_primera_vez_con_motivo_red() = conBase { db, _ ->
        val api = ApiFalsa().apply { respuesta = perfilA }
        val repo = ConfigHcRepository(db, api)
        val estado = repo.estado(medico, conRed = false)
        assertEquals("red", (estado as EstadoConfigHc.PrimeraVezSinConexion).codigo)
        assertEquals(0, api.llamadas)
        assertNull(repo.guardada(medico))

        api.fallo = sinRed
        assertEquals("red", (repo.estado(medico, conRed = true) as EstadoConfigHc.PrimeraVezSinConexion).codigo)
    }

    @Test
    fun fallo_de_la_api_sin_fila_trae_ese_codigo_y_mensaje() = conBase { db, _ ->
        val api = ApiFalsa().apply { fallo = TobbException(403, "no_vinculado", "Tu usuario no está vinculado") }
        val repo = ConfigHcRepository(db, api)
        assertEquals(
            EstadoConfigHc.PrimeraVezSinConexion("no_vinculado", "Tu usuario no está vinculado"),
            repo.estado(medico, conRed = true),
        )
        assertNull(repo.guardada(medico))
    }

    @Test
    fun sin_cliente_clinica_es_como_sin_conexion() = conBase { db, _ ->
        val repo = ConfigHcRepository(db, null)
        val e = assertFailsWith<TobbException> { repo.refrescar(medico) }
        assertEquals("red", e.codigo)
        assertEquals("red", (repo.estado(medico, conRed = true) as EstadoConfigHc.PrimeraVezSinConexion).codigo)

        // Con algo guardado (de antes, con cliente) vale eso.
        ConfigHcRepository(db, ApiFalsa().apply { respuesta = perfilA }).refrescar(medico)
        assertEquals(EstadoConfigHc.ConPerfil(perfilA), repo.estado(medico, conRed = true))
    }

    @Test
    fun perfil_null_es_sin_perfil_y_queda_guardado_como_tal() = conBase { db, _ ->
        val api = ApiFalsa().apply { respuesta = null }
        val repo = ConfigHcRepository(db, api)
        assertEquals(EstadoConfigHc.SinPerfil, repo.estado(medico, conRed = true))
        // Guardado "sin perfil" no es lo mismo que nunca recibido.
        assertEquals(ConfigGuardada(null), repo.guardada(medico))
        api.fallo = sinRed
        assertEquals(EstadoConfigHc.SinPerfil, repo.estado(medico, conRed = true))
    }

    @Test
    fun una_respuesta_nueva_reemplaza_la_anterior() = conBase { db, driver ->
        val api = ApiFalsa().apply { respuesta = perfilA }
        val repo = ConfigHcRepository(db, api)
        repo.refrescar(medico)
        api.respuesta = perfilB
        assertEquals(EstadoConfigHc.ConPerfil(perfilB), repo.estado(medico, conRed = true))
        assertEquals(ConfigGuardada(perfilB), repo.guardada(medico))
        assertEquals(1L, consultar(driver, "SELECT count(*) FROM hc_config"))
        // Cada médico tiene la suya.
        assertNull(repo.guardada("tobb-m2"))
    }

    @Test
    fun un_fallo_no_borra_lo_guardado() = conBase { db, _ ->
        val api = ApiFalsa().apply { respuesta = perfilA }
        val repo = ConfigHcRepository(db, api)
        repo.refrescar(medico)
        api.fallo = TobbException(403, "licencia_vencida", "Tu licencia está vencida")
        val e = assertFailsWith<TobbException> { repo.refrescar(medico) }
        assertEquals("licencia_vencida", e.codigo)
        assertEquals(ConfigGuardada(perfilA), repo.guardada(medico))
    }

    /** API que tarda [demora] en contestar [respuesta]. */
    private class ApiLenta(private val demora: Duration, private val respuesta: PerfilHc?) : ConfigHcApi {
        override suspend fun config(): PerfilHc? {
            delay(demora)
            return respuesta
        }
    }

    @Test
    fun con_espera_y_respuesta_a_tiempo_vale_la_nueva() = conBase { db, _ ->
        ConfigHcRepository(db, ApiFalsa().apply { respuesta = perfilA }).refrescar(medico)
        val repo = ConfigHcRepository(db, ApiLenta(50.milliseconds, perfilB))
        assertEquals(EstadoConfigHc.ConPerfil(perfilB), repo.estadoConEspera(medico, espera = 2.seconds))
        assertEquals(ConfigGuardada(perfilB), repo.guardada(medico))
    }

    @Test
    fun espera_vencida_vale_la_guardada() = conBase { db, _ ->
        ConfigHcRepository(db, ApiFalsa().apply { respuesta = perfilA }).refrescar(medico)
        val repo = ConfigHcRepository(db, ApiLenta(10.seconds, perfilB))
        val inicio = TimeSource.Monotonic.markNow()
        assertEquals(EstadoConfigHc.ConPerfil(perfilA), repo.estadoConEspera(medico, espera = 200.milliseconds))
        assertTrue(inicio.elapsedNow() < 5.seconds, "no esperó a la API lenta")
        assertEquals(ConfigGuardada(perfilA), repo.guardada(medico))
    }

    @Test
    fun espera_vencida_sin_guardada_es_primera_vez_sin_conexion() = conBase { db, _ ->
        val repo = ConfigHcRepository(db, ApiLenta(10.seconds, perfilB))
        val estado = repo.estadoConEspera(medico, espera = 200.milliseconds)
        assertEquals("red", (estado as EstadoConfigHc.PrimeraVezSinConexion).codigo)
        assertNull(repo.guardada(medico))
    }

    @Test
    fun la_espera_por_defecto_es_de_5_segundos() {
        assertEquals(5.seconds, ConfigHcRepository.ESPERA)
    }

    @Test
    fun la_tabla_no_tiene_dirty_ni_deleted() = conBase { _, driver ->
        assertEquals(listOf("medico_id", "hc_codigo", "config_json", "recibida_en"), columnas(driver))
    }

    @Test
    fun la_migracion_6_crea_la_tabla_en_una_base_de_la_version_anterior() {
        assertEquals(7L, Salud360Db.Schema.version)
        val archivo = File.createTempFile("salud360-migracion", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                driver.execute(null, "DROP TABLE hc_config", 0)
                Salud360Db.Schema.synchronous().migrate(driver, 6, 7)
                val db = Salud360Db(driver)
                assertEquals(listOf("medico_id", "hc_codigo", "config_json", "recibida_en"), columnas(driver))
                val repo = ConfigHcRepository(db, ApiFalsa().apply { respuesta = perfilA })
                repo.refrescar(medico)
                assertEquals(ConfigGuardada(perfilA), repo.guardada(medico))
                driver.close()
            }
        } finally {
            archivo.delete()
        }
    }

    @Test
    fun al_abrir_una_base_de_la_version_6_el_driver_la_migra() {
        val archivo = File.createTempFile("salud360-migracion", ".db").also { it.delete() }
        try {
            runBlocking {
                // Base como la dejó la versión anterior de la app: sin la tabla y con user_version 6.
                DriverFactory(archivo.absolutePath).createDriver().apply {
                    execute(null, "DROP TABLE hc_config", 0)
                    execute(null, "PRAGMA user_version = 6", 0)
                    execute(null, "INSERT INTO sync_estado(tabla, ultima_version, ultima_sync) VALUES ('paciente', 3, 0)", 0)
                    close()
                }
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                val db = Salud360Db(driver)
                assertEquals(7L, consultar(driver, "PRAGMA user_version"))
                // Lo que ya había en la base sigue ahí.
                assertEquals(3L, consultar(driver, "SELECT ultima_version FROM sync_estado WHERE tabla = 'paciente'"))
                val repo = ConfigHcRepository(db, ApiFalsa().apply { respuesta = null })
                assertEquals(EstadoConfigHc.SinPerfil, repo.estado(medico, conRed = true))
                driver.close()
            }
        } finally {
            archivo.delete()
        }
    }

    private fun columnas(driver: SqlDriver): List<String> =
        driver.executeQuery(null, "PRAGMA table_info(hc_config)", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(1)!!) })
        }, 0).value

    private fun consultar(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value
}
