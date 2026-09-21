package com.mineradio.app.feature.library.domain

import org.koin.dsl.module

val libraryDomainModule =
    module {
        single { SongUseCases(get()) }
        single { PlaylistUseCases(get()) }
        single { AlbumUseCases(get()) }
        single { PlayHistoryUseCases(get()) }
        single { MusicScanUseCases(get()) }
        single { ScanFolderUseCases(get()) }
    }
