package com.mineradio.app.feature.library.domain

import com.mineradio.app.storage.api.IPlayHistoryRepository

class PlayHistoryUseCases(
    private val repository: IPlayHistoryRepository,
) : IPlayHistoryRepository by repository
