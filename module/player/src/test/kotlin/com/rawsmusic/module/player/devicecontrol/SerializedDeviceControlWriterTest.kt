package com.rawsmusic.module.player.devicecontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val executed = mutableListOf<String>()
    val writer = SerializedDeviceControlWriter(
        scope = scope,
        generation = 7,
        currentGeneration = { 7 },
        coalesceMs = 20,
        execute = { request ->
            synchronized(executed) { executed += "${request.controlId.value}=${(request.value as DeviceControlWriteValue.Number).value}" }
            DeviceControlWriteResult.Applied(DeviceControlSnapshot(7, null))
        },
    )
    val a1 = async { writer.submit(DeviceControlWriteRequest(DeviceControlId("a"), DeviceControlWriteValue.Number(1.0))) }
    val a2 = async { writer.submit(DeviceControlWriteRequest(DeviceControlId("a"), DeviceControlWriteValue.Number(2.0))) }
    val b1 = async { writer.submit(DeviceControlWriteRequest(DeviceControlId("b"), DeviceControlWriteValue.Number(3.0))) }
    check(a1.await() is DeviceControlWriteResult.Applied)
    check(a2.await() is DeviceControlWriteResult.Applied)
    check(b1.await() is DeviceControlWriteResult.Applied)
    check(executed == listOf("a=2.0", "b=3.0")) { "executed=$executed" }

    val slow = SerializedDeviceControlWriter(
        scope = scope,
        generation = 7,
        currentGeneration = { 7 },
        coalesceMs = 500,
        execute = { DeviceControlWriteResult.Applied(DeviceControlSnapshot(7, null)) },
    )
    val pending = async { slow.submit(DeviceControlWriteRequest(DeviceControlId("x"), DeviceControlWriteValue.Number(1.0))) }
    delay(10)
    slow.close()
    check(pending.await() is DeviceControlWriteResult.Rejected)
    writer.close()
    scope.cancel()
    println("SerializedDeviceControlWriterTest: OK")
}
