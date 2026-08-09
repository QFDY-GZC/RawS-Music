package com.rawsmusic.core.common

import android.app.Application
import com.tencent.mmkv.MMKV

object CoreInit {

    private var app: Application? = null

    fun init(application: Application) {
        app = application
        MMKV.initialize(application)
    }

    fun getApp(): Application {
        return app ?: throw IllegalStateException("CoreInit not initialized. Call CoreInit.init(application) in your Application.onCreate()")
    }
}
