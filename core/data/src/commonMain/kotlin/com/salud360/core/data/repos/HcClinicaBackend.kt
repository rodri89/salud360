package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.CATEGORIA_FAMILIARES
import com.salud360.core.data.hcgeneral.CLAVE_OMITIDAS
import com.salud360.core.data.hcgeneral.CLAVE_OMITIDAS_LABORATORIO
import com.salud360.core.data.hcgeneral.LecturaWeb
import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.PREFIJO_ID_WEB
import com.salud360.core.data.hcgeneral.antecedentesDeLaWeb
import com.salud360.core.data.hcgeneral.camposConfirmadosDelEstudio
import com.salud360.core.data.hcgeneral.cargaDeLaWebParaLaPropia
import com.salud360.core.data.hcgeneral.claveNoLeidas
import com.salud360.core.data.hcgeneral.consultaDeLaWeb
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.hcgeneral.filaDeLaWeb
import com.salud360.core.data.hcgeneral.fusionarAntecedente
import com.salud360.core.data.hcgeneral.fusionarConsulta
import com.salud360.core.data.hcgeneral.fusionarConsultaPropia
import com.salud360.core.data.hcgeneral.fusionarExamenDeLaPropia
import com.salud360.core.data.hcgeneral.fusionarExamenFisico
import com.salud360.core.data.hcgeneral.fusionarLaboratorio
import com.salud360.core.data.hcgeneral.fusionarRegistro
import com.salud360.core.data.hcgeneral.hayAlgo
import com.salud360.core.data.hcgeneral.idConsultaWeb
import com.salud360.core.data.hcgeneral.loEnviadoTrasLaLectura
import com.salud360.core.data.hcgeneral.laboratoriosDeLaWeb
import com.salud360.core.data.hcgeneral.motivosDeLaWeb
import com.salud360.core.data.hcgeneral.noLeidasComoTexto
import com.salud360.core.data.hcgeneral.nombreMedicoDeLaWeb
import com.salud360.core.data.hcgeneral.omitidasComoTexto
import com.salud360.core.data.hcgeneral.omitidasLaboratorioComoTexto
import com.salud360.core.data.hcgeneral.ordenarMasRecientesPrimero
import com.salud360.core.data.hcgeneral.pendienteDeLaWeb
import com.salud360.core.data.hcgeneral.registrosDeLaWeb
import com.salud360.core.data.hcgeneral.valoresDeLaWeb
import com.salud360.core.data.hcgeneral.valoresDeLaWebParaLaPropia
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ClinicaEscrituraApi
import com.salud360.core.data.network.hc.ClinicaEscrituraApiRemota
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.ConsultasClinicaApi
import com.salud360.core.data.network.hc.ConsultasClinicaApiRemota
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcApiSoloLectura
import com.salud360.core.data.network.hc.RespuestaAntecedentesDto
import com.salud360.core.data.network.hc.RespuestaBajaDeCargaDto
import com.salud360.core.data.network.hc.RespuestaBajaDto
import com.salud360.core.data.network.hc.RespuestaCargaDto
import com.salud360.core.data.network.hc.RespuestaCerrarDto
import com.salud360.core.data.network.hc.RespuestaCrearConsultaDto
import com.salud360.core.data.network.hc.RespuestaEscrituraDto
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.data.network.hc.RespuestaRegistrosDto
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Historia clínica general (`hclinica`), **lectura** (spec 002, entrega L; desde la spec 005, T23, también recibe la API de escritura, que todavía no usa): trae de la web las consultas del paciente,
 * su historial y el contenido de cada consulta, y lo deja en el dispositivo; su lectura no manda nada, nunca (RF-47).
 *
 * - Su lectura no escribe: lee por una [ConsultasClinicaApi] (que solo lee; la remota se arma con un [HcApiSoloLectura]), recibe aparte la [escritura] y todo método de
 *   envío de [HcBackend] lanza [UnsupportedOperationException] sin tocar la red.
 * - Sin ficha de la web (`fichaConocida` nula) no pide nada y no llama a `pacientes/resolver` (RF-49).
 * - Pedidos (RF-57): abrir la historia de un paciente son la lista (50 por pedido) y un pedido de historial; abrir una
 *   consulta es un pedido. La lista y el historial son pedidos, transacciones y esperas de 60 s independientes.
 * - Lo del dispositivo manda (RF-48, RF-54): una consulta `dirty`, creada acá o ya vinculada con la misma consulta de la
 *   web no se toca, ni una clave sucia de lo que se escribe campo por campo. Lo que la web trae se escribe con `dirty = 0`,
 *   y un valor vacío o que no viene no pisa el que había.
 * - Nada se borra ni se vacía (Q15): lo que la web dejó de mandar queda como estaba, solo se lo marca «Ya no figura en la
 *   web» (RF-63, [MarcasDeLaWeb] y [marcasAusentes]) en la misma transacción que escribe la respuesta. Solo marca una
 *   respuesta completa y correcta de la ficha del paciente; un error, una lista a medias o una de otra ficha no marcan.
 */
