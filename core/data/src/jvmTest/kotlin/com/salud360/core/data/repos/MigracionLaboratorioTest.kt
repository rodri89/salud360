package com.salud360.core.data.repos

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 005 RF-41 y RF-43 (D8, T80): la migración `10.sqm` (v10 a v11) agrega `remoto_id` a `laboratorio` y a `pendiente`: el id de
 * la fila en la web (o del texto único de la consulta, en el pendiente), vacío mientras no se envió. Todo lo que el dispositivo ya
 * tenía queda con `''` y nada más cambia: ni `dirty` ni `updated_at` ni las demás tablas. Sobre una base SQLite de verdad en un
 * archivo temporal, sembrada con datos inventados.
 *
 * Una base de la v10 se arma con el esquema actual quitándole las dos columnas nuevas (y poniéndole `user_version = 10`), igual que
 * en `MigracionMarcaTest`; las filas se siembran **después** de quitarlas, para que no vengan con el id ya puesto.
 */
class MigracionLaboratorioTest {

    private val paciente = "p-prueba-1"
    private val medico = "m-prueba-1"
    private val fecha = LocalDate(2026, 10, 30)

    /** Cómo estaban las tablas sembradas justo antes de migrar (columna por columna, como texto). */
    private class Antes(
        val laboratorio: List<List<String?>>,
        val columnasDeLaboratorio: List<String>,
        val pendiente: List<List<String?>>,
        val columnasDePendiente: List<String>,
        val otras: Map<String, List<List<String?>>>,
    )

    private fun archivoTemporal() = File.createTempFile("salud360-migracion-laboratorio", ".db").also { it.delete() }

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

    private fun laboratorio(
        driver: SqlDriver, id: String, especialidad: String, tipo: String = "laboratorio_general",
        consultaId: String? = null, fechaDeLaCarga: String = "2026-06-02", valores: String = "{\"hb\":\"13\"}",
        activo: Int = 1, actualizada: Long = 1000, deleted: Int = 0, dirty: Int = 0,
    ) = ejecutar(
        driver,
        "INSERT INTO laboratorio (id, paciente_id, consulta_id, especialidad, tipo, fecha, valores_json, activo, updated_at, deleted, dirty) " +
            "VALUES ('$id', '$paciente', ${consultaId?.let { "'$it'" } ?: "NULL"}, '$especialidad', '$tipo', '$fechaDeLaCarga', '$valores', " +
            "$activo, $actualizada, $deleted, $dirty)",
    )

    private fun pendiente(
        driver: SqlDriver, id: String, texto: String, consultaId: String? = null, resuelto: Int = 0,
        actualizado: Long = 1000, deleted: Int = 0, dirty: Int = 0,
    ) = ejecutar(
        driver,
        "INSERT INTO pendiente (id, paciente_id, medico_id, texto, consulta_id, resuelto, updated_at, deleted, dirty) " +
            "VALUES ('$id', '$paciente', '$medico', '$texto', ${consultaId?.let { "'$it'" } ?: "NULL"}, $resuelto, $actualizado, $deleted, $dirty)",
    )

