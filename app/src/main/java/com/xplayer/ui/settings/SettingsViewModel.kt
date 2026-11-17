package com.xplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xplayer.data.model.*
import com.xplayer.data.repository.SettingsRepository
import com.xplayer.data.repository.VideoRepository
import com.xplayer.utils.MediaScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val videoRepository: VideoRepository,
    private val mediaScanner: MediaScanner,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _videoCount  = MutableStateFlow(0)
    private val _folderCount = MutableStateFlow(0)
    val videoCount: StateFlow<Int>  = _videoCount.asStateFlow()
    val folderCount: StateFlow<Int> = _folderCount.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val lastScanTime: StateFlow<Long> = settingsRepository.settingsFlow
        .map { it.lastScanTime }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    init {
        viewModelScope.launch {
            _videoCount.value  = videoRepository.getVideoCount()
            _folderCount.value = videoRepository.getFolderCount()
        }
    }

    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            try {
                val result = mediaScanner.scan()
                videoRepository.replaceAll(result.videos, result.folders)
                settingsRepository.updateLastScanTime(System.currentTimeMillis())
                // Refresh counts after scan
                _videoCount.value  = videoRepository.getVideoCount()
                _folderCount.value = videoRepository.getFolderCount()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun setDecoder(mode: DecoderMode) = viewModelScope.launch {
        settingsRepository.updateDecoderMode(mode)
    }
    fun setOrientation(o: PlayOrientation) = viewModelScope.launch {
        settingsRepository.updateOrientation(o)
    }
    fun setShowThumbnail(v: Boolean) = viewModelScope.launch {
        settingsRepository.updateShowThumbnail(v)
    }
    fun setContinuousPlay(v: Boolean) = viewModelScope.launch {
        settingsRepository.updateContinuousPlay(v)
    }
}
