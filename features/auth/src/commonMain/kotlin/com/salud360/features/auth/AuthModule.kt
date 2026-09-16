package com.salud360.features.auth

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val authFeatureModule = module {
    viewModel { LoginViewModel(get(), get(), get(), get()) }
}
