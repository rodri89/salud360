package com.salud360.core.data.repos

import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.sync.HcApiSync
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Candado de [HcApiSync] contra el envío (spec 002, E5.1, RF-47 y RF-48): con un backend de solo lectura NINGÚN camino de
 * envío llama al backend —`empujarTodo`, `empujarYa`, `marcarSucia` ni los reintentos—, lo que está `dirty` en el
 * dispositivo queda exactamente igual y un backend que sí envía (pediatría) sigue enviando. Sobre una base SQLite de
 * verdad en un archivo temporal, con un backend falso que cuenta cada llamada; datos inventados.
 *
 * El motor corre en `Dispatchers.Unconfined`: sin esperas de por medio (`esperaMs = 0`) cada llamada termina antes de
 * volver, así que lo que se ve al volver es el resultado, y un reintento programado se ve como un hijo activo del `Job`
 * del motor (ver [programado]).
 */
class HcApiSyncSoloLecturaTest {

    /**
     * Backend falso que anota cada llamada de envío. [fallar] lo hace lanzar en `asegurarConsulta`: si el candado no
     * cortara, además de la llamada anotada quedaría un reintento programado.
     */
    private class BackendFalso(
        override val especialidad: String,
        override val soloLectura: Boolean,
        override val envioPropio: Boolean = false,
    ) : HcBackend {
        val llamadas = mutableListOf<String>()
        var fallar = false

        override suspend fun asegurarConsulta(consulta: Consulta): String? {
            llamadas += "asegurarConsulta"
            // Lo único que en el envío llega a `pacientes/resolver` (pediatría lo hace antes de abrir la consulta).
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

        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = emptyList()

        override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) = Unit
    }

    private val fecha = LocalDate.parse("2026-03-10")

    /** Un motor con su propio `Job`, para ver si dejó algo programado. */
    private class Motor(val sync: HcApiSync, val job: Job, val scope: CoroutineScope)

    private fun conBase(bloque: suspend (Salud360Db, ArchivoStore) -> Unit) {
        val archivo = File.createTempFile("salud360-hcapisync", ".db").also { it.delete() }
        val adjuntos = File.createTempFile("salud360-hcapisync", "").also { it.delete(); it.mkdirs() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(Salud360Db(driver), ArchivoStore(adjuntos.absolutePath))
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
            adjuntos.deleteRecursively()
        }
    }

    private fun motor(db: Salud360Db, archivos: ArchivoStore, vararg backends: BackendFalso): Motor {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        return Motor(HcApiSync(db, backends.associateBy { it.especialidad }, archivos, scope), job, scope)
    }

    /** Reintentos o esperas que el motor dejó programados: hijos todavía activos de su `Job`. */
    private fun programado(m: Motor): Int = m.job.children.count { it.isActive }

    /**
     * Espera a que un envío que sí tiene que salir termine: leer un adjunto cambia de hilo (`Dispatchers.IO`), así que
     * esa parte no vuelve antes de que se llame a `empujarTodo`. Con un backend de solo lectura no hace falta (ver
     * [programado], que cuenta también un envío en vuelo).
     */
    private suspend fun esperarQuieto(m: Motor) {
        withTimeout(30_000) { while (programado(m) > 0) delay(20) }
    }

    /**
     * Siembra, para la especialidad [esp], una consulta abierta sin enviar (`c1`) con sección, examen, registro, un adjunto
     * para subir, otro para dar de baja y un antecedente activo; otra consulta cerrada ya enviada (`c2`) con el estado
     * por mandar, y un antecedente con `activo = 0` sin consulta (como los que deja `8.sqm`). Todo `dirty = 1`.
     */
    private suspend fun sembrar(db: Salud360Db, archivos: ArchivoStore, esp: String) {
        val hq = db.historiaClinicaQueries
        val c1 = "$esp-c1"
        val c2 = "$esp-c2"
        val pac1 = "$esp-pac1"
        val pac2 = "$esp-pac2"
        hq.upsertConsulta(Consulta(c1, pac1, "m1", esp, "control", fecha).toRow(100))
        hq.upsertConsulta(Consulta(c2, pac2, "m1", esp, "control", fecha, EstadoConsulta.CERRADA, remotoId = "R-$c2").toRow(110))
        hq.upsertSeccionValor(SeccionValor(c1, "motivo_consulta", "descripcion", "texto inventado").toRow(120))
        hq.upsertExamenFisico(ExamenFisico(c1, peso = "70", talla = "170").toRow(130))
        hq.upsertRegistro(RegistroClinico("$esp-r1", pac1, c1, esp, "interconsulta", fecha, mapOf("motivo" to "inventado")).toRow(140))
        val ruta = archivos.guardar("$esp-a1", "foto.jpg", byteArrayOf(1, 2, 3))
        hq.upsertArchivo(Archivo("$esp-a1", pac1, c1, null, "fotos", "foto.jpg", "image/jpeg", 3, rutaLocal = ruta).toRow(150))
        hq.upsertArchivo(Archivo("$esp-a2", pac1, c1, null, "fotos", "vieja.jpg", "image/jpeg", activo = false, remotoId = "RA-$esp-a2").toRow(160))
        hq.upsertAntecedente(Antecedente("$esp-an1", pac1, esp, "familiares", "hta", true, "padre", c1).toRow(170))
        hq.upsertAntecedente(Antecedente("$esp-an2", pac2, esp, "familiares", "diabetes", true, "", null, activo = false).toRow(180))
        // No viajan por `HcApiSync`, pero también tienen que quedar como están.
        hq.upsertLaboratorio(Laboratorio("$esp-l1", pac1, c1, esp, "laboratorio_general", fecha, mapOf("hb" to "13")).toRow(190))
        hq.upsertPendiente(Pendiente("$esp-pe1", pac1, "m1", "pedir estudio", c1).toRow(200))
    }

