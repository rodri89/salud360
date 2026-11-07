package com.salud360.core.data.sync

import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.SUBENTREGA_HABILITADA
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ClinicaEscrituraApi
import com.salud360.core.data.network.hc.ConsultaAbiertaDto
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.RespuestaBajaDto
import com.salud360.core.data.network.hc.RespuestaCerrarDto
import com.salud360.core.data.network.hc.RespuestaCrearConsultaDto
import com.salud360.core.data.network.hc.RespuestaEscrituraDto
import com.salud360.core.data.repos.HcClinicaPruebas
import com.salud360.core.database.Salud360Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Un hclinica de mentira para el envío de la HC general (spec 005, T25): una [ClinicaEscrituraApi] **con memoria** que contesta como el
 * servidor real (`md/API_SALUD360_CLINICA.md`, «Escritura (E1)») sin red: crear, cerrar y dar de baja son **repetibles por clave**
 * (la misma clave devuelve la misma consulta y `creada = false`, sin una fila nueva), los textos y el examen se escriben sobre la
 * misma consulta, `guardadas` dice lo que se escribió y `desconocidas` lo que no conoce, y una consulta cerrada o dada de baja no acepta
 * escrituras (`409 consulta_cerrada`). Anota cada pedido en [pedidos] con el orden en que llegaron.
 *
 * Para armar fallas sin tiempo real: [falla] hace fallar una operación **antes** de que el servidor haga nada; [respuestasPerdidas]
 * hace que la operación **sí** se aplique pero la respuesta no llegue (sin conexión); [noConoce] son secciones o campos que el servidor
 * no guarda (van en `desconocidas`); [alRecibir] corre cuando el servidor ya aplicó la operación y todavía no contestó (el momento en
 * que, en la vida real, el médico puede seguir escribiendo: «cambio en vuelo»).
 */
internal class ServidorFalso : ClinicaEscrituraApi {
    class ConsultaWeb(val id: Long, val clave: String, val pacienteWebId: Long, val tipo: String) {
        var fecha: LocalDate? = null
        var edadMostrar: String = ""
        var estado: String = "ABIERTA"
        var baja = false
        var claveDeCierre: String? = null
        var claveDeBaja: String? = null
        val secciones = linkedMapOf<String, String>()
        val examen = linkedMapOf<String, String>()
    }

    data class CrearRecibido(
        val clave: String,
        val dispositivo: String,
        val pacienteWebId: Long,
        val tipo: String,
        val fecha: LocalDate,
        val edadMostrar: String?,
    )

    data class CerrarRecibido(val consultaWebId: Long, val clave: String, val dispositivo: String, val fecha: LocalDate, val edadMostrar: String?)

    /** Lo que hay en la «web», por id. */
    val consultas = linkedMapOf<Long, ConsultaWeb>()

    /** Cada pedido que llegó, en orden: `crear c:u1`, `secciones 7001 habitos,nota`, `examen 7001 peso`, `cerrar 7001 c:u1-cierre`, `baja 7001 c:u1-baja`. */
    val pedidos = CopyOnWriteArrayList<String>()

    /** Solo el nombre de la operación de cada pedido, en orden. */
    val operaciones: List<String> get() = pedidos.map { it.substringBefore(' ') }

    val creaciones = CopyOnWriteArrayList<CrearRecibido>()
    val cierres = CopyOnWriteArrayList<CerrarRecibido>()
    val textosEnviados = CopyOnWriteArrayList<Map<String, String>>()
    val camposEnviados = CopyOnWriteArrayList<Map<String, String>>()

    /** Las consultas que el paciente ya tenía abiertas, que `crear` informa en `otras_abiertas`. */
    var otrasAbiertas: List<ConsultaAbiertaDto> = emptyList()

    /** Hace fallar la operación (con ese error) antes de que el servidor la aplique; `null` = anda. */
    @Volatile
    var falla: ((operacion: String) -> Throwable?)? = null

    /** Operaciones que el servidor aplica pero cuya respuesta se pierde en el camino (sin conexión). */
    val respuestasPerdidas = mutableSetOf<String>()

    /** Secciones o campos que el servidor no guarda: van en `desconocidas`. */
    @Volatile
    var noConoce: Set<String> = emptySet()

    /** Corre con el servidor ya aplicado y sin haber contestado todavía. */
    @Volatile
    var alRecibir: (suspend (operacion: String) -> Unit)? = null

    private var proximoId = 7001L

    /** Cuántas consultas distintas hay en la «web». */
    val cantidadDeConsultas: Int get() = consultas.size

    private suspend fun <T> operar(operacion: String, detalle: String, efecto: () -> T): T {
        pedidos += "$operacion $detalle"
        falla?.invoke(operacion)?.let { throw it }
        val resultado = efecto()
        alRecibir?.invoke(operacion)
        if (operacion in respuestasPerdidas) throw TobbException(0, "red", "Sin conexión con la historia clínica (simulada: se perdió la respuesta)")
        return resultado
    }

    private fun consulta(id: Long): ConsultaWeb = consultas[id] ?: throw TobbException(404, "consulta_no_encontrada", "No existe la consulta.")

    private fun cabecera(c: ConsultaWeb) = ConsultaCabeceraDto(
        id = c.id,
        pacienteId = c.pacienteWebId,
        tipo = c.tipo,
        fecha = (c.fecha ?: LocalDate.parse("2026-11-06")).toString(),
        estado = c.estado,
        edadMostrar = c.edadMostrar,
        propia = true,
    )

