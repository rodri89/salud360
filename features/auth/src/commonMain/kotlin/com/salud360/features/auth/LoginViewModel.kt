package com.salud360.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.AppConfig
import com.salud360.core.data.repos.AuthRepository
import com.salud360.core.data.repos.ResultadoLogin
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.model.auth.Credenciales
import com.salud360.core.model.auth.Sesion
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    /** "Recordar este mail": el mail se guarda en el dispositivo y se completa solo la próxima vez. */
    val recordarEmail: Boolean = false,
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
    private val settings: Settings,
) : ViewModel() {
    private val emailRecordado = settings.getStringOrNull(CLAVE_EMAIL_RECORDADO)?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(LoginUiState(
        email = emailRecordado ?: "", recordarEmail = emailRecordado != null,
        // En dev se muestra contra qué MAMP se está probando; en release solo el nombre del entorno.
        servidor = if (config.esDev) "dev · ${config.turnosUrl}" else "release",
    ))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    // Al tocar los campos se limpia el aviso anterior: si no, el cartel de licencia queda al probar con otro usuario.
    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null, licenciaVencida = false) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null, licenciaVencida = false) }

    /** Al destildar se olvida enseguida; al tildar se guarda recién cuando el ingreso sale bien. */
    fun onRecordarEmail(v: Boolean) {
        _state.update { it.copy(recordarEmail = v) }
        if (!v) settings.remove(CLAVE_EMAIL_RECORDADO)
    }

    private fun guardarEmailSiCorresponde(email: String) {
        if (_state.value.recordarEmail) settings.putString(CLAVE_EMAIL_RECORDADO, email.trim().lowercase())
        else settings.remove(CLAVE_EMAIL_RECORDADO)
    }

    fun login(onOk: (Sesion) -> Unit) {
        val s = _state.value
        if (s.email.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "Ingresá tu mail y contraseña") }
            return
        }
        _state.update { it.copy(cargando = true, error = null, licenciaVencida = false) }
        viewModelScope.launch {
            when (val r = auth.login(Credenciales(s.email, s.password))) {
                is ResultadoLogin.Ok -> {
                    guardarEmailSiCorresponde(s.email)
                    _state.update { it.copy(cargando = false, avisoOffline = r.offline) }
                    if (!r.offline) launch { sync.sincronizar(); auth.refrescarPerfil() }
                    onOk(r.sesion)
                }
                is ResultadoLogin.LicenciaVencida -> { guardarEmailSiCorresponde(s.email); _state.update { it.copy(cargando = false, licenciaVencida = true) } }
                // Se muestra el motivo que da turnosonlinebb: no siempre es la contraseña (por ejemplo, un usuario
                // sin permiso para usar Salud 360 daba el mismo aviso y no había forma de saberlo desde la pantalla).
                is ResultadoLogin.CredencialesInvalidas -> _state.update {
                    it.copy(cargando = false, error = r.mensaje.ifBlank { "Mail o contraseña incorrectos" })
                }
                ResultadoLogin.SinConexionYSinSesionPrevia -> _state.update {
                    it.copy(cargando = false, error = "No se pudo conectar con turnosonlinebb. Verificá la conexión a internet. Para ingresar sin internet primero tenés que haber iniciado sesión en este dispositivo.")
                }
                is ResultadoLogin.Error -> _state.update { it.copy(cargando = false, error = r.mensaje) }
            }
        }
    }

    private companion object {
        const val CLAVE_EMAIL_RECORDADO = "login.email_recordado"
    }
}
