package com.xplayer.ui.player

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.provider.Settings
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xplayer.data.model.DecoderMode
import com.xplayer.data.model.PlayOrientation
import com.xplayer.data.repository.SettingsRepository
import com.xplayer.data.repository.VideoRepository
import com.xplayer.player.PlayRequest
import com.xplayer.player.PlayerEngine
import com.xplayer.player.PlayerEngines
import com.xplayer.player.PlayerListener
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val videoPath: String = "",
    val title: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    /** 系统媒体音量百分比 0..100 (进入页面时读取). */
    val volume: Float = 100f,
    /** 窗口亮度 0..1; <0 表示用户尚未调整, 沿用系统亮度 (含自动亮度). */
    val brightness: Float = -1f,
    /** true: 上下滑动切视频; false: 左半屏亮度 / 右半屏音量. */
    val swipeSwitchVideo: Boolean = false,
    /** 亮度 / 音量调节时的临时提示 (如 "亮度 60%"). */
    val indicator: String? = null,
    val speed: Float = 1.0f,
    val isLocked: Boolean = false,
    val showControls: Boolean = true,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val selectedAudioTrack: Int = -1,
    val selectedSubTrack: Int = -1,
    val orientation: PlayOrientation = PlayOrientation.DEFAULT,
    val resolvedOrientation: PlayOrientation = PlayOrientation.PORTRAIT,
    val isCompleted: Boolean = false,
    val message: String? = null,
    /** 视频显示宽高比 (宽/高, 已计入旋转与像素宽高比); <=0 表示未知, 此时 Surface 铺满. */
    val videoAspectRatio: Float = 0f,
)

data class TrackInfo(val id: Int, val name: String)

