package com.salud360.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.createDatabase
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.dsl.module

private const val CLAVE_API = "salud360.api"

/**
 * Punto de entrada Web. La URL del servidor se resuelve en este orden:
 * 1. `?api=https://...` en la URL (se recuerda en localStorage para las próximas visitas),
 * 2. lo recordado en localStorage,
 * 3. `http://localhost:8765` cuando la app se sirve desde localhost (desarrollo),
 * 4. [API_BASE_URL_DEFAULT].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val deQuery = window.location.search.removePrefix("?").split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == "api" }
        ?.let { decodeURIComponent(it[1]) }
        ?.trim()?.takeIf { it.isNotEmpty() }
    if (deQuery != null) runCatching { localStorage.setItem(CLAVE_API, deQuery) }
    val recordada = runCatching { localStorage.getItem(CLAVE_API) }.getOrNull()?.takeIf { it.isNotBlank() }
    val esLocal = window.location.hostname == "localhost" || window.location.hostname == "127.0.0.1"
    val apiUrl = deQuery ?: recordada ?: if (esLocal) "http://localhost:8765" else API_BASE_URL_DEFAULT

    CoroutineScope(Dispatchers.Default).launch {
        // La base (sql.js en un worker) se crea de forma asíncrona antes de montar la UI.
        val db = createDatabase(DriverFactory())
        iniciarKoin(db, listOf(module { single { ArchivoStore() } }), apiBaseUrl = apiUrl)
        document.getElementById("cargando")?.remove()
        // En web el contenido se acota al ancho de una tablet para que las secciones no se estiren a todo el monitor.
        ComposeViewport(document.body!!) { App(anchoMaximoContenido = 840.dp) }
    }
}

private fun decodeURIComponent(s: String): String = js("decodeURIComponent(s)")
