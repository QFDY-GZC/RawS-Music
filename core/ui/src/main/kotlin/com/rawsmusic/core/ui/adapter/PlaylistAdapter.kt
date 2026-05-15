package com.rawsmusic.core.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import com.rawsmusic.core.common.base.BaseAdapter
import com.rawsmusic.core.common.model.Playlist
import com.rawsmusic.core.ui.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onPlaylistLongClick: ((Playlist) -> Unit)? = null
) : BaseAdapter<Playlist, ItemPlaylistBinding>(
    bindingInflater = { parent, _ ->
        ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }
) {

    override fun onBind(binding: ItemPlaylistBinding, item: Playlist, position: Int) {
        binding.tvPlaylistName.text = item.name
        binding.tvSongCount.text = "${item.songCount} songs"

        binding.root.setOnClickListener { onPlaylistClick.invoke(item) }
        binding.root.setOnLongClickListener {
            onPlaylistLongClick?.invoke(item)
            true
        }
    }

    override fun areItemsSame(oldItem: Playlist, newItem: Playlist): Boolean {
        return oldItem.id == newItem.id
    }
}
