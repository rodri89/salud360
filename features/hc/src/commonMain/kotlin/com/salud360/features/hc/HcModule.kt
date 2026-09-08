package com.salud360.features.hc

import com.salud360.core.model.Id
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Módulo Koin de la historia clínica. Recibe las contribuciones de todas las especialidades
 * compiladas en la app y arma el [EspecialidadRegistry].
 */
fun hcFeatureModule(contribuciones: List<EspecialidadContribution>) = module {
    single {
        EspecialidadRegistry(
            definiciones = contribuciones.map { it.definicion },
            renderers = contribuciones.flatMap { it.renderers.entries }.associate { it.key to it.value },
        )
    }
    viewModel { (consultaId: Id, medicoId: Id) -> ConsultaViewModel(get(), get(), get(), get(), get(), consultaId, medicoId) }
    viewModel { (pacienteId: Id, especialidad: String, medicoId: Id) -> HistoriaClinicaViewModel(get(), get(), get(), pacienteId, especialidad, medicoId) }
}
