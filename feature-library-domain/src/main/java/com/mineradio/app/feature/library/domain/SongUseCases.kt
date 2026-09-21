package com.mineradio.app.feature.library.domain

import com.mineradio.app.storage.api.ISongRepository

class SongUseCases(
    private val repository: ISongRepository,
) : ISongRepository by repository
