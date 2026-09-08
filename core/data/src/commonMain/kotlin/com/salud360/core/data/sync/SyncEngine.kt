package com.salud360.core.data.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.sync.CambioLocal
import com.salud360.core.model.sync.CambioRemoto
import com.salud360.core.model.sync.Operacion
import com.salud360.core.model.sync.PushRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EstadoSync(
    val sincronizando: Boolean = false,
    val ultimaSync: Long? = null,
    val pendientes: Int = 0,
    val online: Boolean = true,
    val error: String? = null,
)

/**
 * Motor de sincronización offline-first.
 *
 * 1. **Push**: recorre todas las tablas, toma las filas con `dirty = 1`, las envía al servidor y
 *    las marca limpias si fueron aceptadas.
 * 2. **Pull**: por cada tabla pide los cambios posteriores a la última versión conocida y los
 *    aplica localmente (última escritura gana; si hay un cambio local más nuevo se conserva).
 * 3. **Archivos**: sube los adjuntos que todavía no están en el servidor.
 *
 * Se ejecuta al iniciar sesión, periódicamente y cuando la app vuelve a primer plano.
 */
class SyncEngine(
    private val db: Salud360Db,
    private val api: ApiClient,
    private val archivos: ArchivoStore,
    private val dispositivoId: String,
    private val adapters: List<SyncAdapter> = SyncAdapters.todos(db),
) {
    private val log = Logger.withTag("Sync")
    private val mutex = Mutex()
    private val _estado = MutableStateFlow(EstadoSync())
    val estado: StateFlow<EstadoSync> = _estado.asStateFlow()
    private var periodico: Job? = null

    /** Ejecuta una sincronización completa. Devuelve true si terminó sin errores. */
    suspend fun sincronizar(): Boolean = mutex.withLock {
        if (api.token == null) return false
        _estado.value = _estado.value.copy(sincronizando = true, error = null)
        val ok = runCatching {
            push()
            subirArchivos()
            pull()
        }
        val pendientes = contarPendientes()
        _estado.value = EstadoSync(
            sincronizando = false,
            ultimaSync = if (ok.isSuccess) ahoraMillis() else _estado.value.ultimaSync,
            pendientes = pendientes,
            online = ok.isSuccess,
            error = ok.exceptionOrNull()?.message,
        )
        if (ok.isFailure) log.w(ok.exceptionOrNull()) { "Sincronización fallida" }
        ok.isSuccess
    }

    fun iniciarPeriodico(scope: CoroutineScope, cadaMillis: Long = 60_000) {
        periodico?.cancel()
        periodico = scope.launch {
            while (isActive) {
                sincronizar()
                delay(cadaMillis)
            }
        }
    }

    fun detener() { periodico?.cancel(); periodico = null }

    suspend fun contarPendientes(): Int = adapters.sumOf { it.dirty().size }

    private suspend fun push() {
        val cambios = adapters.flatMap { it.dirty() }
        if (cambios.isEmpty()) return
        cambios.chunked(200).forEach { lote ->
            val respuesta = api.push(PushRequest(lote, dispositivoId))
            val aceptados = respuesta.aceptados.toSet()
            lote.filter { it.registroId in aceptados }.forEach { c -> adapters.first { it.tabla == c.tabla }.marcarLimpio(c.registroId) }
            respuesta.rechazados.forEach { r ->
                log.w { "Rechazado ${r.tabla}/${r.registroId}: ${r.motivo}" }
                // el servidor manda su versión en el pull; marcamos limpio para no reintentar eternamente
                adapters.first { it.tabla == r.tabla }.marcarLimpio(r.registroId)
            }
        }
    }

    private suspend fun pull() {
        adapters.forEach { adapter ->
            var desde = db.authQueries.syncEstado(adapter.tabla).uno { it.ultima_version } ?: 0L
            do {
                val respuesta = api.pull(adapter.tabla, desde)
                respuesta.cambios.forEach { c -> runCatching { adapter.aplicar(c) }.onFailure { log.e(it) { "Error aplicando ${c.tabla}/${c.registroId}" } } }
                desde = maxOf(desde, respuesta.hasta)
                db.authQueries.guardarSyncEstado(adapter.tabla, desde, ahoraMillis())
            } while (respuesta.hayMas)
        }
    }

    private suspend fun subirArchivos() {
        val q = db.historiaClinicaQueries
        val pendientes = q.archivosPendientesDeSubir().awaitAsList()
        pendientes.forEach { fila ->
            val archivo = fila.toModel()
            val bytes = archivo.rutaLocal?.let { archivos.leer(it) } ?: return@forEach
            runCatching { api.subirArchivo(archivo.id, archivo.nombre, archivo.mime, bytes) }
                .onSuccess { r -> q.upsertArchivo(archivo.copy(subido = true, urlRemota = r.url).toRow(ahoraMillis())) }
                .onFailure { log.w(it) { "No se pudo subir ${archivo.nombre}" } }
        }
    }

    /** Descarga un adjunto que está en el servidor pero no en el dispositivo. */
    suspend fun descargarArchivo(id: String): String? {
        val q = db.historiaClinicaQueries
        val archivo = q.archivoPorId(id).uno { it.toModel() } ?: return null
        archivo.rutaLocal?.let { if (archivos.existe(it)) return it }
        val bytes = runCatching { api.descargarArchivo(id) }.getOrNull() ?: return null
        val ruta = archivos.guardar(archivo.id, archivo.nombre, bytes)
        q.upsertArchivo(archivo.copy(rutaLocal = ruta).toRow(ahoraMillis(), dirty = false))
        return ruta
    }

    /** Borra todo el estado de sincronización para forzar una descarga completa. */
    suspend fun reiniciar() { db.authQueries.borrarSyncEstados() }
}

/** Adaptador de sincronización de una tabla. */
interface SyncAdapter {
    val tabla: String
    suspend fun dirty(): List<CambioLocal>
    suspend fun aplicar(cambio: CambioRemoto)
    suspend fun marcarLimpio(registroId: String)
}

/** Operación derivada del flag `deleted` de la fila. */
fun operacionDe(deleted: Long): Operacion = if (deleted != 0L) Operacion.DELETE else Operacion.UPSERT
