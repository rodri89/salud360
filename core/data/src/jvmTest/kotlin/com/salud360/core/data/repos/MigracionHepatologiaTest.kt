package com.salud360.core.data.repos

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.lista
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 001 RF-29 y RF-30 (tanda D, T49): la migración `8.sqm` (v8 a v9) pasa lo que el dispositivo guardó como
 * `hepatologia` a `clinica`, sin perder filas ni tocar `updated_at` ni `dirty`. Sobre una base SQLite de verdad en un
 * archivo temporal, sembrada con `INSERT` de columnas nombradas y datos inventados. Los choques, la idempotencia y el
 * conteo sin pérdida son de T50 (mismo archivo).
 */
class MigracionHepatologiaTest {

    private val paciente = "p-prueba-1"
    private val medico = "m-prueba-1"

    private fun conBase(bloque: suspend (SqlDriver) -> Unit) {
        val archivo = File.createTempFile("salud360-migracion-hepatologia", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(driver)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    private suspend fun migrar(driver: SqlDriver) {
        Salud360Db.Schema.synchronous().migrate(driver, 8, 9)
    }

    private fun ejecutar(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun columnas(driver: SqlDriver, tabla: String): List<String> =
        driver.executeQuery(null, "PRAGMA table_info($tabla)", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(1)!!) })
        }, 0).value

    /** Todas las filas de la tabla, columna por columna como texto, en el orden en que se guardaron. */
    private fun volcado(driver: SqlDriver, tabla: String): List<List<String?>> {
        val n = columnas(driver, tabla).size
        return driver.executeQuery(null, "SELECT * FROM $tabla ORDER BY rowid", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(List(n) { i -> c.getString(i) }) })
        }, 0).value
    }

    private fun numero(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value

    private fun texto(driver: SqlDriver, sql: String): String? =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value

    /** Lo que tenía la tabla antes, con `especialidad = 'hepatologia'` cambiado a `clinica` y nada más. */
    private fun conLaHcMovida(driver: SqlDriver, tabla: String, antes: List<List<String?>>): List<List<String?>> {
        val i = columnas(driver, tabla).indexOf("especialidad")
        return antes.map { fila -> fila.mapIndexed { j, v -> if (j == i && v == "hepatologia") "clinica" else v } }
    }

    private fun consulta(
        driver: SqlDriver, id: String, especialidad: String, estado: String = "CERRADA", tipo: String = "consulta",
        fecha: String = "2026-10-01", actualizada: Long = 1000, dirty: Int = 0, remoto: String = "",
    ) = ejecutar(
        driver,
        "INSERT INTO consulta (id, paciente_id, medico_id, especialidad, tipo, fecha, estado, edad_mostrar, updated_at, deleted, dirty, remoto_id) " +
            "VALUES ('$id', '$paciente', '$medico', '$especialidad', '$tipo', '$fecha', '$estado', '45 años', $actualizada, 0, $dirty, '$remoto')",
    )

    /** Una base como la dejó la versión 8 de la app, con datos de `hepatologia` y de otras HC en cada tabla que la migración toca. */
    private fun sembrar(driver: SqlDriver) {
        // Consultas: tres de hepatología (abierta con cambios sin enviar, cerrada sin enviar, anulada), una de clínica y una de pediatría.
        consulta(driver, "c-hepat-abierta", "hepatologia", estado = "ABIERTA", fecha = "2026-10-05", actualizada = 5005, dirty = 1)
        consulta(driver, "c-hepat-cerrada", "hepatologia", fecha = "2026-09-01", actualizada = 4001, remoto = "")
        consulta(driver, "c-hepat-anulada", "hepatologia", estado = "ANULADA", tipo = "telemedicina", actualizada = 3003)
        consulta(driver, "c-clinica", "clinica", fecha = "2026-08-01", actualizada = 2002, remoto = "r-77")
        consulta(driver, "c-pediatria", "pediatria", fecha = "2026-07-01", actualizada = 1001, dirty = 1, remoto = "r-88")

        // Lo que cuelga de esas consultas.
        ejecutar(driver, "INSERT INTO seccion_valor (consulta_id, seccion, campo, valor, updated_at, deleted, dirty) VALUES ('c-hepat-abierta', 'motivo_consulta', 'texto', 'Control de rutina', 5005, 0, 1)")
        ejecutar(driver, "INSERT INTO seccion_valor (consulta_id, seccion, campo, valor, updated_at, deleted, dirty) VALUES ('c-hepat-cerrada', 'motivo_consulta', 'texto', 'Estudios previos', 4001, 0, 0)")
        ejecutar(driver, "INSERT INTO examen_fisico (consulta_id, peso, talla, updated_at, deleted, dirty) VALUES ('c-hepat-abierta', '70', '170', 5005, 0, 1)")
        ejecutar(driver, "INSERT INTO diagnostico (id, medico_id, nombre, activo, updated_at, deleted, dirty) VALUES ('d-prueba-1', '$medico', 'Diagnóstico de prueba', 1, 100, 0, 0)")
        ejecutar(driver, "INSERT INTO consulta_diagnostico (consulta_id, diagnostico_id, updated_at, deleted, dirty) VALUES ('c-hepat-cerrada', 'd-prueba-1', 4001, 0, 0)")
        ejecutar(driver, "INSERT INTO archivo (id, paciente_id, consulta_id, seccion, nombre, mime, updated_at, deleted, dirty) VALUES ('ar-1', '$paciente', 'c-hepat-abierta', 'documentos', 'informe.pdf', 'application/pdf', 5005, 0, 1)")

        // Registros y laboratorios: uno de hepatología con contenido, uno de clínica y uno de pediatría.
        listOf("hepatologia" to 1, "clinica" to 0, "pediatria" to 1).forEach { (hc, dirty) ->
            ejecutar(driver, "INSERT INTO registro_clinico (id, paciente_id, consulta_id, especialidad, tipo, fecha, campos_json, activo, updated_at, deleted, dirty, remoto_id) VALUES ('r-$hc', '$paciente', NULL, '$hc', 'biopsia_hepatica', '2026-06-01', '{\"detalle\":\"F2 en $hc\"}', 1, 2500, 0, $dirty, '')")
            ejecutar(driver, "INSERT INTO laboratorio (id, paciente_id, consulta_id, especialidad, tipo, fecha, valores_json, activo, updated_at, deleted, dirty) VALUES ('l-$hc', '$paciente', 'c-$hc', '$hc', 'laboratorio_general', '2026-06-02', '{\"got\":\"40\"}', 1, 2600, 0, $dirty)")
        }

        // Antecedentes sin choque: cada HC con una clave distinta.
        listOf("hepatologia" to "cirrosis", "clinica" to "alergias", "pediatria" to "asma").forEach { (hc, clave) ->
            ejecutar(driver, "INSERT INTO antecedente (id, paciente_id, especialidad, categoria, clave, flag, detalle, consulta_id, activo, updated_at, deleted, dirty) VALUES ('an-$hc', '$paciente', '$hc', 'personales', '$clave', 1, 'Detalle de $clave', NULL, 1, 2700, 0, 1)")
        }

        // Texto libre que no es un código de HC: no se toca.
        ejecutar(driver, "INSERT INTO interconsultor (id, medico_id, nombre, apellido, especialidad, activo, updated_at, deleted, dirty) VALUES ('i-prueba-1', '$medico', 'Interconsultor', 'De Prueba', 'hepatologia', 1, 2800, 0, 0)")
        ejecutar(driver, "INSERT INTO turno (id, paciente_id, medico_id, consultorio_id, fecha, horario, especialidad, updated_at, deleted, dirty) VALUES ('t-prueba-1', '$paciente', '$medico', 'co-prueba-1', '2026-10-06', '09:00', 'hepatologia', 2900, 0, 0)")
        ejecutar(driver, "INSERT INTO turno (id, paciente_id, medico_id, consultorio_id, fecha, horario, especialidad, updated_at, deleted, dirty) VALUES ('t-prueba-2', '$paciente', '$medico', 'co-prueba-1', '2026-10-07', '10:00', 'Hepatología', 2901, 0, 0)")

        // La copia de la configuración solo se escribe con `clinica`.
        ejecutar(driver, "INSERT INTO hc_config (medico_id, hc_codigo, config_json, recibida_en) VALUES ('$medico', 'clinica', '{}', 1)")
    }

    @Test
    fun las_consultas_de_hepatologia_pasan_a_clinica_y_nada_mas_cambia() = conBase { driver ->
        sembrar(driver)
        val antes = volcado(driver, "consulta")
        assertEquals(5, antes.size)

        migrar(driver)

        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE especialidad = 'hepatologia'"))
        // Mismos ids, y cada fila igual a la de antes salvo `especialidad`: estado, fecha, tipo, updated_at, dirty, deleted y remoto_id.
        assertEquals(conLaHcMovida(driver, "consulta", antes), volcado(driver, "consulta"))
        assertEquals(
            listOf("clinica", "clinica", "clinica", "clinica", "pediatria"),
            volcado(driver, "consulta").map { it[columnas(driver, "consulta").indexOf("especialidad")] },
        )
        // La abierta con cambios sin enviar sigue sucia y sin tocar; la de pediatría, intacta.
        assertEquals("ABIERTA", texto(driver, "SELECT estado FROM consulta WHERE id = 'c-hepat-abierta'"))
        assertEquals(1L, numero(driver, "SELECT dirty FROM consulta WHERE id = 'c-hepat-abierta'"))
        assertEquals(5005L, numero(driver, "SELECT updated_at FROM consulta WHERE id = 'c-hepat-abierta'"))
        assertEquals("pediatria", texto(driver, "SELECT especialidad FROM consulta WHERE id = 'c-pediatria'"))
        assertEquals("r-88", texto(driver, "SELECT remoto_id FROM consulta WHERE id = 'c-pediatria'"))
    }

    @Test
    fun lo_que_cuelga_de_las_consultas_sigue_ahi_y_se_lee_por_su_consulta() = conBase { driver ->
        sembrar(driver)
        val tablas = listOf("seccion_valor", "examen_fisico", "consulta_diagnostico", "archivo", "diagnostico")
        val antes = tablas.associateWith { volcado(driver, it) }

        migrar(driver)

        // No tienen código de HC: quedan idénticas.
        tablas.forEach { assertEquals(antes.getValue(it), volcado(driver, it), "tabla $it") }
        // Y las consultas ya movidas las encuentran por las consultas de la app.
        val db = Salud360Db(driver)
        runBlocking {
            val q = db.historiaClinicaQueries
            assertEquals(listOf("Control de rutina"), q.valoresDeConsulta("c-hepat-abierta").lista { it.valor })
            assertEquals(listOf("Estudios previos"), q.valoresDeConsulta("c-hepat-cerrada").lista { it.valor })
            assertEquals("70", q.examenFisicoDeConsulta("c-hepat-abierta").uno { it.peso })
        }
    }

    @Test
    fun registros_laboratorios_y_antecedentes_sin_choque_pasan_con_su_contenido() = conBase { driver ->
        sembrar(driver)
        val tablas = listOf("registro_clinico", "laboratorio", "antecedente")
        val antes = tablas.associateWith { volcado(driver, it) }
        tablas.forEach { assertEquals(3, antes.getValue(it).size, "tabla $it") }

        migrar(driver)

        tablas.forEach { tabla ->
            assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM $tabla WHERE especialidad = 'hepatologia'"), "tabla $tabla")
            // Mismas filas, mismo contenido (`campos_json`, `valores_json`, `flag`, `detalle`), `activo`, `updated_at` y `dirty`.
            assertEquals(conLaHcMovida(driver, tabla, antes.getValue(tabla)), volcado(driver, tabla), "tabla $tabla")
            assertEquals("pediatria", texto(driver, "SELECT especialidad FROM $tabla WHERE id LIKE '%-pediatria'"), "tabla $tabla")
        }
        assertEquals("clinica", texto(driver, "SELECT especialidad FROM registro_clinico WHERE id = 'r-hepatologia'"))
        assertEquals("{\"detalle\":\"F2 en hepatologia\"}", texto(driver, "SELECT campos_json FROM registro_clinico WHERE id = 'r-hepatologia'"))
        assertEquals("{\"got\":\"40\"}", texto(driver, "SELECT valores_json FROM laboratorio WHERE id = 'l-hepatologia'"))
        assertEquals("clinica", texto(driver, "SELECT especialidad FROM antecedente WHERE id = 'an-hepatologia'"))
        assertEquals(1L, numero(driver, "SELECT activo FROM antecedente WHERE id = 'an-hepatologia'"))
        assertEquals("Detalle de cirrosis", texto(driver, "SELECT detalle FROM antecedente WHERE id = 'an-hepatologia'"))
    }

    @Test
    fun el_texto_libre_de_interconsultores_y_turnos_no_se_toca() = conBase { driver ->
        sembrar(driver)
        val antes = listOf("interconsultor", "turno").associateWith { volcado(driver, it) }

        migrar(driver)

        antes.forEach { (tabla, filas) -> assertEquals(filas, volcado(driver, tabla), "tabla $tabla") }
        assertEquals("hepatologia", texto(driver, "SELECT especialidad FROM interconsultor WHERE id = 'i-prueba-1'"))
        assertEquals("hepatologia", texto(driver, "SELECT especialidad FROM turno WHERE id = 't-prueba-1'"))
        assertEquals("Hepatología", texto(driver, "SELECT especialidad FROM turno WHERE id = 't-prueba-2'"))
    }

    @Test
    fun hc_config_no_tiene_filas_de_hepatologia_antes_ni_despues() = conBase { driver ->
        sembrar(driver)
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM hc_config WHERE hc_codigo = 'hepatologia'"))
        val antes = volcado(driver, "hc_config")

        migrar(driver)

        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM hc_config WHERE hc_codigo = 'hepatologia'"))
        assertEquals(antes, volcado(driver, "hc_config"))
    }

    @Test
    fun al_abrir_una_base_de_la_version_8_con_datos_el_driver_la_migra() {
        val archivo = File.createTempFile("salud360-migracion-hepatologia", ".db").also { it.delete() }
        try {
            runBlocking {
                // Base como la dejó la versión anterior de la app: con `user_version` 8 y datos.
                DriverFactory(archivo.absolutePath).createDriver().apply {
                    sembrar(this)
                    ejecutar(this, "INSERT INTO sync_estado(tabla, ultima_version, ultima_sync) VALUES ('paciente', 3, 0)")
                    ejecutar(this, "PRAGMA user_version = 8")
                    close()
                }
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    assertEquals(9L, numero(driver, "PRAGMA user_version"))
                    for (tabla in listOf("consulta", "registro_clinico", "laboratorio", "antecedente")) {
                        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM $tabla WHERE especialidad = 'hepatologia'"), "tabla $tabla")
                    }
                    assertEquals(4L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE especialidad = 'clinica'"))
                    assertEquals(1L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE especialidad = 'pediatria'"))
                    // Lo que ya tenía la base sigue ahí.
                    assertEquals(3L, numero(driver, "SELECT ultima_version FROM sync_estado WHERE tabla = 'paciente'"))
                    assertEquals("hepatologia", texto(driver, "SELECT especialidad FROM interconsultor WHERE id = 'i-prueba-1'"))
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    @Test
    fun la_version_del_esquema_es_la_9() {
        assertEquals(9L, Salud360Db.Schema.version)
    }
}
