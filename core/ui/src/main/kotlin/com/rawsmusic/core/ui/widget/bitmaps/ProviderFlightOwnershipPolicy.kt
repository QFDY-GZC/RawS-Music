package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Reference baseline implementation keeps provider request-table key + owner under one monitor.
 * A visible same-size request may join only when the table entry still has a live owner.
 */
internal fun shouldReclaimOrphanProviderFlight(
    flightKeyPresent: Boolean,
    ownerPresent: Boolean,
    ownerCancelled: Boolean,
    ownerSourceWorkStarted: Boolean,
): Boolean =
    flightKeyPresent && (!ownerPresent || (ownerCancelled && !ownerSourceWorkStarted))
