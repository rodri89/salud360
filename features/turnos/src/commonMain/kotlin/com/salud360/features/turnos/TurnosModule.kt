package com.salud360.features.turnos

import com.salud360.core.model.Id
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val turnosFeatureModule = module {
    viewModel { (medicoId: Id, consultorioId: Id, operador: String) -> AgendaViewModel(get(), get(), get(), medicoId, consultorioId, operador) }
    viewModel { (medicoId: Id, consultorioId: Id) -> HorariosViewModel(get(), medicoId, consultorioId) }
    viewModel { (medicoId: Id) -> ConfigAgendaViewModel(get(), medicoId) }
    viewModel { (medicoId: Id?) -> ObrasSocialesViewModel(get(), medicoId) }
    viewModel { (medicoIds: List<Id>) -> RecetasViewModel(get(), medicoIds) }
    viewModel { (consultorioIds: List<Id>, medicoIds: List<Id>) -> SelectorMedicoViewModel(get(), get(), consultorioIds, medicoIds) }
}
