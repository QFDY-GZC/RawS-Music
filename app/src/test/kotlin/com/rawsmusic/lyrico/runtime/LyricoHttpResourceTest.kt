package com.rawsmusic.lyrico.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import okhttp3.OkHttpClient

class LyricoHttpResourceTest {
    @Test fun successivePluginHostsReuseOnePoolAndDispatcher() {
        val field = QuickJsHostApi::class.java.getDeclaredField("okHttpClient").apply { isAccessible = true }
        val first = field.get(QuickJsHostApi()) as OkHttpClient
        repeat(100) {
            val next = field.get(QuickJsHostApi()) as OkHttpClient
            assertSame(first.connectionPool, next.connectionPool)
            assertSame(first.dispatcher, next.dispatcher)
            assertSame(first.sslSocketFactory, next.sslSocketFactory)
        }
    }
}
