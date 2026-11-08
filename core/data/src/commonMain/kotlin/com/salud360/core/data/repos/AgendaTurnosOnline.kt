package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.tobb.TOBB_DNI_BLOQUEO
import com.salud360.core.data.network.tobb.TobbAgendaDia
import com.salud360.core.data.network.tobb.TobbAgendaRequest
import com.salud360.core.data.network.tobb.TobbAgendaResponse
import com.salud360.core.data.network.tobb.TobbAgendaSemana
import com.salud360.core.data.network.tobb.TobbCrearTipoTurnoRequest
import com.salud360.core.data.network.tobb.TobbCrearTipoTurnoResponse
import com.salud360.core.data.network.tobb.TobbHistoriasClinicasRequest
import com.salud360.core.data.network.tobb.TobbHistoriasClinicasResponse
import com.salud360.core.data.network.tobb.TobbAltaResponse
import com.salud360.core.data.network.tobb.TobbAltaUsuario
import com.salud360.core.data.network.tobb.TobbAltaUsuarioResponse
import com.salud360.core.data.network.tobb.TobbCatalogosResponse
import com.salud360.core.data.network.tobb.TobbDiasAtencion
import com.salud360.core.data.network.tobb.TobbObrasSocialesMedicoResponse
import com.salud360.core.data.network.tobb.TobbFechas
import com.salud360.core.data.network.tobb.TobbHorariosResponse
import com.salud360.core.data.network.tobb.TobbMedicoActivoRequest
import com.salud360.core.data.network.tobb.TobbMedicoActivoResponse
import com.salud360.core.data.network.tobb.TobbMedicoTiposRequest
import com.salud360.core.data.network.tobb.TobbMedicoTiposResponse
import com.salud360.core.data.network.tobb.TobbMensajesResponse
import com.salud360.core.data.network.tobb.TobbNuevoPaciente
import com.salud360.core.data.network.tobb.TobbNuevoTurno
import com.salud360.core.data.network.tobb.TobbPaciente
import com.salud360.core.data.network.tobb.TobbPacienteEdicion
import com.salud360.core.data.network.tobb.TobbPacienteResponse
import com.salud360.core.data.network.tobb.TobbPacienteResumen
import com.salud360.core.data.network.tobb.TobbPacientesResponse
import com.salud360.core.data.network.tobb.TobbPacientesVinculadosResponse
import com.salud360.core.data.network.tobb.TobbTipoConfig
import com.salud360.core.data.network.tobb.TobbTiposTurnoResponse
import com.salud360.core.data.network.tobb.TobbTurno
import com.salud360.core.data.network.tobb.TobbTurnoResponse
import com.salud360.core.data.network.tobb.TobbTurnosResponse
import com.salud360.core.data.network.tobb.TobbUsuariosResponse
import com.salud360.core.data.network.tobb.TobbSecretariasResponse
import com.salud360.core.data.network.tobb.aFechaTobb
import com.salud360.core.data.network.tobb.aCodigoTobb
import com.salud360.core.data.network.tobb.esBloqueo
import com.salud360.core.data.network.tobb.nombresDeTipos
import com.salud360.core.data.network.tobb.texto
import com.salud360.core.data.network.tobb.toCatalogo
import com.salud360.core.data.network.tobb.toTipoDelMedico
import com.salud360.core.data.network.tobb.tobbJson
import com.salud360.core.data.network.tobb.toFechaAgregada
import com.salud360.core.data.network.tobb.toHorario
import com.salud360.core.data.network.tobb.toPaciente
import com.salud360.core.data.network.tobb.toSlots
import com.salud360.core.data.network.tobb.toTurno
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.turnos.Consultorio
import com.salud360.core.model.turnos.EspecialidadTurnos
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.ConfigAgenda
import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.FechaAgregada
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.MensajeEspecial
import com.salud360.core.model.turnos.ModuloTurnos
import com.salud360.core.model.turnos.ObraSocial
import com.salud360.core.model.turnos.ObraSocialMedico
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.TipoTurnoCatalogo
import com.salud360.core.model.turnos.TipoTurnoDelMedico
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Un día de agenda ya resuelto: slots con su turno, si es feriado y los turnos del día. */
data class DiaAgendaResuelto(
    val fecha: LocalDate,
    val slots: List<SlotAgenda>,
    val feriado: Boolean,
    val turnos: List<Turno> = emptyList(),
    val cantidadSobreturnos: Int = 0,
)

/**
 * Agenda de un médico que vive en turnosonlinebb.
 *
 * Laravel sigue siendo el dueño de la base de turnos: cada operación se hace directo contra su API
 * `/api/salud360/...` (con el token de sesión del usuario) y la respuesta se guarda en la base local
 * (`turno`, `paciente`) como caché, para que las pantallas —que observan la base— se actualicen solas
 * y la agenda del día se pueda ver aunque se corte la conexión.
 *
 * Los registros importados llevan ids `tobb-t<id>` (turnos) y `tobb-p<id>` (pacientes), ver [TobbIds].
 */
class AgendaTurnosOnline(private val db: Salud360Db, private val turnos: TurnosOnlineClient) {
    private val log = Logger.withTag("TurnosOnline")
    private val q get() = db.turnosQueries
    private val pq get() = db.pacientesQueries
    private val aq get() = db.authQueries
    private val pacientes = PacientesRepository(db)

    /** Feriados informados por la API en las consultas de agenda (la tabla local no tiene los de la web). */
    private val feriados = mutableMapOf<LocalDate, Boolean>()
    private var pacienteBloqueoId: Long? = null

    fun esFeriadoCacheado(fecha: LocalDate): Boolean = feriados[fecha] == true

    private val _nombresDeTipos = MutableStateFlow<Map<Int, String>>(emptyMap())

    /**
     * Nombre de cada tipo de turno (por código) que turnos informó en las lecturas de esta sesión. Los turnos de la caché
     * local solo guardan el código (el esquema no cambia), así que la pantalla rotula con este mapa. Vive en memoria; si
     * turnos no manda nombres (servidor viejo) queda vacío y se rotula con el del enum o «Tipo <código>».
     */
    val nombresDeTipos: StateFlow<Map<Int, String>> = _nombresDeTipos

    private fun aprenderNombres(nuevos: Map<Int, String>) {
        if (nuevos.isNotEmpty()) _nombresDeTipos.update { it + nuevos }
    }

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    /**
     * Agenda de un día. Sin [tipo] pide los horarios de TODOS los tipos (`todos_los_tipos=1`: un servidor viejo ignora el
     * parámetro y sigue devolviendo solo los de tipo 1); con [tipo] pide solo los de ese tipo, como antes.
     */
    suspend fun dia(medicoId: Id, consultorioId: Id, fecha: LocalDate, tipo: TipoTurno? = null): DiaAgendaResuelto {
        val filtro = if (tipo == null) TODOS_LOS_TIPOS else "tipo_turno" to tipo.codigo
        val obj = turnos.tobbGet("agenda/dia", params(medicoId, consultorioId, "fecha" to fecha.toString(), filtro))
        return cachearDia(medicoId, consultorioId, decodificar(TobbAgendaDia.serializer(), obj))
    }

    /** Próximos días con horarios cargados a partir de `desde` (equivalente a la agenda semanal de la web), de todos los tipos. */
    suspend fun semana(medicoId: Id, consultorioId: Id, desde: LocalDate, cantidad: Int = 5): List<DiaAgendaResuelto> {
        val obj = turnos.tobbGet("agenda/semana", params(medicoId, consultorioId, "desde" to desde.toString(), "cantidad" to cantidad, TODOS_LOS_TIPOS))
        return decodificar(TobbAgendaSemana.serializer(), obj).dias.map { cachearDia(medicoId, consultorioId, it) }
    }

