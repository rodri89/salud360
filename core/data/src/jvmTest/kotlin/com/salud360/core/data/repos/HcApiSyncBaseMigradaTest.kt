package com.salud360.core.data.repos

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.sync.HcApiSync
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.RegistroClinico
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El candado de [HcApiSync] frente a una base que `8.sqm` ya migró (spec 002, E5.2, RF-47 y RF-48; regresión del riesgo H2
 * de la tanda D de la 001): en producción las consultas, secciones, registros, antecedentes, etc. de `hepatologia` quedaron
 * `dirty = 1` (nunca hubo API a la que enviarlas), y al pasar a `clinica` el motor las ve como «pendientes de una HC con
 * backend». Con el backend de la HC general de solo lectura no tiene que salir NADA, ni tocarse ninguna fila.
 *
 * La base es un archivo SQLite temporal que parte de una v8 con datos de `hepatologia` (más lo que ya tenía `clinica` y una
 * consulta de pediatría), todo inventado y todo `dirty = 1` como quedó en producción, y se abre con el driver, que corre
 * `8.sqm` (v8 a v9) como en [MigracionHepatologiaTest]. Sobre ella, un backend falso de solo lectura para `clinica` que
 * lanza si lo llaman (una fuga dejaría además un reintento programado) y se ejercitan todos los caminos de envío.
 *
 * El último test fija —sin cambiarlo— lo que hace hoy `SyncEngine` con esta misma base (otro canal, hacia las rutas de
 * turnos que no existen): es la deuda anotada en AGENTS.md.
 */
class HcApiSyncBaseMigradaTest {

    // Los pacientes llevan el documento inventado en el id (99000xxx); nada real.
    private val p1 = "p-99000101"
    private val p2 = "p-99000102"
    private val p3 = "p-99000103"
    private val medico = "m-prueba-1"

    /** Los antecedentes que `8.sqm` deja con `activo = 0` (el repetido que pierde en cada par). */
    private val perdedoras = listOf("an-p2-a-c", "an-p2-b-h", "an-p2-c-h", "an-p3-h")

    /** Consultas de `clinica` después de migrar (las de `hepatologia` ya pasaron). */
    private val consultasDeClinica = listOf(
        "h-abierta", "h-cerrada-1", "h-cerrada-2", "h-anulada", "h-abierta-2", "c-abierta-p2", "c-p3-cerrada",
    )

    /** Las tablas con datos que la migración toca o que cuelgan de lo que toca. */
    private val tablasDeDatos = listOf(
        "consulta", "seccion_valor", "examen_fisico", "archivo", "registro_clinico", "laboratorio", "antecedente",
        "paciente_extra", "medico_preferencia",
    )

    /** Backend falso que anota cada llamada de envío; de clínica además lanza, como si no hubiera conexión. */
    private class BackendFalso(override val especialidad: String, override val soloLectura: Boolean) : HcBackend {
        val llamadas = mutableListOf<String>()
        var fallar = false

        override suspend fun asegurarConsulta(consulta: Consulta): String? {
            llamadas += "asegurarConsulta"
            if (consulta.remotoId.isBlank()) llamadas += "pacientes/resolver"
            if (fallar) error("sin conexión (simulada)")
            return consulta.remotoId.ifBlank { "R-${consulta.id}" }
        }

        override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) {
            llamadas += "enviarSecciones"
        }

