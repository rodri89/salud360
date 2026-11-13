package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.flujoLista
import com.salud360.core.data.flujoUno
import com.salud360.core.data.hcgeneral.AvisoDeCambios
import com.salud360.core.data.hcgeneral.CambioReal
import com.salud360.core.data.hcgeneral.EstadoDeLaConsulta
import com.salud360.core.data.hcgeneral.avisoDeCambios
import com.salud360.core.data.hcgeneral.DatosConsulta
import com.salud360.core.data.hcgeneral.PREFIJO_ID_WEB
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.hcgeneral.esIdDeLaWeb
import com.salud360.core.data.hcgeneral.cambioReal
import com.salud360.core.data.hcgeneral.cambioRealDeAntecedente
import com.salud360.core.data.hcgeneral.cambioRealDeCarga
import com.salud360.core.data.hcgeneral.cambioRealDeRegistro
import com.salud360.core.data.hcgeneral.cambioRealDelExamen
import com.salud360.core.data.hcgeneral.OrigenDeConsulta
import com.salud360.core.data.hcgeneral.PARTE_BAJA
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.cambiosRealesDeSeccion
import com.salud360.core.data.hcgeneral.cierreEnviado
import com.salud360.core.data.hcgeneral.esSoloLecturaWeb
import com.salud360.core.data.hcgeneral.nombresDeMedicosWeb
import com.salud360.core.data.hcgeneral.origenDeConsulta
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.RespuestaLimitesDeAdjuntosDto
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.auth.AvisoLicencia
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.ConsultaDiagnostico
import com.salud360.core.model.hc.Diagnostico
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Interconsultor
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.MedicoPreferencia
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.hc.VacunaAplicada
import com.salud360.core.model.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** Un valor histórico de un campo de sección (para "consultas previas"). */
data class ValorHistorico(val fecha: LocalDate, val consultaId: Id, val valor: String)

/** Examen físico con la fecha de la consulta (para curvas de crecimiento y tabla de peso). */
data class ExamenFisicoFechado(val fecha: LocalDate, val examen: ExamenFisico)

/**
 * Historia clínica genérica: consultas, secciones clave-valor, examen físico, antecedentes,
 * registros repetibles, laboratorios, archivos, pendientes, diagnósticos, interconsultores y vacunas.
 * Todas las especialidades usan este mismo repositorio; lo que cambia es la definición de secciones.
 */
