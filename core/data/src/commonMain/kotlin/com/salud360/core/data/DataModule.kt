package com.salud360.core.data

import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.repos.AdminRepository
import com.salud360.core.data.repos.AgendaTurnosOnline
import com.salud360.core.data.repos.AuthRepository
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.data.repos.PacientesRepository
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.database.Salud360Db
import com.russhwolf.settings.Settings
import com.salud360.core.model.newId
import org.koin.core.module.Module
import org.koin.dsl.module

/** Configuración del entorno (URL del servidor). */
data class AppConfig(val apiBaseUrl: String)

/**
 * Módulo Koin de la capa de datos.
 * La base se crea de forma asíncrona en el arranque de cada plataforma (`createDatabase`) y se
 * pasa ya lista; la plataforma también aporta [com.salud360.core.data.files.ArchivoStore].
 */
fun dataModule(db: Salud360Db, config: AppConfig): Module = module {
    single { config }
    single { db }
    single { Settings() }
    single { ApiClient(config.apiBaseUrl) }
    single { TurnosOnlineClient() }
    single {
        val settings = get<Settings>()
        val id = settings.getStringOrNull("dispositivo_id") ?: newId().also { settings.putString("dispositivo_id", it) }
        SyncEngine(get(), get(), get(), id)
    }
    single { AuthRepository(get(), get(), get(), get()) }
    single { PacientesRepository(get()) }
    single { HcRepository(get()) }
    single { AgendaTurnosOnline(get(), get()) }
    single { TurnosRepository(get(), get()) }
    single { AdminRepository(get(), get()) }
}
