package com.rawsmusic.core.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.powerlist.PowerListSceneItem

/**
 * Song data provider.
 * Replaces SongAdapter with data provider implementation.
 */
class SongDataProvider {

    companion object {
        /** Single view type for all layouts - Poweramp uses ONE view type per view category */
        private const val VIEW_TYPE_SONG = 0
    }

    private var items: List<AudioFile> = emptyList()

    var isSelectMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // PowerListView will handle refresh
            }
        }

    var selectedPositions: Set<Int> = emptySet()
        set(value) {
            field = value
            // PowerListView will handle refresh
        }

    var playingPosition: Int = -1
        set(value) {
            field = value
            // PowerListView will handle refresh
        }

    var onItemClicked: ((AudioFile, Int) -> Unit)? = null
    var onItemLongClicked: ((AudioFile, Int) -> Unit)? = null
    var onSelectionChanged: ((Set<Int>) -> Unit)? = null

    fun getItemCount(): Int = items.size

    fun createView(position: Int, parent: ViewGroup): View? {
        // Poweramp: ONE XML layout per view type, scene switching via SceneParams
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return view
    }

    fun bindView(position: Int, view: View) {
        if (position < 0 || position >= items.size) return
        val item = items[position]

        val aaImage = view.findViewById<ImageView>(R.id.aa_image)
        val tvTitle = view.findViewById<TextView>(R.id.title)
        val tvLine2 = view.findViewById<TextView>(R.id.line2)
        val tvMeta = view.findViewById<TextView>(R.id.meta)
        val cbSelect = view.findViewById<CheckBox>(R.id.select_box)

        // Set title
        tvTitle?.text = item.displayName
        tvTitle?.isSelected = false

        // Set artist and album (line2)
        val artistAlbum = buildString {
            append(item.artist.ifBlank { "Unknown Artist" })
            if (item.album.isNotBlank()) {
                append(" · ")
                append(item.album)
            }
        }
        tvLine2?.text = artistAlbum
        tvLine2?.isSelected = false

        // Always set text content. Scene/layout code controls grid/list visibility so
        // Poweramp grid's INVISIBLE meta scene is preserved instead of provider forcing GONE.
        val fullMeta = buildMetaText(item)
        val durationMeta = AudioUtils.formatDuration(item.duration)
        tvMeta?.text = fullMeta
        tvMeta?.setTag(R.id.tag_meta_full_text, fullMeta)
        tvMeta?.setTag(R.id.tag_meta_duration_text, durationMeta)
        tvMeta?.visibility = View.VISIBLE
        tvLine2?.visibility = View.VISIBLE

        // Update text colors based on playing state
        val isPlaying = position == playingPosition
        updateTextColors(view, tvTitle, tvLine2, tvMeta, isPlaying)
        view.alpha = if (isPlaying) 1f else 0.85f

        // Update selection state
        cbSelect?.visibility = if (isSelectMode) View.VISIBLE else View.GONE
        cbSelect?.isChecked = selectedPositions.contains(position)

        // Load cover art
        aaImage?.let { loadCover(it, item) }

        // PowerListView owns item click dispatch and resolves holder position at click time,
        // matching Poweramp's PowerList.i(view). Do not install position-capturing item
        // listeners from bindView(), because reused holders would report stale positions.
        cbSelect.setOnClickListener {
            toggleSelection(position)
        }
    }

    fun getItemViewType(position: Int): Int = VIEW_TYPE_SONG

    /**
     * Updates the data list.
     */
    fun submitList(newItems: List<AudioFile>) {
        if (newItems === items) return
        if (newItems.size == items.size && newItems.zip(items).all { (a, b) -> a.id == b.id && a.path == b.path }) return
        items = newItems
    }

    /**
     * Gets the current items.
     */
    fun getItems(): List<AudioFile> = items

    /**
     * Gets selected files.
     */
    fun getSelectedFiles(): List<AudioFile> {
        return selectedPositions.mapNotNull { pos -> items.getOrNull(pos) }
    }

    /**
     * Exits select mode.
     */
    fun exitSelectMode() {
        isSelectMode = false
        selectedPositions = emptySet()
    }

    /**
     * Selects all items.
     */
    fun selectAll() {
        if (items.isEmpty()) return
        selectedPositions = items.indices.toSet()
        isSelectMode = true
        onSelectionChanged?.invoke(selectedPositions)
    }

    /**
     * Inverts selection.
     */
    fun invertSelection() {
        if (items.isEmpty()) return
        selectedPositions = items.indices.toSet() - selectedPositions
        isSelectMode = selectedPositions.isNotEmpty()
        onSelectionChanged?.invoke(selectedPositions)
    }

    /**
     * Updates text colors for visible items.
     */

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

    private fun loadCover(ivCover: ImageView, item: AudioFile) {
        val key = if (item.albumArtPath.isNotEmpty()) item.albumArtPath else item.path
        BitmapProvider.load(
            key = key,
            imageView = ivCover,
            targetWidth = ivCover.width.coerceAtLeast(512),
            targetHeight = ivCover.height.coerceAtLeast(512),
            priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_LIST,
            placeholderResId = R.drawable.ic_music_2_fill,
            errorResId = R.drawable.ic_music_2_fill
        )
    }

    private fun toggleSelection(position: Int) {
        val mutable = selectedPositions.toMutableSet()
        if (mutable.contains(position)) {
            mutable.remove(position)
        } else {
            mutable.add(position)
        }
        selectedPositions = mutable
        onSelectionChanged?.invoke(mutable)

        if (mutable.isEmpty()) {
            isSelectMode = false
        }
    }

    private fun updateTextColors(view: View, tvTitle: TextView?, tvArtist: TextView?, tvMeta: TextView?, isPlaying: Boolean) {
        if (isPlaying) {
            // 高亮文本：使用 now_playing_text（亮色背景下琥珀色 #C4956A，暗色背景下亮琥珀色 #D4B896）
            val highlightColor = ContextCompat.getColor(view.context, R.color.now_playing_text)
            tvTitle?.setTextColor(highlightColor)
            tvArtist?.setTextColor(highlightColor)
            tvMeta?.setTextColor(highlightColor)
        } else {
            val isDark = ThemeManager.isDarkMode(view.context)
            if (isDark) {
                tvTitle?.setTextColor(ContextCompat.getColor(view.context, R.color.text_primary))
                tvArtist?.setTextColor(ContextCompat.getColor(view.context, R.color.text_secondary))
                tvMeta?.setTextColor(ContextCompat.getColor(view.context, R.color.text_secondary))
            } else {
                tvTitle?.setTextColor(ContextCompat.getColor(view.context, R.color.text_primary))
                tvArtist?.setTextColor(ContextCompat.getColor(view.context, R.color.text_secondary))
                tvMeta?.setTextColor(ContextCompat.getColor(view.context, R.color.text_secondary))
            }
        }
    }
}
