package com.rawsmusic.lyric

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Shizuku-backed XMSF firewall compatibility layer used by Super Island. */
internal object XiaomiXmsfNetworkHelper {
    private const val TAG = "RawXmsf"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val OEM_DENY_CHAIN = 9
    private const val RULE_DEFAULT = 0
    private const val RULE_ALLOW = 1
    private const val RULE_DENY = 2
    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 500L

    private data class ServiceBackend(
        val serviceName: String,
        val stubClassName: String,
        val label: String
    )

    private val backends = listOf(
        ServiceBackend("connectivity", "android.net.IConnectivityManager\$Stub", "ConnectivityManager"),
        ServiceBackend("network_management", "android.os.INetworkManagementService\$Stub", "NetworkManagementService")
    )
    private val wrappedServices = ConcurrentHashMap<String, Any>()

    @Volatile private var nextRequestCode = 1000

    suspend fun setNetworkingEnabled(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val uid = runCatching { appContext.packageManager.getPackageUid(XMSF_PACKAGE, 0) }
            .getOrElse {
                Log.i(TAG, "XMSF is not installed")
                return false
            }
        if (!ensureShizukuPermission()) {
            Log.w(TAG, "Shizuku is unavailable or permission was denied")
            return false
        }
        return withContext(Dispatchers.IO) {
            var lastFailure: Throwable? = null
            repeat(MAX_RETRIES) { attempt ->
                try {
                    applyFirewallRule(uid, enabled)
                    Log.d(TAG, "XMSF networking ${if (enabled) "restored" else "blocked"} uid=$uid")
                    return@withContext true
                } catch (error: Throwable) {
                    lastFailure = error
                    wrappedServices.clear()
                    if (attempt + 1 < MAX_RETRIES) delay(RETRY_DELAY_MS)
                }
            }
            Log.w(TAG, "No compatible XMSF firewall backend", lastFailure)
            false
        }
    }

    private suspend fun ensureShizukuPermission(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return@withContext false
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) ==
            PackageManager.PERMISSION_GRANTED
        ) return@withContext true

        suspendCancellableCoroutine { continuation ->
            val requestCode = synchronized(this@XiaomiXmsfNetworkHelper) {
                nextRequestCode = (nextRequestCode + 1).coerceAtLeast(1001)
                nextRequestCode
            }
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            listener = Shizuku.OnRequestPermissionResultListener { returnedCode, result ->
                if (returnedCode != requestCode || !continuation.isActive) return@OnRequestPermissionResultListener
                Shizuku.removeRequestPermissionResultListener(listener)
                continuation.resume(result == PackageManager.PERMISSION_GRANTED)
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            runCatching { Shizuku.requestPermission(requestCode) }.onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private fun applyFirewallRule(uid: Int, enabled: Boolean) {
        // These AIDL interfaces are hidden and changed across Android releases. Keep
        // the call boundary reflective so the app can compile against public SDKs.
        val failures = mutableListOf<String>()
        for (backend in backends) {
            try {
                applyFirewallRule(getWrappedService(backend), uid, enabled)
                return
            } catch (error: Throwable) {
                failures += "${backend.label}: ${error.message ?: error.javaClass.name}"
            }
        }
        error("No compatible firewall backend. ${failures.joinToString(" | ")}")
    }

    private fun applyFirewallRule(service: Any, uid: Int, enabled: Boolean) {
        val failures = mutableListOf<String>()
        if (!enabled) {
            runCatching { call(service, listOf("setFirewallChainEnabled"), OEM_DENY_CHAIN, true) }
                .onFailure { failures += "chain: ${it.message}" }
        }
        val modernRule = if (enabled) RULE_DEFAULT else RULE_DENY
        val modernAttempts = listOf<() -> Unit>(
            { call(service, listOf("setUidFirewallRule", "setFirewallUidRule"), OEM_DENY_CHAIN, uid, modernRule) },
            { call(service, listOf("setUidFirewallRules", "setFirewallUidRules"), OEM_DENY_CHAIN, intArrayOf(uid), intArrayOf(modernRule)) }
        )
        if (runAttempts(modernAttempts, failures)) return

        if (!enabled) {
            runCatching { call(service, listOf("setFirewallEnabled"), true) }
                .onFailure { failures += "legacy chain: ${it.message}" }
        }
        val legacyRule = if (enabled) RULE_ALLOW else RULE_DENY
        val legacyAttempts = listOf<() -> Unit>(
            { call(service, listOf("setUidFirewallRule", "setFirewallUidRule"), uid, enabled) },
            { call(service, listOf("setUidFirewallRule", "setFirewallUidRule"), uid, legacyRule) },
            { call(service, listOf("setUidFirewallRules", "setFirewallUidRules"), intArrayOf(uid), intArrayOf(legacyRule)) }
        )
        if (runAttempts(legacyAttempts, failures)) return
        error("No compatible firewall method on ${service.javaClass.name}: ${failures.joinToString(" | ")}")
    }

    private fun runAttempts(attempts: List<() -> Unit>, failures: MutableList<String>): Boolean {
        attempts.forEach { attempt ->
            try {
                attempt()
                return true
            } catch (error: Throwable) {
                failures += error.message ?: error.javaClass.name
            }
        }
        return false
    }

    private fun getWrappedService(backend: ServiceBackend): Any =
        wrappedServices[backend.serviceName] ?: synchronized(this) {
            wrappedServices[backend.serviceName] ?: run {
                val binder = SystemServiceHelper.getSystemService(backend.serviceName)
                    ?: error("${backend.serviceName} binder is null")
                val stub = Class.forName(backend.stubClassName)
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                val original = asInterface.invoke(null, binder) ?: error("${backend.label} unavailable")
                val originalBinder = original.javaClass.getMethod("asBinder").invoke(original) as? IBinder
                    ?: error("${backend.label} binder unavailable")
                val wrapped = asInterface.invoke(null, ShizukuBinderWrapper(originalBinder))
                    ?: error("${backend.label} wrapper unavailable")
                wrappedServices[backend.serviceName] = wrapped
                wrapped
            }
        }

    private fun call(target: Any, names: List<String>, vararg args: Any) {
        val methods = names.flatMap { name -> target.javaClass.methods.filter { it.name == name && it.parameterCount == args.size } }
        if (methods.isEmpty()) error("Missing ${names.joinToString()}(${args.size})")
        var lastFailure: Throwable? = null
        methods.forEach { method ->
            try {
                invoke(target, method, args)
                return
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        throw lastFailure ?: NoSuchMethodException(names.joinToString())
    }

    private fun invoke(target: Any, method: Method, args: Array<out Any>) {
        method.isAccessible = true
        val adapted = Array(args.size) { index ->
            val expected = method.parameterTypes[index]
            val value = args[index]
            when {
                expected == Int::class.javaPrimitiveType -> when (value) {
                    is Boolean -> if (value) 1 else 0
                    is Number -> value.toInt()
                    else -> error("Unsupported int argument")
                }
                expected == Boolean::class.javaPrimitiveType -> when (value) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    else -> error("Unsupported boolean argument")
                }
                expected == IntArray::class.java && value is IntArray -> value
                expected.isInstance(value) -> value
                else -> error("Argument does not match ${expected.name}")
            }
        }
        try {
            method.invoke(target, *adapted)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }
}
