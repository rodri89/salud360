package com.salud360.app

import com.salud360.core.data.AppConfig
import com.salud360.core.data.dataModule
import com.salud360.core.database.Salud360Db
import com.salud360.especialidades.cardiologia.cardiologiaContribution
import com.salud360.especialidades.clinica.clinicaContribution
import com.salud360.especialidades.clinica.hepatologiaContribution
import com.salud360.especialidades.desarrolloinfantil.desarrolloInfantilContribution
import com.salud360.especialidades.endocrinologia.endocrinologiaContribution
import com.salud360.especialidades.gineco.ginecoContribution
import com.salud360.especialidades.hematologia.hematologiaContribution
import com.salud360.especialidades.pediatria.pediatriaContribution
import com.salud360.features.admin.adminFeatureModule
import com.salud360.features.auth.authFeatureModule
import com.salud360.features.hc.EspecialidadContribution
import com.salud360.features.hc.hcFeatureModule
import com.salud360.features.pacientes.pacientesFeatureModule
import com.salud360.features.turnos.turnosFeatureModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

/** Todas las historias clínicas compiladas en la app. Para agregar una especialidad, sumarla acá. */
val especialidades: List<EspecialidadContribution> = listOf(
    clinicaContribution, hepatologiaContribution, pediatriaContribution, ginecoContribution,
    cardiologiaContribution, endocrinologiaContribution, hematologiaContribution, desarrolloInfantilContribution,
)

/** URL del servidor de sincronización. Cambiar por la del despliegue real. */
const val API_BASE_URL_DEFAULT = "https://turnosonlinebb.com/api/salud360"

fun iniciarKoin(db: Salud360Db, modulosPlataforma: List<Module>, apiBaseUrl: String = API_BASE_URL_DEFAULT): KoinApplication = startKoin {
    modules(
        modulosPlataforma +
            dataModule(db, AppConfig(apiBaseUrl)) +
            authFeatureModule + pacientesFeatureModule + turnosFeatureModule +
            hcFeatureModule(especialidades) +
            adminFeatureModule(especialidades.map { it.definicion.codigo to it.definicion.nombre }),
    )
}
