package com.rawsmusic.core.common.utils

import android.content.Context
import android.os.DropBoxManager
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Best-effort evidence, never a claim that the last USB call caused a kernel panic. */
internal object UsbBootEvidence {
    private const val MAX_BYTES = 256 * 1024
    private const val ROOT_MAX_BYTES = 4 * 1024 * 1024

    fun collectAsync(context: Context, destination: File) {
        val app = context.applicationContext
        Thread({
            runCatching {
                val report = buildString {
                    appendLine("CollectedAtMs=${System.currentTimeMillis()} uptimeMs=${SystemClock.elapsedRealtime()}")
                    appendLine("Missing/denied evidence does not exclude kernel failure. Current dmesg is NOT the previous boot.")
                    for (property in listOf("ro.boot.bootreason", "sys.boot.reason", "sys.boot.reason.last", "persist.sys.boot.reason")) {
                        appendLine("=== $property ===")
                        appendLine(command(destination, listOf("/system/bin/getprop", property)))
                    }
                    for (directory in listOf("/sys/fs/pstore", "/proc/last_kmsg")) {
                        val source = File(directory)
                        appendLine("=== $directory ===")
                        val entries = if (source.isDirectory) source.listFiles()?.sortedBy { it.name }?.take(8)
                            else if (source.isFile) listOf(source) else null
                        if (entries.isNullOrEmpty()) appendLine("Unavailable, empty, or permission denied (no root requested).")
                        entries?.forEach { file ->
                            appendLine("--- ${file.name} ---")
                            appendLine(runCatching {
                                file.inputStream().use { input ->
                                    val bytes = ByteArray(MAX_BYTES)
                                    var total = 0
                                    while (total < bytes.size) {
                                        val read = input.read(bytes, total, bytes.size - total)
                                        if (read <= 0) break
                                        total += read
                                    }
                                    bytes.decodeToString(0, total) + if (total == MAX_BYTES) "\n[bounded at $MAX_BYTES bytes]" else ""
                                }
                            }.getOrElse { "Read unavailable: ${it.javaClass.simpleName}: ${it.message}" })
                        }
                    }
                    val dropbox = app.getSystemService(Context.DROPBOX_SERVICE) as? DropBoxManager
                    // Entries can be delayed by Android BootReceiver; timestamps are retained, not attributed blindly.
                    val since = System.currentTimeMillis() - SystemClock.elapsedRealtime() - 60_000L
                    for (tag in listOf("SYSTEM_LAST_KMSG", "SYSTEM_RECOVERY_LOG", "SYSTEM_SERVER_WATCHDOG", "SYSTEM_BOOT")) {
                        appendLine("=== DropBox $tag ===")
                        appendLine(runCatching {
                            val entry = dropbox?.getNextEntry(tag, since)
                            if (entry == null) "Unavailable/no matching entry yet (may require privileged permission)."
                            else try {
                                "entryTimeMs=${entry.timeMillis}\n${entry.getText(MAX_BYTES) ?: "Non-text entry"}"
                            } finally { entry.close() }
                        }.getOrElse { "Read unavailable: ${it.javaClass.simpleName}: ${it.message}" })
                    }
                    appendLine("=== Current-boot dmesg (context only) ===")
                    appendLine(command(destination, listOf("/system/bin/dmesg")))
                    appendLine("=== Root previous-boot evidence ===")
                    appendLine("Deferred until the user explicitly exports logs; no background root prompt is issued at boot.")
                }
                val staging = File(destination, "boot-evidence.pending")
                FileOutputStream(staging).use { it.write(report.toByteArray()); it.fd.sync() }
                check(staging.renameTo(File(destination, "boot-evidence.txt")))
            }.onFailure {
                runCatching { File(destination, "boot-evidence-error.txt").writeText("Collection failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }, "usb-boot-evidence").apply { isDaemon = true; start() }
    }

    /**
     * Collect the strongest previous-boot evidence available to a rooted device.
     *
     * This is intentionally invoked only from an explicit user log-export action. Root managers
     * may display an authorization prompt, which is unreliable and intrusive during boot. Every
     * command below is read-only: no sysctl/property writes, USB ioctls, reboot, or filesystem
     * mutation outside our own evidence file.
     */
    fun collectRootEvidenceForExport(destination: File): String = runCatching {
        destination.mkdirs()
        val su = resolveSuBinary()
        val script = buildString {
            append("echo '=== RawSMusic rooted previous-boot evidence ==='; ")
            append("id; date; uptime; ")
            append("echo '=== boot identity ==='; ")
            append("cat /proc/sys/kernel/random/boot_id 2>&1; ")
            append("settings get global boot_count 2>&1; ")
            append("for p in ro.boot.bootreason sys.boot.reason sys.boot.reason.last persist.sys.boot.reason ro.bootmode; do echo \"\$p=\$(getprop \"\$p\")\"; done; ")
            append("echo '=== boot-related properties ==='; getprop 2>&1 | grep -i -E 'boot|reboot|watchdog|panic' | head -300; ")
            append("echo '=== pstore / last_kmsg ==='; ls -la /sys/fs/pstore 2>&1; ")
            append("for f in /sys/fs/pstore/* /proc/last_kmsg; do if [ -f \"\$f\" ]; then echo \"--- \$f ---\"; tail -c 786432 \"\$f\" 2>&1; echo; fi; done; ")
            append("echo '=== previous-boot logcat (-L, when supported) ==='; ")
            append("logcat -L -b all -d -v threadtime -t 20000 2>&1 || echo 'logcat -L unavailable'; ")
            append("echo '=== current kernel dmesg (context) ==='; dmesg -T 2>&1 || dmesg 2>&1; ")
            append("echo '=== DropBox inventory ==='; ls -lat /data/system/dropbox 2>&1 | head -160; ")
            append("echo '=== selected DropBox reboot/watchdog entries ==='; ")
            append("for n in \$(ls -1t /data/system/dropbox 2>/dev/null | grep -E 'SYSTEM_(LAST_KMSG|RECOVERY_LOG|SERVER_WATCHDOG|SERVER_CRASH|BOOT)|SYSTEM_TOMBSTONE|SYSTEM_FSCK' | head -24); do ")
            append("f=/data/system/dropbox/\$n; echo \"--- \$f ---\"; ")
            append("case \"\$f\" in *.gz) (toybox zcat \"\$f\" 2>/dev/null || gzip -dc \"\$f\" 2>/dev/null || cat \"\$f\") | tail -c 786432 ;; *) tail -c 786432 \"\$f\" 2>&1 ;; esac; echo; done; ")
            append("echo '=== tombstone inventory ==='; ls -lat /data/tombstones 2>&1 | head -100; ")
            append("echo '=== end rooted evidence ==='")
        }
        val result = commandWithLimit(
            directory = destination,
            args = listOf(su, "-c", script),
            timeoutSeconds = 45,
            maxBytes = ROOT_MAX_BYTES,
            tempName = "boot-root-command.tmp",
        )
        val file = File(destination, "boot-evidence-root.txt")
        FileOutputStream(file).use { stream ->
            stream.write(
                buildString {
                    appendLine("Collector=explicit_log_export")
                    appendLine("SuBinary=$su")
                    appendLine("CollectedAtMs=${System.currentTimeMillis()}")
                    appendLine("ReadOnly=true")
                    append(result)
                }.toByteArray(Charsets.UTF_8),
            )
            stream.fd.sync()
        }
        if (result.contains("uid=0")) {
            "root evidence collected: ${file.absolutePath}"
        } else {
            "root evidence attempted but root was not confirmed; see ${file.absolutePath}"
        }
    }.getOrElse { error ->
        val message = "root evidence collection failed: ${error.javaClass.simpleName}: ${error.message}"
        runCatching {
            FileOutputStream(File(destination, "boot-evidence-root.txt")).use { stream ->
                stream.write((message + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
        }
        message
    }

    private fun resolveSuBinary(): String {
        val absoluteCandidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
        )
        return absoluteCandidates.firstOrNull { path ->
            runCatching { File(path).isFile && File(path).canExecute() }.getOrDefault(false)
        } ?: "su"
    }

    private fun command(directory: File, args: List<String>, timeoutSeconds: Long = 2): String = runCatching {
        commandWithLimit(directory, args, timeoutSeconds, MAX_BYTES, "boot-command.tmp")
    }.getOrElse { "Unavailable: ${it.javaClass.simpleName}: ${it.message}" }

    private fun commandWithLimit(
        directory: File,
        args: List<String>,
        timeoutSeconds: Long,
        maxBytes: Int,
        tempName: String,
    ): String = runCatching {
        val output = File(directory, tempName)
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start()
        try {
            val done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) process.destroyForcibly()
            val text = output.inputStream().use { input ->
                val bytes = ByteArray(maxBytes)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read <= 0) break
                    count += read
                }
                bytes.decodeToString(0, count)
            }
            "${if (done) "exit=${process.exitValue()}" else "Timed out (including possible permission prompt)"}\n$text" +
                if (output.length() >= maxBytes) "\n[command output bounded at $maxBytes bytes]" else ""
        } finally { process.destroy(); output.delete() }
    }.getOrElse { "Unavailable: ${it.javaClass.simpleName}: ${it.message}" }
}