        override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) {
            llamadas += "enviarExamen"
        }

        override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> {
            llamadas += "enviarRegistros"
            return emptyMap()
        }

        override suspend fun enviarEstado(consulta: Consulta, remotoId: String) {
            llamadas += "enviarEstado"
        }

        override suspend fun subirArchivo(remotoId: String, archivo: Archivo, bytes: ByteArray): String? {
            llamadas += "subirArchivo"
            return "RA-${archivo.id}"
        }

        override suspend fun borrarArchivo(remotoId: String, archivo: Archivo) {
            llamadas += "borrarArchivo"
        }

        override suspend fun bajarArchivo(remotoId: String, archivo: Archivo): ByteArray? = null

        override suspend fun guardarLicencia(licencia: Licencia): Boolean {
            llamadas += "guardarLicencia"
            return true
        }

        // Lecturas: permitidas con un backend de solo lectura (no son un envío).
        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = emptyList()

        override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) = Unit
    }

    /** Un motor con su propio `Job`, para ver si dejó algo programado. */
    private class Motor(val sync: HcApiSync, val job: Job, val scope: CoroutineScope)

    /** La base migrada, abierta, y cuántas filas `dirty` tenía cada tabla antes de migrar. */
    private class Base(val driver: SqlDriver, val db: Salud360Db, val archivos: ArchivoStore, val suciasV8: Map<String, Long>)

    private fun conBaseMigrada(bloque: suspend (Base) -> Unit) {
        val archivo = File.createTempFile("salud360-hcapisync-migrada", ".db").also { it.delete() }
        val adjuntos = File.createTempFile("salud360-hcapisync-migrada", "").also { it.delete(); it.mkdirs() }
        try {
            runBlocking {
                // Como la dejó la versión anterior de la app: con `user_version` 8 y datos de hepatología.
                val v8 = DriverFactory(archivo.absolutePath).createDriver()
                val suciasV8 = try {
                    sembrarV8(v8)
                    // Sin la columna de la `9.sqm`, que el driver agrega al migrar (si no, `ADD COLUMN` chocaría con la existente).
                    ejecutar(v8, "ALTER TABLE consulta DROP COLUMN propia")
                    ejecutar(v8, "PRAGMA user_version = 8")
                    tablasDeDatos.associateWith { sucias(v8, it) }
                } finally {
                    v8.close()
                }
                // Al abrirla, el driver corre `8.sqm`.
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(Base(driver, Salud360Db(driver), ArchivoStore(adjuntos.absolutePath), suciasV8))
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
            adjuntos.deleteRecursively()
        }
    }

    private fun motor(base: Base, vararg backends: BackendFalso): Motor {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        return Motor(HcApiSync(base.db, backends.associateBy { it.especialidad }, base.archivos, scope), job, scope)
    }

    /** Reintentos o esperas que el motor dejó programados: hijos todavía activos de su `Job`. */
    private fun programado(m: Motor): Int = m.job.children.count { it.isActive }

    private suspend fun esperarQuieto(m: Motor) {
        withTimeout(30_000) { while (programado(m) > 0) delay(20) }
    }

    private fun clinica() = BackendFalso("clinica", soloLectura = true).apply { fallar = true }
    private fun pediatria() = BackendFalso("pediatria", soloLectura = false)

    // ---- SQL crudo (como MigracionHepatologiaTest) ----

    private fun ejecutar(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun lista(driver: SqlDriver, sql: String): List<String> =
        driver.executeQuery(null, sql, { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!!) })
        }, 0).value

    private fun numero(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value

    private fun sucias(driver: SqlDriver, tabla: String) = numero(driver, "SELECT COALESCE(SUM(dirty), 0) FROM $tabla")

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

    /** Todas las tablas de la base (no solo las clínicas), columna por columna. */
    private fun volcadoTotal(driver: SqlDriver): Map<String, List<List<String?>>> =
        lista(driver, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .associateWith { volcado(driver, it) }

    /** Lo mismo sin las filas de pediatría (todas llevan el prefijo `pedi-` en su id o en el de su consulta). */
    private fun sinPediatria(volcado: Map<String, List<List<String?>>>) =
        volcado.mapValues { (_, filas) -> filas.filterNot { f -> f.any { it?.startsWith("pedi-") == true } } }

    private fun assertIdentica(driver: SqlDriver, antes: Map<String, List<List<String?>>>, mensaje: String = "") {
        val despues = volcadoTotal(driver)
        assertEquals(antes.keys, despues.keys, "tablas $mensaje")
        antes.forEach { (tabla, filas) -> assertEquals(filas, despues.getValue(tabla), "tabla $tabla $mensaje") }
    }

    // ---- la base v8 con datos de hepatología, todo sucio ----

    private fun consulta(
        driver: SqlDriver, id: String, especialidad: String, pacienteId: String, estado: String, actualizada: Long,
        dirty: Int = 1, remoto: String = "",
    ) = ejecutar(
        driver,
        "INSERT INTO consulta (id, paciente_id, medico_id, especialidad, tipo, fecha, estado, edad_mostrar, updated_at, deleted, dirty, remoto_id) " +
            "VALUES ('$id', '$pacienteId', '$medico', '$especialidad', 'consulta', '2026-10-05', '$estado', '45 años', $actualizada, 0, $dirty, '$remoto')",
    )

    private fun antecedente(driver: SqlDriver, id: String, pacienteId: String, hc: String, clave: String, actualizada: Long, consultaId: String? = null) =
        ejecutar(
            driver,
            "INSERT INTO antecedente (id, paciente_id, especialidad, categoria, clave, flag, detalle, consulta_id, activo, updated_at, deleted, dirty) " +
                "VALUES ('$id', '$pacienteId', '$hc', 'personales', '$clave', 1, 'Detalle de $id', ${consultaId?.let { "'$it'" } ?: "NULL"}, 1, $actualizada, 0, 1)",
        )

    private fun sembrarV8(driver: SqlDriver) {
        for ((id, dni) in listOf(p1 to "99000101", p2 to "99000102", p3 to "99000103")) {
            ejecutar(driver, "INSERT INTO paciente (id, dni, nombre, apellido, updated_at, deleted, dirty) VALUES ('$id', '$dni', 'Paciente', 'De Prueba $dni', 100, 0, 0)")
        }

        // Consultas: las de hepatología abiertas y cerradas (y una anulada), todas sin enviar; la de clínica que el médico ya
        // tenía abierta (así quedan dos abiertas del mismo paciente); una cerrada ya traída de la web, limpia; una de pediatría.
        consulta(driver, "h-abierta", "hepatologia", p1, "ABIERTA", 5005)
        consulta(driver, "h-cerrada-1", "hepatologia", p1, "CERRADA", 4001)
        consulta(driver, "h-cerrada-2", "hepatologia", p2, "CERRADA", 4002)
        consulta(driver, "h-anulada", "hepatologia", p2, "ANULADA", 3003)
        consulta(driver, "h-abierta-2", "hepatologia", p2, "ABIERTA", 5006)
        consulta(driver, "c-abierta-p2", "clinica", p2, "ABIERTA", 6007)
        consulta(driver, "c-p3-cerrada", "clinica", p3, "CERRADA", 2002, dirty = 0, remoto = "r-77")
        consulta(driver, "pedi-c1", "pediatria", p1, "ABIERTA", 7008)

        // Lo que cuelga de ellas.
        for ((c, t) in listOf("h-abierta" to 5005, "h-cerrada-1" to 4001, "c-abierta-p2" to 6007, "pedi-c1" to 7008)) {
            ejecutar(driver, "INSERT INTO seccion_valor (consulta_id, seccion, campo, valor, updated_at, deleted, dirty) VALUES ('$c', 'motivo_consulta', 'texto', 'Motivo de $c', $t, 0, 1)")
        }
        for ((c, t) in listOf("h-abierta" to 5005, "pedi-c1" to 7008)) {
            ejecutar(driver, "INSERT INTO examen_fisico (consulta_id, peso, talla, updated_at, deleted, dirty) VALUES ('$c', '70', '170', $t, 0, 1)")
        }
        ejecutar(driver, "INSERT INTO archivo (id, paciente_id, consulta_id, seccion, nombre, mime, activo, updated_at, deleted, dirty, remoto_id) VALUES ('ar-1', '$p1', 'h-abierta', 'documentos', 'informe.pdf', 'application/pdf', 1, 5010, 0, 1, '')")
        ejecutar(driver, "INSERT INTO archivo (id, paciente_id, consulta_id, seccion, nombre, mime, activo, updated_at, deleted, dirty, remoto_id) VALUES ('ar-2', '$p1', 'h-abierta', 'documentos', 'viejo.pdf', 'application/pdf', 0, 5011, 0, 1, 'RA-2')")

        // Registros y laboratorio: dos de hepatología (uno colgado de una consulta y otro suelto), uno de clínica y uno de pediatría.
        for ((id, pac, hc, consulta) in listOf(
            listOf("r-h1", p1, "hepatologia", "'h-abierta'"), listOf("r-h2", p2, "hepatologia", "NULL"),
            listOf("r-c1", p2, "clinica", "'c-abierta-p2'"), listOf("pedi-r1", p1, "pediatria", "'pedi-c1'"),
        )) {
            ejecutar(driver, "INSERT INTO registro_clinico (id, paciente_id, consulta_id, especialidad, tipo, fecha, campos_json, activo, updated_at, deleted, dirty, remoto_id) VALUES ('$id', '$pac', $consulta, '$hc', 'biopsia_hepatica', '2026-06-01', '{\"detalle\":\"F2 en $id\"}', 1, 2500, 0, 1, '')")
        }
        ejecutar(driver, "INSERT INTO laboratorio (id, paciente_id, consulta_id, especialidad, tipo, fecha, valores_json, activo, updated_at, deleted, dirty) VALUES ('l-h1', '$p1', 'h-cerrada-1', 'hepatologia', 'laboratorio_general', '2026-06-02', '{\"got\":\"40\"}', 1, 2600, 0, 1)")
        ejecutar(driver, "INSERT INTO laboratorio (id, paciente_id, consulta_id, especialidad, tipo, fecha, valores_json, activo, updated_at, deleted, dirty) VALUES ('l-c1', '$p2', 'c-abierta-p2', 'clinica', 'laboratorio_general', '2026-06-03', '{\"got\":\"41\"}', 1, 2601, 0, 1)")

        // Antecedentes: dos sin choque (uno colgado de una consulta), los cuatro que pierden el dedupe (p2 tres pares —gana
        // hepatología, gana clínica, empate— y p3 uno, cuya única consulta está limpia) y uno de pediatría.
        antecedente(driver, "an-p1-h", p1, "hepatologia", "cirrosis", 2700)
        antecedente(driver, "an-p1-h2", p1, "hepatologia", "ascitis", 2701, consultaId = "h-abierta")
        antecedente(driver, "an-p2-a-h", p2, "hepatologia", "clave_a", 3000); antecedente(driver, "an-p2-a-c", p2, "clinica", "clave_a", 2000)
        antecedente(driver, "an-p2-b-h", p2, "hepatologia", "clave_b", 2000); antecedente(driver, "an-p2-b-c", p2, "clinica", "clave_b", 3000)
        antecedente(driver, "an-p2-c-h", p2, "hepatologia", "clave_c", 2500); antecedente(driver, "an-p2-c-c", p2, "clinica", "clave_c", 2500)
        antecedente(driver, "an-p3-h", p3, "hepatologia", "diabetes", 1000); antecedente(driver, "an-p3-c", p3, "clinica", "diabetes", 2000)
        antecedente(driver, "pedi-an1", p1, "pediatria", "asma", 2800)

        // Datos del paciente: uno que se mueve a clínica y uno que choca y se queda en hepatología.
        ejecutar(driver, "INSERT INTO paciente_extra (paciente_id, especialidad, clave, valor, updated_at, deleted, dirty) VALUES ('$p1', 'hepatologia', 'k_libre', 'solo hep', 100, 0, 1)")
        ejecutar(driver, "INSERT INTO paciente_extra (paciente_id, especialidad, clave, valor, updated_at, deleted, dirty) VALUES ('$p2', 'hepatologia', 'k_choca', 'valor hep', 100, 0, 1)")
        ejecutar(driver, "INSERT INTO paciente_extra (paciente_id, especialidad, clave, valor, updated_at, deleted, dirty) VALUES ('$p2', 'clinica', 'k_choca', 'valor clinica', 200, 0, 1)")

        // Preferencias: las secciones ocultas de hepatología (la migración crea la de clínica).
        ejecutar(driver, "INSERT INTO medico_preferencia (medico_id, clave, valor, updated_at, deleted, dirty) VALUES ('$medico', 'secciones_ocultas_hepatologia', 'a,b', 100, 0, 1)")
    }

    // ---- la base de partida es la que dice ser ----

    @Test
    fun la_base_de_partida_esta_migrada_y_todo_lo_de_hepatologia_sigue_sucio() = conBaseMigrada { base ->
        val d = base.driver
        assertEquals(10L, numero(d, "PRAGMA user_version"))
        for (tabla in listOf("consulta", "registro_clinico", "laboratorio", "antecedente")) {
            assertEquals(0L, numero(d, "SELECT COUNT(*) FROM $tabla WHERE especialidad = 'hepatologia'"), "tabla $tabla")
        }
        // Los repetidos que perdieron siguen en la base, con `activo = 0` y sucios.
        assertEquals(perdedoras.sorted(), lista(d, "SELECT id FROM antecedente WHERE activo = 0 ORDER BY id"))
        assertEquals(perdedoras.size.toLong(), numero(d, "SELECT COUNT(*) FROM antecedente WHERE activo = 0 AND dirty = 1 AND deleted = 0"))
        // Las consultas viejas pasaron a clínica sin enviar y con su `dirty`; la única limpia es la ya traída de la web.
        assertEquals(consultasDeClinica.sorted(), lista(d, "SELECT id FROM consulta WHERE especialidad = 'clinica' ORDER BY id"))
        assertEquals(listOf("c-p3-cerrada"), lista(d, "SELECT id FROM consulta WHERE especialidad = 'clinica' AND dirty = 0"))
        assertEquals(0L, numero(d, "SELECT COUNT(*) FROM consulta WHERE remoto_id <> '' AND dirty = 1"))
        // La migración no limpió nada: las filas sucias de antes siguen sucias (la preferencia gana la fila nueva de clínica).
        for (tabla in tablasDeDatos - "medico_preferencia") {
            assertEquals(base.suciasV8.getValue(tabla), sucias(d, tabla), "dirty de $tabla")
            assertTrue(sucias(d, tabla) > 0, "sembrada: $tabla")
        }
        assertEquals(base.suciasV8.getValue("medico_preferencia") + 1, sucias(d, "medico_preferencia"))
        // Un dato de paciente de hepatología se queda donde estaba (el choque): hay filas de una HC que no tiene backend.
        assertEquals(1L, numero(d, "SELECT COUNT(*) FROM paciente_extra WHERE especialidad = 'hepatologia' AND dirty = 1"))
    }

    // ---- ningún camino de envío llama ni toca ----

    @Test
    fun empujarTodo_no_llama_a_nada_ni_resuelve_pacientes_y_la_base_queda_identica() = conBaseMigrada { base ->
        val antes = volcadoTotal(base.driver)
        val backend = clinica()
        val m = motor(base, backend)

        m.sync.empujarTodo()

        assertEquals(emptyList(), backend.llamadas, "ni asegurarConsulta ni pacientes/resolver")
        assertEquals(0, programado(m), "ni reintentos ni envíos esperando")
        assertIdentica(base.driver, antes)
        // Las filas que importan, por si el volcado no las viera: los repetidos siguen inactivos y las consultas viejas sucias.
        assertEquals(perdedoras.sorted(), lista(base.driver, "SELECT id FROM antecedente WHERE activo = 0 ORDER BY id"))
        assertEquals(6L, numero(base.driver, "SELECT COUNT(*) FROM consulta WHERE especialidad = 'clinica' AND dirty = 1"))
        m.scope.cancel()
    }

    @Test
    fun abrir_cada_consulta_como_lo_hace_el_viewmodel_no_envia_nada() = conBaseMigrada { base ->
        val antes = volcadoTotal(base.driver)
        val backend = clinica()
        val hc = HcRepository(base.db, mapOf("clinica" to backend))
        val m = motor(base, backend)

        for (id in consultasDeClinica) {
            // El `init` de `ConsultaViewModel`: trae lo remoto si la consulta ya tiene id allá y reintenta lo pendiente.
            val c = hc.consulta(id) ?: error("falta la consulta $id")
            assertTrue(hc.tieneApi(c.especialidad), "clinica tiene backend (de solo lectura)")
            runCatching { hc.traerConsultaRemota(c) }
            m.sync.empujarTodo()
        }

        assertEquals(emptyList(), backend.llamadas)
        assertEquals(0, programado(m))
        assertIdentica(base.driver, antes)
        m.scope.cancel()
    }

    @Test
    fun marcarSucia_y_empujarYa_de_cada_consulta_no_envian_ni_programan_nada() = conBaseMigrada { base ->
        val antes = volcadoTotal(base.driver)
        val backend = clinica()
        val m = motor(base, backend)

        for (id in consultasDeClinica) {
            // Lo que dispara el autoguardado (con y sin la espera de siempre), el cierre y la salida de la pantalla.
            m.sync.marcarSucia(id, esperaMs = 0)
            assertEquals(0, programado(m), "marcarSucia sin espera de $id")
            m.sync.marcarSucia(id)
            assertEquals(0, programado(m), "marcarSucia con la espera de siempre de $id: no tiene que quedar un envío esperando")
            m.sync.empujarYa(id)
            assertEquals(0, programado(m), "empujarYa de $id")
        }

        assertEquals(emptyList(), backend.llamadas)
        assertIdentica(base.driver, antes)
        m.scope.cancel()
    }

    @Test
    fun todos_los_caminos_juntos_y_repetidos_dejan_la_base_identica_sin_reintentos() = conBaseMigrada { base ->
        val antes = volcadoTotal(base.driver)
        val backend = clinica() // lanza en `asegurarConsulta`: cualquier fuga dejaría un reintento de 15, 60 y 300 s
        val m = motor(base, backend)

        repeat(2) {
            m.sync.empujarTodo()
            for (id in consultasDeClinica) {
                m.sync.marcarSucia(id, esperaMs = 0)
                m.sync.marcarSucia(id)
                m.sync.empujarYa(id)
                m.sync.empujarTodo()
            }
        }

        assertEquals(emptyList(), backend.llamadas)
        assertEquals(0, programado(m), "ningún reintento programado")
        assertIdentica(base.driver, antes)
        m.scope.cancel()
    }

    // ---- el contador (Q16-A) ----

    /**
     * Q16-A: `pendientes` no cambia con el candado: sigue contando las consultas con algo sin enviar, igual que un motor sin
     * backend para esa HC. No suman los antecedentes `activo = 0` ni los registros (no se resuelven a una consulta de un
     * backend de solo lectura): `c-p3-cerrada` está limpia y solo tiene un antecedente sucio, así que no cuenta.
     */
    @Test
    fun el_contador_pendientes_cuenta_lo_mismo_que_sin_candado_y_no_cambia_con_los_envios() = conBaseMigrada { base ->
        val conCandado = motor(base, clinica())
        val sinBackend = motor(base)

        conCandado.sync.empujarTodo()
        sinBackend.sync.empujarTodo()

        // Las seis de clínica con algo sucio y la de pediatría (sin backend en estos motores).
        assertEquals(7, sinBackend.sync.pendientes.value)
        assertEquals(7, conCandado.sync.pendientes.value)
        for (id in consultasDeClinica) {
            conCandado.sync.empujarYa(id)
            conCandado.sync.marcarSucia(id, esperaMs = 0)
        }
        conCandado.sync.empujarTodo()
        assertEquals(7, conCandado.sync.pendientes.value)
        conCandado.scope.cancel()
        sinBackend.scope.cancel()
    }

    // ---- pediatría, sobre la misma base, sigue enviando lo suyo ----

    @Test
    fun un_backend_que_envia_manda_lo_de_pediatria_sin_tocar_nada_de_clinica() = conBaseMigrada { base ->
        val clinicaAntes = sinPediatria(volcadoTotal(base.driver))
        val pedi = pediatria()
        val clin = clinica()
        val m = motor(base, pedi, clin)

        m.sync.empujarTodo()
        esperarQuieto(m)

        assertEquals(
            listOf("asegurarConsulta", "pacientes/resolver", "enviarSecciones", "enviarRegistros", "enviarExamen", "enviarEstado"),
            pedi.llamadas,
        )
        assertEquals(emptyList(), clin.llamadas)
        assertEquals(0, programado(m))
        // Pediatría quedó limpia (consulta, sección, examen, registro y su antecedente)...
        val d = base.driver
        assertEquals(0L, numero(d, "SELECT COUNT(*) FROM consulta WHERE especialidad = 'pediatria' AND dirty = 1"))
        assertEquals(0L, numero(d, "SELECT COUNT(*) FROM seccion_valor WHERE consulta_id LIKE 'pedi-%' AND dirty = 1"))
        assertEquals(0L, numero(d, "SELECT COUNT(*) FROM examen_fisico WHERE consulta_id LIKE 'pedi-%' AND dirty = 1"))
        assertEquals(0L, numero(d, "SELECT COUNT(*) FROM registro_clinico WHERE id LIKE 'pedi-%' AND dirty = 1"))
        assertEquals(0L, numero(d, "SELECT COUNT(*) FROM antecedente WHERE id LIKE 'pedi-%' AND dirty = 1"))
        // ...y todo lo demás, columna por columna, exactamente como estaba (incluidos los `activo = 0`).
        assertEquals(clinicaAntes, sinPediatria(volcadoTotal(d)))
        assertEquals(perdedoras.sorted(), lista(d, "SELECT id FROM antecedente WHERE activo = 0 ORDER BY id"))
        // Clínica sigue contando sus seis consultas sucias.
        assertEquals(6, m.sync.pendientes.value)
        m.scope.cancel()
    }

    /**
     * Límite conocido, FIJADO sin cambiarlo (no es una promesa de que sea lo ideal): `archivo` no guarda de qué HC es, y
     * `archivosPendientesDeConsulta` toma los sucios sin consulta (`consulta_id` NULL) del paciente de la consulta que se envía.
     * Si un paciente tiene una consulta de pediatría y un adjunto por paciente de la HC general sin enviar, el envío de
     * pediatría lo recoge: si el archivo está en el dispositivo lo SUBIRÍA a pediatría, y si no lo está (como acá) lo marca
     * limpio sin enviar. Con la entrega L no pierde nada (clínica no envía), pero la entrega E tiene que resolverlo antes de
     * enviar adjuntos de clínica (por ejemplo filtrando por la HC de la consulta de origen).
     */
    @Test
    fun limite_conocido_el_envio_de_pediatria_recoge_los_adjuntos_por_paciente_de_cualquier_HC() = conBaseMigrada { base ->
        ejecutar(base.driver, "INSERT INTO archivo (id, paciente_id, consulta_id, seccion, nombre, mime, activo, updated_at, deleted, dirty, remoto_id) VALUES ('ar-por-paciente', '$p1', NULL, 'familigrama', 'familia.pdf', 'application/pdf', 1, 5020, 0, 1, '')")
        val pedi = pediatria()
        val m = motor(base, pedi, clinica())

        m.sync.empujarTodo()
        esperarQuieto(m)

        // Sin archivo en el dispositivo (`ruta_local` NULL) no se sube, pero igual queda limpio.
        assertEquals(0L, numero(base.driver, "SELECT dirty FROM archivo WHERE id = 'ar-por-paciente'"))
        // El resto de los adjuntos de clínica (los de una consulta de clínica) no se tocan.
        assertEquals(2L, numero(base.driver, "SELECT COUNT(*) FROM archivo WHERE consulta_id = 'h-abierta' AND dirty = 1"))
        m.scope.cancel()
    }

    // ---- SyncEngine: otro canal, fuera del candado (deuda anotada en AGENTS.md) ----

    /**
     * Fija lo que hace HOY `SyncEngine` con esta base, sin cambiarlo: no pasa por el candado ni por ningún backend; arma de todas
     * las tablas `dirty` (también las clínicas de `clinica`, y los antecedentes `activo = 0`) un `POST sync/push` hacia la URL de
     * turnos. Esa ruta no existe: contesta 404, `sincronizar()` da `false` y no se limpia nada. Si alguien crea las rutas en turnos,
     * o apaga el motor, este test avisa; es la deuda de AGENTS.md (`SyncEngine` se inicia tras cada login).
     */
    @Test
    fun documenta_SyncEngine_manda_todo_lo_sucio_a_una_ruta_que_da_404_y_no_limpia_nada() = conBaseMigrada { base ->
        val pedidos = CopyOnWriteArrayList<Pair<String, String>>()
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/") { x ->
            pedidos += x.requestMethod + " " + x.requestURI.path to x.requestBody.readBytes().decodeToString()
            x.sendResponseHeaders(404, -1)
            x.close()
        }
        servidor.start()
        val api = ApiClient("http://127.0.0.1:${servidor.address.port}").apply { token = "token-de-prueba-1" }
        try {
            val antes = volcadoTotal(base.driver)
            val engine = SyncEngine(base.db, api, base.archivos, "dispositivo-de-prueba")

            val ok = engine.sincronizar()

            assertEquals(false, ok)
            // Un solo pedido: el push; el 404 corta antes de subir archivos y de bajar nada.
            assertEquals(listOf("POST /sync/push"), pedidos.map { it.first })
            val cambios = Json.parseToJsonElement(pedidos.single().second).jsonObject.getValue("cambios").jsonArray.map { it.jsonObject }
            val porTabla = cambios.groupBy({ it.getValue("tabla").jsonPrimitive.content }, { it.getValue("registroId").jsonPrimitive.content })
            // Qué tablas manda: las clínicas con filas `dirty` (clínica incluida; pediatría también; los pacientes limpios no).
            assertEquals(
                setOf(
                    "consultas", "seccion_valores", "examen_fisico", "archivos", "registros_clinicos", "laboratorios", "antecedentes",
                    "paciente_extras", "medico_preferencias",
                ),
                porTabla.keys,
            )
            assertEquals(consultasDeClinica.sorted().minus("c-p3-cerrada").plus("pedi-c1").sorted(), porTabla.getValue("consultas").sorted())
            // Los antecedentes `activo = 0` de `8.sqm` salen como cualquier otro (con `activo = false` en el payload).
            val antecedentes = cambios.filter { it.getValue("tabla").jsonPrimitive.content == "antecedentes" }
            assertEquals(
                perdedoras.sorted(),
                antecedentes.filter { !it.getValue("payload").jsonObject.getValue("activo").jsonPrimitive.boolean }
                    .map { it.getValue("registroId").jsonPrimitive.content }.sorted(),
            )
            assertEquals(cambios.size, engine.estado.value.pendientes, "nada se limpió: lo pendiente es todo lo enviado")
            assertIdentica(base.driver, antes)
        } finally {
            api.http.close()
            servidor.stop(0)
        }
    }
}
