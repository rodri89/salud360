package com.salud360.core.data.sync

import co.touchlab.kermit.Logger
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.repos.HcBackend
import com.salud360.core.data.repos.HcPediatriaBackend
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Envía a la API de cada historia clínica lo que se fue guardando en el dispositivo.
 *
 * La historia clínica se guarda **primero en el dispositivo**: el autoguardado de la consulta dispara
 * cada 600 ms de pausa mientras el médico escribe con el paciente delante, y el consultorio puede no
 * tener señal. Este motor junta los cambios pendientes de una consulta y los manda en un solo pedido,
 * con su propia espera de unos segundos, así treinta segundos de tecleo son unos pocos envíos y no uno
 * por tecla.
 *
 * Si el envío falla, la fila queda marcada como pendiente y se reintenta con esperas crecientes.
 * Nada se pierde: lo peor que pasa es que tarde en llegar.
 */
class HcApiSync(
    private val db: Salud360Db,
    /** Backend por código de especialidad. Una especialidad sin backend no se envía a ningún lado. */
    private val backends: Map<String, HcBackend>,
    /** Archivos guardados en el dispositivo. Sin él los adjuntos no se suben, el resto va igual. */
    private val archivos: ArchivoStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val log = Logger.withTag("HcApiSync")
    private val hq get() = db.historiaClinicaQueries

    private val jobs = mutableMapOf<Id, Job>()
    private val candado = Mutex()
    private val _pendientes = MutableStateFlow(0)

    /** Cuántas consultas tienen cambios sin enviar, para avisarlo en la pantalla. */
    val pendientes: StateFlow<Int> = _pendientes.asStateFlow()

    /** true si esta especialidad tiene API configurada. */
    fun activo(especialidad: String): Boolean = backends.containsKey(especialidad)

    /**
     * Avisa que una consulta cambió. Reinicia la espera, así el envío sale cuando el médico deja de
     * escribir y no en cada tecla.
     */
    fun marcarSucia(consultaId: Id, esperaMs: Long = ESPERA_MS) {
        scope.launch {
            candado.withLock {
                jobs[consultaId]?.cancel()
                jobs[consultaId] = scope.launch {
                    delay(esperaMs)
                    drenar(consultaId)
                }
            }
        }
    }

    /** Envía ya, sin esperar: al cerrar la consulta, al salir de la pantalla o al volver la conexión. */
    fun empujarYa(consultaId: Id) {
        scope.launch {
            candado.withLock { jobs[consultaId]?.cancel(); jobs.remove(consultaId) }
            drenar(consultaId)
        }
    }

    /** Reintenta todo lo que haya quedado pendiente (al recuperar la conexión o al abrir la app). */
    fun empujarTodo() {
        scope.launch {
            val ids = mutableSetOf<Id>()
            ids += hq.consultasDirty().lista { it.id }
            ids += hq.seccionValoresDirty().lista { it.consulta_id }
            ids += hq.examenesFisicosDirty().lista { it.consulta_id }
            ids += hq.archivosDirty().lista { it.consulta_id }.filterNotNull()
            ids += consultasDeAntecedentesPendientes()
            ids.forEach { drenar(it) }
            recontar()
        }
    }

    /**
     * Manda todo lo pendiente de una consulta: la crea en la API si hace falta, después las secciones,
     * el examen físico y el estado. Solo se limpia lo que el servidor confirmó.
     */
    private suspend fun drenar(consultaId: Id, intento: Int = 0) {
        val fila = hq.consultaPorId(consultaId).uno { it.toModel() } ?: return
        val backend = backends[fila.especialidad] ?: return
        try {
            val remotoId = backend.asegurarConsulta(fila)
            if (remotoId == null) {
                log.w { "la consulta $consultaId todavía no se puede enviar (falta resolver el paciente)" }
                return
            }

            val secciones = hq.seccionValoresDirty().lista { it }
                .filter { it.consulta_id == consultaId && it.deleted == 0L }
            // Los antecedentes del paciente no son una sección sino casillas de su propia tabla, pero
            // viajan como si lo fueran: una sección por categoría, con el valor "<tilde>|<detalle>".
            // Van en el mismo pedido que las secciones, con la consulta en la que se los cargó.
            val antecedentes = hq.antecedentesDirty().lista { it }
                .filter { it.paciente_id == fila.pacienteId && it.especialidad == fila.especialidad && it.deleted == 0L }
            if (secciones.isNotEmpty() || antecedentes.isNotEmpty()) {
                val cuerpo = secciones.groupBy { it.seccion }
                    .mapValues { (_, filas) -> filas.associate { it.campo to it.valor } } +
                    antecedentes.groupBy { "antecedentes_${it.categoria}" }
                        .mapValues { (_, filas) -> filas.associate { it.clave to "${it.flag}|${it.detalle}" } }
                backend.enviarSecciones(remotoId, cuerpo)
                secciones.forEach { hq.limpiarSeccionValor(it.consulta_id, it.seccion, it.campo) }
                antecedentes.forEach { hq.limpiarAntecedente(it.id) }
            }

            // Las listas: como los antecedentes, son del paciente, y se mandan con la consulta en la
            // que se las cargó. La API devuelve con qué id quedó cada una; sin guardarlo, el próximo
            // envío las duplicaría del otro lado.
            val registros = hq.registrosDirty().lista { it.toModel() }
                .filter { it.pacienteId == fila.pacienteId && it.especialidad == fila.especialidad }
            if (registros.isNotEmpty()) {
                val ids = backend.enviarRegistros(remotoId, registros)
                registros.forEach { r ->
                    ids[r.id]?.let { hq.guardarRemotoIdRegistro(it, r.id) }
                    hq.limpiarRegistro(r.id)
                }
            }

            // Los adjuntos van después de las listas: la foto de un examen complementario cuelga de
            // esa fila y necesita el id con el que quedó del otro lado. Se suben de a uno, porque la
            // foto se saca en el consultorio y, si se corta la señal, conviene reintentar esa sola.
            if (archivos != null) {
                for (a in hq.archivosPendientesDeConsulta(consultaId, fila.pacienteId).lista { it.toModel() }) {
                    if (!a.activo) {
                        backend.borrarArchivo(remotoId, a)
                    } else if (a.remotoId.isBlank()) {
                        val bytes = a.rutaLocal?.let { archivos.leer(it) }
                        if (bytes == null) {
                            // Pasa con lo que bajó otro dispositivo: la fila está, el archivo no.
                            log.w { "el adjunto ${a.nombre} no está en este dispositivo: no se sube" }
                        } else {
                            backend.subirArchivo(remotoId, a, bytes)?.let { hq.guardarRemotoIdArchivo(it, a.id) }
                        }
                    }
                    hq.limpiarArchivo(a.id)
                }
            }

            val examenSucio = hq.examenesFisicosDirty().lista { it }.firstOrNull { it.consulta_id == consultaId }
            if (examenSucio != null) {
                backend.enviarExamen(remotoId, HcPediatriaBackend.aCampos(examenSucio.toModel()))
                hq.limpiarExamenFisico(consultaId)
            }

            if (hq.consultasDirty().lista { it.id }.contains(consultaId)) {
                backend.enviarEstado(fila, remotoId)
                hq.limpiarConsulta(consultaId)
            }
            log.i { "consulta $consultaId enviada a ${fila.especialidad}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Lo pendiente sigue marcado, así que no se pierde: se reintenta con una espera mayor.
            if (intento < ESPERAS.size) {
                log.w { "no se pudo enviar la consulta $consultaId (${e.message}); reintento en ${ESPERAS[intento] / 1000}s" }
                scope.launch {
                    delay(ESPERAS[intento])
                    drenar(consultaId, intento + 1)
                }
            } else {
                log.e { "la consulta $consultaId sigue sin poder enviarse: ${e.message}" }
            }
        } finally {
            candado.withLock { jobs.remove(consultaId) }
            recontar()
        }
    }

    private suspend fun recontar() {
        val ids = mutableSetOf<Id>()
        ids += hq.consultasDirty().lista { it.id }
        ids += hq.seccionValoresDirty().lista { it.consulta_id }
        ids += hq.examenesFisicosDirty().lista { it.consulta_id }
        ids += hq.archivosDirty().lista { it.consulta_id }.filterNotNull()
        ids += consultasDeAntecedentesPendientes()
        _pendientes.value = ids.size
    }

    /**
     * Consultas por las que hay que mandar antecedentes o filas de las listas. Unos y otras son del
     * paciente, no de la consulta, así que se los manda con aquella en la que se los cargó; si no
     * quedó anotada, con la última del paciente en esa especialidad, que es lo que la web muestra.
     */
    private suspend fun consultasDeAntecedentesPendientes(): Set<Id> {
        val ids = mutableSetOf<Id>()
        val pendientes = hq.antecedentesDirty().lista { Triple(it.paciente_id, it.especialidad, it.consulta_id) }
            .filter { it.first.isNotBlank() } +
            hq.registrosDirty().lista { Triple(it.paciente_id, it.especialidad, it.consulta_id) }
        for ((pacienteId, especialidad, consultaId) in pendientes) {
            if (!backends.containsKey(especialidad)) continue
            val consulta = consultaId?.let { hq.consultaPorId(it).uno { c -> c.id } }
                ?: hq.consultasDePaciente(pacienteId, especialidad).lista { it.id }.firstOrNull()
            if (consulta != null) ids += consulta
        }
        return ids
    }

    companion object {
        /** Espera desde el último cambio hasta el envío. Suficiente para no cortar mientras se escribe. */
        const val ESPERA_MS = 4_000L

        /** Esperas de los reintentos cuando el envío falla. */
        val ESPERAS = listOf(15_000L, 60_000L, 300_000L)
    }
}
