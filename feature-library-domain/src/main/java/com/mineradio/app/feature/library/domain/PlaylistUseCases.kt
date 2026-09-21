package com.mineradio.app.feature.library.domain

import com.mineradio.app.storage.api.IPlaylistRepository

class PlaylistUseCases(
    private val repository: IPlaylistRepository,
) : IPlaylistRepository by repository
