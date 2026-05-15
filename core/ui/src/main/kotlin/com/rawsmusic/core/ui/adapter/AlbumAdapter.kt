package com.rawsmusic.core.ui.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import coil.load
import com.rawsmusic.core.common.base.BaseAdapter
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.ui.databinding.ItemAlbumBinding

class AlbumAdapter(
    private val onAlbumClick: (Album) -> Unit
) : BaseAdapter<Album, ItemAlbumBinding>(
    bindingInflater = { parent, _ ->
        ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }
) {

    override fun onBind(binding: ItemAlbumBinding, item: Album, position: Int) {
        binding.tvAlbumName.text = item.name
        binding.tvArtist.text = item.artist.ifBlank { "Unknown Artist" }

        val coverUri = item.coverPath.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (coverUri != null) {
            binding.ivCover.load(coverUri) {
                crossfade(true)
            }
        } else {
            binding.ivCover.setImageDrawable(null)
        }

        binding.ivHiresBadge.visibility = if (item.hasHiRes) View.VISIBLE else View.GONE

        binding.root.setOnClickListener { onAlbumClick.invoke(item) }
    }

    override fun areItemsSame(oldItem: Album, newItem: Album): Boolean {
        return oldItem.name == newItem.name && oldItem.artist == newItem.artist
    }
}