    suspend fun proximasFechas(medicoId: Id, consultorioId: Id, desde: LocalDate, cantidad: Int = 3): List<LocalDate> {
        val obj = turnos.tobbGet("agenda/proximas-fechas", params(medicoId, consultorioId, "desde" to desde.toString(), "cantidad" to cantidad, TODOS_LOS_TIPOS))
        return decodificar(TobbFechas.serializer(), obj).fechas.mapNotNull { it.aFechaTobb() }
    }

    suspend fun diasAtencion(medicoId: Id, consultorioId: Id): Set<DayOfWeek> {
        val obj = turnos.tobbGet("agenda/dias-atencion", params(medicoId, consultorioId))
        return decodificar(TobbDiasAtencion.serializer(), obj).diasSemana.filter { it in 1..7 }.map { DayOfWeek(it) }.toSet()
    }

    // ------------------------------------------------------------------
    // Escritura
    // ------------------------------------------------------------------

    /** Registra un turno (o un primer control doble si viene `segundoHorario`). */
    suspend fun registrar(turno: Turno, segundoHorario: LocalTime? = null): ResultadoTurno = intentar {
        val pacienteId = turno.pacienteId ?: throw TobbException(422, "datos", "Falta el paciente del turno")
        val cuerpo = TobbNuevoTurno(
            medicoId = numeroMedico(turno.medicoId), consultorioId = numeroConsultorio(turno.consultorioId),
            pacienteId = pacienteTobbId(pacienteId, turno.medicoId, turno.consultorioId),
            fecha = turno.fecha.toString(), horario = turno.horario.aTextoTobb(), horario2 = segundoHorario?.aTextoTobb(),
            tipoTurno = turno.codigoDeTipo, primerControl = if (turno.primerControl) 1 else 0, comentario = turno.comentario,
        )
        val r = decodificar(TobbTurnosResponse.serializer(), turnos.tobbPost("turnos", cuerpo(TobbNuevoTurno.serializer(), cuerpo)))
        val creados = cachearTurnos(turno.medicoId, r.turnos)
        val primero = creados.firstOrNull() ?: throw TobbException(500, null, "turnosonlinebb no devolvió el turno creado")
        ResultadoTurno.Ok(primero, creados.getOrNull(1))
    }

    suspend fun sobreturno(turno: Turno): ResultadoTurno = intentar {
        val pacienteId = turno.pacienteId ?: throw TobbException(422, "datos", "Falta el paciente del sobreturno")
        val cuerpo = TobbNuevoTurno(
            medicoId = numeroMedico(turno.medicoId), consultorioId = numeroConsultorio(turno.consultorioId),
            pacienteId = pacienteTobbId(pacienteId, turno.medicoId, turno.consultorioId),
            fecha = turno.fecha.toString(), horario = turno.horario.aTextoTobb(), tipoTurno = turno.codigoDeTipo, comentario = turno.comentario,
        )
        val r = decodificar(TobbTurnoResponse.serializer(), turnos.tobbPost("turnos/sobreturno", cuerpo(TobbNuevoTurno.serializer(), cuerpo)))
        val creado = r.turno?.let { cachearTurnos(turno.medicoId, listOf(it)).firstOrNull() } ?: throw TobbException(500, null, "turnosonlinebb no devolvió el sobreturno")
        ResultadoTurno.Ok(creado)
    }

    /**
     * Bloquea un horario registrando un turno para el paciente ficticio de bloqueo (DNI 99999), como hace la web.
     * La API rechaza un segundo bloqueo el mismo día con `mismo_dia`; en ese caso hay que usar "bloquear día".
     * [tipoCodigo] es el tipo del horario que se bloquea: el de tipo 1 no viaja (turnos lo usa por omisión), el resto sí.
     */
    suspend fun bloquearHorario(
        medicoId: Id, consultorioId: Id, fecha: LocalDate, horario: LocalTime, tipoCodigo: Int = CODIGO_TIPO_CONSULTA,
    ): ResultadoTurno = intentar {
        val cuerpo = TobbNuevoTurno(
            medicoId = numeroMedico(medicoId), consultorioId = numeroConsultorio(consultorioId), pacienteId = pacienteBloqueo(),
            fecha = fecha.toString(), horario = horario.aTextoTobb(), tipoTurno = tipoCodigo, comentario = "Bloqueado desde Salud 360",
        )
        val r = decodificar(TobbTurnosResponse.serializer(), turnos.tobbPost("turnos", cuerpo(TobbNuevoTurno.serializer(), cuerpo)))
        val creado = cachearTurnos(medicoId, r.turnos).firstOrNull() ?: throw TobbException(500, null, "turnosonlinebb no devolvió el bloqueo")
        ResultadoTurno.Ok(creado)
    }

