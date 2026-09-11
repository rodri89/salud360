package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.tobb.TOBB_DNI_BLOQUEO
import com.salud360.core.data.network.tobb.TobbAgendaDia
import com.salud360.core.data.network.tobb.TobbAgendaSemana
import com.salud360.core.data.network.tobb.TobbDiasAtencion
import com.salud360.core.data.network.tobb.TobbFechas
import com.salud360.core.data.network.tobb.TobbNuevoPaciente
import com.salud360.core.data.network.tobb.TobbNuevoTurno
import com.salud360.core.data.network.tobb.TobbPaciente
import com.salud360.core.data.network.tobb.TobbPacienteResponse
import com.salud360.core.data.network.tobb.TobbPacienteResumen
import com.salud360.core.data.network.tobb.TobbPacientesResponse
import com.salud360.core.data.network.tobb.TobbTurno
import com.salud360.core.data.network.tobb.TobbTurnoResponse
import com.salud360.core.data.network.tobb.TobbTurnosResponse
import com.salud360.core.data.network.tobb.aFechaTobb
import com.salud360.core.data.network.tobb.aCodigoTobb
import com.salud360.core.data.network.tobb.esBloqueo
import com.salud360.core.data.network.tobb.texto
import com.salud360.core.data.network.tobb.tobbJson
import com.salud360.core.data.network.tobb.toPaciente
import com.salud360.core.data.network.tobb.toSlots
import com.salud360.core.data.network.tobb.toTurno
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.ModuloTurnos
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
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
 * Laravel sigue siendo el dueño de la base de turnos: cada operación se hace contra su API
 * `/api/salud360/...` (a través del servidor de Salud 360, que agrega el token del usuario) y la
 * respuesta se guarda en la base local (`turno`, `paciente`) como caché, para que las pantallas —que
 * observan la base— se actualicen solas y la agenda del día se pueda ver aunque se corte la conexión.
 *
 * Los registros importados llevan ids `tobb-t<id>` (turnos) y `tobb-p<id>` (pacientes), ver [TobbIds].
 */
class AgendaTurnosOnline(private val db: Salud360Db, private val api: ApiClient) {
    private val log = Logger.withTag("TurnosOnline")
    private val q get() = db.turnosQueries
    private val pq get() = db.pacientesQueries

    /** Feriados informados por la API en las consultas de agenda (la tabla local no tiene los de la web). */
    private val feriados = mutableMapOf<LocalDate, Boolean>()
    private var pacienteBloqueoId: Long? = null

    fun esFeriadoCacheado(fecha: LocalDate): Boolean = feriados[fecha] == true

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    suspend fun dia(medicoId: Id, consultorioId: Id, fecha: LocalDate, tipo: TipoTurno = TipoTurno.CONSULTA): DiaAgendaResuelto {
        val obj = api.tobbGet("agenda/dia", params(medicoId, consultorioId, "fecha" to fecha.toString(), "tipo_turno" to tipo.codigo))
        return cachearDia(medicoId, consultorioId, decodificar(TobbAgendaDia.serializer(), obj))
    }

    /** Próximos días con horarios cargados a partir de `desde` (equivalente a la agenda semanal de la web). */
    suspend fun semana(medicoId: Id, consultorioId: Id, desde: LocalDate, cantidad: Int = 5): List<DiaAgendaResuelto> {
        val obj = api.tobbGet("agenda/semana", params(medicoId, consultorioId, "desde" to desde.toString(), "cantidad" to cantidad))
        return decodificar(TobbAgendaSemana.serializer(), obj).dias.map { cachearDia(medicoId, consultorioId, it) }
    }

    suspend fun proximasFechas(medicoId: Id, consultorioId: Id, desde: LocalDate, cantidad: Int = 3): List<LocalDate> {
        val obj = api.tobbGet("agenda/proximas-fechas", params(medicoId, consultorioId, "desde" to desde.toString(), "cantidad" to cantidad))
        return decodificar(TobbFechas.serializer(), obj).fechas.mapNotNull { it.aFechaTobb() }
    }

