package com.rawsmusic.lyric

import android.content.Context
import android.content.pm.PackageManager
import android.net.IConnectivityManager
import android.os.INetworkManagementService
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.lsposed.hiddenapibypass.HiddenApiBypass
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
    private const val PREFS_NAME = "xiaomi_super_island_xmsf"
    private const val KEY_BLOCKED = "xmsf_networking_blocked"
    private val permissionMutex = Mutex()

    fun isMarkedBlocked(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLOCKED, false)

    private fun markBlocked(context: Context, blocked: Boolean): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BLOCKED, blocked).commit()

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
    private fun installHiddenApiAccess(): Boolean {
        return if (Build.VERSION.SDK_INT < 28) true else runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/net/IConnectivityManager", "Landroid/os/INetworkManagementService",
            )
        }.onFailure { Log.w(TAG, "XMSF hidden API exemption failed", it) }.getOrDefault(false)
    }

    private fun getTypedConnectivity(): IConnectivityManager {
        val binder = SystemServiceHelper.getSystemService("connectivity")
            ?: error("connectivity binder is null")
        val original = IConnectivityManager.Stub.asInterface(binder)
            ?: error("connectivity original interface is null")
        return IConnectivityManager.Stub.asInterface(ShizukuBinderWrapper(original.asBinder()))
            ?: error("connectivity interface is null")
    }
    private fun getTypedNetworkManagement(): INetworkManagementService {
        val binder = SystemServiceHelper.getSystemService("network_management")
            ?: error("network_management binder is null")
        val original = INetworkManagementService.Stub.asInterface(binder)
            ?: error("network_management original interface is null")
        return INetworkManagementService.Stub.asInterface(ShizukuBinderWrapper(original.asBinder()))
            ?: error("network_management interface is null")
    }

    @Volatile private var nextRequestCode = 1000

    suspend fun setNetworkingEnabled(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val uid = runCatching { appContext.packageManager.getPackageUid(XMSF_PACKAGE, 0) }
            .getOrElse {
                Log.w(TAG, "XMSF_UID_LOOKUP_FAILED package=$XMSF_PACKAGE", it)
                return false
            }
        if (!ensureShizukuPermission(request = false)) {
            Log.w(TAG, "Shizuku is unavailable or permission was denied")
            return false
        }
        return withContext(Dispatchers.IO) {
            XmsfNetworkRecovery.apply(enabled, { markBlocked(appContext, it) }) {
                var lastFailure: Throwable? = null
                repeat(MAX_RETRIES) { attempt ->
                    try {
                        applyFirewallRule(uid, enabled)
                        Log.d(TAG, "XMSF networking ${if (enabled) "restored" else "blocked"} uid=$uid")
                        return@apply true
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
    }

    /** Only the settings page requests permission; lyric ticks check the existing grant. */
    suspend fun ensureShizukuPermission(request: Boolean = true): Boolean = permissionMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            var ready = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            repeat(20) {
                if (!ready) {
                    delay(250L)
                    ready = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                }
            }
            if (!ready) return@withContext false
            if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) ==
                PackageManager.PERMISSION_GRANTED
            ) return@withContext true
            if (!request) return@withContext false
            val result = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<Boolean> { continuation ->
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
            result == true || runCatching {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
    }

    private fun applyFirewallRule(uid: Int, enabled: Boolean) {
        val hiddenApiAccess = installHiddenApiAccess()
        // Rebuild proxies at every attempt, including after Shizuku has restarted.
        wrappedServices.clear()
        Log.d(TAG, "XMSF firewall request uid=$uid enabled=$enabled sdk=${Build.VERSION.SDK_INT} hiddenApi=$hiddenApiAccess")
        // Some ROMs omit these methods from reflection. Resolve system interfaces directly,
        // preserving Shizuku's Binder identity, before trying reflective legacy signatures.
        runCatching {
            val typedConnectivity = getTypedConnectivity()
            val rule = if (enabled) RULE_DEFAULT else RULE_DENY
            if (!enabled) typedConnectivity.setFirewallChainEnabled(OEM_DENY_CHAIN, true)
            typedConnectivity.setUidFirewallRule(OEM_DENY_CHAIN, uid, rule)
            Log.i(TAG, "XMSF_FIREWALL_APPLIED backend=typed_connectivity uid=$uid enabled=$enabled")
            return
        }.onFailure { Log.w(TAG, "Typed connectivity firewall failed; trying network management", it) }
        runCatching {
            val typedNetworkManagement = getTypedNetworkManagement()
            val rule = if (enabled) RULE_DEFAULT else RULE_DENY
            if (!enabled) typedNetworkManagement.setFirewallChainEnabled(OEM_DENY_CHAIN, true)
            typedNetworkManagement.setUidFirewallRule(OEM_DENY_CHAIN, uid, rule)
            Log.i(TAG, "XMSF_FIREWALL_APPLIED backend=typed_network_management uid=$uid enabled=$enabled")
            return
        }.onFailure { Log.w(TAG, "Typed network management firewall failed; trying reflection", it) }
        // These AIDL interfaces are hidden and changed across Android releases. Keep
        // the call boundary reflective so the app can compile against public SDKs.
        val failures = mutableListOf<String>()
        for (backend in backends) {
            try {
                applyFirewallRule(getWrappedService(backend), uid, enabled)
                Log.i(TAG, "XMSF_FIREWALL_APPLIED backend=reflective_${backend.serviceName} uid=$uid enabled=$enabled")
                return
            } catch (error: Throwable) {
                failures += "${backend.label}: ${error.message ?: error.javaClass.name}"
            }
        }
        error("No compatible firewall backend. ${failures.joinToString(" | ")}")
    }

    private fun applyFirewallRule(service: Any, uid: Int, enabled: Boolean) {
        val failures = mutableListOf<String>()
        val modernRule = if (enabled) RULE_DEFAULT else RULE_DENY
        val modernAttempts = listOf<() -> Unit>(
            { call(service, listOf("setUidFirewallRule", "setFirewallUidRule"), OEM_DENY_CHAIN, uid, modernRule) },
            { call(service, listOf("setUidFirewallRules", "setFirewallUidRules"), OEM_DENY_CHAIN, intArrayOf(uid), intArrayOf(modernRule)) }
        )
        val modernSucceeded = runCatching {
            var applied = false
            XmsfFirewallArguments.modern(enabled,
                { call(service, listOf("setFirewallChainEnabled"), OEM_DENY_CHAIN, true) },
                { applied = runAttempts(modernAttempts, failures) })
            applied
        }.onFailure { failures += "modern chain: ${it.message}" }.getOrDefault(false)
        if (modernSucceeded) return

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
        val adapted = XmsfFirewallArguments.adapt(method.parameterTypes, args)
        try {
            method.invoke(target, *adapted)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }
}
