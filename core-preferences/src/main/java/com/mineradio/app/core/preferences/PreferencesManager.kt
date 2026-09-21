package com.mineradio.app.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PreferencesManager(
    private val context: Context,
) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

    object Keys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DYNAMIC_SPECTRUM_BACKGROUND = stringPreferencesKey("dynamic_spectrum_background")
        val DYNAMIC_COVER_TYPE = stringPreferencesKey("dynamic_cover_type")
        val PROGRESS_BAR_STYLE = stringPreferencesKey("progress_bar_style")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val REVERB_ENABLED = booleanPreferencesKey("reverb_enabled")
        val REVERB_LEVEL = stringPreferencesKey("reverb_level")
        val REVERB_ROOM_SIZE = stringPreferencesKey("reverb_room_size")
        // 自定义背景
        val BACKGROUND_MODE = intPreferencesKey("background_mode")
        val BACKGROUND_IMAGE_URI = stringPreferencesKey("background_image_uri")
        val BACKGROUND_VIDEO_URI = stringPreferencesKey("background_video_uri")
        val PLAYER_BACKGROUND_IMAGE_URI = stringPreferencesKey("player_background_image_uri")
        val LANDSCAPE_BACKGROUND_IMAGE_URI = stringPreferencesKey("landscape_background_image_uri")
        // 播放器内部背景视频 URI
        val PLAYER_BACKGROUND_VIDEO_URI = stringPreferencesKey("player_background_video_uri")
        // 横屏歌词背景视频 URI
        val LANDSCAPE_BACKGROUND_VIDEO_URI = stringPreferencesKey("landscape_background_video_uri")
        // UI布局样式：0=经典模式，1=简洁模式
        val UI_LAYOUT_MODE = intPreferencesKey("ui_layout_mode")
        // 音效预设：0=无，1=3D环绕，2=8D环绕，3=360度环绕，4=超低音，5=超重低音，
        // 6=清澈人声，7=摇滚，8=经典，9=动感，10=电音
        val SOUND_PRESET = intPreferencesKey("sound_preset")
        // 低音增益（-12 ~ +12 dB）
        val BASS_GAIN = stringPreferencesKey("bass_gain")
        // 高音增益（-12 ~ +12 dB）
        val TREBLE_GAIN = stringPreferencesKey("treble_gain")
        // 环绕强度（0 ~ 100）
        val SURROUND_INTENSITY = stringPreferencesKey("surround_intensity")
        // 环绕深度（0 ~ 100）
        val SURROUND_DEPTH = stringPreferencesKey("surround_depth")
        // 音效增强开关（高音增强 / 低音增强 / 人声增强 / 立体声扩展 / 响度均衡）
        // 混响开关复用 REVERB_ENABLED
        val TREBLE_BOOST_ENABLED = booleanPreferencesKey("treble_boost_enabled")
        val BASS_BOOST_ENABLED = booleanPreferencesKey("bass_boost_enabled")
        val VOCAL_BOOST_ENABLED = booleanPreferencesKey("vocal_boost_enabled")
        val STEREO_EXPAND_ENABLED = booleanPreferencesKey("stereo_expand_enabled")
        val LOUDNESS_EQUALIZATION_ENABLED = booleanPreferencesKey("loudness_equalization_enabled")
        // 歌词字体大小（sp，12 ~ 32，默认 16）
        val LYRIC_FONT_SIZE = intPreferencesKey("lyric_font_size")
        // 歌词行间距倍数（1.0 ~ 2.0，默认 1.2）
        val LYRIC_LINE_SPACING = stringPreferencesKey("lyric_line_spacing")
        // 歌词高亮颜色（ARGB 整数，默认跟随主题）
        val LYRIC_HIGHLIGHT_COLOR = intPreferencesKey("lyric_highlight_color")
        // 歌词普通颜色（ARGB 整数，默认跟随主题）
        val LYRIC_NORMAL_COLOR = intPreferencesKey("lyric_normal_color")
        // 歌词字体家族（0=默认，1=衬线，2=等宽，3=圆体）
        val LYRIC_FONT_FAMILY = intPreferencesKey("lyric_font_family")
        // 歌词样式预设（0=默认滚动样式，1=Folia 唱一句显示一句样式）
        val LYRIC_STYLE_PRESET = intPreferencesKey("lyric_style_preset")
    }

    fun getBoolean(
        key: Preferences.Key<Boolean>,
        defaultValue: Boolean = false,
    ): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[key] ?: defaultValue
        }.distinctUntilChanged()

    suspend fun setBoolean(
        key: Preferences.Key<Boolean>,
        value: Boolean,
    ) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    fun getString(
        key: Preferences.Key<String>,
        defaultValue: String = "",
    ): Flow<String> =
        context.dataStore.data.map { preferences ->
            preferences[key] ?: defaultValue
        }

    suspend fun setString(
        key: Preferences.Key<String>,
        value: String,
    ) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    fun getFloat(
        key: Preferences.Key<String>,
        defaultValue: Float = 0f,
    ): Flow<Float> =
        context.dataStore.data.map { preferences ->
            preferences[key]?.toFloatOrNull() ?: defaultValue
        }

    suspend fun setFloat(
        key: Preferences.Key<String>,
        value: Float,
    ) {
        context.dataStore.edit { preferences ->
            preferences[key] = value.toString()
        }
    }

    fun getFloatList(
        key: Preferences.Key<String>,
        defaultValue: List<Float> = emptyList(),
    ): Flow<List<Float>> =
        context.dataStore.data.map { preferences ->
            val serialized = preferences[key]
            if (serialized.isNullOrEmpty()) {
                defaultValue
            } else {
                serialized.split(",").mapNotNull { it.toFloatOrNull() }.ifEmpty { defaultValue }
            }
        }

    suspend fun setFloatList(
        key: Preferences.Key<String>,
        value: List<Float>,
    ) {
        context.dataStore.edit { preferences ->
            preferences[key] = value.joinToString(",")
        }
    }

    fun getInt(
        key: Preferences.Key<Int>,
        defaultValue: Int = 0,
    ): Flow<Int> =
        context.dataStore.data.map { preferences ->
            preferences[key] ?: defaultValue
        }.distinctUntilChanged()

    suspend fun setInt(
        key: Preferences.Key<Int>,
        value: Int,
    ) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }
}
