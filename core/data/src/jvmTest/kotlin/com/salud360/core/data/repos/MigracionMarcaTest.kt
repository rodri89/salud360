package com.salud360.core.data.repos

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005 RF-8 (D26, T15): la migración `9.sqm` (v9 a v10) agrega `consulta.propia` (0 = histórico o de la web, 1 = propia
 * de la app). Todo lo que el dispositivo ya tenía queda como histórico y nada más cambia: ni `dirty` ni `updated_at` ni las
 * demás tablas. Sobre una base SQLite de verdad en un archivo temporal, sembrada con datos inventados.
 *
 * Una base de la v9 se arma con el esquema actual quitándole la columna nueva (y poniéndole `user_version = 9`), igual que en
 * `MigracionHepatologiaTest`; las filas se siembran **después** de quitarla, para que no vengan con la marca ya puesta.
 */
class MigracionMarcaTest {

    private val paciente = "p-prueba-1"
    private val medico = "m-prueba-1"
    private val fecha = LocalDate(2026, 10, 30)

    /** Cómo estaba cada tabla sembrada justo antes de migrar (columna por columna, como texto). */
    private class Antes(val consulta: List<List<String?>>, val columnasDeConsulta: List<String>, val otras: Map<String, List<List<String?>>>)

    private fun archivoTemporal() = File.createTempFile("salud360-migracion-marca", ".db").also { it.delete() }

