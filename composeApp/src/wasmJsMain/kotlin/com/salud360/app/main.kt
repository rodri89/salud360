package com.salud360.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
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
 * Punto de entrada Web. El entorno (dev = MAMP local, release = producción) queda fijado al compilar
 * (`./gradlew devWeb` / `releaseWeb`, ver [Entornos]). En dev, `{host}` de las URLs es el host desde el que se
 * abrió la página (normalmente `localhost`), así que la app le pega al MAMP de la misma máquina.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    val config = configEntorno(hostLocal = window.location.hostname.ifBlank { "localhost" })
    println("Salud 360 · entorno ${config.entorno} · turnos ${config.turnosUrl}")

    CoroutineScope(Dispatchers.Default).launch {
        // La base (sql.js en un worker) se crea de forma asíncrona antes de montar la UI.
        val db = createDatabase(DriverFactory())
        iniciarKoin(db, listOf(module { single { ArchivoStore() } }), config)
        document.getElementById("cargando")?.remove()
        // En web el contenido se acota al ancho de una tablet para que las secciones no se estiren a todo el monitor.
        // Las rutas se reflejan en la URL (#pacientes/…): recargar la página vuelve a la misma pantalla.
        ComposeViewport(document.body!!) {
            App(anchoMaximoContenido = 840.dp, alIniciarNavegacion = { nav -> nav.bindToBrowserNavigation() })
        }
    }
}
