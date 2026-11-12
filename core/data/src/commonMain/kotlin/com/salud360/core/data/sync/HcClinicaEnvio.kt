package com.salud360.core.data.sync

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.EntradaDelContador
import com.salud360.core.data.hcgeneral.EntradaDelPlan
import com.salud360.core.data.hcgeneral.EstadoDeLaConsulta
import com.salud360.core.data.hcgeneral.FilaSucia
import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.OrigenDeConsulta
import com.salud360.core.data.hcgeneral.PARTE_CREAR
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PlanDeEnvio
import com.salud360.core.data.hcgeneral.PasoDeEnvio
import com.salud360.core.data.hcgeneral.ResumenDeEnvio
import com.salud360.core.data.hcgeneral.ResultadoVinculo
import com.salud360.core.data.hcgeneral.SECCIONES_HABILITADAS
import com.salud360.core.data.hcgeneral.TablaDeLaFila
import com.salud360.core.data.hcgeneral.antecedenteEnviado
import com.salud360.core.data.hcgeneral.clasificarErrorDeEnvio
import com.salud360.core.data.hcgeneral.esperaDeReintento
import com.salud360.core.data.hcgeneral.estadoDeLaConsulta
import com.salud360.core.data.hcgeneral.estadoDeVinculo
import com.salud360.core.data.hcgeneral.estadoPendienteDeLaConsulta
import com.salud360.core.data.hcgeneral.origenDeConsulta
import com.salud360.core.data.hcgeneral.planDeEnvio
import com.salud360.core.data.hcgeneral.registroEnviado
import com.salud360.core.data.hcgeneral.resumenDeEnvio
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ClinicaEscrituraApi
import com.salud360.core.data.network.hc.ConsultaAbiertaDto
import com.salud360.core.data.network.hc.PayloadsClinica
import com.salud360.core.data.repos.EnvioDeConsultas
import com.salud360.core.data.repos.EstadosDeEnvio
import com.salud360.core.data.repos.LoEnviadoGuardado
import com.salud360.core.data.repos.MarcasDeLaWeb
import com.salud360.core.data.repos.PacientesHcRepository
import com.salud360.core.data.repos.TurnosPorConsulta
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * La cola de envío de la HC general (spec 005, RF-14, RF-15, RF-27, RF-28, RF-32, RF-54 a RF-56, RF-61): decide **cuándo** se envía una
 * consulta propia, **cuántos envíos hay a la vez** y **qué sale y en qué orden**. Es el orquestador propio de la HC general;
 * `HcApiSync` (pediatría) la ignora por `envioPropio` y no se toca.
 *
 * **Cuándo**
 * - **El autoguardado solo marca** (RF-27): [marcarSucia] no hace ningún pedido ni llama a nada; programa un envío para dentro de
 *   [ESPERA_MS] y cada cambio nuevo de esa consulta **reinicia** la espera. Treinta segundos de tecleo son un solo envío, a los 4 s del
 *   último cambio (RF-28). La espera es de cada consulta: escribir en una no atrasa a otra.
 * - **A pedido** ([empujarYa]): cancela la espera de esa consulta y envía en el acto (cerrar, «Reintentar»).
 * - **Un envío por consulta a la vez** (RF-54): un [Mutex] por consulta. Si se dispara otro mientras uno viaja, **espera** a que
 *   termine y recién entonces manda lo que quedó. Consultas distintas van en paralelo. Un cambio mientras viaja un envío arma su propia
 *   espera y **no** cancela al que está en camino: la espera termina cuando empieza el envío.
 * - **Qué entra** (RF-7, D2): solo las consultas propias de la HC general. El histórico del dispositivo, lo que trajo la web, otras
 *   historias clínicas y las consultas que no existen no se programan ni se envían nunca; lo sucio de ellas queda como estaba.
 *   Abrir una consulta no dispara nada; abrir la app, iniciar sesión y volver la conexión sí, pero solo lo propio ([alAbrirOIniciar], [alRecuperarConexion]).
 *
 * **Qué sale y en qué orden** ([enviar], RF-14, RF-56): lo decide `planDeEnvio` y esta clase lo ejecuta de a un paso: **primero la
 * creación** (clave `c:<id local>`), **después el contenido** (los textos, el examen, los formularios, los antecedentes y los cinco
 * estudios) y **al final el cierre** (o la baja de una anulada), y el cierre solo sale cuando todo lo anterior llegó. El envío **se corta
 * en el primer paso que falla**: lo que ya llegó queda anotado y lo que falló, con su estado, para el próximo intento. Un error se clasifica
 * con `clasificarErrorDeEnvio` (T20) y el estado se guarda por parte (`EstadosDeEnvio`).
 *
 * **Qué se anota al confirmar un paso** (RF-32, RF-55, RF-61), todo en **una sola transacción** local, así un corte entre «el servidor
 * confirmó» y «anotado» no deja nada a medias (y el reenvío repite la misma clave sin duplicar):
 * - el id de la web (`remoto_id`) al crear, y el de cada estudio que la web confirmó, en la misma transacción que lo limpia; la fila de la consulta se
 *   limpia solo si ya no falta el cierre ni la baja;
 * - lo que la web confirmó en `guardadas` (nunca lo que no figura ahí: un envío parcial deja el resto pendiente y «No se pudo enviar»);
 * - la limpieza de las filas que viajaron, **solo las que no cambiaron mientras viajaban** (se compara `updated_at`);
 * - `LoEnviado` (lo último que la web tiene: así un texto vaciado a propósito sale vacío y un vacío no tocado no sale nunca);
 * - se quita el estado de la parte que llegó.
 * Lo que nunca va a viajar (la web no lo guarda, antecedentes desactivados, lo ya igual, D2) se limpia sin figurar como enviado.
 *
 * **El vínculo del paciente** (RF-13, RF-19): antes de crear la primera consulta se resuelve la ficha de la web con
 * `PacientesHcRepository.vinculo` (la lógica de la 002 y la 003). Si no queda vinculado la creación queda «En espera: <motivo>» y no sale
 * nada (`estadoDeVinculo`). Un paciente dado de baja en la web deja «En espera» sin resolver de nuevo: el envío nunca crea fichas por su cuenta.
 *
 * **La sesión** (RF-50): se envía **solo** lo del médico de la sesión (`medicoDeLaSesion`), mirado al programar, al empezar y antes de
 * cada paso. Con otra sesión, o sin ninguna, lo pendiente ni se envía ni se programa ni se toca, y vuelve a salir cuando vuelve su dueño.
 *
 * **Lo que no llegó** (RF-6, RF-29, RF-30):
 * - **Causa ajena al dato** (sesión vencida, HC no habilitada, licencia, paciente fuera de la cartera o de baja, sin conexión, la web
 *   caída, «demasiados pedidos»…): lo que faltaba de la consulta queda «En espera: <motivo>» —nombrando la HC—, sin perder ni modificar
 *   nada, y **se reintenta solo**: a los 15 s, al minuto y a los 5 minutos y después cada 5 minutos para las pasajeras, cada 5 minutos
 *   para las de cuenta o de alguien ([esperaDeReintento]). Un reintento es la misma espera que arma el autoguardado: **una por consulta**;
 *   un cambio del médico la adelanta y no la multiplica. «Demasiados pedidos» frena a todas las consultas hasta que se cumple su espera.
 *   Solo corre mientras la app esté abierta (en el [scope] inyectado). Repite la misma clave, así que no duplica (RF-15).
 * - **Rechazo por el dato** (`422`, `404 consulta_no_encontrada`, `409`, `403 consulta_no_propia`): «No se pudo enviar: <motivo>». No se
 *   reintenta solo y no frena a las demás consultas. El médico puede [reintentar] (vuelve a mandar lo rechazado) o [descartar] (queda
 *   «Descartado: no se envió a la web», el dato se conserva en el dispositivo y el cierre que esperaba sale).
 *
 * **Los disparadores** (T31, RF-57, RF-29): abrir la app, iniciar sesión y recuperar la conexión ([alAbrirOIniciar], [alRecuperarConexion],
 * [observar]) retoman lo propio del médico de la sesión que quedó pendiente o «En espera» con la misma espera de [ESPERA_MS] de siempre, una por
 * consulta, sin atrasar una espera más cercana ni correr en paralelo con un envío de esa consulta. La cadena de reintentos vive en memoria: al
 * cerrar y abrir la app no sigue sola, y la retoma [alAbrirOIniciar]. El histórico del dispositivo no se envía solo (RF-7, D2).
 * * El reloj es inyectable ([esperar]) y la corrutina también ([scope]): los tests mueven un reloj falso y no esperan tiempo real.
 * Todavía no se conecta a la pantalla: el autoguardado de la consulta no lo llama hasta T36.
 */
