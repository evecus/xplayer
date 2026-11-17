package com.xplayer.player

import android.content.Context
import android.view.Surface

/**
 * 一次播放请求 (本地文件 / 网络流共用).
 * [live] 仅作内核调参用 (直播低延迟), 本播放器以本地点播为主, 默认 false.
 */
data class PlayRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val startMs: Long = 0L,
    val live: Boolean = false,
)

/** 轨道描述 (id 为 Media3 group/track 编码, name 为展示名). */
data class TrackDesc(val id: Int, val name: String)

/** 播放内核事件回调. */
interface PlayerListener {

    /** 已就绪: 可 seek, 时长可用. 每个媒体项只回调一次. */
    fun onReady() {}

    fun onPlayingChanged(playing: Boolean) {}

    fun onBuffering(buffering: Boolean) {}

    fun onEnded() {}

    /** 播放失败. [message] 已带内核与解码方式信息, 直接展示即可. */
    fun onError(code: Int, message: String) {}

    /** 视频尺寸变化 (宽高 + 像素宽高比; 方形像素为 1f). */
    fun onVideoSize(width: Int, height: Int, pixelRatio: Float = 1f, rotationDegrees: Int = 0) {}

    /** 提示类信息 (如"硬解失败, 已切换软解"). */
    fun onNotice(message: String) {}
}

/**
 * 播放内核抽象: 上层 ViewModel 只依赖本接口.
 * 生命周期: [open] → [play] → … → [stop] (换源) / [release] (退出页面).
 * 表面可随时 [setSurface] 挂载/摘除.
 */
interface PlayerEngine {

    val isPlaying: Boolean

    /** 当前位置 (ms, 未就绪时为 0). */
    val currentPosition: Long

    /** 总时长 (ms); 未知时为 0. */
    val duration: Long

    /** 已缓冲位置 (ms). */
    val bufferedPosition: Long

    var listener: PlayerListener?

    /** 挂载/摘除渲染表面. */
    fun setSurface(surface: Surface?)

    /** 准备播放 (不自动播: 由调用方决定 [play]). */
    fun open(request: PlayRequest)

    fun play()
    fun pause()

    /** 停止当前媒体项 (换源/退出); 表面与监听器保留. */
    fun stop()

    fun seekTo(ms: Long)

    /** 倍速 (1f = 正常). */
    fun setSpeed(speed: Float)

    /** 音轨列表 (id 为 Media3 group+track 编码, name 为展示名). */
    fun getAudioTracks(): List<TrackDesc>

    /** 字幕轨列表 (内嵌). */
    fun getSubtitleTracks(): List<TrackDesc>

    fun selectAudioTrack(id: Int)
    fun selectSubtitleTrack(id: Int)

    /** 释放内核 (页面销毁). */
    fun release()

    /** 当前内核 + 解码方式描述. */
    fun describe(): String
}

object PlayerEngines {

    /** 没有可用视频解码器时的统一错误码. */
    const val ERROR_NO_DECODER = -1000

    /** 创建内核 (ExoPlayer + NextLib, 解码方式由传入的 [decoderMode] 决定). */
    fun create(decoderMode: com.xplayer.data.model.DecoderMode, context: Context): PlayerEngine =
        ExoEngine(decoderMode, context)
}
