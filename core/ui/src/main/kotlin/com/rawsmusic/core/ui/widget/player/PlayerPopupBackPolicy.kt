package com.rawsmusic.core.ui.widget.player

internal fun playerPopupCanHandleBack(popupVisible: Boolean, playlistMounted: Boolean): Boolean =
    popupVisible && !playlistMounted
