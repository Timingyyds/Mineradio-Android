package com.mineradio.app.feature.library.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.mineradio.app.storage.api.IScanFolderRepository

class ScanFolderUseCases(
    private val repository: IScanFolderRepository,
) {
    fun getExtraFoldersFlow(): Flow<List<ScanFolder>> = repository.getExtraFoldersFlow().map { folders -> folders.map { it.toDomain() } }

    fun getIgnoreFoldersFlow(): Flow<List<ScanFolder>> = repository.getIgnoreFoldersFlow().map { folders -> folders.map { it.toDomain() } }

    suspend fun addFolder(
        uriString: String,
        displayName: String,
        folderType: FolderType,
        pathPrefix: String?,
    ) {
        repository.addFolder(uriString, displayName, folderType.toData(), pathPrefix)
    }

    suspend fun removeFolder(id: Long) {
        repository.removeFolder(id)
    }

    suspend fun reAuthorize(
        id: Long,
        newUriString: String,
        pathPrefix: String?,
    ) {
        repository.reAuthorize(id, newUriString, pathPrefix)
    }
}
