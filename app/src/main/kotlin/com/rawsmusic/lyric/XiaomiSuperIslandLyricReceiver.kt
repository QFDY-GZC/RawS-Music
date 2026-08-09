package com.rawsmusic.lyric

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rawsmusic.core.common.utils.AppLogger

internal class XiaomiSuperIslandLyricReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.d(
            "RawSuperIsland",
            "receiver action=${intent.action} hasLyric=${intent.hasExtra("lineText")}"
        )
        XiaomiSuperIslandLyricRuntime.handle(context, intent)
    }
}
