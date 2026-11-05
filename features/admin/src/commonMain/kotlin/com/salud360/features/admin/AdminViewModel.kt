package com.salud360.features.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.AdminRepository
import com.salud360.core.data.network.tobb.TobbAltaUsuario
import com.salud360.core.data.repos.CambioDeAgenda
import com.salud360.core.data.repos.EstadoAgenda
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.repos.avisoDeAgendaGuardada
import com.salud360.core.data.repos.cambioDeAgenda
import com.salud360.core.data.repos.hoy
import com.salud360.core.data.repos.mostrarEnTurnosEnElAlta
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.newId
import com.salud360.core.model.turnos.Consultorio
import com.salud360.core.model.turnos.EspecialidadTurnos
import com.salud360.core.model.turnos.Feriado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Panel del administrador: usuarios, médicos, secretarias, consultorios, especialidades, feriados y licencias. */
class AdminViewModel(
    private val admin: AdminRepository,
    private val turnos: TurnosRepository,
    private val sync: SyncEngine,
    /** Códigos y nombres de las historias clínicas disponibles en la app. */
    val especialidadesHc: List<Pair<String, String>>,
) : ViewModel() {
    val usuarios: StateFlow<List<Usuario>> = admin.observarUsuarios().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medicos: StateFlow<List<Medico>> = admin.observarMedicos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val secretarias: StateFlow<List<Secretaria>> = admin.observarSecretarias().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val consultorios: StateFlow<List<Consultorio>> = turnos.observarConsultorios().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val especialidades: StateFlow<List<EspecialidadTurnos>> = admin.observarEspecialidades().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val feriados: StateFlow<List<Feriado>> = turnos.observarFeriados().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val licencias: StateFlow<List<Licencia>> = admin.observarLicencias().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mensaje = MutableStateFlow<String?>(null)

    private val _guardandoAgenda = MutableStateFlow<Set<Id>>(emptySet())

    /**
     * Médicos cuya agenda se está guardando en turnos ahora mismo: la tarjeta deshabilita sus interruptores mientras
     * espera la confirmación (D-P10, para que dos toques no manden dos pedidos cruzados).
     */
    val guardandoAgenda: StateFlow<Set<Id>> = _guardandoAgenda

    init {
        // Médicos, consultorios, especialidades, usuarios y secretarias son de turnosonlinebb, que es
        // su dueño. Se traen al abrir el panel y quedan en la base del dispositivo; las listas se
        // observan de ahí, así que aparecen solas. Antes esto lo llenaba el servidor propio de
        // Salud 360, que no está desplegado, y el panel se veía vacío.
        viewModelScope.launch {
            runCatching { turnos.sincronizarAdministracion() }
                .onFailure { mensaje.value = "No se pudo traer la administración de turnos: ${it.message ?: "sin conexión"}" }
        }
        // Las licencias son de la historia clínica, no de turnos: las trae su propia API y solo las
        // informa al administrador. Un médico no ve ninguna y la pestaña se comporta como antes.
        viewModelScope.launch {
            runCatching { admin.sincronizarLicencias() }
                .onFailure { mensaje.value = "No se pudieron traer las licencias: ${it.message ?: "sin conexión"}" }
        }
    }

    /**
     * Da de alta a quien va a usar la app. Va a turnosonlinebb, que es el dueño de la identidad: es
     * la contraseña con la que se entra, y la historia clínica valida la sesión contra ese sistema.
     *
     * El médico que solo usa historia clínica se crea con [tieneAgenda] en false y [mostrarEnTurnos] en false: queda
     * activo, que es lo que le permite entrar, pero no aparece en las listas para pedirle turno (RF-14). Ofrecerse
     * para pedir turno solo tiene sentido con agenda, así que sin agenda no se manda.
     */
    fun crearUsuario(
        nombre: String,
        apellido: String,
        email: String,
        password: String,
        rol: Rol,
        especialidadId: Id? = null,
        consultorioId: Id? = null,
        mostrarEnTurnos: Boolean = false,
        historiasClinicas: List<String> = emptyList(),
        /**
         * Si el médico tiene agenda de turnos (RF-14). Va al final y con valor por omisión para que el alta de
         * secretarias y administradores no lo necesite: para ellos no viaja (`null`, D-P5).
         */
        tieneAgenda: Boolean = false,
    ) = viewModelScope.launch {
        val esMedico = rol == Rol.MEDICO
        val alta = TobbAltaUsuario(
            nombre = nombre.trim(), apellido = apellido.trim(), email = email.trim().lowercase(), password = password,
            rol = rol.name.lowercase(),
            especialidadId = TobbIds.numero(especialidadId),
            consultorioId = TobbIds.numero(consultorioId),
            mostrarEnTurnos = mostrarEnTurnosEnElAlta(esMedico, tieneAgenda, mostrarEnTurnos),
            tieneAgenda = if (esMedico) tieneAgenda else null,
            historiasClinicas = historiasClinicas,
        )
        runCatching { turnos.crearUsuario(alta) }
            .onSuccess { mensaje.value = "Usuario ${it.email} creado" }
            .onFailure { mensaje.value = "No se pudo crear el usuario: ${it.message ?: "sin conexión"}" }
    }

    /**
     * Prende o apaga «Tiene agenda de turnos» de un médico (RF-2, RF-5, RF-18). El dueño del dato es turnos: no hay
     * cambio optimista, la tarjeta sigue mostrando lo guardado hasta que turnos confirma, y la copia del dispositivo la
     * escribe el repositorio recién con esa respuesta. Qué pedido sale lo decide `AgendaDelMedico.cambioDeAgenda`
     * (apagar la agenda apaga también «visible»); si turnos no confirma, [mensaje] dice por qué y el interruptor vuelve solo.
     */
    fun cambiarAgenda(m: Medico, tieneAgenda: Boolean) = pedirCambioDeAgenda(m, tieneAgenda = tieneAgenda, visible = null)

    /** Prende o apaga «Visible para pacientes en la web» (RF-2, RF-6), con la misma espera de confirmación que [cambiarAgenda]. */
    fun cambiarVisible(m: Medico, visible: Boolean) = pedirCambioDeAgenda(m, tieneAgenda = null, visible = visible)

    private fun pedirCambioDeAgenda(m: Medico, tieneAgenda: Boolean?, visible: Boolean?) = viewModelScope.launch {
        // `m` es la copia que tiene la pantalla: se manda solo el campo tocado, así no se pisa con un valor viejo lo
        // que otro administrador haya cambiado en el otro (D-P4).
        val antes = EstadoAgenda(tieneAgenda = m.tieneTurnos, visible = m.visibleEnTurnos)
        val cambio = when (val pedido = cambioDeAgenda(antes, tieneAgenda = tieneAgenda, visible = visible)) {
            is CambioDeAgenda.Rechazado -> {
                mensaje.value = pedido.motivo
                return@launch
            }
            is CambioDeAgenda.Enviar -> pedido
        }
        _guardandoAgenda.value = _guardandoAgenda.value + m.id
        try {
            runCatching { turnos.guardarAgenda(m.id, tieneAgenda = cambio.tieneAgenda, visible = cambio.visible) }
                .onSuccess { guardada ->
                    // RF-18: si la agenda se apagó y el médico tiene turnos futuros, se avisa cuántos (no se cancelan).
                    mensaje.value = avisoDeAgendaGuardada(antes, guardada) ?: "Agenda guardada en turnos"
                }
                .onFailure { mensaje.value = "No se pudo guardar la agenda: ${it.message ?: "sin conexión"}" }
        } finally {
            _guardandoAgenda.value = _guardandoAgenda.value - m.id
        }
    }

    fun guardarUsuario(u: Usuario) = viewModelScope.launch { admin.guardarUsuario(u) }
    fun guardarMedico(m: Medico) = viewModelScope.launch { admin.guardarMedico(m) }
    fun setEspecialidadHc(m: Medico, codigo: String, habilitada: Boolean) = viewModelScope.launch { admin.setEspecialidadHc(m.id, codigo, habilitada) }
    fun guardarSecretaria(s: Secretaria) = viewModelScope.launch { admin.guardarSecretaria(s) }
    fun guardarConsultorio(c: Consultorio) = viewModelScope.launch { turnos.guardarConsultorio(c) }
    fun nuevoConsultorio(nombre: String, direccion: String, telefono: String) = guardarConsultorio(Consultorio(newId(), nombre, direccion, telefono))
    fun guardarEspecialidad(e: EspecialidadTurnos) = viewModelScope.launch { admin.guardarEspecialidad(e) }
    fun nuevaEspecialidad(nombre: String, codigoHc: String?) = guardarEspecialidad(EspecialidadTurnos(newId(), nombre, codigoHc))
    fun agregarFeriado(fecha: LocalDate, descripcion: String) = viewModelScope.launch { turnos.guardarFeriado(Feriado(newId(), fecha, descripcion)) }
    fun eliminarFeriado(f: Feriado) = viewModelScope.launch { turnos.eliminarFeriado(f) }
    /**
     * La licencia manda sobre el ingreso del médico a la historia clínica, así que el guardado que
     * importa es el de allá: si no llegó, se avisa en vez de dejar la pantalla como si hubiera andado.
     */
    fun guardarLicencia(l: Licencia) = viewModelScope.launch {
        runCatching { admin.guardarLicencia(l) }
            .onSuccess { if (!it) mensaje.value = "La licencia quedó guardada en este dispositivo, pero la historia clínica no la aceptó." }
            .onFailure { mensaje.value = "No se pudo guardar la licencia: ${it.message ?: "sin conexión"}" }
    }

    /**
     * Da de alta la licencia de un médico que no tenía ninguna. Sin licencia no entra a la historia
     * clínica, así que cargarla es el paso de habilitarlo: un año, con aviso el último mes.
     */
    fun nuevaLicencia(medicoId: Id) {
        val vence = hoy().plus(DatePeriod(years = 1))
        guardarLicencia(Licencia(medicoId, vence.toString(), vence.minus(DatePeriod(months = 1)).toString()))
    }

    fun sincronizar() = viewModelScope.launch { mensaje.value = if (sync.sincronizar()) "Sincronizado" else "No se pudo sincronizar" }
}