class HcClinicaBackend internal constructor(
    private val db: Salud360Db,
    private val api: ConsultasClinicaApi,
    private val pacientes: PacientesHcRepository,
    private val ahora: () -> Long,
    private val maxPaginas: Int,
    /**
     * La API de escritura (spec 005, T19). Este backend la **recibe** pero todavía no la usa: el motor de envío es `HcClinicaEnvio`
     * (T24 y T25). La lectura sigue siendo solo `GET` por [api], que solo sabe leer.
     */
    val escritura: ClinicaEscrituraApi,
    /** El turno por consulta que comparte con la cola de envío: la lectura de una propia lo toma solo para aplicar lo que trajo. */
    private val turnos: TurnosPorConsulta = TurnosPorConsulta(),
) : HcBackend, Refrescable {
    /** Con el cliente completo: la lectura por su envoltorio de solo lectura y la escritura por [ClinicaEscrituraApiRemota]. */
    constructor(
        db: Salud360Db,
        cliente: HcApiClient,
        pacientes: PacientesHcRepository,
        ahora: () -> Long = ::ahoraMillis,
        turnos: TurnosPorConsulta = TurnosPorConsulta(),
    ) : this(db, ConsultasClinicaApiRemota(HcApiSoloLectura(cliente)), pacientes, ahora, MAX_PAGINAS, ClinicaEscrituraApiRemota(cliente), turnos)

    /** Para probar la lectura sin API de escritura: cualquier uso de [escritura] falla (la lectura nunca la usa). */
    internal constructor(db: Salud360Db, api: ConsultasClinicaApi, pacientes: PacientesHcRepository, ahora: () -> Long, maxPaginas: Int) :
        this(db, api, pacientes, ahora, maxPaginas, SinEscritura)

    override val especialidad: String = CodigosHc.GENERAL

    /** Spec 005 (T23, RF-1): ya no es de solo lectura. Lo que protege a la web (D1) no depende de esto: ver `HcRepository.leeDeLaWeb`. */
    override val soloLectura: Boolean = false

    /** Spec 005 (RF-5): su envío es de `HcClinicaEnvio`, no de `HcApiSync`: el motor viejo la ignora y no la cuenta. */
    override val envioPropio: Boolean = true

    /** Spec 005 (RF-66): la licencia de la HC general la administra la web de hclinica hasta E5; el panel de la app no la toca. */
    override val administraLicencia: Boolean = false

    private val log = Logger.withTag("HcClinica")
    private val hq get() = db.historiaClinicaQueries
    private val pq get() = db.pacientesQueries
    private val marcas = MarcasDeLaWeb(db)
    private val loEnviados = LoEnviadoGuardado(db)

    /** Una lectura a la vez: dos pantallas que piden lo mismo juntas hacen un solo pedido (la segunda ve el minuto). */
    private val candado = Mutex()

    /** Cuándo se leyó bien la lista de cada (paciente, médico), para no repetirla antes de [ESPERA_MS]. */
    private val ultimaLista = mutableMapOf<String, Long>()

    /** Lo mismo para el historial: es otro pedido y tiene su propio minuto (si falló, no corre el de la lista). */
    private val ultimoHistorial = mutableMapOf<String, Long>()

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = traerConsultas(pacienteId, medicoId, forzar = false)

    /**
     * Trae la lista de consultas de la web del paciente, de a [LIMITE_POR_PAGINA], hasta la última página (con un tope de
     * [MAX_PAGINAS]), y la escribe de una vez, de la más vieja a la más nueva; después trae su historial (un pedido) y lo
     * escribe. [forzar] (el botón «Actualizar») se salta el minuto de espera de los dos. Devuelve las consultas de la web
     * que el dispositivo tiene del paciente.
     *
     * Si falla algún pedido (sin conexión, `401`, `403`, `404`, `422`, `429`, `500`, respuesta incompleta) lanza el error de
     * la API, sin reintento propio (RF-58), y lo que ese pedido iba a escribir no se escribe: lo ya traído se queda como
     * estaba. La lista y el historial son independientes: si falla la lista no se pide el historial; si falla el historial
     * la lista ya escrita se queda (y el próximo pedido sin «Actualizar» solo repite el historial).
     */
    override suspend fun traerConsultas(pacienteId: Id, medicoId: Id, forzar: Boolean): List<Consulta> = candado.withLock {
        val ficha = pacientes.fichaConocida(pacienteId, especialidad)
        if (ficha == null) {
            log.i { "$pacienteId no tiene ficha en la HC general: no se pide nada" }
            return@withLock emptyList()
        }
        val clave = "$pacienteId|$medicoId"
        if (forzar || vencida(ultimaLista[clave])) {
            val paginas = leerLista(ficha)
            db.transaction {
                marcas.aplicar(pacienteId, LecturaWeb.Lista(ficha, paginas), hoy(), ahora())
                aplicarLista(pacienteId, medicoId, ficha, paginas)
            }
            ultimaLista[clave] = ahora()
        }
        // Un pedido y una transacción aparte: si falla la lista no se pide (un 429, un 401 o una caída valen para los dos y
        // RF-57 pide no insistir); si falla el historial, la lista ya escrita se queda y el error sale igual.
        if (forzar || vencida(ultimoHistorial[clave])) {
            val historial = leerHistorial(ficha)
            db.transaction { aplicarHistoria(pacienteId, medicoId, ficha, historial) }
            ultimoHistorial[clave] = ahora()
        }
        consultasDeLaWeb(pacienteId)
    }

    private fun vencida(ultima: Long?) = ultima == null || ahora() - ultima >= ESPERA_MS

    /**
     * Lo que el paciente ya tiene traído y la web dejó de mandar (RF-63, Q15): clave de ausencia (el id local de la cosa, o
     * `<consulta>:<sección>:<campo>` para el contenido de una consulta) → fecha en que se notó. Las filas siguen en el
     * dispositivo; esto solo dice cuáles mostrar como «Ya no figura en la web».
     */
    suspend fun marcasAusentes(pacienteId: Id): Map<String, String> = marcas.delPaciente(pacienteId)

    /**
     * Las páginas de `GET consultas`, en orden. Se corta (sin error, y lo recibido igual se aplica) si ya no hay más, si se
     * llegó al tope, o si una página no cuadra: vacía, con otro `desde` o con otro `total` que la primera. `marcarAusentes`
     * (E6.4b) vuelve a comprobarlo y no marca nada con una lista que no cierra.
     */
    private suspend fun leerLista(ficha: Long): List<RespuestaListaConsultasDto> {
        val paginas = mutableListOf<RespuestaListaConsultasDto>()
        var desde = 0
        while (paginas.size < maxPaginas) {
            val pagina = api.listar(ficha, LIMITE_POR_PAGINA, desde)
            paginas += pagina
            if (!pagina.quedanMas) break
            if (pagina.consultas.isEmpty() || pagina.desde != desde || pagina.total != paginas.first().total) {
                log.w { "la lista de $ficha no cuadra (desde=${pagina.desde}, total=${pagina.total}): se deja de pedir" }
                break
            }
            desde += pagina.consultas.size
        }
        if (paginas.size >= maxPaginas && paginas.last().quedanMas) log.w { "la lista de $ficha pasa del tope de $maxPaginas páginas" }
        return paginas
    }

    /** Escribe lo que trajo la lista, de la consulta más vieja a la más nueva (`updated_at` creciente). Dentro de una transacción. */
    private suspend fun aplicarLista(pacienteId: Id, medicoId: Id, ficha: Long, paginas: List<RespuestaListaConsultasDto>) {
        val sello = ahora()
        val cabeceras = paginas.flatMap { it.consultas }.filter { it.pacienteId == ficha }.distinctBy { it.id }
        val sucias = hq.consultasDirty().lista { it.id }.toSet()
        // `updated_at` crece con la antigüedad inversa: las del mismo día se desempatan en la base por él (no se guarda `created_at`).
        ordenarMasRecientesPrimero(cabeceras).asReversed().forEachIndexed { i, c ->
            val web = consultaDeLaWeb(c, pacienteId, medicoId)
            if (web == null) {
                log.w { "la consulta ${c.id} de la web no se puede mostrar (tipo, estado o fecha desconocidos)" }
                return@forEachIndexed
            }
            val local = hq.consultaPorId(web.id).uno { it.toModel() }
            val vinculadas = hq.consultaPorRemotoId(especialidad, web.remotoId).lista { it.toModel() }.filter { it.id != web.id }
            // Una propia de esta app que ya se envió (RF-33): la lista la trae como `hcc-c<id>` pero es la propia. Se refresca con lo que
            // cambió en la web salvo lo pendiente, y no se vuelve a crear ni se deja una copia.
            val propia = vinculadas.firstOrNull { it.propia && !it.esDeLaWeb() }
            if (propia != null) {
                if (local != null) retirarCopia(local, sello + i)
                if (propia.medicoId == medicoId && propia.pacienteId == pacienteId && propia.id !in sucias) {
                    fusionarConsultaPropia(web, propia)?.let { hq.upsertConsulta(it.filaDeLaWeb(sello + i)) }
                }
                return@forEachIndexed
            }
            val intocable = web.id in sucias || vinculadas.isNotEmpty() || (local != null && local.pacienteId != pacienteId)
            if (!intocable) fusionarConsulta(web, local)?.let { hq.upsertConsulta(it.filaDeLaWeb(sello + i)) }
            guardarNombreDelMedico(c, sello + i)
        }
        guardarOmitidas(pacienteId, paginas.first(), sello)
    }

    /**
     * La copia `hcc-c<id>` de una consulta que resultó ser una propia (la lectura la trajo antes de que el envío anotara el id de la web,
     * o un corte dejó el id sin anotar): se da de baja para que la consulta se vea una sola vez (RF-33). Es una baja lógica de la fila de
     * la consulta, no un borrado: lo que colgaba de ella queda en el dispositivo y no se pierde nada. No vuelve a crearse mientras la propia
     * tenga ese `remoto_id`.
     */
    private suspend fun retirarCopia(copia: Consulta, sello: Long) {
        log.i { "la consulta ${copia.id} es la propia ya enviada: se retira la copia" }
        hq.upsertConsulta(copia.toRow(sello, deleted = true, dirty = false))
    }
    /** La consulta es una propia de esta app ya enviada con ese `remoto_id` y que se puede refrescar: no anulada (terminal). */
    private fun esPropiaVinculada(local: Consulta, remotoId: String): Boolean =
        local.propia && !local.esDeLaWeb() && local.remotoId == remotoId && local.estado != EstadoConsulta.ANULADA

    /** Las propias ya enviadas del paciente, por el id de su consulta en la web: lo que la web guarda de ella cuelga de la propia (RF-33). */
    private suspend fun vinculadasDelPaciente(pacienteId: Id): Map<Long, Id> =
        hq.consultasDePaciente(pacienteId, especialidad).lista { it.toModel() }
            .filter { it.propia && !it.esDeLaWeb() && it.estado != EstadoConsulta.ANULADA }
            .mapNotNull { c -> c.remotoId.toLongOrNull()?.takeIf { it > 0 }?.let { it to c.id } }
            .toMap()
    /** El nombre de un médico ajeno (`medico_preferencia`); uno vacío no pisa el que había y uno sin enviar no se toca. */
    private suspend fun guardarNombreDelMedico(c: ConsultaCabeceraDto, sello: Long) {
        val nombre = nombreMedicoDeLaWeb(c) ?: return
        val actual = hq.preferenciasDeMedico(nombre.medicoId).lista { it }.firstOrNull { it.clave == nombre.clave }
        if (actual != null && (actual.dirty != 0L || actual.valor == nombre.valor)) return
        hq.upsertPreferencia(nombre.filaDeLaWeb(sello))
    }

    /** Lo que la lista no manda y cuenta (RF-43). Es una cuenta, no un dato clínico: sigue a la web, también hacia cero. */
    private suspend fun guardarOmitidas(pacienteId: Id, primera: RespuestaListaConsultasDto, sello: Long) =
        guardarAviso(pacienteId, CLAVE_OMITIDAS, omitidasComoTexto(primera.omitidas), primera.omitidas.hayAlgo(), sello)

    /**
     * Un aviso del paciente (`paciente_extra`, sin migración): lo que la web informa y la app no muestra. Es una cuenta o una
     * lista de nombres, no un dato clínico, así que sigue a la web también hacia cero; sin fila previa y sin nada que avisar
     * ([hayAlgo] falso) no se escribe, y una fila con cambios sin enviar no se toca.
     */
    private suspend fun guardarAviso(pacienteId: Id, clave: String, texto: String, hayAlgo: Boolean, sello: Long) {
        val actual = pq.extrasDePaciente(pacienteId, especialidad).lista { it }.firstOrNull { it.clave == clave }
        val escribir = if (actual == null) hayAlgo else actual.dirty == 0L && actual.valor != texto
        if (escribir) pq.upsertPacienteExtra(PacienteExtra(pacienteId, especialidad, clave, texto).filaDeLaWeb(sello))
    }

    private suspend fun consultasDeLaWeb(pacienteId: Id): List<Consulta> =
        hq.consultasDePaciente(pacienteId, especialidad).lista { it.toModel() }.filter { it.esDeLaWeb() }

    // ------------------------------------------------------------------
    // El historial del paciente (`GET pacientes/{id}/historia`)
    // ------------------------------------------------------------------

    /** Un solo pedido; si la respuesta es de otra ficha es dudosa y no se aplica (RF-55). */
    private suspend fun leerHistorial(ficha: Long): RespuestaHistoriaDto {
        val historial = api.historia(ficha)
        if (historial.pacienteId != ficha) throw respuestaDudosa("el historial es de la ficha ${historial.pacienteId} y se pidió la $ficha")
        return historial
    }

    /** El día de hoy según el reloj del backend (el mismo que sella las filas): es el valor de una marca. */
    private fun hoy(): LocalDate = Instant.fromEpochMilliseconds(ahora()).toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun respuestaDudosa(detalle: String) = TobbException(0, "respuesta", "Respuesta inesperada de $especialidad: $detalle")

    /**
     * Escribe lo que la web guarda por paciente y fecha, campo por campo y solo lo que viene con valor (nunca un
     * `INSERT OR REPLACE` de una fila armada solo con lo recibido, nunca un `DELETE`): antecedentes familiares (con la nota
     * del perfil 4), cargas de laboratorio y de peso, estudios, exámenes complementarios, interconsultas, el motivo de las
     * consultas que ya están en el dispositivo y el pendiente. Lo sucio del dispositivo no se toca, lo traído antes que
     * ya no viene se queda (Q15) y lo que se escribe lleva `dirty = 0`. Va en una transacción: o entra todo o nada, también
     * las marcas «Ya no figura en la web» de lo que esta respuesta dejó de traer (E6.4b), que se escriben primero.
     *
     * Las cargas de laboratorio y peso que esta app ya envió se emparejan por tipo + `remoto_id` (RF-33, T86-a): lo que la web guarda de
     * ellas se refresca sin nacer una `hcc-l…` aparte, y una dada de baja en el dispositivo no se reactiva.
     */
    private suspend fun aplicarHistoria(pacienteId: Id, medicoId: Id, ficha: Long, h: RespuestaHistoriaDto) {
        val sello = ahora()
        marcas.aplicar(pacienteId, LecturaWeb.Historia(ficha, h), hoy(), sello)
        val consultas = hq.consultasDePaciente(pacienteId, especialidad).lista { it.id }.toSet()
        // Las propias ya enviadas (RF-33): lo que la web guarda de su consulta cuelga de ellas.
        val vinculadas = vinculadasDelPaciente(pacienteId)

        val familiares = hq.antecedentesDeCategoria(pacienteId, especialidad, CATEGORIA_FAMILIARES).lista { it.toModel() }
        val antecedentesSucios = hq.antecedentesDirty().lista { it.id }.toSet()
        antecedentesDeLaWeb(pacienteId, h).forEach { web ->
            fusionarAntecedente(web, familiares, antecedentesSucios)?.let { hq.upsertAntecedente(it.filaDeLaWeb(sello)) }
        }

        val cargasSucias = hq.laboratoriosDirty().lista { it.id }.toSet()
        // Las cargas que esta app ya envió tienen su id local y el de la web (`remoto_id`): se emparejan por tipo + `remoto_id` (RF-33, T86-a)
        // y no se duplican como `hcc-l…`.
        val cargasPropias = hq.laboratoriosDePacienteTodos(pacienteId, especialidad).lista { it.toModel() }
            .filter { !it.id.startsWith(PREFIJO_ID_WEB) && it.remotoId.isNotBlank() }
            .associateBy { it.tipo to it.remotoId }
        laboratoriosDeLaWeb(pacienteId, h.laboratorios, consultas, vinculadas).forEach { web ->
            val propia = cargasPropias[web.tipo to web.remotoId]
            if (propia != null) {
                // Lo sucio manda (una baja propia esperando viajar no se reactiva): lo limpio se refresca si la web lo cambió (RF-34).
                if (propia.id !in cargasSucias) {
                    fusionarLaboratorio(cargaDeLaWebParaLaPropia(web, propia), propia)?.let { hq.upsertLaboratorio(it.filaDeLaWeb(sello)) }
                }
                return@forEach
            }
            if (web.id in cargasSucias) return@forEach
            val local = hq.laboratorioPorId(web.id).uno { it.toModel() }
            if (local != null && local.pacienteId != pacienteId) return@forEach
            fusionarLaboratorio(web, local)?.let { hq.upsertLaboratorio(it.filaDeLaWeb(sello)) }
        }

        val registrosSucios = hq.registrosDirty().lista { it.id }.toSet()
        // Las filas que esta app ya envió tienen su id local y el de la web (`remoto_id`): se emparejan por él (RF-33) y no se duplican.
        val registrosPropios = hq.registrosDePacienteTodos(pacienteId, especialidad).lista { it.toModel() }
            .filter { !it.id.startsWith(PREFIJO_ID_WEB) && it.remotoId.isNotBlank() }
            .associateBy { it.tipo to it.remotoId }
        // T65-a: lo que la web tiene de cada estudio propio, por la consulta local a la que pertenece, para dejarlo anotado en `LoEnviado`.
        val propiasLocales = vinculadas.values.toSet()
        val estudiosDeLaWeb = mutableMapOf<Id, Map<String, Map<String, String>>>()
        registrosDeLaWeb(pacienteId, h, consultas, vinculadas).forEach { web ->
            val propio = registrosPropios[web.tipo to web.remotoId]
            if (propio != null) {
                if (propio.id !in registrosSucios) {
                    fusionarRegistro(web.copy(id = propio.id, activo = propio.activo), propio)?.let { hq.upsertRegistro(it.filaDeLaWeb(sello)) }
                }
                propio.consultaId?.takeIf { it in propiasLocales }?.let { consultaLocalId ->
                    estudiosDeLaWeb[consultaLocalId] = estudiosDeLaWeb[consultaLocalId].orEmpty() + (web.remotoId to camposConfirmadosDelEstudio(web))
                }
                return@forEach
            }
            if (web.id in registrosSucios) return@forEach
            val local = hq.registroPorId(web.id).uno { it.toModel() }
            if (local != null && local.pacienteId != pacienteId) return@forEach
            fusionarRegistro(web, local)?.let { hq.upsertRegistro(it.filaDeLaWeb(sello)) }
        }
        // Lo que la web tiene de los estudios propios queda en `LoEnviado` (también si la fila local estaba sucia: lo sucio es del dispositivo y
        // lo anotado es de la web, que es contra lo que el plan compara el cambio siguiente).
        estudiosDeLaWeb.forEach { (consultaLocalId, confirmados) ->
            val previo = loEnviados.deLaConsulta(pacienteId, consultaLocalId)
            val nuevo = previo.copy(registros = previo.registros + confirmados)
            if (nuevo != previo) loEnviados.guardar(pacienteId, consultaLocalId, nuevo, sello)
        }

        val valoresSucios = hq.seccionValoresDirty().lista { Triple(it.consulta_id, it.seccion, it.campo) }.toSet()
        motivosDeLaWeb(h, consultas).forEach { web ->
            if (Triple(web.consultaId, web.seccion, web.campo) in valoresSucios) return@forEach
            val actual = hq.valoresDeSeccion(web.consultaId, web.seccion).lista { it.toModel() }.firstOrNull { it.campo == web.campo }
            if (actual?.valor != web.valor) hq.upsertSeccionValor(web.filaDeLaWeb(sello))
        }

        val pendientesSucios = hq.pendientesDirty().lista { it.id }.toSet()
        pendienteDeLaWeb(pacienteId, medicoId, h)?.takeIf { it.id !in pendientesSucios }?.let { web ->
            val local = hq.pendientePorId(web.id).uno { it.toModel() }
            if (local != null && local.pacienteId != pacienteId) return@let
            if (local != web) hq.upsertPendiente(web.filaDeLaWeb(sello))
        }

        val sinFecha = h.omitidas.laboratorioSinFecha.coerceAtLeast(0)
        guardarAviso(pacienteId, CLAVE_OMITIDAS_LABORATORIO, omitidasLaboratorioComoTexto(sinFecha), sinFecha > 0, sello)
    }

    // ------------------------------------------------------------------
    // Una consulta de la web (`GET consultas/{id}`)
    // ------------------------------------------------------------------

    /**
     * Trae el contenido de una consulta de la web (un solo pedido, RF-57) y lo escribe en `seccion_valor` y `examen_fisico`
     * de esa consulta, más el aviso de `no_leidas` (RF-43) en `paciente_extra`; todo con `dirty = 0` y en una transacción.
     * El resto de lo que la web guarda por consulta (laboratorio, estudios, pendientes, motivos de las otras) viene con el
     * historial. No cambia la fila de la consulta, que es de la lista: una consulta de la web es siempre solo lectura (RF-25).
     *
     * No pide nada, y no escribe, si la consulta no es de la web (su id no es `hcc-c<remotoId>`), si no está en el
     * dispositivo, si tiene cambios sin enviar o si el paciente no tiene ficha (RF-49). Una clave sucia no se toca, un valor
     * vacío o que no viene no pisa uno cargado y nada se borra (Q15). Cualquier error de la API (`404
     * consulta_no_encontrada`, `403`, `429`, ...) sale tal cual, sin reintento y sin escribir: quien quiera marcarla como «ya
     * no figura en la web» (E6.4b) distingue el `404` por su código.
     */
    override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) {
        val consultaWebId = remotoId.toLongOrNull()?.takeIf { it > 0 }
        if (consultaWebId == null) {
            log.w { "$consultaLocalId no es la consulta $remotoId de la web: no se pide" }
            return
        }
        // Primero el pedido, con el candado de la lectura y sin el turno de la consulta. Una consulta de la web se aplica ahí mismo, como
        // siempre; una propia deja lo que trajo (y cómo estaba lo enviado antes de pedirlo) para aplicarlo con su turno.
        val propia = candado.withLock {
            val local = hq.consultaPorId(consultaLocalId).uno { it.toModel() }
            if (local == null || local.especialidad != especialidad) return@withLock null
            // Es la consulta de la web (`hcc-c<id>`) o una propia ya enviada con ese `remoto_id` (RF-33); cualquier otra no se pide.
            val deLaWeb = consultaLocalId == idConsultaWeb(consultaWebId)
            if (!deLaWeb && !esPropiaVinculada(local, remotoId)) {
                log.w { "$consultaLocalId no es la consulta $remotoId de la web: no se pide" }
                return@withLock null
            }
            // La fila sucia de una consulta de la web no se toca; la de una propia (cierre o baja pendientes) no impide refrescar su
            // contenido: lo pendiente se protege campo por campo.
            if (deLaWeb && consultaLocalId in hq.consultasDirty().lista { it.id }) return@withLock null
            val ficha = pacientes.fichaConocida(local.pacienteId, especialidad)
            if (ficha == null) {
                log.i { "${local.pacienteId} no tiene ficha en la HC general: no se pide nada" }
                return@withLock null
            }
            // Lo que la web tiene de la propia según esta app, ANTES de pedir: si cuando llegue la respuesta ya es otro, un envío confirmó algo
            // en el medio y la respuesta puede ser más vieja que eso.
            val antes = if (deLaWeb) null else loEnviados.deLaConsulta(local.pacienteId, local.id)
            val respuesta = try {
                api.consulta(consultaWebId)
            } catch (e: TobbException) {
                if (e.status == 404 && e.codigo == CODIGO_CONSULTA_NO_ENCONTRADA) marcarNoEncontrada(local, consultaWebId, e)
                throw e
            }
            if (respuesta.consulta.id != consultaWebId) throw respuestaDudosa("llegó la consulta ${respuesta.consulta.id} y se pidió la $consultaWebId")
            if (respuesta.consulta.pacienteId != ficha) {
                throw respuestaDudosa("la consulta $consultaWebId es de la ficha ${respuesta.consulta.pacienteId} y el paciente tiene la $ficha")
            }
            if (antes == null) {
                db.transaction { aplicarConsulta(local, respuesta) }
                null
            } else {
                PropiaTraida(local.id, local.pacienteId, remotoId, respuesta, antes)
            }
        } ?: return
        aplicarEnSuTurno(propia)
    }

    /** El contenido que trajo la web de una propia, a la espera de su turno: [antes] es lo que la web tenía según `LoEnviado` al pedirlo. */
    private class PropiaTraida(
        val id: Id,
        val pacienteId: Id,
        val remotoId: String,
        val respuesta: RespuestaConsultaDto,
        val antes: LoEnviado,
    )

    /**
     * Aplica lo que trajo la web de una propia **con el turno de la consulta** (el mismo de la cola de envío, RF-54), así nunca corre a la
     * vez que un envío ni lo deshace. Si desde que se pidió un envío confirmó algo (`LoEnviado` cambió), la respuesta puede ser anterior y no
     * se aplica: la lectura siguiente trae lo vigente. Tampoco si mientras tanto dejó de ser una propia refrescable (la anularon).
     *
     * Sin riesgo de bloqueo cruzado: acá el orden es siempre turno y después candado de la lectura, y quien tiene el candado de la lectura
     * (la lista, el pedido de la consulta) nunca espera un turno ni algo que lo tenga; la cola de envío y `HcRepository`, que sí tienen el
     * turno, jamás llaman a la lectura.
     */
    private suspend fun aplicarEnSuTurno(p: PropiaTraida) = turnos.conTurno(p.id) {
        candado.withLock {
            val local = hq.consultaPorId(p.id).uno { it.toModel() }
            if (local == null || !esPropiaVinculada(local, p.remotoId)) return@withLock
            if (loEnviados.deLaConsulta(p.pacienteId, p.id) != p.antes) {
                log.i { "la consulta ${p.id} cambió mientras se pedía a la web: se descarta esa respuesta" }
                return@withLock
            }
            db.transaction { aplicarConsulta(local, p.respuesta) }
        }
    }
    /**
     * La web contesta `404 consulta_no_encontrada` por una consulta que ya se había traído (RF-63): se la marca «Ya no figura
     * en la web» y queda en el dispositivo con todo su contenido. El error sale igual (lo lanza quien llama); si ni la marca se
     * puede escribir, se anota y se conserva el `404`.
     */
    private suspend fun marcarNoEncontrada(local: Consulta, consultaWebId: Long, error: TobbException) {
        try {
            db.transaction { marcas.aplicar(local.pacienteId, LecturaWeb.ConsultaNoEncontrada(consultaWebId), hoy(), ahora()) }
        } catch (falla: Exception) {
            if (falla is CancellationException) throw falla
            log.w(falla) { "no se pudo marcar la consulta $consultaWebId como ausente de la web" }
            error.addSuppressed(falla)
        }
    }

    /**
     * Escribe campo por campo lo que la consulta trae con valor; lo sucio no se toca y nada se borra ni se vacía. Primero van
     * las marcas «Ya no figura en la web» del contenido que esta respuesta dejó de traer (E6.4b): en la misma transacción.
     */
    private suspend fun aplicarConsulta(local: Consulta, r: RespuestaConsultaDto) {
        val sello = ahora()
        val id = local.id
        marcas.aplicar(local.pacienteId, LecturaWeb.UnaConsulta(r), hoy(), sello)

        val sucios = hq.seccionValoresDirty().lista { it }.filter { it.consulta_id == id }.map { it.seccion to it.campo }.toSet()
        val actuales = hq.valoresDeConsulta(id).lista { it.toModel() }.associateBy { it.seccion to it.campo }
        val examenSucio = id in hq.examenesFisicosDirty().lista { it.consulta_id }
        val actualExamen = hq.examenFisicoDeConsulta(id).uno { it.toModel() }
        if (local.propia && !local.esDeLaWeb()) {
            // Una propia ya enviada (RF-33): lo pendiente manda, un vacío no pisa y lo que el médico descartó se conserva. Lo que la web
            // tiene queda anotado, así lo que el médico escriba después se compara contra eso.
            val previo = loEnviados.deLaConsulta(local.pacienteId, id)
            valoresDeLaWebParaLaPropia(valoresDeLaWeb(id, r.secciones), actuales, sucios, previo)
                .forEach { hq.upsertSeccionValor(it.filaDeLaWeb(sello)) }
            if (!examenSucio) {
                fusionarExamenDeLaPropia(id, r.examenFisico, actualExamen, previo.examen)?.let { hq.upsertExamenFisico(it.filaDeLaWeb(sello)) }
            }
            val nuevo = loEnviadoTrasLaLectura(previo, r.secciones, r.examenFisico)
            if (nuevo != previo) loEnviados.guardar(local.pacienteId, id, nuevo, sello)
        } else {
            valoresDeLaWeb(id, r.secciones).forEach { web ->
                val clave = web.seccion to web.campo
                if (clave !in sucios && actuales[clave]?.valor != web.valor) hq.upsertSeccionValor(web.filaDeLaWeb(sello))
            }
            if (!examenSucio) fusionarExamenFisico(id, r.examenFisico, actualExamen)?.let { hq.upsertExamenFisico(it.filaDeLaWeb(sello)) }
        }

        val noLeidas = noLeidasComoTexto(r.noLeidas)
        guardarAviso(local.pacienteId, claveNoLeidas(r.consulta.id), noLeidas, noLeidas.isNotEmpty(), sello)
    }

    // ------------------------------------------------------------------
    // Envío: no existe (RF-47)
    // ------------------------------------------------------------------

    override suspend fun asegurarConsulta(consulta: Consulta): String? = noEnvia("asegurarConsulta")

    override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) = noEnvia("enviarSecciones")

    override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) = noEnvia("enviarExamen")

    override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> = noEnvia("enviarRegistros")

    override suspend fun enviarEstado(consulta: Consulta, remotoId: String) = noEnvia("enviarEstado")

    override suspend fun subirArchivo(remotoId: String, archivo: Archivo, bytes: ByteArray): String? = noEnvia("subirArchivo")

    override suspend fun borrarArchivo(remotoId: String, archivo: Archivo) = noEnvia("borrarArchivo")

    /** La entrega L no trae adjuntos (`no_leidas` los avisa): no hay nada que bajar. */
    override suspend fun bajarArchivo(remotoId: String, archivo: Archivo): ByteArray? = null

    // Estos métodos son del motor viejo (`HcApiSync`), que ignora a este backend por `envioPropio`: la HC general se envía con
    // `HcClinicaEnvio` y la API de escritura ([escritura]). Si alguien los llama, lanzan sin tocar la red, como siempre.
    private fun noEnvia(operacion: String): Nothing =
        throw UnsupportedOperationException("La historia clínica general no se envía con este motor ($operacion): lo hace HcClinicaEnvio")

    companion object {
        /** Consultas por pedido (Q9). */
        const val LIMITE_POR_PAGINA = 50

        /** Tope de seguridad: 20 páginas son 1000 consultas de un mismo paciente. */
        const val MAX_PAGINAS = 20

        /** No se repite la lista del mismo paciente antes de un minuto (RF-57). */
        const val ESPERA_MS = 60_000L

        /** El `codigo` del `404` de `GET consultas/{id}` cuando la consulta ya no figura (dada de baja o inexistente). */
        const val CODIGO_CONSULTA_NO_ENCONTRADA = "consulta_no_encontrada"
    }
}

