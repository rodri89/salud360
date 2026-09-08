package com.salud360.server

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.sync.SyncAdapter
import com.salud360.core.data.sync.SyncAdapters
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.sync.CambioLocal
import com.salud360.core.model.sync.CambioRemoto
import com.salud360.core.model.sync.Operacion
import com.salud360.core.model.sync.PullResponse
import com.salud360.core.model.sync.PushRequest
import com.salud360.core.model.sync.PushResponse
import com.salud360.core.model.sync.Rechazo
import com.salud360.core.model.sync.Tablas
import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Sincronización del lado servidor.
 *
 * - `push`: aplica cada cambio a las tablas reales (misma lógica que el cliente, vía [SyncAdapters])
 *   y lo agrega al registro `cambios` con una versión creciente. Los turnos se validan para que
 *   dos dispositivos sin conexión no reserven el mismo horario.
 * - `pull`: devuelve los cambios de una tabla posteriores a una versión.
 */
class SyncService(private val db: Salud360Db) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val adapters: Map<String, SyncAdapter> = SyncAdapters.todos(db).associateBy { it.tabla }
    private val mutex = Mutex()
    private val driver get() = db.driverInterno()

    init {
        driver.execute(null, """
            CREATE TABLE IF NOT EXISTS cambios (
                version INTEGER PRIMARY KEY AUTOINCREMENT,
                tabla TEXT NOT NULL,
                registro_id TEXT NOT NULL,
                operacion TEXT NOT NULL,
                payload TEXT NOT NULL,
                usuario_id TEXT NOT NULL,
                dispositivo_id TEXT NOT NULL,
                creado_en INTEGER NOT NULL
            )
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX IF NOT EXISTS cambios_tabla_version ON cambios(tabla, version)", 0)
    }

    suspend fun push(req: PushRequest, usuario: Usuario): PushResponse = mutex.withLock {
        val aceptados = mutableListOf<String>()
        val rechazados = mutableListOf<Rechazo>()
        // Se aplican en el orden de las tablas (catálogos primero) para respetar dependencias.
        val ordenados = req.cambios.sortedBy { Tablas.todas.indexOf(it.tabla).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        for (c in ordenados) {
            val adapter = adapters[c.tabla]
            if (adapter == null) { rechazados += Rechazo(c.registroId, c.tabla, "Tabla desconocida"); continue }
            val motivo = validar(c)
            if (motivo != null) { rechazados += Rechazo(c.registroId, c.tabla, motivo); continue }
            val version = System.currentTimeMillis()
            runCatching {
                adapter.aplicar(CambioRemoto(c.tabla, c.registroId, c.operacion, c.payload, version))
                registrar(c, usuario.id, req.dispositivoId, version)
            }.onSuccess { aceptados += c.registroId }
                .onFailure { rechazados += Rechazo(c.registroId, c.tabla, it.message ?: "Error al aplicar") }
        }
        PushResponse(aceptados, rechazados)
    }

    /** Un turno activo no puede pisar otro turno activo/bloqueado de otro registro en el mismo horario. */
    private suspend fun validar(c: CambioLocal): String? {
        if (c.tabla != Tablas.TURNOS || c.operacion == Operacion.DELETE) return null
        val t = runCatching { json.decodeFromJsonElement(Turno.serializer(), c.payload) }.getOrNull() ?: return "Turno inválido"
        if (t.estado == EstadoTurno.CANCELADO) return null
        val ocupados = db.turnosQueries.turnosOcupados(t.medicoId, t.consultorioId, t.fecha.toString()).awaitAsList().map { it.toModel() }
        val choque = ocupados.firstOrNull { it.id != t.id && it.horario == t.horario }
        return if (choque != null) "El horario ${t.horario} ya está ocupado (${choque.pacienteNombre.ifBlank { "bloqueado" }})" else null
    }

    private fun registrar(c: CambioLocal, usuarioId: String, dispositivoId: String, version: Long) {
        driver.execute(null, "INSERT INTO cambios(tabla, registro_id, operacion, payload, usuario_id, dispositivo_id, creado_en) VALUES (?, ?, ?, ?, ?, ?, ?)", 7) {
            bindString(0, c.tabla); bindString(1, c.registroId); bindString(2, c.operacion.name); bindString(3, c.payload.toString())
            bindString(4, usuarioId); bindString(5, dispositivoId); bindLong(6, version)
        }
    }

    /** Registra un cambio generado por el propio servidor (ej. alta de usuario) para que llegue a los clientes. */
    fun registrarInterno(tabla: String, registroId: String, payload: JsonObject) {
        registrar(CambioLocal(0, tabla, registroId, Operacion.UPSERT, payload, System.currentTimeMillis()), "servidor", "servidor", System.currentTimeMillis())
    }

    fun pull(tabla: String, desde: Long, limite: Int): PullResponse {
        val filas = mutableListOf<CambioRemoto>()
        var ultima = desde
        driver.executeQuery(null, "SELECT version, registro_id, operacion, payload, creado_en FROM cambios WHERE tabla = ? AND creado_en > ? ORDER BY creado_en, version LIMIT ?", { cursor ->
            while (cursor.next().value) {
                val creado = cursor.getLong(4)!!
                filas += CambioRemoto(tabla, cursor.getString(1)!!, Operacion.valueOf(cursor.getString(2)!!), json.parseToJsonElement(cursor.getString(3)!!).jsonObject, creado)
                ultima = maxOf(ultima, creado)
            }
            app.cash.sqldelight.db.QueryResult.Unit
        }, 3) { bindString(0, tabla); bindLong(1, desde); bindLong(2, (limite + 1).toLong()) }
        val hayMas = filas.size > limite
        val pagina = if (hayMas) filas.take(limite) else filas
        // Se colapsan cambios repetidos del mismo registro dejando el último
        val colapsados = pagina.groupBy { it.registroId }.map { (_, v) -> v.last() }.sortedBy { it.version }
        return PullResponse(colapsados, hasta = pagina.lastOrNull()?.version ?: desde, hayMas = hayMas)
    }
}
