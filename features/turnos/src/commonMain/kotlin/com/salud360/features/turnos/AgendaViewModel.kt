package com.salud360.features.turnos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.PacientesRepository
import com.salud360.core.data.repos.ResultadoTurno
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.repos.hoy
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.model.Id
import com.salud360.core.model.newId
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.ModuloTurnos
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

data class DiaAgenda(val fecha: LocalDate, val slots: List<SlotAgenda>, val feriado: Boolean, val sobreturnos: List<Turno>)

data class AgendaUiState(
    val fecha: LocalDate = hoy(),
    val turnosDelDia: List<Turno> = emptyList(),
    val modulos: Set<ModuloTurnos> = emptySet(),
    val semana: List<DiaAgenda> = emptyList(),
    val cargandoSemana: Boolean = false,
    val mensaje: String? = null,
    val esFeriado: Boolean = false,
) {
    val conCaja: Boolean get() = ModuloTurnos.CAJA_COMENTARIO in modulos
    val primerControlDoble: Boolean get() = ModuloTurnos.PRIMER_CONTROL_DOBLE in modulos
}

/**
 * Agenda de un médico en un consultorio, para el médico o la secretaria.
 * Cubre: listado del día, semana, asignar turno, sobreturno, bloquear, cancelar, asistencia, caja y comentario.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModel(
    private val turnos: TurnosRepository,
    private val pacientes: PacientesRepository,
    private val sync: SyncEngine,
    val medicoId: Id,
    val consultorioId: Id,
    /** Nombre de quien opera (se registra en `otorgadoPor` / `canceladoPor`). */
    private val operador: String,
) : ViewModel() {
    private val fecha = MutableStateFlow(hoy())
    private val _state = MutableStateFlow(AgendaUiState())
    val state: StateFlow<AgendaUiState> = _state

    val turnosDelDia: StateFlow<List<Turno>> = fecha.flatMapLatest { f -> turnos.observarTurnosDelDia(medicoId, consultorioId, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(fecha, turnosDelDia, turnos.observarModulos(medicoId)) { f, t, m -> Triple(f, t, m) }.collect { (f, t, m) ->
                _state.update { it.copy(fecha = f, turnosDelDia = t, modulos = m, esFeriado = turnos.esFeriado(f)) }
            }
        }
    }

    fun irA(f: LocalDate) { fecha.value = f }
    fun diaSiguiente() = irA(fecha.value.plus(1, DateTimeUnit.DAY))
    fun diaAnterior() = irA(fecha.value.plus(-1, DateTimeUnit.DAY))
    fun hoyMismo() = irA(hoy())

    /** Slots del día actual (para asignar o bloquear). */
    suspend fun slotsDelDia(f: LocalDate = fecha.value): List<SlotAgenda> = turnos.slotsDelDia(medicoId, consultorioId, f)

    /** Arma los próximos 5 días con atención a partir de una fecha (`obtener5Dias`). */
    fun cargarSemana(desde: LocalDate = fecha.value) = viewModelScope.launch {
        _state.update { it.copy(cargandoSemana = true) }
        val dias = mutableListOf<DiaAgenda>()
        var f = desde
        var i = 0
        while (dias.size < 5 && i < 60) {
            val feriado = turnos.esFeriado(f)
            val slots = if (feriado) emptyList() else turnos.slotsDelDia(medicoId, consultorioId, f)
            if (slots.isNotEmpty()) dias += DiaAgenda(f, slots, feriado, emptyList())
            f = f.plus(1, DateTimeUnit.DAY); i++
        }
        _state.update { it.copy(semana = dias, cargandoSemana = false) }
    }

    fun semanaSiguiente() { _state.value.semana.lastOrNull()?.let { cargarSemana(it.fecha.plus(1, DateTimeUnit.DAY)) } }
    fun semanaAnterior() { _state.value.semana.firstOrNull()?.let { cargarSemana(it.fecha.plus(-7, DateTimeUnit.DAY)) } }

    suspend fun buscarPacientes(texto: String): List<Paciente> = pacientes.buscar(texto, null, 30)

    /** Asigna un turno a un paciente en un slot (`registrarAsignarTurno`). */
    fun asignar(paciente: Paciente, f: LocalDate, horario: LocalTime, primerControl: Boolean, segundoHorario: LocalTime? = null, tipo: TipoTurno = TipoTurno.CONSULTA, onResultado: (ResultadoTurno) -> Unit) = viewModelScope.launch {
        val t = Turno(
            newId(), paciente.id, medicoId, consultorioId, f, horario, tipo, otorgadoPor = operador, primerControl = primerControl,
            pacienteNombre = paciente.nombreCompleto, pacienteDni = paciente.dni, pacienteTelefono = paciente.telefono, pacienteObraSocial = paciente.obraSocial,
        )
        val r = if (primerControl && segundoHorario != null) turnos.registrarTurnoDoble(t, segundoHorario) else turnos.registrarTurno(t)
        if (r is ResultadoTurno.Ok) { pacientes.vincular(medicoId, paciente.id); launch { sync.sincronizar() } }
        cargarSemana(_state.value.semana.firstOrNull()?.fecha ?: f)
        onResultado(r)
    }

    fun sobreturno(paciente: Paciente, f: LocalDate, horario: LocalTime, onResultado: (ResultadoTurno) -> Unit) = viewModelScope.launch {
        val t = Turno(newId(), paciente.id, medicoId, consultorioId, f, horario, otorgadoPor = operador, sobreturno = true,
            pacienteNombre = paciente.nombreCompleto, pacienteDni = paciente.dni, pacienteTelefono = paciente.telefono, pacienteObraSocial = paciente.obraSocial)
        val r = turnos.registrarSobreturno(t)
        if (r is ResultadoTurno.Ok) { pacientes.vincular(medicoId, paciente.id); launch { sync.sincronizar() } }
        onResultado(r)
    }

    fun bloquear(f: LocalDate, horario: LocalTime) = viewModelScope.launch { turnos.bloquearHorario(medicoId, consultorioId, f, horario, operador); cargarSemana(_state.value.semana.firstOrNull()?.fecha ?: f) }
    fun bloquearDia(f: LocalDate, onResultado: (Boolean) -> Unit) = viewModelScope.launch { val ok = turnos.bloquearDia(medicoId, consultorioId, f, operador); cargarSemana(_state.value.semana.firstOrNull()?.fecha ?: f); onResultado(ok) }
    fun liberar(turno: Turno) = viewModelScope.launch {
        if (turno.estado == EstadoTurno.BLOQUEADO) turnos.liberarBloqueo(turno.id) else turnos.cancelarTurno(turno.id, operador)
        cargarSemana(_state.value.semana.firstOrNull()?.fecha ?: turno.fecha)
        launch { sync.sincronizar() }
    }
    fun cancelar(turno: Turno) = liberar(turno)
    fun asistencia(turno: Turno, a: Asistencia) = viewModelScope.launch { turnos.marcarAsistencia(turno.id, a) }
    fun caja(turno: Turno, valor: Double) = viewModelScope.launch { turnos.actualizarCaja(turno.id, valor) }
    fun comentario(turno: Turno, texto: String) = viewModelScope.launch { turnos.actualizarComentario(turno.id, texto) }
    suspend fun proximasFechas(desde: LocalDate = hoy()): List<LocalDate> = turnos.proximasFechasLibres(medicoId, consultorioId, desde)
    suspend fun diasConAtencion(): Set<DayOfWeek> = turnos.diasConAtencion(medicoId, consultorioId, hoy())
}
