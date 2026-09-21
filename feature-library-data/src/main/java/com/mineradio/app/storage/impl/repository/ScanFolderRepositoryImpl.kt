package com.mineradio.app.storage.impl.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.mineradio.app.storage.api.FolderType
import com.mineradio.app.storage.api.IScanFolderRepository
import com.mineradio.app.storage.api.ScanFolder
import com.mineradio.app.storage.impl.dao.ScanFolderDao
import com.mineradio.app.storage.impl.entity.ScanFolderEntity

class ScanFolderRepositoryImpl(
    private val scanFolderDao: ScanFolderDao,
) : IScanFolderRepository {

    override fun getExtraFoldersFlow(): Flow<List<ScanFolder>> =
        scanFolderDao.getByType(FolderType.EXTRA.value).map { it.map(ScanFolderEntity::toScanFolder) }

    override fun getIgnoreFoldersFlow(): Flow<List<ScanFolder>> =
        scanFolderDao.getByType(FolderType.IGNORE.value).map { it.map(ScanFolderEntity::toScanFolder) }

    override suspend fun getExtraFoldersSync(): List<ScanFolder> =
        scanFolderDao.getByTypeSync(FolderType.EXTRA.value).map(ScanFolderEntity::toScanFolder)

    override suspend fun getIgnoreFoldersSync(): List<ScanFolder> =
        scanFolderDao.getByTypeSync(FolderType.IGNORE.value).map(ScanFolderEntity::toScanFolder)

    override suspend fun addFolder(
        uriString: String,
        displayName: String,
        folderType: FolderType,
        pathPrefix: String?,
    ) {
        // 去重：同类型下相同 URI 或相同路径前缀视为已存在
        val existing = scanFolderDao.getByTypeSync(folderType.value)
        if (existing.any { it.uriString == uriString || (pathPrefix != null && it.pathPrefix == pathPrefix) }) {
            return
        }
        scanFolderDao.insert(
            ScanFolderEntity(
                uriString = uriString,
                displayName = displayName,
                folderType = folderType.value,
                pathPrefix = pathPrefix,
            )
        )
    }

    override suspend fun removeFolder(id: Long) = scanFolderDao.deleteById(id)

    override suspend fun markInaccessible(id: Long) = scanFolderDao.markInaccessible(id)

    override suspend fun reAuthorize(id: Long, newUriString: String, pathPrefix: String?) =
        scanFolderDao.reAuthorize(id, newUriString, pathPrefix)
}

private fun ScanFolderEntity.toScanFolder() = ScanFolder(
    id = id ?: 0L,
    uriString = uriString,
    displayName = displayName,
    folderType = if (folderType == FolderType.IGNORE.value) FolderType.IGNORE else FolderType.EXTRA,
    pathPrefix = pathPrefix,
    addedAt = addedAt,
    isAccessible = isAccessible,
)
