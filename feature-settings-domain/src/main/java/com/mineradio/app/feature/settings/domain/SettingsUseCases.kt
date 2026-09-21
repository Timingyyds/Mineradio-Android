package com.mineradio.app.feature.settings.domain

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import com.mineradio.app.core.preferences.PreferencesManager

class SettingsUseCases(
    private val preferencesManager: PreferencesManager,
) {
    object Keys {
        val DARK_MODE = PreferencesManager.Keys.DARK_MODE
        val KEEP_SCREEN_ON = PreferencesManager.Keys.KEEP_SCREEN_ON
        val DYNAMIC_COLOR = PreferencesManager.Keys.DYNAMIC_COLOR
        val DYNAMIC_SPECTRUM_BACKGROUND = PreferencesManager.Keys.DYNAMIC_SPECTRUM_BACKGROUND
        val DYNAMIC_COVER_TYPE = PreferencesManager.Keys.DYNAMIC_COVER_TYPE
        val PROGRESS_BAR_STYLE = PreferencesManager.Keys.PROGRESS_BAR_STYLE
        val EQ_ENABLED = PreferencesManager.Keys.EQ_ENABLED
        val EQ_BANDS = PreferencesManager.Keys.EQ_BANDS
        val REVERB_ENABLED = PreferencesManager.Keys.REVERB_ENABLED
        val REVERB_LEVEL = PreferencesManager.Keys.REVERB_LEVEL
        val REVERB_ROOM_SIZE = PreferencesManager.Keys.REVERB_ROOM_SIZE
        // 自定义背景
        val BACKGROUND_MODE = PreferencesManager.Keys.BACKGROUND_MODE
        val BACKGROUND_IMAGE_URI = PreferencesManager.Keys.BACKGROUND_IMAGE_URI
        val BACKGROUND_VIDEO_URI = PreferencesManager.Keys.BACKGROUND_VIDEO_URI
        val PLAYER_BACKGROUND_IMAGE_URI = PreferencesManager.Keys.PLAYER_BACKGROUND_IMAGE_URI
        val LANDSCAPE_BACKGROUND_IMAGE_URI = PreferencesManager.Keys.LANDSCAPE_BACKGROUND_IMAGE_URI
        val PLAYER_BACKGROUND_VIDEO_URI = PreferencesManager.Keys.PLAYER_BACKGROUND_VIDEO_URI
        val LANDSCAPE_BACKGROUND_VIDEO_URI = PreferencesManager.Keys.LANDSCAPE_BACKGROUND_VIDEO_URI
        // UI布局样式：0=经典模式，1=简洁模式
        val UI_LAYOUT_MODE = PreferencesManager.Keys.UI_LAYOUT_MODE
        // 音效预设
        val SOUND_PRESET = PreferencesManager.Keys.SOUND_PRESET
        val BASS_GAIN = PreferencesManager.Keys.BASS_GAIN
        val TREBLE_GAIN = PreferencesManager.Keys.TREBLE_GAIN
        val SURROUND_INTENSITY = PreferencesManager.Keys.SURROUND_INTENSITY
        val SURROUND_DEPTH = PreferencesManager.Keys.SURROUND_DEPTH
        // 音效增强开关（混响复用 REVERB_ENABLED）
        val TREBLE_BOOST_ENABLED = PreferencesManager.Keys.TREBLE_BOOST_ENABLED
        val BASS_BOOST_ENABLED = PreferencesManager.Keys.BASS_BOOST_ENABLED
        val VOCAL_BOOST_ENABLED = PreferencesManager.Keys.VOCAL_BOOST_ENABLED
        val STEREO_EXPAND_ENABLED = PreferencesManager.Keys.STEREO_EXPAND_ENABLED
        val LOUDNESS_EQUALIZATION_ENABLED = PreferencesManager.Keys.LOUDNESS_EQUALIZATION_ENABLED
        // 歌词
        val LYRIC_FONT_SIZE = PreferencesManager.Keys.LYRIC_FONT_SIZE
        val LYRIC_LINE_SPACING = PreferencesManager.Keys.LYRIC_LINE_SPACING
        val LYRIC_HIGHLIGHT_COLOR = PreferencesManager.Keys.LYRIC_HIGHLIGHT_COLOR
        val LYRIC_NORMAL_COLOR = PreferencesManager.Keys.LYRIC_NORMAL_COLOR
        val LYRIC_FONT_FAMILY = PreferencesManager.Keys.LYRIC_FONT_FAMILY
        val LYRIC_STYLE_PRESET = PreferencesManager.Keys.LYRIC_STYLE_PRESET
    }

    fun getBoolean(
        key: Preferences.Key<Boolean>,
        defaultValue: Boolean = false,
    ): Flow<Boolean> = preferencesManager.getBoolean(key, defaultValue)

    suspend fun setBoolean(
        key: Preferences.Key<Boolean>,
        value: Boolean,
    ) {
        preferencesManager.setBoolean(key, value)
    }

    fun getString(
        key: Preferences.Key<String>,
        defaultValue: String = "",
    ): Flow<String> = preferencesManager.getString(key, defaultValue)

    suspend fun setString(
        key: Preferences.Key<String>,
        value: String,
    ) {
        preferencesManager.setString(key, value)
    }

    fun getInt(
        key: Preferences.Key<Int>,
        defaultValue: Int = 0,
    ): Flow<Int> = preferencesManager.getInt(key, defaultValue)

    suspend fun setInt(
        key: Preferences.Key<Int>,
        value: Int,
    ) {
        preferencesManager.setInt(key, value)
    }

    fun getFloat(
        key: Preferences.Key<String>,
        defaultValue: Float = 0f,
    ): Flow<Float> = preferencesManager.getFloat(key, defaultValue)

    suspend fun setFloat(
        key: Preferences.Key<String>,
        value: Float,
    ) {
        preferencesManager.setFloat(key, value)
    }

    fun getFloatList(
        key: Preferences.Key<String>,
        defaultValue: List<Float> = emptyList(),
    ): Flow<List<Float>> = preferencesManager.getFloatList(key, defaultValue)

    suspend fun setFloatList(
        key: Preferences.Key<String>,
        value: List<Float>,
    ) {
        preferencesManager.setFloatList(key, value)
    }
}
