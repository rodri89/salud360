package com.salud360.core.data.sync

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.EntradaDelContador
import com.salud360.core.data.hcgeneral.EntradaDelPlan
import com.salud360.core.data.hcgeneral.FilaSucia
import com.salud360.core.data.hcgeneral.OrigenDeConsulta
import com.salud360.core.data.hcgeneral.ResumenDeEnvio
import com.salud360.core.data.hcgeneral.SUBENTREGA_HABILITADA
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.hcgeneral.origenDeConsulta
import com.salud360.core.data.hcgeneral.planDeEnvio
import com.salud360.core.data.hcgeneral.resumenDeEnvio
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.repos.EstadosDeEnvio
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La cola de envío de la HC general (spec 005, RF-27, RF-28, RF-54): decide **cuándo** se envía una consulta propia y **cuántos envíos
 * hay a la vez**. Es el orquestador propio de la HC general; `HcApiSync` (pediatría) la ignora por `envioPropio` y no se toca.
 *
 * - **El autoguardado solo marca** (RF-27): [marcarSucia] no hace ningún pedido ni llama a nada; programa un envío para dentro de
 *   [ESPERA_MS] y cada cambio nuevo de esa consulta **reinicia** la espera. Treinta segundos de tecleo son un solo envío, a los 4 s del
 *   último cambio (RF-28). La espera es de cada consulta: escribir en una no atrasa a otra.
 * - **A pedido** ([empujarYa]): cancela la espera de esa consulta y envía en el acto (cerrar, «Reintentar»).
 * - **Un envío por consulta a la vez** (RF-54): un [Mutex] por consulta. Si se dispara otro mientras uno viaja, **espera** a que
 *   termine y recién entonces manda lo que quedó. Consultas distintas van en paralelo. Un cambio mientras viaja un envío arma su propia
 *   espera y **no** cancela al que está en camino: la espera termina cuando empieza el envío.
 * - **Qué entra** (RF-7, D2): solo las consultas propias de la HC general. El histórico del dispositivo, lo que trajo la web, otras
 *   historias clínicas y las consultas que no existen no se programan ni se envían nunca; lo sucio de ellas queda como estaba.
 *   Abrir una consulta, iniciar sesión o volver la conexión **no** son disparadores de esta clase (los disparadores de RF-57 son T31).
 * - **Qué manda**: nada todavía. El cuerpo del envío es [enviar], un gancho que T25 llena con el envío ordenado (crear → contenido →
 *   cierre). Lo que falle ahí se registra y suelta el turno; los reintentos y los estados persistentes son T27.
 *
 * El reloj es inyectable ([esperar]) y la corrutina también ([scope]): los tests mueven un reloj falso y no esperan tiempo real.
 * Todavía no se conecta a la pantalla: el autoguardado de la consulta no lo llama hasta T36.
 */
