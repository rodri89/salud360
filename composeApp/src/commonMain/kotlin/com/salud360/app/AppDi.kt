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

/**
 * Arma la configuración del entorno con el que se compiló la app ([Entornos], generado por Gradle a partir de
 * `gradle.properties`: `-Pentorno=dev|release`). En dev las URLs traen `{host}`, que se reemplaza por el host
 * desde el que cada plataforma llega al MAMP de la Mac (`localhost`, `10.0.2.2` en el emulador Android, o el
 * `-PdevHost` indicado para un teléfono físico).
 */
fun configEntorno(hostLocal: String): AppConfig {
    val host = Entornos.DEV_HOST.ifBlank { hostLocal }
    fun url(u: String) = u.replace("{host}", host).trimEnd('/')
    return AppConfig(
        entorno = Entornos.ENTORNO,
        turnosUrl = url(Entornos.TURNOS_URL),
        hcUrls = Entornos.HC_URLS.mapValues { url(it.value) },
    )
}

fun iniciarKoin(db: Salud360Db, modulosPlataforma: List<Module>, config: AppConfig): KoinApplication = startKoin {
    modules(
        modulosPlataforma +
            dataModule(db, config) +
            authFeatureModule + pacientesFeatureModule + turnosFeatureModule +
            hcFeatureModule(especialidades) +
            adminFeatureModule(especialidades.map { it.definicion.codigo to it.definicion.nombre }),
    )
}
