package com.mineradio.app.feature.library.domain

import androidx.compose.runtime.Immutable
import com.mineradio.app.storage.api.FolderType as StorageFolderType
import com.mineradio.app.storage.api.PreviewAudioFile as StoragePreviewAudioFile
import com.mineradio.app.storage.api.ScanFolder as StorageScanFolder
import com.mineradio.app.storage.api.ScanProgress as StorageScanProgress
import com.mineradio.app.storage.api.ScanResult as StorageScanResult

enum class FolderType {
    EXTRA,
    IGNORE,
}

@Immutable
data class ScanFolder(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val folderType: FolderType,
    val pathPrefix: String?,
    val addedAt: Long,
    val isAccessible: Boolean,
)

@Immutable
data class ScanResult(
    val totalScanned: Int,
    val newAdded: Int,
    val updated: Int,
    val removed: Int,
)

@Immutable
data class ScanProgress(
    val current: Int,
    val total: Int,
    val currentFile: String,
)

@Immutable
data class PreviewAudioFile(
    val path: String,
    val displayName: String,
    val size: Long,
    val extension: String,
    val isAlreadyImported: Boolean,
)

internal fun StorageFolderType.toDomain(): FolderType =
    when (this) {
        StorageFolderType.EXTRA -> FolderType.EXTRA
        StorageFolderType.IGNORE -> FolderType.IGNORE
    }

internal fun FolderType.toData(): StorageFolderType =
    when (this) {
        FolderType.EXTRA -> StorageFolderType.EXTRA
        FolderType.IGNORE -> StorageFolderType.IGNORE
    }

internal fun StorageScanFolder.toDomain(): ScanFolder =
    ScanFolder(
        id = id,
        uriString = uriString,
        displayName = displayName,
        folderType = folderType.toDomain(),
        pathPrefix = pathPrefix,
        addedAt = addedAt,
        isAccessible = isAccessible,
    )

internal fun StorageScanResult.toDomain(): ScanResult =
    ScanResult(
        totalScanned = totalScanned,
        newAdded = newAdded,
        updated = updated,
        removed = removed,
    )

internal fun StorageScanProgress.toDomain(): ScanProgress =
    ScanProgress(
        current = current,
        total = total,
        currentFile = currentFile,
    )

internal fun StoragePreviewAudioFile.toDomain(): PreviewAudioFile =
    PreviewAudioFile(
        path = path,
        displayName = displayName,
        size = size,
        extension = extension,
        isAlreadyImported = isAlreadyImported,
    )