open class HcClinicaEnvio(
    private val db: Salud360Db,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Cómo se espera: por omisión, de verdad; los tests pasan un reloj falso. */
    private val esperar: suspend (Long) -> Unit = { delay(it) },
    /** El momento en que se arma un plan (el plan solo lo repite, RF-55). */
    private val ahora: () -> Long = ::ahoraMillis,
    /** Hasta qué subentrega trae el envío esta versión; decide qué cuenta como pendiente. */
    private val habilitada: SubentregaDeEscritura = SUBENTREGA_HABILITADA,
) {
    private val log = Logger.withTag("HcClinicaEnvio")
    private val hq get() = db.historiaClinicaQueries

    /** Protege [esperas] y [turnos]; no se sostiene mientras se espera ni mientras se envía. */
    private val candado = Mutex()

    /** La espera programada de cada consulta (solo la que todavía no empezó a enviar). */
    private val esperas = mutableMapOf<Id, Job>()

    /** El turno de cada consulta: quien lo tiene está enviándola. */
    private val turnos = mutableMapOf<Id, Mutex>()

    /**
     * Avisa que una consulta propia cambió. **No envía ni pide nada**: programa el envío para dentro de [esperaMs] y reinicia la
     * espera si ya había una (RF-27, RF-28). Es lo único que llama el autoguardado, que dispara cada 600 ms. Con una consulta que no
     * es propia (histórico, de la web, de otra historia, inexistente) no programa nada.
     */
    fun marcarSucia(consultaId: Id, esperaMs: Long = ESPERA_MS) {
        scope.launch {
            if (!esPropia(consultaId)) return@launch
            candado.withLock {
                esperas.remove(consultaId)?.cancel()
                esperas[consultaId] = scope.launch {
                    val yo = currentCoroutineContext()[Job]
                    if (esperaMs > 0) esperar(esperaMs)
                    // Pasada la espera ya no se la puede cancelar: un cambio nuevo arma otra espera y su envío hace cola detrás de éste.
                    val lePertenece = candado.withLock { (esperas[consultaId] === yo).also { if (it) esperas.remove(consultaId) } }
                    if (lePertenece) enviarDeAUno(consultaId)
                }
            }
        }
    }

    /**
     * Envía ya una consulta propia, sin esperar los 4 s: cancela la espera programada y entra en el turno de la consulta (si hay un
     * envío en curso, espera a que termine, RF-54). Devuelve el trabajo, que termina cuando termina ese envío.
     */
    fun empujarYa(consultaId: Id): Job = scope.launch {
        if (!esPropia(consultaId)) return@launch
        candado.withLock { esperas.remove(consultaId)?.cancel() }
        enviarDeAUno(consultaId)
    }

    /**
     * El cuerpo del envío de una consulta propia. **Gancho de T25**: hoy no manda nada. Se llama con el turno de la consulta tomado
     * (nunca dos a la vez para la misma) y siempre para una consulta propia de la HC general.
     */
    protected open suspend fun enviar(consultaId: Id) = Unit

    private suspend fun enviarDeAUno(consultaId: Id) {
        val turno = candado.withLock { turnos.getOrPut(consultaId) { Mutex() } }
        turno.withLock {
            try {
                enviar(consultaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Suelta el turno y no rompe la cola. Qué pasa con lo pendiente (reintento, «En espera») es de T27.
                log.w { "no se pudo enviar la consulta $consultaId: ${e.message}" }
            }
        }
    }

    /** true si la consulta existe, es de la HC general y es propia de la app (RF-7, RF-8). */
    private suspend fun esPropia(consultaId: Id): Boolean {
        val consulta = hq.consultaPorId(consultaId).uno { it.toModel() } ?: return false
        return consulta.especialidad == CodigosHc.GENERAL && consulta.origenDeConsulta() == OrigenDeConsulta.PROPIA
    }

    /**
     * Cuántas consultas propias de la HC general tienen cambios sin enviar, con cuáles están «En espera» o «No se pudo enviar»
     * (RF-31). Cuenta todo tipo de dato que esta versión envía y no cuenta el histórico ni lo que nunca va a viajar (RF-60). Solo lee:
     * no escribe nada ni programa nada.
     *
     * Hasta T25, que registra lo que la web confirmó, el plan no sabe qué texto ya está en la web: un texto que el médico vació a
     * propósito después de enviarlo no se cuenta (se lo toma por un vacío que no tocó).
     */
    suspend fun contarPendientes(): ResumenDeEnvio {
        val consultasSucias = hq.consultasDirty().lista { it.id }.toSet()
        val valores = hq.seccionValoresDirty().lista { it }
        val examenes = hq.examenesFisicosDirty().lista { it }
        val antecedentes = hq.antecedentesDirty().lista { it }
        val registros = hq.registrosDirty().lista { it }
        val laboratorios = hq.laboratoriosDirty().lista { it }
        val pendientes = hq.pendientesDirty().lista { it }

        val candidatas = mutableSetOf<Id>().apply {
            addAll(consultasSucias)
            valores.mapTo(this) { it.consulta_id }
            examenes.mapTo(this) { it.consulta_id }
            antecedentes.mapNotNullTo(this) { it.consulta_id }
            registros.mapNotNullTo(this) { it.consulta_id }
            laboratorios.mapNotNullTo(this) { it.consulta_id }
            pendientes.mapNotNullTo(this) { it.consulta_id }
        }
        val guardados = EstadosDeEnvio(db)

        val entradas = candidatas.sorted().mapNotNull { id ->
            val consulta = hq.consultaPorId(id).uno { it.toModel() } ?: return@mapNotNull null
            if (consulta.especialidad != CodigosHc.GENERAL || consulta.origenDeConsulta() != OrigenDeConsulta.PROPIA) return@mapNotNull null
            val estados = guardados.deLaConsulta(consulta.pacienteId, id).mapValues { it.value.estado }
            EntradaDelContador(
                pacienteId = consulta.pacienteId,
                plan = planDeEnvio(
                    EntradaDelPlan(
                        consulta = consulta,
                        consultaSucia = id in consultasSucias,
                        valores = valores.filter { it.consulta_id == id }.map { FilaSucia(it.toModel(), true, it.updated_at) },
                        examen = examenes.firstOrNull { it.consulta_id == id }?.let { FilaSucia(it.toModel(), true, it.updated_at) },
                        antecedentes = antecedentes.filter { it.consulta_id == id }.map { FilaSucia(it.toModel(), true, it.updated_at) },
                        registros = registros.filter { it.consulta_id == id }.map { FilaSucia(it.toModel(), true, it.updated_at) },
                        estados = estados,
                        habilitada = habilitada,
                        armadoEn = ahora(),
                    ),
                ),
                estados = estados,
                laboratorios = laboratorios.filter { it.consulta_id == id }.map { it.toModel() },
                pendientes = pendientes.filter { it.consulta_id == id }.map { it.toModel() },
                habilitada = habilitada,
            )
        }
        return resumenDeEnvio(entradas)
    }

    companion object {
        /** La espera desde el último cambio hasta el envío: la misma del envío diferido que ya usa pediatría (RF-28). */
        const val ESPERA_MS = HcApiSync.ESPERA_MS
    }
}
