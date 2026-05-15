package com.rawsmusic.core.ui.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import coil.load
import com.rawsmusic.core.common.base.BaseAdapter
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.databinding.ItemSongBinding

class SongAdapter(
    private val onSongClick: (AudioFile, Int) -> Unit,
    private val onLongClick: ((AudioFile, Int) -> Boolean)? = null,
    private val onSelectionChanged: ((Set<Long>) -> Unit)? = null
) : BaseAdapter<AudioFile, ItemSongBinding>(
    bindingInflater = { parent, _ ->
        ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }
) {

    var currentPlayingId: Long = -1
        set(value) {
            val oldValue = field
            field = value
            val oldIndex = items.indexOfFirst { it.id == oldValue }
            val newIndex = items.indexOfFirst { it.id == value }
            _currentPlayingPosition = newIndex
            if (oldValue == -1L || value == -1L) {
                notifyDataSetChanged()
            } else {
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
                if (newIndex >= 0) notifyItemChanged(newIndex)
            }
        }

    private var _currentPlayingPosition: Int = -1
    val currentPlayingPosition: Int get() = _currentPlayingPosition

    var isEditMode: Boolean = false
        private set

    private val _selectedIds = mutableSetOf<Long>()
    val selectedIds: Set<Long> get() = _selectedIds

    fun enterEditMode(firstItemId: Long) {
        if (isEditMode) return
        isEditMode = true
        _selectedIds.clear()
        _selectedIds.add(firstItemId)
        onSelectionChanged?.invoke(_selectedIds.toSet())
        notifyDataSetChanged()
    }

    fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        _selectedIds.clear()
        onSelectionChanged?.invoke(emptySet())
        notifyDataSetChanged()
    }

    fun selectAll() {
        _selectedIds.clear()
        _selectedIds.addAll(items.map { it.id })
        onSelectionChanged?.invoke(_selectedIds.toSet())
        notifyDataSetChanged()
    }

    fun invertSelection() {
        val allIds = items.map { it.id }.toSet()
        val newSelected = allIds - _selectedIds
        _selectedIds.clear()
        _selectedIds.addAll(newSelected)
        onSelectionChanged?.invoke(_selectedIds.toSet())
        notifyDataSetChanged()
    }

    fun selectSong(id: Long) {
        _selectedIds.add(id)
        onSelectionChanged?.invoke(_selectedIds.toSet())
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    fun deselectSong(id: Long) {
        _selectedIds.remove(id)
        onSelectionChanged?.invoke(_selectedIds.toSet())
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    fun getSelectedSongs(): List<AudioFile> {
        return items.filter { it.id in _selectedIds }
    }

    override fun onBind(binding: ItemSongBinding, item: AudioFile, position: Int) {
        binding.tvTitle.text = item.displayName
        binding.tvArtist.text = buildString {
            append(item.artist.ifBlank { "Unknown Artist" })
            append(" · ")
            append(AudioUtils.formatDuration(item.duration))
        }

        // 优先使用 song:// scheme 从歌曲文件本身提取封面
        val coverUri = if (item.path.isNotBlank()) {
            Uri.parse("song://${item.path}")
        } else {
            item.albumArtPath.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        }
        if (coverUri != null) {
            binding.ivCover.visibility = View.VISIBLE
            binding.ivCover.load(coverUri) {
                crossfade(true)
            }
        } else {
            binding.ivCover.visibility = View.GONE
        }

        val isPlaying = item.id == currentPlayingId
        val isSelected = item.id in _selectedIds

        binding.tvTitle.isSelected = true

        if (isPlaying) {
            binding.tvTitle.setTextColor(0xFFFFFFFF.toInt())
        } else {
            binding.tvTitle.setTextColor(0xFFFFFFFF.toInt())
        }

        binding.root.alpha = if (currentPlayingId != -1L && !isPlaying) 0.85f else 1f

        if (isEditMode) {
            binding.cbSelect.visibility = View.VISIBLE
            binding.cbSelect.isChecked = isSelected
            binding.root.setOnClickListener { toggleSelection(item.id) }
            binding.root.setOnLongClickListener(null)
        } else {
            binding.cbSelect.visibility = View.GONE
            binding.root.setOnClickListener { onSongClick.invoke(item, position) }
            binding.root.setOnLongClickListener { onLongClick?.invoke(item, position) ?: false }
        }
    }

    private fun toggleSelection(id: Long) {
        if (_selectedIds.contains(id)) {
            _selectedIds.remove(id)
        } else {
            _selectedIds.add(id)
        }
        onSelectionChanged?.invoke(_selectedIds.toSet())
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun areItemsSame(oldItem: AudioFile, newItem: AudioFile): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsSame(oldItem: AudioFile, newItem: AudioFile): Boolean {
        return oldItem == newItem && oldItem.isFavorite == newItem.isFavorite
    }
}