    /** Una base como la dejó la v10 de la app: con el esquema actual sin las dos columnas nuevas y con filas de cada origen. */
    private suspend fun crearV10(archivo: File): SqlDriver {
        val driver = DriverFactory(archivo.absolutePath).createDriver()
        ejecutar(driver, "ALTER TABLE laboratorio DROP COLUMN remoto_id")
        ejecutar(driver, "ALTER TABLE pendiente DROP COLUMN remoto_id")
        ejecutar(driver, "PRAGMA user_version = 10")

        ejecutar(
            driver,
            "INSERT INTO consulta (id, paciente_id, medico_id, especialidad, tipo, fecha, estado, edad_mostrar, updated_at, deleted, dirty, remoto_id, propia) " +
                "VALUES ('c-propia', '$paciente', '$medico', 'clinica', 'consulta', '2026-10-01', 'ABIERTA', '45 años', 5005, 0, 1, '', 1)",
        )

        // De clínica: una del dispositivo (suya, con cambios sin enviar), una dada de baja y una sin consulta.
        laboratorio(driver, "l-clinica-sucia", "clinica", consultaId = "c-propia", actualizada = 5005, dirty = 1)
        laboratorio(driver, "l-clinica-borrada", "clinica", consultaId = "c-propia", actualizada = 2002, deleted = 1, dirty = 1)
        laboratorio(driver, "l-clinica-suelta", "clinica", tipo = "trombofilia", actualizada = 1500)
        // De pediatría: con el id de su API en la consulta y con una carga dada de baja.
        laboratorio(driver, "l-pediatria", "pediatria", tipo = "laboratorio_general", actualizada = 6006, dirty = 1)
        laboratorio(driver, "l-pediatria-baja", "pediatria", tipo = "laboratorio_general", activo = 0, actualizada = 1001)
        // De la web: el id ya trae el de la fila remota y `hcc-` (lectura).
        laboratorio(driver, "hcc-llaboratorio_general-77", "clinica", consultaId = "c-propia", actualizada = 7007)
        laboratorio(driver, "hcc-l-trombofilia-78", "clinica", tipo = "trombofilia", actualizada = 7008)

        // Pendientes: de clínica (sucios y resueltos), de pediatría y de la web.
        pendiente(driver, "p-clinica-1", "Control de presión", consultaId = "c-propia", actualizado = 5005, dirty = 1)
        pendiente(driver, "p-clinica-2", "Pedir laboratorio", consultaId = "c-propia", actualizado = 4004)
        pendiente(driver, "p-clinica-resuelto", "Ya resuelto", consultaId = "c-propia", resuelto = 1, actualizado = 3003)
        pendiente(driver, "p-clinica-borrado", "Borrado", consultaId = "c-propia", actualizado = 2002, deleted = 1, dirty = 1)
        pendiente(driver, "p-pediatria", "Control del niño sano", actualizado = 6006, dirty = 1)
        pendiente(driver, "hcc-p77", "Texto de la web", consultaId = "c-propia", actualizado = 7007)

        ejecutar(driver, "INSERT INTO seccion_valor (consulta_id, seccion, campo, valor, updated_at, deleted, dirty) VALUES ('c-propia', 'motivo_consulta', 'texto', 'Control de rutina', 5005, 0, 1)")
        ejecutar(driver, "INSERT INTO paciente_extra (paciente_id, especialidad, clave, valor, updated_at, deleted, dirty) VALUES ('$paciente', 'clinica', 'k', 'v', 100, 0, 1)")
        ejecutar(driver, "INSERT INTO sync_estado(tabla, ultima_version, ultima_sync) VALUES ('paciente', 3, 0)")
        return driver
    }

    private val tablasQueNoSeTocan = listOf("consulta", "seccion_valor", "paciente_extra", "sync_estado")

    private fun fotografiar(driver: SqlDriver) = Antes(
        laboratorio = volcado(driver, "laboratorio"),
        columnasDeLaboratorio = columnas(driver, "laboratorio"),
        pendiente = volcado(driver, "pendiente"),
        columnasDePendiente = columnas(driver, "pendiente"),
        otras = tablasQueNoSeTocan.associateWith { volcado(driver, it) },
    )

