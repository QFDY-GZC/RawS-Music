package com.rawsmusic.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class PlaybackWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        PlaybackWidgetUpdater.requestUpdate(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        PlaybackWidgetUpdater.requestUpdate(context)
    }

    override fun onEnabled(context: Context) {
        PlaybackWidgetUpdater.requestUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> PlaybackWidgetUpdater.requestUpdate(context)
            ACTION_PROGRESS -> PlaybackWidgetUpdater.requestProgressUpdate(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.rawsmusic.action.REFRESH_PLAYBACK_WIDGET"
        const val ACTION_PROGRESS = "com.rawsmusic.action.PROGRESS_PLAYBACK_WIDGET"
    }
}
