package com.xplayer.data.model

data class AppSettings(
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val defaultOrientation: PlayOrientation = PlayOrientation.DEFAULT,
    val showThumbnail: Boolean = true,
    val continuousPlay: Boolean = true,
    /** true: 上下滑动切换视频; false: 左半屏调亮度、右半屏调音量. */
    val swipeSwitchVideo: Boolean = true,
    val lastScanTime: Long = 0L,
)

enum class DecoderMode(val label: String) {
    AUTO("自动"),
    HARDWARE("硬解"),
    SOFTWARE("软解"),
}

enum class PlayOrientation(val label: String) {
    DEFAULT("默认"),
    AUTO("跟随传感器"),
    LANDSCAPE("横屏"),
    PORTRAIT("竖屏"),
}