    private fun ejecutar(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun columnas(driver: SqlDriver, tabla: String): List<String> =
        driver.executeQuery(null, "PRAGMA table_info($tabla)", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(1)!!) })
        }, 0).value

    /** Nombre, tipo, obligatoriedad, valor por omisión y clave primaria de cada columna, en orden. */
    private fun definicion(driver: SqlDriver, tabla: String): List<String> =
        driver.executeQuery(null, "PRAGMA table_info($tabla)", { c ->
            QueryResult.Value(buildList {
                while (c.next().value) add("${c.getString(1)}|${c.getString(2)}|${c.getLong(3)}|${c.getString(4)}|${c.getLong(5)}")
            })
        }, 0).value

    private fun volcado(driver: SqlDriver, tabla: String, columnas: List<String> = columnas(driver, tabla)): List<List<String?>> =
        driver.executeQuery(null, "SELECT ${columnas.joinToString(", ")} FROM $tabla ORDER BY rowid", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(List(columnas.size) { i -> c.getString(i) }) })
        }, 0).value

    private fun numero(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value

    private fun consulta(
        driver: SqlDriver, id: String, especialidad: String, estado: String = "CERRADA", tipo: String = "consulta",
        actualizada: Long = 1000, dirty: Int = 0, deleted: Int = 0, remoto: String = "",
    ) = ejecutar(
        driver,
        "INSERT INTO consulta (id, paciente_id, medico_id, especialidad, tipo, fecha, estado, edad_mostrar, updated_at, deleted, dirty, remoto_id) " +
            "VALUES ('$id', '$paciente', '$medico', '$especialidad', '$tipo', '2026-10-01', '$estado', '45 años', $actualizada, $deleted, $dirty, '$remoto')",
    )

    /** Una base como la dejó la v9 de la app: con el esquema actual sin la columna nueva y con consultas de cada origen. */
    private suspend fun crearV9(archivo: File): SqlDriver {
        val driver = DriverFactory(archivo.absolutePath).createDriver()
        ejecutar(driver, "ALTER TABLE consulta DROP COLUMN propia")
        ejecutar(driver, "PRAGMA user_version = 9")

        // De clínica, sucias (abierta con cambios sin enviar) y limpias.
        consulta(driver, "c-clinica-sucia", "clinica", estado = "ABIERTA", actualizada = 5005, dirty = 1)
        consulta(driver, "c-clinica-cerrada", "clinica", actualizada = 4004, dirty = 0, remoto = "")
        consulta(driver, "c-clinica-anulada", "clinica", estado = "ANULADA", tipo = "telemedicina", actualizada = 3003, dirty = 1)
        consulta(driver, "c-clinica-borrada", "clinica", actualizada = 2002, deleted = 1)
        // De pediatría, con su id en la API.
        consulta(driver, "c-pediatria-sucia", "pediatria", estado = "ABIERTA", tipo = "control", actualizada = 6006, dirty = 1, remoto = "r-88")
        consulta(driver, "c-pediatria-cerrada", "pediatria", tipo = "control", actualizada = 1001, remoto = "r-87")
        // De la web (lo que trajo la lectura): prefijo `hcc-` y `remoto_id`.
        consulta(driver, "hcc-c77", "clinica", actualizada = 7007, remoto = "77")
        consulta(driver, "hcc-c78", "clinica", estado = "ABIERTA", actualizada = 7008, remoto = "78")

        ejecutar(driver, "INSERT INTO seccion_valor (consulta_id, seccion, campo, valor, updated_at, deleted, dirty) VALUES ('c-clinica-sucia', 'motivo_consulta', 'texto', 'Control de rutina', 5005, 0, 1)")
        ejecutar(driver, "INSERT INTO examen_fisico (consulta_id, peso, talla, updated_at, deleted, dirty) VALUES ('c-clinica-sucia', '70', '170', 5005, 0, 1)")
        ejecutar(driver, "INSERT INTO paciente_extra (paciente_id, especialidad, clave, valor, updated_at, deleted, dirty) VALUES ('$paciente', 'clinica', 'k', 'v', 100, 0, 1)")
        ejecutar(driver, "INSERT INTO sync_estado(tabla, ultima_version, ultima_sync) VALUES ('paciente', 3, 0)")
        return driver
    }

    private val tablasQueNoSeTocan = listOf("seccion_valor", "examen_fisico", "paciente_extra", "sync_estado")

    private fun fotografiar(driver: SqlDriver) = Antes(
        consulta = volcado(driver, "consulta"),
        columnasDeConsulta = columnas(driver, "consulta"),
        otras = tablasQueNoSeTocan.associateWith { volcado(driver, it) },
    )

    /** La base de la v9, cerrada y vuelta a abrir: el driver la migra solo, como al arrancar la app. */
    private fun conBaseMigrada(bloque: suspend (SqlDriver, Antes) -> Unit) {
        val archivo = archivoTemporal()
        try {
            runBlocking {
                val antes = crearV9(archivo).let { v9 ->
                    try {
                        assertEquals(9L, numero(v9, "PRAGMA user_version"))
                        fotografiar(v9)
                    } finally {
                        v9.close()
                    }
                }
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(driver, antes)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    @Test
    fun todas_las_consultas_existentes_quedan_como_historicas() = conBaseMigrada { driver, antes ->
        assertEquals(8, antes.consulta.size)
        assertEquals(8L, numero(driver, "SELECT COUNT(*) FROM consulta"))
        assertEquals(8L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE propia = 0"))
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE propia <> 0"))
        val q = Salud360Db(driver).historiaClinicaQueries
        for (id in listOf("c-clinica-sucia", "c-clinica-cerrada", "c-pediatria-sucia", "hcc-c77", "hcc-c78")) {
            assertFalse(q.consultaPorId(id).uno { it.toModel().propia }!!, "consulta $id")
        }
    }

    @Test
    fun dirty_updated_at_y_el_resto_de_las_columnas_quedan_intactos() = conBaseMigrada { driver, antes ->
        // Las columnas de la v9, con sus valores, son las mismas fila por fila (incluidas `dirty`, `updated_at` y `remoto_id`).
        assertEquals(antes.consulta, volcado(driver, "consulta", antes.columnasDeConsulta))
        assertEquals(3L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE dirty = 1"))
        assertEquals(5005L, numero(driver, "SELECT updated_at FROM consulta WHERE id = 'c-clinica-sucia'"))
        // Y las demás tablas ni se enteran.
        for ((tabla, filas) in antes.otras) assertEquals(filas, volcado(driver, tabla), "tabla $tabla")
    }

    @Test
    fun la_columna_va_al_final_con_cero_por_omision_y_queda_igual_que_en_una_base_nueva() = conBaseMigrada { driver, antes ->
        assertEquals(antes.columnasDeConsulta + "propia", columnas(driver, "consulta"))
        assertEquals("propia|INTEGER|1|0|0", definicion(driver, "consulta").last())

        val archivoNuevo = archivoTemporal()
        try {
            val nueva = DriverFactory(archivoNuevo.absolutePath).createDriver()
            try {
                assertEquals(definicion(nueva, "consulta"), definicion(driver, "consulta"))
            } finally {
                nueva.close()
            }
        } finally {
            archivoNuevo.delete()
        }
    }

    @Test
    fun una_consulta_nueva_guardada_como_propia_queda_en_1_y_sin_la_marca_queda_en_0() = conBaseMigrada { driver, _ ->
        val q = Salud360Db(driver).historiaClinicaQueries
        val propia = Consulta("c-nueva-propia", paciente, medico, "clinica", "consulta", fecha, EstadoConsulta.ABIERTA, propia = true)
        val sinMarca = Consulta("c-nueva-sin-marca", paciente, medico, "clinica", "consulta", fecha, EstadoConsulta.ABIERTA)
        q.upsertConsulta(propia.toRow(9000, dirty = true))
        q.upsertConsulta(sinMarca.toRow(9001, dirty = true))

        assertEquals(1L, numero(driver, "SELECT propia FROM consulta WHERE id = 'c-nueva-propia'"))
        assertEquals(0L, numero(driver, "SELECT propia FROM consulta WHERE id = 'c-nueva-sin-marca'"))
        assertTrue(q.consultaPorId("c-nueva-propia").uno { it.toModel().propia }!!)
        assertFalse(q.consultaPorId("c-nueva-sin-marca").uno { it.toModel().propia }!!)
        // Lo demás de la fila se guardó como siempre.
        assertEquals(9000L, numero(driver, "SELECT updated_at FROM consulta WHERE id = 'c-nueva-propia'"))
        assertEquals(1L, numero(driver, "SELECT dirty FROM consulta WHERE id = 'c-nueva-propia'"))
        // Las históricas siguen en 0 (y solo hay una propia).
        assertEquals(1L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE propia = 1"))
    }

    @Test
    fun guardar_de_nuevo_una_consulta_historica_o_una_propia_no_cambia_la_marca() = conBaseMigrada { driver, _ ->
        val q = Salud360Db(driver).historiaClinicaQueries
        val propia = Consulta("c-propia", paciente, medico, "clinica", "consulta", fecha, EstadoConsulta.ABIERTA, propia = true)
        q.upsertConsulta(propia.toRow(9000))

        // Lo que hace el repositorio: leer, `copy` y volver a guardar.
        val historica = q.consultaPorId("c-clinica-cerrada").uno { it.toModel() }!!
        q.upsertConsulta(historica.copy(edadMostrar = "46 años").toRow(9100))
        q.upsertConsulta(q.consultaPorId("c-propia").uno { it.toModel() }!!.copy(estado = EstadoConsulta.CERRADA).toRow(9200))

        assertEquals(0L, numero(driver, "SELECT propia FROM consulta WHERE id = 'c-clinica-cerrada'"))
        assertEquals(1L, numero(driver, "SELECT propia FROM consulta WHERE id = 'c-propia'"))
        assertEquals("CERRADA", q.consultaPorId("c-propia").uno { it.estado })
    }

    @Test
    fun marcarPropia_marca_solo_esa_consulta_y_no_toca_dirty_ni_updated_at() = conBaseMigrada { driver, antes ->
        val q = Salud360Db(driver).historiaClinicaQueries

        q.marcarPropia("c-clinica-cerrada")

        assertEquals(1L, numero(driver, "SELECT propia FROM consulta WHERE id = 'c-clinica-cerrada'"))
        assertEquals(1L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE propia = 1"))
        // Con la columna nueva fuera, la tabla queda exactamente como estaba.
        assertEquals(antes.consulta, volcado(driver, "consulta", antes.columnasDeConsulta))
        // Marcar una que no existe no hace nada.
        q.marcarPropia("no-existe")
        assertEquals(1L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE propia = 1"))
    }

    @Test
    fun el_driver_deja_la_base_en_la_version_actual_y_abrirla_otra_vez_no_cambia_nada() {
        val archivo = archivoTemporal()
        try {
            runBlocking {
                crearV9(archivo).close()
                val primera = DriverFactory(archivo.absolutePath).createDriver()
                val despues = try {
                    assertEquals(Salud360Db.Schema.version, numero(primera, "PRAGMA user_version"))
                    Triple(volcado(primera, "consulta"), columnas(primera, "consulta"), numero(primera, "SELECT COUNT(*) FROM consulta"))
                } finally {
                    primera.close()
                }
                val segunda = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    assertEquals(Salud360Db.Schema.version, numero(segunda, "PRAGMA user_version"))
                    assertEquals(despues.first, volcado(segunda, "consulta"))
                    assertEquals(despues.second, columnas(segunda, "consulta"))
                    assertEquals(despues.third, numero(segunda, "SELECT COUNT(*) FROM consulta"))
                } finally {
                    segunda.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    /** El camino de la web: `migrate(guardada, version)` sobre el mismo driver, sin reabrir (`DriverFactory.wasmJs`). */
    @Test
    fun migrate_de_la_9_a_la_10_hace_lo_mismo_que_el_driver() {
        val archivo = archivoTemporal()
        try {
            runBlocking {
                val driver = crearV9(archivo)
                try {
                    val antes = fotografiar(driver)
                    Salud360Db.Schema.synchronous().migrate(driver, 9, 10)
                    assertEquals(antes.columnasDeConsulta + "propia", columnas(driver, "consulta"))
                    assertEquals(antes.consulta, volcado(driver, "consulta", antes.columnasDeConsulta))
                    assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE propia <> 0"))
                    for ((tabla, filas) in antes.otras) assertEquals(filas, volcado(driver, tabla), "tabla $tabla")
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    @Test
    fun la_version_del_esquema_es_la_10() {
        assertEquals(10L, Salud360Db.Schema.version)
    }
}
