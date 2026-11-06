package com.salud360.core.data.repos

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.lista
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec 001 RF-29 y RF-30 (tanda D, T49): la migración `8.sqm` (v8 a v9) pasa lo que el dispositivo guardó como
 * `hepatologia` a `clinica`, sin perder filas ni tocar `updated_at` ni `dirty`. Sobre una base SQLite de verdad en un
 * archivo temporal, sembrada con `INSERT` de columnas nombradas y datos inventados.
 *
 * T50 (mismo archivo) prueba la parte de la migración con datos que chocan —las dos HC con lo mismo—, que se corra dos veces
 * sin cambiar nada y que no se pierda ninguna fila, y lee el resultado con `HcRepository` como lo hace la app.
 *
 * **Límite conocido de `paciente_extra`** (`el_dato_de_hepatologia_frente_a_una_fila_borrada_de_clinica_queda_en_hepatologia`):
 * la migración mueve un dato de `hepatologia` solo si `clinica` no tiene esa clave, y mira también las filas con `deleted = 1`.
 * Si `clinica` tiene la clave borrada, el dato vivo de `hepatologia` NO pasa: queda en `hepatologia`, donde nada lo lee (no se
 * pierde ni se pisa, pero tampoco se ve en la HC general). Es a propósito: pasarlo exigiría reescribir una fila clínica de
 * `clinica` (la borrada), y una migración no edita datos clínicos. Si alguna vez hace falta, es una decisión aparte.
 */
class MigracionHepatologiaTest {

    private val paciente = "p-prueba-1"
    private val medico = "m-prueba-1"

    // T50: los pacientes y médicos de los choques (nada real).
    private val paciente2 = "p-prueba-2"
    private val paciente3 = "p-prueba-3"
    private val paciente4 = "p-prueba-4"
    private val paciente5 = "p-prueba-5"
    private val paciente6 = "p-prueba-6"
    private val ocultasClinica = "secciones_ocultas_clinica"
    private val ocultasHepatologia = "secciones_ocultas_hepatologia"
    private val tablasDeDatos = listOf(
        "consulta", "seccion_valor", "registro_clinico", "laboratorio", "antecedente", "paciente_extra", "medico_preferencia",
    )