    /** Todas las filas `dirty` de las tablas clínicas, con todas sus columnas (id, dirty, updated_at, remoto_id…). */
    private suspend fun filasSucias(db: Salud360Db): List<List<Any>> {
        val hq = db.historiaClinicaQueries
        return listOf(
            hq.consultasDirty().lista { it },
            hq.seccionValoresDirty().lista { it },
            hq.examenesFisicosDirty().lista { it },
            hq.antecedentesDirty().lista { it },
            hq.registrosDirty().lista { it },
            hq.archivosDirty().lista { it },
            hq.laboratoriosDirty().lista { it },
            hq.pendientesDirty().lista { it },
        )
    }

    private fun clinica() = BackendFalso("clinica", soloLectura = true).apply { fallar = true }

    /** La HC general con envío propio (spec 005): su envío es de `HcClinicaEnvio`, no de este motor. [soloLectura] es el de hoy (T22) o el de después (T23). */
    private fun clinicaPropia(soloLectura: Boolean) = BackendFalso("clinica", soloLectura = soloLectura, envioPropio = true).apply { fallar = true }
    private fun pediatria() = BackendFalso("pediatria", soloLectura = false)

    @Test
    fun empujarTodo_no_llama_a_nada_y_deja_todo_como_estaba() = conBase { db, archivos ->
        sembrar(db, archivos, "clinica")
        val antes = filasSucias(db)
        val backend = clinica()
        val m = motor(db, archivos, backend)

        m.sync.empujarTodo()

        assertEquals(emptyList(), backend.llamadas)
        assertEquals(0, programado(m))
        assertEquals(antes, filasSucias(db))
        // Q16-A: el contador sigue contando lo local sin enviar (c1 y c2), como antes del candado.
        assertEquals(2, m.sync.pendientes.value)
        m.scope.cancel()
    }

    @Test
    fun empujarYa_no_llama_a_nada_y_deja_todo_como_estaba() = conBase { db, archivos ->
        sembrar(db, archivos, "clinica")
        val antes = filasSucias(db)
        val backend = clinica()
        val m = motor(db, archivos, backend)

        m.sync.empujarYa("clinica-c1")
        m.sync.empujarYa("clinica-c2")

        assertEquals(emptyList(), backend.llamadas)
        assertEquals(0, programado(m))
        assertEquals(antes, filasSucias(db))
        m.scope.cancel()
    }

    @Test
    fun marcarSucia_sin_espera_no_llama_a_nada_y_deja_todo_como_estaba() = conBase { db, archivos ->
        sembrar(db, archivos, "clinica")
        val antes = filasSucias(db)
        val backend = clinica()
        val m = motor(db, archivos, backend)

        m.sync.marcarSucia("clinica-c1", esperaMs = 0)
        m.sync.marcarSucia("clinica-c2", esperaMs = 0)

        assertEquals(emptyList(), backend.llamadas)
        assertEquals(0, programado(m))
        assertEquals(antes, filasSucias(db))
        m.scope.cancel()
    }

    @Test
    fun marcarSucia_con_la_espera_de_siempre_no_deja_un_envio_programado() = conBase { db, archivos ->
        sembrar(db, archivos, "clinica")
        val backend = clinica()
        val m = motor(db, archivos, backend)

        m.sync.marcarSucia("clinica-c1")

        // Sin el candado quedaría un envío esperando los 4 s (ver la prueba de control con pediatría).
        assertEquals(0, programado(m))
        assertEquals(emptyList(), backend.llamadas)
        m.scope.cancel()
    }