class HcRepository(
    private val db: Salud360Db,
    /**
     * Especialidades con API propia (hoy pediatría). El repositorio no habla con la red: solo sabe si
     * hay backend para decidir si además de guardar en el dispositivo hay que traer lo que está en el
     * servidor. Una especialidad sin backend funciona exactamente como antes, solo local.
     */
    private val backends: Map<String, HcBackend> = emptyMap(),
    /**
     * La cola de envío de la HC general (spec 005, T28), si la hay: cerrar y anular una consulta propia piden el envío ya, y reabrir y
     * anular consultan si el cierre está viajando y toman su turno. Sin ella (los tests, las demás historias) todo se guarda solo acá.
     */
    private val envio: EnvioDeConsultas? = null,
) {
    private val q get() = db.historiaClinicaQueries

    /** true si esa especialidad guarda también en su propio sistema. */
    fun tieneApi(especialidad: String): Boolean = backends.containsKey(especialidad)

    /** true si esa especialidad tiene un backend que solo lee: lo traído de ella lo manda su dueño y la app no lo envía. */
    fun esSoloLectura(especialidad: String): Boolean = backends[especialidad]?.soloLectura == true

    /**
     * true si la historia de esa especialidad se **lee de la web**: la pantalla muestra lo que trajo la web (grupos, «Actualizar»,
     * avisos) y una consulta con `remoto_id` que no es propia es de la web. Es la HC general —un backend de solo lectura o que sabe
     * traer a pedido, [Refrescable]— **escriba o no** (spec 005, T23: al pasar a `soloLectura = false` esto no puede cambiar, o las
     * consultas de la web dejarían de ser solo lectura, D1). Pediatría y las demás no se leen de la web.
     */
    fun leeDeLaWeb(especialidad: String): Boolean = backends[especialidad]?.let { it.soloLectura || it is Refrescable } == true

    /** La consulta es de la web y nunca se escribe desde acá (RF-45, D1): su id es `hcc-…` o tiene `remoto_id` sin ser propia de la app. */
    fun esConsultaDeLaWeb(consulta: Consulta): Boolean = consulta.esSoloLecturaWeb(leeDeLaWeb(consulta.especialidad))

    /** Especialidades que guardan también en su propio sistema, para traerlas sin que el médico las pida. */
    val especialidadesConApi: Set<String> get() = backends.keys

    /**
     * Licencias del que está usando la app que están por vencer, una por historia clínica.
     *
     * La fecha vive en la base de cada especialidad, que la informa al validar la sesión. Si alguna no
     * contesta se la deja afuera: es un recordatorio, no algo que deba frenar el trabajo.
     */
    suspend fun avisosDeLicencia(): List<AvisoLicencia> =
        backends.values.mapNotNull { runCatching { it.avisoDeLicencia() }.getOrNull() }

    /** Trae del servidor las consultas del paciente y las deja en el dispositivo. Null si no hay API. */
    suspend fun traerConsultasRemotas(pacienteId: Id, medicoId: Id, especialidad: String): List<Consulta>? =
        backends[especialidad]?.traerConsultas(pacienteId, medicoId)

    /** true si esa especialidad sabe traer a pedido lo de la web ([Refrescable]): solo ahí la pantalla ofrece «Actualizar». */
    fun puedeActualizar(especialidad: String): Boolean = backends[especialidad] is Refrescable

    /**
     * «Actualizar» (RF-57, RF-58): como [traerConsultasRemotas] pero saltándose el minuto de espera. Null si esa especialidad
     * no sabe hacerlo (no cambia nada para ella). Si falla lanza el error de la API, sin reintento propio.
     */
    suspend fun actualizarConsultasRemotas(pacienteId: Id, medicoId: Id, especialidad: String): List<Consulta>? =
        (backends[especialidad] as? Refrescable)?.traerConsultas(pacienteId, medicoId, forzar = true)

    /** Trae del servidor el contenido de una consulta, sin pisar lo que esté pendiente de enviar. */
    suspend fun traerConsultaRemota(consulta: Consulta) {
        val backend = backends[consulta.especialidad] ?: return
        if (consulta.remotoId.isBlank()) return
        backend.traerConsulta(consulta.id, consulta.remotoId)
    }

    /**
     * Lista los documentos de la web de una consulta que se acaba de **abrir** (spec 005, T106; RF-45), con la regla de Q-E4-3 que decide el
     * backend: una consulta nueva y en curso lista todos los documentos del paciente y una previa solo los de esa consulta. Solo la HC general
     * sabe hacerlo ([ListaDeAdjuntos]): las demás no piden nada. **Nunca** se llama desde el autoguardado, y no se reintenta sola: si falla, lo
     * ya traído se queda y el error sale tal cual (la pantalla lo absorbe para no romperse).
     */
    suspend fun traerAdjuntosRemotos(consulta: Consulta) {
        (backends[consulta.especialidad] as? ListaDeAdjuntos)?.traerAdjuntos(consulta.id)
    }

    /**
     * El tope de los adjuntos de una consulta de [especialidad] (spec 005, T107; RF-44, D18), para avisar **antes** de subir con `limiteExcedido`:
     * el máximo efectivo que informa la web. `null` si esa historia clínica no sube adjuntos ([LimitesDeAdjuntos]) o su API no está. Un solo pedido,
     * sin reintento propio: si falla, el error sale tal cual y la pantalla no frena el archivo (la cola lo compara igual al subirlo, T104).
     */
    suspend fun limiteDeAdjuntos(especialidad: String): RespuestaLimitesDeAdjuntosDto? =
        (backends[especialidad] as? LimitesDeAdjuntos)?.limiteDeAdjuntos()

    // ---- aviso al envío y estado de envío de la consulta (spec 005, T36) ----

    /**
     * El autoguardado terminó de guardar un cambio de [consulta] en el dispositivo (spec 005, T36; RF-2, RF-27, RF-62): avisa a quien corresponda
     * ([avisoDeCambios]) y devuelve a quién. En la HC general es la cola de envío, que programa el envío para 4 s después del último cambio y
     * decide qué entra (solo lo propio del médico de la sesión); una consulta de la web no avisa a nadie. **No hace ningún pedido a la web.**
     * Pediatría y las demás siguen con `HcApiSync`, que la pantalla llama aparte: acá no se hace nada por ellas.
     */
    fun avisarCambio(consulta: Consulta): AvisoDeCambios {
        val aviso = avisoDeCambios(consulta, esConsultaDeLaWeb(consulta))
        if (aviso == AvisoDeCambios.COLA_DE_LA_HC_GENERAL) envio?.avisarCambio(consulta.id)
        return aviso
    }

    /**
     * El estado de envío de una consulta propia de la HC general para su pantalla (T28, T36): lo que falta mandar y cómo está, si el cierre
     * viaja, si la web ya la tiene cerrada y si se puede reabrir o anular. Se vuelve a calcular cuando cambia algo de lo que lo decide: la
     * consulta, sus textos y su examen, los antecedentes del paciente, los estados guardados (`paciente_extra`) y los cierres en vuelo de la
     * cola. `null` sin cola o si la consulta no es propia de la HC general (el histórico no se envía y lo de la web solo se lee). Solo lee.
     */
    fun observarEstadoDeEnvio(consulta: Consulta): Flow<EstadoDeLaConsulta?> {
        val cola = envio
        if (cola == null || !consulta.esPropiaDeLaGeneral()) return flowOf(null)
        return combine(
            observarConsulta(consulta.id),
            q.valoresDeConsulta(consulta.id).flujoLista { it.dirty },
            observarExamenFisico(consulta.id),
            observarAntecedentes(consulta.pacienteId, consulta.especialidad),
            db.pacientesQueries.extrasDePaciente(consulta.pacienteId, consulta.especialidad).flujoLista { it.dirty to it.clave },
            cola.consultasEnviandoElCierre,
        ) { Unit }.conflate().map { cola.estadoDeLaConsulta(consulta.id) }.distinctUntilChanged()
    }

    /**
     * «Reintentar» (RF-6, RF-30; spec 005, T37): pide a la cola que mande ya lo pendiente de [consultaId] y vuelva a incluir lo que la web
     * rechazó, con la misma clave y el mismo cuerpo. Sin lógica propia: la cola decide si la consulta es suya (propia y del médico de la sesión).
     * Sin cola no hace nada. Devuelve el trabajo, que termina con ese envío.
     */
    fun reintentarEnvio(consultaId: Id): Job? = envio?.reintentar(consultaId)

    /**
     * «Descartar» (RF-30, D19(d)): lo que la web rechazó queda «Descartado: no se envió a la web» y se conserva en el dispositivo; la cola libera
     * lo que esperaba (el cierre). Sin [parte], todo lo rechazado. Nunca descarta una espera. Sin cola no hace nada.
     */
    fun descartarEnvio(consultaId: Id, parte: String? = null): Job? = envio?.descartar(consultaId, parte)

    // ---- consultas ----

    fun observarConsultas(pacienteId: Id, especialidad: String): Flow<List<Consulta>> =
        q.consultasDePaciente(pacienteId, especialidad).flujoLista { it.toModel() }

    fun observarConsultasTodas(pacienteId: Id): Flow<List<Consulta>> =
        q.consultasDePacienteTodas(pacienteId).flujoLista { it.toModel() }

    fun observarConsulta(id: Id): Flow<Consulta?> = q.consultaPorId(id).flujoUno { it.toModel() }
    suspend fun consulta(id: Id): Consulta? = q.consultaPorId(id).uno { it.toModel() }
    suspend fun ultimaConsulta(pacienteId: Id, especialidad: String): Consulta? = q.ultimaConsulta(pacienteId, especialidad).uno { it.toModel() }

    /**
     * Devuelve la consulta abierta del médico para ese paciente o crea una nueva (`activo = 2` en las apps originales).
     *
     * HC general (spec 005, RF-8, RF-11, D26): la consulta nueva se crea **propia** (`propia = 1`) y solo se reutiliza una
     * propia abierta del mismo paciente, médico y tipo —enviada o no, RF-62—; nunca una del histórico ni una de la web
     * (ni con backend registrado ni sin él). Las demás especialidades no llevan la marca y se comportan como siempre: con un
     * backend de solo lectura solo se retoma una abierta **del dispositivo**; una que vino de la web ([esDeLaWeb]) no es del
     * médico para editarla ni se marca para envío, así que se ignora y se crea una local (RF-46).
     */
    suspend fun abrirConsulta(pacienteId: Id, medicoId: Id, especialidad: String, tipo: String, fecha: LocalDate, edadMostrar: String): Consulta {
        val esLaGeneral = especialidad == CodigosHc.GENERAL
        val abierta = when {
            esLaGeneral -> q.consultaAbiertaPropia(pacienteId, medicoId, especialidad, tipo, PREFIJO_ID_WEB).uno { it.toModel() }
            esSoloLectura(especialidad) -> q.consultaAbiertaLocal(pacienteId, medicoId, especialidad, PREFIJO_ID_WEB).uno { it.toModel() }
            else -> q.consultaAbierta(pacienteId, medicoId, especialidad).uno { it.toModel() }
        }
        abierta?.let { if (it.tipo == tipo) return it }
        val nueva = Consulta(newId(), pacienteId, medicoId, especialidad, tipo, fecha, EstadoConsulta.ABIERTA, edadMostrar, propia = esLaGeneral)
        q.upsertConsulta(nueva.toRow(ahoraMillis()))
        return nueva
    }

    // Guardas de la lectura de la web (spec 002, E7.3, RF-45, RF-47, RF-62): lo que vino de la web (consultas y filas `hcc-…`)
    // no se escribe desde acá —ni se cierra, reabre, anula, edita o borra—, porque el cambio no llegaría a la web y quedaría
    // `dirty` para siempre. Las operaciones que escriben devuelven `true` si escribieron y `false` si no lo hicieron: porque
    // era de la web (la fila no se toca, nada queda pendiente de envío) o porque no existía. Lo del dispositivo y pediatría
    // no cambian. La pantalla ya no ofrece ninguna de estas acciones sobre lo de la web; esto es la segunda barrera.

    /** La consulta se lee porque vino de la web ([esSoloLecturaWeb]); con el backend de su especialidad si lo tiene. */
    private fun Consulta.deLaWeb(): Boolean = esConsultaDeLaWeb(this)

    /** Escribe la cabecera de la consulta. `false` si es de la web: esa la trae la lectura y no se pisa desde acá. */
    suspend fun guardarConsulta(consulta: Consulta): Boolean {
        if (consulta.deLaWeb() || cierreEnviadoOViajando(consulta.id)) return false
        q.upsertConsulta(consulta.toRow(ahoraMillis()))
        return true
    }

    /**
     * Cierra la consulta (`establecer_activo`). `false` si no existe o es de la web (una consulta de la web no se cierra, RF-45).
     *
     * Una consulta **propia** de la HC general (spec 005, RF-22, RF-24, RF-55) queda cerrada en el dispositivo con el cierre pendiente de
     * envío («Falta enviar») y se pide el envío ordenado a la cola: la creación si falta, el contenido y al final el cierre, con la fecha y
     * la edad que cargó el médico. Si hay un envío en curso, el cierre espera a que termine. `false` también si está anulada o ya tiene el
     * cierre enviado (o viajando): una cerrada que la web ya tiene cerrada no se vuelve a cerrar con otros datos. Conserva `propia`, el id
     * de la web y todo lo cargado.
     */
    suspend fun cerrarConsulta(id: Id, fecha: LocalDate? = null, edadMostrar: String? = null): Boolean {
        val c = consulta(id)?.takeUnless { it.deLaWeb() } ?: return false
        if (c.esPropiaDeLaGeneral()) {
            if (c.estado == EstadoConsulta.ANULADA) return false
            if (c.estado == EstadoConsulta.CERRADA && (envio?.enviandoElCierre(id) == true || cierreYaEnviado(c))) return false
        }
        q.upsertConsulta(c.copy(estado = EstadoConsulta.CERRADA, fecha = fecha ?: c.fecha, edadMostrar = edadMostrar ?: c.edadMostrar).toRow(ahoraMillis()))
        if (c.esPropiaDeLaGeneral()) envio?.empujarYa(id)
        return true
    }

    /**
     * `false` si no existe o es de la web (no se reabre, RF-45).
     *
     * Una consulta **propia** de la HC general se reabre solo mientras el cierre **no se haya enviado** y **no esté viajando** (RF-25, D13: la
     * web no tiene reapertura): vuelve a abierta sin tocar la web, y el cierre retirado no deja su estado («En espera», «No se pudo enviar»…)
     * para que uno nuevo no quede frenado por el viejo. Se decide con el turno de la consulta tomado, así un envío no cambia las cosas en
     * el medio. `false` si ya se envió el cierre, si está viajando o si está anulada; reabrir una abierta no cambia nada.
     */
    suspend fun reabrirConsulta(id: Id): Boolean {
        val c = consulta(id)?.takeUnless { it.deLaWeb() } ?: return false
        if (!c.esPropiaDeLaGeneral()) {
            q.upsertConsulta(c.copy(estado = EstadoConsulta.ABIERTA).toRow(ahoraMillis()))
            return true
        }
        if (c.estado != EstadoConsulta.CERRADA) return c.estado == EstadoConsulta.ABIERTA
        if (envio?.enviandoElCierre(id) == true) return false
        return enElTurno(id) {
            val fresca = consulta(id)
            when {
                fresca == null -> false
                fresca.estado == EstadoConsulta.ABIERTA -> true
                fresca.estado != EstadoConsulta.CERRADA || cierreYaEnviado(fresca) -> false
                else -> {
                    db.transaction {
                        // La web la tiene abierta (si ya está creada): la fila queda como ella, sin nada que mandar de la consulta.
                        q.upsertConsulta(fresca.copy(estado = EstadoConsulta.ABIERTA).toRow(ahoraMillis(), dirty = fresca.remotoId.isBlank()))
                        EstadosDeEnvio(db).quitar(fresca.pacienteId, fresca.id, PARTE_CIERRE)
                    }
                    true
                }
            }
        }
    }

    /**
     * `false` si no existe o es de la web (no se anula, RF-45).
     *
     * Una consulta **propia** de la HC general (RF-26, D14): si **nunca se creó** en la web, no se envía nada (queda anulada solo en el
     * dispositivo); si ya se creó, la cola **descarta lo pendiente**, deja terminar el envío que estuviera en curso y la da de baja en la web
     * (nunca la borra). Lo cargado se conserva en el dispositivo. **No** se anula una con el cierre ya enviado (queda solo para leer) ni
     * mientras el cierre viaja: `false`. Anular una cerrada cuyo cierre no se envió sí se puede (la web la sigue viendo abierta). Los datos
     * por paciente pendientes asociados a ella quedan en reserva: ni se envían ni se tiran.
     */
    suspend fun anularConsulta(id: Id): Boolean {
        val c = consulta(id)?.takeUnless { it.deLaWeb() } ?: return false
        if (!c.esPropiaDeLaGeneral()) {
            q.upsertConsulta(c.copy(estado = EstadoConsulta.ANULADA).toRow(ahoraMillis()))
            return true
        }
        if (c.estado == EstadoConsulta.ANULADA) return true
        if (envio?.enviandoElCierre(id) == true) return false
        val anular: suspend () -> Boolean = {
            val fresca = consulta(id)
            when {
                fresca == null -> false
                fresca.estado == EstadoConsulta.ANULADA -> true
                fresca.estado == EstadoConsulta.CERRADA && cierreYaEnviado(fresca) -> false
                else -> {
                    db.transaction {
                        q.upsertConsulta(fresca.copy(estado = EstadoConsulta.ANULADA).toRow(ahoraMillis()))
                        // Lo que esperaba (en espera, rechazado, descartado) ya no se va a enviar: solo importa la baja.
                        val estados = EstadosDeEnvio(db)
                        estados.deLaConsulta(fresca.pacienteId, fresca.id).keys.filter { it != PARTE_BAJA }.forEach {
                            estados.quitar(fresca.pacienteId, fresca.id, it)
                        }
                    }
                    true
                }
            }
        }
        // Una abierta se anula al instante: el envío en curso termina lo que está mandando y no sigue con el resto. Una cerrada espera su
        // turno, porque quizá el cierre justo esté saliendo y entonces ya no se puede anular.
        val anulo = if (c.estado == EstadoConsulta.CERRADA) enElTurno(id, anular) else anular()
        if (anulo) envio?.empujarYa(id)
        return anulo
    }

    /** La consulta es propia de la app y de la HC general (la que se envía con `HcClinicaEnvio`): el histórico y las demás, no. */
    private fun Consulta.esPropiaDeLaGeneral(): Boolean =
        especialidad == CodigosHc.GENERAL && origenDeConsulta() == OrigenDeConsulta.PROPIA

    /** La web ya tiene cerrada la consulta (RF-25): ver [cierreEnviado]. Lee el `dirty` de la fila y el estado de la parte «cierre». */
    private suspend fun cierreYaEnviado(c: Consulta): Boolean {
        val sucia = q.consultaPorId(c.id).uno { it.dirty != 0L } ?: return false
        val estadoDelCierre = EstadosDeEnvio(db).deLaConsulta(c.pacienteId, c.id)[PARTE_CIERRE]?.estado
        return cierreEnviado(c, sucia, estadoDelCierre)
    }

    /** Corre [bloque] con el turno de la consulta de la cola, si la hay; sin cola no hay con quién competir. */
    private suspend fun <T> enElTurno(id: Id, bloque: suspend () -> T): T = if (envio != null) envio.conTurno(id, bloque) else bloque()
    suspend fun contarConsultas(medicoId: Id, desde: LocalDate, hasta: LocalDate): Long =
        q.contarConsultasMedico(medicoId, desde.toString(), hasta.toString()).uno { it } ?: 0

    // ---- secciones clave-valor ----

    fun observarValores(consultaId: Id): Flow<Map<String, Map<String, String>>> =
        q.valoresDeConsulta(consultaId).flujoLista { it.toModel() }.map { lista ->
            lista.groupBy { it.seccion }.mapValues { (_, v) -> v.associate { it.campo to it.valor } }
        }

    fun observarSeccion(consultaId: Id, seccion: String): Flow<Map<String, String>> =
        q.valoresDeSeccion(consultaId, seccion).flujoLista { it.toModel() }.map { l -> l.associate { it.campo to it.valor } }

    suspend fun valoresDeSeccion(consultaId: Id, seccion: String): Map<String, String> =
        q.valoresDeSeccion(consultaId, seccion).lista { it.toModel() }.associate { it.campo to it.valor }

    /**
     * `false` si [consultaId] es una consulta de la web: sus secciones las trae la lectura y no se escriben desde acá.
     *
     * En la HC general solo un **cambio real** escribe (spec 005, RF-68, RF-69; [cambioReal]): el mismo valor, o un vacío sobre
     * nada, no toca la base —ni `dirty` ni `updated_at`— y devuelve `true` (el valor ya quedó como se pidió); vaciar algo
     * que tenía texto se escribe vacío. Las demás historias clínicas escriben siempre, como antes.
     */
    suspend fun guardarValor(consultaId: Id, seccion: String, campo: String, valor: String): Boolean {
        if (esIdDeLaWeb(consultaId) || cierreEnviadoOViajando(consultaId)) return false
        if (esDeLaHcGeneral(consultaId) && cambioReal(valoresDeSeccion(consultaId, seccion)[campo], valor) == CambioReal.NINGUNO) return true
        q.upsertSeccionValor(SeccionValor(consultaId, seccion, campo, valor).toRow(ahoraMillis()))
        return true
    }

    /**
     * `false` si [consultaId] es una consulta de la web (ver [guardarValor]); no escribe ningún campo. En la HC general escribe
     * solo los campos con cambio real, en una transacción; si ninguno cambió no escribe nada.
     */
    suspend fun guardarSeccion(consultaId: Id, seccion: String, valores: Map<String, String>): Boolean {
        if (esIdDeLaWeb(consultaId) || cierreEnviadoOViajando(consultaId)) return false
        val aEscribir = if (esDeLaHcGeneral(consultaId)) {
            val cambios = cambiosRealesDeSeccion(valoresDeSeccion(consultaId, seccion), valores)
            valores.filterKeys { it in cambios }
        } else {
            valores
        }
        if (aEscribir.isEmpty()) return true
        val ahora = ahoraMillis()
        db.transaction {
            aEscribir.forEach { (campo, valor) -> q.upsertSeccionValor(SeccionValor(consultaId, seccion, campo, valor).toRow(ahora)) }
        }
        return true
    }

    /**
     * La segunda barrera de escritura (spec 005, T28 decisión 4, T36; RF-25, D13): [consultaId] es una consulta propia de la HC general con el
     * cierre **ya enviado** -la web la tiene cerrada y no se reabre- o **viajando**: ninguna escritura sobre ella debe llegar al servidor ni quedar
     * pendiente. La pantalla ya la muestra solo para leer; esto lo garantiza aunque la pantalla se equivoque. Las demás consultas, una cerrada
     * con el cierre pendiente o descartado y una abierta no están bloqueadas. Una consulta que no existe tampoco.
     */
    private suspend fun cierreEnviadoOViajando(consultaId: Id): Boolean {
        val c = consulta(consultaId)?.takeIf { it.esPropiaDeLaGeneral() } ?: return false
        if (envio?.enviandoElCierre(consultaId) == true) return true
        return c.estado == EstadoConsulta.CERRADA && cierreYaEnviado(c)
    }

    /** La consulta es de la HC general (la única con marca de propia y con «cambio real» al guardar); una que no existe, no. */
    private suspend fun esDeLaHcGeneral(consultaId: Id): Boolean =
        q.consultaPorId(consultaId).uno { it.especialidad } == CodigosHc.GENERAL

    /** La consulta es propia de la app: con la marca, no borrada y no traída de la web (RF-68). */
    private suspend fun esPropia(consultaId: Id?): Boolean =
        consultaId != null && !esIdDeLaWeb(consultaId) && q.consultaPorId(consultaId).uno { it.propia != 0L } == true

    /** Historial de un campo en consultas anteriores (ej. "Consultas previas" del motivo de consulta). */
    fun observarHistorial(pacienteId: Id, especialidad: String, seccion: String, campo: String): Flow<List<ValorHistorico>> =
        q.historialCampo(pacienteId, especialidad, seccion, campo).flujoLista { ValorHistorico(LocalDate.parse(it.fecha), it.consulta_id, it.valor) }

    /** Valor de un campo en cada consulta no anulada del paciente (consulta → valor), para `historialTexto`. */
    fun observarValoresDeCampo(pacienteId: Id, especialidad: String, seccion: String, campo: String): Flow<Map<Id, String>> =
        q.valoresDeCampoDePaciente(pacienteId, especialidad, seccion, campo).flujoLista { it.consulta_id to it.valor }
            .map { it.toMap() }

    /**
     * Último valor conocido de cada campo de una sección en las demás consultas del paciente (se excluye
     * `consultaIdActual`). Sirve para lo que pertenece al paciente y se arrastra entre consultas, como los hitos
     * del desarrollo madurativo.
     */
    fun observarUltimosValores(pacienteId: Id, especialidad: String, seccion: String, consultaIdActual: Id): Flow<Map<String, String>> =
        q.ultimosValoresDeSeccion(pacienteId, especialidad, seccion, consultaIdActual).flujoLista { it.campo to it.valor }
            .map { lista -> lista.distinctBy { it.first }.toMap() }

    // ---- examen físico ----

    fun observarExamenFisico(consultaId: Id): Flow<ExamenFisico?> = q.examenFisicoDeConsulta(consultaId).flujoUno { it.toModel() }
    /**
     * `false` si el examen es de una consulta de la web: lo trae la lectura y no se escribe desde acá. En la HC general solo un
     * cambio real escribe ([cambioRealDelExamen]: un examen igual, o vacío —`0.00` incluido— sobre nada, no toca la base).
     */
    suspend fun guardarExamenFisico(examen: ExamenFisico): Boolean {
        if (esIdDeLaWeb(examen.consultaId) || cierreEnviadoOViajando(examen.consultaId)) return false
        if (esDeLaHcGeneral(examen.consultaId) &&
            !cambioRealDelExamen(q.examenFisicoDeConsulta(examen.consultaId).uno { it.toModel() }, examen)
        ) {
            return true
        }
        q.upsertExamenFisico(examen.toRow(ahoraMillis()))
        return true
    }

    fun observarExamenesFisicos(pacienteId: Id): Flow<List<ExamenFisicoFechado>> =
        q.examenesFisicosDePaciente(pacienteId).flujoLista {
            ExamenFisicoFechado(
                LocalDate.parse(it.fecha),
                ExamenFisico(
                    it.consulta_id, it.peso, it.peso_percentil, it.talla, it.talla_percentil, it.imc, it.imc_percentil,
                    it.perimetro_cefalico, it.perimetro_cefalico_percentil, it.circunferencia_abdominal, it.tension_arterial,
                    it.frecuencia_cardiaca, it.temperatura, it.saturacion, it.ipd, it.nota,
                ),
            )
        }

    // ---- antecedentes ----

    fun observarAntecedentes(pacienteId: Id, especialidad: String): Flow<List<Antecedente>> =
        q.antecedentesDePaciente(pacienteId, especialidad).flujoLista { it.toModel() }

    fun observarAntecedentes(pacienteId: Id, especialidad: String, categoria: String): Flow<List<Antecedente>> =
        q.antecedentesDeCategoria(pacienteId, especialidad, categoria).flujoLista { it.toModel() }

    /**
     * `false` si el antecedente ya existe y vino de la web (`hcc-a…`, la web es su dueña y el cambio no llegaría) o si se
     * atribuye a una consulta de la web; no escribe nada.
     *
     * Siempre asocia el antecedente a [consultaId], la consulta donde se edita: editar una casilla del histórico dentro de una
     * propia la reasigna a esa propia y la marca sucia, y viaja solo eso (spec 005, RF-68). En la HC general solo un **cambio
     * real** escribe ([cambioRealDeAntecedente]): guardar lo mismo, o una casilla vacía sobre nada, no reasigna ni marca nada y
     * devuelve `true`; desmarcar algo cargado es un vaciado a propósito y se escribe. Las demás historias clínicas escriben siempre.
     */
    suspend fun guardarAntecedente(pacienteId: Id, especialidad: String, categoria: String, clave: String, flag: Boolean, detalle: String, consultaId: Id?): Boolean {
        val existente = q.antecedentesDeCategoria(pacienteId, especialidad, categoria).lista { it.toModel() }.firstOrNull { it.clave == clave }
        if (existente?.esDeLaWeb() == true || (consultaId != null && esIdDeLaWeb(consultaId))) return false
        if (consultaId != null && cierreEnviadoOViajando(consultaId)) return false
        if (especialidad == CodigosHc.GENERAL && cambioRealDeAntecedente(existente, flag, detalle) == CambioReal.NINGUNO) return true
        val a = (existente ?: Antecedente(newId(), pacienteId, especialidad, categoria, clave)).copy(flag = flag, detalle = detalle, consultaId = consultaId)
        q.upsertAntecedente(a.toRow(ahoraMillis()))
        return true
    }

    // ---- registros repetibles ----

    fun observarRegistros(pacienteId: Id, especialidad: String, tipo: String): Flow<List<RegistroClinico>> =
        q.registrosDePaciente(pacienteId, especialidad, tipo).flujoLista { it.toModel() }

    fun observarRegistrosDeConsulta(consultaId: Id, tipo: String): Flow<List<RegistroClinico>> =
        q.registrosDeConsulta(consultaId, tipo).flujoLista { it.toModel() }

    /**
     * El vínculo con la API de la especialidad se conserva aunque el que llama arme el registro de
     * cero: la pantalla no lo conoce, y perderlo duplicaría la fila del otro lado en el próximo envío.
     */
    /**
     * Baja de la API de la especialidad el contenido de un adjunto que no está en este dispositivo,
     * porque lo subió la web o alguien desde otro teléfono. Null si no hay API o no se pudo.
     *
     * La HC general baja el documento **por su id de la web** (T106): la consulta que lo lista puede ser nueva y todavía no tener id allá. Las
     * demás historias clínicas piden el id de la consulta, como siempre.
     */
    suspend fun bajarArchivo(consulta: Consulta, archivo: Archivo): ByteArray? {
        val backend = backends[consulta.especialidad] ?: return null
        if (archivo.remotoId.isBlank()) return null
        if (consulta.remotoId.isBlank() && consulta.especialidad != CodigosHc.GENERAL) return null
        return backend.bajarArchivo(consulta.remotoId, archivo)
    }

    /**
     * Los bytes de un documento para mostrarlo (spec 005, T106; RF-45): si ya están en el dispositivo, de la copia local; si no, se bajan **una
     * sola vez** con la sesión del médico ([bajarArchivo]) y quedan cacheados en [store]. Relee la fila, así la pantalla que se quedó con el
     * adjunto de antes (sin ruta) no los vuelve a pedir. `null` si no hay nada que bajar o la web no lo tiene (un `404` de un documento dado de
     * baja o de un archivo que falta): la pantalla no se rompe.
     */
    suspend fun bytesDeArchivo(consulta: Consulta, archivo: Archivo, store: ArchivoStore): ByteArray? {
        val actual = q.archivoPorId(archivo.id).uno { it.toModel() } ?: archivo
        actual.rutaLocal?.let { store.leer(it) }?.let { return it }
        if (actual.remotoId.isBlank()) return null
        val bytes = bajarArchivo(consulta, actual) ?: return null
        guardarRutaLocal(actual, store.guardar(actual.id, actual.nombre, bytes))
        return bytes
    }

    /** Anota dónde quedó guardado en este dispositivo un adjunto que se acaba de bajar: no hay nada que enviar. */
    suspend fun guardarRutaLocal(archivo: Archivo, ruta: String) {
        q.upsertArchivo(archivo.copy(rutaLocal = ruta).toRow(ahoraMillis(), dirty = false))
    }

    /** Un registro por su id local, para conservar lo que no se está editando (la consulta de origen). */
    suspend fun registro(id: Id): RegistroClinico? = q.registroPorId(id).uno { it.toModel() }

    /**
     * `false` si el registro es de la web (`hcc-r…`) o cuelga de una consulta de la web: lo trae la lectura y no se escribe.
     *
     * [enConsulta] es la consulta donde se está editando. En la HC general, si esa consulta es propia y la fila que ya existía
     * es del histórico (apunta a una consulta que no es propia) o no tiene consulta, se reasigna a [enConsulta] y se marca sucia:
     * viaja solo esa fila (spec 005, RF-68). Una fila que ya es de otra propia se queda donde está. Solo un **cambio real** escribe
     * ([cambioRealDeRegistro]): guardar lo mismo no reasigna ni marca nada y devuelve `true`. Sin [enConsulta], y en las demás
     * historias clínicas, la fila conserva la consulta que trae y se escribe siempre, como antes.
     */
    suspend fun guardarRegistro(registro: RegistroClinico, enConsulta: Id? = null): Boolean {
        if (registro.esDeLaWeb() || registro.consultaId?.let(::esIdDeLaWeb) == true) return false
        if (enConsulta != null && cierreEnviadoOViajando(enConsulta)) return false
        val anterior = q.registroPorId(registro.id).uno { it.toModel() }
        val remoto = registro.remotoId.ifBlank { anterior?.remotoId.orEmpty() }
        var aGuardar = registro.copy(remotoId = remoto)
        if (registro.especialidad == CodigosHc.GENERAL) {
            if (!cambioRealDeRegistro(anterior, aGuardar)) return true
            if (anterior != null && enConsulta != null && !esPropia(aGuardar.consultaId) && esPropia(enConsulta)) {
                aGuardar = aGuardar.copy(consultaId = enConsulta)
            }
        }
        q.upsertRegistro(aGuardar.toRow(ahoraMillis()))
        return true
    }

    // ---- datos por paciente que pueden viajar (spec 005, RF-68) ----

    /**
     * Los antecedentes del paciente asociados a una consulta propia (activos): los únicos que pueden viajar a la web. Los de una
     * consulta del histórico, de la web o sin consulta no viajan por estar en el mismo paciente. Con [soloSucios], solo lo tocado.
     */
    suspend fun antecedentesDeConsultasPropias(pacienteId: Id, especialidad: String, soloSucios: Boolean = false): List<Antecedente> =
        q.antecedentesDeConsultasPropias(pacienteId, especialidad, PREFIJO_ID_WEB, if (soloSucios) 1L else 0L).lista { it.toModel() }

    /** Lo mismo para las filas de registro (con las dadas de baja: la baja también viaja). */
    suspend fun registrosDeConsultasPropias(pacienteId: Id, especialidad: String, soloSucios: Boolean = false): List<RegistroClinico> =
        q.registrosDeConsultasPropias(pacienteId, especialidad, PREFIJO_ID_WEB, if (soloSucios) 1L else 0L).lista { it.toModel() }

    /** `false` si no existe o es de la web (`hcc-r…`): no se quita desde acá. */
    suspend fun eliminarRegistro(id: Id): Boolean {
        if (esIdDeLaWeb(id)) return false
        val r = q.registroPorId(id).uno { it.toModel() } ?: return false
        if (r.consultaId != null && cierreEnviadoOViajando(r.consultaId!!)) return false
        q.upsertRegistro(r.copy(activo = false).toRow(ahoraMillis()))
        return true
    }

    // ---- laboratorios ----

    fun observarLaboratorios(pacienteId: Id, especialidad: String, tipo: String): Flow<List<Laboratorio>> =
        q.laboratoriosDePaciente(pacienteId, especialidad, tipo).flujoLista { it.toModel() }

    /** Una carga por su id local, para conservar lo que no se está editando (la consulta de origen), como [registro]. */
    suspend fun laboratorio(id: Id): Laboratorio? = q.laboratorioPorId(id).uno { it.toModel() }

    /**
     * `false` si la carga es de la web (`hcc-l…`) o cuelga de una consulta de la web: la web es su dueña (RF-62).
     *
     * [enConsulta] es la consulta donde se está editando. En la HC general, si esa consulta es propia y la carga que ya existía es del
     * histórico (apunta a una consulta que no es propia) o no tiene consulta, se reasigna a [enConsulta] y se marca sucia: viaja solo
     * esa carga (spec 005, RF-68). Una carga que ya es de otra propia se queda donde está. Solo un **cambio real** escribe
     * ([cambioRealDeCarga]): guardar lo mismo no reasigna ni marca nada y devuelve `true`; vaciar a propósito se escribe —la web la
     * recibe vacía (RF-69)—. Sin [enConsulta], y en las demás historias clínicas, la carga conserva la consulta que trae y se escribe
     * siempre, como antes. La segunda barrera de escritura de T36 rechaza la carga nueva y la edición si su consulta, o la consulta
     * donde se edita, ya tiene el cierre enviado o viajando. El `remoto_id` de la fila se conserva aunque el que llama arme la carga
     * de cero (la pantalla no lo conoce): perderlo daría de alta en la web una carga que ya está.
     */
    suspend fun guardarLaboratorio(lab: Laboratorio, enConsulta: Id? = null): Boolean {
        if (lab.esDeLaWeb() || lab.consultaId?.let(::esIdDeLaWeb) == true) return false
        if (lab.consultaId != null && cierreEnviadoOViajando(lab.consultaId!!)) return false
        if (enConsulta != null && cierreEnviadoOViajando(enConsulta)) return false
        val anterior = q.laboratorioPorId(lab.id).uno { it.toModel() }
        var aGuardar = lab.copy(remotoId = lab.remotoId.ifBlank { anterior?.remotoId.orEmpty() })
        if (lab.especialidad == CodigosHc.GENERAL) {
            if (!cambioRealDeCarga(anterior, aGuardar)) return true
            if (anterior != null && enConsulta != null && !esPropia(aGuardar.consultaId) && esPropia(enConsulta)) {
                aGuardar = aGuardar.copy(consultaId = enConsulta)
            }
        }
        q.upsertLaboratorio(aGuardar.toRow(ahoraMillis()))
        return true
    }

    /**
     * `false` si no existe, es de la web (`hcc-l…`) o cuelga de una consulta de la web (RF-62), o si su consulta ya tiene el cierre
     * enviado o viajando (T36). En la HC general, una carga que la web ya tiene ([Laboratorio.remotoId]) se da de baja con
     * `activo = 0` y queda sucia para que la baja viaje (RF-41); una que nunca se envió se retira del dispositivo sin dejar nada
     * pendiente de envío, porque en la web no hay nada que dar de baja. Las demás historias clínicas siguen como antes.
     */
    suspend fun eliminarLaboratorio(id: Id): Boolean {
        if (esIdDeLaWeb(id)) return false
        val l = q.laboratorioPorId(id).uno { it.toModel() } ?: return false
        if (l.consultaId?.let(::esIdDeLaWeb) == true) return false
        if (l.consultaId != null && cierreEnviadoOViajando(l.consultaId!!)) return false
        val retirada = l.copy(activo = false)
        val ahora = ahoraMillis()
        if (l.especialidad == CodigosHc.GENERAL && l.remotoId.isBlank()) {
            q.upsertLaboratorio(retirada.toRow(ahora, deleted = true, dirty = false))
        } else {
            q.upsertLaboratorio(retirada.toRow(ahora))
        }
        return true
    }

    // ---- archivos ----

    fun observarArchivos(pacienteId: Id, seccion: String): Flow<List<Archivo>> = q.archivosDePaciente(pacienteId, seccion).flujoLista { it.toModel() }
    fun observarArchivosDeConsulta(consultaId: Id, seccion: String): Flow<List<Archivo>> = q.archivosDeConsulta(consultaId, seccion).flujoLista { it.toModel() }
    fun observarArchivosDeRegistro(registroId: Id): Flow<List<Archivo>> = q.archivosDeRegistro(registroId).flujoLista { it.toModel() }
    suspend fun archivosPendientesDeSubir(): List<Archivo> = q.archivosPendientesDeSubir().lista { it.toModel() }
    suspend fun archivo(id: Id): Archivo? = q.archivoPorId(id).uno { it.toModel() }

    /**
     * `false` si el adjunto cuelga de una consulta de la web (o lleva un id de la web): esas no se escriben desde acá.
     *
     * También `false` si la consulta que lo recibe tiene el cierre **ya enviado** o **viajando** (la segunda barrera de
     * escritura de T36; RF-25, D13): una consulta cerrada en la web no admite un documento nuevo. El histórico y las demás
     * historias clínicas no están bloqueados por esto.
     */
    suspend fun guardarArchivo(archivo: Archivo): Boolean {
        if (archivo.deLaWeb()) return false
        if (archivo.consultaId != null && cierreEnviadoOViajando(archivo.consultaId!!)) return false
        q.upsertArchivo(archivo.toRow(ahoraMillis()))
        return true
    }

    /** `false` si no existe, cuelga de una consulta de la web o su consulta ya tiene el cierre enviado o viajando (T36). */
    suspend fun eliminarArchivo(id: Id): Boolean {
        val a = archivo(id)?.takeUnless { it.deLaWeb() } ?: return false
        if (a.consultaId != null && cierreEnviadoOViajando(a.consultaId!!)) return false
        q.upsertArchivo(a.copy(activo = false).toRow(ahoraMillis()))
        return true
    }

    private fun Archivo.deLaWeb(): Boolean = esIdDeLaWeb(id) || consultaId?.let(::esIdDeLaWeb) == true

    // ---- pendientes ----

    fun observarPendientes(pacienteId: Id, medicoId: Id): Flow<List<Pendiente>> = q.pendientesDePaciente(pacienteId, medicoId).flujoLista { it.toModel() }
    fun observarCantidadPendientes(medicoId: Id): Flow<Long> = q.contarPendientesDeMedico(medicoId).flujoUno { it }.map { it ?: 0L }

    /** `false` si el pendiente es de la web (`hcc-p…`, no se resuelve ni se reemplaza, RF-62) o cuelga de una consulta de la web. */
    suspend fun guardarPendiente(p: Pendiente): Boolean {
        if (p.esDeLaWeb() || p.consultaId?.let(::esIdDeLaWeb) == true) return false
        q.upsertPendiente(p.toRow(ahoraMillis()))
        return true
    }

    // ---- diagnósticos ----

    fun observarDiagnosticos(medicoId: Id): Flow<List<Diagnostico>> = q.diagnosticosDeMedico(medicoId).flujoLista { it.toModel() }
    fun observarDiagnosticosDeConsulta(consultaId: Id): Flow<List<Diagnostico>> = q.diagnosticosDeConsulta(consultaId).flujoLista { it.toModel() }
    suspend fun guardarDiagnostico(d: Diagnostico) = q.upsertDiagnostico(d.toRow(ahoraMillis()))
    /** `false` si [consultaId] es una consulta de la web: sus diagnósticos no se escriben desde acá. */
    suspend fun asignarDiagnostico(consultaId: Id, diagnosticoId: Id, asignado: Boolean): Boolean {
        if (esIdDeLaWeb(consultaId)) return false
        q.upsertConsultaDiagnostico(ConsultaDiagnostico(consultaId, diagnosticoId).toRow(ahoraMillis(), deleted = !asignado))
        return true
    }

    /** Copia los diagnósticos de la última consulta cerrada a la consulta nueva (HC hematología). */
    suspend fun copiarUltimosDiagnosticos(pacienteId: Id, especialidad: String, consultaId: Id) {
        val ultima = ultimaConsulta(pacienteId, especialidad) ?: return
        val ids = q.consultaDiagnosticosDe(ultima.id).lista { it.diagnostico_id }
        ids.forEach { asignarDiagnostico(consultaId, it, true) }
    }

    fun observarPacientesPorDiagnostico(diagnosticoId: Id) = q.pacientesPorDiagnostico(diagnosticoId).flujoLista { it.toModel() }

    // ---- interconsultores ----

    fun observarInterconsultores(medicoId: Id): Flow<List<Interconsultor>> = q.interconsultoresDeMedico(medicoId).flujoLista { it.toModel() }
    suspend fun guardarInterconsultor(i: Interconsultor) = q.upsertInterconsultor(i.toRow(ahoraMillis()))

    // ---- vacunas ----

    fun observarVacunas(pacienteId: Id): Flow<List<VacunaAplicada>> = q.vacunasDePaciente(pacienteId).flujoLista { it.toModel() }

    /** `false` si la vacuna se atribuye a una consulta de la web. */
    suspend fun guardarVacuna(v: VacunaAplicada): Boolean {
        if (v.consultaId?.let(::esIdDeLaWeb) == true) return false
        q.upsertVacuna(v.toRow(ahoraMillis()))
        return true
    }
    suspend fun vacunas(pacienteId: Id): List<VacunaAplicada> = q.vacunasDePaciente(pacienteId).lista { it.toModel() }

    // ---- datos de una consulta (HC general, bloque de solo lectura) ----

    /**
     * Lo cargado en una consulta, para saber qué secciones tienen datos en ella (RF-22). Lo del paciente (registros y
     * adjuntos sin consulta, antecedentes) no entra; los antecedentes se leen con [observarAntecedentes].
     */
    fun observarDatosConsulta(consultaId: Id): Flow<DatosConsulta> = combine(
        observarValores(consultaId),
        observarExamenFisico(consultaId),
        q.tiposRegistroDeConsulta(consultaId).flujoLista { it },
        q.tiposLaboratorioDeConsulta(consultaId).flujoLista { it },
        combine(q.seccionesArchivoDeConsulta(consultaId).flujoLista { it }, observarDiagnosticosDeConsulta(consultaId)) { a, d -> a to d.size },
    ) { valores, examen, registros, laboratorios, (archivos, diagnosticos) ->
        DatosConsulta(valores, examen, registros.toSet(), laboratorios.toSet(), archivos.toSet(), diagnosticos)
    }

    // ---- preferencias del médico ----

    fun observarPreferencias(medicoId: Id): Flow<Map<String, String>> =
        q.preferenciasDeMedico(medicoId).flujoLista { it.toModel() }.map { l -> l.associate { it.clave to it.valor } }
    suspend fun guardarPreferencia(medicoId: Id, clave: String, valor: String) =
        q.upsertPreferencia(MedicoPreferencia(medicoId, clave, valor).toRow(ahoraMillis()))

    /** Nombres de los médicos de la web que cargaron consultas (`hcc-m<id>` → nombre), para «Cargada por …» (Q4). Solo lectura. */
    fun observarNombresDeMedicosWeb(): Flow<Map<Id, String>> =
        q.nombresDeMedicosWeb().flujoLista { it.toModel() }.map { nombresDeMedicosWeb(it) }
}
