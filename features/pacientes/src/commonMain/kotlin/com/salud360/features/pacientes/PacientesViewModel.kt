package com.salud360.features.pacientes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.data.repos.PacienteGuardado
import com.salud360.core.data.repos.PacientesRepository
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.repos.avisoDePacienteYaExistia
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.newId
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 * La cartera son los pacientes asociados al médico (`medico_paciente`): los que dio de alta o tienen
 * turno con él. Si el médico es de turnosonlinebb, al abrir la pantalla se trae de una vez toda su
 * cartera (`pacientes/vinculados`) y se guarda vinculada localmente, así la lista aparece cargada sin
 * tener que escribir. La búsqueda filtra solo dentro de esa cartera: no consulta la web (un paciente
 * ajeno entra a la lista dándolo de alta con su DNI o dándole un turno, no buscándolo acá).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PacientesListViewModel(
    private val pacientes: PacientesRepository,
    private val turnos: TurnosRepository,
    private val hc: HcRepository,
    private val medicoId: Id?,
) : ViewModel() {
    val busqueda = MutableStateFlow("")
    private val remota = medicoId != null && turnos.esRemota(medicoId)
    private val _cargandoCartera = MutableStateFlow(false)
    val cargandoCartera: StateFlow<Boolean> = _cargandoCartera

    /** Por qué la lista puede estar vacía sin que sea culpa del médico. Null si no hubo problema. */
    private val _mensaje = MutableStateFlow<String?>(null)
    val mensaje: StateFlow<String?> = _mensaje

    /**
     * Paginado: la lista observa solo los primeros `limite` pacientes (filtro resuelto en SQL) y el límite crece de a
     * [PAGINA] cuando la pantalla llega al final. Así una cartera de miles de pacientes no se mapea ni recompone entera.
     */
    private val limite = MutableStateFlow(PAGINA)
    private val filtro = busqueda.debounce(200).map { it.trim() }.distinctUntilChanged()

    val lista: StateFlow<List<Paciente>> = combine(filtro, limite) { q, lim -> q to lim }
        .flatMapLatest { (q, lim) -> pacientes.observarPagina(medicoId, q, lim) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        if (medicoId != null) {
            viewModelScope.launch {
                _cargandoCartera.value = true
                if (remota) runCatching { turnos.sincronizarPacientesVinculados(medicoId) }
                // Y la cartera de cada historia clínica. Para el médico que no atiende por agenda es
                // la única que tiene: en turnos no le dieron ningún turno, así que no hay pacientes
                // de dónde sacarlos, y la lista le quedaba vacía.
                //
                // Si falla se avisa. Una lista vacía y un error acá se ven igual —no hay pacientes—,
                // y el motivo real (el médico sin vincular, la historia clínica sin habilitar) queda
                // escondido en el log, donde no lo ve nadie.
                runCatching { hc.sincronizarPacientes(medicoId) }
                    .onFailure { _mensaje.value = "No se pudo traer tu cartera de la historia clínica: ${it.message ?: "sin conexión"}" }
                _cargandoCartera.value = false
            }
        }
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
    /**
     * El DNI ya existía en el sistema: no se creó otro paciente, se lo asoció al médico y [paciente] es la ficha que
     * ya tenía el sistema (no la que se escribió). Null si fue un alta nueva o una edición.
     */
    val avisoExistente: String? = null,
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

    /** El DNI ya estaba en el dispositivo y falta que turnos asocie al médico (el `POST` de alta lo hace). */
    private var asociarEnTurnos = false

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
        _state.value = _state.value.copy(guardando = true, errores = emptyMap(), avisoRemoto = null, avisoExistente = null)
        viewModelScope.launch {
            // Primero el dispositivo, que no depende de la red; después turnosonlinebb.
            val guardado = pacientes.guardar(p, vincularA)
            // Un id distinto es que el DNI ya estaba en el dispositivo con otra ficha: se la asoció sin pisarla.
            val duplicadoLocal = guardado.id != p.id
            // Si turnos no llegó a asociarlo, el reintento tiene que volver a ser un alta (un PUT no asocia).
            if (duplicadoLocal) asociarEnTurnos = true
            val remoto = guardarEnTurnosOnline(guardado.id, esAlta = asociarEnTurnos)
            if (remoto.aviso == null) asociarEnTurnos = false
            val ficha = remoto.guardado?.paciente ?: guardado
            val yaExistia = duplicadoLocal || remoto.guardado?.yaExistia == true
            _state.value = _state.value.copy(
                paciente = ficha, guardando = false, guardado = true, avisoRemoto = remoto.aviso,
                avisoExistente = if (yaExistia) avisoDePacienteYaExistia() else null,
            )
            // Si ya existía no se navega solo: el aviso tiene que leerse, y la pantalla ofrece «Ver ficha».
            if (!yaExistia) onOk(ficha)
        }
    }

    /** Lo que pasó al mandar la ficha a turnosonlinebb: lo que devolvió (null si no correspondía o falló) y el aviso si falló. */
    private class ResultadoRemoto(val guardado: PacienteGuardado? = null, val aviso: String? = null)

    /**
     * Manda la ficha a turnosonlinebb cuando el médico es de la web (la da de alta si todavía no existe allá; si el DNI
     * ya existía allá, la ficha local se reemplaza por la de turnos y no quedan dos). Sin esto lo editado se pierde en
     * cuanto la web vuelve a informar al paciente. Si no se pudo, el resultado trae el aviso a mostrar.
     */
    private suspend fun guardarEnTurnosOnline(pacienteId: Id, esAlta: Boolean): ResultadoRemoto {
        val medicoId = vincularA ?: return ResultadoRemoto()
        if (!turnos.esRemota(medicoId)) return ResultadoRemoto()
        return try {
            ResultadoRemoto(guardado = turnos.guardarPacienteRemoto(medicoId, pacienteId, consultorioId = null, esAlta = esAlta))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ResultadoRemoto(aviso = "Se guardó en este dispositivo, pero no se pudo actualizar turnosonlinebb: ${e.message ?: "sin conexión"}. Volvé a guardar para reintentar.")
        }
    }
}

/** Ficha del paciente con su actividad: consultas por especialidad y turnos. */
class PacienteDetalleViewModel(
    pacientes: PacientesRepository,
    private val hc: HcRepository,
    turnos: TurnosRepository,
    private val pacienteId: Id,
    private val medicoId: Id,
) : ViewModel() {
    val paciente: StateFlow<Paciente?> = pacientes.observar(pacienteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val consultas: StateFlow<List<Consulta>> = hc.observarConsultasTodas(pacienteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val turnos: StateFlow<List<Turno>> = turnos.observarTurnosDePaciente(pacienteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Las historias clínicas con sistema propio se traen acá y no recién al entrar en cada una: el
        // médico abre la ficha para ver qué pasó con el paciente, y hasta ahora la lista salía vacía
        // aunque del otro lado hubiera consultas. La lista se observa de la base, así que aparecen solas.
        if (medicoId.isNotBlank()) {
            viewModelScope.launch {
                hc.especialidadesConApi.forEach { especialidad ->
                    runCatching { hc.traerConsultasRemotas(pacienteId, medicoId, especialidad) }
                }
            }
        }
    }
}

private fun <T> MutableStateFlow<T>.asStateFlowCompat(): StateFlow<T> = this