    @Test
    fun antecedentes_y_registros_sucios_sin_consulta_sucia_tampoco_salen() = conBase { db, archivos ->
        // Solo un antecedente con `activo = 0` y un registro sin consulta: ninguna consulta, sección, examen ni adjunto
        // sucios. `consultasDeAntecedentesPendientes` los resuelve a "la última consulta del paciente": ahí también corta.
        val hq = db.historiaClinicaQueries
        hq.upsertConsulta(Consulta("clinica-c9", "clinica-pac9", "m1", "clinica", "control", fecha, EstadoConsulta.CERRADA, remotoId = "R-9").toRow(50, dirty = false))
        hq.upsertAntecedente(Antecedente("clinica-an9", "clinica-pac9", "clinica", "familiares", "acv", true, "", null, activo = false).toRow(60))
        hq.upsertRegistro(RegistroClinico("clinica-r9", "clinica-pac9", null, "clinica", "interconsulta", fecha, mapOf("motivo" to "inventado")).toRow(70))
        val antes = filasSucias(db)
        val backend = clinica()
        val m = motor(db, archivos, backend)

        m.sync.empujarTodo()

        assertEquals(emptyList(), backend.llamadas)
        assertEquals(0, programado(m))
        assertEquals(antes, filasSucias(db))
        // Igual que sin backend: no se cuentan (el contador no cambia, Q16-A).
        assertEquals(0, m.sync.pendientes.value)
        m.scope.cancel()
    }

    @Test
    fun una_consulta_que_no_existe_no_programa_nada() = conBase { db, archivos ->
        sembrar(db, archivos, "clinica")
        val m = motor(db, archivos, clinica())

        // Una consulta que no existe no rompe nada ni programa nada.
        m.sync.marcarSucia("no-existe", esperaMs = 0)
        m.sync.empujarYa("no-existe")

        assertEquals(0, programado(m))
        m.scope.cancel()
    }

    // ---- un backend con envío propio (spec 005, RF-5): este motor no le manda nada ni lo cuenta ----

    @Test
    fun con_envio_propio_ningun_camino_llama_al_backend_ni_cuenta_ni_programa_aunque_deje_de_ser_de_solo_lectura() = conBase { db, archivos ->
        for (soloLectura in listOf(true, false)) {
            sembrar(db, archivos, "clinica")
            val antes = filasSucias(db)
            val backend = clinicaPropia(soloLectura)
            val m = motor(db, archivos, backend)

            m.sync.empujarTodo()
            m.sync.empujarYa("clinica-c1")
            m.sync.empujarYa("clinica-c2")
            m.sync.marcarSucia("clinica-c1", esperaMs = 0)
            m.sync.marcarSucia("clinica-c2")

            assertEquals(emptyList(), backend.llamadas, "soloLectura=$soloLectura")
            assertEquals(0, programado(m), "soloLectura=$soloLectura")
            assertEquals(antes, filasSucias(db), "lo sucio queda exactamente igual (soloLectura=$soloLectura)")
            assertEquals(0, m.sync.pendientes.value, "no cuenta: lo cuenta el motor propio (soloLectura=$soloLectura)")
            m.scope.cancel()
        }
    }

    @Test
    fun con_envio_propio_los_antecedentes_y_registros_sucios_tampoco_salen_ni_se_cuentan() = conBase { db, archivos ->
        val hq = db.historiaClinicaQueries
        hq.upsertConsulta(Consulta("clinica-c9", "clinica-pac9", "m1", "clinica", "control", fecha, EstadoConsulta.CERRADA, remotoId = "R-9").toRow(50, dirty = false))
        hq.upsertAntecedente(Antecedente("clinica-an9", "clinica-pac9", "clinica", "familiares", "acv", true, "x", "clinica-c9").toRow(60))
        hq.upsertRegistro(RegistroClinico("clinica-r9", "clinica-pac9", "clinica-c9", "clinica", "interconsulta", fecha, mapOf("motivo" to "inventado")).toRow(70))
        val antes = filasSucias(db)
        val backend = clinicaPropia(soloLectura = false)
        val m = motor(db, archivos, backend)

        m.sync.empujarTodo()

        assertEquals(emptyList(), backend.llamadas)
        assertEquals(0, programado(m))
        assertEquals(antes, filasSucias(db))
        assertEquals(0, m.sync.pendientes.value)
        m.scope.cancel()
    }

    @Test
    fun pediatria_envia_y_la_hc_con_envio_propio_ni_se_toca_ni_se_cuenta() = conBase { db, archivos ->
        sembrar(db, archivos, "pediatria")
        sembrar(db, archivos, "clinica")
        val clinicaAntes = filasSucias(db).map { filas -> filas.filter { it.toString().contains("clinica") } }
        val pedi = pediatria()
        val clin = clinicaPropia(soloLectura = false)
        val m = motor(db, archivos, pedi, clin)

        m.sync.empujarTodo()
        esperarQuieto(m)

        assertTrue("enviarSecciones" in pedi.llamadas && "enviarEstado" in pedi.llamadas, "pediatría no envió: ${pedi.llamadas}")
        assertEquals(emptyList(), clin.llamadas)
        assertEquals(clinicaAntes, filasSucias(db).map { filas -> filas.filter { it.toString().contains("clinica") } })
        assertEquals(0, m.sync.pendientes.value, "pediatría quedó limpia y la de envío propio no se cuenta acá")
        m.scope.cancel()
    }

