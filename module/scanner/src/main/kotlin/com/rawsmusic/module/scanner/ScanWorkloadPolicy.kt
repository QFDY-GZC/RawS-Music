package com.rawsmusic.module.scanner

internal class ScanWorkloadPolicy(private val getPriority: () -> Int, private val setPriority: (Int) -> Unit) {
    fun <T> execute(operation: () -> T): T {
        val original = getPriority()
        setPriority(1)
        return try { operation() } finally { setPriority(original) }
    }
}