    /** La base de la v10, cerrada y vuelta a abrir: el driver la migra solo, como al arrancar la app. */
    private fun conBaseMigrada(bloque: suspend (SqlDriver, Antes) -> Unit) {
        val archivo = archivoTemporal()
        try {
            runBlocking {
                val antes = crearV10(archivo).let { v10 ->
                    try {
                        assertEquals(10L, numero(v10, "PRAGMA user_version"))
                        fotografiar(v10)
                    } finally {
                        v10.close()
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
    fun todas_las_filas_existentes_quedan_con_el_remoto_id_vacio() = conBaseMigrada { driver, antes ->
        assertEquals(7, antes.laboratorio.size)
        assertEquals(6, antes.pendiente.size)
        assertEquals(7L, numero(driver, "SELECT COUNT(*) FROM laboratorio"))
        assertEquals(7L, numero(driver, "SELECT COUNT(*) FROM laboratorio WHERE remoto_id = ''"))
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM laboratorio WHERE remoto_id <> ''"))
        assertEquals(6L, numero(driver, "SELECT COUNT(*) FROM pendiente"))
        assertEquals(6L, numero(driver, "SELECT COUNT(*) FROM pendiente WHERE remoto_id = ''"))
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM pendiente WHERE remoto_id <> ''"))
        // Las filas se leen como siempre y con el id vacío (las de la web también: su id se sabe por el `remoto_id` de la consulta).
        val q = Salud360Db(driver).historiaClinicaQueries
        for (id in listOf("l-clinica-sucia", "l-pediatria", "hcc-llaboratorio_general-77")) {
            assertEquals("", q.laboratorioPorId(id).uno { it.toModel().remotoId }, "laboratorio $id")
        }
        for (id in listOf("p-clinica-1", "p-pediatria", "hcc-p77")) {
            assertEquals("", q.pendientePorId(id).uno { it.toModel().remotoId }, "pendiente $id")
        }
    }

    @Test
    fun dirty_updated_at_y_el_resto_de_las_columnas_quedan_intactos() = conBaseMigrada { driver, antes ->
        // Las columnas de la v10, con sus valores, son las mismas fila por fila (incluidas `dirty`, `updated_at` y `activo`).
        assertEquals(antes.laboratorio, volcado(driver, "laboratorio", antes.columnasDeLaboratorio))
        assertEquals(antes.pendiente, volcado(driver, "pendiente", antes.columnasDePendiente))
        assertEquals(3L, numero(driver, "SELECT COUNT(*) FROM laboratorio WHERE dirty = 1"))
        assertEquals(5005L, numero(driver, "SELECT updated_at FROM laboratorio WHERE id = 'l-clinica-sucia'"))
        assertEquals(1L, numero(driver, "SELECT activo FROM laboratorio WHERE id = 'l-clinica-sucia'"))
        assertEquals(0L, numero(driver, "SELECT activo FROM laboratorio WHERE id = 'l-pediatria-baja'"))
        assertEquals(3L, numero(driver, "SELECT COUNT(*) FROM pendiente WHERE dirty = 1"))
        assertEquals(1L, numero(driver, "SELECT resuelto FROM pendiente WHERE id = 'p-clinica-resuelto'"))
        // Y las demás tablas ni se enteran (ni la consulta, que conserva su id de la web y su marca de propia).
        for ((tabla, filas) in antes.otras) assertEquals(filas, volcado(driver, tabla), "tabla $tabla")
        assertEquals(1L, numero(driver, "SELECT propia FROM consulta WHERE id = 'c-propia'"))
    }

    @Test
    fun la_columna_va_al_final_con_el_defecto_y_queda_igual_que_en_una_base_nueva() = conBaseMigrada { driver, antes ->
        assertEquals(antes.columnasDeLaboratorio + "remoto_id", columnas(driver, "laboratorio"))
        assertEquals(antes.columnasDePendiente + "remoto_id", columnas(driver, "pendiente"))
        assertEquals("remoto_id|TEXT|1|''|0", definicion(driver, "laboratorio").last())
        assertEquals("remoto_id|TEXT|1|''|0", definicion(driver, "pendiente").last())

        val archivoNuevo = archivoTemporal()
        try {
            val nueva = DriverFactory(archivoNuevo.absolutePath).createDriver()
            try {
                assertEquals(definicion(nueva, "laboratorio"), definicion(driver, "laboratorio"))
                assertEquals(definicion(nueva, "pendiente"), definicion(driver, "pendiente"))
            } finally {
                nueva.close()
            }
        } finally {
            archivoNuevo.delete()
        }
    }

    @Test
    fun una_fila_nueva_guarda_su_remoto_id_y_se_encuentra_por_el() = conBaseMigrada { driver, _ ->
        val q = Salud360Db(driver).historiaClinicaQueries

        q.upsertLaboratorio(Laboratorio("l-nueva", paciente, "c-propia", "clinica", "laboratorio_general", fecha, mapOf("hb" to "12"), remotoId = "881").toRow(9000, dirty = true))
        q.upsertPendiente(Pendiente("p-nueva", paciente, medico, "Control en un mes", "c-propia", remotoId = "991").toRow(9001, dirty = true))

        assertEquals("881", q.laboratorioPorId("l-nueva").uno { it.toModel().remotoId })
        assertEquals("991", q.pendientePorId("p-nueva").uno { it.toModel().remotoId })
        assertEquals("l-nueva", q.laboratorioPorRemotoId("881").uno { it.id })
        assertEquals("p-nueva", q.pendientePorRemotoId("991").uno { it.id })
        assertEquals(9000L, numero(driver, "SELECT updated_at FROM laboratorio WHERE id = 'l-nueva'"))
        assertEquals(1L, numero(driver, "SELECT dirty FROM laboratorio WHERE id = 'l-nueva'"))
        // Lo que no se envió no tiene id de la web: un id que no existe no encuentra ninguna fila.
        assertNull(q.laboratorioPorRemotoId("no-existe").executeAsOneOrNull())
        assertNull(q.pendientePorRemotoId("no-existe").executeAsOneOrNull())
        // Los pendientes de antes siguen sin id; solo el nuevo tiene uno (el defecto del modelo es vacío).
        assertEquals(6L, numero(driver, "SELECT COUNT(*) FROM pendiente WHERE remoto_id = ''"))
        assertEquals(1L, numero(driver, "SELECT COUNT(*) FROM pendiente WHERE remoto_id = '991'"))
    }

    @Test
    fun guardar_el_remoto_id_no_toca_dirty_ni_updated_at_ni_el_resto_de_la_fila() = conBaseMigrada { driver, antes ->
        val q = Salud360Db(driver).historiaClinicaQueries

        q.guardarRemotoIdLaboratorio("551", "l-clinica-sucia")
        q.guardarRemotoIdPendiente("552", "p-clinica-1")

        assertEquals("551", q.laboratorioPorId("l-clinica-sucia").uno { it.toModel().remotoId })
        assertEquals("552", q.pendientePorId("p-clinica-1").uno { it.toModel().remotoId })
        // Con las columnas nuevas fuera, las dos tablas quedan exactamente como estaban.
        assertEquals(antes.laboratorio, volcado(driver, "laboratorio", antes.columnasDeLaboratorio))
        assertEquals(antes.pendiente, volcado(driver, "pendiente", antes.columnasDePendiente))
        // Un id que no existe no hace nada.
        q.guardarRemotoIdLaboratorio("553", "no-existe")
        assertEquals(1L, numero(driver, "SELECT COUNT(*) FROM laboratorio WHERE remoto_id <> ''"))
    }

    @Test
    fun copy_y_toRow_conservan_el_remoto_id() = conBaseMigrada { driver, _ ->
        val q = Salud360Db(driver).historiaClinicaQueries
        q.guardarRemotoIdLaboratorio("661", "l-clinica-suelta")
        q.guardarRemotoIdPendiente("662", "p-clinica-2")

        // Lo que hace el repositorio: leer, `copy` y volver a guardar (cambia otra cosa, no el id de la web).
        val carga = q.laboratorioPorId("l-clinica-suelta").uno { it.toModel() }!!
        q.upsertLaboratorio(carga.copy(valores = mapOf("hb" to "14")).toRow(9100))
        val texto = q.pendientePorId("p-clinica-2").uno { it.toModel() }!!
        q.upsertPendiente(texto.copy(texto = "Pedir laboratorio y ecografía").toRow(9200))

        assertEquals("661", q.laboratorioPorId("l-clinica-suelta").uno { it.toModel().remotoId })
        assertEquals("662", q.pendientePorId("p-clinica-2").uno { it.toModel().remotoId })
        assertEquals(mapOf("hb" to "14"), q.laboratorioPorId("l-clinica-suelta").uno { it.toModel().valores })
        assertEquals("Pedir laboratorio y ecografía", q.pendientePorId("p-clinica-2").uno { it.texto })
        // Y las históricas sin id siguen sin él.
        assertEquals("", q.laboratorioPorId("l-clinica-sucia").uno { it.toModel().remotoId })
    }

    @Test
    fun el_driver_deja_la_base_en_la_version_actual_y_abrirla_otra_vez_no_cambia_nada() {
        val archivo = archivoTemporal()
        try {
            runBlocking {
                crearV10(archivo).close()
                val primera = DriverFactory(archivo.absolutePath).createDriver()
                val despues = try {
                    assertEquals(11L, numero(primera, "PRAGMA user_version"))
                    Triple(
                        volcado(primera, "laboratorio"),
                        columnas(primera, "pendiente"),
                        volcado(primera, "pendiente"),
                    )
                } finally {
                    primera.close()
                }
                val segunda = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    assertEquals(11L, numero(segunda, "PRAGMA user_version"))
                    assertEquals(despues.first, volcado(segunda, "laboratorio"))
                    assertEquals(despues.second, columnas(segunda, "pendiente"))
                    assertEquals(despues.third, volcado(segunda, "pendiente"))
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
    fun migrate_de_la_10_a_la_11_hace_lo_mismo_que_el_driver() {
        val archivo = archivoTemporal()
        try {
            runBlocking {
                val driver = crearV10(archivo)
                try {
                    val antes = fotografiar(driver)
                    Salud360Db.Schema.synchronous().migrate(driver, 10, 11)
                    assertEquals(antes.columnasDeLaboratorio + "remoto_id", columnas(driver, "laboratorio"))
                    assertEquals(antes.columnasDePendiente + "remoto_id", columnas(driver, "pendiente"))
                    assertEquals(antes.laboratorio, volcado(driver, "laboratorio", antes.columnasDeLaboratorio))
                    assertEquals(antes.pendiente, volcado(driver, "pendiente", antes.columnasDePendiente))
                    assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM laboratorio WHERE remoto_id <> ''"))
                    assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM pendiente WHERE remoto_id <> ''"))
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
    fun la_version_del_esquema_es_la_11() {
        assertEquals(11L, Salud360Db.Schema.version)
    }
}
