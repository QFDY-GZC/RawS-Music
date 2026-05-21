package com.rawsmusic.core.ui.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.graphics.Outline
import coil.load
import com.rawsmusic.core.common.base.BaseAdapter
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.databinding.ItemSongBinding
import com.rawsmusic.core.ui.theme.ThemeManager

class SongAdapter(
    private val onSongClick: (AudioFile, Int) -> Unit,
    private val onLongClick: ((AudioFile, Int) -> Boolean)? = null,
    private val onSelectionChanged: ((Set<Long>) -> Unit)? = null
) : BaseAdapter<AudioFile, ItemSongBinding>(
    bindingInflater = { parent, _ ->
        ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }
) {

    private val roundedOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val cornerPx = (10 * view.resources.displayMetrics.density).toInt()
            outline.setRoundRect(0, 0, view.width, view.height, cornerPx.toFloat())
        }
    }

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

    var isLightBackground: Boolean = false

    var fontApplier: ((android.widget.TextView) -> Unit)? = null

    private var recyclerView: androidx.recyclerview.widget.RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun notifyVisibleItemsChanged() {
        updateVisibleTextColors()
    }

    private fun updateVisibleTextColors() {
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        for (i in first..last) {
            val holder = rv.findViewHolderForAdapterPosition(i) ?: continue
            val binding = try {
                ItemSongBinding.bind(holder.itemView)
            } catch (_: Exception) { continue }
            val isPlaying = items.getOrNull(i)?.id == currentPlayingId
            applyTextColor(binding, isPlaying)
        }
    }

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
            if (item.album.isNotBlank()) {
                append(" · ")
                append(item.album)
            }
        }
        binding.tvMeta.text = buildMetaText(item)

        fontApplier?.invoke(binding.tvTitle)
        fontApplier?.invoke(binding.tvArtist)
        fontApplier?.invoke(binding.tvMeta)

        val coverUri = if (item.path.isNotBlank()) {
            Uri.parse("song://${item.path}")
        } else {
            item.albumArtPath.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        }
        if (coverUri != null) {
            binding.ivCover.visibility = View.VISIBLE
            binding.ivCover.outlineProvider = roundedOutlineProvider
            binding.ivCover.clipToOutline = true
            binding.ivCover.load(coverUri) {
                crossfade(true)
            }
        } else {
            binding.ivCover.visibility = View.GONE
        }

        val isPlaying = item.id == currentPlayingId
        val isSelected = item.id in _selectedIds

        binding.tvTitle.isSelected = true
        binding.tvArtist.isSelected = true

        applyTextColor(binding, isPlaying)

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

    private fun applyTextColor(binding: ItemSongBinding, isPlaying: Boolean) {
        val isLight = ThemeManager.isLightBackground
        val primaryColor = if (isLight) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val secondaryColor = if (isLight) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
        val tertiaryColor = if (isLight) 0x99000000.toInt() else 0x99FFFFFF.toInt()

        binding.tvTitle.setTextColor(if (isPlaying) primaryColor else primaryColor)
        binding.tvArtist.setTextColor(secondaryColor)
        binding.tvMeta.setTextColor(tertiaryColor)
    }

    private fun buildMetaText(item: AudioFile): String {
        val parts = mutableListOf<String>()
        if (item.isHiRes) {
            parts.add("Hi·Res")
        }
        if (item.bitsPerSample > 0) {
            parts.add("${item.bitsPerSample}bit")
        }
        if (item.sampleRate > 0) {
            val kHz = item.sampleRate / 1000.0
            val kHzStr = if (item.sampleRate % 1000 == 0) {
                "${item.sampleRate / 1000}kHz"
            } else {
                String.format("%.1fkHz", kHz)
            }
            parts.add(kHzStr)
        }
        parts.add(AudioUtils.formatDuration(item.duration))
        return parts.joinToString(" | ")
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
