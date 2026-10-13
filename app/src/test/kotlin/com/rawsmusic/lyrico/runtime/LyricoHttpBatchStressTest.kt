package com.rawsmusic.lyrico.runtime

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class LyricoHttpBatchStressTest {
    @Test(timeout = 60000) fun twoThousandPluginLifetimesDoNotAccumulateConnections() {
        val server = ServerSocket(0)
        val accepted = AtomicInteger()
        val served = AtomicInteger()
        val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val workers = Executors.newFixedThreadPool(4)
        val listener = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = server.accept().apply { tcpNoDelay = true }
                    sockets.add(socket)
                    accepted.incrementAndGet()
                    workers.submit {
                        socket.use {
                            try {
                                val reader = socket.getInputStream().bufferedReader()
                                val output = socket.getOutputStream()
                                while (reader.readLine() != null) {
                                    while (!reader.readLine().isNullOrEmpty()) { }
                                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok".toByteArray())
                                    output.flush()
                                    served.incrementAndGet()
                                }
                            } catch (_: java.io.IOException) { }
                        }
                    }
                }
            } catch (_: java.io.IOException) { }
        }
        try {
            val payload = "{\"url\":\"http://127.0.0.1:${server.localPort}/lyric\"}"
            repeat(2000) {
                assertTrue(QuickJsHostApi().call("http.getText", payload).contains("\"value\":\"ok\""))
                assertTrue("connection count must not follow song count", accepted.get() <= 4)
            }
            assertEquals(2000, served.get())
            assertEquals(1, accepted.get())
        } finally {
            server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
            workers.shutdownNow()
            listener.join(1000)
        }
    }

    @Test(timeout = 15000) fun concurrentCallsHaveAFixedCapacityIndependentOfBatchLength() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val executor = Executors.newFixedThreadPool(12)
        try {
            val tasks = (0 until 600).map {
                executor.submit {
                    LyricoHttpTransport.withPermit {
                        val count = active.incrementAndGet()
                        peak.accumulateAndGet(count, ::maxOf)
                        try { Thread.sleep(1) } finally { active.decrementAndGet() }
                    }
                }
            }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(peak.get() <= 4)
            assertEquals(0, active.get())
        } finally { executor.shutdownNow() }
    }
}
