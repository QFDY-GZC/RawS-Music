package com.rawsmusic.module.scanner

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn

object MediaStoreChangeObserver {

    data class Change(
        val uri: Uri?,
        val flags: Int,
        val timestampMs: Long = System.currentTimeMillis()
    )

    @OptIn(FlowPreview::class)
    fun observeAudio(context: Context, debounceMs: Long = 2_500L): Flow<Change> {
        val appContext = context.applicationContext

        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Change(uri = null, flags = 0))
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    trySend(Change(uri = uri, flags = 0))
                }

                override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                    trySend(Change(uri = uris.firstOrNull(), flags = flags))
                }
            }

            appContext.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer
            )

            android.util.Log.d(TAG, "registered")

            awaitClose {
                appContext.contentResolver.unregisterContentObserver(observer)
                android.util.Log.d(TAG, "unregistered")
            }
        }
            .conflate()
            .debounce(debounceMs)
            .flowOn(Dispatchers.IO)
    }

    private const val TAG = "MediaStoreChangeObserver"
}
