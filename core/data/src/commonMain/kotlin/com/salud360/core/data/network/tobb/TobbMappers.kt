package com.salud360.core.data.network.tobb

import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.FechaAgregada
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** DNI del paciente ficticio con el que la web de turnos marca los horarios bloqueados. */
const val TOBB_DNI_BLOQUEO = "99999"

/** Texto de un primitivo JSON que puede venir como número o como cadena (DNI, teléfono, importes). */
fun JsonPrimitive?.texto(): String = this?.contentOrNull?.trim()?.takeIf { it != "null" } ?: ""

fun JsonPrimitive?.decimal(): Double = texto().replace(',', '.').toDoubleOrNull() ?: 0.0

/** "9:20", "09:20" o "09:20:00" → LocalTime. Devuelve null si no se puede interpretar. */
fun String.aHoraTobb(): LocalTime? {
    val partes = trim().split(':')
    val h = partes.getOrNull(0)?.toIntOrNull() ?: return null
    val m = partes.getOrNull(1)?.toIntOrNull() ?: 0
    if (h !in 0..23 || m !in 0..59) return null
    return LocalTime(h, m)
}

fun String.aFechaTobb(): LocalDate? = runCatching { LocalDate.parse(take(10).replace('/', '-')) }.getOrNull()

fun Int.aAsistencia(): Asistencia = when (this) { 1 -> Asistencia.ASISTIO; 2 -> Asistencia.NO_ASISTIO; else -> Asistencia.SIN_MARCAR }
fun Asistencia.aCodigoTobb(): Int = when (this) { Asistencia.ASISTIO -> 1; Asistencia.NO_ASISTIO -> 2; Asistencia.SIN_MARCAR -> 0 }

/** true si el turno es un bloqueo (paciente ficticio de la web). */
fun TobbTurno.esBloqueo(): Boolean = paciente?.dni.texto() == TOBB_DNI_BLOQUEO

/** Horario fijo de `GET horarios` → modelo local con id `tobb-h<id>`. Null si el día u horario no se pueden interpretar. */
fun TobbHorarioFijo.toHorario(medicoId: Id, consultorioId: Id): HorarioMedico? {
    val hora = horario.aHoraTobb() ?: return null
    if (dia !in 1..7) return null
    return HorarioMedico(
        TobbIds.horario(id), medicoId, consultorioId, DayOfWeek(dia), hora, doble = doble == 1, tipoTurno = TipoTurno.porCodigo(tipoTurno),
        validoDesde = validoDesde?.aFechaTobb(), validoHasta = validoHasta?.aFechaTobb(), activo = true, quincenal = quincenal == 1,
    )
}

/** Fecha especial de `GET horarios` → modelo local con id `tobb-f<id>`. */
fun TobbFechaEspecial.toFechaAgregada(medicoId: Id, consultorioId: Id): FechaAgregada? {
    val f = fecha.aFechaTobb() ?: return null
    return FechaAgregada(TobbIds.fechaAgregada(id), medicoId, consultorioId, f, horarios.mapNotNull { it.horario.aHoraTobb() }.sorted())
}

/**
 * Convierte un turno de la API al modelo de Salud 360. Los bloqueos de la web (paciente con DNI 99999)
 * se representan como [EstadoTurno.BLOQUEADO] sin paciente; `activo = 2` (pago pendiente) también ocupa.
 */
fun TobbTurno.toTurno(): Turno? {
    val f = fecha.aFechaTobb() ?: return null
    val h = horario.aHoraTobb() ?: return null
    val bloqueo = esBloqueo()
    val estado = when {
        activo == 0 -> EstadoTurno.CANCELADO
        bloqueo || activo == 2 -> EstadoTurno.BLOQUEADO
        else -> EstadoTurno.ACTIVO
    }
    val p = paciente
    return Turno(
        id = TobbIds.turno(id),
        pacienteId = if (bloqueo || pacienteId <= 0) null else TobbIds.paciente(pacienteId),
        medicoId = TobbIds.medico(medicoId),
        consultorioId = TobbIds.consultorio(consultorioId),
        fecha = f, horario = h,
        tipoTurno = TipoTurno.porCodigo(tipoTurno),
        estado = estado,
        asistencia = asistio.aAsistencia(),
        sobreturno = sobreturno == 1,
        primerControl = primerControl,
        caja = caja,
        comentario = comentario ?: "",
        otorgadoPor = otorgadoPor ?: "",
        canceladoPor = canceladoPor ?: "",
        pagado = pago == 1,
        importeReserva = importeReserva.decimal(),
        especialidad = especialidad ?: "",
        pacienteNombre = if (bloqueo) "Bloqueado" else listOf(p?.apellido ?: "", p?.nombre ?: "").filter { it.isNotBlank() }.joinToString(", "),
        pacienteDni = if (bloqueo) "" else p?.dni.texto(),
        pacienteTelefono = if (bloqueo) "" else p?.telefono.texto(),
        pacienteObraSocial = if (bloqueo) "" else p?.obraSocial ?: "",
    )
}

/** Datos mínimos del paciente que viajan con el turno, para la ficha local. */
fun TobbPacienteResumen.toPaciente(): Paciente = Paciente(
    id = TobbIds.paciente(id), dni = dni.texto(), nombre = nombre ?: "", apellido = apellido ?: "",
    telefono = telefono.texto(), mail = mail ?: "", obraSocial = obraSocial ?: "", numeroAfiliado = numeroAfiliado.texto(),
)

fun TobbPaciente.toPaciente(): Paciente = Paciente(
    id = TobbIds.paciente(id), dni = dni.texto(), nombre = nombre ?: "", apellido = apellido ?: "",
    fechaNacimiento = fechaNacimiento?.aFechaTobb(),
    telefono = telefono.texto(), mail = mail ?: "", domicilio = domicilio ?: "", localidad = localidad ?: "",
    obraSocial = obraSocial ?: "", numeroAfiliado = numeroAfiliado.texto(), obraSocialPlan = obraSocialPlan ?: "",
    afiliadoObligatorio = afiliadoObligatorio == 1,
    // activo = 2 en la web significa "pendiente de activación por el médico"
    activo = activo == 1,
    nota = nota ?: "",
)

/**
 * Combina los slots de disponibilidad con los turnos del día para armar la agenda que muestra la app.
 * @param dobles si el médico tiene primer control doble: un slot libre seguido de otro libre admite el doble.
 */
fun List<TobbSlot>.toSlots(turnos: List<Turno>, dobles: Boolean): List<SlotAgenda> {
    val porHorario = turnos.filter { it.estado != EstadoTurno.CANCELADO }.associateBy { it.horario }
    val ordenados = mapNotNull { s -> s.horario.aHoraTobb()?.let { it to (s.libre == 1) } }.sortedBy { it.first }
    return ordenados.mapIndexed { i, (h, libre) ->
        val siguienteLibre = ordenados.getOrNull(i + 1)?.second == true
        SlotAgenda(horario = h, libre = libre && porHorario[h] == null, turno = porHorario[h], doble = dobles && libre && siguienteLibre)
    }
}

fun Id.numeroTobb(): Long? = TobbIds.numero(this)
