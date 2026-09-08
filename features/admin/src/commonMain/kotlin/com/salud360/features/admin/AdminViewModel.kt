package com.salud360.features.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.AdminRepository
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.model.Id
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
import kotlinx.datetime.LocalDate

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

    fun crearUsuario(nombre: String, apellido: String, email: String, password: String, rol: Rol) = viewModelScope.launch {
        runCatching { admin.crearUsuario(nombre, apellido, email, password, rol) }
            .onSuccess { mensaje.value = "Usuario ${it.email} creado"; launch { sync.sincronizar() } }
            .onFailure { mensaje.value = "No se pudo crear el usuario: ${it.message ?: "sin conexión"}" }
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
    fun guardarLicencia(l: Licencia) = viewModelScope.launch { admin.guardarLicencia(l) }
    fun sincronizar() = viewModelScope.launch { mensaje.value = if (sync.sincronizar()) "Sincronizado" else "No se pudo sincronizar" }
}
