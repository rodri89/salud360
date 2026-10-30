package com.salud360.core.data

import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.hc.EnfermedadesApiRemota
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcApiClients
import com.salud360.core.data.network.hc.HcApiSoloLectura
import com.salud360.core.data.network.hc.PacientesHcApiRemota
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.repos.AdminRepository
import com.salud360.core.data.repos.AgendaTurnosOnline
import com.salud360.core.data.repos.AuthRepository
import com.salud360.core.data.repos.ConfigHcApiRemota
import com.salud360.core.data.repos.ConfigHcRepository
import com.salud360.core.data.repos.EnfermedadesRepository
import com.salud360.core.data.repos.HcBackends
import com.salud360.core.data.repos.HcClinicaBackend
import com.salud360.core.data.repos.HcPediatriaBackend
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.data.repos.PacientesHcRepository
import com.salud360.core.data.repos.PacientesRepository
import com.salud360.core.data.repos.PerfilesHcRepository
import com.salud360.core.data.repos.RevocaSesion
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.sync.HcApiSync
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.database.Salud360Db
import com.russhwolf.settings.Settings
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.newId
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Configuración del entorno en el que corre la app.
 *
 * @param entorno "dev" (MAMP local) o "release" (producción).
 * @param turnosUrl base de turnosonlinebb (identidad, agenda, pacientes); la API cuelga de `<turnosUrl>/api/salud360/`.
 * @param hcUrls base de la API de cada historia clínica por código de especialidad (ej. "pediatria" → hc_pediatria).
 * @param apiBaseUrl servidor de sincronización propio (Ktor, hoy no desplegado).
 */
data class AppConfig(
    val entorno: String,
    val turnosUrl: String,
    val hcUrls: Map<String, String> = emptyMap(),
    val apiBaseUrl: String = turnosUrl.trimEnd('/') + "/api/salud360",
) {
    val esDev: Boolean get() = entorno == "dev"

    /** URL base de la API de una historia clínica, o null si esa especialidad todavía no tiene API configurada. */
    fun hcUrl(codigoEspecialidad: String): String? = hcUrls[codigoEspecialidad]?.takeIf { it.isNotBlank() }
}

/**
 * Módulo Koin de la capa de datos.
 * La base se crea de forma asíncrona en el arranque de cada plataforma (`createDatabase`) y se
 * pasa ya lista; la plataforma también aporta [com.salud360.core.data.files.ArchivoStore].
 *
 * @param nombresHc código de HC → nombre visible, del registro de especialidades (para los motivos de la cartera).
 */
fun dataModule(db: Salud360Db, config: AppConfig, nombresHc: Map<String, String>): Module = module {
    single { config }
    single { db }
    single { Settings() }
    single { ApiClient(config.apiBaseUrl) }
    single { TurnosOnlineClient(config.turnosUrl) }
    single {
        val settings = get<Settings>()
        val id = settings.getStringOrNull("dispositivo_id") ?: newId().also { settings.putString("dispositivo_id", it) }
        SyncEngine(get(), get(), get(), id)
    }
    // Clientes de las historias clínicas que ya tienen API propia (`salud360.entorno.*.hc.<codigo>`).
    // Las que no están configuradas siguen guardando solo en el dispositivo, sin cambiar nada.
    single {
        HcApiClients(
            config.hcUrls.mapNotNull { (codigo, url) ->
                url.takeIf { it.isNotBlank() }?.let { codigo to HcApiClient(it, codigo) }
            }.toMap(),
        )
    }
    // `HcBackendsSoloLecturaTest` obliga a decidir `soloLectura` de cada código que se agregue acá. La HC general se
    // registra de solo lectura (spec 002, entrega L): trae las consultas de la web y no manda nada. Usa el mismo
    // cliente (misma URL y mismo token) que su configuración, su cartera y sus enfermedades, pero envuelto en
    // `HcApiSoloLectura`: el backend no recibe con qué escribir. Sin URL de la HC general en el entorno no se registra
    // y todo queda como antes (sin API).
    single {
        HcBackends(
            get<HcApiClients>().porEspecialidad.mapNotNull { (codigo, cliente) ->
                when (codigo) {
                    "pediatria" -> codigo to HcPediatriaBackend(get(), cliente)
                    CodigosHc.GENERAL -> codigo to HcClinicaBackend(get(), HcApiSoloLectura(cliente), get<PacientesHcRepository>())
                    else -> null
                }
            }.toMap(),
        )
    }
    // Cartera y vínculo de pacientes de cada HC con API, pediatría y la general por igual (spec 002). Las consultas de
    // la general solo se leen (`HcClinicaBackend` arriba).
    single {
        val clientes = get<HcApiClients>().porEspecialidad
        PacientesHcApis(
            listOfNotNull(
                clientes["pediatria"]?.let { "pediatria" to PacientesHcApiRemota(it, "pediatria_paciente_id") },
                clientes[CodigosHc.GENERAL]?.let { CodigosHc.GENERAL to PacientesHcApiRemota(it, "hc_paciente_id") },
            ).toMap(),
        )
    }
    single { PacientesHcRepository(get(), get(), nombresHc) }
    single { HcApiSync(get(), get<HcBackends>().porEspecialidad, get()) }
    // `alCerrarSesion`: al cerrar sesión, los pedidos de enfermedades que ya salieron no reescriben la copia (E12).
    // `revocar` (RF-59): al cerrar sesión, en segundo plano, turnos y cada HC con API (hclinica y pediatría) olvidan el token.
    single {
        val enfermedades = get<EnfermedadesRepository>()
        val turnos = get<TurnosOnlineClient>()
        val clientes = get<HcApiClients>().porEspecialidad
        AuthRepository(
            get(), get(), turnos, get(), clientes, enfermedades::cerrarSesion,
            revocar = listOf(RevocaSesion(turnos::cerrarSesion)) + clientes.values.map { RevocaSesion(it::salir) },
        )
    }
    single { PacientesRepository(get()) }
    single { HcRepository(get(), get<HcBackends>().porEspecialidad) }
    single { AgendaTurnosOnline(get(), get()) }
    single { TurnosRepository(get(), get()) }
    single { AdminRepository(get(), get(), get<HcBackends>().porEspecialidad) }
    // HC general (hclinica): configuración por perfiles, fuera de `HcBackends` (que solo lleva sus consultas, de la spec 002).
    // Sin URL de hclinica en el entorno los dos se comportan como sin conexión.
    single { ConfigHcRepository(get(), get<HcApiClients>().porEspecialidad[CodigosHc.GENERAL]?.let { ConfigHcApiRemota(it) }) }
    single { PerfilesHcRepository(get<HcApiClients>().porEspecialidad[CodigosHc.GENERAL]) }
    // Enfermedades de la HC general (spec 003): también fuera de `HcBackends`; nunca pasan por `HcApiSync` ni `SyncEngine`.
    // El controlador de cada consulta lo arma su ViewModel con `PacientesHcRepository`.
    single {
        EnfermedadesRepository(
            get(),
            get<HcApiClients>().porEspecialidad[CodigosHc.GENERAL]?.let { EnfermedadesApiRemota(it) },
            nombresHc[CodigosHc.GENERAL] ?: CodigosHc.GENERAL,
        )
    }
}
