package com.rawsmusic.core.ui.widget.bitmaps

internal fun shouldParkOutgoingGestureCallback(callbackKey: String, visualKey: String,
    boundKey: String, outgoingKey: String, committedKey: String, leaseActive: Boolean): Boolean =
    callbackKey == boundKey && ignoreStalePromotedGestureBinding(
        callbackKey, visualKey, outgoingKey, committedKey, leaseActive, explicitManual = false,
    )

internal fun ignoreStalePromotedGestureBinding(bindingKey: String, visualKey: String,
    outgoingKey: String, committedKey: String, leaseActive: Boolean, explicitManual: Boolean): Boolean =
    leaseActive && !explicitManual && outgoingKey.isNotBlank() && committedKey.isNotBlank() &&
        outgoingKey != committedKey && bindingKey == outgoingKey && visualKey == committedKey
