package com.rawsmusic.lyric

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

internal class XiaomiSuperIslandLyricReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(
            "RawSuperIsland",
            "receiver action=${intent.action} hasLyric=${intent.hasExtra("lineText")}"
        )
        XiaomiSuperIslandLyricRuntime.handle(context, intent)
    }
}
