package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.SUBENTREGA_HABILITADA
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.ClinicaEscrituraApi
import com.salud360.core.data.network.hc.RespuestaAntecedentesDto
import com.salud360.core.data.network.hc.RespuestaBajaDto
import com.salud360.core.data.network.hc.RespuestaCerrarDto
import com.salud360.core.data.network.hc.RespuestaCrearConsultaDto
import com.salud360.core.data.network.hc.RespuestaEscrituraDto
import com.salud360.core.data.network.hc.RespuestaRegistrosDto
import com.salud360.core.data.repos.HcClinicaPruebas
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lo que comparten los tests de la cola de envío de la HC general (spec 005, T24): [HcClinicaEnvio] con un `enviar` de prueba que
 * anota cuándo empieza y cuándo termina cada envío (y cuántos hay a la vez), sobre una base SQLite temporal, un reloj falso
 * ([RelojFalso]) y `Dispatchers.Unconfined` (nada espera de verdad). Datos inventados; ninguna red.
 */
internal val FECHA_DE_PRUEBA: LocalDate = LocalDate.parse("2026-11-06")

/** El envío de prueba: no manda nada; anota lo que pasa en [eventos] y deja que el test frene o falle cada envío. */
internal class EnvioProbado(
    db: Salud360Db,
    scope: CoroutineScope,
    private val reloj: RelojFalso,
    habilitada: SubentregaDeEscritura = SUBENTREGA_HABILITADA,
    /** El médico de la sesión; por omisión, el de prueba (la cola solo toma las consultas del médico de la sesión). */
    private val medicoDeLaSesion: Id? = MEDICO,
) : HcClinicaEnvio(db, scope, reloj::esperar, { reloj.ahora }, habilitada, medicoDeLaSesion = { medicoDeLaSesion }) {
    /** `inicio <consulta>`, `fin <consulta>` (terminó) o `cancelado <consulta>`, en el orden en que pasaron. */
    val eventos = CopyOnWriteArrayList<String>()

    /** Los momentos del reloj falso en que empezó cada envío, en orden. */
    val momentos = CopyOnWriteArrayList<Long>()

    /** Una puerta por envío, en el orden en que empiezan: el envío no termina hasta que el test la abre. Sin puertas, termina solo. */
    val puertas = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

    /** Si no es null, cada envío falla con esto después de pasar su puerta. */
    @Volatile
    var falla: Throwable? = null

    private val enVuelo = AtomicInteger(0)
    private val maximo = AtomicInteger(0)

    /** Cuántos envíos empezaron. */
    val envios: List<String> get() = eventos.filter { it.startsWith("inicio ") }

    /** El máximo de envíos que hubo a la vez (de cualquier consulta). */
    val maximoALaVez: Int get() = maximo.get()

    override suspend fun enviar(consultaId: Id) {
        eventos += "inicio $consultaId"
        momentos += reloj.ahora
        val ahoraEnVuelo = enVuelo.incrementAndGet()
        maximo.updateAndGet { maxOf(it, ahoraEnVuelo) }
        try {
            if (puertas.isNotEmpty()) puertas.removeAt(0).await()
            falla?.let { throw it }
            eventos += "fin $consultaId"
        } catch (e: kotlinx.coroutines.CancellationException) {
            eventos += "cancelado $consultaId"
            throw e
        } finally {
            enVuelo.decrementAndGet()
        }
    }
}

/** La API de escritura de mentira: anota cada llamada. Si algo la usa sin querer, el test lo ve. */
internal class EscrituraContada : ClinicaEscrituraApi {
    val llamadas = CopyOnWriteArrayList<String>()

    override suspend fun crear(clave: String, dispositivo: String, pacienteWebId: Long, tipo: String, fecha: LocalDate, edadMostrar: String?): RespuestaCrearConsultaDto {
        llamadas += "crear"
        error("no se usa en T24")
    }

    override suspend fun secciones(consultaWebId: Long, textos: Map<String, String>): RespuestaEscrituraDto {
        llamadas += "secciones"
        error("no se usa en T24")
    }