open class HcClinicaEnvio(
    private val db: Salud360Db,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Cómo se espera: por omisión, de verdad; los tests pasan un reloj falso. */
    private val esperar: suspend (Long) -> Unit = { delay(it) },
    /** El momento en que se arma un plan (el plan solo lo repite, RF-55) y el día de un estado. */
    private val ahora: () -> Long = ::ahoraMillis,
    /** Hasta qué subentrega trae el envío esta versión; decide qué viaja y qué cuenta como pendiente. */
    private val habilitadas: Set<String> = SECCIONES_HABILITADAS,
    /** La API de escritura de la HC general; sin ella (la HC general no tiene URL en este entorno) no se envía nada. */
    private val escritura: ClinicaEscrituraApi? = null,
    /** De dónde sale la ficha de la web del paciente (`vinculo`). Sin él no se puede crear ninguna consulta. */
    private val pacientes: PacientesHcRepository? = null,
    /** El id estable de este dispositivo, que va en cada pedido de escritura. Sin él no se envía nada. */
    private val dispositivo: String = "",
    /** El nombre visible de la HC, que nombran los motivos de los estados. */
    private val nombreHc: String = CodigosHc.GENERAL,
    /**
     * El médico de la sesión actual (`null` si no hay sesión de médico). La cola envía **solo** lo de ese médico (RF-50): sin sesión, o con
     * la de otro, no programa ni manda ni toca nada. Por omisión no hay sesión: ante la duda no se manda con el token de nadie.
     */
    private val medicoDeLaSesion: () -> Id? = { null },
    /** El turno de cada consulta, compartido con la lectura de las propias (HcClinicaBackend) y HcRepository: nunca dos a la vez sobre la misma. */
    private val turnos: TurnosPorConsulta = TurnosPorConsulta(),
) : EnvioDeConsultas {
    private val log = Logger.withTag("HcClinicaEnvio")
    private val hq get() = db.historiaClinicaQueries
    private val estados = EstadosDeEnvio(db)
    private val loEnviados = LoEnviadoGuardado(db)
    private val marcas = MarcasDeLaWeb(db)

    /** Protege [esperas] e [intentos]; no se sostiene mientras se espera ni mientras se envía. */
    private val candado = Mutex()

    /** La espera programada de cada consulta (solo la que todavía no empezó a enviar). */
    private val esperas = mutableMapOf<Id, Job>()

    /** Cuándo vence la espera de [esperas] (en la hora de [ahora]); solo vale mientras esa espera siga activa. Lo escribe [programar], el único que arma esperas. */
    private val vencimientos = mutableMapOf<Id, Long>()

    /**
     * Cuántas veces seguidas falló cada consulta por una causa ajena al dato: de ahí sale la espera del próximo reintento solo
     * ([esperaDeReintento]). Se reinicia con un envío sin fallos o cuando el médico toca «Reintentar». Vive en memoria: es la cadena de
     * reintentos de esta apertura de la app (RF-29: solo mientras la app esté abierta).
     */
    private val intentos = mutableMapOf<Id, Int>()

    /** Las consultas a las que el médico tocó «Reintentar»: su próximo envío vuelve a incluir lo que la web rechazó (RF-30). */
    private val aMano = mutableSetOf<Id>()

    /**
     * Hasta cuándo (en el reloj de [ahora]) la web pidió esperar con «demasiados pedidos». Mientras tanto no sale **ningún** pedido
     * automático, de ninguna consulta: el límite es por IP y lo comparte todo el consultorio (RF-29, sin ráfagas).
     */
    private var frenadoHasta = 0L

    private val _enviandoElCierre = MutableStateFlow<Set<Id>>(emptySet())

    /**
     * Las consultas cuyo cierre está viajando ahora mismo (RF-55): desde que sale el pedido hasta que queda anotado lo que contestó la web.
     * Mientras figuran acá la pantalla dice «Enviando el cierre.» y no se puede reabrir ni anular.
     */
    override val consultasEnviandoElCierre: StateFlow<Set<Id>> = _enviandoElCierre.asStateFlow()

    override fun enviandoElCierre(consultaId: Id): Boolean = consultaId in _enviandoElCierre.value

    private val _consultasCreadas = MutableSharedFlow<ConsultaCreadaEnLaWeb>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Cada vez que una consulta queda creada en la web (RF-59): con las consultas que el paciente ya tenía abiertas allá, para que la
     * pantalla avise quién las abrió. Solo informa: la consulta se creó igual. Quien no está mirando no recibe nada (no se guarda).
     */
    val consultasCreadas: SharedFlow<ConsultaCreadaEnLaWeb> = _consultasCreadas.asSharedFlow()

    /**
     * Avisa que una consulta propia cambió. **No envía ni pide nada**: programa el envío para dentro de [esperaMs] y reinicia la
     * espera si ya había una (RF-27, RF-28). Es lo único que llama el autoguardado, que dispara cada 600 ms. Con una consulta que no
     * es propia (histórico, de la web, de otra historia, inexistente) no programa nada.
     */
    fun marcarSucia(consultaId: Id, esperaMs: Long = ESPERA_MS) {
        scope.launch {
            if (!puedeEnviar(consultaId)) return@launch
            programar(consultaId, esperaMs, reemplazar = true)
        }
    }

    /**
     * Deja programado un envío de [consultaId] para dentro de [esperaMs]: **una sola espera por consulta**, la de los 4 s del autoguardado
     * o la de un reintento. Con [reemplazar] (un cambio del médico) cancela la que hubiera y arma ésta, así un cambio **adelanta** el
     * reintento y no lo multiplica; sin él (un reintento por un fallo) deja la que hubiera, que ya es igual o más cercana. Pasada la espera
     * ya no se la puede cancelar: un cambio nuevo arma otra y su envío hace cola detrás de éste. Si la web pidió esperar ([frenadoHasta]),
     * el envío espera a que se cumpla, también el de los cambios del médico.
     */
    private suspend fun programar(consultaId: Id, esperaMs: Long, reemplazar: Boolean, soloSiAdelanta: Boolean = false) {
        candado.withLock {
            val actual = esperas[consultaId]
            if (!reemplazar && actual != null && actual.isActive) return@withLock
            // Un disparador de RF-57 solo adelanta: si la espera que hay vence antes de [esperaMs], se queda como está.
            if (soloSiAdelanta && actual != null && actual.isActive && (vencimientos[consultaId] ?: Long.MAX_VALUE) <= ahora() + esperaMs) return@withLock
            actual?.cancel()
            vencimientos[consultaId] = ahora() + esperaMs
            esperas[consultaId] = scope.launch {
                val yo = currentCoroutineContext()[Job]
                if (esperaMs > 0) esperar(esperaMs)
                val resto = candado.withLock { frenadoHasta } - ahora()
                if (resto > 0) esperar(resto)
                val lePertenece = candado.withLock { (esperas[consultaId] === yo).also { if (it) esperas.remove(consultaId) } }
                if (lePertenece) enviarDeAUno(consultaId)
            }
        }
    }

    /** El autoguardado guardó un cambio ([EnvioDeConsultas.avisarCambio]): lo mismo que [marcarSucia] con la espera de siempre. */
    override fun avisarCambio(consultaId: Id) = marcarSucia(consultaId)

    /**
     * Programa el próximo intento de una consulta que quedó «En espera» ([estado]): a los 15 s, al minuto y a los 5 minutos para una causa
     * pasajera y después cada 5 minutos; cada 5 minutos para una de cuenta o de alguien ([esperaDeReintento]). Es la cadena de reintentos
     * de RF-29: **solo mientras la app esté abierta** (corre en el [scope] de la cola) y nunca más rápido. «Demasiados pedidos» frena además
     * a todas las demás consultas hasta que se cumpla su espera.
     */
    private suspend fun programarReintento(consultaId: Id, estado: EstadoEnvio.EnEspera) {
        val intento = candado.withLock { (intentos[consultaId] ?: 0).also { intentos[consultaId] = it + 1 } }
        val espera = esperaDeReintento(estado, intento)
        if (estado.causa == CausaEnEspera.DEMASIADOS_PEDIDOS) candado.withLock { frenadoHasta = maxOf(frenadoHasta, ahora() + espera) }
        programar(consultaId, espera, reemplazar = false)
    }

    /**
     * Envía ya una consulta propia, sin esperar los 4 s: cancela la espera programada y entra en el turno de la consulta (si hay un
     * envío en curso, espera a que termine, RF-54). Devuelve el trabajo, que termina cuando termina ese envío.
     */
    override fun empujarYa(consultaId: Id): Job = scope.launch {
        if (!puedeEnviar(consultaId)) return@launch
        candado.withLock { esperas.remove(consultaId)?.cancel() }
        enviarDeAUno(consultaId)
    }

    /**
     * «Reintentar» a mano (RF-30): envía ya la consulta y esta vez **vuelve a incluir lo que la web rechazó** («No se pudo enviar»), con
     * la misma clave y el mismo cuerpo. Si la web lo vuelve a rechazar, queda igual; no arma reintentos solos. Reinicia la cuenta de los
     * reintentos de las esperas. Solo para una consulta propia del médico de la sesión.
     */
    override fun reintentar(consultaId: Id): Job = scope.launch {
        if (!puedeEnviar(consultaId)) return@launch
        candado.withLock {
            aMano += consultaId
            intentos.remove(consultaId)
            esperas.remove(consultaId)?.cancel()
        }
        enviarDeAUno(consultaId)
    }

    /**
     * «Descartar» (RF-30, D19(d)): el médico decide no enviar lo que la web **rechazó**. La parte queda «Descartado: no se envió a la
     * web» y el dato **se conserva en el dispositivo** (solo deja de estar pendiente); la web no se toca ni se anota nada de lo que
     * «tiene». Con eso lo que esperaba (el cierre) se libera y sale. Sin [parte] descarta todo lo rechazado de la consulta. Solo descarta
     * «No se pudo enviar», nunca una espera (se perdería lo que falta mandar), y nunca la creación (sin ella no hay consulta). Lo que el
     * médico edite después vuelve a viajar. Devuelve el trabajo, que termina cuando termina el descarte y el envío que lo sigue.
     */
    override fun descartar(consultaId: Id, parte: String?): Job = scope.launch {
        if (!puedeEnviar(consultaId)) return@launch
        val descarto = conTurno(consultaId) { descartarRechazadas(consultaId, parte) }
        if (!descarto) return@launch
        candado.withLock { esperas.remove(consultaId)?.cancel() }
        enviarDeAUno(consultaId)
    }

    /**
     * Se abrió la app con una sesión, o el médico inició sesión (RF-57): retoma lo **propio** del médico de la sesión que quedó pendiente o
     * «En espera». La cadena de reintentos vive en memoria y no sobrevive a cerrar la app (RF-29, T27): esto la retoma. Ver [reanudar].
     */
    fun alAbrirOIniciar(): Job = reanudar()

    /**
     * Volvió la conexión (RF-57), según el [ObservadorDeConexion] de la plataforma (ver [observar]): retoma lo pendiente o «En espera» del
     * médico de la sesión sin esperar el reintento largo que tuviera programado. Ver [reanudar].
     */
    fun alRecuperarConexion(): Job = reanudar()

    /** Conecta [observador] con [alRecuperarConexion]. Registrarse **no** dispara nada, aunque haya red y pendientes: abrir la app es otro disparador. */
    fun observar(observador: ObservadorDeConexion) {
        observador.iniciar { alRecuperarConexion() }
    }

    /** Desconecta a [observador] (cierre de sesión). */
    fun dejarDeObservar(observador: ObservadorDeConexion) {
        observador.detener()
    }

    /**
     * Lo que hacen los tres disparadores de RF-57 (abrir la app, iniciar sesión, recuperar la conexión): para cada consulta **propia** del
     * médico de la sesión con algo para mandar ahora (un cambio sin enviar o una parte «En espera»; no lo «No se pudo enviar», que espera al
     * médico, ni lo que no viaja) arma **la misma espera de [ESPERA_MS]** que el autoguardado y los reintentos: una sola por consulta, que
     * **adelanta** a una más larga (el reintento de 5 minutos) pero nunca atrasa a una más cercana, así varios eventos seguidos (la conexión que
     * va y viene) no hacen ráfaga ni postergan el envío. Pasada la espera el envío pide el turno de la consulta, como siempre: si hay otro
     * en curso espera a que termine (RF-54) y, si ya no queda nada, no pide nada. No envía el histórico (RF-7, D2), ni lo de la web, ni lo de
     * otro médico (RF-50). Sin sesión de médico, sin API de escritura o sin id de dispositivo no hace nada.
     */
    private fun reanudar(): Job = scope.launch {
        for (id in consultasParaReanudar()) programar(id, ESPERA_MS, reemplazar = true, soloSiAdelanta = true)
    }

    private suspend fun consultasParaReanudar(): List<Id> {
        if (escritura == null || dispositivo.isBlank() || medicoDeLaSesion() == null) return emptyList()
        val sucio = cargarSucio()
        return consultasConAlgoSucio(sucio).sorted().filter { id ->
            val consulta = hq.consultaPorId(id).uno { it.toModel() } ?: return@filter false
            puedeEnviar(consulta) &&
                planDeEnvio(entradaDelPlan(consulta, sucio, loEnviados.deLaConsulta(consulta.pacienteId, consulta.id), estadosDe(consulta))).pasos.isNotEmpty()
        }
    }
    /** Corre [bloque] con el turno de la consulta: nunca a la vez que un envío de la misma (RF-54). */
    override suspend fun <T> conTurno(consultaId: Id, bloque: suspend () -> T): T {
        return turnos.conTurno(consultaId, bloque)
    }

    private suspend fun enviarDeAUno(consultaId: Id) {
        conTurno(consultaId) {
            try {
                enviar(consultaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Suelta el turno y no rompe la cola. Un error del servidor ya quedó guardado como estado de la parte; esto es un fallo local
                // (por ejemplo, no se pudo anotar lo que el servidor confirmó): lo pendiente sigue pendiente y el reenvío repite la clave.
                // No se rinde: se vuelve a intentar con la misma espera que una causa pasajera.
                log.w { "no se pudo terminar el envío de la consulta $consultaId: ${e.message}" }
                programarReintento(consultaId, EstadoEnvio.EnEspera(CausaEnEspera.SERVIDOR, e.message.orEmpty()))
            }
        }
    }

    /** El descarte en sí, con el turno tomado. true si descartó algo. */
    private suspend fun descartarRechazadas(consultaId: Id, parte: String?): Boolean {
        val consulta = hq.consultaPorId(consultaId).uno { it.toModel() } ?: return false
        if (!puedeEnviar(consulta)) return false
        val guardados = estados.deLaConsulta(consulta.pacienteId, consulta.id).mapValues { it.value.estado }
        val rechazadas = guardados.filter { (p, estado) ->
            estado is EstadoEnvio.NoSePudoEnviar && p != PARTE_CREAR && (parte == null || p == parte)
        }.keys
        if (rechazadas.isEmpty()) return false
        // El plan con las partes rechazadas incluidas dice qué filas cubre cada una: son las que dejan de estar pendientes.
        val plan = planDeEnvio(
            entradaDelPlan(consulta, cargarSucio(), loEnviados.deLaConsulta(consulta.pacienteId, consulta.id), guardados, reintentoManual = true),
        )
        db.transaction {
            for (p in rechazadas) {
                plan.pasos.firstOrNull { it.parte == p }?.cubre?.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                estados.guardar(consulta.pacienteId, consulta.id, p, EstadoEnvio.Descartado, hoy())
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Qué dispara: nada más que los métodos de arriba. Qué manda: esto.
    // ------------------------------------------------------------------

    /**
     * El envío de una consulta propia (RF-14, RF-56): arma el plan con lo sucio, lo último que la web confirmó y los estados, limpia lo
     * que nunca viaja y ejecuta los pasos en orden hasta el primero que no llega. Se llama con el turno de la consulta tomado (nunca
     * dos a la vez para la misma) y siempre para una consulta propia de la HC general. Los tests de la cola lo sobreescriben.
     */
    protected open suspend fun enviar(consultaId: Id) {
        // El «Reintentar» del médico vale para este envío y nada más: se consume ya, aunque después no haya nada que mandar.
        val aMano = candado.withLock { aMano.remove(consultaId) }
        val api = escritura ?: return
        if (dispositivo.isBlank()) {
            log.w { "no hay id de dispositivo: no se envía la consulta $consultaId" }
            return
        }
        val consulta = hq.consultaPorId(consultaId).uno { it.toModel() } ?: return
        if (!puedeEnviar(consulta)) return

        val sucio = cargarSucio()
        val lo = loEnviados.deLaConsulta(consulta.pacienteId, consulta.id)
        val plan = planDeEnvio(entradaDelPlan(consulta, sucio, lo, estadosDe(consulta), reintentoManual = aMano))
        val envio = EnvioEnCurso(consulta = consulta, api = api, plan = plan, webId = consulta.remotoId.toLongOrNull(), lo = lo)

        limpiarLoQueNoViaja(consulta, plan)

        val confirmadas = mutableSetOf<String>()
        while (true) {
            val paso = plan.siguiente(confirmadas) ?: break
            // La sesión se mira antes de cada paso (RF-50): si entró otro médico mientras tanto, lo que sigue no sale con su token (crearía la
            // consulta a nombre del otro, RF-14). Lo ya anotado queda; no se marca nada: lo del primero no se modifica.
            if (!esDeLaSesion(consulta)) break
            // Si el médico anuló la consulta mientras viajaba un paso, lo que seguía en este plan ya no sale (RF-26): lo pendiente se descarta y
            // el próximo envío manda la baja.
            if (consulta.estado != EstadoConsulta.ANULADA && estaAnulada(consulta.id)) break
            // El envío se corta en el primer paso que no llega: nada de lo que sigue (y nunca el cierre) sale detrás.
            if (!envio.ejecutar(paso)) break
            confirmadas += paso.parte
        }

        // Un envío sin fallos por una causa ajena termina la cadena de reintentos: la próxima falla vuelve a empezar a los 15 s. Y si no queda
        // nada por mandar, nada está «En espera»: se limpian los estados viejos de partes que ya no esperan nada.
        if (!envio.huboEspera) {
            candado.withLock { intentos.remove(consultaId) }
            if (esDeLaSesion(consulta) && plan.siguiente(confirmadas) == null) {
                estadosDe(consulta).filterValues { it is EstadoEnvio.EnEspera }.keys.forEach { estados.quitar(consulta.pacienteId, consulta.id, it) }
            }
        }
    }

    private suspend fun estaAnulada(consultaId: Id): Boolean = hq.consultaPorId(consultaId).uno { it.estado } == EstadoConsulta.ANULADA.name

    /** El envío de una consulta mientras corre: lo que se sabe de ella y lo que fue pasando. */
    private inner class EnvioEnCurso(
        val consulta: Consulta,
        val api: ClinicaEscrituraApi,
        /** El plan de este envío: de ahí salen los pasos que faltan si hay que dejarlos «En espera». */
        val plan: PlanDeEnvio,
        /** El id de la web: el de antes, o el que contesta la creación de este envío. */
        var webId: Long?,
        /** Lo último que la web tiene confirmado, al día con cada paso. */
        var lo: LoEnviado,
    ) {
        /** Algún paso quedó «En espera» en este envío (y, por lo tanto, ya hay un reintento programado). */
        var huboEspera = false

        /** Ejecuta [paso]; true si la web lo confirmó **entero** y quedó anotado, false si hay que cortar acá. */
        suspend fun ejecutar(paso: PasoDeEnvio): Boolean = when (paso) {
            is PasoDeEnvio.Crear -> crear(paso)
            is PasoDeEnvio.Secciones -> secciones(paso)
            is PasoDeEnvio.Examen -> examen(paso)
            is PasoDeEnvio.Formularios -> formularios(paso)
            is PasoDeEnvio.Antecedentes -> antecedentes(paso)
            is PasoDeEnvio.Registros -> registros(paso)
            is PasoDeEnvio.Cerrar -> cerrar(paso)
            is PasoDeEnvio.Baja -> baja(paso)
            // Un paso que esta versión no conoce no se manda: el envío se corta y no se anota nada. Con los pasos de hoy (crear, textos, examen,
            // formularios, antecedentes, estudios, cierre y baja) no hay ninguno; queda por si una versión futura agrega uno sin enseñarle a la cola.
            else -> error("el paso ${paso.parte} de la consulta ${consulta.id} todavía no se sabe enviar")
        }

        private suspend fun crear(paso: PasoDeEnvio.Crear): Boolean {
            val ficha = fichaDelPaciente(paso) ?: return false
            val respuesta = intentar(paso) { api.crear(paso.clave, dispositivo, ficha, paso.tipo, paso.fecha, paso.edadMostrar) } ?: return false
            val idDeLaWeb = respuesta.consulta.id
            db.transaction {
                hq.guardarRemotoId(idDeLaWeb.toString(), consulta.id)
                // La fila sigue sucia si ya falta el cierre (o la baja): se limpia al confirmar lo último, no antes (T21, decisión b).
                if (consulta.estado == EstadoConsulta.ABIERTA) {
                    paso.cubre.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                }
                estados.quitar(consulta.pacienteId, consulta.id, PARTE_CREAR)
            }
            webId = idDeLaWeb
            _consultasCreadas.tryEmit(ConsultaCreadaEnLaWeb(consulta.id, idDeLaWeb, respuesta.otrasAbiertas))
            return true
        }

        private suspend fun secciones(paso: PasoDeEnvio.Secciones): Boolean {
            val id = webId ?: return false
            val respuesta = intentar(paso) { api.secciones(id, paso.textos) } ?: return false
            val llegaron = paso.textos.keys.filter { it in respuesta.guardadas }.toSet()
            val faltan = paso.textos.keys - llegaron
            val nuevo = lo.conLoConfirmado(PasoDeEnvio.Secciones(paso.textos.filterKeys { it in llegaron }, emptyList()))
            db.transaction {
                if (llegaron.isNotEmpty()) loEnviados.guardar(consulta.pacienteId, consulta.id, nuevo)
                // Solo se limpia lo que llegó y no cambió mientras viajaba (RF-55). Una clave es `<sección>/<campo>`.
                paso.cubre.filter { it.clave.substringBefore('/') in llegaron }.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                anotarEstado(paso.parte, faltan)
            }
            lo = nuevo
            return faltan.isEmpty()
        }

        private suspend fun examen(paso: PasoDeEnvio.Examen): Boolean {
            val id = webId ?: return false
            val respuesta = intentar(paso) { api.examen(id, paso.campos) } ?: return false
            val llegaron = paso.campos.keys.filter { it in respuesta.guardadas }.toSet()
            val faltan = paso.campos.keys - llegaron
            val nuevo = lo.conLoConfirmado(PasoDeEnvio.Examen(paso.campos.filterKeys { it in llegaron }, emptyList()))
            db.transaction {
                if (llegaron.isNotEmpty()) loEnviados.guardar(consulta.pacienteId, consulta.id, nuevo)
                // El examen es una sola fila: si faltó algún campo queda pendiente entera y el envío siguiente compara contra lo confirmado.
                if (faltan.isEmpty()) paso.cubre.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                anotarEstado(paso.parte, faltan)
            }
            lo = nuevo
            return faltan.isEmpty()
        }

        /**
         * `PUT consultas/{id}/formularios` (T63-a): un solo pedido con los campos que cambiaron de verdad (lo arma el plan, T62-a). La web confirma en `guardadas`
         * (`<sección>.<clave>`): solo eso se anota como lo que la web tiene (`LoEnviado`) y se limpia, y solo si la fila no cambió mientras viajaba (RF-55). Lo que no
         * figura queda sucio y la parte «No se pudo enviar» (como los textos, T25 (b)). Una respuesta perdida no anota nada: el reenvío repite el mismo pedido.
         */
        private suspend fun formularios(paso: PasoDeEnvio.Formularios): Boolean {
            val id = webId ?: return false
            val valores = paso.valores.groupBy({ it.seccion }, { it.campo to it.valor }).mapValues { (_, campos) -> campos.toMap() }
            val respuesta = intentar(paso) { api.formularios(id, valores) } ?: return false
            val guardadas = respuesta.guardadas.toSet()
            val llegaron = paso.valores.filter { "${it.seccion}.${it.campo}" in guardadas }
            val faltan = paso.valores.filter { it !in llegaron }.map { "${it.seccion}.${it.campo}" }.toSet()
            val claves = llegaron.map { "${it.seccion}/${it.campo}" }.toSet()
            val nuevo = lo.conLoConfirmado(PasoDeEnvio.Formularios(llegaron, emptyList()))
            db.transaction {
                if (llegaron.isNotEmpty()) loEnviados.guardar(consulta.pacienteId, consulta.id, nuevo)
                // Una clave es `<sección>/<campo>`, la misma de la fila de `seccion_valor`.
                paso.cubre.filter { it.clave in claves }.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                anotarEstado(paso.parte, faltan)
            }
            lo = nuevo
            return faltan.isEmpty()
        }

        /**
         * `PUT pacientes/{ficha}/antecedentes-familiares` (T63-a): un solo pedido a la ficha de la web del paciente, con la consulta de la web y las casillas (y la
         * nota) que cambiaron de verdad contra lo último confirmado. La web confirma las claves en `guardadas`; igual que los formularios, solo eso se anota y se
         * limpia (por id de fila y si no cambió en vuelo) y lo demás queda sucio y «No se pudo enviar». Sin la ficha vinculada no sale (queda «En espera»).
         */
        private suspend fun antecedentes(paso: PasoDeEnvio.Antecedentes): Boolean {
            val id = webId ?: return false
            val ficha = fichaDelPaciente(paso) ?: return false
            val enviados = paso.items.mapNotNull { a -> lo.antecedenteEnviado(a)?.let { a.clave to it } }.toMap()
            val respuesta = intentar(paso) { api.antecedentes(ficha, id, paso.items, enviados) } ?: return false
            val guardadas = respuesta.guardadas.toSet()
            val llegaron = paso.items.filter { it.clave in guardadas }
            val faltan = paso.items.filter { it !in llegaron }.map { it.clave }.toSet()
            val ids = llegaron.map { it.id }.toSet()
            val nuevo = lo.conLoConfirmado(PasoDeEnvio.Antecedentes(llegaron, emptyList()))
            db.transaction {
                if (llegaron.isNotEmpty()) loEnviados.guardar(consulta.pacienteId, consulta.id, nuevo)
                paso.cubre.filter { it.clave in ids }.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                anotarEstado(paso.parte, faltan)
            }
            lo = nuevo
            return faltan.isEmpty()
        }

        /**
         * `PUT consultas/{id}/registros` (T63-b): un solo pedido con los **cinco estudios** que arman el plan (T62-b: solo los que cambian de verdad, con su
         * fecha y su `detalle`; un vaciado va `detalle ""`) y lo último confirmado (`LoEnviado.registroEnviado`) como «lo ya enviado». La web contesta el id
         * de cada fila en `ids` (`ref` = `r:<id local>`) y los `ref` escritos en `guardadas`: solo esos se limpian y reciben su `remoto_id` — los dos anotados
         * en **una sola transacción** con `LoEnviado` y el estado (RF-61), y solo si la fila no cambió mientras viajaba (RF-55). Lo que no figura queda sucio
         * y la parte «No se pudo enviar» (como los textos, T25 (b)); `desconocidas` tampoco se limpia. Una respuesta perdida no anota nada: el reenvío repite
         * los mismos `ref` y la web no duplica la fila.
         */
        private suspend fun registros(paso: PasoDeEnvio.Registros): Boolean {
            val id = webId ?: return false
            val enviados = paso.items.mapNotNull { r -> lo.registroEnviado(r)?.let { r.id to it } }.toMap()
            val respuesta = intentar(paso) { api.registros(id, paso.items, enviados) } ?: return false
            val idPorRef = respuesta.idsPorRef()
            val guardadas = respuesta.guardadas.toSet()
            // Un ref confirmado sin id no sirve: sin saber el id de la web la fila no se puede anotar y el reenvío duplicaría. Se deja pendiente.
            val confirmadas = guardadas.filter { it in idPorRef }.toSet()
            val refDe = paso.items.associate { it.id to PayloadsClinica.claveDeRegistro(it.id) }
            val llegaron = paso.items.filter { refDe.getValue(it.id) in confirmadas }
            val faltan = paso.items.filter { it !in llegaron }.map { refDe.getValue(it.id) }.toSet()
            val idsLocales = llegaron.map { it.id }.toSet()
            val remotos = llegaron.associate { it.id to idPorRef.getValue(refDe.getValue(it.id)).toString() }
            val nuevo = lo.conLoConfirmado(PasoDeEnvio.Registros(llegaron, emptyList()), remotos = remotos)
            db.transaction {
                // El id de la web y la limpieza de la misma fila ("nunca una fila sin id y limpia", RF-61).
                remotos.forEach { (local, remoto) -> hq.guardarRemotoIdRegistro(remoto, local) }
                if (llegaron.isNotEmpty()) loEnviados.guardar(consulta.pacienteId, consulta.id, nuevo)
                paso.cubre.filter { it.clave in idsLocales }.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                anotarEstado(paso.parte, faltan)
            }
            lo = nuevo
            return faltan.isEmpty()
        }

        private suspend fun cerrar(paso: PasoDeEnvio.Cerrar): Boolean {
            val id = webId ?: return false
            // «Enviando el cierre.» (RF-55): desde que sale el pedido hasta que queda anotado, así no se reabre ni se anula en el medio.
            _enviandoElCierre.update { it + consulta.id }
            try {
                val respuesta = intentar(paso) { api.cerrar(id, paso.clave, dispositivo, paso.fecha, paso.edadMostrar) } ?: return false
                db.transaction {
                    if (respuesta.cerrada) paso.cubre.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                    anotarEstado(paso.parte, if (respuesta.cerrada) emptySet() else setOf("el cierre"))
                }
                return respuesta.cerrada
            } finally {
                _enviandoElCierre.update { it - consulta.id }
            }
        }

        private suspend fun baja(paso: PasoDeEnvio.Baja): Boolean {
            val id = webId ?: return false
            val respuesta = intentar(paso) { api.baja(id, paso.clave, dispositivo) } ?: return false
            db.transaction {
                if (respuesta.baja) paso.cubre.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
                anotarEstado(paso.parte, if (respuesta.baja) emptySet() else setOf("la baja"))
            }
            return respuesta.baja
        }

        /**
         * La ficha de la web del paciente, o `null` si no quedó vinculado (RF-13). Se resuelve acá, antes de crear la primera consulta, con
         * `PacientesHcRepository.vinculo` (la lógica de la 002 y la 003): si ya se conoce, no pregunta nada (RF-35); si no, la web la busca
         * y el vínculo queda guardado sin pendiente de envío. Si no queda vinculado (hay que elegir ficha, el documento no sirve, la ficha es
         * de otro paciente, o no se pudo preguntar) la creación queda «En espera: <motivo>» y no sale nada. Un paciente dado de baja no pasa
         * por acá: su ficha ya se conoce, y la web contesta `404 paciente_no_encontrado` al crear (RF-19), sin resolver de nuevo.
         */
        private suspend fun fichaDelPaciente(paso: PasoDeEnvio): Long? {
            val repositorio = pacientes ?: return null
            val resultado = try {
                repositorio.vinculo(consulta.pacienteId, CodigosHc.GENERAL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ResultadoVinculo.NoSePudo(e as? TobbException ?: TobbException(0, "respuesta", e.message?.trim()?.ifEmpty { null } ?: "error"))
            }
            val estado = estadoDeVinculo(resultado, nombreHc) ?: return (resultado as ResultadoVinculo.Vinculado).fichaId
            fallo(paso, estado)
            log.w { "la consulta ${consulta.id} no se envía: el paciente no quedó vinculado ($resultado)" }
            return null
        }

        /**
         * Hace la llamada a la web. Si falla, clasifica el error (T20), guarda el estado de la parte y devuelve `null`: quien llama corta
         * el envío. Solo envuelve la llamada: un fallo al anotar lo que la web confirmó es local y no se toma por un error de la web.
         */
        private suspend fun <T> intentar(paso: PasoDeEnvio, llamada: suspend () -> T): T? = try {
            llamada()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val clasificacion = clasificarErrorDeEnvio(e, nombreHc, yaEnviada = webId != null)
            fallo(paso, clasificacion.estado)
            // La web dio de baja la consulta que ya estaba enviada (RF-33): «Ya no figura en la web», sin tocar ningún dato local.
            if (clasificacion.marcaYaNoFigura) webId?.let { marcas.marcarConsulta(consulta.pacienteId, it, hoy(), ahora()) }
            log.w { "la web no aceptó ${paso.parte} de la consulta ${consulta.id}: ${e.message}" }
            null
        }

        /**
         * Guarda el [estado] de la parte que no salió. Una causa ajena al dato («En espera», RF-6) vale para **todo lo que faltaba de esta
         * consulta**: lo que seguía en el plan queda igual «En espera» con el mismo motivo —no se pierde ni se modifica nada— y se envía todo
         * junto cuando pasa la causa. Deja programado el próximo intento ([programarReintento]). Un rechazo (NoSePudoEnviar) marca solo
         * la parte rechazada: no se reintenta solo y no frena lo demás.
         */
        private suspend fun fallo(paso: PasoDeEnvio, estado: EstadoEnvio) {
            estados.guardar(consulta.pacienteId, consulta.id, paso.parte, estado, hoy())
            if (estado is EstadoEnvio.EnEspera) {
                // Una causa ajena al dato deja en espera todo lo que faltaba de la consulta (RF-6), no solo la parte que se intentó.
                plan.pasos.dropWhile { it !== paso }.drop(1).forEach {
                    estados.guardar(consulta.pacienteId, consulta.id, it.parte, estado, hoy())
                }
                huboEspera = true
                programarReintento(consulta.id, estado)
            }
        }

        /** Llegó todo ([faltan] vacío): se quita el estado de la parte. Si no, la web no guardó [faltan] y la parte queda «No se pudo enviar». */
        private suspend fun anotarEstado(parte: String, faltan: Set<String>) {
            if (faltan.isEmpty()) {
                estados.quitar(consulta.pacienteId, consulta.id, parte)
            } else {
                val motivo = "Historia clínica de $nombreHc: la web no guardó ${faltan.sorted().joinToString(", ")}."
                estados.guardar(consulta.pacienteId, consulta.id, parte, EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.OTRO, motivo), hoy())
            }
        }
    }

    /**
     * Limpia, sin figurar como enviado ni como pendiente, lo que nunca va a viajar (RF-60): lo que la web no guarda, los antecedentes
     * desactivados (RF-9, D2), lo que ya está igual en la web y lo de una consulta anulada. También la fila de una anulada que nunca
     * llegó a crearse: no hay nada que enviarle a la web (RF-26). Todo en una transacción y solo si la fila no cambió desde el plan.
     */
    private suspend fun limpiarLoQueNoViaja(consulta: Consulta, plan: PlanDeEnvio) {
        val anuladaSinCrear = consulta.estado == EstadoConsulta.ANULADA && consulta.remotoId.isBlank() && plan.pasos.isEmpty()
        if (plan.sinViaje.isEmpty() && !anuladaSinCrear) return
        db.transaction {
            plan.sinViaje.forEach { limpiarSiNoCambio(consulta.id, it.tabla, it.clave, it.actualizadaEn) }
            if (anuladaSinCrear) limpiarSiNoCambio(consulta.id, TablaDeLaFila.CONSULTA, consulta.id, consulta.updatedAt?.toEpochMilliseconds() ?: 0L)
        }
    }

    /** Deja la fila `dirty = 0` solo si su `updated_at` sigue siendo el de cuando se armó el envío: lo que cambió en vuelo sigue pendiente (RF-55). */
    private suspend fun limpiarSiNoCambio(consultaId: Id, tabla: TablaDeLaFila, clave: String, actualizadaEn: Long) {
        when (tabla) {
            TablaDeLaFila.CONSULTA ->
                if (hq.consultaPorId(clave).uno { it.updated_at } == actualizadaEn) hq.limpiarConsulta(clave)
            TablaDeLaFila.SECCION_VALOR -> {
                val seccion = clave.substringBefore('/')
                val campo = clave.substringAfter('/')
                val fila = hq.seccionValoresDirty().lista { it }.firstOrNull { it.consulta_id == consultaId && it.seccion == seccion && it.campo == campo }
                if (fila != null && fila.updated_at == actualizadaEn) hq.limpiarSeccionValor(consultaId, seccion, campo)
            }
            TablaDeLaFila.EXAMEN_FISICO -> {
                val fila = hq.examenesFisicosDirty().lista { it }.firstOrNull { it.consulta_id == consultaId }
                if (fila != null && fila.updated_at == actualizadaEn) hq.limpiarExamenFisico(consultaId)
            }
            TablaDeLaFila.ANTECEDENTE -> {
                val fila = hq.antecedentesDirty().lista { it }.firstOrNull { it.id == clave }
                if (fila != null && fila.updated_at == actualizadaEn) hq.limpiarAntecedente(clave)
            }
            TablaDeLaFila.REGISTRO -> {
                val fila = hq.registrosDirty().lista { it }.firstOrNull { it.id == clave }
                if (fila != null && fila.updated_at == actualizadaEn) hq.limpiarRegistro(clave)
            }
            TablaDeLaFila.LABORATORIO -> {
                val fila = hq.laboratoriosDirty().lista { it }.firstOrNull { it.id == clave }
                if (fila != null && fila.updated_at == actualizadaEn) hq.limpiarLaboratorio(clave)
            }
        }
    }

    private fun hoy(): LocalDate = Instant.fromEpochMilliseconds(ahora()).toLocalDateTime(TimeZone.currentSystemDefault()).date

    // ------------------------------------------------------------------
    // Qué hay sin enviar
    // ------------------------------------------------------------------

    /** Todo lo `dirty` de la HC general, de una vez: las filas con su `updated_at` (para comparar después, RF-55). */
    private class Sucio(
        val consultas: Set<Id>,
        val valores: List<FilaSucia<SeccionValor>>,
        val examenes: List<FilaSucia<ExamenFisico>>,
        val antecedentes: List<FilaSucia<Antecedente>>,
        val registros: List<FilaSucia<RegistroClinico>>,
        val laboratorios: List<Laboratorio>,
        val pendientes: List<Pendiente>,
    )

    /** Los ids de las consultas que tienen alguna fila sucia (la propia, sus textos, su examen, sus antecedentes, registros, laboratorios o pendientes). */
    private fun consultasConAlgoSucio(sucio: Sucio): Set<Id> = mutableSetOf<Id>().apply {
        addAll(sucio.consultas)
        sucio.valores.mapTo(this) { it.fila.consultaId }
        sucio.examenes.mapTo(this) { it.fila.consultaId }
        sucio.antecedentes.mapNotNullTo(this) { it.fila.consultaId }
        sucio.registros.mapNotNullTo(this) { it.fila.consultaId }
        sucio.laboratorios.mapNotNullTo(this) { it.consultaId }
        sucio.pendientes.mapNotNullTo(this) { it.consultaId }
    }
    private suspend fun cargarSucio() = Sucio(
        consultas = hq.consultasDirty().lista { it.id }.toSet(),
        valores = hq.seccionValoresDirty().lista { FilaSucia(it.toModel(), true, it.updated_at) },
        examenes = hq.examenesFisicosDirty().lista { FilaSucia(it.toModel(), true, it.updated_at) },
        antecedentes = hq.antecedentesDirty().lista { FilaSucia(it.toModel(), true, it.updated_at) },
        registros = hq.registrosDirty().lista { FilaSucia(it.toModel(), true, it.updated_at) },
        laboratorios = hq.laboratoriosDirty().lista { it.toModel() },
        pendientes = hq.pendientesDirty().lista { it.toModel() },
    )

    private fun entradaDelPlan(
        consulta: Consulta,
        sucio: Sucio,
        lo: LoEnviado,
        estados: Map<String, EstadoEnvio>,
        reintentoManual: Boolean = false,
    ) = EntradaDelPlan(
        consulta = consulta,
        consultaSucia = consulta.id in sucio.consultas,
        valores = sucio.valores.filter { it.fila.consultaId == consulta.id },
        examen = sucio.examenes.firstOrNull { it.fila.consultaId == consulta.id },
        antecedentes = sucio.antecedentes.filter { it.fila.consultaId == consulta.id },
        registros = sucio.registros.filter { it.fila.consultaId == consulta.id },
        loEnviado = lo,
        estados = estados,
        habilitadas = habilitadas,
        reintentoManual = reintentoManual,
        armadoEn = ahora(),
    )

    private suspend fun estadosDe(consulta: Consulta): Map<String, EstadoEnvio> =
        estados.deLaConsulta(consulta.pacienteId, consulta.id).mapValues { it.value.estado }

    private fun esPropia(consulta: Consulta): Boolean =
        consulta.especialidad == CodigosHc.GENERAL && consulta.origenDeConsulta() == OrigenDeConsulta.PROPIA

    /** La consulta es del médico de la sesión (RF-50). Sin sesión de médico, de nadie: no se manda con el token de otro. */
    private fun esDeLaSesion(consulta: Consulta): Boolean = medicoDeLaSesion()?.let { it == consulta.medicoId } == true

    /** La cola puede ocuparse de [consulta]: es propia de la app (RF-7, RF-8) y del médico de la sesión (RF-50). */
    private fun puedeEnviar(consulta: Consulta): Boolean = esPropia(consulta) && esDeLaSesion(consulta)

    /** true si la consulta existe y la cola puede ocuparse de ella ([puedeEnviar]). */
    private suspend fun puedeEnviar(consultaId: Id): Boolean = hq.consultaPorId(consultaId).uno { it.toModel() }?.let { puedeEnviar(it) } ?: false

    /** Todo lo que el contador y la tarjeta necesitan de una consulta propia: su plan, sus estados y lo que no pasa por el plan. */
    private suspend fun entradaDelContador(consulta: Consulta, sucio: Sucio): EntradaDelContador {
        val estados = estadosDe(consulta)
        return EntradaDelContador(
            pacienteId = consulta.pacienteId,
            plan = planDeEnvio(entradaDelPlan(consulta, sucio, loEnviados.deLaConsulta(consulta.pacienteId, consulta.id), estados)),
            estados = estados,
            laboratorios = sucio.laboratorios.filter { it.consultaId == consulta.id },
            pendientes = sucio.pendientes.filter { it.consultaId == consulta.id },
            habilitadas = habilitadas,
        )
    }

    /**
     * El estado de envío de una consulta propia de la HC general, para su tarjeta y su banner (T28; RF-24, RF-25, RF-26, RF-55): lo que
     * falta mandar y cómo está («Falta enviar», «En espera: <motivo>», «No se pudo enviar: <motivo>»), si el cierre viaja ahora
     * («Enviando el cierre.»), si la web ya la tiene cerrada, y si se puede reabrir o anular. `null` si no existe o no es propia (el
     * histórico y lo de la web no se envían). Solo lee. Las reglas son las de [com.salud360.core.data.hcgeneral.estadoDeLaConsulta], las mismas
     * que aplica `HcRepository`.
     */
    override suspend fun estadoDeLaConsulta(consultaId: Id): EstadoDeLaConsulta? {
        val consulta = hq.consultaPorId(consultaId).uno { it.toModel() } ?: return null
        if (!esPropia(consulta)) return null
        val sucia = hq.consultaPorId(consultaId).uno { it.dirty != 0L } ?: false
        val entrada = entradaDelContador(consulta, cargarSucio())
        return estadoDeLaConsulta(
            consulta = consulta,
            filaSucia = sucia,
            estadoDelCierre = entrada.estados[PARTE_CIERRE],
            envio = estadoPendienteDeLaConsulta(entrada),
            enviandoElCierre = enviandoElCierre(consultaId),
            partesDescartadas = entrada.estados.filterValues { it == EstadoEnvio.Descartado }.keys,
        )
    }
    /**
     * Cuántas consultas propias de la HC general tienen cambios sin enviar, con cuáles están «En espera» o «No se pudo enviar»
     * (RF-31). Cuenta todo tipo de dato que esta versión envía y no cuenta el histórico ni lo que nunca va a viajar (RF-60). Usa lo
     * último que la web confirmó, así un texto que el médico vació a propósito después de enviarlo se cuenta (RF-69). Solo lee: no
     * escribe nada ni programa nada.
     */
    suspend fun contarPendientes(): ResumenDeEnvio {
        val sucio = cargarSucio()
        val entradas = consultasConAlgoSucio(sucio).sorted().mapNotNull { id ->
            val consulta = hq.consultaPorId(id).uno { it.toModel() } ?: return@mapNotNull null
            if (!puedeEnviar(consulta)) return@mapNotNull null // lo de otro médico no se cuenta ni se muestra (RF-50)
            entradaDelContador(consulta, sucio)
        }
        return resumenDeEnvio(entradas)
    }

    companion object {
        /** La espera desde el último cambio hasta el envío: la misma del envío diferido que ya usa pediatría (RF-28). */
        const val ESPERA_MS = HcApiSync.ESPERA_MS
    }
}

/**
 * Una consulta propia quedó creada en la web (RF-59). [otrasAbiertas] son las consultas que el paciente ya tenía abiertas allá, de
 * cualquier médico (con el nombre de quien la abrió, si la web lo pudo resolver). Solo informa: la consulta se creó igual.
 */
data class ConsultaCreadaEnLaWeb(val consultaId: Id, val consultaWebId: Long, val otrasAbiertas: List<ConsultaAbiertaDto>)
