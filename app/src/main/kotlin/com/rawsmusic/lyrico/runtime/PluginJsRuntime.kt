package com.rawsmusic.lyrico.runtime

interface PluginJsRuntime : AutoCloseable {
    fun eval(script: String, filename: String = "<eval>"): String
    fun call(functionName: String, requestJson: String): String
}
