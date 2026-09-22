package com.salud360.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import com.salud360.core.data.repos.AuthRepository
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.ui.components.LoadingIndicator
import com.salud360.core.ui.theme.Salud360Theme
import com.salud360.features.auth.LoginScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Raíz de la aplicación: restaura la sesión guardada, muestra el login o el shell principal
 * según el rol, y mantiene la sincronización periódica mientras la app está abierta.
 */
@Composable
/**
 * `anchoMaximoContenido`: si se pasa (web), el contenido del shell se acota a ese ancho y se centra.
 * `alIniciarNavegacion`: gancho de la plataforma al crear el navegador de pantallas (en web ata las rutas a la URL,
 * así recargar la página vuelve a la misma pantalla).
 */
fun App(darkTheme: Boolean? = null, anchoMaximoContenido: Dp? = null, alIniciarNavegacion: (suspend (NavHostController) -> Unit)? = null) {
    Salud360Theme(darkTheme = darkTheme ?: androidx.compose.foundation.isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val auth = koinInject<AuthRepository>()
            val sync = koinInject<SyncEngine>()
            val sesion by auth.sesion.collectAsState()
            var restaurando by remember { mutableStateOf(true) }
            val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

            LaunchedEffect(Unit) {
                auth.restaurar()
                restaurando = false
            }
            LaunchedEffect(sesion?.token) {
                if (sesion != null) sync.iniciarPeriodico(appScope) else sync.detener()
            }

            when {
                restaurando -> Box(Modifier.fillMaxSize()) { LoadingIndicator(text = "Abriendo Salud 360...") }
                sesion == null -> LoginScreen(onLogin = {})
                else -> MainShell(sesion = sesion!!, onLogout = { appScope.launch { auth.logout() } }, anchoMaximoContenido = anchoMaximoContenido, alIniciarNavegacion = alIniciarNavegacion)
            }
        }
    }
}
