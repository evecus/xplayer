package com.xplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xplayer.data.model.PlayOrientation
import com.xplayer.ui.theme.*
import kotlin.math.abs

/** 进度条已播放部分 (青色) 与拖动圆点 (紫色). */
private val ProgressPlayed = Color(0xFF00E5FF)
private val ProgressThumb = Color(0xFF9C4DFF)

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    // 返回前先停播并摘掉 Surface, 避免退出动画期间画面残留在上一页.
    val leave = {
        viewModel.stopAndDetach()
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        onBack()
    }
    BackHandler(onBack = leave)


    // Apply orientation setting using resolvedOrientation (never DEFAULT).
    LaunchedEffect(state.resolvedOrientation) {
        activity?.requestedOrientation = when (state.resolvedOrientation) {
            PlayOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayOrientation.PORTRAIT  -> ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
            PlayOrientation.AUTO      -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayOrientation.DEFAULT   -> ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        }
    }

    // 不支持后台播放: App 进入后台 (按 Home / 切应用 / 锁屏) 即暂停, 回到前台保持暂停.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 亮度: 用户调节后写入窗口亮度, 离开页面时恢复为系统亮度.
    LaunchedEffect(state.brightness) {
        if (state.brightness >= 0f) {
            activity?.window?.let { w ->
                w.attributes = w.attributes.also { it.screenBrightness = state.brightness }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.also {
                    it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // Keep screen on
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 播放页全程隐藏状态栏和底部导航条 (边缘滑动可临时唤出, 随后自动再隐藏);
    // 状态栏图标强制浅色, 退出时恢复.
    val window = activity?.window
    if (window != null) {
        val controller = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
        // 弹窗等造成窗口焦点变化后系统栏可能被带出, 状态变化时重新隐藏.
        LaunchedEffect(state.showControls, state.isLocked, state.isPlaying) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        DisposableEffect(window) {
            val wasLightStatus = controller.isAppearanceLightStatusBars
            val wasLightNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = wasLightStatus
                controller.isAppearanceLightNavigationBars = wasLightNav
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ExoPlayer Surface (按视频宽高比 contain 居中, 不匹配区域留黑)
        PlayerSurface(viewModel = viewModel, aspectRatio = state.videoAspectRatio)

        // Gesture + controls overlay
        PlayerOverlay(
            state = state,
            onBack = leave,
            onTap = { viewModel.toggleControls() },
            onSeekRelative = { viewModel.seekRelative(it) },
            onSeekTo = { viewModel.seekTo(it) },
            onNext = { viewModel.playNext() },
            onPrevious = { viewModel.playPrevious() },
            onVolumeChange = { viewModel.adjustVolume(it) },
            onBrightnessChange = { viewModel.adjustBrightness(it) },
            onTogglePlay = { viewModel.togglePlayPause() },
            onToggleLock = { viewModel.toggleLock() },
            onSpeedSelect = { viewModel.setSpeed(it) },
            onAudioTrack = { viewModel.selectAudioTrack(it) },
            onSubTrack = { viewModel.selectSubtitleTrack(it) },
            onMenuOpenChanged = { open -> if (open) viewModel.holdControls() else viewModel.showControls() },
        )

        // 亮度 / 音量调节提示
        state.indicator?.let { text ->
            Text(
                text = text,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Buffering indicator
        AnimatedVisibility(
            visible = state.isBuffering,
            modifier = Modifier.align(Alignment.Center),
        ) {
            CircularProgressIndicator(color = GreenPrimary, strokeWidth = 3.dp)
        }

        // Notice / error message (解码降级提示 / 播放错误)
        state.message?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(4000L)
                viewModel.consumeMessage()
            }
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = Color.Black.copy(0.85f),
                contentColor = Color.White,
            ) { Text(msg) }
        }
    }
}

@Composable
private fun PlayerSurface(viewModel: PlayerViewModel, aspectRatio: Float) {
    // 外层用黑色铺满, 内层 Surface 按视频比例居中, 未覆盖区域即黑边.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val surfaceModifier = if (aspectRatio > 0f && aspectRatio.isFinite()) {
            val boxAspect = maxWidth / maxHeight
            if (aspectRatio > boxAspect) {
                // 视频更宽: 宽度撑满, 高度按比例收缩 (上下留黑)
                Modifier.width(maxWidth).height(maxWidth / aspectRatio)
            } else {
                // 视频更高: 高度撑满, 宽度按比例收缩 (左右留黑)
                Modifier.height(maxHeight).width(maxHeight * aspectRatio)
            }
        } else {
            // 尺寸未知时先铺满, onVideoSize 到达后会重组为正确比例
            Modifier.fillMaxSize()
        }

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.onSurfaceCreated(holder.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.onSurfaceDestroyed()
                        }
                    })
                }
            },
            modifier = surfaceModifier,
        )
    }
}

