package com.salud360.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.createDatabase
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.dsl.module

/**
 * Punto de entrada Web. La URL del servidor puede indicarse con `?api=https://...`
 * (útil para desarrollo); si no, se usa [API_BASE_URL_DEFAULT].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val apiUrl = window.location.search.removePrefix("?").split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == "api" }
        ?.let { decodeURIComponent(it[1]) }
        ?: API_BASE_URL_DEFAULT

    CoroutineScope(Dispatchers.Default).launch {
        // La base (sql.js en un worker) se crea de forma asíncrona antes de montar la UI.
        val db = createDatabase(DriverFactory())
        iniciarKoin(db, listOf(module { single { ArchivoStore() } }), apiBaseUrl = apiUrl)
        document.getElementById("cargando")?.remove()
        ComposeViewport(document.body!!) { App() }
    }
}

private fun decodeURIComponent(s: String): String = js("decodeURIComponent(s)")
