package com.salud360.features.pacientes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.data.repos.PacientesRepository
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.newId
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Listado y búsqueda de pacientes. Si se indica `medicoId` se limita a su cartera (médico o
 * secretaria operando para un médico); si es null muestra todos (administrador).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PacientesListViewModel(
    private val pacientes: PacientesRepository,
    private val medicoId: Id?,
) : ViewModel() {
    val busqueda = MutableStateFlow("")

    private val base = if (medicoId != null) pacientes.observarDeMedico(medicoId) else pacientes.observarTodos()

    val lista: StateFlow<List<Paciente>> = combine(base, busqueda.debounce(200)) { todos, q ->
        val t = q.trim().lowercase()
        if (t.isEmpty()) todos
        else todos.filter { p -> p.dni.startsWith(t) || p.apellido.lowercase().contains(t) || p.nombre.lowercase().contains(t) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Búsqueda global por DNI en toda la base (para vincular un paciente ya existente de otro médico). */
    suspend fun buscarGlobal(dni: String): Paciente? = pacientes.porDni(dni)

    fun vincular(pacienteId: Id) {
        val m = medicoId ?: return
        viewModelScope.launch { pacientes.vincular(m, pacienteId) }
    }
}

data class PacienteFormState(
    val paciente: Paciente,
    val errores: Map<String, String> = emptyMap(),
    val guardando: Boolean = false,
    val guardado: Boolean = false,
    /** Un paciente con ese DNI ya existe: se reutilizará su ficha. */
    val existente: Paciente? = null,
)

/** Alta / edición de la ficha del paciente (formulario compartido por médico y secretaria). */
class PacienteFormViewModel(
    private val pacientes: PacientesRepository,
    private val pacienteId: Id?,
    private val vincularA: Id?,
) : ViewModel() {
    private val _state = MutableStateFlow(PacienteFormState(Paciente(newId(), "", "", "")))
    val state: StateFlow<PacienteFormState> = _state.asStateFlowCompat()

    init {
        if (pacienteId != null) viewModelScope.launch {
            pacientes.porId(pacienteId)?.let { p -> _state.value = _state.value.copy(paciente = p) }
        }
    }

    fun actualizar(transform: (Paciente) -> Paciente) {
        _state.value = _state.value.copy(paciente = transform(_state.value.paciente), guardado = false)
    }

    fun verificarDni() {
        val dni = _state.value.paciente.dni.trim()
        if (dni.isEmpty()) return
        viewModelScope.launch {
            val e = pacientes.porDni(dni)
            _state.value = _state.value.copy(existente = e?.takeIf { it.id != _state.value.paciente.id })
        }
    }

    fun usarExistente() {
        val e = _state.value.existente ?: return
        _state.value = _state.value.copy(paciente = e, existente = null)
    }

    fun guardar(onOk: (Paciente) -> Unit) {
        val p = _state.value.paciente
        val errores = buildMap {
            if (p.nombre.isBlank()) put("nombre", "Requerido")
            if (p.apellido.isBlank()) put("apellido", "Requerido")
            if (p.dni.isBlank()) put("dni", "Requerido")
            else if (p.dni.any { !it.isDigit() }) put("dni", "Solo números")
        }
        if (errores.isNotEmpty()) { _state.value = _state.value.copy(errores = errores); return }
        _state.value = _state.value.copy(guardando = true, errores = emptyMap())
        viewModelScope.launch {
            val guardado = pacientes.guardar(p, vincularA)
            _state.value = _state.value.copy(paciente = guardado, guardando = false, guardado = true)
            onOk(guardado)
        }
    }
}

/** Ficha del paciente con su actividad: consultas por especialidad y turnos. */
class PacienteDetalleViewModel(
    pacientes: PacientesRepository,
    hc: HcRepository,
    turnos: TurnosRepository,
    private val pacienteId: Id,
) : ViewModel() {
    val paciente: StateFlow<Paciente?> = pacientes.observar(pacienteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val consultas: StateFlow<List<Consulta>> = hc.observarConsultasTodas(pacienteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val turnos: StateFlow<List<Turno>> = turnos.observarTurnosDePaciente(pacienteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

private fun <T> MutableStateFlow<T>.asStateFlowCompat(): StateFlow<T> = this