/** 无操作多久后自动隐藏播放控制 (进入全屏播放). */
private const val CONTROLS_HIDE_DELAY_MS = 2_000L

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val videoPath: String = checkNotNull(savedStateHandle["videoPath"])

    /** 当前正在播放的视频 (上下滑动切换后与 [videoPath] 不同). */
    private var currentPath: String = videoPath

    private var continuousPlay = true
    private var indicatorJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 同文件夹的视频列表 (顺序与文件夹页一致), 网络流 / 未入库的视频为空 → 不支持切换. */
    private var playlist: List<com.xplayer.data.model.VideoFile> = emptyList()
    private var playlistIndex = -1

    private val _state = MutableStateFlow(PlayerUiState(videoPath = videoPath))
    val uiState: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 播放内核 (ExoPlayer + NextLib). 解码方式在进入播放页时读一次设置确定. */
    private var engine: PlayerEngine? = null
    private var progressJob: Job? = null
    private var hideControlsJob: Job? = null
    private var surface: Surface? = null

    init {
        scheduleHideControls()
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val resolved = resolveOrientation(settings.defaultOrientation, videoPath)
            continuousPlay = settings.continuousPlay
            _state.update {
                it.copy(
                    swipeSwitchVideo = settings.swipeSwitchVideo,
                    volume = currentVolumePercent(),
                    orientation = settings.defaultOrientation,
                    resolvedOrientation = resolved,
                    title = videoPath.substringAfterLast("/"),
                )
            }
            // 用当前解码设置创建内核 (ExoPlayer + NextLib).
            engine = PlayerEngines.create(settings.decoderMode, context).also { e ->
                e.listener = engineListener
            }
            // Surface 可能早于 init 就绪, 若已挂上则立即开播.
            surface?.let { attachAndOpen(it) }
            loadPlaylist()
        }
    }

    private suspend fun loadPlaylist() {
        if (isNetworkUrl(videoPath)) return
        val current = videoRepository.getVideoByPath(videoPath) ?: return
        val list = videoRepository.getVideosInFolder(current.folderPath).first()
        val idx = list.indexOfFirst { it.path == videoPath }
        if (idx >= 0) {
            playlist = list
            playlistIndex = idx
        }
    }

    /** 上滑: 下一个视频. */
    fun playNext() = switchBy(+1)

    /** 下滑: 上一个视频. */
    fun playPrevious() = switchBy(-1)

    private fun switchBy(delta: Int) {
        if (playlist.size <= 1) {
            _state.update { it.copy(message = "没有其他视频可切换") }
            return
        }
        val target = playlistIndex + delta
        if (target !in playlist.indices) {
            _state.update { it.copy(message = if (delta > 0) "已经是最后一个视频" else "已经是第一个视频") }
            return
        }
        switchTo(target)
    }

    private fun switchTo(index: Int) {
        val e = engine ?: return
        val video = playlist[index]
        playlistIndex = index
        currentPath = video.path
        progressJob?.cancel()
        _state.update {
            it.copy(
                videoPath = video.path,
                title = video.path.substringAfterLast("/"),
                position = 0L,
                duration = 0L,
                isCompleted = false,
                isBuffering = true,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrack = -1,
                selectedSubTrack = -1,
                message = null,
            )
        }
        e.open(PlayRequest(url = Uri.fromFile(java.io.File(video.path)).toString()))
        e.play()
        viewModelScope.launch {
            // 方向设为「默认」时, 按新视频宽高重新决定横/竖屏.
            val settings = settingsRepository.settingsFlow.first()
            val resolved = withContext(Dispatchers.IO) { resolveOrientation(settings.defaultOrientation, video.path) }
            if (currentPath == video.path) _state.update { it.copy(resolvedOrientation = resolved) }
        }
    }

    private val engineListener = object : PlayerListener {
        override fun onReady() {
            startProgressTracking()
            updateTracks()
        }

        override fun onPlayingChanged(playing: Boolean) {
            // 暂停时控制栏常驻; 恢复播放后重新计时自动隐藏.
            _state.update { it.copy(isPlaying = playing, showControls = if (!playing) true else it.showControls) }
            if (playing) scheduleHideControls() else hideControlsJob?.cancel()
        }

        override fun onBuffering(buffering: Boolean) {
            _state.update { it.copy(isBuffering = buffering) }
        }

        override fun onEnded() {
            _state.update { it.copy(isPlaying = false, isCompleted = true) }
            progressJob?.cancel()
            // 连续播放: 播完自动播同文件夹下一个; 最后一个则停在结束状态.
            if (continuousPlay && playlistIndex >= 0 && playlistIndex + 1 in playlist.indices) {
                switchTo(playlistIndex + 1)
            }
        }

        override fun onError(code: Int, message: String) {
            _state.update { it.copy(isBuffering = false, message = message) }
        }

        override fun onNotice(message: String) {
            _state.update { it.copy(message = message) }
        }

        override fun onVideoSize(width: Int, height: Int, pixelRatio: Float, rotationDegrees: Int) {
            if (width <= 0 || height <= 0) return
            // 90/270 旋转时宽高互换
            val swapped = rotationDegrees == 90 || rotationDegrees == 270
            val w = if (swapped) height else width
            val h = if (swapped) width else height
            val ratio = if (pixelRatio > 0f) pixelRatio else 1f
            val aspect = (w.toFloat() * ratio) / h.toFloat()
            _state.update { it.copy(videoAspectRatio = aspect) }
        }
    }

    /** 由 PlayerSurface 的 surfaceCreated 回调调用. */
    fun onSurfaceCreated(holderSurface: Surface) {
        surface = holderSurface
        if (engine != null) {
            attachAndOpen(holderSurface)
        } else {
            // 等待 init 中 engine 创建完成 (正常 < 200ms).
            viewModelScope.launch {
                var waited = 0
                while (engine == null && waited < 3000) {
                    delay(20)
                    waited += 20
                }
                engine?.let { attachAndOpen(holderSurface) }
            }
        }
    }

    fun onSurfaceDestroyed() {
        surface = null
        engine?.setSurface(null)
    }

    /**
     * 离开播放页时立即调用: 停止播放并摘除渲染表面.
     * 不能等 onCleared —— 它要等导航退出动画结束后才触发, 期间 SurfaceView 会一直显示最后画面.
     */
    fun stopAndDetach() {
        progressJob?.cancel()
        hideControlsJob?.cancel()
        surface = null
        engine?.let {
            it.pause()
            it.setSurface(null)
            it.stop()
        }
    }

    private fun attachAndOpen(s: Surface) {
        val e = engine ?: return
        e.setSurface(s)
        // 媒体项尚未打开时才 open (currentPosition/duration 都是 0).
        if (e.duration <= 0L && e.currentPosition <= 0L) {
            val mediaItem = if (isNetworkUrl(currentPath)) currentPath
            else Uri.fromFile(java.io.File(currentPath)).toString()
            e.open(PlayRequest(url = mediaItem))
        }
        e.play()
    }

    private fun isNetworkUrl(path: String): Boolean =
        path.startsWith("http://") || path.startsWith("https://") ||
            path.startsWith("rtsp://") || path.startsWith("rtmp://") ||
            path.startsWith("mms://") || path.startsWith("ftp://")

    private fun resolveOrientation(orientation: PlayOrientation, path: String): PlayOrientation {
        return when (orientation) {
            PlayOrientation.DEFAULT -> {
                val (w, h) = getVideoDimensions(path)
                if (w > 0 && h > 0 && w > h) PlayOrientation.LANDSCAPE else PlayOrientation.PORTRAIT
            }
            else -> orientation
        }
    }

    private fun getVideoDimensions(path: String): Pair<Int, Int> {
        return try {
            val retriever = MediaMetadataRetriever()
            if (isNetworkUrl(path)) retriever.setDataSource(path, emptyMap())
            else retriever.setDataSource(path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            retriever.release()
            Pair(w, h)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                val e = engine ?: break
                _state.update { it.copy(position = e.currentPosition, duration = e.duration) }
                delay(500L)
            }
        }
    }

    private fun updateTracks() {
        val e = engine ?: return
        val audio = e.getAudioTracks().map { TrackInfo(it.id, it.name) }
        val subs = mutableListOf<TrackInfo>(TrackInfo(-1, "无字幕"))
        subs += e.getSubtitleTracks().map { TrackInfo(it.id, it.name) }
        _state.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = subs,
                selectedAudioTrack = audio.firstOrNull()?.id ?: -1,
                selectedSubTrack = -1,
            )
        }
    }

    // ── Controls ────────────────────────────────────────────────────────────

    fun togglePlayPause() {
        val e = engine ?: return
        if (e.isPlaying) e.pause() else e.play()
        scheduleHideControls()
    }

    fun seekTo(ms: Long) {
        engine?.seekTo(ms)
        _state.update { it.copy(position = ms) }
        scheduleHideControls()
    }

    fun seekRelative(deltaMs: Long) {
        val newPos = (_state.value.position + deltaMs).coerceIn(0L, _state.value.duration)
        seekTo(newPos)
    }

    private fun currentVolumePercent(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max
    }

    /** [fraction]: 滑动距离占屏幕高度的比例, 向上为正; 整屏高度 = 100%. */
    fun adjustVolume(fraction: Float) {
        val pct = (_state.value.volume + fraction * 100f).coerceIn(0f, 100f)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(pct * max / 100f), 0)
        _state.update { it.copy(volume = pct) }
        showIndicator("音量 ${pct.toInt()}%")
    }

    /** [fraction]: 同 [adjustVolume]. 实际亮度由 PlayerScreen 写入窗口. */
    fun adjustBrightness(fraction: Float) {
        val base = _state.value.brightness.takeIf { it >= 0f } ?: systemBrightness()
        val value = (base + fraction).coerceIn(0.01f, 1f)
        _state.update { it.copy(brightness = value) }
        showIndicator("亮度 ${(value * 100).toInt()}%")
    }

    private fun systemBrightness(): Float {
        val raw = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
        return raw.coerceIn(0.01f, 1f)
    }

    private fun showIndicator(text: String) {
        _state.update { it.copy(indicator = text) }
        indicatorJob?.cancel()
        indicatorJob = viewModelScope.launch {
            delay(800L)
            _state.update { it.copy(indicator = null) }
        }
    }

    fun setSpeed(speed: Float) {
        engine?.setSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun toggleLock() { _state.update { it.copy(isLocked = !it.isLocked) } }

    fun showControls() {
        _state.update { it.copy(showControls = true) }
        scheduleHideControls()
    }

    fun toggleControls() {
        // 暂停时控制栏常驻, 点击不隐藏
        if (!_state.value.isPlaying) {
            _state.update { it.copy(showControls = true) }
            return
        }
        val showing = _state.value.showControls
        _state.update { it.copy(showControls = !showing) }
        if (!showing) scheduleHideControls()
        else hideControlsJob?.cancel()
    }

    /** App 进入后台时调用: 暂停播放 (音视频都停), 不自动恢复. */
    fun pause() {
        engine?.pause()
    }

    /** 菜单弹窗打开期间暂停自动隐藏. */
    fun holdControls() { hideControlsJob?.cancel() }

    fun consumeMessage() { _state.update { it.copy(message = null) } }

    private fun scheduleHideControls() {
        hideControlsJob?.cancel()
        hideControlsJob = viewModelScope.launch {
            delay(CONTROLS_HIDE_DELAY_MS)
            // 暂停 / 加载中不隐藏
            if (_state.value.isPlaying) _state.update { it.copy(showControls = false) }
        }
    }

    fun selectAudioTrack(id: Int) {
        engine?.selectAudioTrack(id)
        _state.update { it.copy(selectedAudioTrack = id) }
    }

    fun selectSubtitleTrack(id: Int) {
        engine?.selectSubtitleTrack(id)
        _state.update { it.copy(selectedSubTrack = id) }
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        hideControlsJob?.cancel()
        engine?.release()
        engine = null
    }
}
