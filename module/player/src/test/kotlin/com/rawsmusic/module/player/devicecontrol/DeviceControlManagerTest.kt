package com.rawsmusic.module.player.devicecontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlManagerTest {
    @Test
    fun `manager merges standard and vendor capabilities and routes writes to one owner`() = runBlocking {
        val device = DeviceControlDevice("usb:1:2", "fixture", DeviceConnectionKind.USB, 1, 2)
        val standard = fakeBackend("standard", DeviceControlBackendAddress.UsbAudioClass(1, 5, 2, 0))
        val vendor = fakeBackend("vendor", DeviceControlBackendAddress.UsbVendor("fixture", "eq.0"))
        val manager = DeviceControlManager(this, listOf(standard, vendor))
        manager.bind(device)
        assertEquals(2, manager.snapshot.value.capabilities.size)
        val result = manager.write(
            DeviceControlWriteRequest(DeviceControlId("vendor"), DeviceControlWriteValue.Number(1.0)),
        )
        assertTrue(result is DeviceControlWriteResult.Applied)
        assertEquals(0, standard.session!!.writes)
        assertEquals(1, vendor.session!!.writes)
        manager.close()
    }


    @Test
    fun `blocking backend work never inherits manager caller thread`() = runBlocking {
        val callerThread = Thread.currentThread().id
        val device = DeviceControlDevice("usb:1:2", "fixture", DeviceConnectionKind.USB, 1, 2)
        val observedThreads = java.util.concurrent.CopyOnWriteArrayList<Long>()
        lateinit var session: FakeSession
        val backend = object : DeviceControlBackend {
            override suspend fun open(device: DeviceControlDevice, generation: Long): DeviceControlSession {
                observedThreads += Thread.currentThread().id
                return FakeSession(
                    device,
                    generation,
                    "vendor",
                    DeviceControlBackendAddress.UsbVendor("fixture", "eq.0"),
                    onProbe = { observedThreads += Thread.currentThread().id },
                    onWrite = { observedThreads += Thread.currentThread().id },
                ).also { session = it }
            }
        }
        val manager = DeviceControlManager(this, listOf(backend))
        manager.bind(device)
        assertTrue(manager.write(
            DeviceControlWriteRequest(DeviceControlId("vendor"), DeviceControlWriteValue.Number(1.0)),
        ) is DeviceControlWriteResult.Applied)
        assertTrue(observedThreads.isNotEmpty())
        assertTrue("hardware I/O inherited caller thread: $observedThreads", observedThreads.none { it == callerThread })
        assertEquals(1, session.writes)
        manager.close()
    }

    private fun fakeBackend(id: String, address: DeviceControlBackendAddress): FakeBackend = FakeBackend(id, address)

    private class FakeBackend(
        private val id: String,
        private val address: DeviceControlBackendAddress,
    ) : DeviceControlBackend {
        var session: FakeSession? = null
        override suspend fun open(device: DeviceControlDevice, generation: Long): DeviceControlSession =
            FakeSession(device, generation, id, address).also { session = it }
    }

    private class FakeSession(
        override val device: DeviceControlDevice,
        override val generation: Long,
        private val id: String,
        private val address: DeviceControlBackendAddress,
        private val onProbe: (() -> Unit)? = null,
        private val onWrite: (() -> Unit)? = null,
    ) : DeviceControlSession {
        private val state = MutableStateFlow(DeviceControlSnapshot(generation, device))
        override val snapshot: StateFlow<DeviceControlSnapshot> = state
        var writes = 0

        override suspend fun probe() {
            onProbe?.invoke()
            state.value = DeviceControlSnapshot(
                generation,
                device,
                listOf(
                    DeviceControlCapability.Range(
                        DeviceControlId(id),
                        id,
                        DeviceNumericControl(
                            DeviceControlId(id),
                            id,
                            access = DeviceControlAccess.READ_WRITE,
                            current = 0.0,
                            ranges = listOf(DeviceNumericRange(-10.0, 10.0, 1.0)),
                            address = address,
                        ),
                    ),
                ),
                DeviceControlSnapshot.ProbeState.READY,
            )
        }

        override suspend fun refresh() = probe()
        override suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult {
            onWrite?.invoke()
            writes++
            return DeviceControlWriteResult.Applied(state.value)
        }
        override suspend fun close() = Unit
    }
}