    /** Las únicas tablas que `8.sqm` modifica (`seccion_valor` y compañía cuelgan de `consulta_id` y no se tocan). */
    private val tablasQueMueve = setOf(
        "consulta", "antecedente", "registro_clinico", "laboratorio", "paciente_extra", "medico_preferencia",
    )

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
    private fun volcado(driver: SqlDriver, tabla: String, donde: String = "1 = 1"): List<List<String?>> {
        val n = columnas(driver, tabla).size
        return driver.executeQuery(null, "SELECT * FROM $tabla WHERE $donde ORDER BY rowid", { c ->
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
        fecha: String = "2026-10-01", actualizada: Long = 1000, dirty: Int = 0, remoto: String = "", pacienteId: String = paciente,
    ) = ejecutar(
        driver,
        "INSERT INTO consulta (id, paciente_id, medico_id, especialidad, tipo, fecha, estado, edad_mostrar, updated_at, deleted, dirty, remoto_id) " +
            "VALUES ('$id', '$pacienteId', '$medico', '$especialidad', '$tipo', '$fecha', '$estado', '45 años', $actualizada, 0, $dirty, '$remoto')",
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
                    // Sin la columna de la `9.sqm`, que el driver agrega al migrar (si no, `ADD COLUMN` chocaría con la existente).
                    ejecutar(this, "ALTER TABLE consulta DROP COLUMN propia")
                    ejecutar(this, "PRAGMA user_version = 8")
                    close()
                }
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    assertEquals(10L, numero(driver, "PRAGMA user_version"))
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
    fun la_version_del_esquema_es_la_10() {
        // La `9.sqm` (marca de consulta propia, spec 005) la subió de 9 a 10; acá se prueba la `8.sqm`.
        assertEquals(10L, Salud360Db.Schema.version)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // T50: datos que chocan, idempotencia, sin pérdida y lectura con los repositorios
    // ---------------------------------------------------------------------------------------------------------------

    private fun antecedente(
        driver: SqlDriver, id: String, hc: String, clave: String, actualizada: Long, categoria: String = "personales",
        pacienteId: String = paciente2, activo: Int = 1, deleted: Int = 0, dirty: Int = if (hc == "hepatologia") 1 else 0,
    ) = ejecutar(
        driver,
        "INSERT INTO antecedente (id, paciente_id, especialidad, categoria, clave, flag, detalle, consulta_id, activo, updated_at, deleted, dirty) " +
            "VALUES ('$id', '$pacienteId', '$hc', '$categoria', '$clave', 1, 'Detalle de $id', NULL, $activo, $actualizada, $deleted, $dirty)",
    )

    private fun extra(
        driver: SqlDriver, pacienteId: String, hc: String, clave: String, valor: String, actualizada: Long = 100,
        deleted: Int = 0, dirty: Int = 1,
    ) = ejecutar(
        driver,
        "INSERT INTO paciente_extra (paciente_id, especialidad, clave, valor, updated_at, deleted, dirty) " +
            "VALUES ('$pacienteId', '$hc', '$clave', '$valor', $actualizada, $deleted, $dirty)",
    )

    private fun preferencia(
        driver: SqlDriver, medicoId: String, clave: String, valor: String, actualizada: Long, dirty: Int, deleted: Int = 0,
    ) = ejecutar(
        driver,
        "INSERT OR REPLACE INTO medico_preferencia (medico_id, clave, valor, updated_at, deleted, dirty) " +
            "VALUES ('$medicoId', '$clave', '$valor', $actualizada, $deleted, $dirty)",
    )

    /** (medico, clave) → valor, updated_at, deleted, dirty. */
    private fun preferencias(driver: SqlDriver): Map<Pair<String, String>, List<String?>> =
        volcado(driver, "medico_preferencia").associate { (it[0]!! to it[1]!!) to it.drop(2) }

    /** (paciente, especialidad, clave) → valor, updated_at, deleted, dirty. */
    private fun extras(driver: SqlDriver): Map<Triple<String, String, String>, List<String?>> =
        volcado(driver, "paciente_extra").associate { Triple(it[0]!!, it[1]!!, it[2]!!) to it.drop(3) }

    /** Lo que se espera de `antecedente`: todo igual salvo la HC movida y el `activo` de las perdedoras. */
    private fun antecedentesEsperados(driver: SqlDriver, antes: List<List<String?>>, perdedoras: Set<String>): List<List<String?>> {
        val activo = columnas(driver, "antecedente").indexOf("activo")
        return conLaHcMovida(driver, "antecedente", antes).map { f ->
            if (f[0] in perdedoras) f.toMutableList().also { it[activo] = "0" } else f
        }
    }

    private val perdedoras = setOf("an-a-c", "an-b-h", "an-c-h")

    /**
     * Datos que chocan entre `hepatologia` y `clinica`, todos de pacientes y médicos propios (no tocan lo de [sembrar], así que
     * se pueden usar los dos). Las dos HC cargadas para el mismo paciente es lo que pasa si el médico trabajó en "Clínica"
     * mientras sus datos viejos seguían en "Hepatología".
     */
    private fun sembrarChoques(driver: SqlDriver) {
        // Consultas abiertas del mismo paciente y médico: una en cada HC. p2: la de clinica es la más reciente; p3: la de hepatologia.
        consulta(driver, "c-p2-clinica", "clinica", estado = "ABIERTA", fecha = "2026-10-10", actualizada = 6000, dirty = 1, pacienteId = paciente2)
        consulta(driver, "c-p2-hepat", "hepatologia", estado = "ABIERTA", fecha = "2026-10-05", actualizada = 5500, dirty = 1, pacienteId = paciente2)
        consulta(driver, "c-p2-pediatria", "pediatria", estado = "ABIERTA", fecha = "2026-10-11", actualizada = 6500, pacienteId = paciente2)
        consulta(driver, "c-p3-clinica", "clinica", estado = "ABIERTA", fecha = "2026-10-02", actualizada = 4000, pacienteId = paciente3)
        consulta(driver, "c-p3-hepat", "hepatologia", estado = "ABIERTA", fecha = "2026-10-12", actualizada = 7000, dirty = 1, pacienteId = paciente3)
        ejecutar(driver, "INSERT INTO seccion_valor (consulta_id, seccion, campo, valor, updated_at, deleted, dirty) VALUES ('c-p2-hepat', 'motivo_consulta', 'texto', 'Motivo en hepatología', 5500, 0, 1)")

        // Antecedentes de p2 (categoría "personales" salvo que se diga). Sufijo -h = hepatologia, -c = clinica.
        antecedente(driver, "an-a-h", "hepatologia", "clave_a", 3000); antecedente(driver, "an-a-c", "clinica", "clave_a", 2000) // (a) hepatologia más nueva
        antecedente(driver, "an-b-h", "hepatologia", "clave_b", 2000); antecedente(driver, "an-b-c", "clinica", "clave_b", 3000) // (b) clinica más nueva
        antecedente(driver, "an-c-h", "hepatologia", "clave_c", 2500); antecedente(driver, "an-c-c", "clinica", "clave_c", 2500) // (c) empate
        antecedente(driver, "an-d-h", "hepatologia", "clave_a", 4000, categoria = "familiares") // (d) otra categoría: no choca con an-a-c
        antecedente(driver, "an-d2-h", "hepatologia", "clave_b", 9000, pacienteId = paciente3) // (d) otro paciente: no choca con an-b-c
        antecedente(driver, "an-d3-p", "pediatria", "clave_a", 9000, dirty = 1) // otra HC: no choca con nadie
        // (g) lo que ya estaba borrado o inactivo no cuenta como choque.
        antecedente(driver, "an-g1-c", "clinica", "clave_g1", 9000, deleted = 1); antecedente(driver, "an-g1-h", "hepatologia", "clave_g1", 1000)
        antecedente(driver, "an-g2-c", "clinica", "clave_g2", 9000, activo = 0); antecedente(driver, "an-g2-h", "hepatologia", "clave_g2", 1000)
        antecedente(driver, "an-g3-h", "hepatologia", "clave_g3", 9000, activo = 0); antecedente(driver, "an-g3-c", "clinica", "clave_g3", 1000)
        antecedente(driver, "an-g4-h", "hepatologia", "clave_g4", 9000, deleted = 1); antecedente(driver, "an-g4-c", "clinica", "clave_g4", 1000)

        // paciente_extra: la misma clave en las dos HC (p2), una sin choque, la de otro paciente, otras HC con la misma clave.
        extra(driver, paciente2, "hepatologia", "k_choca", "valor hep", dirty = 1)
        extra(driver, paciente2, "clinica", "k_choca", "valor clinica", actualizada = 200, dirty = 0)
        extra(driver, paciente2, "hepatologia", "k_libre", "solo hep", dirty = 1)
        extra(driver, paciente2, "pediatria", "k_choca", "valor pediatria")
        extra(driver, paciente2, "turnos", "k_choca", "valor turnos")
        extra(driver, paciente3, "hepatologia", "k_choca", "otro paciente")
        // Límite conocido: la de clinica está borrada y la de hepatologia viva.
        extra(driver, paciente4, "clinica", "k_borrada", "valor borrado de clinica", actualizada = 300, deleted = 1, dirty = 0)
        extra(driver, paciente4, "hepatologia", "k_borrada", "valor vivo de hepatologia", actualizada = 150)

        // Secciones ocultas: solo hepatologia, ambas, clinica vacía, hepatologia vacía, las dos vacías, clinica borrada, solo clinica,
        // hepatologia ya borrada, ninguna.
        preferencia(driver, "m-pref-a", ocultasHepatologia, "a,b", 100, dirty = 1)
        preferencia(driver, "m-pref-b", ocultasClinica, "x", 300, dirty = 0)
        preferencia(driver, "m-pref-b", ocultasHepatologia, "a,b", 200, dirty = 1)
        preferencia(driver, "m-pref-b", "filas_ocultas_clinica_laboratorio_general", "f1,f2", 250, dirty = 1)
        preferencia(driver, "m-pref-i", ocultasClinica, "k", 100, dirty = 1)
        preferencia(driver, "m-pref-i", ocultasHepatologia, "l", 400, dirty = 0)
        preferencia(driver, "m-pref-c", ocultasClinica, "", 100, dirty = 0)
        preferencia(driver, "m-pref-c", ocultasHepatologia, "a", 150, dirty = 1)
        preferencia(driver, "m-pref-d", ocultasClinica, "x", 100, dirty = 0)
        preferencia(driver, "m-pref-d", ocultasHepatologia, "", 150, dirty = 1)
        preferencia(driver, "m-pref-v", ocultasClinica, "", 100, dirty = 0)
        preferencia(driver, "m-pref-v", ocultasHepatologia, "", 150, dirty = 0)
        preferencia(driver, "m-pref-e", ocultasClinica, "z", 500, dirty = 0, deleted = 1)
        preferencia(driver, "m-pref-e", ocultasHepatologia, "a", 200, dirty = 1)
        preferencia(driver, "m-pref-f", ocultasClinica, "q", 100, dirty = 0)
        preferencia(driver, "m-pref-f", "secciones_ocultas_pediatria", "p1", 100, dirty = 1)
        preferencia(driver, "m-pref-g", ocultasHepatologia, "viejo", 100, dirty = 1, deleted = 1)
        preferencia(driver, "m-pref-h", "otra_preferencia", "1", 100, dirty = 0)
    }

    // ---- consultas ----

    @Test
    fun dos_consultas_abiertas_del_mismo_paciente_en_las_dos_HC_quedan_las_dos_abiertas_en_clinica() = conBase { driver ->
        sembrarChoques(driver)
        val antes = volcado(driver, "consulta")

        migrar(driver)

        // Mismas filas, mismo estado, `dirty`, `updated_at` y fecha: solo cambia la HC de las que venían de hepatologia.
        assertEquals(conLaHcMovida(driver, "consulta", antes), volcado(driver, "consulta"))
        for (id in listOf("c-p2-clinica", "c-p2-hepat", "c-p3-clinica", "c-p3-hepat")) {
            assertEquals("ABIERTA", texto(driver, "SELECT estado FROM consulta WHERE id = '$id'"), id)
            assertEquals("clinica", texto(driver, "SELECT especialidad FROM consulta WHERE id = '$id'"), id)
        }
        assertEquals(1L, numero(driver, "SELECT dirty FROM consulta WHERE id = 'c-p2-hepat'"))
        assertEquals(0L, numero(driver, "SELECT dirty FROM consulta WHERE id = 'c-p3-clinica'"))
        val q = Salud360Db(driver).historiaClinicaQueries
        // Las dos se listan (la más nueva primero) y la de pediatría no se mezcla.
        assertEquals(listOf("c-p2-clinica", "c-p2-hepat"), q.consultasDePaciente(paciente2, "clinica").lista { it.id })
        assertEquals(listOf("c-p3-hepat", "c-p3-clinica"), q.consultasDePaciente(paciente3, "clinica").lista { it.id })
        // `consultaAbierta` devuelve la modificada más reciente, venga de la HC que venga; la otra sigue abierta, no se borra.
        assertEquals("c-p2-clinica", q.consultaAbierta(paciente2, medico, "clinica").uno { it.id })
        assertEquals("c-p3-hepat", q.consultaAbierta(paciente3, medico, "clinica").uno { it.id })
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM consulta WHERE deleted = 1"))
    }

    // ---- antecedentes ----

    private fun activo(driver: SqlDriver, id: String) = numero(driver, "SELECT activo FROM antecedente WHERE id = '$id'")

    @Test
    fun antecedente_repetido_gana_el_de_hepatologia_si_es_mas_nuevo() = conBase { driver ->
        sembrarChoques(driver)
        migrar(driver)
        assertEquals(1L, activo(driver, "an-a-h"))
        assertEquals(0L, activo(driver, "an-a-c"))
    }

    @Test
    fun antecedente_repetido_gana_el_de_clinica_si_es_mas_nuevo() = conBase { driver ->
        sembrarChoques(driver)
        migrar(driver)
        assertEquals(1L, activo(driver, "an-b-c"))
        assertEquals(0L, activo(driver, "an-b-h"))
    }

    @Test
    fun antecedente_repetido_con_la_misma_fecha_gana_el_de_clinica() = conBase { driver ->
        sembrarChoques(driver)
        migrar(driver)
        assertEquals(1L, activo(driver, "an-c-c"))
        assertEquals(0L, activo(driver, "an-c-h"))
    }

    @Test
    fun la_misma_clave_en_otra_categoria_o_en_otro_paciente_no_choca() = conBase { driver ->
        sembrarChoques(driver)
        migrar(driver)
        assertEquals(1L, activo(driver, "an-d-h"), "otra categoría")
        assertEquals(1L, activo(driver, "an-a-h"))
        assertEquals(1L, activo(driver, "an-d2-h"), "otro paciente")
        assertEquals(1L, activo(driver, "an-b-c"))
        assertEquals(1L, activo(driver, "an-d3-p"), "otra HC")
    }

    @Test
    fun la_perdedora_no_se_borra_conserva_flag_detalle_y_nada_mas_cambia() = conBase { driver ->
        sembrarChoques(driver)
        val antes = volcado(driver, "antecedente")

        migrar(driver)

        // Mismas filas con el mismo `dirty`, `updated_at`, `flag` y `detalle`: solo la HC movida y el `activo` de las tres perdedoras.
        assertEquals(antecedentesEsperados(driver, antes, perdedoras), volcado(driver, "antecedente"))
        for (id in perdedoras) {
            assertEquals(1L, numero(driver, "SELECT flag FROM antecedente WHERE id = '$id'"), id)
            assertEquals("Detalle de $id", texto(driver, "SELECT detalle FROM antecedente WHERE id = '$id'"), id)
            assertEquals(0L, numero(driver, "SELECT deleted FROM antecedente WHERE id = '$id'"), id)
            assertEquals("clinica", texto(driver, "SELECT especialidad FROM antecedente WHERE id = '$id'"), id)
        }
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM antecedente WHERE especialidad = 'hepatologia'"))
    }

    @Test
    fun el_paciente_queda_con_un_solo_antecedente_activo_por_clave() = conBase { driver ->
        sembrarChoques(driver)
        migrar(driver)
        val q = Salud360Db(driver).historiaClinicaQueries

        val personales = q.antecedentesDeCategoria(paciente2, "clinica", "personales").lista { it.clave }
        assertEquals(personales.size, personales.toSet().size, "claves repetidas: $personales")
        assertEquals(
            setOf("clave_a", "clave_b", "clave_c", "clave_g1", "clave_g2", "clave_g3", "clave_g4"),
            personales.toSet(),
        )
        // Mirando todas las categorías, tampoco se repite (categoría, clave).
        val todas = q.antecedentesDePaciente(paciente2, "clinica").lista { it.categoria to it.clave }
        assertEquals(todas.size, todas.toSet().size, "repetidas: $todas")
        assertEquals(personales.size + 1, todas.size, "más el de la categoría 'familiares'")
    }

    @Test
    fun lo_borrado_o_inactivo_de_antes_no_cuenta_como_choque() = conBase { driver ->
        sembrarChoques(driver)
        migrar(driver)
        // La de clinica borrada o inactiva no le quita lugar a la de hepatologia viva (y no se toca).
        assertEquals(1L, activo(driver, "an-g1-h"))
        assertEquals(1L, numero(driver, "SELECT deleted FROM antecedente WHERE id = 'an-g1-c'"))
        assertEquals(1L, activo(driver, "an-g2-h"))
        assertEquals(0L, activo(driver, "an-g2-c"))
        // La de hepatologia inactiva o borrada no le quita lugar a la de clinica viva (aunque sea más nueva).
        assertEquals(1L, activo(driver, "an-g3-c"))
        assertEquals(0L, activo(driver, "an-g3-h"))
        assertEquals(1L, activo(driver, "an-g4-c"))
        assertEquals(1L, numero(driver, "SELECT deleted FROM antecedente WHERE id = 'an-g4-h'"))
    }

    // ---- antecedentes que se parecen a un choque y no lo son (H3 del reviewer) ----

    /**
     * Cuatro pares con la misma clave que NO chocan, armados para que la fila de `clinica` (y la de `hepatologia`) no pierda por
     * otra razón: en cada par, la que "ganaría" por fecha si el `EXISTS` mirara de menos es la otra. Cada condición del `EXISTS`
     * (categoría, paciente, HC) tiene su par, así que sacarla de `8.sqm` desactiva una fila que tenía que seguir activa.
     * Sufijo -h = hepatologia, -c = clinica, -p = pediatria.
     */
    private fun sembrarParecidos(driver: SqlDriver) {
        // (a) Misma clave del mismo paciente en otra categoría; la de clinica es más nueva (sin `c.categoria` perdería la de hepatologia).
        antecedente(driver, "an-pa-h", "hepatologia", "clave_pa", 1000, categoria = "familiares", pacienteId = paciente5)
        antecedente(driver, "an-pa-c", "clinica", "clave_pa", 2000, categoria = "personales", pacienteId = paciente5)
        // (b) Lo mismo al revés; la de hepatologia es más nueva (sin `h.categoria` perdería la de clinica).
        antecedente(driver, "an-pb-h", "hepatologia", "clave_pb", 3000, categoria = "personales", pacienteId = paciente5)
        antecedente(driver, "an-pb-c", "clinica", "clave_pb", 1000, categoria = "familiares", pacienteId = paciente5)
        // (c) Misma categoría y clave en otro paciente; la de clinica es más nueva (sin `c.paciente_id` perdería la de hepatologia).
        antecedente(driver, "an-pc-h", "hepatologia", "clave_pc", 1000, pacienteId = paciente5)
        antecedente(driver, "an-pc-c", "clinica", "clave_pc", 2000, pacienteId = paciente6)
        // (d) La misma clave, categoría y paciente en pediatría, más nueva (sin `h.especialidad = 'hepatologia'` perdería la de clinica).
        antecedente(driver, "an-pd-c", "clinica", "clave_pd", 1000, pacienteId = paciente5)
        antecedente(driver, "an-pd-p", "pediatria", "clave_pd", 5000, pacienteId = paciente5)
    }

    private val parecidos = listOf("an-pa-h", "an-pa-c", "an-pb-h", "an-pb-c", "an-pc-h", "an-pc-c", "an-pd-c", "an-pd-p")

    @Test
    fun la_misma_clave_en_otra_categoria_y_clinica_mas_nueva_no_desactiva_la_de_hepatologia() = conBase { driver ->
        sembrarParecidos(driver)
        migrar(driver)
        assertEquals(1L, activo(driver, "an-pa-h"))
        assertEquals(1L, activo(driver, "an-pa-c"))
    }

    @Test
    fun la_misma_clave_en_otra_categoria_y_hepatologia_mas_nueva_no_desactiva_la_de_clinica() = conBase { driver ->
        sembrarParecidos(driver)
        migrar(driver)
        assertEquals(1L, activo(driver, "an-pb-h"))
        assertEquals(1L, activo(driver, "an-pb-c"))
    }

    @Test
    fun la_misma_clave_de_otro_paciente_y_clinica_mas_nueva_no_desactiva_la_de_hepatologia() = conBase { driver ->
        sembrarParecidos(driver)
        migrar(driver)
        assertEquals(1L, activo(driver, "an-pc-h"))
        assertEquals(1L, activo(driver, "an-pc-c"))
    }

    @Test
    fun una_fila_de_pediatria_mas_nueva_no_desactiva_la_de_clinica() = conBase { driver ->
        sembrarParecidos(driver)
        migrar(driver)
        assertEquals(1L, activo(driver, "an-pd-c"))
        assertEquals(1L, activo(driver, "an-pd-p"))
    }

    @Test
    fun los_pares_que_no_chocan_cambian_solo_de_hc() = conBase { driver ->
        sembrarParecidos(driver)
        val antes = volcado(driver, "antecedente")
        assertEquals(parecidos.size, antes.size)

        migrar(driver)

        // Ninguna pierde el `activo`, el `dirty` ni la fecha: solo las de hepatologia pasan a clinica.
        assertEquals(conLaHcMovida(driver, "antecedente", antes), volcado(driver, "antecedente"))
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM antecedente WHERE activo = 0"))
    }

    // ---- paciente_extra ----

    @Test
    fun paciente_extra_que_choca_no_hace_fallar_la_migracion_y_cada_uno_conserva_su_valor() = conBase { driver ->
        sembrarChoques(driver)
        val antes = extras(driver)

        migrar(driver)

        val despues = extras(driver)
        // clinica conserva su valor; la de hepatologia queda donde estaba, sin tocar.
        assertEquals(antes.getValue(Triple(paciente2, "clinica", "k_choca")), despues.getValue(Triple(paciente2, "clinica", "k_choca")))
        assertEquals("valor clinica", despues.getValue(Triple(paciente2, "clinica", "k_choca"))[0])
        assertEquals(antes.getValue(Triple(paciente2, "hepatologia", "k_choca")), despues.getValue(Triple(paciente2, "hepatologia", "k_choca")))
        assertEquals("valor hep", despues.getValue(Triple(paciente2, "hepatologia", "k_choca"))[0])
        // La que no choca se mueve entera (mismo valor, `updated_at` y `dirty`); la de otro paciente también.
        assertEquals(antes.getValue(Triple(paciente2, "hepatologia", "k_libre")), despues.getValue(Triple(paciente2, "clinica", "k_libre")))
        assertEquals(antes.getValue(Triple(paciente3, "hepatologia", "k_choca")), despues.getValue(Triple(paciente3, "clinica", "k_choca")))
        assertEquals(setOf(Triple(paciente2, "hepatologia", "k_libre"), Triple(paciente3, "hepatologia", "k_choca")), antes.keys - despues.keys)
        // Las de otras HC, intactas.
        assertEquals(antes.getValue(Triple(paciente2, "pediatria", "k_choca")), despues.getValue(Triple(paciente2, "pediatria", "k_choca")))
        assertEquals(antes.getValue(Triple(paciente2, "turnos", "k_choca")), despues.getValue(Triple(paciente2, "turnos", "k_choca")))
        assertEquals(antes.size, despues.size)
        // Lo que lee la app de la HC general del paciente.
        assertEquals(
            mapOf("k_choca" to "valor clinica", "k_libre" to "solo hep"),
            PacientesRepository(Salud360Db(driver)).observarExtras(paciente2, "clinica").first(),
        )
    }

    /**
     * Límite conocido (ver el KDoc de la clase): la migración mira también las filas `deleted = 1` de `clinica`, así que un dato
     * vivo de `hepatologia` frente a uno borrado de `clinica` no se mueve. No se pierde y no se toca ninguna fila de `clinica`;
     * queda en `hepatologia`, donde la app ya no lo lee. Este test FIJA ese comportamiento; no es una promesa de que sea el ideal.
     */
    @Test
    fun el_dato_de_hepatologia_frente_a_una_fila_borrada_de_clinica_queda_en_hepatologia() = conBase { driver ->
        sembrarChoques(driver)
        val antes = extras(driver)

        migrar(driver)

        val despues = extras(driver)
        val borrada = Triple(paciente4, "clinica", "k_borrada")
        val viva = Triple(paciente4, "hepatologia", "k_borrada")
        // La borrada de clinica sigue igual (no se reescribe) y la viva de hepatologia sigue en hepatologia con su valor.
        assertEquals(antes.getValue(borrada), despues.getValue(borrada))
        assertEquals(antes.getValue(viva), despues.getValue(viva))
        assertEquals("valor vivo de hepatologia", despues.getValue(viva)[0])
        // La app, que lee solo `deleted = 0` de clinica, no la ve; el dato sigue en la base.
        assertEquals(emptyMap(), PacientesRepository(Salud360Db(driver)).observarExtras(paciente4, "clinica").first())
        assertEquals(1L, numero(driver, "SELECT COUNT(*) FROM paciente_extra WHERE paciente_id = '$paciente4' AND especialidad = 'hepatologia' AND deleted = 0"))
    }

    // ---- secciones ocultas ----

    private fun oculta(driver: SqlDriver, medicoId: String): List<String?>? = preferencias(driver)[medicoId to ocultasClinica]

    /** Como lo lee `ConsultaViewModel` (`split(',')` y `toSet()`). */
    private fun comoLoLeeLaApp(valor: String?): Set<String>? = valor?.split(',')?.toSet()

    @Test
    fun solo_hepatologia_las_secciones_pasan_a_clinica_y_la_clave_vieja_queda_borrada() = conBase { driver ->
        sembrarChoques(driver)
        val antes = preferencias(driver).getValue("m-pref-a" to ocultasHepatologia)

        migrar(driver)

        val clinica = oculta(driver, "m-pref-a")!!
        assertEquals("a,b", clinica[0])
        assertEquals("100", clinica[1], "updated_at de la vieja")
        assertEquals("0", clinica[2], "viva")
        // La vieja queda, borrada, con lo que tenía (menos `deleted`).
        val vieja = preferencias(driver).getValue("m-pref-a" to ocultasHepatologia)
        assertEquals(antes.take(2) + "1" + antes[3], vieja)
        // Lo que leen las pantallas (solo `deleted = 0`).
        val hc = HcRepository(Salud360Db(driver))
        assertEquals(mapOf(ocultasClinica to "a,b"), hc.observarPreferencias("m-pref-a").first())
    }

    @Test
    fun las_dos_listas_se_unen_sin_pisar_la_de_clinica() = conBase { driver ->
        sembrarChoques(driver)

        migrar(driver)

        val valor = oculta(driver, "m-pref-b")!![0]
        assertEquals("x,a,b", valor)
        // Leída como la leen `ConsultaViewModel` y `ConfigSeccionesHcScreen`: conjunto, con lo de las dos.
        assertEquals(setOf("x", "a", "b"), comoLoLeeLaApp(valor))
        assertEquals("1", preferencias(driver).getValue("m-pref-b" to ocultasHepatologia)[2])
        // `updated_at` es el mayor de los dos.
        assertEquals("300", oculta(driver, "m-pref-b")!![1])
        assertEquals("400", oculta(driver, "m-pref-i")!![1])
        assertEquals(setOf("k", "l"), comoLoLeeLaApp(oculta(driver, "m-pref-i")!![0]))
    }

    @Test
    fun una_lista_vacia_no_deja_comas_de_mas() = conBase { driver ->
        sembrarChoques(driver)

        migrar(driver)

        assertEquals("a", oculta(driver, "m-pref-c")!![0], "clinica vacía")
        assertEquals("x", oculta(driver, "m-pref-d")!![0], "hepatologia vacía")
        assertEquals("", oculta(driver, "m-pref-v")!![0], "las dos vacías")
        assertEquals(setOf("a"), comoLoLeeLaApp(oculta(driver, "m-pref-c")!![0]))
        assertEquals(setOf("x"), comoLoLeeLaApp(oculta(driver, "m-pref-d")!![0]))
    }

    @Test
    fun la_lista_de_clinica_borrada_se_reemplaza_por_una_viva() = conBase { driver ->
        sembrarChoques(driver)

        migrar(driver)

        val clinica = oculta(driver, "m-pref-e")!!
        assertEquals("a", clinica[0])
        assertEquals("0", clinica[2], "deleted")
        assertEquals("1", preferencias(driver).getValue("m-pref-e" to ocultasHepatologia)[2])
        assertEquals(mapOf(ocultasClinica to "a"), HcRepository(Salud360Db(driver)).observarPreferencias("m-pref-e").first())
    }

    @Test
    fun otros_medicos_y_otras_claves_quedan_intactos() = conBase { driver ->
        sembrarChoques(driver)
        val antes = preferencias(driver)
        val tocadas = setOf(ocultasClinica, ocultasHepatologia)

        migrar(driver)

        val despues = preferencias(driver)
        // Ninguna clave que no sea de secciones ocultas de clinica o hepatologia cambia.
        assertEquals(antes.filterKeys { it.second !in tocadas }, despues.filterKeys { it.second !in tocadas })
        assertEquals("f1,f2", despues.getValue("m-pref-b" to "filas_ocultas_clinica_laboratorio_general")[0])
        // Un médico sin hepatologia, o con la vieja ya borrada, o sin ninguna de las dos, no cambia ni gana filas.
        for (m in listOf("m-pref-f", "m-pref-g", "m-pref-h")) {
            assertEquals(antes.filterKeys { it.first == m }, despues.filterKeys { it.first == m }, m)
        }
        assertEquals(null, oculta(driver, "m-pref-g"), "no se crea una clinica para quien tenía la vieja borrada")
        assertEquals(null, oculta(driver, "m-pref-h"))
        assertEquals("q", oculta(driver, "m-pref-f")!![0])
    }

    /**
     * Documenta el `dirty` de la unión tal como está hoy (T49, sin cambiar): la fila nueva de `clinica` copia el `dirty` de la
     * de `clinica` si existía y, si no, el de la vieja. No es el máximo: una unión que agrega secciones a una lista `dirty = 0`
     * queda con `dirty = 0`. La preferencia es del dispositivo (la app no tiene una API que la reciba), así que no se manda a
     * ningún lado y la unión no se pierde.
     */
    @Test
    fun el_dirty_de_la_union_es_el_de_la_fila_de_clinica_o_el_de_la_vieja() = conBase { driver ->
        sembrarChoques(driver)

        migrar(driver)

        assertEquals("1", oculta(driver, "m-pref-a")!![3], "solo hepatologia (dirty 1): el de la vieja")
        assertEquals("0", oculta(driver, "m-pref-b")!![3], "clinica 0 y hepatologia 1: queda el de clinica")
        assertEquals("1", oculta(driver, "m-pref-i")!![3], "clinica 1 y hepatologia 0: queda el de clinica")
        assertEquals("1", oculta(driver, "m-pref-e")!![3], "clinica borrada: no cuenta, el de la vieja")
        // La vieja conserva su `dirty` al borrarse.
        assertEquals("1", preferencias(driver).getValue("m-pref-b" to ocultasHepatologia)[3])
    }

    // ---- las demás HC ----

    @Test
    fun las_filas_de_otras_especialidades_no_cambian() = conBase { driver ->
        sembrar(driver)
        sembrarChoques(driver)
        val otras = "especialidad NOT IN ('hepatologia', 'clinica')"
        val tablas = listOf("consulta", "registro_clinico", "laboratorio", "antecedente", "paciente_extra")
        val antes = tablas.associateWith { volcado(driver, it, otras) }
        tablas.forEach { assertEquals(true, antes.getValue(it).isNotEmpty(), "sembrada: $it") }

        migrar(driver)

        tablas.forEach { assertEquals(antes.getValue(it), volcado(driver, it, otras), "tabla $it") }
    }

    private fun tablas(driver: SqlDriver): List<String> =
        driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!!) })
        }, 0).value

    /**
     * La migración solo toca las seis tablas de [tablasQueMueve]. Las que guardan los códigos que informa turnos (`medico`,
     * `especialidad`) no se normalizan acá: se normalizan al leer (T48) y se reescriben limpias en el próximo ingreso. Las demás
     * (`hc_enfermedades`, `sesion_local`, `hc_config`, `seccion_valor`...) no tienen nada que ver. Un volcado completo antes y
     * después lo fija para que nadie le agregue a `8.sqm` un `UPDATE` de más.
     */
    @Test
    fun toda_tabla_que_la_migracion_no_mueve_queda_identica() = conBase { driver ->
        sembrar(driver)
        sembrarChoques(driver)
        sembrarParecidos(driver)
        ejecutar(driver, "INSERT INTO medico (id, usuario_id, nombre, apellido, especialidades_hc, updated_at) VALUES ('m-prueba-hc', 'u-prueba-1', 'Médico', 'De Prueba', 'pediatria,hepatologia', 100)")
        ejecutar(driver, "INSERT INTO especialidad (id, nombre, codigo_hc, updated_at) VALUES ('tobb-e-prueba', 'Hepatología', 'hepatologia', 100)")
        ejecutar(driver, "INSERT INTO hc_enfermedades (medico_id, parte, ficha_id, datos_json, recibida_en) VALUES ('m-prueba-hc', 'seccion', 7, '{\"hepatologia\":1}', 1)")
        ejecutar(driver, "INSERT INTO sesion_local (id, usuario_id, token, sesion_json, guardado_en) VALUES (1, 'u-prueba-1', 'valor-de-prueba', '{\"historias\":[\"hepatologia\"]}', 1)")
        val sinMover = tablas(driver) - tablasQueMueve
        // Las que importan están entre las que se comparan (y hay de sobra).
        listOf("medico", "especialidad", "hc_enfermedades", "sesion_local", "hc_config", "seccion_valor", "interconsultor", "turno")
            .forEach { assertEquals(true, it in sinMover, "se compara $it") }
        val antes = sinMover.associateWith { volcado(driver, it) }
        assertEquals(true, antes.getValue("medico").isNotEmpty() && antes.getValue("especialidad").isNotEmpty())
        assertEquals(true, antes.getValue("hc_enfermedades").isNotEmpty() && antes.getValue("sesion_local").isNotEmpty())

        migrar(driver)

        sinMover.forEach { assertEquals(antes.getValue(it), volcado(driver, it), "tabla $it") }
        // Y a propósito: turnos sigue diciendo `hepatologia` en la base; la normalización es de lectura.
        assertEquals("pediatria,hepatologia", texto(driver, "SELECT especialidades_hc FROM medico WHERE id = 'm-prueba-hc'"))
        assertEquals("hepatologia", texto(driver, "SELECT codigo_hc FROM especialidad WHERE id = 'tobb-e-prueba'"))
    }

    // ---- idempotencia y sin pérdida ----

    @Test
    fun correr_la_migracion_otra_vez_no_cambia_nada_ni_falla() = conBase { driver ->
        sembrar(driver)
        sembrarChoques(driver)

        migrar(driver)
        val primera = tablasDeDatos.associateWith { volcado(driver, it) }
        assertEquals(true, primera.getValue("medico_preferencia").isNotEmpty())

        migrar(driver)
        assertEquals(primera, tablasDeDatos.associateWith { volcado(driver, it) }, "segunda corrida")
        migrar(driver)
        assertEquals(primera, tablasDeDatos.associateWith { volcado(driver, it) }, "tercera corrida")
    }

    @Test
    fun una_edicion_hecha_despues_de_migrar_no_se_deshace_al_volver_a_correrla() = conBase { driver ->
        sembrarChoques(driver)
        migrar(driver)
        // El médico cambia sus secciones ocultas y un antecedente en la HC general.
        preferencia(driver, "m-pref-b", ocultasClinica, "solo", 900, dirty = 1)
        ejecutar(driver, "UPDATE antecedente SET detalle = 'editado', updated_at = 9999, dirty = 1 WHERE id = 'an-a-h'")
        val antes = tablasDeDatos.associateWith { volcado(driver, it) }

        migrar(driver)

        assertEquals(antes, tablasDeDatos.associateWith { volcado(driver, it) })
        assertEquals("solo", oculta(driver, "m-pref-b")!![0])
    }

    /** Una pestaña vieja (v8) puede volver a guardar `hepatologia` en una base ya migrada: la próxima migración lo mueve sin fallar. */
    @Test
    fun lo_que_una_app_vieja_vuelva_a_guardar_como_hepatologia_se_mueve_en_la_proxima_corrida() = conBase { driver ->
        sembrarChoques(driver)
        migrar(driver)
        consulta(driver, "c-vuelve", "hepatologia", estado = "ABIERTA", actualizada = 8000, dirty = 1, pacienteId = paciente2)
        antecedente(driver, "an-vuelve", "hepatologia", "clave_a", 99999) // más nueva que an-a-h, que ya es de clinica
        extra(driver, paciente2, "hepatologia", "k_nueva", "valor nuevo")
        preferencia(driver, "m-pref-b", ocultasHepatologia, "z", 500, dirty = 1) // reemplaza la vieja borrada

        migrar(driver)

        assertEquals("clinica", texto(driver, "SELECT especialidad FROM consulta WHERE id = 'c-vuelve'"))
        assertEquals("clinica", texto(driver, "SELECT especialidad FROM antecedente WHERE id = 'an-vuelve'"))
        assertEquals(1L, activo(driver, "an-vuelve"))
        assertEquals(0L, activo(driver, "an-a-h"), "la que ya estaba en clinica es más vieja: pierde")
        assertEquals(1L, numero(driver, "SELECT COUNT(*) FROM paciente_extra WHERE paciente_id = '$paciente2' AND especialidad = 'clinica' AND clave = 'k_nueva'"))
        assertEquals("x,a,b,z", oculta(driver, "m-pref-b")!![0])
        for (tabla in listOf("consulta", "antecedente", "registro_clinico", "laboratorio")) {
            assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM $tabla WHERE especialidad = 'hepatologia'"), tabla)
        }
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM medico_preferencia WHERE clave = '$ocultasHepatologia' AND deleted = 0"))
    }

    @Test
    fun ninguna_fila_se_pierde_y_el_dirty_total_no_cambia() = conBase { driver ->
        sembrar(driver)
        sembrarChoques(driver)
        fun cuenta(tabla: String) = numero(driver, "SELECT COUNT(*) FROM $tabla")
        fun sucias(tabla: String) = numero(driver, "SELECT COALESCE(SUM(dirty), 0) FROM $tabla")
        val filasAntes = tablasDeDatos.associateWith { cuenta(it) }
        val suciasAntes = tablasDeDatos.associateWith { sucias(it) }
        tablasDeDatos.forEach { assertEquals(true, filasAntes.getValue(it) > 0, "sembrada: $it") }
        val inactivasAntes = numero(driver, "SELECT COUNT(*) FROM antecedente WHERE activo = 0")
        // La excepción documentada: por cada médico con secciones ocultas de hepatologia vivas y sin ninguna fila de clinica
        // (ni borrada: esa se reemplaza), `medico_preferencia` gana una fila. El conteo esperado se calcula, no se ignora.
        val preferenciasNuevas = numero(
            driver,
            "SELECT COUNT(*) FROM medico_preferencia h WHERE h.clave = '$ocultasHepatologia' AND h.deleted = 0 AND NOT EXISTS (" +
                "SELECT 1 FROM medico_preferencia c WHERE c.medico_id = h.medico_id AND c.clave = '$ocultasClinica')",
        )
        assertEquals(1L, preferenciasNuevas, "solo m-pref-a")
        val vivasDeHepatologia = numero(driver, "SELECT COUNT(*) FROM medico_preferencia WHERE clave = '$ocultasHepatologia' AND deleted = 0")
        assertEquals(true, vivasDeHepatologia > 1, "sembrada")

        migrar(driver)

        for (tabla in tablasDeDatos - "medico_preferencia") {
            assertEquals(filasAntes.getValue(tabla), cuenta(tabla), "filas de $tabla")
            assertEquals(suciasAntes.getValue(tabla), sucias(tabla), "dirty de $tabla")
        }
        assertEquals(filasAntes.getValue("medico_preferencia") + preferenciasNuevas, cuenta("medico_preferencia"))
        // Lo único que cambia de `antecedente` es `activo` de las perdedoras, y de las preferencias, `deleted` de las viejas.
        assertEquals(inactivasAntes + perdedoras.size, numero(driver, "SELECT COUNT(*) FROM antecedente WHERE activo = 0"))
        assertEquals(0L, numero(driver, "SELECT COUNT(*) FROM medico_preferencia WHERE clave = '$ocultasHepatologia' AND deleted = 0"))
        // De paciente_extra se mueven dos y dos se quedan en hepatologia (la que choca y la que enfrenta una borrada).
        assertEquals(2L, numero(driver, "SELECT COUNT(*) FROM paciente_extra WHERE especialidad = 'hepatologia'"))
    }

    // ---- con los repositorios ----

    @Test
    fun despues_de_migrar_los_repositorios_leen_lo_de_hepatologia_en_clinica_sin_duplicados() = conBase { driver ->
        sembrar(driver)
        sembrarChoques(driver)

        migrar(driver)

        val repo = HcRepository(Salud360Db(driver))
        // Consultas (la anulada no se lista): las de hepatologia más la de clinica, cada una una vez, la más nueva primero.
        val consultas = repo.observarConsultas(paciente, "clinica").first()
        assertEquals(listOf("c-hepat-abierta", "c-hepat-cerrada", "c-clinica"), consultas.map { it.id })
        assertEquals("ABIERTA", consultas.first().estado.name)
        assertEquals(listOf("c-p2-clinica", "c-p2-hepat"), repo.observarConsultas(paciente2, "clinica").first().map { it.id })
        // Y `abrirConsulta` ya no retoma la abierta de hepatología: es del histórico y desde la spec 005 (T16, RF-11) solo se retoma
        // una propia. Crea una propia nueva y la del histórico queda como estaba (la ve en clinica, sin la marca).
        val antes3 = repo.observarConsultas(paciente3, "clinica").first()
        assertEquals(2, antes3.size)
        assertTrue(antes3.none { it.propia }, "lo migrado es histórico")
        val abierta = repo.abrirConsulta(paciente3, medico, "clinica", "consulta", LocalDate(2026, 10, 26), "45 años")
        assertNotEquals("c-p3-hepat", abierta.id)
        assertTrue(abierta.propia)
        val despues3 = repo.observarConsultas(paciente3, "clinica").first()
        assertEquals(3, despues3.size)
        assertEquals(antes3.toSet(), despues3.filter { it.id != abierta.id }.toSet(), "las del histórico no cambian")
        // Registros y laboratorios, con su contenido.
        val registros = repo.observarRegistros(paciente, "clinica", "biopsia_hepatica").first()
        assertEquals(setOf("r-hepatologia", "r-clinica"), registros.map { it.id }.toSet())
        assertEquals(registros.size, registros.map { it.id }.toSet().size)
        assertEquals(mapOf("detalle" to "F2 en hepatologia"), registros.first { it.id == "r-hepatologia" }.campos)
        val laboratorios = repo.observarLaboratorios(paciente, "clinica", "laboratorio_general").first()
        assertEquals(setOf("l-hepatologia", "l-clinica"), laboratorios.map { it.id }.toSet())
        assertEquals(mapOf("got" to "40"), laboratorios.first { it.id == "l-hepatologia" }.valores)
        // Antecedentes: los del paciente 1 sin choque, y los del paciente 2 con una sola fila por categoría y clave.
        assertEquals(setOf("cirrosis", "alergias"), repo.observarAntecedentes(paciente, "clinica").first().map { it.clave }.toSet())
        val antecedentes2 = repo.observarAntecedentes(paciente2, "clinica").first()
        assertEquals(
            setOf("an-a-h", "an-b-c", "an-c-c", "an-d-h", "an-g1-h", "an-g2-h", "an-g3-c", "an-g4-c"),
            antecedentes2.map { it.id }.toSet(),
        )
        assertEquals(antecedentes2.size, antecedentes2.map { it.categoria to it.clave }.toSet().size)
        assertEquals(
            listOf("Detalle de an-a-h"),
            repo.observarAntecedentes(paciente2, "clinica", "personales").first().filter { it.clave == "clave_a" }.map { it.detalle },
        )
        // Los motivos de las consultas de hepatología salen en las "consultas previas" de la HC general.
        assertEquals(
            mapOf("c-hepat-abierta" to "Control de rutina", "c-hepat-cerrada" to "Estudios previos"),
            repo.observarValoresDeCampo(paciente, "clinica", "motivo_consulta", "texto").first(),
        )
        assertEquals(
            mapOf("c-p2-hepat" to "Motivo en hepatología"),
            repo.observarValoresDeCampo(paciente2, "clinica", "motivo_consulta", "texto").first(),
        )
        // En hepatologia ya no queda nada para ver.
        assertEquals(emptyList(), repo.observarConsultas(paciente, "hepatologia").first())
        assertEquals(emptyList(), repo.observarRegistros(paciente, "hepatologia", "biopsia_hepatica").first())
    }
}
