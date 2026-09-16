package com.salud360.features.pacientes

import com.salud360.core.model.Id
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val pacientesFeatureModule = module {
    viewModel { (medicoId: Id?) -> PacientesListViewModel(get(), get(), medicoId) }
    viewModel { (pacienteId: Id?, vincularA: Id?) -> PacienteFormViewModel(get(), pacienteId, vincularA) }
    viewModel { (pacienteId: Id) -> PacienteDetalleViewModel(get(), get(), get(), pacienteId) }
}
