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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Listado y búsqueda de pacientes. Si se indica `medicoId` se limita a su cartera (médico o
 * secretaria operando para un médico); si es null muestra todos (administrador).
 *
 * Si el médico es de turnosonlinebb: al abrir la pantalla se trae de una vez toda su cartera
 * (`pacientes/vinculados`) y se guarda vinculada localmente, así la lista aparece cargada sin
 * tener que escribir. Además, la búsqueda (2+ caracteres) también consulta la web en vivo —igual
 * que al asignar un turno—, para encontrar cualquier paciente aunque todavía no tenga turno acá.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PacientesListViewModel(
    private val pacientes: PacientesRepository,
    private val turnos: TurnosRepository,
    private val medicoId: Id?,
) : ViewModel() {
    val busqueda = MutableStateFlow("")
    private val remota = medicoId != null && turnos.esRemota(medicoId)
    private val remotos = MutableStateFlow<List<Paciente>?>(null)
    private val _cargandoCartera = MutableStateFlow(false)
    val cargandoCartera: StateFlow<Boolean> = _cargandoCartera

    /**
     * Paginado: la lista observa solo los primeros `limite` pacientes (filtro resuelto en SQL) y el límite crece de a
     * [PAGINA] cuando la pantalla llega al final. Así una cartera de miles de pacientes no se mapea ni recompone entera.
     */
    private val limite = MutableStateFlow(PAGINA)
    private val filtro = busqueda.debounce(200).map { it.trim() }.distinctUntilChanged()

    private val locales: Flow<List<Paciente>> = combine(filtro, limite) { q, lim -> q to lim }
        .flatMapLatest { (q, lim) -> pacientes.observarPagina(medicoId, q, lim) }

    val lista: StateFlow<List<Paciente>> = combine(locales, remotos) { locales, remotos ->
        if (remotos.isNullOrEmpty()) locales else (remotos + locales).distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Total que cumple el filtro (para el título y para saber si hay más páginas). */
    val total: StateFlow<Long> = filtro.flatMapLatest { q -> pacientes.observarTotal(medicoId, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val hayMas: StateFlow<Boolean> = combine(total, limite) { t, lim -> t > lim }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Pide la siguiente página (la llama la pantalla al acercarse al final de la lista). */
    fun cargarMas() { if (hayMas.value) limite.value += PAGINA }

    init {
        // Al cambiar la búsqueda se vuelve a la primera página.
        viewModelScope.launch { filtro.collect { limite.value = PAGINA } }
        if (remota) {
            viewModelScope.launch {
                _cargandoCartera.value = true
                runCatching { turnos.sincronizarPacientesVinculados(medicoId!!) }
                _cargandoCartera.value = false
            }
            viewModelScope.launch {
                busqueda.debounce(300).collectLatest { q ->
                    remotos.value = if (q.trim().length >= 2) runCatching { turnos.buscarPacientesRemotos(medicoId!!, q) }.getOrNull() else null
                }
            }
        }
    }

    /** Búsqueda global por DNI en toda la base (para vincular un paciente ya existente de otro médico). */
    suspend fun buscarGlobal(dni: String): Paciente? = pacientes.porDni(dni)

    fun vincular(pacienteId: Id) {
        val m = medicoId ?: return
        viewModelScope.launch { pacientes.vincular(m, pacienteId) }
    }

    private companion object {
        const val PAGINA = 50L
    }
}

data class PacienteFormState(
    val paciente: Paciente,
    val errores: Map<String, String> = emptyMap(),
    val guardando: Boolean = false,
    val guardado: Boolean = false,
    /** Un paciente con ese DNI ya existe: se reutilizará su ficha. */
    val existente: Paciente? = null,
    /** La ficha se guardó en el dispositivo pero no se pudo actualizar turnosonlinebb (sin conexión, etc.). */
    val avisoRemoto: String? = null,
)

/** Alta / edición de la ficha del paciente (formulario compartido por médico y secretaria). */
class PacienteFormViewModel(
    private val pacientes: PacientesRepository,
    private val turnos: TurnosRepository,
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
        _state.value = _state.value.copy(guardando = true, errores = emptyMap(), avisoRemoto = null)
        viewModelScope.launch {
            // Primero el dispositivo, que no depende de la red; después turnosonlinebb.
            val guardado = pacientes.guardar(p, vincularA)
            val aviso = guardarEnTurnosOnline(guardado.id)
            _state.value = _state.value.copy(paciente = guardado, guardando = false, guardado = true, avisoRemoto = aviso)
            onOk(guardado)
        }
    }

    /**
     * Manda la ficha a turnosonlinebb cuando el médico es de la web (la da de alta si todavía no existe allá).
     * Sin esto lo editado se pierde en cuanto la web vuelve a informar al paciente. Devuelve el aviso a mostrar
     * si no se pudo, o null si salió bien o no correspondía.
     */
    private suspend fun guardarEnTurnosOnline(pacienteId: Id): String? {
        val medicoId = vincularA ?: return null
        if (!turnos.esRemota(medicoId)) return null
        return try {
            turnos.guardarPacienteRemoto(medicoId, pacienteId, consultorioId = null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "Se guardó en este dispositivo, pero no se pudo actualizar turnosonlinebb: ${e.message ?: "sin conexión"}. Volvé a guardar para reintentar."
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
