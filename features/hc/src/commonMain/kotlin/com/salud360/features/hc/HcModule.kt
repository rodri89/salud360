package com.salud360.features.hc

import com.salud360.core.model.Id
import com.salud360.features.hc.secciones.CurvasCrecimientoSeccion
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Secciones a medida que aporta el propio motor de HC, disponibles para todas las especialidades por `renderKey`. */
val renderersBase: Map<String, SeccionRenderer> = mapOf(
    RENDER_CURVAS_CRECIMIENTO to { s, ctx -> CurvasCrecimientoSeccion(s, ctx) },
)

/** `renderKey` de la sección de curvas de crecimiento (percentilos OMS) para especialidades con `conPercentilos`. */
const val RENDER_CURVAS_CRECIMIENTO = "curvas_crecimiento"

/**
 * Módulo Koin de la historia clínica. Recibe las contribuciones de todas las especialidades
 * compiladas en la app y arma el [EspecialidadRegistry].
 */
fun hcFeatureModule(contribuciones: List<EspecialidadContribution>) = module {
    single {
        EspecialidadRegistry(
            definiciones = contribuciones.map { it.definicion },
            // Los renderers propios del motor (curvas de crecimiento) van primero: cualquier especialidad puede usarlos.
            renderers = renderersBase + contribuciones.flatMap { it.renderers.entries }.associate { it.key to it.value },
        )
    }
    viewModel { (consultaId: Id, medicoId: Id) -> ConsultaViewModel(get(), get(), get(), get(), get(), consultaId, medicoId) }
    viewModel { (pacienteId: Id, especialidad: String, medicoId: Id) -> HistoriaClinicaViewModel(get(), get(), get(), pacienteId, especialidad, medicoId) }
    viewModel { (medicoId: Id, especialidades: List<String>) -> ConfigSeccionesHcViewModel(get(), get(), medicoId, especialidades) }
}
