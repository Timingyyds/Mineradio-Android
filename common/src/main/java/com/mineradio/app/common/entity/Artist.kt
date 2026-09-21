package com.mineradio.app.common.entity

import androidx.compose.runtime.Immutable

@Immutable
data class Artist(
    val name: String,
    val songCount: Int,
    val coverAlbumId: Long,
)
