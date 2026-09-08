package com.salud360.features.admin

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** @param especialidadesHc códigos y nombres de las historias clínicas compiladas en la app. */
fun adminFeatureModule(especialidadesHc: List<Pair<String, String>>) = module {
    viewModel { AdminViewModel(get(), get(), get(), especialidadesHc) }
}
