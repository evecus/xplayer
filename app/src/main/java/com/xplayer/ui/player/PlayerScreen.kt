package com.xplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xplayer.data.model.PlayOrientation
import com.xplayer.ui.theme.*
import kotlin.math.abs

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    // Apply orientation setting using resolvedOrientation (never DEFAULT).
    LaunchedEffect(state.resolvedOrientation) {
        activity?.requestedOrientation = when (state.resolvedOrientation) {
            PlayOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayOrientation.PORTRAIT  -> ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
            PlayOrientation.AUTO      -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayOrientation.DEFAULT   -> ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
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
            onBack = onBack,
            onTap = { viewModel.toggleControls() },
            onSeekRelative = { viewModel.seekRelative(it) },
            onSeekTo = { viewModel.seekTo(it) },
            onVolumeChange = { delta ->
                viewModel.setVolume((state.volume + delta).coerceIn(0, 200))
            },
            onBrightnessChange = { delta ->
                viewModel.setBrightness(state.brightness + delta)
            },
            onTogglePlay = { viewModel.togglePlayPause() },
            onToggleLock = { viewModel.toggleLock() },
            onSpeedSelect = { viewModel.setSpeed(it) },
            onAudioTrack = { viewModel.selectAudioTrack(it) },
            onSubTrack = { viewModel.selectSubtitleTrack(it) },
        )

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
    onVolumeChange: (Int) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLock: () -> Unit,
    onSpeedSelect: (Float) -> Unit,
    onAudioTrack: (Int) -> Unit,
    onSubTrack: (Int) -> Unit,
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
                onDoubleTapLeft  = { onSeekRelative(-10_000L) },
                onDoubleTapRight = { onSeekRelative(10_000L) },
                onSwipeLeft  = { dx -> onSeekRelative(-(dx * 300).toLong()) },
                onSwipeRight = { dx -> onSeekRelative((dx * 300).toLong()) },
                onSwipeUpLeft   = { dy -> onBrightnessChange(dy * 0.5f) },
                onSwipeDownLeft = { dy -> onBrightnessChange(dy * 0.5f) },
                onSwipeUpRight   = { dy -> onVolumeChange((dy * 50).toInt()) },
                onSwipeDownRight = { dy -> onVolumeChange((dy * 50).toInt()) },
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
                )
            }
        }
    }
}

@Composable
private fun GestureLayer(
    onTap: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onSwipeLeft: (Float) -> Unit,
    onSwipeRight: (Float) -> Unit,
    onSwipeUpLeft: (Float) -> Unit,
    onSwipeDownLeft: (Float) -> Unit,
    onSwipeUpRight: (Float) -> Unit,
    onSwipeDownRight: (Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2) onDoubleTapLeft()
                            else onDoubleTapRight()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val isLeft = change.position.x < size.width / 2
                        val dx = dragAmount.x / size.width
                        val dy = -dragAmount.y / size.height
                        if (abs(dragAmount.x) > abs(dragAmount.y)) {
                            if (dx < 0) onSwipeLeft(abs(dx)) else onSwipeRight(dx)
                        } else {
                            if (isLeft) {
                                if (dy > 0) onSwipeUpLeft(dy) else onSwipeDownLeft(dy)
                            } else {
                                if (dy > 0) onSwipeUpRight(dy) else onSwipeDownRight(dy)
                            }
                        }
                    }
                }
        )
    }
}

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
) {
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubMenu by remember { mutableStateOf(false) }

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
                .statusBarsPadding()
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
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val progress = if (state.duration > 0) state.position.toFloat() / state.duration else 0f
            Slider(
                value = progress,
                onValueChange = { onSeekTo((it * state.duration).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = GreenPrimary,
                    activeTrackColor = GreenPrimary,
                    inactiveTrackColor = Color.White.copy(0.3f),
                ),
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