/** Sin API de escritura: lo que usan los tests de la lectura. Ninguna lectura la llama; si algo lo hace, falla fuerte. */
private object SinEscritura : ClinicaEscrituraApi {
    private fun sinApi(): Nothing = throw UnsupportedOperationException("Este HcClinicaBackend se armó sin API de escritura")

    override suspend fun crear(clave: String, dispositivo: String, pacienteWebId: Long, tipo: String, fecha: LocalDate, edadMostrar: String?): RespuestaCrearConsultaDto = sinApi()
    override suspend fun secciones(consultaWebId: Long, textos: Map<String, String>): RespuestaEscrituraDto = sinApi()
    override suspend fun examen(consultaWebId: Long, campos: Map<String, String>): RespuestaEscrituraDto = sinApi()
    override suspend fun formularios(consultaWebId: Long, valores: Map<String, Map<String, String>>): RespuestaEscrituraDto = sinApi()
    override suspend fun antecedentes(pacienteWebId: Long, consultaWebId: Long, antecedentes: List<Antecedente>, enviados: Map<String, Antecedente>): RespuestaAntecedentesDto = sinApi()
    override suspend fun registros(consultaWebId: Long, registros: List<RegistroClinico>, enviados: Map<Id, RegistroClinico>): RespuestaRegistrosDto = sinApi()
    override suspend fun cerrar(consultaWebId: Long, clave: String, dispositivo: String, fecha: LocalDate, edadMostrar: String?): RespuestaCerrarDto = sinApi()
    override suspend fun baja(consultaWebId: Long, clave: String, dispositivo: String): RespuestaBajaDto = sinApi()
    override suspend fun laboratorio(
        pacienteWebId: Long,
        consultaWebId: Long,
        dispositivo: String,
        fecha: LocalDate,
        carga: Laboratorio,
        idWeb: Long?,
        confirmada: Laboratorio?,
    ): RespuestaCargaDto = sinApi()
    override suspend fun peso(
        pacienteWebId: Long,
        consultaWebId: Long,
        dispositivo: String,
        fecha: LocalDate,
        carga: Laboratorio,
        idWeb: Long?,
        confirmada: Laboratorio?,
    ): RespuestaCargaDto = sinApi()
    override suspend fun bajaDeCarga(
        pacienteWebId: Long,
        cargaWebId: Long,
        consultaWebId: Long,
        clave: String,
        dispositivo: String,
        tipo: String,
    ): RespuestaBajaDeCargaDto = sinApi()
}
