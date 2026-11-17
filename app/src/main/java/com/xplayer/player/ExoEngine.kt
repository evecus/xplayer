package com.xplayer.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.xplayer.data.model.DecoderMode

/**
 * ExoPlayer (media3) 内核.
 * 解码器过滤与"自动"模式的硬 → 软回退由 [DecoderController] 负责,
 * 本类只做 内核事件 → [PlayerListener] 的翻译, 让上层与内核无关.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ExoEngine(decoderMode: DecoderMode, context: Context) : PlayerEngine {

    private val decoder = DecoderController(context, decoderMode) { listener?.onNotice(it) }
    private val exo: ExoPlayer = decoder.createPlayer()

    override var listener: PlayerListener? = null

    /** 起播位置 (open 时传入, 就绪后 seek). */
    private var startMs = 0L

    /** 当前媒体项是否已回调过 onReady (自动回退重新 prepare 时复位). */
    private var ready = false

    init {
        exo.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener?.onBuffering(true)
                    Player.STATE_READY -> {
                        listener?.onBuffering(false)
                        if (!ready) {
                            ready = true
                            if (startMs > 0) exo.seekTo(startMs)
                            listener?.onReady()
                        }
                    }
                    Player.STATE_ENDED -> listener?.onEnded()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 自动模式: 解码器相关错误 → 逐级降级重试 (软 MediaCodec → FFmpeg), 不算失败.
                if (decoder.onPlayerError(exo, error)) {
                    ready = false
                    return
                }
                listener?.onBuffering(false)
                val cause = generateSequence<Throwable>(error) { it.cause }.last()
                listener?.onError(
                    error.errorCode,
                    "播放失败: ${error.errorCodeName}\n" +
                        "${cause.javaClass.simpleName}: ${cause.message ?: ""}\n" +
                        "解码方式: ${decoder.describe()}",
                )
            }

            override fun onTracksChanged(tracks: Tracks) {
                // 硬解/软解模式没有可用视频解码器: ExoPlayer 只会静音只放声音, 这里按失败处理.
                if (decoder.onTracks(tracks) == DecoderController.TracksCheck.UNSUPPORTED) {
                    exo.stop()
                    listener?.onBuffering(false)
                    listener?.onError(PlayerEngines.ERROR_NO_DECODER, decoder.unsupportedMessage())
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = videoSize.pixelWidthHeightRatio
                    val rotation = try { exo.videoFormat?.rotationDegrees ?: 0 } catch (_: Exception) { 0 }
                    listener?.onVideoSize(
                        videoSize.width,
                        videoSize.height,
                        if (ratio > 0f) ratio else 1f,
                        if (rotation == 90 || rotation == 270) rotation else 0,
                    )
                }
            }
        })
    }

    override val isPlaying: Boolean get() = exo.isPlaying

    override val currentPosition: Long get() = exo.currentPosition.coerceAtLeast(0L)

    override val duration: Long
        get() = if (exo.duration == C.TIME_UNSET) 0L else exo.duration.coerceAtLeast(0L)

    override val bufferedPosition: Long get() = exo.bufferedPosition.coerceAtLeast(0L)

    override fun setSurface(surface: Surface?) {
        if (surface == null) exo.clearVideoSurface() else exo.setVideoSurface(surface)
    }

    override fun open(request: PlayRequest) {
        startMs = request.startMs
        ready = false
        decoder.newItem()
        val item = MediaItem.Builder()
            .setUri(request.url)
            .apply { request.mimeType?.let { setMimeType(it) } }
            .build()
        exo.setMediaItem(item)
        exo.prepare()
    }

    override fun play() {
        exo.play()
    }

    override fun pause() {
        exo.pause()
    }

    override fun stop() {
        exo.stop()
        ready = false
    }

    override fun seekTo(ms: Long) {
        exo.seekTo(ms)
    }

    override fun setSpeed(speed: Float) {
        exo.setPlaybackSpeed(speed)
    }

    // ── 轨道 ───────────────────────────────────────────────────────────────
    // id 编码: 高 16 位 = group 索引, 低 16 位 = track 索引; -1 表示关闭.
    private fun trackId(group: Int, track: Int): Int = (group shl 16) or (track and 0xFFFF)

    private fun collectTracks(type: Int): List<TrackDesc> {
        val out = mutableListOf<TrackDesc>()
        val groups = exo.currentTracks.groups
        for (g in groups.indices) {
            val group = groups[g]
            if (group.type != type) continue
            for (t in 0 until group.length) {
                val format = group.getTrackFormat(t)
                val base = format.label ?: format.language ?: "Track ${t + 1}"
                out += TrackDesc(trackId(g, t), base)
            }
        }
        return out
    }

    override fun getAudioTracks(): List<TrackDesc> = collectTracks(C.TRACK_TYPE_AUDIO)

    override fun getSubtitleTracks(): List<TrackDesc> = collectTracks(C.TRACK_TYPE_TEXT)

    override fun selectAudioTrack(id: Int) = selectTrack(C.TRACK_TYPE_AUDIO, id)

    override fun selectSubtitleTrack(id: Int) = selectTrack(C.TRACK_TYPE_TEXT, id)

    private fun selectTrack(type: Int, id: Int) {
        val params = exo.trackSelectionParameters.buildUpon()
        val override = if (id >= 0) {
            val g = id shr 16
            val t = id and 0xFFFF
            val group = exo.currentTracks.groups.getOrNull(g)
            if (group != null && t < group.length) {
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, t)
            } else null
        } else null
        params.setTrackTypeDisabled(type, id < 0)
        if (override != null) params.setOverrideForType(override)
        exo.trackSelectionParameters = params.build()
    }

    override fun release() {
        exo.release()
    }

    override fun describe(): String = "ExoPlayer · ${decoder.describe()}"
}
