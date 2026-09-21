package com.mineradio.app.feature.library.domain

import com.mineradio.app.storage.api.IAlbumRepository

class AlbumUseCases(
    private val repository: IAlbumRepository,
) : IAlbumRepository by repository
