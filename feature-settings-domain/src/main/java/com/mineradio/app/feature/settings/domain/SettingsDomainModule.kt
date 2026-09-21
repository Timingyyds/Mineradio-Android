package com.mineradio.app.feature.settings.domain

import org.koin.dsl.module

val settingsDomainModule =
    module {
        single { SettingsUseCases(get()) }
    }