    // Lo que el cliente mandó se anota antes de que el servidor pueda fallar: un pedido que no llegó a aplicarse igual salió.

    override suspend fun crear(
        clave: String,
        dispositivo: String,
        pacienteWebId: Long,
        tipo: String,
        fecha: LocalDate,
        edadMostrar: String?,
    ): RespuestaCrearConsultaDto {
        creaciones += CrearRecibido(clave, dispositivo, pacienteWebId, tipo, fecha, edadMostrar)
        return operar("crear", clave) {
            val existente = consultas.values.firstOrNull { it.clave == clave }
            val c = existente ?: ConsultaWeb(proximoId++, clave, pacienteWebId, tipo).also {
                it.fecha = fecha
                it.edadMostrar = edadMostrar.orEmpty()
                consultas[it.id] = it
            }
            RespuestaCrearConsultaDto(cabecera(c), creada = existente == null, otrasAbiertas = otrasAbiertas)
        }
    }

    override suspend fun secciones(consultaWebId: Long, textos: Map<String, String>): RespuestaEscrituraDto {
        textosEnviados += textos
        return operar("secciones", "$consultaWebId ${textos.keys.sorted().joinToString(",")}") {
            val c = consulta(consultaWebId)
            if (c.estado != "ABIERTA" || c.baja) throw TobbException(409, "consulta_cerrada", "La consulta está cerrada o dada de baja.")
            val guardar = textos.filterKeys { it !in noConoce }
            c.secciones += guardar
            RespuestaEscrituraDto(guardadas = guardar.keys.toList(), desconocidas = textos.keys.filter { it in noConoce })
        }
    }

    override suspend fun examen(consultaWebId: Long, campos: Map<String, String>): RespuestaEscrituraDto {
        camposEnviados += campos
        return operar("examen", "$consultaWebId ${campos.keys.sorted().joinToString(",")}") {
            val c = consulta(consultaWebId)
            if (c.estado != "ABIERTA" || c.baja) throw TobbException(409, "consulta_cerrada", "La consulta está cerrada o dada de baja.")
            val guardar = campos.filterKeys { it !in noConoce }
            c.examen += guardar
            RespuestaEscrituraDto(guardadas = guardar.keys.toList(), desconocidas = campos.keys.filter { it in noConoce })
        }
    }

    override suspend fun cerrar(consultaWebId: Long, clave: String, dispositivo: String, fecha: LocalDate, edadMostrar: String?): RespuestaCerrarDto {
        cierres += CerrarRecibido(consultaWebId, clave, dispositivo, fecha, edadMostrar)
        return operar("cerrar", "$consultaWebId $clave") {
            val c = consulta(consultaWebId)
            if (c.baja) throw TobbException(409, "consulta_cerrada", "La consulta está dada de baja.")
            if (c.claveDeCierre == null) {
                c.estado = "CERRADA"
                c.claveDeCierre = clave
                c.fecha = fecha
                if (!edadMostrar.isNullOrBlank()) c.edadMostrar = edadMostrar
            }
            RespuestaCerrarDto(cabecera(c), cerrada = true)
        }
    }
    override suspend fun baja(consultaWebId: Long, clave: String, dispositivo: String): RespuestaBajaDto =
        operar("baja", "$consultaWebId $clave") {
            val c = consulta(consultaWebId)
            if (c.estado == "CERRADA") throw TobbException(409, "consulta_cerrada", "Una consulta cerrada no se da de baja.")
            c.baja = true
            c.claveDeBaja = clave
            RespuestaBajaDto(baja = true, consultaId = consultaWebId)
        }
}

/** Todo lo que un test del envío necesita a mano. [nuevoEnvio] arma otra cola sobre la misma base y el mismo servidor (como al reabrir la app). */
internal class Escena(
    val db: Salud360Db,
    val driver: SqlDriver,
    val servidor: ServidorFalso,
    val reloj: RelojFalso,
    val scope: CoroutineScope,
    private val habilitada: SubentregaDeEscritura,
) {
    /** La fecha de «hoy» de la escena, para los estados: un día fijo, noviembre de 2026. */
    private val base = 1_793_000_000_000L

    fun nuevoEnvio(
        escritura: ClinicaEscrituraApi? = servidor,
        dispositivo: String = DISPOSITIVO,
        habilitada: SubentregaDeEscritura = this.habilitada,
    ) = HcClinicaEnvio(
        db, scope, reloj::esperar, { base + reloj.ahora }, habilitada,
        escritura = escritura,
        pacientes = HcClinicaPruebas.pacientes(db),
        dispositivo = dispositivo,
        nombreHc = "Clínica general",
    )

    val envio: HcClinicaEnvio = nuevoEnvio()

    companion object {
        const val DISPOSITIVO = "disp-1"
    }
}

/** Corre [bloque] con una base temporal (el paciente de prueba ya vinculado con la ficha 5001), un servidor falso y la cola real. */
internal fun conServidor(
    habilitada: SubentregaDeEscritura = SUBENTREGA_HABILITADA,
    servidor: ServidorFalso = ServidorFalso(),
    vinculado: Boolean = true,
    bloque: suspend Escena.() -> Unit,
) = HcClinicaPruebas.conBase(vinculado) { db, driver ->
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        Escena(db, driver, servidor, RelojFalso(), scope, habilitada).bloque()
    } finally {
        scope.cancel()
    }
}
