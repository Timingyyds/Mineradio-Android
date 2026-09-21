package com.mineradio.app.common.entity

import androidx.compose.runtime.Immutable

/**
 * 播放器进度条样式。
 * ★ 简化版：只保留时域波形（默认样式），移除其他样式选择
 */
@Immutable
sealed class ProgressBarStyle(
    val value: String,
    val name: String,
) {
    object TimeDomainWaveform : ProgressBarStyle(
        "time_domain_waveform",
        "时域波形",
    )

    override fun toString(): String = name

    companion object {
        fun fromString(value: String): ProgressBarStyle = TimeDomainWaveform

        val presets: List<ProgressBarStyle>
            get() = listOf(TimeDomainWaveform)
    }
}