@Composable
private fun PlayerOverlay(
    state: PlayerUiState,
    onBack: () -> Unit,
    onTap: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLock: () -> Unit,
    onSpeedSelect: (Float) -> Unit,
    onAudioTrack: (Int) -> Unit,
    onSubTrack: (Int) -> Unit,
    onMenuOpenChanged: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state.isLocked) {
                if (state.isLocked) {
                    detectTapGestures { onTap() }
                }
            }
    ) {
        if (!state.isLocked) {
            GestureLayer(
                onTap = onTap,
                onDoubleTap = onTogglePlay,
                onSwipeLeft  = { dx -> onSeekRelative(-(dx * 300).toLong()) },
                onSwipeRight = { dx -> onSeekRelative((dx * 300).toLong()) },
                switchEnabled = state.swipeSwitchVideo,
                onSwipeUp   = onNext,      // 上滑: 下一个视频 (同抖音)
                onSwipeDown = onPrevious,  // 下滑: 上一个视频
                onVolumeChange = onVolumeChange,          // 开关关闭时: 右半屏上下滑
                onBrightnessChange = onBrightnessChange,  // 开关关闭时: 左半屏上下滑
            )
        }

        AnimatedVisibility(
            visible = state.showControls || state.isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (state.isLocked) {
                Box(Modifier.fillMaxSize()) {
                    LockButton(
                        isLocked = true,
                        onClick = onToggleLock,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                    )
                }
            } else {
                PlayerControls(
                    state = state,
                    onBack = onBack,
                    onTogglePlay = onTogglePlay,
                    onToggleLock = onToggleLock,
                    onSeekTo = onSeekTo,
                    onSpeedSelect = onSpeedSelect,
                    onAudioTrack = onAudioTrack,
                    onSubTrack = onSubTrack,
                    onMenuOpenChanged = onMenuOpenChanged,
                )
            }
        }
    }
}

