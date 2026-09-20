package com.rawsmusic.module.scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

data class LibraryScanSummary(
    val scanned: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val elapsedMs: Long,
    val mode: Mode = Mode.FULL,
    val skippedDirectories: Int = 0,
    val reused: Int = 0,
    val metadataParsed: Int = 0
) {
    val outcome: Outcome
        get() = when {
            scanned <= 0 && added <= 0 && updated <= 0 && removed <= 0 -> Outcome.EMPTY
            added > 0 -> Outcome.ADDED
            updated > 0 || removed > 0 -> Outcome.CHANGED
            else -> Outcome.UNCHANGED
        }

    enum class Outcome { EMPTY, ADDED, CHANGED, UNCHANGED }

    enum class Mode { FULL, INCREMENTAL }
}

/** Keeps every scan entry point on the same completion-toast wording and timing. */
object LibraryScanToast {
    fun show(context: Context, summary: LibraryScanSummary) {
        val appContext = context.applicationContext
        val text = format(appContext, summary)
        val showToast = {
            Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToast()
        } else {
            Handler(Looper.getMainLooper()).post(showToast)
        }
    }

    fun format(context: Context, summary: LibraryScanSummary): String {
        val scanned = summary.scanned.coerceAtLeast(0)
        val added = summary.added.coerceAtLeast(0)
        val parts = mutableListOf(
            context.getString(
                if (summary.mode == LibraryScanSummary.Mode.INCREMENTAL) {
                    R.string.library_scan_toast_incremental
                } else {
                    R.string.library_scan_toast_full
                },
                scanned
            ),
            context.getString(R.string.library_scan_toast_added, added)
        )

        if (summary.mode == LibraryScanSummary.Mode.INCREMENTAL) {
            if (summary.reused > 0) {
                parts += context.getString(R.string.library_scan_toast_reused, summary.reused)
            }
            if (summary.metadataParsed > 0) {
                parts += context.getString(R.string.library_scan_toast_parsed, summary.metadataParsed)
            }
            if (summary.skippedDirectories > 0) {
                parts += context.getString(R.string.library_scan_toast_skipped_directories, summary.skippedDirectories)
            }
        }

        if (summary.updated > 0) {
            parts += context.getString(R.string.library_scan_toast_updated, summary.updated)
        }
        if (summary.removed > 0) {
            parts += context.getString(R.string.library_scan_toast_removed, summary.removed)
        }
        when (summary.outcome) {
            LibraryScanSummary.Outcome.EMPTY -> parts += context.getString(R.string.library_scan_toast_empty)
            LibraryScanSummary.Outcome.UNCHANGED -> parts += context.getString(R.string.library_scan_toast_unchanged)
            LibraryScanSummary.Outcome.ADDED,
            LibraryScanSummary.Outcome.CHANGED -> Unit
        }

        return context.getString(
            R.string.library_scan_toast_result,
            parts.joinToString(context.getString(R.string.library_scan_toast_separator)),
            summary.elapsedMs.coerceAtLeast(0L) / 1000.0
        )
    }
}
