package com.salud360.app

import androidx.compose.ui.window.ComposeUIViewController
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.createDatabase
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module
import platform.UIKit.UIViewController

private var inicializado = false

/** Punto de entrada usado por `iosApp/iosApp/ContentView.swift`. */
fun MainViewController(): UIViewController {
    if (!inicializado) {
        val db = runBlocking { createDatabase(DriverFactory()) }
        // En dev, el simulador iOS ve la Mac como localhost; para un iPhone físico compilar con -PdevHost=<ip de la Mac>.
        iniciarKoin(db, listOf(module { single { ArchivoStore() } }), configEntorno(hostLocal = "localhost"))
        inicializado = true
    }
    return ComposeUIViewController { App() }
}
