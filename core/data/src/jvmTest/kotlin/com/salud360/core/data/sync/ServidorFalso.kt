package com.salud360.core.data.sync

import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.SECCIONES_HABILITADAS
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ClinicaEscrituraApi
import com.salud360.core.data.network.hc.ConsultaAbiertaDto
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.PayloadsClinica
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.HcResolverRespuesta
import com.salud360.core.data.network.hc.PaginaCartera
import com.salud360.core.data.network.hc.PacientesHcApi
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.network.hc.RespuestaAntecedentesDto
import com.salud360.core.data.network.hc.RespuestaBajaDto
import com.salud360.core.data.network.hc.RespuestaCerrarDto
import com.salud360.core.data.network.hc.RespuestaCrearConsultaDto
import com.salud360.core.data.network.hc.RespuestaEscrituraDto
import com.salud360.core.data.network.hc.RespuestaRegistrosDto
import com.salud360.core.data.repos.HcClinicaBackend
import com.salud360.core.data.repos.HcClinicaPruebas
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.PacientesHcRepository
import com.salud360.core.data.repos.TurnosPorConsulta
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

        /** `<sección>.<clave>` -> valor de los formularios (`""` = vaciado). */
        val formularios = linkedMapOf<String, String>()

        /** Clave de la casilla -> `<0|1>|<detalle>`; la `nota` va con su texto tal cual. */
        val antecedentes = linkedMapOf<String, String>()
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

    /** Lo que llegó a `formularios` (sección -> clave -> valor), ya con las reglas del cuerpo de la API (T61). */
    val formulariosEnviados = CopyOnWriteArrayList<Map<String, Map<String, String>>>()

    /** Cada cuerpo de `antecedentes` que llegó, con la ficha y la consulta de la ruta. */
    class AntecedentesRecibido(val pacienteWebId: Long, val consultaWebId: Long, val cuerpo: JsonObject)

    val antecedentesRecibidos = CopyOnWriteArrayList<AntecedentesRecibido>()

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

    /**
     * `PUT consultas/{id}/formularios` (T54, T61). Como la remota real, arma el cuerpo con `PayloadsClinica` y **sin nada que mandar no hace ningún pedido**.
     * Es un upsert por campo: `guardadas` son `<sección>.<clave>` y las que figuran en [noConoce] van en `desconocidas` sin escribirse.
     */
    override suspend fun formularios(consultaWebId: Long, valores: Map<String, Map<String, String>>): RespuestaEscrituraDto {
        val cuerpo = PayloadsClinica.formularios(valores) ?: return RespuestaEscrituraDto(guardadas = emptyList(), desconocidas = emptyList())
        val enviados = (cuerpo.getValue("formularios") as JsonObject).mapValues { (_, secc) ->
            (secc as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content }
        }
        formulariosEnviados += enviados
        val claves = enviados.flatMap { (seccion, campos) -> campos.keys.map { "$seccion.$it" } }
        return operar("formularios", "$consultaWebId ${claves.sorted().joinToString(",")}") {
            val c = consulta(consultaWebId)
            if (c.estado != "ABIERTA" || c.baja) throw TobbException(409, "consulta_cerrada", "La consulta está cerrada o dada de baja.")
            enviados.forEach { (seccion, campos) -> campos.forEach { (clave, valor) -> if ("$seccion.$clave" !in noConoce) c.formularios["$seccion.$clave"] = valor } }
            RespuestaEscrituraDto(guardadas = claves.filter { it !in noConoce }, desconocidas = claves.filter { it in noConoce })
        }
    }

    /**
     * `PUT pacientes/{id}/antecedentes-familiares` (T61). Arma el cuerpo con `PayloadsClinica` (lo que cambió contra [enviados]) y sin nada que mandar no
     * hace ningún pedido. La consulta tiene que ser de esa ficha (`422`) y estar abierta (`409`); `guardadas` son las claves de las casillas (y `nota`).
     */
    override suspend fun antecedentes(
        pacienteWebId: Long,
        consultaWebId: Long,
        antecedentes: List<Antecedente>,
        enviados: Map<String, Antecedente>,
    ): RespuestaAntecedentesDto {
        val cuerpo = PayloadsClinica.antecedentes(consultaWebId, antecedentes, enviados) ?: return RespuestaAntecedentesDto(id = null, guardadas = emptyList())
        antecedentesRecibidos += AntecedentesRecibido(pacienteWebId, consultaWebId, cuerpo)
        val casillas = cuerpo.getValue("casillas") as JsonObject
        val claves = casillas.keys.toList() + listOfNotNull("nota".takeIf { cuerpo.containsKey("nota") })
        return operar("antecedentes", "$pacienteWebId $consultaWebId ${claves.sorted().joinToString(",")}") {
            val c = consulta(consultaWebId)
            if (c.pacienteWebId != pacienteWebId) throw TobbException(422, "datos_invalidos", "La consulta no es de ese paciente.")
            if (c.estado != "ABIERTA" || c.baja) throw TobbException(409, "consulta_cerrada", "La consulta está cerrada o dada de baja.")
            casillas.forEach { (clave, casilla) ->
                if (clave !in noConoce) {
                    casilla as JsonObject
                    c.antecedentes[clave] = "${(casilla.getValue("flag") as JsonPrimitive).content}|${(casilla.getValue("detalle") as JsonPrimitive).content}"
                }
            }
            if (cuerpo.containsKey("nota") && "nota" !in noConoce) c.antecedentes["nota"] = (cuerpo.getValue("nota") as JsonPrimitive).content
            RespuestaAntecedentesDto(id = 900L + consultaWebId, guardadas = claves.filter { it !in noConoce }, desconocidas = claves.filter { it in noConoce })
        }
    }

    // T63-b: el servidor falso de los registros (estudios, exámenes, interconsultas) llega con la mitad de registros.
    override suspend fun registros(consultaWebId: Long, registros: List<RegistroClinico>, enviados: Map<Id, RegistroClinico>): RespuestaRegistrosDto =
        error("el servidor falso de los registros llega con T63-b")

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

