package com.mineradio.app.player.impl.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import com.mineradio.app.common.entity.Song
import com.mineradio.app.player.api.PlayMode
import com.mineradio.app.storage.api.ISongRepository
import org.koin.java.KoinJavaComponent.getKoin

class PlayerKVUtils(
    context: Context,
) {
    private val sharedPreferences = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    private val songRepository = getKoin().get<ISongRepository>()

    companion object {
      private const val KEY_HISTORY_IDS = "history_ids"
      private const val KEY_HISTORY_POSITION = "history_position"
      private const val KEY_PLAY_MODE = "play_mode"
      // ★ 播放进度保存/恢复
      private const val KEY_SAVED_MEDIA_ID = "saved_media_id"
      private const val KEY_SAVED_POSITION_MS = "saved_position_ms"
    }

    /**
     * 历史播放的id
     */
    fun setHistoryIds(ids: List<Long>) {
        sharedPreferences.edit { putString("history_ids", ids.joinToString(",")) }
    }

    /**
     * 获取历史播放歌曲列表
     * 使用批量查询优化，避免 N+1 问题
     */
    @WorkerThread
    suspend fun getHistoryItems(): List<Song> {
        val ids = getHistoryIds()
        if (ids.isEmpty()) return emptyList()
        
        // 使用批量查询而非循环单独查询
        val songs = songRepository.getSongsByMediaStoreIds(ids)
        
        // 按历史顺序返回结果
        val songMap = songs.associateBy { it.mediaStoreId }
        return ids.mapNotNull { songMap[it] }
    }

    /**
     * 获取历史播放的id
     */
    fun getHistoryIds(): List<Long> {
        val ids = sharedPreferences.getString(KEY_HISTORY_IDS, "")
        return ids?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
    }

    /**
     * 播放到第一个
     */
    fun setHistoryPosition(position: Int) {
        sharedPreferences.edit { putInt(KEY_HISTORY_POSITION, position) }
    }

    /**
     * 设置播放的到第几个的index到缓存
     */
    fun getHistoryPosition(position: Int) {
        sharedPreferences.getInt(KEY_HISTORY_POSITION, 0)
    }

    /**
     * 播放模式
     */
    fun setPlayMode(mode: String) {
        sharedPreferences.edit { putString(KEY_PLAY_MODE, mode) }
    }

    /**
     * 获取播放模式
     */
    fun getPlayMode(): String = sharedPreferences.getString(KEY_PLAY_MODE, null) ?: PlayMode.LOOP.name

    fun getPlayModeFlow(): Flow<PlayMode> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == KEY_PLAY_MODE) {
                        trySend(parsePlayMode(getPlayMode()))
                    }
                }
            if (sharedPreferences.contains(KEY_PLAY_MODE)) {
                trySend(parsePlayMode(getPlayMode()))
            }
            sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.buffer(Channel.CONFLATED)  // 使用 CONFLATED 替代 UNLIMITED，只保留最新值

    private fun parsePlayMode(mode: String): PlayMode =
        when (mode) {
            "LOOP" -> PlayMode.LOOP
            "SHUFFLE" -> PlayMode.SHUFFLE
            else -> PlayMode.LIST
        }

    // ==================== 播放进度保存/恢复 ====================

    /**
     * 保存当前播放进度（媒体ID + 位置）
     * ★ 用于退出后恢复上次播放进度
     */
    fun savePlaybackPosition(mediaId: String, positionMs: Long) {
        sharedPreferences.edit {
            putString(KEY_SAVED_MEDIA_ID, mediaId)
            putLong(KEY_SAVED_POSITION_MS, positionMs)
        }
    }

    /**
     * 获取保存的媒体ID
     */
    fun getSavedMediaId(): String? =
        sharedPreferences.getString(KEY_SAVED_MEDIA_ID, null)

    /**
     * 获取保存的播放位置（毫秒）
     */
    fun getSavedPositionMs(): Long =
        sharedPreferences.getLong(KEY_SAVED_POSITION_MS, 0L)

    /**
     * 清除保存的播放进度
     */
    fun clearSavedPlaybackPosition() {
        sharedPreferences.edit {
            remove(KEY_SAVED_MEDIA_ID)
            remove(KEY_SAVED_POSITION_MS)
        }
    }
}
