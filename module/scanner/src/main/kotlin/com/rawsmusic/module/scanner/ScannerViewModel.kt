package com.rawsmusic.module.scanner

import android.content.Context
import com.rawsmusic.core.common.base.BaseViewModel
import com.rawsmusic.core.common.utils.PermissionUtils
import com.rawsmusic.core.common.ext.isAtLeastT
import com.rawsmusic.module.data.prefs.AppPreferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScannerViewModel : BaseViewModel() {

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _scanProgress = MutableStateFlow(0 to 0)
    val scanProgress: StateFlow<Pair<Int, Int>> = _scanProgress.asStateFlow()

    fun startScan(context: Context) {
        viewModelScope.launch {
            val hasPermission = if (context.isAtLeastT) {
                PermissionUtils.requestMediaPermission(context)
            } else {
                PermissionUtils.requestStoragePermission(context)
            }

            if (!hasPermission) {
                _scanState.value = ScanState.Error("Permission denied")
                return@launch
            }

            _scanState.value = ScanState.Scanning
            val customPaths = AppPreferences.UI.scanPaths

            ScanManager.startScan(context, customPaths).collect { progress ->
                when (progress) {
                    is ScanProgress.Started -> {
                        _scanProgress.value = 0 to progress.totalEstimated
                    }
                    is ScanProgress.Progress -> {
                        _scanProgress.value = progress.scanned to progress.total
                    }
                    is ScanProgress.Completed -> {
                        _scanState.value = ScanState.Completed(progress.found, progress.timeMs)
                    }
                    is ScanProgress.Error -> {
                        _scanState.value = ScanState.Error(progress.message)
                    }
                }
            }
        }
    }
}

sealed class ScanState {
    object Idle : ScanState()
    object Scanning : ScanState()
    data class Completed(val found: Int, val timeMs: Long) : ScanState()
    data class Error(val message: String) : ScanState()
}
