package com.rawsmusic.memory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.waveform.RawWaveformCache
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.PowerListCoilArtwork
import com.rawsmusic.core.ui.widget.flow.clearRawFlowMemoryCache
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndexCache
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.ui.songs.PlayerHolder
import java.util.Locale

/** Adapter for the vendor-neutral Fair Runtime Memory broadcast contract. */
object FairRuntimeMemoryManager {
    private const val TAG = "FairRuntimeMemory"
    private const val ACTION_MEMORY_TRIM = "itgsa.intent.action.TRIM"
    private const val ACTION_MEMORY_KILL = "itgsa.intent.action.KILL"
    private const val BUNDLE_COMMON = "common"
    private const val BUNDLE_EXTRA = "extra"
    private const val RESULT_SUCCESS = 0
    private const val RESULT_FAILURE = 1

    private val lock = Any()
    @Volatile private var initialized = false
    private var workerThread: HandlerThread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MEMORY_TRIM && intent.action != ACTION_MEMORY_KILL) return
            handleMemoryNotification(intent)
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val applicationContext = context.applicationContext
            val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            val handler = Handler(thread.looper)
            val filter = IntentFilter().apply {
                addAction(ACTION_MEMORY_TRIM)
                addAction(ACTION_MEMORY_KILL)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(
                    receiver,
                    filter,
                    null,
                    handler,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                applicationContext.registerReceiver(receiver, filter, null, handler)
            }
            workerThread = thread
            initialized = true
            AppLogger.i(TAG, "FAIR_MEMORY initialized actions=$ACTION_MEMORY_TRIM,$ACTION_MEMORY_KILL")
        }
    }

    fun onAndroidTrimMemory(level: Int) {
        val shouldTrim = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (!shouldTrim) return
        val thread = workerThread ?: return
        Handler(thread.looper).post {
            val startedAt = SystemClock.elapsedRealtime()
            trimReconstructableCaches()
            AppLogger.i(
                TAG,
                "FAIR_MEMORY android_trim level=$level elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
        }
    }

    private fun handleMemoryNotification(intent: Intent) {
        val startedAt = SystemClock.elapsedRealtime()
        val common = intent.getBundleExtra(BUNDLE_COMMON)
        if (common == null) {
            AppLogger.w(TAG, "FAIR_MEMORY ignored missing_common")
            return
        }
        // Some vendor builds omit the diagnostic bundle. It is not needed to release memory or
        // acknowledge the request, so treat it as optional while retaining the common contract.
        val extra = intent.getBundleExtra(BUNDLE_EXTRA) ?: Bundle.EMPTY
        val notifyType = common.getInt("notifyType")
        val notifyId = common.getInt("notifyId")
        val reason = common.getString("reason").orEmpty()
        val action = common.getString("action").orEmpty()
        val callback = common.getBinder("callback")
        if (callback == null) {
            AppLogger.w(TAG, "FAIR_MEMORY ignored missing_callback notifyId=$notifyId")
            return
        }
        val isKill = intent.action == ACTION_MEMORY_KILL ||
            action.uppercase(Locale.ROOT).contains("KILL")
        val pssBeforeKb = Debug.getPss()
        var result = RESULT_SUCCESS
        var persisted = !isKill

        try {
            if (isKill) {
                persisted = (PlayerService.currentRuntimeController() ?: PlayerHolder.controller)
                    ?.persistForMemoryTermination() ?: true
                if (!persisted) result = RESULT_FAILURE
            }
            trimReconstructableCaches()
        } catch (error: Throwable) {
            result = RESULT_FAILURE
            AppLogger.e(TAG, "FAIR_MEMORY processing failed action=$action", error)
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val replyExtra = Bundle().apply {
            putString("reply", if (result == RESULT_SUCCESS) "completed" else "failed")
            putLong("elapsedMs", elapsedMs)
            putLong("pssBeforeKb", pssBeforeKb)
            putBoolean("statePersisted", persisted)
        }
        val replied = reply(callback, notifyType, notifyId, result, replyExtra)

        AppLogger.i(
            TAG,
            "FAIR_MEMORY event=${if (isKill) "KILL" else "TRIM"} " +
                "notifyType=$notifyType notifyId=$notifyId reason=$reason " +
                "sourceAction=${intent.action} heap=${extra.getInt("heapAlloc")}/${extra.getInt("heapCapacity")} " +
                "pss=${extra.getInt("pss")}/${extra.getInt("pssLimit")} " +
                "measuredPssKb=$pssBeforeKb persisted=$persisted result=$result " +
                "callback=$replied elapsedMs=$elapsedMs"
        )
    }

    private fun trimReconstructableCaches() {
        PowerListCoilArtwork.trimMemory()
        BitmapProvider.trimMemory()
        RawWaveformCache.clearMemory()
        clearRawFlowMemoryCache()
        RawAlphabetIndexCache.clear()
    }

    private fun reply(
        callback: IBinder,
        notifyType: Int,
        notifyId: Int,
        result: Int,
        extra: Bundle
    ): Boolean {
        if (!callback.isBinderAlive) return false
        val data = Parcel.obtain()
        return try {
            data.writeInt(notifyType)
            data.writeInt(notifyId)
            data.writeInt(result)
            data.writeBundle(extra)
            callback.transact(
                IBinder.FIRST_CALL_TRANSACTION,
                data,
                null,
                IBinder.FLAG_ONEWAY
            )
        } catch (error: Throwable) {
            AppLogger.e(TAG, "FAIR_MEMORY callback failed notifyId=$notifyId", error)
            false
        } finally {
            data.recycle()
        }
    }
}
