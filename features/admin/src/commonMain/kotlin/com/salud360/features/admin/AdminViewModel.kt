package com.salud360.features.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.AdminRepository
import com.salud360.core.data.network.tobb.TobbAltaUsuario
import com.salud360.core.data.repos.CambioDeAgenda
import com.salud360.core.data.repos.EstadoAgenda
import com.salud360.core.data.repos.MOTIVO_SIN_MEDICO_DEL_USUARIO
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.repos.avisoDeAgendaGuardada
import com.salud360.core.data.repos.avisoDeMedicoActivoGuardado
import com.salud360.core.data.repos.avisoDeMedicoActivoNoGuardado
import com.salud360.core.data.repos.avisoDeHistoriasClinicasGuardadas
import com.salud360.core.data.repos.avisoDeHistoriasClinicasNoGuardadas
import com.salud360.core.data.repos.cambioDeAgenda
import com.salud360.core.data.repos.hoy
import com.salud360.core.data.repos.licenciasVisiblesEnElPanel
import com.salud360.core.data.repos.medicosVisiblesEnElPanel
import com.salud360.core.data.repos.mostrarEnTurnosEnElAlta
import com.salud360.core.data.repos.nuevasHistoriasClinicas
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    /**
     * Médicos que muestran las pestañas «Médicos» y «Licencias»: sin los desactivados (el médico o su usuario). La
     * pestaña «Secretarias» sigue usando [medicos], para que una secretaria con un médico inactivo tildado pueda destildarlo.
     */
    val medicosActivos: StateFlow<List<Medico>> = combine(admin.observarMedicos(), admin.observarUsuarios(), ::medicosVisiblesEnElPanel)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Licencias de [medicosActivos]. */
    val licenciasVisibles: StateFlow<List<Licencia>> = combine(admin.observarLicencias(), medicosActivos, ::licenciasVisiblesEnElPanel)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Último aviso para el administrador. La pantalla lo muestra en un snackbar y lo limpia al mostrarlo, así el mismo
     * texto repetido se vuelve a mostrar. Se publica con [avisar] y [avisarError].
     */
    val mensaje = MutableStateFlow<Aviso?>(null)

    private fun avisar(texto: String) { mensaje.value = Aviso(texto) }
    private fun avisarError(texto: String) { mensaje.value = Aviso(texto, esError = true) }

    private val _guardandoAgenda = MutableStateFlow<Set<Id>>(emptySet())

    /**
     * Médicos cuya agenda se está guardando en turnos ahora mismo: la tarjeta deshabilita sus interruptores mientras
     * espera la confirmación (D-P10, para que dos toques no manden dos pedidos cruzados).
     */
    val guardandoAgenda: StateFlow<Set<Id>> = _guardandoAgenda

    private val _guardandoHc = MutableStateFlow<Set<Id>>(emptySet())

    /**
     * Médicos cuyas historias clínicas se están guardando en turnos ahora mismo: la tarjeta deshabilita sus casillas
     * mientras espera, para no mandar dos listas pisadas una sobre la otra.
     */
    val guardandoHc: StateFlow<Set<Id>> = _guardandoHc

    private val _guardandoUsuarios = MutableStateFlow<Set<Id>>(emptySet())

    /** Usuarios (por id de usuario) cuyo «activo» se está guardando en turnos ahora mismo: su botón queda deshabilitado. */
    val guardandoUsuarios: StateFlow<Set<Id>> = _guardandoUsuarios

    init {
        // Médicos, consultorios, especialidades, usuarios y secretarias son de turnosonlinebb, que es
        // su dueño. Se traen al abrir el panel y quedan en la base del dispositivo; las listas se
        // observan de ahí, así que aparecen solas. Antes esto lo llenaba el servidor propio de
        // Salud 360, que no está desplegado, y el panel se veía vacío.
        viewModelScope.launch {
            runCatching { turnos.sincronizarAdministracion() }
                .onFailure { avisarError("No se pudo traer la administración de turnos: ${it.message ?: "sin conexión"}") }
        }
        // Las licencias son de la historia clínica, no de turnos: las trae su propia API y solo las
        // informa al administrador. Un médico no ve ninguna y la pestaña se comporta como antes.
        viewModelScope.launch {
            runCatching { admin.sincronizarLicencias() }
                .onFailure { avisarError("No se pudieron traer las licencias: ${it.message ?: "sin conexión"}") }
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
            .onSuccess { avisar("Usuario ${it.email} creado") }
            .onFailure { avisarError("No se pudo crear el usuario: ${it.message ?: "sin conexión"}") }
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
                avisarError(pedido.motivo)
                return@launch
            }
            is CambioDeAgenda.Enviar -> pedido
        }
        _guardandoAgenda.value = _guardandoAgenda.value + m.id
        try {
            runCatching { turnos.guardarAgenda(m.id, tieneAgenda = cambio.tieneAgenda, visible = cambio.visible) }
                .onSuccess { guardada ->
                    // RF-18: si la agenda se apagó y el médico tiene turnos futuros, se avisa cuántos (no se cancelan).
                    avisar(avisoDeAgendaGuardada(antes, guardada) ?: "Agenda guardada en turnos")
                }
                .onFailure { avisarError("No se pudo guardar la agenda: ${it.message ?: "sin conexión"}") }
        } finally {
            _guardandoAgenda.value = _guardandoAgenda.value - m.id
        }
    }

    /**
     * Activa o desactiva a un usuario médico desde la pestaña Usuarios. El dueño del dato es turnos (donde el «activo»
     * del usuario es el del médico): se pide con `PUT medicos/{id}/activo` y no hay cambio optimista, la tabla sigue
     * mostrando lo que hay en la base local, que el repositorio escribe recién con la confirmación. Mientras espera,
     * el botón de ese usuario queda quieto ([guardandoUsuarios]) y un segundo pedido del mismo usuario se ignora.
     * Éxito o error se avisan por [mensaje]. Quién puede cambiarlo lo decide `puedeCambiarActivoDelUsuario`.
     */
    fun cambiarActivoUsuario(u: Usuario, activo: Boolean) = viewModelScope.launch {
        if (u.id in _guardandoUsuarios.value) return@launch
        _guardandoUsuarios.value = _guardandoUsuarios.value + u.id
        try {
            runCatching {
                val medico = admin.observarMedicos().first().firstOrNull { it.usuarioId == u.id }
                    ?: throw IllegalStateException(MOTIVO_SIN_MEDICO_DEL_USUARIO)
                turnos.guardarActivoMedico(medico.id, activo)
            }
                .onSuccess { avisar(avisoDeMedicoActivoGuardado(it)) }
                .onFailure { avisarError(avisoDeMedicoActivoNoGuardado(it.message)) }
        } finally {
            _guardandoUsuarios.value = _guardandoUsuarios.value - u.id
        }
    }

    fun guardarMedico(m: Medico) = viewModelScope.launch { admin.guardarMedico(m) }

    /**
     * Habilita o quita una historia clínica del médico. El dueño del dato es turnos: se manda la lista exacta que debe
     * quedar y no hay cambio optimista; la casilla sigue mostrando lo guardado hasta que turnos confirma (la copia del
     * dispositivo la escribe el repositorio recién con esa respuesta). Mientras espera, las casillas de ese médico
     * quedan quietas ([guardandoHc]). Éxito o error se avisan por [mensaje].
     */
    fun setEspecialidadHc(m: Medico, codigo: String, habilitada: Boolean) = viewModelScope.launch {
        if (m.id in _guardandoHc.value) return@launch
        val lista = nuevasHistoriasClinicas(m.especialidadesHc, codigo, habilitada)
        _guardandoHc.value = _guardandoHc.value + m.id
        try {
            runCatching { turnos.guardarHistoriasClinicas(m.id, lista) }
                .onSuccess { avisar(avisoDeHistoriasClinicasGuardadas()) }
                .onFailure { avisarError(avisoDeHistoriasClinicasNoGuardadas(it.message)) }
        } finally {
            _guardandoHc.value = _guardandoHc.value - m.id
        }
    }

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
            .onSuccess { if (!it) avisarError("La licencia quedó guardada en este dispositivo, pero la historia clínica no la aceptó.") }
            .onFailure { avisarError("No se pudo guardar la licencia: ${it.message ?: "sin conexión"}") }
    }

    /**
     * Da de alta la licencia de un médico que no tenía ninguna. Sin licencia no entra a la historia
     * clínica, así que cargarla es el paso de habilitarlo: un año, con aviso el último mes.
     */
    fun nuevaLicencia(medicoId: Id) {
        val vence = hoy().plus(DatePeriod(years = 1))
        guardarLicencia(Licencia(medicoId, vence.toString(), vence.minus(DatePeriod(months = 1)).toString()))
    }

    fun sincronizar() = viewModelScope.launch { if (sync.sincronizar()) avisar("Sincronizado") else avisarError("No se pudo sincronizar") }
}

/** Aviso del panel de administración: [esError] lo muestra con el color de error en lugar del de éxito. */
data class Aviso(val texto: String, val esError: Boolean = false)
