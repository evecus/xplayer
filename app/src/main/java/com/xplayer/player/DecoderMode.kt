package com.xplayer.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.xplayer.data.model.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode as NextDecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * 每个 ExoPlayer 一个: 基于 NextLib 的 [NextRenderersFactory] + [DecoderManager]
 * 管理 FFmpeg/dav1d 扩展渲染器与 MediaCodec 的选择和运行时切换.
 *
 * 三档设置 (来自 XPlayer 设置页 DataStore, 移植自 ubox) 映射到 NextLib:
 *   AUTO     → NextDecoderMode.AUTO      (硬 MediaCodec → 软 MediaCodec → FFmpeg 自动兜底);
 *   HARDWARE → NextDecoderMode.HARDWARE  (只用硬 MediaCodec);
 *   SOFTWARE → NextDecoderMode.FFMPEG    (只用 FFmpeg/dav1d 渲染器).
 * 音频固定 AUTO, 系统不支持的音频编码 (AC3/EAC3/DTS/TrueHD...) 由 FFmpeg 渲染器接管.
 *
 * 自动模式的运行时升级链 (NextLib 不自动恢复运行时解码失败, 由这里接管):
 *   AUTO → 强制软 MediaCodec → 强制 FFmpeg → 报错.
 */
@OptIn(UnstableApi::class)
class DecoderController(
    private val context: Context,
    private val mode: DecoderMode,
    private val onNotice: (String) -> Unit,
) {

    /** 自动模式升级阶段: 0=自动链 1=已强制软 MediaCodec 2=已强制 FFmpeg. */
    @Volatile
    private var escalation = 0

    /** NextLib 的解码器管理器 (视频/音频模式独立切换). */
    private val manager = DecoderManager()

    /** 创建按解码方式配置好的 ExoPlayer. 必须在主线程调用. */
    fun createPlayer(): ExoPlayer {
        val renderers = NextRenderersFactory(context)
            .setDecoderManager(manager)
            // 同类解码器列表内初始化失败时依次尝试下一个 (例如多个硬件解码器)
            .setEnableDecoderFallback(true)
        // DefaultDataSource 按 scheme 分发: file / content / asset / http / https,
        // 本地视频与网络流共用; media3-exoplayer-hls 已在依赖中, m3u8 自动识别.
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        manager.attach(player)
        manager.selectVideoDecoder(targetMode())
        manager.selectAudioDecoder(NextDecoderMode.AUTO)
        return player
    }

    /** 当前设置对应的视频解码模式 (含自动模式的升级阶段). */
    private fun targetMode(): NextDecoderMode = when (mode) {
        DecoderMode.HARDWARE -> NextDecoderMode.HARDWARE
        DecoderMode.SOFTWARE -> NextDecoderMode.FFMPEG
        DecoderMode.AUTO -> when (escalation) {
            1 -> NextDecoderMode.SOFTWARE
            2 -> NextDecoderMode.FFMPEG
            else -> NextDecoderMode.AUTO
        }
    }

    /** 即将播放新的媒体项: 自动模式重新从硬解开始. */
    fun newItem() {
        escalation = 0
        manager.selectVideoDecoder(targetMode())
    }

    /** 给错误提示用: 当前实际使用的解码方式. */
    fun describe(): String = when (mode) {
        DecoderMode.HARDWARE -> "硬解 (无回退)"
        DecoderMode.SOFTWARE -> "FFmpeg 软解 (无回退)"
        DecoderMode.AUTO -> when (escalation) {
            1 -> "自动 (已切换软 MediaCodec)"
            2 -> "自动 (已切换 FFmpeg)"
            else -> "自动 (硬解优先)"
        }
    }

    private fun isDecoderError(e: PlaybackException): Boolean = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> false
    }

    /** 自动模式且尚未降到底: 视频解码器强制降一级并从当前位置重新准备. */
    private fun escalateAndRetry(player: ExoPlayer): Boolean {
        if (mode != DecoderMode.AUTO || escalation >= 2) return false
        escalation++
        val target = targetMode()
        val live = player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET
        val pos = player.currentPosition
        manager.selectVideoDecoder(target)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (!live) player.seekTo(pos) else player.seekToDefaultPosition()
        player.playWhenReady = true
        onNotice(if (escalation == 1) "硬解失败, 已切换软解" else "软解失败, 已切换 FFmpeg 解码")
        return true
    }

    /**
     * 播放器报错时调用. 返回 true = 解码器相关错误且已在自动模式下降级重试.
     */
    fun onPlayerError(player: ExoPlayer, error: PlaybackException): Boolean {
        if (!isDecoderError(error)) return false
        return escalateAndRetry(player)
    }

    enum class TracksCheck { OK, UNSUPPORTED }

    /** 启用的渲染器无一支持视频轨 (静默丢弃只播声音) → 按失败处理. */
    fun onTracks(tracks: Tracks): TracksCheck {
        val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        if (!hasVideo || tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)) return TracksCheck.OK
        return TracksCheck.UNSUPPORTED
    }

    fun unsupportedMessage(): String = "当前设备没有可用于该视频的解码器\n解码方式: ${describe()}"
}
