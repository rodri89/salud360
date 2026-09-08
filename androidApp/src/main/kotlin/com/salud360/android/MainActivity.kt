package com.salud360.android

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.salud360.app.App
import com.salud360.app.iniciarKoin
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.createDatabase
import com.salud360.features.hc.platform.contextGlobal
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class Salud360Application : Application() {
    override fun onCreate() {
        super.onCreate()
        contextGlobal = this
        val db = runBlocking { createDatabase(DriverFactory(this@Salud360Application)) }
        iniciarKoin(
            db = db,
            modulosPlataforma = listOf(module { single { ArchivoStore(this@Salud360Application) } }),
        ).androidContext(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}