    override suspend fun examen(consultaWebId: Long, campos: Map<String, String>): RespuestaEscrituraDto {
        llamadas += "examen"
        error("no se usa en T24")
    }

    override suspend fun formularios(consultaWebId: Long, valores: Map<String, Map<String, String>>): RespuestaEscrituraDto {
        llamadas += "formularios"
        error("no se usa en T24")
    }

    override suspend fun antecedentes(pacienteWebId: Long, consultaWebId: Long, antecedentes: List<Antecedente>, enviados: Map<String, Antecedente>): RespuestaAntecedentesDto {
        llamadas += "antecedentes"
        error("no se usa en T24")
    }

    override suspend fun registros(consultaWebId: Long, registros: List<RegistroClinico>, enviados: Map<Id, RegistroClinico>): RespuestaRegistrosDto {
        llamadas += "registros"
        error("no se usa en T24")
    }

    override suspend fun cerrar(consultaWebId: Long, clave: String, dispositivo: String, fecha: LocalDate, edadMostrar: String?): RespuestaCerrarDto {
        llamadas += "cerrar"
        error("no se usa en T24")
    }

    override suspend fun baja(consultaWebId: Long, clave: String, dispositivo: String): RespuestaBajaDto {
        llamadas += "baja"
        error("no se usa en T24")
    }
}

/** Una consulta propia de la HC general sin enviar. El [tipo] es el que de verdad guarda la app (`control` o `telemedicina`). */
internal suspend fun Salud360Db.sembrarPropia(
    id: Id,
    paciente: Id = PACIENTE,
    medico: Id = MEDICO,
    remotoId: String = "",
    estado: EstadoConsulta = EstadoConsulta.ABIERTA,
    edad: String = "45 años",
    dirty: Boolean = true,
    actualizadaEn: Long = 100,
    tipo: String = "control",
) = historiaClinicaQueries.upsertConsulta(
    Consulta(id, paciente, medico, "clinica", tipo, FECHA_DE_PRUEBA, estado, edad, remotoId = remotoId, propia = true).toRow(actualizadaEn, dirty = dirty),
)
/** Una consulta del histórico del dispositivo (`propia = 0`): nada de ella sale solo (RF-7, D2). */
internal suspend fun Salud360Db.sembrarHistorica(id: Id, paciente: Id = PACIENTE, medico: Id = MEDICO) =
    historiaClinicaQueries.upsertConsulta(Consulta(id, paciente, medico, "clinica", "consulta", FECHA_DE_PRUEBA, EstadoConsulta.CERRADA).toRow(100))

/** Una consulta que trajo la lectura de la web (id `hcc-…`, con `remoto_id`): solo se lee. */
internal suspend fun Salud360Db.sembrarDeLaWeb(id: Id = "hcc-c7003", paciente: Id = PACIENTE, medico: Id = MEDICO) =
    historiaClinicaQueries.upsertConsulta(
        Consulta(id, paciente, medico, "clinica", "consulta", FECHA_DE_PRUEBA, EstadoConsulta.CERRADA, remotoId = id.removePrefix("hcc-c")).toRow(100, dirty = false),
    )

/** Un texto sin enviar en la consulta [consultaId] (el autoguardado lo deja `dirty`). */
internal suspend fun Salud360Db.sembrarTexto(consultaId: Id, seccion: String = "nota", texto: String = "texto inventado") =
    historiaClinicaQueries.upsertSeccionValor(SeccionValor(consultaId, seccion, "texto", texto).toRow(120))

/** Corre [bloque] con una base temporal, un reloj falso y la cola de prueba sobre `Dispatchers.Unconfined`. */
internal fun conCola(habilitada: SubentregaDeEscritura = SUBENTREGA_HABILITADA, bloque: suspend (Salud360Db, EnvioProbado, RelojFalso) -> Unit) =
    HcClinicaPruebas.conBase { db, _ ->
        val reloj = RelojFalso()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            bloque(db, EnvioProbado(db, scope, reloj, habilitada), reloj)
        } finally {
            scope.cancel()
        }
    }