    /** Bloquea el día completo. Devuelve null si salió bien o el mensaje de error. */
    suspend fun bloquearDia(medicoId: Id, consultorioId: Id, fecha: LocalDate): String? = try {
        val cuerpo = mapOf(
            "medico_id" to numeroMedico(medicoId), "consultorio_id" to numeroConsultorio(consultorioId),
            "fecha" to fecha.toString(), "paciente_id" to pacienteBloqueo(),
        )
        val r = decodificar(TobbTurnosResponse.serializer(), turnos.tobbPost("turnos/bloquear-dia", cuerpo))
        guardarTurnosDelDia(medicoId, consultorioId, fecha, r.turnos)
        null
    } catch (e: TobbException) {
        if (e.codigo == "dia_con_turnos") "No se puede bloquear: hay pacientes con turno ese día" else e.message
    } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error de la API
    } catch (e: Exception) {
        log.w(e) { "No se pudo bloquear el día" }; "Sin conexión con turnosonlinebb: ${e.message}"
    }

    suspend fun cancelar(turnoId: Id, medicoId: Id, motivo: String = "") {
        val n = numeroTurno(turnoId)
        val r = decodificar(TobbTurnoResponse.serializer(), turnos.tobbPost("turnos/$n/cancelar", mapOf("motivo" to motivo)))
        r.turno?.let { cachearTurnos(medicoId, listOf(it)) } ?: marcarCanceladoLocal(turnoId)
    }

    suspend fun asistencia(turnoId: Id, medicoId: Id, a: Asistencia) = accionSobreTurno(turnoId, medicoId, "asistencia", mapOf("asistio" to a.aCodigoTobb()))
    suspend fun caja(turnoId: Id, medicoId: Id, valor: Double) = accionSobreTurno(turnoId, medicoId, "caja", mapOf("caja" to valor))
    suspend fun comentario(turnoId: Id, medicoId: Id, texto: String) = accionSobreTurno(turnoId, medicoId, "comentario", mapOf("comentario" to texto))

    private suspend fun accionSobreTurno(turnoId: Id, medicoId: Id, accion: String, cuerpo: Map<String, Any?>) {
        val r = decodificar(TobbTurnoResponse.serializer(), turnos.tobbPost("turnos/${numeroTurno(turnoId)}/$accion", cuerpo))
        r.turno?.let { cachearTurnos(medicoId, listOf(it)) }
    }

    // ------------------------------------------------------------------
    // Horarios fijos y fechas especiales (plantilla semanal del médico)
    // ------------------------------------------------------------------

    /**
     * Trae de turnosonlinebb (`GET horarios`) la plantilla semanal y las fechas especiales futuras del médico en ese
     * consultorio y las deja en la caché local (`horario` con ids `tobb-h…`, `fecha_agregada` con `tobb-f…`).
     * Los importados que ya no vienen (borrados desde la web) se desactivan.
     */
    suspend fun sincronizarHorarios(medicoId: Id, consultorioId: Id) {
        val r = decodificar(TobbHorariosResponse.serializer(), turnos.tobbGet("horarios", params(medicoId, consultorioId)))
        aprenderNombres(r.nombresDeTipos())
        val ahora = ahoraMillis()
        val fijos = r.horariosFijos.mapNotNull { it.toHorario(medicoId, consultorioId) }
        val especiales = r.fechasEspeciales.mapNotNull { it.toFechaAgregada(medicoId, consultorioId) }
        val idsFijos = fijos.map { it.id }.toSet()
        val idsEspeciales = especiales.map { it.id }.toSet()
        val localesFijos = q.horariosDeMedico(medicoId).lista { it.toModel() }.filter { it.consultorioId == consultorioId }
        val localesEspeciales = q.fechasAgregadasDeMedico(medicoId, hoy().toString()).lista { it.toModel() }.filter { it.consultorioId == consultorioId }
        db.transaction {
            localesFijos.filter { TobbIds.esTobb(it.id) && it.id !in idsFijos }.forEach { q.upsertHorario(it.copy(activo = false).toRow(ahora, dirty = false)) }
            fijos.forEach { q.upsertHorario(it.toRow(ahora, dirty = false)) }
            localesEspeciales.filter { TobbIds.esTobb(it.id) && it.id !in idsEspeciales }.forEach { q.upsertFechaAgregada(it.copy(activo = false).toRow(ahora, dirty = false)) }
            especiales.forEach { q.upsertFechaAgregada(it.toRow(ahora, dirty = false)) }
        }
        log.d { "Horarios de $medicoId/$consultorioId: ${fijos.size} fijos, ${especiales.size} fechas especiales" }
    }

    /** Alta de un horario fijo en la web (`POST horarios`). Devuelve false si ya existía (`duplicado`). */
    suspend fun agregarHorario(h: HorarioMedico): Boolean {
        val cuerpo = mapOf(
            "medico_id" to numeroMedico(h.medicoId), "consultorio_id" to numeroConsultorio(h.consultorioId),
            "dia" to h.dia.isoDayNumber, "horario" to h.horario.aTextoTobb(), "tipo_turno" to h.codigoDeTipo,
            "valido_desde" to h.validoDesde?.toString(), "valido_hasta" to h.validoHasta?.toString(),
            "quincenal" to if (h.quincenal) 1 else 0,
        )
        val r = try {
            decodificar(TobbAltaResponse.serializer(), turnos.tobbPost("horarios", cuerpo))
        } catch (e: TobbException) {
            if (e.codigo == "duplicado") return false else throw e
        }
        q.upsertHorario(h.copy(id = TobbIds.horario(r.id)).toRow(ahoraMillis(), dirty = false))
        return true
    }

    /** Vigencia (y quincenal, cuando la API lo acepte) de un horario fijo de la web (`PUT horarios/{id}`); el resto queda en caché. */
    suspend fun guardarHorario(h: HorarioMedico) {
        val cuerpo = mapOf("valido_desde" to (h.validoDesde?.toString() ?: ""), "valido_hasta" to (h.validoHasta?.toString() ?: ""), "quincenal" to if (h.quincenal) 1 else 0)
        turnos.tobbPut("horarios/${numeroHorario(h.id)}", cuerpo)
        q.upsertHorario(h.toRow(ahoraMillis(), dirty = false))
    }

    suspend fun eliminarHorario(h: HorarioMedico) {
        try { turnos.tobbDelete("horarios/${numeroHorario(h.id)}") } catch (e: TobbException) { if (e.codigo != "no_encontrado") throw e }
        q.upsertHorario(h.copy(activo = false).toRow(ahoraMillis(), dirty = false))
    }

    suspend fun agregarFechaEspecial(f: FechaAgregada) {
        val cuerpo = mapOf(
            "medico_id" to numeroMedico(f.medicoId), "consultorio_id" to numeroConsultorio(f.consultorioId),
            "fecha" to f.fecha.toString(), "horarios" to f.horarios.map { it.aTextoTobb() },
        )
        val r = decodificar(TobbAltaResponse.serializer(), turnos.tobbPost("horarios/fecha-especial", cuerpo))
        q.upsertFechaAgregada(f.copy(id = TobbIds.fechaAgregada(r.fechaEspecialId)).toRow(ahoraMillis(), dirty = false))
    }

    suspend fun eliminarFechaEspecial(f: FechaAgregada) {
        try { turnos.tobbDelete("horarios/fecha-especial/${numeroFechaEspecial(f.id)}") } catch (e: TobbException) { if (e.codigo != "no_encontrado") throw e }
        q.upsertFechaAgregada(f.copy(activo = false).toRow(ahoraMillis(), dirty = false))
    }

    // ------------------------------------------------------------------
    // Configuración (cupo de primeros controles, ventana de reservas) y mensajes
    // ------------------------------------------------------------------

    /**
     * Refresca desde turnosonlinebb el cupo de primeros controles y la ventana de días (`GET horarios`) y los mensajes
     * para pacientes (`GET mensajes`, ids `tobb-n…`). El valor de consulta es propio de Salud 360 y se conserva.
     */
    suspend fun sincronizarConfig(medicoId: Id) {
        val n = numeroMedico(medicoId)
        val h = decodificar(TobbHorariosResponse.serializer(), turnos.tobbGet("horarios", mapOf("medico_id" to n)))
        val mensajes = decodificar(TobbMensajesResponse.serializer(), turnos.tobbGet("mensajes", mapOf("medico_id" to n))).mensajes
        val ahora = ahoraMillis()
        val previa = q.configDeMedico(medicoId).uno { it.toModel() }
        val cupo = h.cupoPrimerControl.filter { it.dia in 1..7 }.associate { DayOfWeek(it.dia) to it.cantidad }
        val config = ConfigAgenda(medicoId, ventanaDias = h.ventanaDias, valorConsulta = previa?.valorConsulta ?: 0.0, cupoPrimerControl = cupo)
        val importados = mensajes.map { MensajeEspecial(TobbIds.mensaje(it.id), medicoId, it.titulo, it.descripcion, it.validoDesde?.aFechaTobb(), it.validoHasta?.aFechaTobb(), it.activo == 1) }
        val ids = importados.map { it.id }.toSet()
        val locales = q.mensajesDeMedico(medicoId).lista { it.toModel() }
        db.transaction {
            q.upsertConfigAgenda(config.toRow(ahora, dirty = false))
            locales.filter { TobbIds.esTobb(it.id) && it.id !in ids }.forEach { q.upsertMensajeEspecial(it.toRow(ahora, deleted = true, dirty = false)) }
            importados.forEach { q.upsertMensajeEspecial(it.toRow(ahora, dirty = false)) }
        }
        log.d { "Config de $medicoId: ventana ${h.ventanaDias}, cupo ${cupo.size} días, ${importados.size} mensajes" }
    }

    /** Guarda en la web la ventana de días (`PUT config/ventana-dias`) y el cupo por día (`PUT config/primer-control`); el resto queda en caché. */
    suspend fun guardarConfig(c: ConfigAgenda) {
        val n = numeroMedico(c.medicoId)
        turnos.tobbPut("config/ventana-dias", mapOf("medico_id" to n, "dias" to c.ventanaDias.coerceIn(1, 730)))
        val dias = DayOfWeek.entries.map { d -> mapOf("dia" to d.isoDayNumber, "cantidad" to (c.cupoPrimerControl[d] ?: 0)) }
        turnos.tobbPut("config/primer-control", mapOf("medico_id" to n, "dias" to dias))
        q.upsertConfigAgenda(c.toRow(ahoraMillis(), dirty = false))
    }

    /**
     * Crea (`POST mensajes`) o actualiza (`PUT mensajes/{id}`) un mensaje para pacientes en la web y lo deja en caché.
     * Devuelve el mensaje con el id de la web (`tobb-n…`).
     */
    suspend fun guardarMensaje(m: MensajeEspecial): MensajeEspecial {
        val campos = mapOf(
            "titulo" to m.titulo.trim(), "descripcion" to m.descripcion.trim(),
            "valido_desde" to (m.validoDesde?.toString() ?: ""), "valido_hasta" to (m.validoHasta?.toString() ?: ""), "activo" to if (m.activo) 1 else 0,
        )
        val n = TobbIds.numero(m.id)
        val guardado = sinRutaEnLaWeb("editar mensajes") {
            if (n != null) {
                turnos.tobbPut("mensajes/$n", campos); m
            } else {
                val r = decodificar(TobbAltaResponse.serializer(), turnos.tobbPost("mensajes", campos + ("medico_id" to numeroMedico(m.medicoId))))
                m.copy(id = TobbIds.mensaje(r.id))
            }
        }
        val ahora = ahoraMillis()
        db.transaction {
            if (guardado.id != m.id) q.upsertMensajeEspecial(m.toRow(ahora, deleted = true, dirty = false))
            q.upsertMensajeEspecial(guardado.toRow(ahora, dirty = false))
        }
        return guardado
    }

    suspend fun eliminarMensaje(m: MensajeEspecial) {
        TobbIds.numero(m.id)?.let { n ->
            sinRutaEnLaWeb("eliminar mensajes") { try { turnos.tobbDelete("mensajes/$n") } catch (e: TobbException) { if (e.codigo != "no_encontrado") throw e } }
        }
        q.upsertMensajeEspecial(m.toRow(ahoraMillis(), deleted = true, dirty = false))
    }

    /**
     * Un 404 o 405 sin `codigo` de la API significa que la ruta no existe en el servidor de turnosonlinebb (la web
     * todavía no se actualizó con esa función); se traduce a un mensaje entendible en lugar del HTML de Laravel.
     */
    private suspend fun <T> sinRutaEnLaWeb(funcion: String, bloque: suspend () -> T): T = try {
        bloque()
    } catch (e: TobbException) {
        if (e.codigo == null && (e.status == 404 || e.status == 405)) throw TobbException(e.status, "sin_ruta", "La web de turnos todavía no permite $funcion desde la app (falta actualizar turnosonlinebb).")
        throw e
    }

    // ------------------------------------------------------------------
    // Panel de administración
    // ------------------------------------------------------------------

    /**
     * Trae de turnosonlinebb lo que muestra el panel de administración y lo deja en la base del
     * dispositivo: médicos, consultorios y especialidades del `catalogos`, y los usuarios y las
     * secretarias de sus dos endpoints.
     *
     * Estas tablas las llenaba el servidor propio de Salud 360, que no está desplegado, así que el
     * panel se veía vacío. Turnos ya es el dueño de estos datos, así que se leen de ahí. Todo entra
     * con ids `tobb-…` y `dirty = false`: es una copia para mostrar, no algo que la app edite.
     *
     * Los dos últimos endpoints son solo para el administrador; si el que pregunta no lo es, se
     * guardan igual el catálogo y se deja constancia en el log.
     */
    /**
     * Da de alta en turnos a quien va a usar la app, y vuelve a traer la administración para que
     * aparezca en las listas con los números que le asignó turnos.
     *
     * El alta va allá y no a la base del dispositivo porque **la identidad es de turnosonlinebb**:
     * esa es la contraseña con la que se entra, y la historia clínica resuelve la sesión contra ese
     * sistema. Un usuario creado solo acá no podría iniciar sesión en ningún lado.
     *
     * El médico que solo usa historia clínica va con `mostrarEnTurnos = false`: queda activo, que es
     * lo que le permite entrar, pero no aparece en las listas para pedirle turno.
     *
     * Devuelve el usuario ya guardado en el dispositivo.
     */
    suspend fun crearUsuario(alta: TobbAltaUsuario): Usuario {
        val r = decodificar(TobbAltaUsuarioResponse.serializer(), turnos.tobbPost("usuarios", cuerpo(TobbAltaUsuario.serializer(), alta)))
        sincronizarAdministracion()
        val creado = r.usuario
        return Usuario(
            id = TobbIds.usuario(creado.id),
            email = creado.email,
            nombre = creado.nombre,
            apellido = creado.apellido,
            rol = runCatching { Rol.valueOf(creado.rol.uppercase()) }.getOrNull() ?: Rol.MEDICO,
        )
    }

    suspend fun sincronizarAdministracion() {
        val catalogo = decodificar(TobbCatalogosResponse.serializer(), turnos.tobbGet("catalogos"))
        val usuarios = runCatching { decodificar(TobbUsuariosResponse.serializer(), turnos.tobbGet("usuarios")).usuarios }
            .onFailure { log.w { "no se pudieron traer los usuarios de turnos: ${it.message}" } }
            .getOrDefault(emptyList())
        val secretarias = runCatching { decodificar(TobbSecretariasResponse.serializer(), turnos.tobbGet("secretarias")).secretarias }
            .onFailure { log.w { "no se pudieron traer las secretarias de turnos: ${it.message}" } }
            .getOrDefault(emptyList())
        val ahora = ahoraMillis()

        // El usuario de cada médico, para no dejar el vínculo vacío: el catálogo no lo informa.
        val usuarioDeMedico = usuarios.filter { it.medicoId != null }.associate { it.medicoId!! to it.id }
        // La copia del panel se reescribe entera: para lo que turnos no informa hay que partir del estado que ya tenía
        // el dispositivo (si no, «visible» vuelve a `true` en cada sincronización y el interruptor no se queda).
        val previos = aq.medicos().lista { it.toModel() }.associateBy { it.id }

        db.transaction {
            catalogo.consultorios.forEach {
                q.upsertConsultorio(Consultorio(TobbIds.consultorio(it.id), it.nombre, it.direccion, it.telefono).toRow(ahora, dirty = false))
            }
            catalogo.especialidades.forEach {
                aq.upsertEspecialidad(EspecialidadTurnos(TobbIds.especialidad(it.id), it.nombre, it.color).toRow(ahora, dirty = false))
            }
            catalogo.medicos.forEach { m ->
                val id = TobbIds.medico(m.id)
                aq.upsertMedico(
                    Medico(
                        id = id,
                        usuarioId = usuarioDeMedico[m.id]?.let { TobbIds.usuario(it) }.orEmpty(),
                        nombre = m.nombre, apellido = m.apellido, telefono = m.telefono, mail = m.mail,
                        sexo = m.sexo.orEmpty(), foto = m.foto,
                        consultorioId = TobbIds.consultorio(m.consultorioId),
                        especialidadId = TobbIds.especialidad(m.especialidadId),
                        especialidadesHc = m.historiasClinicas,
                        // Tiene agenda y es visible lo que informa turnos (decisión del administrador); sin esos
                        // campos rige `tiene_horarios` y el valor previo del dispositivo. Ver `AgendaDelMedico`.
                        tieneTurnos = tieneAgendaDe(m.tieneAgenda, m.tieneHorarios),
                        visibleEnTurnos = visibleDe(m.visibleEnTurnos, previos[id]?.visibleEnTurnos),
                        activo = m.activo == 1,
                    ).toRow(ahora, dirty = false),
                )
            }
            // `catalogos` no trae a los médicos desactivados pero `usuarios` sí (con su `medico_id`): se los deja en el
            // dispositivo, inactivos, para que el administrador pueda reactivarlos desde la pestaña Usuarios.
            val enCatalogo = catalogo.medicos.map { it.id }.toSet()
            usuarios.forEach { u ->
                val n = u.medicoId?.takeIf { it !in enCatalogo } ?: return@forEach
                val id = TobbIds.medico(n)
                val usuarioId = TobbIds.usuario(u.id)
                val previo = previos[id]
                val inactivo = if (previo != null) {
                    previo.copy(usuarioId = usuarioId, activo = false)
                } else {
                    val partes = partirNombre(u.nombre)
                    Medico(id = id, usuarioId = usuarioId, nombre = partes.first, apellido = partes.second, activo = false)
                }
                aq.upsertMedico(inactivo.toRow(ahora, dirty = false))
            }
            usuarios.forEach { u ->
                val rol = runCatching { Rol.valueOf(u.rol.uppercase()) }.getOrNull() ?: return@forEach
                // Turnos guarda un nombre solo; se parte para mostrarlo como el resto de la app.
                val partes = partirNombre(u.nombre)
                aq.upsertUsuario(
                    Usuario(
                        id = TobbIds.usuario(u.id),
                        email = u.email,
                        nombre = partes.first,
                        apellido = partes.second,
                        rol = rol,
                        activo = u.activo == 1,
                    ).toRow(ahora, dirty = false),
                )
            }
            secretarias.forEach { s ->
                aq.upsertSecretaria(
                    Secretaria(
                        id = TobbIds.secretaria(s.id),
                        usuarioId = TobbIds.usuario(s.userId),
                        nombre = s.nombre, apellido = s.apellido,
                        consultorioIds = s.consultorios.map { TobbIds.consultorio(it) },
                    ).toRow(ahora, dirty = false),
                )
            }
        }
        log.d {
            "Panel: ${catalogo.medicos.size} médicos, ${catalogo.consultorios.size} consultorios, " +
                "${catalogo.especialidades.size} especialidades, ${usuarios.size} usuarios, ${secretarias.size} secretarias"
        }
    }

    /** Turnos guarda un nombre solo: la primera palabra es el nombre y el resto el apellido (como se muestra en el resto de la app). */
    private fun partirNombre(completo: String): Pair<String, String> {
        val partes = completo.trim().split(" ", limit = 2)
        return partes.firstOrNull().orEmpty() to partes.getOrNull(1).orEmpty()
    }

    /**
     * Activa o desactiva a un médico en turnos (`PUT medicos/{n}/activo`, solo administrador) y recién después de la
     * confirmación escribe la copia del dispositivo con el estado final que informó turnos, sin `dirty` (el dueño del
     * dato es turnos). En turnos el «activo» del usuario es el del médico, así que se actualizan los dos: el
     * [Medico.activo] y el [Usuario.activo] del usuario vinculado ([Medico.usuarioId]), tocando solo ese campo.
     *
     * Si el médico o su usuario no están en el dispositivo no se inventa ninguna ficha y se devuelve igual el estado.
     * Un `200` que no informa en qué quedó el médico no se toma como confirmación.
     */
    suspend fun guardarActivoMedico(medicoId: Id, activo: Boolean): Boolean {
        val n = numeroMedico(medicoId)
        val r = sinRutaEnLaWeb("activar o desactivar a un médico") {
            val obj = turnos.tobbPut("medicos/$n/activo", cuerpo(TobbMedicoActivoRequest.serializer(), TobbMedicoActivoRequest(activo)))
            try {
                decodificar(TobbMedicoActivoResponse.serializer(), obj)
            } catch (e: SerializationException) {
                throw TobbException(500, "respuesta", "turnosonlinebb confirmó el cambio pero no informó si el médico quedó activo.")
            }
        }
        val previo = aq.medicoPorId(TobbIds.medico(n)).uno { it.toModel() }
        if (previo != null) {
            val usuario = previo.usuarioId.takeIf { it.isNotBlank() }?.let { aq.usuarioPorId(it).uno { fila -> fila.toModel() } }
            val ahora = ahoraMillis()
            db.transaction {
                aq.upsertMedico(previo.copy(activo = r.activo).toRow(ahora, dirty = false))
                usuario?.let { aq.upsertUsuario(it.copy(activo = r.activo).toRow(ahora, dirty = false)) }
            }
        }
        log.d { "Médico $medicoId en turnos: activo ${r.activo}" }
        return r.activo
    }

    /**
     * Guarda en turnos (`PUT medicos/{n}/agenda`, solo administrador) la agenda y/o la visibilidad de un médico, y recién
     * después de la confirmación escribe la copia del dispositivo con el estado final que informó turnos, sin `dirty`
     * (el dueño del dato es turnos: `SyncEngine` no tiene que empujarlo). [tieneAgenda] y [visible] son lo que se toca;
     * `null` = no se manda, así un administrador no pisa con un valor viejo el campo que cambió el otro.
     *
     * Lo que decide qué sale (apagar la agenda apaga «visible», encender «visible» exige agenda) está en
     * `AgendaDelMedico.cambioDeAgenda`; acá solo se envía y se confirma. Si el médico local no está, no se escribe nada
     * y se devuelve igual lo que contestó turnos. No toca las historias clínicas del médico ni su licencia (RF-16).
     *
     * Devuelve el estado final con los `turnos_futuros` que cuenta turnos (para el aviso de RF-18). Un `200` que no
     * informa en qué quedó la agenda no se toma como confirmación.
     */
    suspend fun guardarAgenda(medicoId: Id, tieneAgenda: Boolean?, visible: Boolean?): AgendaGuardada {
        val n = numeroMedico(medicoId)
        val r = sinRutaEnLaWeb("cambiar la agenda de un médico") {
            val pedido = TobbAgendaRequest(tieneAgenda = tieneAgenda, visibleEnTurnos = visible)
            val obj = turnos.tobbPut("medicos/$n/agenda", cuerpo(TobbAgendaRequest.serializer(), pedido))
            try {
                decodificar(TobbAgendaResponse.serializer(), obj)
            } catch (e: SerializationException) {
                throw TobbException(500, "respuesta", "turnosonlinebb confirmó el cambio pero no informó cómo quedó la agenda del médico.")
            }
        }
        val id = TobbIds.medico(n)
        val previo = aq.medicoPorId(id).uno { it.toModel() }
        if (previo != null) {
            aq.upsertMedico(previo.copy(tieneTurnos = r.tieneAgenda, visibleEnTurnos = r.visibleEnTurnos).toRow(ahoraMillis(), dirty = false))
        }
        log.d { "Agenda de $medicoId en turnos: agenda ${r.tieneAgenda}, visible ${r.visibleEnTurnos}, ${r.turnosFuturos} turnos futuros" }
        return AgendaGuardada(tieneAgenda = r.tieneAgenda, visible = r.visibleEnTurnos, turnosFuturos = r.turnosFuturos)
    }

    /**
     * Guarda en turnos (`PUT medicos/{n}/historias-clinicas`, solo administrador) la lista EXACTA de historias clínicas
     * habilitadas de un médico (`[]` = ninguna) y recién después de la confirmación escribe la copia del dispositivo.
     * Antes solo se escribía en el dispositivo: turnos no se enteraba y al volver a entrar pisaba el cambio.
     *
     * La copia toma la lista que informó turnos (puede venir en otro orden o distinta de la pedida), sin `dirty`, y no
     * toca ningún otro campo del médico. Si el médico local no está, no se inventa la ficha y se devuelve igual la lista.
     * Un `200` que no informa la lista no se toma como confirmación.
     */
    suspend fun guardarHistoriasClinicas(medicoId: Id, codigos: List<String>): List<String> {
        val n = numeroMedico(medicoId)
        val r = sinRutaEnLaWeb("cambiar las historias clínicas de un médico") {
            val obj = turnos.tobbPut("medicos/$n/historias-clinicas", cuerpo(TobbHistoriasClinicasRequest.serializer(), TobbHistoriasClinicasRequest(codigos)))
            try {
                decodificar(TobbHistoriasClinicasResponse.serializer(), obj)
            } catch (e: SerializationException) {
                throw TobbException(500, "respuesta", "turnosonlinebb confirmó el cambio pero no informó qué historias clínicas quedaron habilitadas.")
            }
        }
        val previo = aq.medicoPorId(TobbIds.medico(n)).uno { it.toModel() }
        if (previo != null) {
            aq.upsertMedico(previo.copy(especialidadesHc = r.historiasClinicas).toRow(ahoraMillis(), dirty = false))
        }
        log.d { "Historias clínicas de $medicoId en turnos: ${r.historiasClinicas}" }
        return r.historiasClinicas
    }

    // ------------------------------------------------------------------
    // Tipos de turno (solo administrador; sin copia local: turnos es el dueño del dato)
    // ------------------------------------------------------------------

    /** Catálogo global de tipos de turno (`GET tipos-turno`), con los inactivos. No se guarda en el dispositivo. */
    suspend fun tiposDeTurno(): List<TipoTurnoCatalogo> {
        val r = sinRutaEnLaWeb("ver los tipos de turno") {
            val obj = turnos.tobbGet("tipos-turno")
            try {
                decodificar(TobbTiposTurnoResponse.serializer(), obj)
            } catch (e: SerializationException) {
                throw TobbException(500, "respuesta", "turnosonlinebb respondió pero no informó el catálogo de tipos de turno.")
            }
        }
        return r.tipos.map { it.toCatalogo() }
    }

    /**
     * Crea un tipo en el catálogo (`POST tipos-turno`, solo administrador) y devuelve el que creó turnos. Un nombre
     * repetido (`409 nombre_repetido`) o inválido (`422`) lanza [TobbException] con el mensaje de turnos.
     */
    suspend fun crearTipoTurno(nombre: String): TipoTurnoCatalogo {
        val r = sinRutaEnLaWeb("crear tipos de turno") {
            val obj = turnos.tobbPost("tipos-turno", cuerpo(TobbCrearTipoTurnoRequest.serializer(), TobbCrearTipoTurnoRequest(nombre.trim())))
            try {
                decodificar(TobbCrearTipoTurnoResponse.serializer(), obj)
            } catch (e: SerializationException) {
                throw TobbException(500, "respuesta", "turnosonlinebb confirmó el alta pero no informó el tipo de turno creado.")
            }
        }
        log.d { "Tipo de turno creado en turnos: ${r.tipo.id}" }
        return r.tipo.toCatalogo()
    }

    /** Tipos habilitados de un médico, con su condición y sus opciones (`GET medicos/{n}/tipos-turno`, solo administrador). */
    suspend fun tiposDelMedico(medicoId: Id): List<TipoTurnoDelMedico> {
        val n = numeroMedico(medicoId)
        val r = sinRutaEnLaWeb("ver los tipos de turno de un médico") {
            val obj = turnos.tobbGet("medicos/$n/tipos-turno")
            try {
                decodificar(TobbMedicoTiposResponse.serializer(), obj)
            } catch (e: SerializationException) {
                throw TobbException(500, "respuesta", "turnosonlinebb respondió pero no informó los tipos de turno del médico.")
            }
        }
        return r.tipos.map { it.toTipoDelMedico() }
    }

    /**
     * Guarda en turnos (`PUT medicos/{n}/tipos-turno`, solo administrador) la lista EXACTA de tipos habilitados de un
     * médico (`[]` = ninguno) con su condición y, donde [ConfigTipoTurno.opciones] no es `null`, sus opciones. No hay
     * copia local que escribir: devuelve lo que quedó según turnos y sus avisos (un tipo deshabilitado que todavía tiene
     * horarios no es un error). Un `200` que no informa los tipos no se toma como confirmación.
     */
    suspend fun guardarTiposDelMedico(medicoId: Id, config: List<ConfigTipoTurno>): GuardadoTipos {
        val n = numeroMedico(medicoId)
        val r = sinRutaEnLaWeb("cambiar los tipos de turno de un médico") {
            val pedido = TobbMedicoTiposRequest(config.map { TobbTipoConfig(it.id, it.condicion.codigo, it.opciones) })
            val obj = turnos.tobbPut("medicos/$n/tipos-turno", cuerpo(TobbMedicoTiposRequest.serializer(), pedido))
            try {
                decodificar(TobbMedicoTiposResponse.serializer(), obj)
            } catch (e: SerializationException) {
                throw TobbException(500, "respuesta", "turnosonlinebb confirmó el cambio pero no informó qué tipos de turno quedaron habilitados.")
            }
        }
        log.d { "Tipos de turno de $medicoId en turnos: ${r.tipos.map { it.id }}, ${r.avisos.size} avisos" }
        return GuardadoTipos(r.tipos.map { it.toTipoDelMedico() }, r.avisos)
    }

    // ------------------------------------------------------------------
    // Obras sociales
    // ------------------------------------------------------------------

    /**
     * Trae el catálogo de obras sociales (`GET catalogos`) y las del médico (`GET obras-sociales`) y los deja en la
     * caché local (`obra_social` con ids `tobb-o…`, `obra_social_medico` con `tobb-v…`).
     */
    suspend fun sincronizarObrasSociales(medicoId: Id) {
        val catalogo = decodificar(TobbCatalogosResponse.serializer(), turnos.tobbGet("catalogos")).obrasSociales
        val delMedico = decodificar(TobbObrasSocialesMedicoResponse.serializer(), turnos.tobbGet("obras-sociales", mapOf("medico_id" to numeroMedico(medicoId)))).obrasSociales
        val ahora = ahoraMillis()
        // Por si el catálogo no trae alguna que sí está vinculada al médico.
        val nombres = (catalogo.map { it.id to it.nombre } + delMedico.map { it.obraSocialId to it.nombre }).toMap()
        val vinculos = delMedico.map { ObraSocialMedico(TobbIds.obraSocialMedico(it.id), medicoId, TobbIds.obraSocial(it.obraSocialId), it.importe, it.importeReserva ?: 0.0, it.activo == 1) }
        val idsVinculos = vinculos.map { it.id }.toSet()
        val localesVinculos = q.obrasSocialesDeMedico(medicoId).lista { it.toModel() }
        db.transaction {
            nombres.forEach { (n, nombre) -> q.upsertObraSocial(ObraSocial(TobbIds.obraSocial(n), nombre.trim().uppercase()).toRow(ahora, dirty = false)) }
            localesVinculos.filter { TobbIds.esTobb(it.id) && it.id !in idsVinculos }.forEach { q.upsertObraSocialMedico(it.toRow(ahora, deleted = true, dirty = false)) }
            vinculos.forEach { q.upsertObraSocialMedico(it.toRow(ahora, dirty = false)) }
        }
        log.d { "Obras sociales de $medicoId: ${nombres.size} en catálogo, ${vinculos.size} vinculadas" }
    }

    /**
     * Crea o actualiza el vínculo médico–obra social en la web (`POST obras-sociales`: activo e importe). La API no
     * recibe el importe de reserva, que queda solo en la caché local. Devuelve el vínculo con el id de la web.
     */
    suspend fun guardarObraSocialMedico(o: ObraSocialMedico): ObraSocialMedico {
        val osId = TobbIds.numero(o.obraSocialId) ?: throw TobbException(400, "obra_social", "La obra social ${o.obraSocialId} no pertenece a turnosonlinebb")
        val cuerpo = mapOf("medico_id" to numeroMedico(o.medicoId), "obra_social_id" to osId, "activo" to if (o.activo) 1 else 0, "importe" to o.importe)
        val r = decodificar(TobbAltaResponse.serializer(), turnos.tobbPost("obras-sociales", cuerpo))
        val guardado = o.copy(id = TobbIds.obraSocialMedico(r.id))
        val ahora = ahoraMillis()
        db.transaction {
            // Si el vínculo existía con otro id (creado localmente antes) se reemplaza por el de la web.
            if (guardado.id != o.id) q.upsertObraSocialMedico(o.toRow(ahora, deleted = true, dirty = false))
            q.upsertObraSocialMedico(guardado.toRow(ahora, dirty = false))
        }
        return guardado
    }

    // ------------------------------------------------------------------
    // Pacientes
    // ------------------------------------------------------------------

    /** Busca pacientes en la base de turnosonlinebb (DNI, apellido, nombre o mail) y los guarda en la base local. */
    suspend fun buscarPacientes(texto: String): List<Paciente> {
        val t = texto.trim()
        if (t.length < 2) return emptyList()
        val r = decodificar(TobbPacientesResponse.serializer(), turnos.tobbGet("pacientes/buscar", mapOf("q" to t)))
        val ahora = ahoraMillis()
        val pacientes = r.pacientes.filter { it.dni.texto() != TOBB_DNI_BLOQUEO }.map { it.toPaciente() }
        db.transaction { pacientes.forEach { guardarPacienteLocal(it, ahora) } }
        return pacientes
    }

    /**
     * Trae de turnosonlinebb todos los pacientes ya vinculados a este médico (`pacientes/vinculados`) y los deja
     * guardados y vinculados en la base local, para que la pestaña de pacientes los muestre sin tener que buscar.
     */
    suspend fun pacientesVinculados(medicoId: Id): List<Paciente> {
        val r = decodificar(
            TobbPacientesVinculadosResponse.serializer(),
            turnos.tobbGet("pacientes/vinculados", mapOf("medico_id" to numeroMedico(medicoId), "bloqueados" to 0)),
        )
        val ahora = ahoraMillis()
        val pacientes = r.pacientes.filter { it.dni.texto() != TOBB_DNI_BLOQUEO }.map { it.toPaciente() }
        db.transaction {
            pacientes.forEach { p ->
                guardarPacienteLocal(p, ahora)
                if (pq.vinculo(medicoId, p.id).uno { it } == null) pq.upsertMedicoPaciente(MedicoPaciente(medicoId, p.id).toRow(ahora, dirty = false))
            }
        }
        return pacientes
    }

    /**
     * Id en turnosonlinebb de un paciente de la app. Si el paciente se creó en Salud 360 se lo da de alta en la
     * web (por DNI: si ya existe se reutiliza) y se recuerda el vínculo en `paciente_extra` (turnos / tobb_id).
     */
    suspend fun pacienteTobbId(pacienteId: Id, medicoId: Id, consultorioId: Id?): Long {
        idTobbConocido(pacienteId)?.let { return it }
        return altaPacienteRemoto(pacienteId, medicoId, consultorioId)
    }

    /** Id del paciente en turnosonlinebb si ya se conoce: está en el propio id (`tobb-p…`) o en `paciente_extra`. */
    private suspend fun idTobbConocido(pacienteId: Id): Long? {
        TobbIds.numero(pacienteId)?.let { return it }
        return pq.extrasDePaciente(pacienteId, EXTRA_TURNOS).lista { it.toModel() }
            .firstOrNull { it.clave == EXTRA_TOBB_ID }?.valor?.toLongOrNull()
    }

    /** Ficha local del paciente con el DNI validado, que turnosonlinebb exige numérico. */
    private suspend fun pacienteConDni(pacienteId: Id): Pair<Paciente, String> {
        val p = pq.pacientePorId(pacienteId).uno { it.toModel() } ?: throw TobbException(404, "paciente", "El paciente no existe en este dispositivo")
        val dni = p.dni.trim()
        if (dni.isEmpty() || !dni.all { it.isDigit() }) throw TobbException(422, "datos", "El paciente necesita un DNI numérico para registrarlo en turnosonlinebb")
        return p to dni
    }

    /** Da de alta el paciente en la web (por DNI: si ya existe se reutiliza) y recuerda el vínculo en `paciente_extra`. */
    private suspend fun altaPacienteRemoto(pacienteId: Id, medicoId: Id, consultorioId: Id?): Long {
        val r = enviarAltaPaciente(pacienteId, medicoId, consultorioId)
        anotarIdTobb(pacienteId, r.paciente.id)
        return r.paciente.id
    }

    /** dirty = true: el vínculo viaja por la sincronización normal a los demás dispositivos. */
    private suspend fun anotarIdTobb(pacienteId: Id, idTobb: Long) {
        pq.upsertPacienteExtra(PacienteExtra(pacienteId, EXTRA_TURNOS, EXTRA_TOBB_ID, idTobb.toString()).toRow(ahoraMillis(), dirty = true))
    }

    /** `POST pacientes` con la ficha local. Turnos contesta la ficha final y si la creó (`creado`) o ya existía por DNI. */
    private suspend fun enviarAltaPaciente(pacienteId: Id, medicoId: Id, consultorioId: Id?): TobbPacienteResponse {
        val (p, dni) = pacienteConDni(pacienteId)
        val cuerpo = TobbNuevoPaciente(
            medicoId = numeroMedico(medicoId), consultorioId = consultorioId?.let { TobbIds.numero(it) }, dni = dni, nombre = p.nombre, apellido = p.apellido,
            telefono = p.telefono.ifBlank { null }, mail = p.mail.ifBlank { null }, fechaNacimiento = p.fechaNacimiento?.toString(),
            domicilio = p.domicilio.ifBlank { null }, localidad = p.localidad.ifBlank { null },
            obraSocial = p.obraSocial.ifBlank { null }, numeroAfiliado = p.numeroAfiliado.ifBlank { null }, obraSocialPlan = p.obraSocialPlan.ifBlank { null },
            nota = p.nota.ifBlank { null },
        )
        return decodificar(TobbPacienteResponse.serializer(), turnos.tobbPost("pacientes", cuerpo(TobbNuevoPaciente.serializer(), cuerpo)))
    }

    /**
     * Alta de un paciente nuevo del médico: el `POST pacientes` lo crea en turnos o, si el DNI ya existía, solo lo
     * asocia al médico y devuelve la ficha que turnos ya tenía. Según lo que conteste (ver [decidirTrasAlta]) la ficha
     * local se conserva o se reemplaza por la de turnos, de modo que nunca queden dos filas con el mismo DNI.
     */
    private suspend fun altaPacienteConFusion(pacienteId: Id, medicoId: Id, consultorioId: Id?): PacienteGuardado {
        val r = enviarAltaPaciente(pacienteId, medicoId, consultorioId)
        val remoto = r.paciente.toPaciente()
        val remotaYaEnElDispositivo = remoto.id != pacienteId && pq.pacientePorId(remoto.id).uno { it } != null
        val decision = decidirTrasAlta(pacienteId, remoto.id, r.creado, remotaYaEnElDispositivo)
        val ficha = when (decision.accion) {
            AccionTrasAlta.ANOTAR_ID_REMOTO -> {
                anotarIdTobb(pacienteId, r.paciente.id)
                pq.pacientePorId(pacienteId).uno { it.toModel() } ?: remoto
            }
            AccionTrasAlta.ACTUALIZAR_LOCAL, AccionTrasAlta.REEMPLAZAR_LOCAL -> pacientes.reemplazarPorRemoto(pacienteId, remoto)
        }
        return PacienteGuardado(ficha, decision.yaExistia)
    }

    /**
     * Guarda en turnosonlinebb la ficha que se acaba de editar en la app. Si el paciente ya existe allá se
     * actualiza (`PUT pacientes/{id}`, que solo pisa los campos enviados); si no, se lo da de alta.
     * Sin esto, lo que se edita en la app se pierde en cuanto la web vuelve a informar al paciente.
     *
     * [esAlta] fuerza el `POST` aunque la ficha ya tenga id de turnos: es un alta del médico sobre un DNI que el
     * dispositivo ya tenía de turnos, y lo que asocia al médico es el `POST` (un `PUT` pisaría datos ajenos).
     * Devuelve la ficha que quedó en el dispositivo y si el paciente ya existía en el sistema.
     */
    suspend fun guardarPacienteRemoto(pacienteId: Id, medicoId: Id, consultorioId: Id?, esAlta: Boolean = false): PacienteGuardado {
        val id = idTobbConocido(pacienteId)
        if (id == null || esAlta) return altaPacienteConFusion(pacienteId, medicoId, consultorioId)
        val (p, dni) = pacienteConDni(pacienteId)
        val cuerpo = TobbPacienteEdicion(
            dni = dni, nombre = p.nombre, apellido = p.apellido, telefono = p.telefono, mail = p.mail,
            domicilio = p.domicilio, localidad = p.localidad, obraSocial = p.obraSocial,
            numeroAfiliado = p.numeroAfiliado, obraSocialPlan = p.obraSocialPlan, nota = p.nota,
            fechaNacimiento = p.fechaNacimiento?.toString(),
        )
        turnos.tobbPut("pacientes/$id", cuerpo(TobbPacienteEdicion.serializer(), cuerpo))
        return PacienteGuardado(p, yaExistia = false)
    }

    private suspend fun pacienteBloqueo(): Long {
        pacienteBloqueoId?.let { return it }
        val r = decodificar(TobbPacientesResponse.serializer(), turnos.tobbGet("pacientes/buscar", mapOf("q" to TOBB_DNI_BLOQUEO)))
        val id = r.pacientes.firstOrNull { it.dni.texto() == TOBB_DNI_BLOQUEO }?.id
            ?: throw TobbException(404, "sin_bloqueo", "No existe el paciente de bloqueo (DNI $TOBB_DNI_BLOQUEO) en turnosonlinebb")
        pacienteBloqueoId = id
        return id
    }

    // ------------------------------------------------------------------
    // Caché local
    // ------------------------------------------------------------------

    private suspend fun cachearDia(medicoId: Id, consultorioId: Id, d: TobbAgendaDia): DiaAgendaResuelto {
        val fecha = d.fecha.aFechaTobb() ?: hoy()
        feriados[fecha] = d.esFeriado
        aprenderNombres(d.nombresDeTipos())
        val turnos = guardarTurnosDelDia(medicoId, consultorioId, fecha, d.turnos)
        val dobles = ModuloTurnos.PRIMER_CONTROL_DOBLE in modulos(medicoId)
        return DiaAgendaResuelto(fecha, d.slots.toSlots(turnos, dobles), d.esFeriado, turnos, d.cantidadSobreturnos)
    }

    /** Reemplaza en la caché los turnos del día: los que ya no vienen (cancelados en la web) se marcan cancelados. */
    private suspend fun guardarTurnosDelDia(medicoId: Id, consultorioId: Id, fecha: LocalDate, dtos: List<TobbTurno>): List<Turno> {
        val ahora = ahoraMillis()
        val turnos = dtos.mapNotNull { it.toTurno() }
        val ids = turnos.map { it.id }.toSet()
        val locales = q.turnosDelDia(medicoId, consultorioId, fecha.toString()).lista { it.toModel() }
        db.transaction {
            locales.filter { TobbIds.esTobb(it.id) && it.id !in ids }.forEach { q.upsertTurno(it.copy(estado = EstadoTurno.CANCELADO).toRow(ahora, dirty = false)) }
            turnos.forEach { q.upsertTurno(it.toRow(ahora, dirty = false)) }
            dtos.filter { !it.esBloqueo() }.mapNotNull { it.paciente }.forEach { guardarPacienteResumen(it, medicoId, ahora) }
        }
        return turnos
    }

    private suspend fun cachearTurnos(medicoId: Id, dtos: List<TobbTurno>): List<Turno> {
        aprenderNombres(dtos.nombresDeTipos())
        val ahora = ahoraMillis()
        val turnos = dtos.mapNotNull { it.toTurno() }
        db.transaction {
            turnos.forEach { q.upsertTurno(it.toRow(ahora, dirty = false)) }
            dtos.filter { !it.esBloqueo() }.mapNotNull { it.paciente }.forEach { guardarPacienteResumen(it, medicoId, ahora) }
        }
        return turnos
    }

    private suspend fun marcarCanceladoLocal(turnoId: Id) {
        val t = q.turnoPorId(turnoId).uno { it.toModel() } ?: return
        q.upsertTurno(t.copy(estado = EstadoTurno.CANCELADO).toRow(ahoraMillis(), dirty = false))
    }

    private suspend fun guardarPacienteResumen(p: TobbPacienteResumen, medicoId: Id, ahora: Long) {
        guardarPacienteLocal(p.toPaciente(), ahora)
        val id = TobbIds.paciente(p.id)
        if (pq.vinculo(medicoId, id).uno { it } == null) pq.upsertMedicoPaciente(MedicoPaciente(medicoId, id).toRow(ahora, dirty = false))
    }

    /** Actualiza la ficha local conservando lo que la web no informa (datos de la historia clínica, etc.). */
    private suspend fun guardarPacienteLocal(nuevo: Paciente, ahora: Long) {
        val existente = pq.pacientePorId(nuevo.id).uno { it.toModel() }
        pq.upsertPaciente(fusionarFicha(existente, nuevo).toRow(ahora, dirty = false))
    }

    private suspend fun modulos(medicoId: Id): Set<ModuloTurnos> =
        q.modulosDeMedico(medicoId).lista { it.toModel() }.filterNotNull().filter { it.activo }.map { it.modulo }.toSet()

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun params(medicoId: Id, consultorioId: Id, vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("medico_id" to numeroMedico(medicoId), "consultorio_id" to TobbIds.numero(consultorioId)) + extra

    private fun numeroMedico(id: Id): Long = TobbIds.numero(id) ?: throw TobbException(400, "medico", "El médico $id no pertenece a turnosonlinebb")
    private fun numeroConsultorio(id: Id): Long = TobbIds.numero(id) ?: throw TobbException(400, "consultorio", "El consultorio $id no pertenece a turnosonlinebb")
    private fun numeroTurno(id: Id): Long = TobbIds.numero(id) ?: throw TobbException(400, "turno", "El turno $id no pertenece a turnosonlinebb")
    private fun numeroHorario(id: Id): Long = TobbIds.numero(id) ?: throw TobbException(400, "horario", "El horario $id no pertenece a turnosonlinebb")
    private fun numeroFechaEspecial(id: Id): Long = TobbIds.numero(id) ?: throw TobbException(400, "fecha_especial", "La fecha especial $id no pertenece a turnosonlinebb")

    private fun <T> decodificar(serializer: KSerializer<T>, obj: JsonObject): T = tobbJson.decodeFromJsonElement(serializer, obj)
    private fun <T> cuerpo(serializer: KSerializer<T>, valor: T): JsonObject = tobbJson.encodeToJsonElement(serializer, valor).jsonObject

    /** Traduce los rechazos de la API a [ResultadoTurno]; cualquier otro error queda como [ResultadoTurno.Error]. */
    private suspend fun intentar(bloque: suspend () -> ResultadoTurno): ResultadoTurno = try {
        bloque()
    } catch (e: TobbException) {
        when (e.codigo) {
            "ocupado" -> ResultadoTurno.HorarioOcupado
            "mismo_dia" -> ResultadoTurno.PacienteYaTieneTurnoEseDia
            "cupo_primer_control" -> ResultadoTurno.CupoPrimerControlAgotado
            else -> ResultadoTurno.Error(e.message ?: "turnosonlinebb rechazó la operación")
        }
    } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error de la API
    } catch (e: Exception) {
        log.w(e) { "Error hablando con turnosonlinebb" }
        ResultadoTurno.Error("Sin conexión con turnosonlinebb: ${e.message ?: "error de red"}")
    }

    companion object {
        /** Parámetro que pide los horarios de todos los tipos de turno (menos videollamada); un servidor viejo lo ignora. */
        private val TODOS_LOS_TIPOS = "todos_los_tipos" to 1

        /** Clave de `paciente_extra` donde se guarda el id del paciente en turnosonlinebb. */
        const val EXTRA_TURNOS = "turnos"
        const val EXTRA_TOBB_ID = "tobb_id"
    }
}

fun LocalTime.aTextoTobb(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
