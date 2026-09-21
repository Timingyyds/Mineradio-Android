package com.mineradio.app.feature.player.domain

import com.mineradio.app.player.api.IMusicPlayer

class PlayerUseCases(
    private val player: IMusicPlayer,
) : IMusicPlayer by player