    @Test
    fun una_consulta_que_no_existe_no_programa_nada_con_envio_propio() = conBase { db, archivos ->
        sembrar(db, archivos, "clinica")
        val m = motor(db, archivos, clinicaPropia(soloLectura = false))

        m.sync.marcarSucia("no-existe", esperaMs = 0)
        m.sync.empujarYa("no-existe")

        assertEquals(0, programado(m))
        m.scope.cancel()
    }

    // ---- pediatría (backend que envía) sigue como siempre ----

    @Test
    fun control_un_backend_que_envia_recibe_todo_y_queda_limpio() = conBase { db, archivos ->
        sembrar(db, archivos, "pediatria")
        val backend = pediatria()
        val m = motor(db, archivos, backend)

        m.sync.empujarTodo()
        esperarQuieto(m)

        for (esperada in listOf(
            "asegurarConsulta", "pacientes/resolver", "enviarSecciones", "enviarRegistros", "subirArchivo", "borrarArchivo",
            "enviarExamen", "enviarEstado",
        )) {
            assertTrue(esperada in backend.llamadas, "faltó $esperada en ${backend.llamadas}")
        }
        val hq = db.historiaClinicaQueries
        assertEquals(emptyList(), hq.consultasDirty().lista { it })
        assertEquals(emptyList(), hq.seccionValoresDirty().lista { it })
        assertEquals(emptyList(), hq.examenesFisicosDirty().lista { it })
        assertEquals(emptyList(), hq.antecedentesDirty().lista { it })
        assertEquals(emptyList(), hq.registrosDirty().lista { it })
        assertEquals(emptyList(), hq.archivosDirty().lista { it })
        assertEquals(0, m.sync.pendientes.value)
        m.scope.cancel()
    }

    @Test
    fun control_un_backend_que_envia_sin_esperar_con_marcarSucia_y_empujarYa() = conBase { db, archivos ->
        sembrar(db, archivos, "pediatria")
        val backend = pediatria()
        val m = motor(db, archivos, backend)

        m.sync.marcarSucia("pediatria-c1", esperaMs = 0)
        esperarQuieto(m)
        assertTrue("enviarSecciones" in backend.llamadas, "marcarSucia no envió: ${backend.llamadas}")
        backend.llamadas.clear()
        m.sync.empujarYa("pediatria-c2")
        esperarQuieto(m)
        // c2 ya estaba creada (sin `pacientes/resolver`); lleva el antecedente de su paciente y el estado.
        assertEquals(listOf("asegurarConsulta", "enviarSecciones", "enviarEstado"), backend.llamadas)
        m.scope.cancel()
    }

    @Test
    fun control_un_envio_que_falla_deja_un_reintento_programado_y_la_marcarSucia_normal_espera() = conBase { db, archivos ->
        // Es la prueba de que `programado` ve lo que tiene que ver: con pediatría hay algo programado, con clínica no.
        sembrar(db, archivos, "pediatria")
        val backend = pediatria().apply { fallar = true }
        val m = motor(db, archivos, backend)

        m.sync.empujarYa("pediatria-c1")
        assertEquals(1, programado(m), "el envío fallido tendría que dejar un reintento")
        m.scope.cancel()

        val otro = motor(db, archivos, backend)
        otro.sync.marcarSucia("pediatria-c1")
        assertEquals(1, programado(otro), "marcarSucia tendría que dejar el envío esperando")
        otro.scope.cancel()
    }

    @Test
    fun mezcla_pediatria_envia_y_clinica_no() = conBase { db, archivos ->
        sembrar(db, archivos, "pediatria")
        sembrar(db, archivos, "clinica")
        val clinicaAntes = filasSucias(db).map { filas -> filas.filter { it.toString().contains("clinica") } }
        val pedi = pediatria()
        val clin = clinica()
        val m = motor(db, archivos, pedi, clin)

        m.sync.empujarTodo()
        esperarQuieto(m)

        assertTrue("enviarSecciones" in pedi.llamadas && "enviarEstado" in pedi.llamadas, "pediatría no envió: ${pedi.llamadas}")
        assertEquals(emptyList(), clin.llamadas)
        assertEquals(0, programado(m))
        assertEquals(clinicaAntes, filasSucias(db).map { filas -> filas.filter { it.toString().contains("clinica") } })
        // Pediatría quedó limpia, clínica sigue contando sus dos consultas.
        assertEquals(2, m.sync.pendientes.value)
        m.scope.cancel()
    }
}
