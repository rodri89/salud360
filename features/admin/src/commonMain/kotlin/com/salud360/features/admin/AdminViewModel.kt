package com.salud360.features.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.AdminRepository
import com.salud360.core.data.network.tobb.TobbAltaUsuario
import com.salud360.core.data.repos.PerfilesHcRepository
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.repos.hoy
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.newId
import com.salud360.core.model.turnos.Consultorio
import com.salud360.core.model.turnos.EspecialidadTurnos
import com.salud360.core.model.turnos.Feriado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    /** Para asignar el perfil de la HC general en el alta de un médico (RF-18). */
    private val perfilesHc: PerfilesHcRepository,
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
     * El médico que solo usa historia clínica se crea con [mostrarEnTurnos] en false: queda activo,
     * que es lo que le permite entrar, pero no aparece en las listas para pedirle turno.
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
        /** Perfil de la HC general, si se habilitó "Clínica" (RF-18); se asigna después del alta. */
        perfilId: Long? = null,
    ) = viewModelScope.launch {
        val alta = TobbAltaUsuario(
            nombre = nombre.trim(), apellido = apellido.trim(), email = email.trim().lowercase(), password = password,
            rol = rol.name.lowercase(),
            especialidadId = TobbIds.numero(especialidadId),
            consultorioId = TobbIds.numero(consultorioId),
            mostrarEnTurnos = mostrarEnTurnos,
            historiasClinicas = historiasClinicas,
        )
        val creado = runCatching { turnos.crearUsuario(alta) }
            .getOrElse { mensaje.value = "No se pudo crear el usuario: ${it.message ?: "sin conexión"}"; return@launch }
        val email = creado.usuario.email
        val numero = creado.medicoIdTobb
        // El perfil va por número de médico de turnos: hclinica lo guarda aunque todavía no tenga el usuario
        // vinculado. Si no se puede, el usuario ya existe en turnos: se avisa qué falta, no que falló el alta.
        mensaje.value = when {
            perfilId == null || CodigosHc.GENERAL !in historiasClinicas -> "Usuario $email creado"
            numero == null -> "Usuario $email creado; falta asignarle el perfil: turnos no devolvió su número de médico"
            else -> runCatching { perfilesHc.asignar(numero, perfilId) }.fold(
                onSuccess = { "Usuario $email creado, con su perfil de la historia clínica general" },
                onFailure = { "Usuario $email creado; falta asignarle el perfil: ${it.message ?: "sin conexión"}" },
            )
        }
    }

    fun guardarUsuario(u: Usuario) = viewModelScope.launch { admin.guardarUsuario(u) }
    fun guardarMedico(m: Medico) = viewModelScope.launch { admin.guardarMedico(m) }

    /** Médicos con un cambio de historias clínicas en camino: sus casillas esperan la respuesta de turnos. */
    val hcEnCurso = MutableStateFlow(emptySet<Id>())

    /**
     * Las historias clínicas habilitadas son de turnos: se le manda la lista completa y el dispositivo se actualiza
     * solo con lo que turnos acepta (sin `dirty`). Si falla, la casilla queda como estaba (RF-18).
     */
    fun setEspecialidadHc(m: Medico, codigo: String, habilitada: Boolean) {
        if (m.id in hcEnCurso.value) return
        val nuevas = if (habilitada) (m.especialidadesHc + codigo).distinct() else m.especialidadesHc - codigo
        hcEnCurso.update { it + m.id }
        viewModelScope.launch {
            runCatching { turnos.guardarHistoriasClinicas(m.id, nuevas) }
                .onSuccess { mensaje.value = "Historias clínicas de ${m.nombreCompleto} actualizadas en turnos" }
                .onFailure { mensaje.value = "No se pudieron cambiar las historias clínicas de ${m.nombreCompleto}: ${it.message ?: "sin conexión"}" }
            hcEnCurso.update { it - m.id }
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
