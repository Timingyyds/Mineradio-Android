package com.mineradio.app.storage.impl.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import androidx.core.net.toUri
import android.icu.text.Transliterator
import com.kyant.taglib.AudioPropertiesReadStyle
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.mineradio.app.storage.api.IMusicScanService
import com.mineradio.app.storage.api.IScanFolderRepository
import com.mineradio.app.storage.api.MediaStoreChangeEvent
import com.mineradio.app.storage.api.MediaStoreChangeType
import com.mineradio.app.storage.api.PreviewAudioFile
import com.mineradio.app.storage.api.ScanProgress
import com.mineradio.app.storage.api.ScanResult
import com.mineradio.app.storage.impl.dao.AlbumDao
import com.mineradio.app.storage.impl.dao.SongDao
import com.mineradio.app.storage.impl.entity.AlbumEntity
import com.mineradio.app.storage.impl.entity.SongEntity
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * 音乐扫描服务实现 - 基于 MediaStore 和文件系统扫描
 * 支持自动监听系统媒体库变更
 */
class MusicScanService(
    private val context: Context,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val scanFolderRepository: IScanFolderRepository,
) : IMusicScanService {

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    private val _isScanning = MutableStateFlow(false)
    private var isCancelled = false

    // MediaStore 变更事件流
    private val _mediaStoreChanges =
        MutableSharedFlow<MediaStoreChangeEvent>(replay = 0, extraBufferCapacity = 10)

    // 协程作用域用于处理去抖动扫描
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 去抖动任务
    private var debounceJob: Job? = null

    private val pendingMediaStoreSyncLock = Any()
    private val pendingChangedMediaStoreIds = mutableSetOf<Long>()
    private val pendingDeletedMediaStoreIds = mutableSetOf<Long>()
    private var pendingFullMediaStoreRescan = false

    // MediaStore 内容观察者
    private var mediaStoreObserver: ContentObserver? = null

    // 去抖动延迟时间（毫秒）- 避免频繁变更触发多次扫描
    private val debounceDelayMs = 2000L

    companion object {
        private const val TAG = "MusicScanService"
        private const val MAX_FOLDER_DEPTH = 20

        // 支持的音频格式（市面所有常见音频 MIME 类型）
        private val SUPPORTED_MIME_TYPES = setOf(
            // MPEG / MP3 系列
            "audio/mpeg",
            "audio/mp3",
            "audio/x-mpeg",
            "audio/x-mp3",
            // MP4 / M4A / AAC / ALAC 系列
            "audio/mp4",
            "audio/x-m4a",
            "audio/m4a",
            "audio/aac",
            "audio/aacp",
            "audio/x-aac",
            "audio/3gpp",
            "audio/3gpp2",
            "audio/mp4a-latm",
            "audio/alac",
            "audio/x-alac",
            "audio/x-m4b",
            "audio/x-m4r",
            // FLAC 系列
            "audio/flac",
            "audio/x-flac",
            // WAV / PCM 系列
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/vnd.wave",
            "audio/x-pn-wav",
            // OGG / Opus / Vorbis 系列
            "audio/ogg",
            "audio/opus",
            "audio/x-opus+ogg",
            "audio/vorbis",
            "audio/x-vorbis",
            "audio/x-ogg",
            // AIFF 系列
            "audio/aiff",
            "audio/x-aiff",
            // APE / WV / TTA / DSD 等无损
            "audio/x-ape",
            "audio/x-monkeys-audio",
            "audio/x-wv",
            "audio/x-wavpack",
            "audio/x-tta",
            "audio/x-dsf",
            "audio/x-dff",
            "audio/x-dsd",
            // AMR / 3GP 移动格式
            "audio/amr",
            "audio/amr-wb",
            "audio/x-amr",
            // MKA / WebM
            "audio/x-matroska",
            "audio/webm",
            "audio/x-webm",
            // MIDI / SP-MIDI
            "audio/midi",
            "audio/x-midi",
            "audio/sp-midi",
            // WMA / ASF
            "audio/x-ms-wma",
            "audio/x-wma",
            // 其他
            "audio/basic",
            "audio/x-mpegurl",
            "audio/x-scpls",
        )

        // 文件系统遍历时的音频扩展名过滤（市面所有常见音频格式）
        private val SUPPORTED_EXTENSIONS = setOf(
            // 无损
            "flac", "wav", "ape", "alac", "wv", "tta", "dsf", "dff", "dsd",
            // 有损
            "mp3", "m4a", "aac", "ogg", "opus", "oga", "wma",
            // 容器/移动
            "m4b", "m4r", "mka", "webm", "3gp", "3gpp", "3g2", "amr", "awb",
            // 经典格式
            "aiff", "aif", "aifc",
            // MIDI
            "mid", "midi", "kar",
            // 其他
            "mpc", "mpp", "mp+", "ofr", "ofs", "spx", "wv",
        )

        // 加密音乐文件扩展名（无法直接播放，但需在扫描时显示）
        // 网易云音乐 NCM、QQ音乐 QMC、酷狗 KGM/KW、酷我 Kuwo、虾米 XM 等
        private val ENCRYPTED_AUDIO_EXTENSIONS = setOf(
            "ncm",                       // 网易云音乐 NCM
            "qmc0", "qmc2", "qmc3", "qmc4", "qmc6", "qmc8", "qmcflac", "mflac",  // QQ音乐 QMC
            "mgg", "mggl",               // QQ音乐加密 OGG
            "tkm", "tm",                 // 酷我 / 天翼
            "kwm",                       // 酷我音乐 KWM
            "kuwo",                      // 酷我音乐
            "xm",                        // 虾米音乐 XM
            "vpr",                       // 酷狗 VPR
            "cache",                     // 通用缓存加密格式
            "uc"                         // 酷狗 UC 加密
        )
    }

    // 用于读取音频的响度增益信息
    private val TRACK_GAIN_KEYS = listOf(
        "REPLAYGAIN_TRACK_GAIN",
        "REPLAYGAIN_TRACK_GAIN_DB",
        "R128_TRACK_GAIN"
    )

    // 用于读取专辑的响度增益信息
    private val ALBUM_GAIN_KEYS = listOf(
        "REPLAYGAIN_ALBUM_GAIN",
        "REPLAYGAIN_ALBUM_GAIN_DB",
        "R128_ALBUM_GAIN"
    )

    override fun getScanProgress(): Flow<ScanProgress?> = _scanProgress.asStateFlow()

    override fun isScanning(): Flow<Boolean> = _isScanning.asStateFlow()

    override fun cancelScan() {
        isCancelled = true
    }

    override fun getMediaStoreChanges(): Flow<MediaStoreChangeEvent> =
        _mediaStoreChanges.asSharedFlow()

    /**
     * 启动 MediaStore 变更监听
     * 监听音频文件的新增、修改、删除事件
     */
    override fun startMediaStoreObserver() {
        if (mediaStoreObserver != null) {
            Timber.tag(TAG).w("MediaStore 观察者已在运行中")
            return
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange(selfChange, uri, 0)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
                Timber.tag(TAG)
                    .d("MediaStore 变更检测: uri=$uri, flags=$flags, selfChange=$selfChange")

                // 确定变更类型
                val changeType = when {
                    flags and ContentResolver.NOTIFY_DELETE != 0 -> MediaStoreChangeType.CONTENT_DELETED
                    flags and ContentResolver.NOTIFY_INSERT != 0 -> MediaStoreChangeType.CONTENT_CHANGED
                    flags and ContentResolver.NOTIFY_UPDATE != 0 -> MediaStoreChangeType.CONTENT_CHANGED
                    else -> MediaStoreChangeType.UNKNOWN
                }

                // 发送变更事件
                serviceScope.launch {
                    _mediaStoreChanges.emit(MediaStoreChangeEvent(changeType))
                }

                enqueueObservedMediaStoreChange(
                    mediaStoreId = extractObservedMediaStoreId(uri),
                    changeType = changeType,
                )
                scheduleDebouncedMediaStoreSync()
            }
        }

        // 注册观察者，notifyForDescendants=true 监听子路径变化
        context.contentResolver.registerContentObserver(
            uri,
            true, // notifyForDescendants
            mediaStoreObserver!!
        )

        Timber.tag(TAG).i("MediaStore 观察者已启动，监听: $uri")
    }

    /**
     * 停止 MediaStore 变更监听
     */
    override fun stopMediaStoreObserver() {
        mediaStoreObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            mediaStoreObserver = null
            Timber.tag(TAG).i("MediaStore 观察者已停止")
        }
        debounceJob?.cancel()
        debounceJob = null
        synchronized(pendingMediaStoreSyncLock) {
            pendingChangedMediaStoreIds.clear()
            pendingDeletedMediaStoreIds.clear()
            pendingFullMediaStoreRescan = false
        }
    }

    private data class PendingMediaStoreSync(
        val changedIds: Set<Long>,
        val deletedIds: Set<Long>,
        val requiresFullRescan: Boolean,
    )

    private fun enqueueObservedMediaStoreChange(
        mediaStoreId: Long?,
        changeType: MediaStoreChangeType,
    ) {
        synchronized(pendingMediaStoreSyncLock) {
            when {
                changeType == MediaStoreChangeType.CONTENT_DELETED && mediaStoreId != null -> {
                    pendingDeletedMediaStoreIds.add(mediaStoreId)
                }

                mediaStoreId != null -> {
                    pendingChangedMediaStoreIds.add(mediaStoreId)
                }

                else -> {
                    pendingFullMediaStoreRescan = true
                }
            }
        }
    }

    private fun scheduleDebouncedMediaStoreSync() {
        debounceJob?.cancel()
        debounceJob =
            serviceScope.launch {
                delay(debounceDelayMs)
                flushPendingMediaStoreSync()
            }
    }

    private fun takePendingMediaStoreSync(): PendingMediaStoreSync =
        synchronized(pendingMediaStoreSyncLock) {
            val request =
                PendingMediaStoreSync(
                    changedIds = pendingChangedMediaStoreIds.toSet(),
                    deletedIds = pendingDeletedMediaStoreIds.toSet(),
                    requiresFullRescan = pendingFullMediaStoreRescan,
                )
            pendingChangedMediaStoreIds.clear()
            pendingDeletedMediaStoreIds.clear()
            pendingFullMediaStoreRescan = false
            request
        }

    private fun mergePendingMediaStoreSync(request: PendingMediaStoreSync) {
        synchronized(pendingMediaStoreSyncLock) {
            pendingChangedMediaStoreIds.addAll(request.changedIds)
            pendingDeletedMediaStoreIds.addAll(request.deletedIds)
            pendingFullMediaStoreRescan = pendingFullMediaStoreRescan || request.requiresFullRescan
        }
    }

    private suspend fun flushPendingMediaStoreSync() {
        val request = takePendingMediaStoreSync()
        if (!request.requiresFullRescan && request.changedIds.isEmpty() && request.deletedIds.isEmpty()) {
            return
        }

        if (_isScanning.value) {
            Timber.tag(TAG).i("当前已有扫描任务，合并变更并等待下一轮去抖动同步")
            mergePendingMediaStoreSync(request)
            scheduleDebouncedMediaStoreSync()
            return
        }

        if (request.requiresFullRescan) {
            Timber.tag(TAG).i("监听变更无法定位具体媒体项，回退到全量增量扫描")
            scanMediaStore()
            return
        }

        Timber.tag(TAG).i(
            "去抖动完成，开始局部同步 MediaStore：changed=${request.changedIds.size}, deleted=${request.deletedIds.size}",
        )
        scanMediaStoreDelta(
            changedMediaStoreIds = request.changedIds,
            deletedMediaStoreIds = request.deletedIds,
        )
    }

    private fun extractObservedMediaStoreId(uri: Uri?): Long? {
        if (uri == null) return null
        return runCatching { ContentUris.parseId(uri) }.getOrNull()
            ?: uri.lastPathSegment?.toLongOrNull()
    }

    override suspend fun scanMediaStore(): ScanResult = withContext(Dispatchers.IO) {
        if (_isScanning.value) {
            Timber.tag(TAG).w("扫描已在进行中")
            return@withContext ScanResult(0, 0, 0, 0)
        }

        _isScanning.value = true
        isCancelled = false
        var totalScanned = 0
        var newAdded = 0
        var updated = 0

        // 加载忽略文件夹路径前缀（MediaStore 扫描时过滤用）
        val ignorePrefixes: List<String> = try {
            scanFolderRepository.getIgnoreFoldersSync()
                .mapNotNull { it.pathPrefix }
                .map { if (it.endsWith("/")) it else "$it/" }
        } catch (e: Exception) {
            emptyList()
        }

        try {
            val albums = loadAlbums()
            albumDao.replaceAll(albums)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "扫描失败")
            _isScanning.value = false
            return@withContext ScanResult(0, 0, 0, 0)
        }

        try {
            val contentResolver = context.contentResolver

            // 1. 从 DB 加载现有歌曲的摘要信息，构建 HashMap（O(1) 查找）
            val existingScanInfoMap: Map<Long, SongDao.SongScanInfo> =
                songDao.getAllScanInfo().associateBy { it.mediaStoreId }

            // 2. 查询 MediaStore（包含 DATE_MODIFIED 用于增量判断）
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_MODIFIED,
            )

            // ★ 放宽过滤：不再强制 IS_MUSIC=1（部分设备不会给非主流音频打 IS_MUSIC 标记）
            // 仅保留 DURATION > 10000 过滤太短的音频，MIME 白名单已在代码层过滤
            val selection =
                "${MediaStore.Audio.Media.DURATION} > 10000"
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            // 需要 TagLib 深度扫描的歌曲列表（新增或修改的）
            val songsToScan = mutableListOf<SongEntity>()
            // 本次 MediaStore 中所有可见的 mediaStoreId
            val currentMediaStoreIds = mutableSetOf<Long>()

            contentResolver.query(
                uri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateModifiedColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                val totalCount = cursor.count
                Timber.tag(TAG).d("开始增量扫描 MediaStore，共 $totalCount 个音频文件")

                while (cursor.moveToNext() && !isCancelled) {
                    val mediaStoreId = cursor.getLong(idColumn)
                    val displayName = cursor.getString(nameColumn) ?: "Unknown"
                    val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                    val albumName = cursor.getString(albumColumn) ?: ""
                    val size = cursor.getLong(sizeColumn)
                    val duration = cursor.getLong(durationColumn)
                    val mimeType = cursor.getString(mimeTypeColumn) ?: ""
                    val albumId = cursor.getLong(albumIdColumn)
                    val path = cursor.getString(dataColumn) ?: ""
                    val dateModified = cursor.getLong(dateModifiedColumn)

                    // 过滤不支持的格式
                    if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
                        continue
                    }

                    // 过滤太短的音频（小于 10 秒）
                    if (duration < 10000) {
                        continue
                    }

                    // 跳过忽略文件夹中的文件
                    if (path.isNotEmpty() && ignorePrefixes.any { path.startsWith(it) }) {
                        continue
                    }

                    totalScanned++
                    currentMediaStoreIds.add(mediaStoreId)

                    // 更新进度
                    _scanProgress.value = ScanProgress(
                        current = totalScanned,
                        total = totalCount,
                        currentFile = displayName
                    )

                    val existingInfo = existingScanInfoMap[mediaStoreId]

                    if (existingInfo != null && existingInfo.dateModified == dateModified && dateModified != 0L) {
                        // 文件未变更 → 跳过 TagLib 扫描，不做任何处理
                        continue
                    }

                    // 需要 TagLib 扫描：新增歌曲或文件已修改
                    val audioInfo = extractAudioInfoWithTaglib(
                        contentResolver = contentResolver,
                        mediaStoreId = mediaStoreId,
                        fallbackDuration = duration
                    )

                    val finalDisplayName = audioInfo.title ?: displayName
                    val finalArtist = audioInfo.artist ?: artist
                    val finalAlbum = audioInfo.album ?: albumName
                    val sortName = generateSortName(finalDisplayName)


                    var codec = mimeType.let {
                        when {
                            it.contains("mp3", ignoreCase = true) -> "MP3"
                            it.contains("aac", ignoreCase = true) -> "AAC"
                            it.contains("flac", ignoreCase = true) -> "FLAC"
                            it.contains("alac", ignoreCase = true) -> "ALAC"
                            it.contains("opus", ignoreCase = true) -> "Opus"
                            it.contains("vorbis", ignoreCase = true) -> "OGG"
                            it.contains("ogg", ignoreCase = true) -> "OGG"
                            it.contains("wav", ignoreCase = true) -> "WAV"
                            it.contains("m4a", ignoreCase = true) -> "M4A"
                            it.contains("evrc", ignoreCase = true) -> "EVRC"
                            else -> it.substringAfter("/").uppercase()
                        }
                    }
                    if (codec == "M4A") {
                        codec = if (audioInfo.bitRate >= 700000) "ALAC" else "AAC"
                    }

                    val song = SongEntity(
                        songId = existingInfo?.songId, // 保留已有主键，Upsert 会更新而非插入
                        mediaStoreId = mediaStoreId,
                        path = path,
                        displayName = finalDisplayName,
                        artist = finalArtist,
                        size = size,
                        like = existingInfo?.like ?: false,
                        duration = audioInfo.duration.takeIf { it > 0 } ?: duration,
                        sort = existingInfo?.sort ?: 0,
                        mimeType = mimeType,
                        albumId = albumId,
                        album = finalAlbum,
                        sampleRate = audioInfo.sampleRate,
                        bitRate = audioInfo.bitRate,
                        channels = audioInfo.channels,
                        digit = audioInfo.digit,
                        isIgnore = existingInfo?.isIgnore ?: false,
                        sortName = sortName,
                        dateModified = dateModified,
                        codec = codec,
                    )
                    songsToScan.add(song)
                    if (existingInfo != null) {
                        updated++
                    } else {
                        newAdded++
                    }
                }
            }

            if (isCancelled) {
                Timber.tag(TAG).w("扫描已取消")
                return@withContext ScanResult(totalScanned, 0, 0, 0)
            }

            // 3. 计算已从 MediaStore 中移除的歌曲
            val removedMediaStoreIds = existingScanInfoMap.keys - currentMediaStoreIds

            // 4. 一次事务完成：删除已移除 + upsert 变更
            songDao.incrementalUpdateSongs(
                removedMediaStoreIds = removedMediaStoreIds.toList(),
                changedSongs = songsToScan,
            )

            val removed = removedMediaStoreIds.size

            Timber.tag(TAG).i(
                "增量扫描完成: 总计=$totalScanned, 新增=$newAdded, 更新=$updated, 删除=$removed, " +
                        "跳过=${totalScanned - newAdded - updated}"
            )

            ScanResult(
                totalScanned = totalScanned,
                newAdded = newAdded,
                updated = updated,
                removed = removed.coerceAtLeast(0)
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "扫描失败")
            ScanResult(0, 0, 0, 0)
        } finally {
            _isScanning.value = false
            _scanProgress.value = null
        }
    }

    private suspend fun scanMediaStoreDelta(
        changedMediaStoreIds: Set<Long>,
        deletedMediaStoreIds: Set<Long>,
    ): ScanResult =
        withContext(Dispatchers.IO) {
            val candidateIds = (changedMediaStoreIds + deletedMediaStoreIds)
            if (candidateIds.isEmpty()) return@withContext ScanResult(0, 0, 0, 0)
            if (_isScanning.value) {
                Timber.tag(TAG).w("扫描已在进行中，跳过本次局部同步")
                return@withContext ScanResult(0, 0, 0, 0)
            }

            _isScanning.value = true
            isCancelled = false

            try {
                performMediaStoreDeltaSync(changedMediaStoreIds, deletedMediaStoreIds)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "局部同步 MediaStore 失败，回退到全量增量扫描")
                mergePendingMediaStoreSync(
                    PendingMediaStoreSync(
                        changedIds = emptySet(),
                        deletedIds = emptySet(),
                        requiresFullRescan = true,
                    ),
                )
                scheduleDebouncedMediaStoreSync()
                ScanResult(0, 0, 0, 0)
            } finally {
                _isScanning.value = false
                _scanProgress.value = null
            }
        }

    /**
     * 局部同步核心逻辑：按 mediaStoreId 从 MediaStore 拉取行并统一入库（含专辑刷新）。
     * 调用方负责 _isScanning 状态维护与异常处理。
     */
    private suspend fun performMediaStoreDeltaSync(
        changedMediaStoreIds: Set<Long>,
        deletedMediaStoreIds: Set<Long>,
    ): ScanResult {
        val candidateIds = (changedMediaStoreIds + deletedMediaStoreIds)
        if (candidateIds.isEmpty()) return ScanResult(0, 0, 0, 0)
        var totalScanned = 0
        var newAdded = 0
        var updated = 0

        val ignorePrefixes = loadIgnorePrefixes()
        val existingInfoMap =
                    songDao
                        .getScanInfoByMediaStoreIds(candidateIds.toList())
                        .associateBy { it.mediaStoreId }
                val affectedAlbumIds = mutableSetOf<Long>()
                val retainedExistingIds = mutableSetOf<Long>()
                val songsToUpsert = mutableListOf<SongEntity>()
                val contentResolver = context.contentResolver
                val queryTargetIds = changedMediaStoreIds.toList()

                queryTargetIds.chunked(300).forEach { chunk ->
                    if (isCancelled) return@forEach
                    queryMediaStoreByIds(contentResolver, chunk)?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                        val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                        val dateModifiedColumn =
                            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                        while (cursor.moveToNext() && !isCancelled) {
                            val mediaStoreId = cursor.getLong(idColumn)
                            val displayName = cursor.getString(nameColumn) ?: "Unknown"
                            val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                            val albumName = cursor.getString(albumColumn) ?: ""
                            val size = cursor.getLong(sizeColumn)
                            val duration = cursor.getLong(durationColumn)
                            val mimeType = cursor.getString(mimeTypeColumn) ?: ""
                            val albumId = cursor.getLong(albumIdColumn)
                            val path = cursor.getString(dataColumn) ?: ""
                            val dateModified = cursor.getLong(dateModifiedColumn)
                            val existingInfo = existingInfoMap[mediaStoreId]

                            if (!isMediaStoreSongEligible(mimeType, duration, path, ignorePrefixes)) {
                                existingInfo?.albumId?.let(affectedAlbumIds::add)
                                Timber.tag(TAG).d("MediaStore 变更文件不符合条件，跳过: $displayName")
                                continue
                            }

                            totalScanned++
                            _scanProgress.value =
                                ScanProgress(
                                    current = totalScanned,
                                    total = queryTargetIds.size,
                                    currentFile = displayName,
                                )

                            if (
                                existingInfo != null &&
                                existingInfo.dateModified == dateModified &&
                                dateModified != 0L
                            ) {
                                retainedExistingIds.add(mediaStoreId)
                                continue
                            }

                            val audioInfo =
                                extractAudioInfoWithTaglib(
                                    contentResolver = contentResolver,
                                    mediaStoreId = mediaStoreId,
                                    fallbackDuration = duration,
                                )
                            val finalDisplayName = audioInfo.title ?: displayName
                            val finalArtist = audioInfo.artist ?: artist
                            val finalAlbum = audioInfo.album ?: albumName
                            val sortName = generateSortName(finalDisplayName)
                            val codec = resolveCodec(mimeType, audioInfo.bitRate)



                            songsToUpsert.add(
                                SongEntity(
                                    songId = existingInfo?.songId,
                                    mediaStoreId = mediaStoreId,
                                    path = path,
                                    displayName = finalDisplayName,
                                    artist = finalArtist,
                                    size = size,
                                    like = existingInfo?.like ?: false,
                                    duration = audioInfo.duration.takeIf { it > 0 } ?: duration,
                                    sort = existingInfo?.sort ?: 0,
                                    mimeType = mimeType,
                                    albumId = albumId,
                                    album = finalAlbum,
                                    sampleRate = audioInfo.sampleRate,
                                    bitRate = audioInfo.bitRate,
                                    channels = audioInfo.channels,
                                    digit = audioInfo.digit,
                                    isIgnore = existingInfo?.isIgnore ?: false,
                                    sortName = sortName,
                                    dateModified = dateModified,
                                    codec = codec,
                                ),
                            )
                            affectedAlbumIds.add(albumId)
                            existingInfo?.albumId?.takeIf { it != albumId }?.let(affectedAlbumIds::add)
                            if (existingInfo != null) {
                                retainedExistingIds.add(mediaStoreId)
                                updated++
                            } else {
                                newAdded++
                            }
                        }
                    }
                }

                if (isCancelled) {
                    Timber.tag(TAG).w("局部同步已取消")
                    return ScanResult(totalScanned, 0, 0, 0)
                }

                val removedMediaStoreIds =
                    buildSet {
                        addAll(deletedMediaStoreIds.filter { it in existingInfoMap })
                        queryTargetIds
                            .filter { it in existingInfoMap && it !in retainedExistingIds }
                            .forEach(::add)
                    }
                removedMediaStoreIds
                    .asSequence()
                    .mapNotNull(existingInfoMap::get)
                    .map { it.albumId }
                    .forEach(affectedAlbumIds::add)

                songDao.incrementalUpdateSongs(
                    removedMediaStoreIds = removedMediaStoreIds.toList(),
                    changedSongs = songsToUpsert,
                )
                refreshAlbumsByIds(affectedAlbumIds)

                Timber.tag(TAG).i(
                    "局部同步完成: 目标=${candidateIds.size}, 扫描=$totalScanned, 新增=$newAdded, 更新=$updated, 删除=${removedMediaStoreIds.size}",
                )

                return ScanResult(
                    totalScanned = totalScanned,
                    newAdded = newAdded,
                    updated = updated,
                    removed = removedMediaStoreIds.size,
                )
    }

    override suspend fun scanFolder(folderPath: String): ScanResult {
        Timber.tag(TAG).w("scanFolder 已废弃，请使用 scanExtraFolders()")
        return ScanResult(0, 0, 0, 0)
    }

    override suspend fun scanFolders(folderPaths: List<String>): ScanResult {
        Timber.tag(TAG).w("scanFolders 已废弃，请使用 scanExtraFolders()")
        return ScanResult(0, 0, 0, 0)
    }

    override suspend fun scanExtraFolders(): ScanResult = withContext(Dispatchers.IO) {
        val extraFolders = try {
            scanFolderRepository.getExtraFoldersSync()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "加载额外扫描文件夹失败")
            return@withContext ScanResult(0, 0, 0, 0)
        }
        if (extraFolders.isEmpty()) return@withContext ScanResult(0, 0, 0, 0)

        // 加载忽略路径前缀
        val ignorePrefixes = loadIgnorePrefixes()

        // 第一层防御：预校验 persistedUriPermissions
        val validUris = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .toHashSet()

        val (foldersToScan, lostFolders) = extraFolders.partition { Uri.parse(it.uriString) in validUris }
        lostFolders.forEach {
            Timber.tag(TAG).w("SAF 权限预校验失效: ${it.displayName}")
            try { scanFolderRepository.markInaccessible(it.id) } catch (_: Exception) {}
        }
        if (foldersToScan.isEmpty()) return@withContext ScanResult(0, 0, 0, 0)

        if (_isScanning.value) {
            Timber.tag(TAG).w("扫描已在进行中，跳过额外文件夹扫描")
            return@withContext ScanResult(0, 0, 0, 0)
        }
        _isScanning.value = true
        isCancelled = false

        try {
            // ★ 修复：用 App 数据库的已导入路径（而非 MediaStore 全量路径）
            //   否则 SAF 目录下的文件因已在 MediaStore 中而被全数跳过 → 导入 0 首
            val existingPaths = buildAppDbExistingPathsSet()

            var totalScanned = 0
            // 新注册进 MediaStore 的 mediaStoreId，注册后统一走 MediaStore 增量入库
            val registeredMediaIds = mutableSetOf<Long>()

            for (folder in foldersToScan) {
                if (isCancelled) break
                try {
                    // 第二层防御：每个文件夹独立 try-catch
                    val treeUri = Uri.parse(folder.uriString)
                    val audioFiles = walkDocumentTree(treeUri, ignorePrefixes)

                    for (fileInfo in audioFiles) {
                        if (isCancelled) break
                        totalScanned++

                        // ★ 修复：只在已导入 App 数据库时跳过（而不是已在 MediaStore 时跳过）
                        val filePath = fileInfo.absolutePath ?: continue
                        if (existingPaths.contains(filePath)) continue

                        _scanProgress.value = ScanProgress(
                            current = totalScanned,
                            total = audioFiles.size,
                            currentFile = fileInfo.displayName
                        )

                        // 注册到 MediaStore，获取 mediaId
                        var mediaId = registerFileInMediaStore(filePath)
                        if (mediaId == null) {
                            // ★ 回退：文件可能已在 MediaStore 中，按 path 查 mediaId
                            mediaId = queryMediaStoreIdByPath(filePath)
                        }
                        if (mediaId == null) continue
                        registeredMediaIds.add(mediaId)
                        existingPaths.add(filePath) // 防止同次扫描重复处理
                    }
                } catch (e: SecurityException) {
                    // 第二层防御：权限在遍历中被撤销
                    Timber.tag(TAG).w("SAF 权限在扫描中失效: ${folder.displayName}")
                    try { scanFolderRepository.markInaccessible(folder.id) } catch (_: Exception) {}
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "额外文件夹扫描失败: ${folder.displayName}")
                }
            }

            // 注册完成后复用 MediaStore 增量入库（正确获得 albumId/专辑/标签信息）
            val deltaResult = performMediaStoreDeltaSync(
                changedMediaStoreIds = registeredMediaIds,
                deletedMediaStoreIds = emptySet(),
            )

            Timber.tag(TAG).i("额外文件夹扫描完成: 总计=$totalScanned, 新增=${deltaResult.newAdded}")
            ScanResult(
                totalScanned = totalScanned,
                newAdded = deltaResult.newAdded,
                updated = deltaResult.updated,
                removed = 0,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "额外文件夹扫描失败")
            ScanResult(0, 0, 0, 0)
        } finally {
            _isScanning.value = false
            _scanProgress.value = null
        }
    }

    override suspend fun scanAllDirectoriesForPreview(): List<PreviewAudioFile> = withContext(Dispatchers.IO) {
        if (_isScanning.value) {
            Timber.tag(TAG).w("扫描已在进行中")
            return@withContext emptyList()
        }
        _isScanning.value = true
        isCancelled = false

        try {
            val ignorePrefixes = loadIgnorePrefixes()
            // ★ 修复：用 App 数据库已导入路径判断 isAlreadyImported
            //   用 MediaStore 全量路径会让所有已索引歌曲显示为"已导入"，导致默认选中为空
            val existingPaths = buildAppDbExistingPathsSet()
            val results = mutableListOf<PreviewAudioFile>()

            // ★ 优先直接查询 MediaStore（Android 10+ File.listFiles() 不可靠）
            Timber.tag(TAG).i("全盘预览扫描：开始查询 MediaStore")
            _scanProgress.value = ScanProgress(0, 0, "正在查询媒体库...")

            val mediaStoreFiles = mutableListOf<PreviewAudioFile>()
            var totalCount = 0
            try {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.MIME_TYPE,
                    ),
                    "${MediaStore.Audio.Media.DURATION} > 10000",
                    null,
                    "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
                )
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        if (isCancelled) break
                        totalCount++
                        val displayName = c.getString(1) ?: continue
                        val data = c.getString(2) ?: ""
                        val size = c.getLong(3)
                        val duration = c.getLong(4)
                        val mimeType = c.getString(5) ?: ""

                        // 跳过非音频 MIME
                        if (mimeType.isNotEmpty() && mimeType !in SUPPORTED_MIME_TYPES) {
                            // 检查是否是加密格式
                            val ext = File(displayName).extension.lowercase()
                            if (ext !in ENCRYPTED_AUDIO_EXTENSIONS) continue
                        }

                        // 跳过忽略目录
                        if (data.isNotEmpty() && ignorePrefixes.any { data.startsWith(it) }) continue
                        // 跳过 Android/data 和 Android/obb
                        if (data.contains("/Android/data/") || data.contains("/Android/obb/")) continue
                        // 跳过过小文件
                        if (size < 10240) continue

                        _scanProgress.value = ScanProgress(
                            current = totalCount,
                            total = 0,
                            currentFile = displayName
                        )

                        val ext = File(displayName).extension.lowercase()
                        val isImported = existingPaths.contains(data)

                        mediaStoreFiles.add(
                            PreviewAudioFile(
                                path = data,
                                displayName = File(displayName).nameWithoutExtension,
                                size = size,
                                extension = ext,
                                isAlreadyImported = isImported,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "MediaStore 查询失败")
            }

            Timber.tag(TAG).i("全盘预览扫描：MediaStore 查询到 $totalCount 个文件,有效 ${mediaStoreFiles.size} 个")

            // ★ 补充：文件系统遍历（仅当有「所有文件访问权限」时执行，否则跳过避免卡死）
            if (!isCancelled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && android.os.Environment.isExternalStorageManager()) {
                _scanProgress.value = ScanProgress(
                    current = totalCount,
                    total = 0,
                    currentFile = "补充扫描文件系统..."
                )
                val existingFilePaths = mediaStoreFiles.map { it.path }.toMutableSet()
                val fsFiles = mutableListOf<File>()
                val externalDir = Environment.getExternalStorageDirectory()
                collectAudioFiles(externalDir, ignorePrefixes, fsFiles, 0)

                for (file in fsFiles) {
                    if (isCancelled) break
                    if (file.absolutePath in existingFilePaths) continue
                    totalCount++
                    _scanProgress.value = ScanProgress(
                        current = totalCount,
                        total = 0,
                        currentFile = "补充: ${file.name}"
                    )
                    val ext = file.extension.lowercase()
                    val isImported = existingPaths.contains(file.absolutePath)
                    mediaStoreFiles.add(
                        PreviewAudioFile(
                            path = file.absolutePath,
                            displayName = file.nameWithoutExtension,
                            size = file.length(),
                            extension = ext,
                            isAlreadyImported = isImported,
                        )
                    )
                    existingFilePaths.add(file.absolutePath)
                }
                Timber.tag(TAG).i("全盘预览扫描：文件系统补充找到 ${fsFiles.size} 个文件")
            } else if (!isCancelled) {
                Timber.tag(TAG).i("全盘预览扫描：跳过文件系统遍历（无 MANAGE_EXTERNAL_STORAGE 权限）")
            }

            results.addAll(mediaStoreFiles)
            Timber.tag(TAG).i("全盘预览扫描完成: 找到 ${results.size} 个音频文件，其中已导入 ${results.count { it.isAlreadyImported }} 个")
            results
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "全盘预览扫描失败")
            emptyList()
        } finally {
            _isScanning.value = false
            _scanProgress.value = null
        }
    }

    /**
     * 递归收集目录下的所有音频文件（java.io.File 方式，非 SAF）
     */
    private fun collectAudioFiles(
        dir: File,
        ignorePrefixes: List<String>,
        results: MutableList<File>,
        depth: Int,
    ) {
        if (depth > MAX_FOLDER_DEPTH) return
        if (!dir.exists() || !dir.isDirectory) return

        val children = dir.listFiles() ?: return
        for (child in children) {
            if (isCancelled) return
            if (child.isDirectory) {
                // 跳过忽略目录
                val dirPath = if (child.absolutePath.endsWith("/")) child.absolutePath else "${child.absolutePath}/"
                if (ignorePrefixes.any { dirPath.startsWith(it) }) continue
                // 跳过 Android/data 和 Android/obb（系统保护目录，扫描无意义且可能触发权限问题）
                if (child.absolutePath.contains("/Android/data/") || child.absolutePath.contains("/Android/obb/")) continue
                collectAudioFiles(child, ignorePrefixes, results, depth + 1)
            } else {
                val ext = child.extension.lowercase()
                if (ext in SUPPORTED_EXTENSIONS || ext in ENCRYPTED_AUDIO_EXTENSIONS) {
                    // 跳过忽略目录下的文件
                    val filePath = child.absolutePath
                    if (ignorePrefixes.any { filePath.startsWith(it) }) continue
                    // 跳过过小文件（< 10KB，可能是占位文件或损坏文件）
                    if (child.length() < 10240) continue
                    results.add(child)
                }
            }
        }
    }

    override suspend fun importSelectedFiles(filePaths: List<String>): ScanResult = withContext(Dispatchers.IO) {
        if (filePaths.isEmpty()) return@withContext ScanResult(0, 0, 0, 0)
        if (_isScanning.value) {
            Timber.tag(TAG).w("扫描已在进行中")
            return@withContext ScanResult(0, 0, 0, 0)
        }
        _isScanning.value = true
        isCancelled = false

        try {
            // ★ existingPaths 以前从 MediaStore 查，但扫描预览也来自 MediaStore，
            //   导致两者完全重叠，导入时全部被跳过（新增 0 首）。
            //   现在改为从 App 数据库查已导入的路径，避免误跳过。
            val existingPaths = buildAppDbExistingPathsSet()
            val registeredMediaIds = mutableSetOf<Long>()
            var totalScanned = 0

            for (filePath in filePaths) {
                if (isCancelled) break
                totalScanned++

                _scanProgress.value = ScanProgress(
                    current = totalScanned,
                    total = filePaths.size,
                    currentFile = File(filePath).name
                )

                // 跳过已导入到 App 数据库的文件
                if (existingPaths.contains(filePath)) continue

                // 注册到 MediaStore（对已有文件可能返回 null → 用 path 查 mediaId 回退）
                var mediaId = registerFileInMediaStore(filePath)
                if (mediaId == null) {
                    // ★ 回退：文件可能已在 MediaStore 中，按 path 查 mediaId
                    mediaId = queryMediaStoreIdByPath(filePath)
                }
                if (mediaId == null) continue
                registeredMediaIds.add(mediaId)
                existingPaths.add(filePath)
            }

            // 复用 MediaStore 增量入库
            val deltaResult = performMediaStoreDeltaSync(
                changedMediaStoreIds = registeredMediaIds,
                deletedMediaStoreIds = emptySet(),
            )

            Timber.tag(TAG).i("选择性导入完成: 选中=${filePaths.size}, 导入=${deltaResult.newAdded}, 更新=${deltaResult.updated}")
            ScanResult(
                totalScanned = totalScanned,
                newAdded = deltaResult.newAdded,
                updated = deltaResult.updated,
                removed = 0,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "选择性导入失败")
            ScanResult(0, 0, 0, 0)
        } finally {
            _isScanning.value = false
            _scanProgress.value = null
        }
    }

    private suspend fun loadIgnorePrefixes(): List<String> =
        try {
            scanFolderRepository
                .getIgnoreFoldersSync()
                .mapNotNull { it.pathPrefix }
                .map { if (it.endsWith("/")) it else "$it/" }
        } catch (e: Exception) {
            emptyList()
        }

    private fun queryMediaStoreByIds(
        contentResolver: ContentResolver,
        mediaStoreIds: List<Long>,
    ) = if (mediaStoreIds.isEmpty()) {
        null
    } else {
        val uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
        val placeholders = mediaStoreIds.joinToString(",") { "?" }
        contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_MODIFIED,
            ),
            "${MediaStore.Audio.Media._ID} IN ($placeholders)",
            mediaStoreIds.map(Long::toString).toTypedArray(),
            null,
        )
    }

    private fun isMediaStoreSongEligible(
        mimeType: String,
        duration: Long,
        path: String,
        ignorePrefixes: List<String>,
    ): Boolean {
        // ★ mimeType 不在白名单时，用文件后缀作为回退判断
        //   MediaScannerConnection 注册后可能返回 null/空/application/octet-stream
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            if (path.isNotEmpty()) {
                val ext = File(path).extension.lowercase()
                if (ext !in SUPPORTED_EXTENSIONS) return false
                // 后缀支持，继续检查时长和路径
            } else {
                return false
            }
        }
        if (duration < 10000) return false
        if (path.isNotEmpty() && ignorePrefixes.any { path.startsWith(it) }) return false
        return true
    }

    private fun resolveCodec(
        mimeType: String,
        bitRate: Int,
    ): String {
        var codec =
            when {
                mimeType.contains("mp3", ignoreCase = true) -> "MP3"
                mimeType.contains("aac", ignoreCase = true) -> "AAC"
                mimeType.contains("flac", ignoreCase = true) -> "FLAC"
                mimeType.contains("alac", ignoreCase = true) -> "ALAC"
                mimeType.contains("opus", ignoreCase = true) -> "Opus"
                mimeType.contains("vorbis", ignoreCase = true) -> "Vorbis"
                mimeType.contains("wav", ignoreCase = true) -> "WAV"
                mimeType.contains("m4a", ignoreCase = true) -> "M4A"
                else -> mimeType.substringAfter("/").uppercase()
            }
        if (codec == "M4A") {
            codec = if (bitRate >= 700000) "ALAC" else "AAC"
        }
        return codec
    }

    private suspend fun refreshAlbumsByIds(albumIds: Set<Long>) {
        if (albumIds.isEmpty()) return
        val albumIdStrings = albumIds.map(Long::toString)
        val albums = loadAlbums(albumIds)
        albumDao.replaceByIds(albumIdStrings, albums)
    }

    /** 从 MediaStore 构建现有文件路径的可变集合 */
    private suspend fun buildExistingPathsSet(): MutableSet<String> = withContext(Dispatchers.IO) {
        val paths = mutableSetOf<String>()
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null, null, null
        )?.use { cursor ->
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            if (dataCol >= 0) {
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol)
                    if (!path.isNullOrEmpty()) paths.add(path)
                }
            }
        }
        paths
    }

    /** ★ 从 App 数据库构建已导入文件路径集合（避免与 MediaStore 扫描结果重叠） */
    private suspend fun buildAppDbExistingPathsSet(): MutableSet<String> = withContext(Dispatchers.IO) {
        try {
            songDao.getAllPaths().toMutableSet()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "查询 App 数据库已导入路径失败")
            mutableSetOf()
        }
    }

    /** ★ 按 path 从 MediaStore 查询 mediaId（回退：文件可能已在 MediaStore 中）
     *  Android 11+ DATA 列已废弃,改用 DISPLAY_NAME + SIZE 联合查询 */
    private fun queryMediaStoreIdByPath(filePath: String): Long? {
        return try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val file = File(filePath)
            val displayName = file.name
            val fileSize = file.length()
            // ★ Android 10 以下仍可用 DATA 列精确匹配
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DATA} = ?",
                    arrayOf(filePath),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                        if (idCol >= 0) return cursor.getLong(idCol)
                    }
                }
                return null
            }
            // ★ Android 10+ 用 DISPLAY_NAME + SIZE 联合查询（DATA 列已废弃）
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.SIZE} = ?",
                arrayOf(displayName, fileSize.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    if (idCol >= 0) cursor.getLong(idCol) else null
                } else null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "按 path 查 MediaStore mediaId 失败: $filePath")
            null
        }
    }

    /**
     * 递归遍历 SAF 目录树，收集所有音频文件信息
     * @param treeUri  SAF tree URI
     * @param ignorePrefixes 忽略文件夹的绝对路径前缀列表（以 "/" 结尾）
     * @param parentDocId  当前递归层级的 document ID，首次调用传 null 使用 tree root
     * @param depth  当前递归深度，超过 MAX_FOLDER_DEPTH 时停止
     */
    private fun walkDocumentTree(
        treeUri: Uri,
        ignorePrefixes: List<String>,
        parentDocId: String? = null,
        depth: Int = 0,
    ): List<DocumentFileInfo> {
        if (depth > MAX_FOLDER_DEPTH) return emptyList()

        val docId = parentDocId ?: DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

        val results = mutableListOf<DocumentFileInfo>()

        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

            while (cursor.moveToNext() && !isCancelled) {
                val childDocId = cursor.getString(idCol) ?: continue
                val displayName = cursor.getString(nameCol) ?: continue
                val mimeType = cursor.getString(mimeCol) ?: continue
                val size = cursor.getLong(sizeCol)

                // 解析绝对路径（用于忽略文件夹匹配）
                val absolutePath = resolveAbsolutePath(childDocId)

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // 是目录：本身位于忽略目录之下时跳过整棵子树；
                    // 若忽略目录是它的后代，仍需递归进入，由子层逐项过滤
                    val folderPath = if (absolutePath != null) {
                        if (absolutePath.endsWith("/")) absolutePath else "$absolutePath/"
                    } else null

                    if (folderPath != null && ignorePrefixes.any { folderPath.startsWith(it) }) {
                        continue // 跳过此子目录整棵树
                    }
                    results.addAll(walkDocumentTree(treeUri, ignorePrefixes, childDocId, depth + 1))
                } else {
                    // 是文件：按扩展名过滤（含加密音乐格式，加密格式仅用于扫描展示）
                    val ext = displayName.substringAfterLast('.', "").lowercase()
                    if (ext !in SUPPORTED_EXTENSIONS && ext !in ENCRYPTED_AUDIO_EXTENSIONS) continue

                    // 忽略文件夹过滤
                    if (absolutePath != null && ignorePrefixes.any { absolutePath.startsWith(it) }) continue

                    results.add(
                        DocumentFileInfo(
                            displayName = displayName,
                            mimeType = if (mimeType == "application/octet-stream") "audio/$ext" else mimeType,
                            size = size,
                            absolutePath = absolutePath,
                            duration = 0L,
                        )
                    )
                }
            }
        }
        return results
    }

    /**
     * 从 SAF document ID 解析绝对文件路径
     * - 主存储：`primary:relative/path` → `/storage/emulated/0/relative/path`
     * - 外置存储：`XXXX-XXXX:relative/path` → `/storage/XXXX-XXXX/relative/path`
     */
    private fun resolveAbsolutePath(documentId: String): String? {
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volume, relativePath) = parts
        return if (volume.equals("primary", ignoreCase = true)) {
            "${Environment.getExternalStorageDirectory()}/$relativePath"
        } else {
            "/storage/$volume/$relativePath"
        }
    }

    /**
     * 通过 MediaScannerConnection 将文件注册进 MediaStore，返回获得的 mediaStoreId
     * 回调在主线程触发，通过 suspendCancellableCoroutine 桥接到协程
     */
    private suspend fun registerFileInMediaStore(filePath: String): Long? =
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(filePath),
                null
            ) { _, uri ->
                if (uri != null) {
                    val mediaId = uri.lastPathSegment?.toLongOrNull()
                    cont.resume(mediaId)
                } else {
                    cont.resume(null)
                }
            }
        }

    /** SAF 文件信息 */
    private data class DocumentFileInfo(
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val absolutePath: String?,
        val duration: Long,
    )

    /**
     * 使用 Taglib 提取音频详细信息（优先使用）
     * 优化：只打开一次 FileDescriptor，通过 dup() 复制给两个 TagLib 调用
     */
    private fun extractAudioInfoWithTaglib(
        contentResolver: ContentResolver,
        mediaStoreId: Long,
        fallbackDuration: Long,
    ): AudioInfo {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        var sampleRate = 0
        var bitRate = 0
        var channels = 0
        var digit = 0
        var trackGainDb = 0.0f
        var albumGainDb = 0.0f
        try {
            val uri = "content://media/external/audio/media/$mediaStoreId".toUri()

            // 只打开一次 FileDescriptor，通过 dup() 复制给两个 TagLib 调用
            // dupPfd 包裹在 use{} 中：若在 detachFd() 之前发生异常，use{} 负责
            // 关闭复制出来的 fd，避免泄漏；detachFd() 成功后 close() 是空操作。
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                try {
                    pfd.dup().use { dupPfd ->
                        // detachFd() 将 fd 所有权转移给 TagLib，TagLib 用完后负责关闭
                        val fdForMetadata = pfd.detachFd()
                        val fdForAudioProps = dupPfd.detachFd()

                        // 读取元数据（标题、艺术家、专辑）
                        try {
                            val metadata = TagLib.getMetadata(
                                fd = fdForMetadata,
                                readPictures = false
                            )
                            if (metadata != null) {
                                title = metadata.propertyMap["TITLE"]?.firstOrNull()
                                artist = metadata.propertyMap["ARTIST"]?.firstOrNull()
                                album = metadata.propertyMap["ALBUM"]?.firstOrNull()
                                trackGainDb = extractGainValue(metadata.propertyMap, TRACK_GAIN_KEYS)?: 0.0f
                                albumGainDb = extractGainValue(metadata.propertyMap, ALBUM_GAIN_KEYS)?: 0.0f
                            }
                            Timber.tag(TAG).e("响度信息: trackGainDb=$trackGainDb dB, albumGainDb=$albumGainDb dB")
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Taglib 元数据读取失败")
                        }

                        // 读取音频属性（采样率、比特率等）
                        try {
                            TagLib.getAudioProperties(
                                fdForAudioProps,
                                readStyle = AudioPropertiesReadStyle.Accurate
                            )?.let {
                                duration = it.length.toLong()
                                sampleRate = it.sampleRate
                                bitRate = it.bitrate * 1000 // kbps 转 bps
                                channels = it.channels
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Taglib 音频属性读取失败")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Taglib FD 操作失败，回退到默认值")
                }
            }

            // 如果 Taglib 未能获取 duration，使用回退值
            if (duration == 0L) {
                duration = fallbackDuration
            }

            // 从 MediaStore 获取 bitrate（作为补充）
            if (bitRate == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.query(
                    Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        mediaStoreId.toString()
                    ),
                    arrayOf(MediaStore.Audio.Media.BITRATE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val bitRateColumn = cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                        if (bitRateColumn != -1) {
                            bitRate = cursor.getInt(bitRateColumn)
                        }
                    }
                }
            }

            // 使用默认值填充缺失的信息
            if (sampleRate == 0) sampleRate = 44100
            if (channels == 0) channels = 2
            if (digit == 0) digit = 16

        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "提取音频信息失败: $mediaStoreId")
            duration = fallbackDuration
            sampleRate = 44100
            channels = 2
            digit = 16
        }

        return AudioInfo(
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            sampleRate = sampleRate,
            bitRate = bitRate,
            channels = channels,
            digit = digit
        )
    }

    /**
     * 根据歌曲名称生成排序用的首字符
     * - 英文：直接使用首字符（大写）
     * - 中文：转换为拼音首字母
     * - 日文：转换为罗马音首字母
     * - 其他：返回 "#"
     */
    private fun generateSortName(displayName: String): String {
        if (displayName.isEmpty()) return "#"

        val firstChar = displayName.first()

        return try {
            when {
                // 英文字母（A-Z, a-z）
                firstChar.isLetter() && firstChar.code in 0x41..0x7A -> {
                    firstChar.uppercaseChar().toString()
                }

                // 中文（CJK 统一表意文字）
                firstChar.code in 0x4E00..0x9FFF -> {
                    val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII")
                    val pinyin = transliterator.transliterate(firstChar.toString())
                    pinyin.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                }

                // 日文平假名（ひらがな）
                firstChar.code in 0x3040..0x309F -> {
                    val transliterator = Transliterator.getInstance("Hiragana-Latin")
                    val romaji = transliterator.transliterate(firstChar.toString())
                    romaji.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                }

                // 日文片假名（カタカナ）
                firstChar.code in 0x30A0..0x30FF -> {
                    val transliterator = Transliterator.getInstance("Katakana-Latin")
                    val romaji = transliterator.transliterate(firstChar.toString())
                    romaji.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                }

                // 数字
                firstChar.isDigit() -> "#"

                // 其他字符
                else -> "#"
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "生成排序名称失败: $displayName")
            "#"
        }
    }

    /**
     * 音频信息数据类（扩展版本，包含元数据）
     */
    private data class AudioInfo(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val duration: Long,
        val sampleRate: Int,
        val bitRate: Int,
        val channels: Int,
        val digit: Int,
    )


    private suspend fun loadAlbums(albumIds: Set<Long>? = null): List<AlbumEntity> = withContext(Dispatchers.IO) {
        if (albumIds != null && albumIds.isEmpty()) return@withContext emptyList()
        val albums = mutableListOf<AlbumEntity>()
        val collection = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
            MediaStore.Audio.Albums.FIRST_YEAR
        )

        val sortOrder = "${MediaStore.Audio.Albums.ALBUM} ASC"

        val chunks = albumIds?.toList()?.chunked(300) ?: listOf(emptyList())
        chunks.forEach { chunk ->
            val selection =
                if (chunk.isEmpty()) {
                    null
                } else {
                    "${MediaStore.Audio.Albums._ID} IN (${chunk.joinToString(",") { "?" }})"
                }
            val selectionArgs =
                if (chunk.isEmpty()) {
                    null
                } else {
                    chunk.map(Long::toString).toTypedArray()
                }

            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST)
                val songsCountColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.NUMBER_OF_SONGS)
                val yearColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.FIRST_YEAR)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(albumColumn) ?: continue
                    val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                    val songsCount = cursor.getInt(songsCountColumn)
                    val year = cursor.getInt(yearColumn)
                    val albumArtUri =
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                            id,
                        ).toString()
                    albums.add(
                        AlbumEntity(
                            id = id.toString(),
                            title = title,
                            artist = artist,
                            artworkUri = albumArtUri,
                            year = year,
                            numberOfSongs = songsCount,
                        ),
                    )
                }
            }
        }

        albums
    }


    // 从 TagLib 的 propertyMap 中提取 ReplayGain 值，支持多个可能的键
    private fun extractGainValue(propertyMap: Map<String, Array<String>>, keys: List<String>): Float? {
        for (key in keys) {
            val rawValue = propertyMap[key]?.firstOrNull() ?: continue
            return parseGainString(rawValue)
        }
        return null
    }

    // 解析 ReplayGain 字符串，支持 "dB" 后缀和空白字符
    private fun parseGainString(raw: String): Float? {
        val cleaned = raw.trim()
            .replace(Regex("[dD][bB]"), "")  // Remove "dB" suffix
            .trim()
        return cleaned.toFloatOrNull()
    }
}
