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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    /** Fecha desde la que se pidió la semana (la muestra el selector de la agenda semanal). */
    val semanaDesde: LocalDate? = null,
    val cargandoSemana: Boolean = false,
    /** Próximas fechas (desde hoy) con al menos un turno libre. */
    val proximasFechas: List<LocalDate> = emptyList(),
    /** Turno que la secretaria está moviendo a otro horario (flujo "Modificar" de la web): se elige el nuevo y se cancela este. */
    val turnoAModificar: Turno? = null,
    /** Fichas de los pacientes con turno ese día (número de afiliado, plan), por id de paciente. */
    val pacientesDelDia: Map<Id, Paciente> = emptyMap(),
    /** true mientras se consulta la agenda del día a turnosonlinebb. */
    val cargando: Boolean = false,
    /** Mensaje de error de la última operación (por ejemplo, sin conexión con turnosonlinebb). */
    val mensaje: String? = null,
    val esFeriado: Boolean = false,
    /** La agenda de este médico vive en turnosonlinebb (se lee y escribe contra su API). */
    val remota: Boolean = false,
) {
    val conCaja: Boolean get() = ModuloTurnos.CAJA_COMENTARIO in modulos
    val primerControlDoble: Boolean get() = ModuloTurnos.PRIMER_CONTROL_DOBLE in modulos
}

