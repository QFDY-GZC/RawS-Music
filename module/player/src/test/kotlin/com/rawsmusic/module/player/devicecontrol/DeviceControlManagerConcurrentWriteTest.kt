package com.rawsmusic.module.player.devicecontrol

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlManagerConcurrentWriteTest {
    @Test
    fun `manager does not serialize away per-control conflation`() = runBlocking {
        val device = DeviceControlDevice("usb:1:2", "fixture", DeviceConnectionKind.USB, 1, 2)
        lateinit var session: ConflatingSession
        val backend = object : DeviceControlBackend {
            override suspend fun open(device: DeviceControlDevice, generation: Long): DeviceControlSession =
                ConflatingSession(device, generation, this@runBlocking).also { session = it }
        }
        val manager = DeviceControlManager(this, listOf(backend))
        manager.bind(device)

        coroutineScope {
            launch {
                assertTrue(
                    manager.write(request(1.0)) is DeviceControlWriteResult.Applied,
                )
            }
            delay(5L)
            launch {
                assertTrue(
                    manager.write(request(2.0)) is DeviceControlWriteResult.Applied,
                )
            }
        }

        assertEquals(listOf(2.0), session.actualWrites.toList())
        manager.close()
    }

    private fun request(value: Double) = DeviceControlWriteRequest(
        DeviceControlId(CONTROL_ID),
        DeviceControlWriteValue.Number(value),
    )

    private class ConflatingSession(
        override val device: DeviceControlDevice,
        override val generation: Long,
        scope: CoroutineScope,
    ) : DeviceControlSession {
        val actualWrites = CopyOnWriteArrayList<Double>()
        private val state = MutableStateFlow(readySnapshot())
        override val snapshot: StateFlow<DeviceControlSnapshot> = state
        private val writer = SerializedDeviceControlWriter(
            scope = scope,
            generation = generation,
            currentGeneration = { generation },
            coalesceMs = 40L,
            execute = { request ->
                actualWrites += (request.value as DeviceControlWriteValue.Number).value
                delay(10L)
                DeviceControlWriteResult.Applied(state.value)
            },
        )

        override suspend fun probe() = Unit
        override suspend fun refresh() = Unit
        override suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult = writer.submit(request)
        override suspend fun close() = writer.close()

        private fun readySnapshot() = DeviceControlSnapshot(
            generation = generation,
            device = device,
            capabilities = listOf(
                DeviceControlCapability.Range(
                    id = DeviceControlId(CONTROL_ID),
                    label = "EQ",
                    control = DeviceNumericControl(
                        id = DeviceControlId(CONTROL_ID),
                        label = "EQ",
                        access = DeviceControlAccess.READ_WRITE,
                        current = 0.0,
                        ranges = listOf(DeviceNumericRange(-12.0, 12.0, 0.5)),
                        address = DeviceControlBackendAddress.UsbVendor("fixture", "eq"),
                    ),
                ),
            ),
            probeState = DeviceControlSnapshot.ProbeState.READY,
        )
    }

    private companion object {
        const val CONTROL_ID = "vendor:eq:0"
    }
}
