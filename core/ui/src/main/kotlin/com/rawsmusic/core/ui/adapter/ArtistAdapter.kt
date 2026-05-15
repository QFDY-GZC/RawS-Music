package com.rawsmusic.core.ui.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import coil.load
import com.rawsmusic.core.common.base.BaseAdapter
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.ui.databinding.ItemArtistBinding

class ArtistAdapter(
    private val onArtistClick: (Artist) -> Unit
) : BaseAdapter<Artist, ItemArtistBinding>(
    bindingInflater = { parent, _ ->
        ItemArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }
) {

    override fun onBind(binding: ItemArtistBinding, item: Artist, position: Int) {
        binding.tvArtistName.text = item.name
        binding.tvInfo.text = "${item.songCount} songs · ${item.albumCount} albums"

        if (item.coverPath.isNotBlank()) {
            binding.ivArtistCover.load(Uri.parse(item.coverPath)) { crossfade(true) }
        } else {
            binding.ivArtistCover.setImageDrawable(null)
        }

        binding.root.setOnClickListener { onArtistClick.invoke(item) }
    }

    override fun areItemsSame(oldItem: Artist, newItem: Artist): Boolean {
        return oldItem.name == newItem.name
    }
}