/**
 * Agenda de un médico en un consultorio, para el médico o la secretaria.
 * Cubre: listado del día, semana, asignar turno, sobreturno, bloquear, cancelar, asistencia, caja y comentario.
 *
 * Si el médico es de turnosonlinebb, cada cambio de fecha pide la agenda a la web y la deja en la base local;
 * las pantallas observan la base, así que se actualizan solas y siguen mostrando lo último que se vio si se
 * corta la conexión.
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
    private val remota = turnos.esRemota(medicoId)
    private val _state = MutableStateFlow(AgendaUiState(remota = remota))
    val state: StateFlow<AgendaUiState> = _state

    val turnosDelDia: StateFlow<List<Turno>> = fecha.flatMapLatest { f -> turnos.observarTurnosDelDia(medicoId, consultorioId, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(fecha, turnosDelDia, turnos.observarModulos(medicoId)) { f, t, m -> Triple(f, t, m) }.collect { (f, t, m) ->
                _state.update { it.copy(fecha = f, turnosDelDia = t, modulos = m, esFeriado = turnos.esFeriado(f)) }
            }
        }
        if (remota) viewModelScope.launch { fecha.collectLatest { f -> refrescar(f) } }
        // Fichas de los pacientes del día (afiliado, plan): se observan para que la tarjeta muestre lo que haya en caché.
        viewModelScope.launch {
            turnosDelDia.map { ts -> ts.mapNotNull { it.pacienteId }.distinct().sorted() }.distinctUntilChanged()
                .flatMapLatest { ids -> pacientes.observarPorIds(ids) }
                .collect { lista -> _state.update { it.copy(pacientesDelDia = lista.associateBy { p -> p.id }) } }
        }
        cargarProximasFechas()
    }

    fun irA(f: LocalDate) { fecha.value = f }
    fun diaSiguiente() = irA(fecha.value.plus(1, DateTimeUnit.DAY))
    fun diaAnterior() = irA(fecha.value.plus(-1, DateTimeUnit.DAY))

    /** Vuelve a pedir la agenda del día a turnosonlinebb (no hace nada para médicos locales). */
    fun refrescar() = viewModelScope.launch { refrescar(fecha.value) }

    private suspend fun refrescar(f: LocalDate) {
        if (!remota) return
        _state.update { it.copy(cargando = true) }
        runCatching { turnos.refrescarDia(medicoId, consultorioId, f) }
            .onSuccess { d -> _state.update { it.copy(cargando = false, mensaje = null, esFeriado = d?.feriado ?: it.esFeriado) } }
            .onFailure { e -> _state.update { it.copy(cargando = false) }; reportar(e) }
    }

    /** Slots del día actual (para asignar o bloquear). */
    suspend fun slotsDelDia(f: LocalDate = fecha.value): List<SlotAgenda> =
        runCatching { turnos.slotsDelDia(medicoId, consultorioId, f) }
            .onFailure { e -> reportar(e) }
            .getOrDefault(emptyList())

    /** Arma los próximos 5 días con atención a partir de una fecha (`obtener5Dias`). */
    fun cargarSemana(desde: LocalDate = fecha.value) = viewModelScope.launch {
        _state.update { it.copy(cargandoSemana = true, semanaDesde = desde) }
        runCatching { turnos.semana(medicoId, consultorioId, desde) }
            .onSuccess { dias -> _state.update { it.copy(semana = dias.map { d -> DiaAgenda(d.fecha, d.slots, d.feriado, d.turnos.filter { t -> t.sobreturno }) }, cargandoSemana = false, mensaje = null) } }
            .onFailure { e -> _state.update { it.copy(cargandoSemana = false) }; reportar(e) }
    }

    fun semanaSiguiente() { _state.value.semana.lastOrNull()?.let { cargarSemana(it.fecha.plus(1, DateTimeUnit.DAY)) } }
    fun semanaAnterior() { (_state.value.semanaDesde ?: _state.value.semana.firstOrNull()?.fecha)?.let { cargarSemana(it.plus(-7, DateTimeUnit.DAY)) } }

    /** Refresca las próximas fechas con turno libre (desde hoy). Se llama al abrir y tras cada cambio de disponibilidad. */
    fun cargarProximasFechas() = viewModelScope.launch { _state.update { it.copy(proximasFechas = proximasFechas()) } }

    /** Pacientes para asignar: los de turnosonlinebb si la agenda es remota (con la base local como respaldo). */
    suspend fun buscarPacientes(texto: String): List<Paciente> {
        if (remota) {
            val remotos = runCatching { turnos.buscarPacientesRemotos(medicoId, texto) }.onFailure { e -> reportar(e) }.getOrNull()
            if (remotos != null) return remotos
        }
        return pacientes.buscar(texto, null, 30)
    }

    /** Asigna un turno a un paciente en un slot (`registrarAsignarTurno`). */
    fun asignar(paciente: Paciente, f: LocalDate, horario: LocalTime, primerControl: Boolean, segundoHorario: LocalTime? = null, tipo: TipoTurno = TipoTurno.CONSULTA, onResultado: (ResultadoTurno) -> Unit) = viewModelScope.launch {
        val t = Turno(
            newId(), paciente.id, medicoId, consultorioId, f, horario, tipo, otorgadoPor = operador, primerControl = primerControl,
            pacienteNombre = paciente.nombreCompleto, pacienteDni = paciente.dni, pacienteTelefono = paciente.telefono, pacienteObraSocial = paciente.obraSocial,
        )
        val r = if (primerControl && segundoHorario != null) turnos.registrarTurnoDoble(t, segundoHorario) else turnos.registrarTurno(t)
        if (r is ResultadoTurno.Ok) { pacientes.vincular(medicoId, paciente.id); despuesDeCambiar(f); cargarProximasFechas() }
        cargarSemana(_state.value.semanaDesde ?: f)
        onResultado(r)
    }

    fun sobreturno(paciente: Paciente, f: LocalDate, horario: LocalTime, onResultado: (ResultadoTurno) -> Unit) = viewModelScope.launch {
        val t = Turno(newId(), paciente.id, medicoId, consultorioId, f, horario, otorgadoPor = operador, sobreturno = true,
            pacienteNombre = paciente.nombreCompleto, pacienteDni = paciente.dni, pacienteTelefono = paciente.telefono, pacienteObraSocial = paciente.obraSocial)
        val r = turnos.registrarSobreturno(t)
        if (r is ResultadoTurno.Ok) { pacientes.vincular(medicoId, paciente.id); despuesDeCambiar(f); cargarProximasFechas() }
        onResultado(r)
    }

    fun bloquear(f: LocalDate, horario: LocalTime, onResultado: (ResultadoTurno) -> Unit = {}) = viewModelScope.launch {
        val r = turnos.bloquearHorario(medicoId, consultorioId, f, horario, operador)
        if (r is ResultadoTurno.Ok) { despuesDeCambiar(f); cargarProximasFechas() }
        cargarSemana(_state.value.semanaDesde ?: f)
        onResultado(r)
    }

    /** Bloquea el día completo. `onResultado` recibe null si salió bien o el motivo del rechazo. */
    fun bloquearDia(f: LocalDate, onResultado: (String?) -> Unit) = viewModelScope.launch {
        val error = turnos.bloquearDia(medicoId, consultorioId, f, operador)
        if (error == null) { despuesDeCambiar(f); cargarProximasFechas() }
        cargarSemana(_state.value.semanaDesde ?: f)
        onResultado(error)
    }

    fun liberar(turno: Turno) = viewModelScope.launch {
        runCatching { if (turno.estado == EstadoTurno.BLOQUEADO) turnos.liberarBloqueo(turno.id) else turnos.cancelarTurno(turno.id, operador) }
            .onSuccess { despuesDeCambiar(turno.fecha); cargarProximasFechas() }
            .onFailure { e -> reportar(e) }
        cargarSemana(_state.value.semanaDesde ?: turno.fecha)
    }
    fun cancelar(turno: Turno) = liberar(turno)

    // ---- Modificar turno (secretaria): guardar el turno, elegir otro horario, registrar el nuevo y cancelar el anterior ----

    fun iniciarModificacion(t: Turno) = _state.update { it.copy(turnoAModificar = t, mensaje = null) }
    fun cancelarModificacion() = _state.update { it.copy(turnoAModificar = null) }

    /**
     * Mueve el turno en modificación a `f`/`horario`, en el mismo orden que la web: primero se cancela el anterior y
     * después se registra el nuevo (así no choca con "un turno por día" si es el mismo día). Si el nuevo es rechazado,
     * se vuelve a registrar el anterior para no dejar al paciente sin turno.
     */
    fun moverTurno(f: LocalDate, horario: LocalTime, onResultado: (ResultadoTurno) -> Unit) = viewModelScope.launch {
        val viejo = _state.value.turnoAModificar ?: return@launch
        val nuevo = viejo.copy(
            id = newId(), fecha = f, horario = horario, estado = EstadoTurno.ACTIVO, asistencia = Asistencia.SIN_MARCAR, sobreturno = false,
            otorgadoPor = operador, canceladoPor = "", caja = 0.0, recordatorioEnviado = false, pagado = false, importeReserva = 0.0,
        )
        val r = runCatching {
            turnos.cancelarTurno(viejo.id, operador)
            val registrado = turnos.registrarTurno(nuevo)
            if (registrado !is ResultadoTurno.Ok) {
                turnos.registrarTurno(viejo.copy(id = newId(), estado = EstadoTurno.ACTIVO, canceladoPor = "", otorgadoPor = operador), validarMismoDia = false)
            }
            registrado
        }.getOrElse { e -> if (e is CancellationException) throw e; ResultadoTurno.Error(errorDe(e)) }
        if (r is ResultadoTurno.Ok) _state.update { it.copy(turnoAModificar = null) }
        despuesDeCambiar(viejo.fecha)
        if (f != viejo.fecha) despuesDeCambiar(f)
        cargarSemana(_state.value.semanaDesde ?: f)
        cargarProximasFechas()
        onResultado(r)
    }
    fun asistencia(turno: Turno, a: Asistencia) = accion { turnos.marcarAsistencia(turno.id, a) }
    fun caja(turno: Turno, valor: Double) = accion { turnos.actualizarCaja(turno.id, valor) }
    fun comentario(turno: Turno, texto: String) = accion { turnos.actualizarComentario(turno.id, texto) }
    suspend fun proximasFechas(desde: LocalDate = hoy()): List<LocalDate> =
        runCatching { turnos.proximasFechasLibres(medicoId, consultorioId, desde) }.getOrDefault(emptyList())
    suspend fun diasConAtencion(): Set<DayOfWeek> = runCatching { turnos.diasConAtencion(medicoId, consultorioId, hoy()) }.getOrDefault(emptySet())

    private fun accion(bloque: suspend () -> Unit) = viewModelScope.launch {
        runCatching { bloque() }.onFailure { e -> reportar(e) }
    }

    /**
     * Deja el error en `mensaje`, salvo que sea una cancelación: cerrar un diálogo o salir de la pantalla mientras se
     * consultaba cancela la corrutina ("The coroutine scope left the composition") y eso no es un fallo de turnosonlinebb.
     */
    private fun reportar(e: Throwable) {
        if (e is CancellationException) return
        _state.update { it.copy(mensaje = errorDe(e)) }
    }

    /** Tras escribir: los médicos locales sincronizan con el servidor; los de turnosonlinebb ya quedaron actualizados en la caché. */
    private fun despuesDeCambiar(f: LocalDate) {
        if (remota) viewModelScope.launch { refrescar(f) } else viewModelScope.launch { sync.sincronizar() }
    }

    private fun errorDe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() }?.let { "turnosonlinebb: $it" } ?: "Sin conexión con turnosonlinebb"
}
