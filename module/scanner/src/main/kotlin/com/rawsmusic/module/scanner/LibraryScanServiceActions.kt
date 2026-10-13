package com.rawsmusic.module.scanner

object LibraryScanServiceActions {
    const val ACTION_START = "com.rawsmusic.action.START_LIBRARY_SCAN"
    const val ACTION_CANCEL = "com.rawsmusic.action.CANCEL_LIBRARY_SCAN"
    const val EXTRA_REASON = "extra_reason"
    const val REASON_MANUAL = "manual"
    const val REASON_AUTO = "media_library_changed"
}