@Composable
private fun GestureLayer(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onSwipeLeft: (Float) -> Unit,
    onSwipeRight: (Float) -> Unit,
    switchEnabled: Boolean,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { onDoubleTap() }
                    )
                }
                .pointerInput(switchEnabled) {
                    // 一次拖动只认一个方向: 横向 = 快进/快退;
                    // 纵向 = 开关开启时松手切换视频, 关闭时左半屏调亮度 / 右半屏调音量.
                    var horizontal: Boolean? = null
                    var totalY = 0f
                    var startLeft = true
                    val switchThreshold = size.height * 0.12f
                    detectDragGestures(
                        onDragStart = { offset ->
                            horizontal = null
                            totalY = 0f
                            startLeft = offset.x < size.width / 2f
                        },
                        onDragEnd = {
                            if (switchEnabled && horizontal == false) {
                                if (totalY <= -switchThreshold) onSwipeUp()
                                else if (totalY >= switchThreshold) onSwipeDown()
                            }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        if (horizontal == null) horizontal = abs(dragAmount.x) > abs(dragAmount.y)
                        if (horizontal == true) {
                            val dx = dragAmount.x / size.width
                            if (dx < 0) onSwipeLeft(abs(dx)) else onSwipeRight(dx)
                        } else if (switchEnabled) {
                            totalY += dragAmount.y
                        } else {
                            val fraction = -dragAmount.y / size.height
                            if (startLeft) onBrightnessChange(fraction) else onVolumeChange(fraction)
                        }
                    }
                }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControls(
    state: PlayerUiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLock: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedSelect: (Float) -> Unit,
    onAudioTrack: (Int) -> Unit,
    onSubTrack: (Int) -> Unit,
    onMenuOpenChanged: (Boolean) -> Unit,
) {
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubMenu by remember { mutableStateOf(false) }

    // 弹窗打开时暂停自动隐藏, 关闭后重新计时.
    val anyMenuOpen = showSpeedMenu || showAudioMenu || showSubMenu
    LaunchedEffect(anyMenuOpen) { onMenuOpenChanged(anyMenuOpen) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(0.6f),
                    0.3f to Color.Transparent,
                    0.7f to Color.Transparent,
                    1f to Color.Black.copy(0.7f),
                )
            )
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回", tint = Color.White)
            }
            Text(
                text = state.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showSpeedMenu = true }) {
                Text("${state.speed}x", color = Color.White, fontSize = 13.sp)
            }
            if (state.audioTracks.isNotEmpty()) {
                IconButton(onClick = { showAudioMenu = true }) {
                    Icon(Icons.Outlined.AudioFile, contentDescription = "音轨", tint = Color.White)
                }
            }
            if (state.subtitleTracks.size > 1) {
                IconButton(onClick = { showSubMenu = true }) {
                    Icon(Icons.Outlined.ClosedCaption, contentDescription = "字幕", tint = Color.White)
                }
            }
            LockButton(isLocked = false, onClick = onToggleLock)
        }

        Spacer(Modifier.weight(1f))

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val progress = if (state.duration > 0) state.position.toFloat() / state.duration else 0f
            // 细线进度条: 白色底线, 已播放部分青色, 拖动点为小紫点.
            Slider(
                value = progress,
                onValueChange = { onSeekTo((it * state.duration).toLong()) },
                thumb = {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(ProgressThumb, CircleShape)
                    )
                },
                track = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Color.White, RoundedCornerShape(1.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(ProgressPlayed, RoundedCornerShape(1.dp))
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatTime(state.position), color = Color.White.copy(0.8f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onSeekTo((state.position - 10_000L).coerceAtLeast(0L)) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "-10s", tint = Color.White)
                }
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(48.dp)
                        .background(GreenPrimary, CircleShape),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = { onSeekTo((state.position + 10_000L).coerceAtMost(state.duration)) }) {
                    Icon(Icons.Filled.Forward10, contentDescription = "+10s", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(formatTime(state.duration), color = Color.White.copy(0.8f), fontSize = 12.sp)
            }
        }
    }

    if (showSpeedMenu) {
        SpeedMenu(
            current = state.speed,
            onSelect = { onSpeedSelect(it); showSpeedMenu = false },
            onDismiss = { showSpeedMenu = false },
        )
    }

    if (showAudioMenu && state.audioTracks.isNotEmpty()) {
        TrackMenu(
            title = "音频轨道",
            tracks = state.audioTracks,
            selectedId = state.selectedAudioTrack,
            onSelect = { onAudioTrack(it); showAudioMenu = false },
            onDismiss = { showAudioMenu = false },
        )
    }

    if (showSubMenu) {
        TrackMenu(
            title = "字幕",
            tracks = state.subtitleTracks,
            selectedId = state.selectedSubTrack,
            onSelect = { onSubTrack(it); showSubMenu = false },
            onDismiss = { showSubMenu = false },
        )
    }
}

@Composable
private fun LockButton(isLocked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .background(Color.Black.copy(0.4f), RoundedCornerShape(8.dp)),
    ) {
        Icon(
            if (isLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
            contentDescription = if (isLocked) "解锁" else "锁定",
            tint = Color.White,
        )
    }
}

@Composable
private fun SpeedMenu(current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放速度") },
        text = {
            Column {
                speeds.forEach { speed ->
                    ListItem(
                        headlineContent = { Text("${speed}x") },
                        trailingContent = {
                            if (speed == current) {
                                Icon(Icons.Filled.Check, null, tint = GreenPrimary)
                            }
                        },
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures { onSelect(speed) }
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TrackMenu(
    title: String,
    tracks: List<TrackInfo>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                tracks.forEach { track ->
                    ListItem(
                        headlineContent = { Text(track.name.ifBlank { "Track ${track.id}" }) },
                        trailingContent = {
                            if (track.id == selectedId) {
                                Icon(Icons.Filled.Check, null, tint = GreenPrimary)
                            }
                        },
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures { onSelect(track.id) }
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