/**
 * El `pacientes/resolver` de mentira (spec 005, T26): contesta lo que diga [respuesta] y anota cada pedido. Por omisión vincula con la
 * ficha 5002 por documento. La cartera no se pide nunca al enviar.
 */
internal class ResolverFalso : PacientesHcApi {
    val pedidos = CopyOnWriteArrayList<HcPacienteRequest>()
    val elegidas = CopyOnWriteArrayList<Long?>()

    @Volatile
    var respuesta: (pedido: HcPacienteRequest, elegida: Long?) -> HcResolverRespuesta = { pedido, elegida ->
        HcResolverRespuesta(ficha(elegida ?: 5002, pedido.dni), creado = false, vinculadoPor = if (elegida != null) "elegido" else "dni")
    }

    override suspend fun pagina(desde: Int, limite: Int): PaginaCartera = error("al enviar no se pide la cartera")

    override suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta {
        pedidos += pedido
        elegidas += elegida
        return respuesta(pedido, elegida)
    }

    companion object {
        fun ficha(id: Long, dni: String) = HcPacienteFicha(id = id, nombre = "SALUD360 $id", apellido = "PRUEBA", dni = dni)

        /** La respuesta de la web cuando vincula con la ficha [id]. */
        fun vinculada(id: Long, dni: String) = HcResolverRespuesta(ficha(id, dni), creado = false, vinculadoPor = "dni")
    }
}

/** Todo lo que un test del envío necesita a mano. [nuevoEnvio] arma otra cola sobre la misma base y el mismo servidor (como al reabrir la app). */
internal class Escena(
    val db: Salud360Db,
    val driver: SqlDriver,
    val servidor: ServidorFalso,
    val reloj: RelojFalso,
    val scope: CoroutineScope,
    private val habilitadas: Set<String>,
    pacientesApi: PacientesHcApi,
    /** El médico de la sesión, que el test puede cambiar (otro médico entra en el mismo dispositivo) o dejar en `null` (sin sesión). */
    @Volatile var medicoDeLaSesion: Id? = MEDICO,
) {
    /** La fecha de «hoy» de la escena, para los estados: un día fijo, noviembre de 2026. */
    private val base = 1_793_000_000_000L

    /** El repositorio de pacientes real, sobre [pacientesApi] (por omisión, una que falla si alguien resuelve algo). */
    val pacientes = PacientesHcRepository(db, PacientesHcApis(mapOf(CodigosHc.GENERAL to pacientesApi)), mapOf(CodigosHc.GENERAL to "Clínica general"), esperar = {})

    /** El turno por consulta que comparten la cola, el backend y el repositorio (como los arma DataModule). */
    val turnos = TurnosPorConsulta()

    fun nuevoEnvio(
        escritura: ClinicaEscrituraApi? = servidor,
        dispositivo: String = DISPOSITIVO,
        habilitadas: Set<String> = this.habilitadas,
    ) = HcClinicaEnvio(
        db, scope, reloj::esperar, { base + reloj.ahora }, habilitadas,
        escritura = escritura,
        pacientes = pacientes,
        dispositivo = dispositivo,
        nombreHc = "Clínica general",
        medicoDeLaSesion = { medicoDeLaSesion },
        turnos = turnos,
    )

    val envio: HcClinicaEnvio = nuevoEnvio()

    /** El backend real de la HC general: lee de una API de mentira y escribe contra el servidor falso (como lo arma DataModule). */
    val backend = HcClinicaBackend(db, HcClinicaPruebas.ApiFalsa(), pacientes, { 0L }, HcClinicaBackend.MAX_PAGINAS, servidor, turnos)

    /** El repositorio de la app, con [envio] cableado: cerrar, reabrir y anular hablan con la cola. [conCola] = false lo arma sin ella. */
    fun repositorio(conCola: Boolean = true) = HcRepository(db, mapOf(CodigosHc.GENERAL to backend), if (conCola) envio else null)

    val hc: HcRepository by lazy { repositorio() }

    companion object {
        const val DISPOSITIVO = "disp-1"
    }
}

/**
 * Corre [bloque] con una base temporal (el paciente de prueba ya vinculado con la ficha 5001, salvo [vinculado] = false), un servidor
 * falso, la cola real y la sesión del médico de prueba.
 */
internal fun conServidor(
    habilitadas: Set<String> = SECCIONES_HABILITADAS,
    servidor: ServidorFalso = ServidorFalso(),
    vinculado: Boolean = true,
    pacientesApi: PacientesHcApi = HcClinicaPruebas.PacientesProhibida,
    bloque: suspend Escena.() -> Unit,
) = HcClinicaPruebas.conBase(vinculado) { db, driver ->
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        Escena(db, driver, servidor, RelojFalso(), scope, habilitadas, pacientesApi).bloque()
    } finally {
        scope.cancel()
    }
}