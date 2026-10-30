package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.CLAVE_OMITIDAS
import com.salud360.core.data.hcgeneral.consultaDeLaWeb
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.hcgeneral.filaDeLaWeb
import com.salud360.core.data.hcgeneral.fusionarConsulta
import com.salud360.core.data.hcgeneral.hayAlgo
import com.salud360.core.data.hcgeneral.nombreMedicoDeLaWeb
import com.salud360.core.data.hcgeneral.omitidasComoTexto
import com.salud360.core.data.hcgeneral.ordenarMasRecientesPrimero
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.ConsultasClinicaApi
import com.salud360.core.data.network.hc.ConsultasClinicaApiRemota
import com.salud360.core.data.network.hc.HcApiSoloLectura
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Historia clínica general (`hclinica`), **solo lectura** (spec 002, entrega L): trae la lista de consultas de la web y la
 * deja en el dispositivo; no manda nada, nunca (RF-47).
 *
 * - No tiene cómo escribir: recibe un [HcApiSoloLectura] (o una [ConsultasClinicaApi], que solo lee) y todo método de
 *   envío de [HcBackend] lanza [UnsupportedOperationException] sin tocar la red.
 * - Sin ficha de la web (`fichaConocida` nula) no pide nada y no llama a `pacientes/resolver` (RF-49).
 * - Lo del dispositivo manda (RF-48, RF-54): una consulta `dirty`, creada acá o ya vinculada con la misma consulta de la
 *   web no se toca. Lo que la web trae se escribe con `dirty = 0`, y un valor vacío no pisa el que había.
 * - Nada se borra ni se vacía (Q15): una consulta que la web dejó de mandar queda como estaba. Las marcas «Ya no figura
 *   en la web» son de E6.4b.
 *
 * Esta tarea (E6.3) trae solo la lista; el contenido de una consulta y el historial son de E6.4.
 */
class HcClinicaBackend internal constructor(
    private val db: Salud360Db,
    private val api: ConsultasClinicaApi,
    private val pacientes: PacientesHcRepository,
    private val ahora: () -> Long,
    private val maxPaginas: Int,
) : HcBackend {
    constructor(db: Salud360Db, api: HcApiSoloLectura, pacientes: PacientesHcRepository, ahora: () -> Long = ::ahoraMillis) :
        this(db, ConsultasClinicaApiRemota(api), pacientes, ahora, MAX_PAGINAS)

    override val especialidad: String = CodigosHc.GENERAL

    override val soloLectura: Boolean = true

    private val log = Logger.withTag("HcClinica")
    private val hq get() = db.historiaClinicaQueries
    private val pq get() = db.pacientesQueries

    /** Una lectura a la vez: dos pantallas que piden lo mismo juntas hacen un solo pedido (la segunda ve el minuto). */
    private val candado = Mutex()

    /** Cuándo se leyó bien la lista de cada (paciente, médico), para no repetirla antes de [ESPERA_MS]. */
    private val ultimaLista = mutableMapOf<String, Long>()

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = traerConsultas(pacienteId, medicoId, forzar = false)

    /**
     * Trae la lista de consultas de la web del paciente, de a [LIMITE_POR_PAGINA], hasta la última página (con un tope de
     * [MAX_PAGINAS]), y la escribe de una vez, de la más vieja a la más nueva. [forzar] (el botón «Actualizar») se salta el
     * minuto de espera. Devuelve las consultas de la web que el dispositivo tiene del paciente.
     *
     * Si falla algún pedido (sin conexión, `401`, `403`, `404`, `422`, `429`, `500`, respuesta incompleta) lanza el error de
     * la API y no escribe nada: lo ya traído se queda como estaba. Sin reintento propio (RF-58).
     */
    suspend fun traerConsultas(pacienteId: Id, medicoId: Id, forzar: Boolean): List<Consulta> = candado.withLock {
        val ficha = pacientes.fichaConocida(pacienteId, especialidad)
        if (ficha == null) {
            log.i { "$pacienteId no tiene ficha en la HC general: no se pide nada" }
            return@withLock emptyList()
        }
        val clave = "$pacienteId|$medicoId"
        val ultima = ultimaLista[clave]
        if (!forzar && ultima != null && ahora() - ultima < ESPERA_MS) return@withLock consultasDeLaWeb(pacienteId)

        val paginas = leerLista(ficha)
        db.transaction { aplicarLista(pacienteId, medicoId, ficha, paginas) }
        ultimaLista[clave] = ahora()
        consultasDeLaWeb(pacienteId)
    }

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
    private suspend fun guardarOmitidas(pacienteId: Id, primera: RespuestaListaConsultasDto, sello: Long) {
        val texto = omitidasComoTexto(primera.omitidas)
        val actual = pq.extrasDePaciente(pacienteId, especialidad).lista { it }.firstOrNull { it.clave == CLAVE_OMITIDAS }
        val escribir = if (actual == null) primera.omitidas.hayAlgo() else actual.dirty == 0L && actual.valor != texto
        if (escribir) pq.upsertPacienteExtra(PacienteExtra(pacienteId, especialidad, CLAVE_OMITIDAS, texto).filaDeLaWeb(sello))
    }

    private suspend fun consultasDeLaWeb(pacienteId: Id): List<Consulta> =
        hq.consultasDePaciente(pacienteId, especialidad).lista { it.toModel() }.filter { it.esDeLaWeb() }

    /** El contenido de una consulta de la web es de E6.4: hasta entonces este backend no se registra (E6.5). */
    override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) {
        throw UnsupportedOperationException("traerConsulta todavía no está hecho (E6.4)")
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
    }
}
