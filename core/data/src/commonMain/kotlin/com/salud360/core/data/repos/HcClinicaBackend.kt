package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.CATEGORIA_FAMILIARES
import com.salud360.core.data.hcgeneral.CLAVE_OMITIDAS
import com.salud360.core.data.hcgeneral.CLAVE_OMITIDAS_LABORATORIO
import com.salud360.core.data.hcgeneral.LecturaWeb
import com.salud360.core.data.hcgeneral.antecedentesDeLaWeb
import com.salud360.core.data.hcgeneral.claveNoLeidas
import com.salud360.core.data.hcgeneral.consultaDeLaWeb
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.hcgeneral.filaDeLaWeb
import com.salud360.core.data.hcgeneral.fusionarAntecedente
import com.salud360.core.data.hcgeneral.fusionarConsulta
import com.salud360.core.data.hcgeneral.fusionarExamenFisico
import com.salud360.core.data.hcgeneral.fusionarLaboratorio
import com.salud360.core.data.hcgeneral.fusionarRegistro
import com.salud360.core.data.hcgeneral.hayAlgo
import com.salud360.core.data.hcgeneral.idConsultaWeb
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
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.ConsultasClinicaApi
import com.salud360.core.data.network.hc.ConsultasClinicaApiRemota
import com.salud360.core.data.network.hc.HcApiSoloLectura
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
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
 * Historia clínica general (`hclinica`), **solo lectura** (spec 002, entrega L): trae de la web las consultas del paciente,
 * su historial y el contenido de cada consulta, y lo deja en el dispositivo; no manda nada, nunca (RF-47).
 *
 * - No tiene cómo escribir: recibe un [HcApiSoloLectura] (o una [ConsultasClinicaApi], que solo lee) y todo método de
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
) : HcBackend, Refrescable {
    constructor(db: Salud360Db, api: HcApiSoloLectura, pacientes: PacientesHcRepository, ahora: () -> Long = ::ahoraMillis) :
        this(db, ConsultasClinicaApiRemota(api), pacientes, ahora, MAX_PAGINAS)

    override val especialidad: String = CodigosHc.GENERAL

    override val soloLectura: Boolean = true

    private val log = Logger.withTag("HcClinica")
    private val hq get() = db.historiaClinicaQueries
    private val pq get() = db.pacientesQueries
    private val marcas = MarcasDeLaWeb(db)

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
            val delDispositivo = hq.consultaPorRemotoId(especialidad, web.remotoId).lista { it.id }.any { it != web.id }
            val intocable = web.id in sucias || delDispositivo || (local != null && local.pacienteId != pacienteId)
            if (!intocable) fusionarConsulta(web, local)?.let { hq.upsertConsulta(it.filaDeLaWeb(sello + i)) }
            guardarNombreDelMedico(c, sello + i)
        }
        guardarOmitidas(pacienteId, paginas.first(), sello)
    }

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
     */
    private suspend fun aplicarHistoria(pacienteId: Id, medicoId: Id, ficha: Long, h: RespuestaHistoriaDto) {
        val sello = ahora()
        marcas.aplicar(pacienteId, LecturaWeb.Historia(ficha, h), hoy(), sello)
        val consultas = hq.consultasDePaciente(pacienteId, especialidad).lista { it.id }.toSet()

        val familiares = hq.antecedentesDeCategoria(pacienteId, especialidad, CATEGORIA_FAMILIARES).lista { it.toModel() }
        val antecedentesSucios = hq.antecedentesDirty().lista { it.id }.toSet()
        antecedentesDeLaWeb(pacienteId, h).forEach { web ->
            fusionarAntecedente(web, familiares, antecedentesSucios)?.let { hq.upsertAntecedente(it.filaDeLaWeb(sello)) }
        }

        val cargasSucias = hq.laboratoriosDirty().lista { it.id }.toSet()
        laboratoriosDeLaWeb(pacienteId, h.laboratorios, consultas).filter { it.id !in cargasSucias }.forEach { web ->
            val local = hq.laboratorioPorId(web.id).uno { it.toModel() }
            if (local != null && local.pacienteId != pacienteId) return@forEach
            fusionarLaboratorio(web, local)?.let { hq.upsertLaboratorio(it.filaDeLaWeb(sello)) }
        }

        val registrosSucios = hq.registrosDirty().lista { it.id }.toSet()
        registrosDeLaWeb(pacienteId, h, consultas).filter { it.id !in registrosSucios }.forEach { web ->
            val local = hq.registroPorId(web.id).uno { it.toModel() }
            if (local != null && local.pacienteId != pacienteId) return@forEach
            fusionarRegistro(web, local)?.let { hq.upsertRegistro(it.filaDeLaWeb(sello)) }
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
        if (consultaWebId == null || consultaLocalId != idConsultaWeb(consultaWebId)) {
            log.w { "$consultaLocalId no es la consulta $remotoId de la web: no se pide" }
            return
        }
        candado.withLock {
            val local = hq.consultaPorId(consultaLocalId).uno { it.toModel() }
            if (local == null || local.especialidad != especialidad) return@withLock
            if (consultaLocalId in hq.consultasDirty().lista { it.id }) return@withLock
            val ficha = pacientes.fichaConocida(local.pacienteId, especialidad)
            if (ficha == null) {
                log.i { "${local.pacienteId} no tiene ficha en la HC general: no se pide nada" }
                return@withLock
            }
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
            db.transaction { aplicarConsulta(local, respuesta) }
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
        valoresDeLaWeb(id, r.secciones).forEach { web ->
            val clave = web.seccion to web.campo
            if (clave !in sucios && actuales[clave]?.valor != web.valor) hq.upsertSeccionValor(web.filaDeLaWeb(sello))
        }

        if (id !in hq.examenesFisicosDirty().lista { it.consulta_id }) {
            val actual = hq.examenFisicoDeConsulta(id).uno { it.toModel() }
            fusionarExamenFisico(id, r.examenFisico, actual)?.let { hq.upsertExamenFisico(it.filaDeLaWeb(sello)) }
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

    private fun noEnvia(operacion: String): Nothing =
        throw UnsupportedOperationException("La historia clínica general es de solo lectura: $operacion no manda nada")

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
