package com.rawsmusic.core.ui.widget.flow

internal fun canReuseAlbumTexture(cachedSource: Long?, requestedSource: Long?): Boolean =
    requestedSource == null || cachedSource == requestedSource

internal fun canPublishAlbumTexture(cachedSource: Long?, incomingSource: Long?): Boolean =
    cachedSource == null || incomingSource != null
