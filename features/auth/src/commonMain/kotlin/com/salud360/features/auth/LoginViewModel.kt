package com.salud360.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.AppConfig
import com.salud360.core.data.repos.AuthRepository
import com.salud360.core.data.repos.ResultadoLogin
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.model.auth.Credenciales
import com.salud360.core.model.auth.Sesion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val cargando: Boolean = false,
    val error: String? = null,
    val avisoOffline: Boolean = false,
    val licenciaVencida: Boolean = false,
    /** URL del servidor de Salud 360 al que se conecta la app (se muestra para diagnosticar). */
    val servidor: String = "",
)

class LoginViewModel(
    private val auth: AuthRepository,
    private val sync: SyncEngine,
    private val config: AppConfig,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState(servidor = config.apiBaseUrl))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun login(onOk: (Sesion) -> Unit) {
        val s = _state.value
        if (s.email.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "Ingresá tu mail y contraseña") }
            return
        }
        _state.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            when (val r = auth.login(Credenciales(s.email, s.password))) {
                is ResultadoLogin.Ok -> {
                    _state.update { it.copy(cargando = false, avisoOffline = r.offline) }
                    if (!r.offline) launch { sync.sincronizar(); auth.refrescarPerfil() }
                    onOk(r.sesion)
                }
                is ResultadoLogin.LicenciaVencida -> _state.update { it.copy(cargando = false, licenciaVencida = true) }
                ResultadoLogin.CredencialesInvalidas -> _state.update { it.copy(cargando = false, error = "Mail o contraseña incorrectos") }
                ResultadoLogin.SinConexionYSinSesionPrevia -> _state.update {
                    it.copy(cargando = false, error = "No se pudo conectar con el servidor ${config.apiBaseUrl}. Verificá la conexión o la URL del servidor. Para ingresar sin internet primero tenés que haber iniciado sesión en este dispositivo.")
                }
                is ResultadoLogin.Error -> _state.update { it.copy(cargando = false, error = r.mensaje) }
            }
        }
    }
}
