package com.mineradio.app.feature.library.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.mineradio.app.storage.api.IMusicScanService

class MusicScanUseCases(
    private val scanService: IMusicScanService,
) {
    suspend fun scanMediaStore(): ScanResult = scanService.scanMediaStore().toDomain()

    suspend fun scanExtraFolders(): ScanResult = scanService.scanExtraFolders().toDomain()

    suspend fun scanAllDirectoriesForPreview(): List<PreviewAudioFile> =
        scanService.scanAllDirectoriesForPreview().map { it.toDomain() }

    suspend fun importSelectedFiles(filePaths: List<String>): ScanResult =
        scanService.importSelectedFiles(filePaths).toDomain()

    fun getScanProgress(): Flow<ScanProgress?> = scanService.getScanProgress().map { it?.toDomain() }

    fun cancelScan() {
        scanService.cancelScan()
    }

    fun startMediaStoreObserver() {
        scanService.startMediaStoreObserver()
    }

    fun stopMediaStoreObserver() {
        scanService.stopMediaStoreObserver()
    }
}
