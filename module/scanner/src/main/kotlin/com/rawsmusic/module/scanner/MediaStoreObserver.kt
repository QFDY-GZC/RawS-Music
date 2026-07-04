package com.rawsmusic.module.scanner

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.rawsmusic.core.common.utils.AppLogger

/**
 * MediaStore 内容变化监听器。
 *
 * 监听 MediaStore.Audio.Media 的变化，
 * 通过 ScanScheduler 去抖调度扫描。
 */
class MediaStoreObserver(
    private val context: Context
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        private const val TAG = "MediaStoreObserver"

        val AUDIO_URI: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private var registered = false

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        AppLogger.d(TAG, "onChange: uri=$uri")
        ScanScheduler.onContentChanged(context)
    }

    /**
     * 注册监听器
     */
    fun register() {
        if (registered) return
        try {
            context.contentResolver.registerContentObserver(
                AUDIO_URI,
                true, // notifyForDescendants
                this
            )
            registered = true
            AppLogger.d(TAG, "Registered for $AUDIO_URI")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register", e)
        }
    }

    /**
     * 注销监听器
     */
    fun unregister() {
        if (!registered) return
        try {
            context.contentResolver.unregisterContentObserver(this)
            registered = false
            AppLogger.d(TAG, "Unregistered")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unregister", e)
        }
    }
}
