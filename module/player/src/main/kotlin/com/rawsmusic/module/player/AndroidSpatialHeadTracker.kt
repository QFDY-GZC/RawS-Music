package com.rawsmusic.module.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.rawsmusic.core.common.utils.AppLogger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads Android's dynamic headphone head-tracker sensor (TYPE_HEAD_TRACKER=37).
 *
 * The phone's own rotation-vector sensor is deliberately not used as a fallback:
 * moving the handset is not equivalent to moving the listener's head. When no
 * compatible headset sensor is exposed, head tracking remains unavailable.
 */
class AndroidSpatialHeadTracker(
    context: Context,
    private val onRelativeQuaternion: (x: Float, y: Float, z: Float, w: Float) -> Unit
) : AutoCloseable {
    data class Capability(val available: Boolean, val sensorName: String?)

    companion object {
        private const val TAG = "RawSpatialHeadTracker"
        private const val TYPE_HEAD_TRACKER_COMPAT = 37
        private const val SENSOR_PERIOD_US = 10_000 // 100 Hz request; hardware may report slower.
        private const val CALLBACK_MIN_INTERVAL_NS = 8_000_000L

        fun capability(context: Context): Capability {
            if (Build.VERSION.SDK_INT < 33) return Capability(false, null)
            val manager = context.applicationContext
                .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return Capability(false, null)
            val sensor = manager.getDynamicSensorList(TYPE_HEAD_TRACKER_COMPAT).firstOrNull()
                ?: manager.getDefaultSensor(TYPE_HEAD_TRACKER_COMPAT)
            return Capability(sensor != null, sensor?.name)
        }

        fun observeCapability(
            context: Context,
            onChanged: (Capability) -> Unit
        ): AutoCloseable {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (Build.VERSION.SDK_INT < 33 || manager == null) {
                onChanged(Capability(false, null))
                return AutoCloseable { }
            }
            val mainHandler = Handler(Looper.getMainLooper())
            val callback = object : SensorManager.DynamicSensorCallback() {
                override fun onDynamicSensorConnected(sensor: Sensor) {
                    if (sensor.type == TYPE_HEAD_TRACKER_COMPAT) {
                        onChanged(capability(appContext))
                    }
                }

                override fun onDynamicSensorDisconnected(sensor: Sensor) {
                    if (sensor.type == TYPE_HEAD_TRACKER_COMPAT) {
                        onChanged(capability(appContext))
                    }
                }
            }
            manager.registerDynamicSensorCallback(callback, mainHandler)
            onChanged(capability(appContext))
            return AutoCloseable {
                manager.unregisterDynamicSensorCallback(callback)
            }
        }
    }

    private data class Quaternion(
        val x: Float,
        val y: Float,
        val z: Float,
        val w: Float
    )

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("RawSpatialHeadTracker").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var requestedEnabled = false
    @Volatile
    private var closed = false
    private var activeSensor: Sensor? = null
    private var referenceQuaternion: Quaternion? = null
    private var recenterPending = true
    private var lastCallbackTimestampNs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!requestedEnabled || event.values.size < 3) return
            if (Build.VERSION.SDK_INT >= 33 && event.firstEventAfterDiscontinuity) {
                recenterPending = true
            }
            val current = axisAngleVectorToQuaternion(
                event.values[0],
                event.values[1],
                event.values[2]
            )
            if (recenterPending || referenceQuaternion == null) {
                referenceQuaternion = current
                recenterPending = false
                lastCallbackTimestampNs = event.timestamp
                onRelativeQuaternion(0f, 0f, 0f, 1f)
                AppLogger.i(TAG, "head tracker recentered sensor=${event.sensor.name}")
                return
            }
            if (event.timestamp - lastCallbackTimestampNs < CALLBACK_MIN_INTERVAL_NS) return
            lastCallbackTimestampNs = event.timestamp

            val reference = referenceQuaternion ?: current
            // Sensor values transform reference-frame orientation to head-frame
            // orientation. qCurrent * inverse(qReference) gives motion since recenter.
            val relative = multiply(current, conjugate(reference)).normalized()
            // Android head coordinates are +X right, +Y forward, +Z up. The
            // Ambisonics renderer uses +X forward, +Y left, +Z up, so rotate
            // the quaternion vector part into the renderer basis.
            val rendererRelative = Quaternion(
                x = relative.y,
                y = -relative.x,
                z = relative.z,
                w = relative.w
            ).normalized()
            onRelativeQuaternion(
                rendererRelative.x,
                rendererRelative.y,
                rendererRelative.z,
                rendererRelative.w
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val dynamicCallback = object : SensorManager.DynamicSensorCallback() {
        override fun onDynamicSensorConnected(sensor: Sensor) {
            if (sensor.type == TYPE_HEAD_TRACKER_COMPAT) {
                handler.post { updateRegistration("dynamic_connected") }
            }
        }

        override fun onDynamicSensorDisconnected(sensor: Sensor) {
            if (sensor.type == TYPE_HEAD_TRACKER_COMPAT) {
                handler.post { updateRegistration("dynamic_disconnected") }
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 24) {
            sensorManager.registerDynamicSensorCallback(dynamicCallback, handler)
        }
    }

    fun setEnabled(enabled: Boolean) {
        requestedEnabled = enabled
        handler.post { updateRegistration("set_enabled=$enabled") }
    }

    fun recenter() {
        handler.post {
            recenterPending = true
            referenceQuaternion = null
            onRelativeQuaternion(0f, 0f, 0f, 1f)
        }
    }

    fun isAvailable(): Boolean = findSensor() != null

    private fun findSensor(): Sensor? {
        if (Build.VERSION.SDK_INT < 33) return null
        return sensorManager.getDynamicSensorList(TYPE_HEAD_TRACKER_COMPAT).firstOrNull()
            ?: sensorManager.getDefaultSensor(TYPE_HEAD_TRACKER_COMPAT)
    }

    private fun updateRegistration(reason: String) {
        if (closed) return
        val desiredSensor = if (requestedEnabled) findSensor() else null
        if (activeSensor === desiredSensor) return

        sensorManager.unregisterListener(listener)
        activeSensor = null
        referenceQuaternion = null
        recenterPending = true
        lastCallbackTimestampNs = 0L
        onRelativeQuaternion(0f, 0f, 0f, 1f)

        if (desiredSensor != null) {
            val registered = sensorManager.registerListener(
                listener,
                desiredSensor,
                SENSOR_PERIOD_US,
                0,
                handler
            )
            if (registered) activeSensor = desiredSensor
            AppLogger.i(
                TAG,
                "registration reason=$reason requested=$requestedEnabled " +
                    "registered=$registered sensor=${desiredSensor.name}"
            )
        } else {
            AppLogger.i(
                TAG,
                "registration reason=$reason requested=$requestedEnabled sensor=none"
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        requestedEnabled = false
        sensorManager.unregisterListener(listener)
        if (Build.VERSION.SDK_INT >= 24) {
            sensorManager.unregisterDynamicSensorCallback(dynamicCallback)
        }
        onRelativeQuaternion(0f, 0f, 0f, 1f)
        thread.quitSafely()
    }

    private fun axisAngleVectorToQuaternion(x: Float, y: Float, z: Float): Quaternion {
        val angle = sqrt(x * x + y * y + z * z)
        if (!angle.isFinite() || angle < 1.0e-7f) return Quaternion(0f, 0f, 0f, 1f)
        val scale = sin(angle * 0.5f) / angle
        return Quaternion(x * scale, y * scale, z * scale, cos(angle * 0.5f)).normalized()
    }

    private fun conjugate(q: Quaternion): Quaternion = Quaternion(-q.x, -q.y, -q.z, q.w)

    private fun multiply(a: Quaternion, b: Quaternion): Quaternion = Quaternion(
        x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
    )

    private fun Quaternion.normalized(): Quaternion {
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (!norm.isFinite() || norm < 1.0e-7f) return Quaternion(0f, 0f, 0f, 1f)
        return Quaternion(x / norm, y / norm, z / norm, w / norm)
    }
}