    suspend fun diasAtencion(medicoId: Id, consultorioId: Id): Set<DayOfWeek> {
        val obj = api.tobbGet("agenda/dias-atencion", params(medicoId, consultorioId))
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
            tipoTurno = turno.tipoTurno.codigo, primerControl = if (turno.primerControl) 1 else 0, comentario = turno.comentario,
        )
        val r = decodificar(TobbTurnosResponse.serializer(), api.tobbPost("turnos", cuerpo(TobbNuevoTurno.serializer(), cuerpo)))
        val creados = cachearTurnos(turno.medicoId, r.turnos)
        val primero = creados.firstOrNull() ?: throw TobbException(500, null, "turnosonlinebb no devolvió el turno creado")
        ResultadoTurno.Ok(primero, creados.getOrNull(1))
    }

    suspend fun sobreturno(turno: Turno): ResultadoTurno = intentar {
        val pacienteId = turno.pacienteId ?: throw TobbException(422, "datos", "Falta el paciente del sobreturno")
        val cuerpo = TobbNuevoTurno(
            medicoId = numeroMedico(turno.medicoId), consultorioId = numeroConsultorio(turno.consultorioId),
            pacienteId = pacienteTobbId(pacienteId, turno.medicoId, turno.consultorioId),
            fecha = turno.fecha.toString(), horario = turno.horario.aTextoTobb(), tipoTurno = turno.tipoTurno.codigo, comentario = turno.comentario,
        )
        val r = decodificar(TobbTurnoResponse.serializer(), api.tobbPost("turnos/sobreturno", cuerpo(TobbNuevoTurno.serializer(), cuerpo)))
        val creado = r.turno?.let { cachearTurnos(turno.medicoId, listOf(it)).firstOrNull() } ?: throw TobbException(500, null, "turnosonlinebb no devolvió el sobreturno")
        ResultadoTurno.Ok(creado)
    }

    /**
     * Bloquea un horario registrando un turno para el paciente ficticio de bloqueo (DNI 99999), como hace la web.
     * La API rechaza un segundo bloqueo el mismo día con `mismo_dia`; en ese caso hay que usar "bloquear día".
     */
    suspend fun bloquearHorario(medicoId: Id, consultorioId: Id, fecha: LocalDate, horario: LocalTime): ResultadoTurno = intentar {
        val cuerpo = TobbNuevoTurno(
            medicoId = numeroMedico(medicoId), consultorioId = numeroConsultorio(consultorioId), pacienteId = pacienteBloqueo(),
            fecha = fecha.toString(), horario = horario.aTextoTobb(), comentario = "Bloqueado desde Salud 360",
        )
        val r = decodificar(TobbTurnosResponse.serializer(), api.tobbPost("turnos", cuerpo(TobbNuevoTurno.serializer(), cuerpo)))
        val creado = cachearTurnos(medicoId, r.turnos).firstOrNull() ?: throw TobbException(500, null, "turnosonlinebb no devolvió el bloqueo")
        ResultadoTurno.Ok(creado)
    }

    /** Bloquea el día completo. Devuelve null si salió bien o el mensaje de error. */
    suspend fun bloquearDia(medicoId: Id, consultorioId: Id, fecha: LocalDate): String? = try {
        val cuerpo = mapOf(
            "medico_id" to numeroMedico(medicoId), "consultorio_id" to numeroConsultorio(consultorioId),
            "fecha" to fecha.toString(), "paciente_id" to pacienteBloqueo(),
        )
        val r = decodificar(TobbTurnosResponse.serializer(), api.tobbPost("turnos/bloquear-dia", cuerpo))
        guardarTurnosDelDia(medicoId, consultorioId, fecha, r.turnos)
        null
    } catch (e: TobbException) {
        if (e.codigo == "dia_con_turnos") "No se puede bloquear: hay pacientes con turno ese día" else e.message
    } catch (e: Exception) {
        log.w(e) { "No se pudo bloquear el día" }; "Sin conexión con turnosonlinebb: ${e.message}"
    }

    suspend fun cancelar(turnoId: Id, medicoId: Id, motivo: String = "") {
        val n = numeroTurno(turnoId)
        val r = decodificar(TobbTurnoResponse.serializer(), api.tobbPost("turnos/$n/cancelar", mapOf("motivo" to motivo)))
        r.turno?.let { cachearTurnos(medicoId, listOf(it)) } ?: marcarCanceladoLocal(turnoId)
    }

    suspend fun asistencia(turnoId: Id, medicoId: Id, a: Asistencia) = accionSobreTurno(turnoId, medicoId, "asistencia", mapOf("asistio" to a.aCodigoTobb()))
    suspend fun caja(turnoId: Id, medicoId: Id, valor: Double) = accionSobreTurno(turnoId, medicoId, "caja", mapOf("caja" to valor))
    suspend fun comentario(turnoId: Id, medicoId: Id, texto: String) = accionSobreTurno(turnoId, medicoId, "comentario", mapOf("comentario" to texto))

    private suspend fun accionSobreTurno(turnoId: Id, medicoId: Id, accion: String, cuerpo: Map<String, Any?>) {
        val r = decodificar(TobbTurnoResponse.serializer(), api.tobbPost("turnos/${numeroTurno(turnoId)}/$accion", cuerpo))
        r.turno?.let { cachearTurnos(medicoId, listOf(it)) }
    }

    // ------------------------------------------------------------------
    // Pacientes
    // ------------------------------------------------------------------

    /** Busca pacientes en la base de turnosonlinebb (DNI, apellido, nombre o mail) y los guarda en la base local. */
    suspend fun buscarPacientes(texto: String): List<Paciente> {
        val t = texto.trim()
        if (t.length < 2) return emptyList()
        val r = decodificar(TobbPacientesResponse.serializer(), api.tobbGet("pacientes/buscar", mapOf("q" to t)))
        val ahora = ahoraMillis()
        val pacientes = r.pacientes.filter { it.dni.texto() != TOBB_DNI_BLOQUEO }.map { it.toPaciente() }
        db.transaction { pacientes.forEach { guardarPacienteLocal(it, ahora) } }
        return pacientes
    }

    /**
     * Id en turnosonlinebb de un paciente de la app. Si el paciente se creó en Salud 360 se lo da de alta en la
     * web (por DNI: si ya existe se reutiliza) y se recuerda el vínculo en `paciente_extra` (turnos / tobb_id).
     */
    suspend fun pacienteTobbId(pacienteId: Id, medicoId: Id, consultorioId: Id): Long {
        TobbIds.numero(pacienteId)?.let { return it }
        pq.extrasDePaciente(pacienteId, EXTRA_TURNOS).lista { it.toModel() }.firstOrNull { it.clave == EXTRA_TOBB_ID }?.valor?.toLongOrNull()?.let { return it }
        val p = pq.pacientePorId(pacienteId).uno { it.toModel() } ?: throw TobbException(404, "paciente", "El paciente no existe en este dispositivo")
        val dni = p.dni.trim()
        if (dni.isEmpty() || !dni.all { it.isDigit() }) throw TobbException(422, "datos", "El paciente necesita un DNI numérico para registrarlo en turnosonlinebb")
        val cuerpo = TobbNuevoPaciente(
            medicoId = numeroMedico(medicoId), consultorioId = TobbIds.numero(consultorioId), dni = dni, nombre = p.nombre, apellido = p.apellido,
            telefono = p.telefono.ifBlank { null }, mail = p.mail.ifBlank { null }, fechaNacimiento = p.fechaNacimiento?.toString(),
            domicilio = p.domicilio.ifBlank { null }, localidad = p.localidad.ifBlank { null },
            obraSocial = p.obraSocial.ifBlank { null }, numeroAfiliado = p.numeroAfiliado.ifBlank { null }, obraSocialPlan = p.obraSocialPlan.ifBlank { null },
        )
        val r = decodificar(TobbPacienteResponse.serializer(), api.tobbPost("pacientes", cuerpo(TobbNuevoPaciente.serializer(), cuerpo)))
        // dirty = true: el vínculo viaja por la sincronización normal a los demás dispositivos
        pq.upsertPacienteExtra(PacienteExtra(pacienteId, EXTRA_TURNOS, EXTRA_TOBB_ID, r.paciente.id.toString()).toRow(ahoraMillis(), dirty = true))
        return r.paciente.id
    }

    private suspend fun pacienteBloqueo(): Long {
        pacienteBloqueoId?.let { return it }
        val r = decodificar(TobbPacientesResponse.serializer(), api.tobbGet("pacientes/buscar", mapOf("q" to TOBB_DNI_BLOQUEO)))
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
        val fusionado = if (existente == null) nuevo else existente.copy(
            dni = nuevo.dni.ifBlank { existente.dni }, nombre = nuevo.nombre.ifBlank { existente.nombre }, apellido = nuevo.apellido.ifBlank { existente.apellido },
            telefono = nuevo.telefono.ifBlank { existente.telefono }, mail = nuevo.mail.ifBlank { existente.mail },
            domicilio = nuevo.domicilio.ifBlank { existente.domicilio }, localidad = nuevo.localidad.ifBlank { existente.localidad },
            fechaNacimiento = nuevo.fechaNacimiento ?: existente.fechaNacimiento,
            obraSocial = nuevo.obraSocial.ifBlank { existente.obraSocial }, numeroAfiliado = nuevo.numeroAfiliado.ifBlank { existente.numeroAfiliado },
            obraSocialPlan = nuevo.obraSocialPlan.ifBlank { existente.obraSocialPlan },
        )
        pq.upsertPaciente(fusionado.toRow(ahora, dirty = false))
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
    } catch (e: Exception) {
        log.w(e) { "Error hablando con turnosonlinebb" }
        ResultadoTurno.Error("Sin conexión con turnosonlinebb: ${e.message ?: "error de red"}")
    }

    companion object {
        /** Clave de `paciente_extra` donde se guarda el id del paciente en turnosonlinebb. */
        const val EXTRA_TURNOS = "turnos"
        const val EXTRA_TOBB_ID = "tobb_id"
    }
}

fun LocalTime.aTextoTobb(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
