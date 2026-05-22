/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.view

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.contains
import androidx.core.view.forEach
import androidx.core.view.isVisible
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.TimingNavigator
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import io.github.proify.lyricon.lyric.view.line.LyricLineView
import java.util.concurrent.CopyOnWriteArraySet

open class LyricPlayerView(
    context: Context,
    attributes: AttributeSet? = null,
) : ScrollView(context, attributes), UpdatableColor {

    companion object {
        internal const val KEY_SONG_TITLE_LINE: String = "TitleLine"
        private const val MIN_GAP_DURATION: Long = 7 * 1000
        private const val TAG = "LyricPlayerView"

        private const val INTERLUDE_FADE_DURATION_MS = 600L
    }

    private var isTextMode = false
    private var styleConfig = RichLyricLineConfig()

    private var isEnableRelativeProgress = false
    private var isEnableRelativeProgressHighlight = false
    private var isEnteringInterludeMode = false

    private var lineModelList: List<RichLyricLineModel>? = null
    private var timingNavigator: TimingNavigator<RichLyricLineModel> = emptyTimingNavigator()
    private var currentInterludeState: InterludeState? = null
    private var currentHighlightIndex = -1

    private val activeLyricLines = mutableListOf<IRichLyricLine>()
    private val textRecycleLineView by lazy { RichLyricLineView(context) }
    private val defaultLayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (14 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
        }

    private var interludeFadeAnimator: ValueAnimator? = null
    private var interludeAlpha: Float = 1f
    private var interludeScale: Float = 1f

    private var isUserScrolling = false
    private var autoScrollResumeTime = 0L
    private val AUTO_SCROLL_RESUME_DELAY = 3000L

    private var anchorOffset: Float = 0f

    val lyricCountChangeListeners = CopyOnWriteArraySet<LyricCountChangeListener>()

    private val mainPlayListener = object : LyricPlayListener {
        override fun onPlayStarted(view: LyricLineView) {}
        override fun onPlayEnded(view: LyricLineView) {}
        override fun onPlayProgress(view: LyricLineView, total: Float, progress: Float) {}
    }

    private val secondaryPlayListener = object : LyricPlayListener {
        override fun onPlayStarted(view: LyricLineView) {}
        override fun onPlayEnded(view: LyricLineView) {}
        override fun onPlayProgress(view: LyricLineView, total: Float, progress: Float) {}
    }

    private val contentView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        addView(contentView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    var isDisplayTranslation = true
        private set
    var isDisplayRoma = true
        private set

    var song: Song? = null
        set(value) {
            isTextMode = false
            if (value != null) {
                val curFirstLine = activeLyricLines.firstOrNull()
                val isExitingPlaceholder =
                    curFirstLine.isTitleLine() && getSongTitle(value) == curFirstLine?.text

                if (!isExitingPlaceholder) {
                    reset()
                }

                val newSong = fillGapAtStart(value)
                var previous: RichLyricLineModel? = null
                lineModelList = newSong.lyrics?.map {
                    RichLyricLineModel(it).apply {
                        this.previous = previous
                        previous?.next = this
                        previous = this
                    }
                }
                timingNavigator = TimingNavigator(lineModelList?.toTypedArray() ?: emptyArray())

                rebuildAllLineViews()
            } else {
                reset()
                lineModelList = null
                timingNavigator = emptyTimingNavigator()
            }

            field = value
        }

    var text: String? = null
        set(value) {
            field = value
            if (!isTextMode) {
                reset(); isTextMode = true
            }
            if (value.isNullOrBlank()) {
                contentView.removeAllViews()
                return
            }

            if (!contentView.contains(textRecycleLineView)) {
                contentView.addView(textRecycleLineView, defaultLayoutParams)
                updateTextLineViewStyle(styleConfig)
            }
            val old = textRecycleLineView.line

            val line = RichLyricLine(
                text = value.lines().first(),
                translation = value.lines().getOrNull(1),
            )

            textRecycleLineView.line = line
            textRecycleLineView.post { textRecycleLineView.tryStartMarquee() }

            lyricCountChangeListeners.forEach {
                it.onLyricTextChanged(old?.text ?: "", value)
            }
        }

    private fun rebuildAllLineViews() {
        contentView.removeAllViews()
        activeLyricLines.clear()

        val models = lineModelList ?: return
        val paddingTop = if (anchorOffset > 0f) anchorOffset.toInt() else height / 2
        val paddingBottom = if (anchorOffset > 0f) anchorOffset.toInt() else height / 2
        contentView.setPadding(0, paddingTop, 0, paddingBottom)

        for (model in models) {
            val view = createDoubleLineView(model)
            contentView.addView(view, defaultLayoutParams)
            activeLyricLines.add(model)
        }

        currentHighlightIndex = -1
        Log.d("LyricDebug", "rebuildAllLineViews: ${models.size} lines, anchorOffset=$anchorOffset, height=$height")
        updateViewsVisibility()
    }

    fun setStyle(config: RichLyricLineConfig) = apply {
        this.styleConfig = config
        updateTextLineViewStyle(config)
        contentView.forEach { if (it is RichLyricLineView) it.setStyle(config) }
        updateViewsVisibility()
    }

    fun getStyle() = styleConfig

    fun setTransitionConfig(config: String?) {
        contentView.forEach { if (it is RichLyricLineView) it.setTransitionConfig(config) }
    }

    private var _transitionConfig: String? = null

    fun updateDisplayTranslation(
        displayTranslation: Boolean = isDisplayTranslation,
        displayRoma: Boolean = isDisplayRoma
    ) {
        isDisplayTranslation = displayTranslation
        isDisplayRoma = displayRoma
        contentView.forEach {
            if (it is RichLyricLineView) {
                it.displayTranslation = displayTranslation
                it.displayRoma = displayRoma
                it.notifyLineChanged()
            }
        }
        updateViewsVisibility()
    }

    fun seekTo(position: Long) {
        currentInterludeState = null
        if (isEnteringInterludeMode) {
            isEnteringInterludeMode = false
            interludeFadeAnimator?.cancel()
            interludeAlpha = 1f
            interludeScale = 1f
        }
        updatePosition(position, true)
    }

    fun setPosition(position: Long) = updatePosition(position)

    fun reset() {
        interludeFadeAnimator?.cancel()
        contentView.removeAllViews()
        activeLyricLines.clear()
        currentHighlightIndex = -1
        if (isEnteringInterludeMode) exitInterludeMode()
    }

    override fun removeAllViews() {
        contentView.removeAllViews()
    }

    override fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        styleConfig.apply {
            this.primary.textColor = primary
            secondary.textColor = primary
            syllable.highlightColor = highlight
            syllable.backgroundColor = background
        }
        contentView.forEach {
            if (it is UpdatableColor) {
                it.updateColor(primary, background, highlight)
            }
        }
    }

    private fun updatePosition(position: Long, seekTo: Boolean = false) {
        if (isTextMode) return

        val tempFound = mutableListOf<RichLyricLineModel>()
        timingNavigator.forEachAtOrPrevious(position) { tempFound.add(it) }

        val newHighlightIndex = if (tempFound.isNotEmpty()) {
            val lastMatch = tempFound.last()
            activeLyricLines.indexOf(lastMatch)
        } else -1

        val highlightChanged = newHighlightIndex != currentHighlightIndex

        if (highlightChanged) {
            Log.d("LyricDebug", "updatePosition: pos=$position, highlight: $currentHighlightIndex → $newHighlightIndex, activeLines=${activeLyricLines.size}, childCount=${contentView.childCount}")
            currentHighlightIndex = newHighlightIndex
            updateViewsVisibility()
        }

        if (highlightChanged && currentHighlightIndex >= 0) {
            val now = System.currentTimeMillis()
            if (!isUserScrolling || now > autoScrollResumeTime) {
                isUserScrolling = false
                scrollToHighlight(currentHighlightIndex, !seekTo)
            }
        }

        contentView.forEach { view ->
            if (view is RichLyricLineView) {
                if (seekTo) view.seekTo(position) else view.setPosition(position)
            }
        }

        handleInterlude(position, tempFound)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                isUserScrolling = true
                autoScrollResumeTime = Long.MAX_VALUE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                autoScrollResumeTime = System.currentTimeMillis() + AUTO_SCROLL_RESUME_DELAY
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun scrollToHighlight(index: Int, smooth: Boolean) {
        if (index < 0 || index >= contentView.childCount) return
        val targetView = contentView.getChildAt(index) ?: return

        val offset = if (anchorOffset > 0f) anchorOffset else height / 2f
        val targetScroll = (targetView.top - offset + targetView.height / 2f).toInt()

        if (smooth) {
            smoothScrollTo(0, targetScroll.coerceAtLeast(0))
        } else {
            scrollTo(0, targetScroll.coerceAtLeast(0))
        }
    }

    fun updateAnchorOffset(offset: Float) {
        anchorOffset = offset
        val paddingTop = if (anchorOffset > 0f) anchorOffset.toInt() else height / 2
        val paddingBottom = if (anchorOffset > 0f) anchorOffset.toInt() else height / 2
        contentView.setPadding(0, paddingTop, 0, paddingBottom)
    }

    fun updateViewsVisibility() {
        doUpdateViewsVisibility()
    }

    private fun doUpdateViewsVisibility() {
        val totalChildCount = contentView.childCount
        if (totalChildCount == 0) return

        val pSize = styleConfig.primary.textSize
        val sSize = styleConfig.secondary.textSize

        for (i in 0 until totalChildCount) {
            val view = contentView.getChildAt(i) as? RichLyricLineView ?: continue
            val isHighlight = i == currentHighlightIndex
            val distance = if (currentHighlightIndex >= 0) kotlin.math.abs(i - currentHighlightIndex) else Int.MAX_VALUE

            if (isHighlight) {
                view.main.visibilityIfChanged = View.VISIBLE
                view.main.setTextSize(pSize)
                view.alpha = 1f
                view.setRenderScale(1.0f)
                view.translationY = 0f

                val hasSecContent = view.secondary.lyric.let {
                    it.text.isNotBlank() || it.words.isNotEmpty()
                }
                view.secondary.visibilityIfChanged =
                    if (view.alwaysShowSecondary || hasSecContent) View.VISIBLE else View.GONE
                view.secondary.setTextSize(sSize)
            } else {
                val fadeAlpha = when {
                    currentHighlightIndex < 0 -> 0.5f
                    distance <= 1 -> 0.6f
                    distance <= 2 -> 0.45f
                    distance <= 3 -> 0.35f
                    distance <= 5 -> 0.25f
                    distance <= 8 -> 0.18f
                    else -> 0.12f
                }

                view.main.visibilityIfChanged = View.VISIBLE
                view.main.setTextSize(sSize)
                val hasSecContent = view.secondary.lyric.let {
                    it.text.isNotBlank() || it.words.isNotEmpty()
                }
                view.secondary.visibilityIfChanged =
                    if (view.alwaysShowSecondary || hasSecContent) View.VISIBLE else View.GONE
                view.secondary.setTextSize(sSize)
                view.alpha = fadeAlpha
                view.setRenderScale(1.0f)
                view.translationY = 0f
            }
        }

        invalidate()
    }

    private fun createDoubleLineView(line: IRichLyricLine) = RichLyricLineView(
        context,
        displayTranslation = isDisplayTranslation,
        displayRoma = isDisplayRoma,
        enableRelativeProgress = isEnableRelativeProgress,
        enableRelativeProgressHighlight = isEnableRelativeProgressHighlight,
    ).apply {
        this.line = line
        setStyle(styleConfig)
        setMainLyricPlayListener(mainPlayListener)
        setSecondaryLyricPlayListener(secondaryPlayListener)
        setTransitionConfig(_transitionConfig)
    }

    private fun updateTextLineViewStyle(config: RichLyricLineConfig) {
        textRecycleLineView.setStyle(config)
    }

    private fun handleInterlude(position: Long, matches: List<RichLyricLineModel>) {
        val resolved = resolveInterludeState(position, matches)
        if (currentInterludeState == resolved) return

        if (currentInterludeState != null && resolved == null) {
            currentInterludeState = null
            exitInterludeMode()
        } else if (resolved != null) {
            currentInterludeState = resolved
            val remainingDuration = resolved.end - position
            enteringInterludeMode(remainingDuration.coerceAtLeast(0L))
        }
    }

    private fun resolveInterludeState(
        pos: Long,
        matches: List<RichLyricLineModel>
    ): InterludeState? {
        currentInterludeState?.let { if (pos in (it.start + 1) until it.end) return it }

        if (matches.isEmpty()) return null
        val current = matches.last()
        val next = current.next ?: return null

        if (next.begin - current.end <= MIN_GAP_DURATION) return null
        if (pos <= current.end || pos >= next.begin) return null

        return InterludeState(current.end, next.begin)
    }

    protected open fun enteringInterludeMode(duration: Long) {
        isEnteringInterludeMode = true
        interludeFadeAnimator?.cancel()
        interludeAlpha = 1f
        interludeScale = 1f
        interludeFadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            setDuration(INTERLUDE_FADE_DURATION_MS)
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                interludeAlpha = anim.animatedValue as Float
                interludeScale = 0.85f + 0.15f * interludeAlpha
                applyInterludeState()
            }
            start()
        }
    }

    protected open fun exitInterludeMode() {
        isEnteringInterludeMode = false
        interludeFadeAnimator?.cancel()
        interludeFadeAnimator = ValueAnimator.ofFloat(interludeAlpha, 1f).apply {
            setDuration(INTERLUDE_FADE_DURATION_MS)
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                interludeAlpha = anim.animatedValue as Float
                interludeScale = 0.85f + 0.15f * interludeAlpha
                applyInterludeState()
            }
            start()
        }
    }

    private fun applyInterludeState() {
        val alpha = interludeAlpha
        val scale = interludeScale
        for (i in 0 until contentView.childCount) {
            val view = contentView.getChildAt(i) as? RichLyricLineView ?: continue
            if (view.isVisible) {
                view.alpha = alpha
                view.scaleX = scale
                view.scaleY = scale
            }
        }
    }

    @Suppress("UnnecessaryVariable")
    fun fillGapAtStart(origin: Song): Song {
        val song = origin
        val title = getSongTitle(song) ?: return song
        val lyrics = song.lyrics?.toMutableList() ?: mutableListOf()

        if (lyrics.isEmpty()) {
            val d = if (song.duration > 0) song.duration else Long.MAX_VALUE
            lyrics.add(createLyricTitleLine(d, d, title))
        } else {
            val first = lyrics.first()
            if (first.begin > 0) {
                var end = first.begin
                if (end > 1) end--
                lyrics.add(0, createLyricTitleLine(end, end, title))
            }
        }
        song.lyrics = lyrics
        return song
    }

    private fun createLyricTitleLine(end: Long, duration: Long, text: String) =
        RichLyricLine(end = end, duration = duration, text = text).apply {
            metadata = lyricMetadataOf(KEY_SONG_TITLE_LINE to "true")
        }

    private fun getSongTitle(song: Song): String? {
        val name = song.name
        val artist = song.artist

        return when (styleConfig.placeholderFormat) {
            PlaceholderFormat.NONE -> null
            PlaceholderFormat.NAME_ARTIST -> when {
                !name.isNullOrBlank() && !artist.isNullOrBlank() -> "$name - $artist"
                !name.isNullOrBlank() -> name
                else -> null
            }
            PlaceholderFormat.NAME -> name?.takeIf { it.isNotBlank() }
            else -> name?.takeIf { it.isNotBlank() }
        }
    }

    private fun emptyTimingNavigator() = TimingNavigator<RichLyricLineModel>(emptyArray())

    private data class InterludeState(val start: Long, val end: Long)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d("LyricDebug", "onAttachedToWindow: childCount=${contentView.childCount}, lineModelList=${lineModelList?.size ?: 0}")
        if (contentView.childCount == 0 && lineModelList != null) {
            rebuildAllLineViews()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d("LyricDebug", "onDetachedFromWindow: childCount=${contentView.childCount}")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val paddingTop = if (anchorOffset > 0f) anchorOffset.toInt() else h / 2
        val paddingBottom = if (anchorOffset > 0f) anchorOffset.toInt() else h / 2
        contentView.setPadding(0, paddingTop, 0, paddingBottom)
        updateViewsVisibility()
    }

    interface LyricCountChangeListener {
        fun onLyricTextChanged(old: String, new: String)
        fun onLyricChanged(news: List<IRichLyricLine>, removes: List<IRichLyricLine>)
    }
}

fun IRichLyricLine?.isTitleLine(): Boolean =
    this?.metadata?.getBoolean(LyricPlayerView.KEY_SONG_TITLE_LINE, false) == true
