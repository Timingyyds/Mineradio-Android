package com.mineradio.app.feature.player.domain

import org.koin.dsl.module

val playerDomainModule =
    module {
        single { PlayerUseCases(get()) }
    }
