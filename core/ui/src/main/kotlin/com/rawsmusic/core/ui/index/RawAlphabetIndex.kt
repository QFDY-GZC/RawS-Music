package com.rawsmusic.core.ui.widget.index

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import com.rawsmusic.core.ui.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.floor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ─────────────── 标签表 ───────────────

private val LatinLabels = ('A'..'Z').map { it.toString() } + "#"

private val JapaneseLabels = listOf(
    "あ", "か", "さ", "た", "な",
    "は", "ま", "や", "ら", "わ",
    "A", "B", "C", "D", "E", "F", "G",
    "H", "I", "J", "K", "L", "M", "N",
    "O", "P", "Q", "R", "S", "T", "U",
    "V", "W", "X", "Y", "Z", "#"
)

private val KoreanLabels = listOf(
    "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ",
    "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "#"
)

private val CyrillicLabels = listOf(
    "А", "Б", "В", "Г", "Д", "Е", "Ж", "З",
    "И", "Й", "К", "Л", "М", "Н", "О", "П",
    "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч",
    "Ш", "Щ", "Э", "Ю", "Я", "#"
)

private val GreekLabels = listOf(
    "Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ",
    "Ι", "Κ", "Λ", "Μ", "Ν", "Ξ", "Ο", "Π",
    "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω", "#"
)

private val MixedLabels: List<String> =
    LatinLabels.dropLast(1) +
        listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ") +
        listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ") +
        listOf(
            "А", "Б", "В", "Г", "Д", "Е", "Ж", "З",
            "И", "Й", "К", "Л", "М", "Н", "О", "П",
            "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч",
            "Ш", "Щ", "Э", "Ю", "Я"
        ) +
        listOf(
            "Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ",
            "Ι", "Κ", "Λ", "Μ", "Ν", "Ξ", "Ο", "Π",
            "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω"
        ) +
        "#"

// ─────────────── 模式枚举 ───────────────

enum class RawIndexMode {
    AUTO,
    MIXED,
    LATIN,
    CHINESE_PINYIN,
    JAPANESE,
    KOREAN,
    CYRILLIC,
    GREEK
}

// ─────────────── 数据 ───────────────

@Immutable
data class RawAlphabetIndexData(
    val labels: List<String>,
    val targets: Map<String, Int>,
    val mode: RawIndexMode
)

@Composable
fun <T> rememberAdaptiveAlphabetIndexData(
    items: List<T>,
    mode: RawIndexMode = RawIndexMode.AUTO,
    keySelector: (T) -> String
): RawAlphabetIndexData {
    val language = Locale.getDefault().language

    return remember(items, mode, language) {
        buildAdaptiveAlphabetIndexData(
            items = items,
            requestedMode = mode,
            locale = Locale.getDefault(),
            keySelector = keySelector
        )
    }
}

fun <T> buildAdaptiveAlphabetIndexData(
    items: List<T>,
    requestedMode: RawIndexMode = RawIndexMode.AUTO,
    locale: Locale = Locale.getDefault(),
    keySelector: (T) -> String
): RawAlphabetIndexData {
    val titles = items.map { keySelector(it) }
    val mode = if (requestedMode == RawIndexMode.AUTO) {
        detectBestIndexMode(titles, locale)
    } else {
        requestedMode
    }

    val labels = labelsForMode(mode)
    val targets = linkedMapOf<String, Int>()

    items.forEachIndexed { index, item ->
        val key = indexKeyOf(
            raw = keySelector(item),
            mode = mode
        )

        if (!targets.containsKey(key)) {
            targets[key] = index
        }
    }

    val finalLabels = labels.filter { targets.containsKey(it) }.toMutableList().apply {
        if (targets.containsKey("#") && !contains("#")) add("#")
    }

    return RawAlphabetIndexData(
        labels = finalLabels,
        targets = targets,
        mode = mode
    )
}

fun <T> List<T>.sortedForAdaptiveAlphabetIndex(
    mode: RawIndexMode = RawIndexMode.AUTO,
    keySelector: (T) -> String
): List<T> {
    val resolvedMode = if (mode == RawIndexMode.AUTO) {
        detectBestIndexMode(map { keySelector(it) }, Locale.getDefault())
    } else {
        mode
    }

    return sortedWith(
        compareBy<T> { item ->
            val key = indexKeyOf(keySelector(item), resolvedMode)
            indexSortOrder(key, resolvedMode)
        }.thenBy { item ->
            normalizeForAlphabetSort(keySelector(item), resolvedMode)
        }
    )
}

// ─────────────── Composable ───────────────

@Composable
fun RawAlphabetIndex(
    data: RawAlphabetIndexData,
    modifier: Modifier = Modifier,
    enabled: Boolean = data.targets.isNotEmpty(),
    minCellHeightDp: Float = 11.5f,
    onTopSelect: (() -> Unit)? = null,
    onSelect: (letter: String, index: Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colorScheme = MiuixTheme.colorScheme
    val isLight = colorScheme.background.luminance() > 0.5f
    val railColor = colorScheme.background.copy(alpha = if (isLight) 0.28f else 0.34f)

    val selectedBgColor = colorScheme.primary.copy(alpha = 0.16f)

    val textColor = colorScheme.onSurfaceVariantSummary.copy(
        alpha = if (isLight) 0.78f else 0.84f
    )

    val disabledColor = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.30f)
    val activeColor = colorScheme.primary

    var railHeightPx by remember { mutableIntStateOf(0) }
    var topButtonHeightPx by remember { mutableIntStateOf(0) }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var touching by remember { mutableStateOf(false) }
    var lastDispatchedLetter by remember { mutableStateOf<String?>(null) }

    val labelRailHeightPx = (railHeightPx - topButtonHeightPx).coerceAtLeast(0)

    val visibleLabels = remember(
        data.labels,
        data.targets,
        labelRailHeightPx,
        minCellHeightDp
    ) {
        val minCellPx = with(density) { minCellHeightDp.dp.toPx() }

        val maxCount = if (labelRailHeightPx <= 0) {
            data.labels.size
        } else {
            floor(labelRailHeightPx / minCellPx)
                .toInt()
                .coerceAtLeast(8)
        }

        compressIndexLabels(
            labels = data.labels,
            targets = data.targets,
            maxCount = maxCount
        )
    }

    fun selectByOffset(y: Float) {
        if (!enabled || railHeightPx <= 0 || visibleLabels.isEmpty()) return

        if (onTopSelect != null && topButtonHeightPx > 0 && y <= topButtonHeightPx) {
            selectedLetter = null
            onTopSelect()
            return
        }

        val labelY = (y - topButtonHeightPx).coerceAtLeast(0f)
        val labelHeight = labelRailHeightPx.coerceAtLeast(1).toFloat()
        val rawIndex = ((labelY / labelHeight) * visibleLabels.size)
            .toInt()
            .coerceIn(0, visibleLabels.lastIndex)

        val letter = visibleLabels[rawIndex]

        val targetIndex = resolveAlphabetTargetIndex(
            letter = letter,
            visibleLabels = visibleLabels,
            allLabels = data.labels,
            targets = data.targets
        ) ?: return

        if (selectedLetter != letter) {
            selectedLetter = letter
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        if (lastDispatchedLetter != letter) {
            lastDispatchedLetter = letter
            onSelect(letter, targetIndex)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .width(22.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.CenterEnd
    ) {
        val availableHeight = maxHeight - 8.dp

        val cellHeight = (availableHeight.value / visibleLabels.size.coerceAtLeast(1))
            .dp
            .coerceIn(9.5.dp, 15.dp)

        val fontSize = when {
            visibleLabels.size <= 14 -> 10.sp
            visibleLabels.size <= 24 -> 9.sp
            else -> 8.sp
        }

        AnimatedVisibility(
            visible = touching && selectedLetter != null,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-34).dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(activeColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = selectedLetter.orEmpty(),
                    color = if (activeColor.luminance() > 0.5f) {
                        Color.Black
                    } else {
                        Color.White
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(
            modifier = Modifier
                .width(22.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 5.dp,
                        bottomStart = 5.dp,
                        topEnd = 0.dp,
                        bottomEnd = 0.dp
                    )
                )
                .background(railColor)
                .padding(vertical = 4.dp)
                .onSizeChanged { railHeightPx = it.height }
                .pointerInput(enabled, data, visibleLabels) {
                    if (!enabled) return@pointerInput

                    detectTapGestures { offset ->
                        touching = true
                        lastDispatchedLetter = null
                        selectByOffset(offset.y)
                        touching = false
                        selectedLetter = null
                        lastDispatchedLetter = null
                    }
                }
                .pointerInput(enabled, data, visibleLabels) {
                    if (!enabled) return@pointerInput

                    detectDragGestures(
                        onDragStart = { offset ->
                            touching = true
                            lastDispatchedLetter = null
                            selectByOffset(offset.y)
                        },
                        onDrag = { change, _ ->
                            selectByOffset(change.position.y)
                            change.consume()
                        },
                        onDragEnd = {
                            touching = false
                            selectedLetter = null
                            lastDispatchedLetter = null
                        },
                        onDragCancel = {
                            touching = false
                            selectedLetter = null
                            lastDispatchedLetter = null
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (onTopSelect != null) {
                Box(
                    modifier = Modifier
                        .height(22.dp)
                        .width(22.dp)
                        .onSizeChanged { topButtonHeightPx = it.height },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_index_top),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(activeColor),
                        modifier = Modifier.size(13.dp)
                    )
                }
            } else if (topButtonHeightPx != 0) {
                topButtonHeightPx = 0
            }

            visibleLabels.forEach { letter ->
                val hasTarget = data.targets.containsKey(letter)
                val isSelected = touching && selectedLetter == letter

                Box(
                    modifier = Modifier
                        .height(cellHeight)
                        .width(22.dp)
                        .background(
                            if (isSelected) selectedBgColor
                            else Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter,
                        color = when {
                            isSelected -> activeColor
                            hasTarget -> textColor
                            else -> disabledColor
                        },
                        fontSize = fontSize,
                        fontWeight = if (isSelected) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun Color.blendWith(
    target: Color,
    fraction: Float
): Color {
    val f = fraction.coerceIn(0f, 1f)

    return Color(
        red = red + (target.red - red) * f,
        green = green + (target.green - green) * f,
        blue = blue + (target.blue - blue) * f,
        alpha = 1f
    )
}

// ─────────────── 模式检测 ───────────────

private fun labelsForMode(mode: RawIndexMode): List<String> {
    return when (mode) {
        RawIndexMode.AUTO -> LatinLabels
        RawIndexMode.MIXED -> MixedLabels
        RawIndexMode.LATIN -> LatinLabels
        RawIndexMode.CHINESE_PINYIN -> LatinLabels
        RawIndexMode.JAPANESE -> JapaneseLabels
        RawIndexMode.KOREAN -> KoreanLabels
        RawIndexMode.CYRILLIC -> CyrillicLabels
        RawIndexMode.GREEK -> GreekLabels
    }
}

private fun detectBestIndexMode(
    titles: List<String>,
    locale: Locale
): RawIndexMode {
    var latin = 0
    var han = 0
    var kana = 0
    var hangul = 0
    var cyrillic = 0
    var greek = 0

    titles.forEach { title ->
        val c = title.trim().firstOrNull { !it.isWhitespace() } ?: return@forEach

        when {
            isKana(c) -> kana++
            isHangul(c) -> hangul++
            isCyrillic(c) -> cyrillic++
            isGreek(c) -> greek++
            isHan(c) -> han++
            c in 'A'..'Z' || c in 'a'..'z' -> latin++
        }
    }

    val usedScriptCount = listOf(
        latin + han,
        kana,
        hangul,
        cyrillic,
        greek
    ).count { it > 0 }

    if (usedScriptCount >= 2) {
        return RawIndexMode.MIXED
    }

    val language = locale.language.lowercase()

    return when {
        kana > 0 && language == "ja" -> RawIndexMode.JAPANESE
        hangul > 0 -> RawIndexMode.KOREAN
        cyrillic > 0 -> RawIndexMode.CYRILLIC
        greek > 0 -> RawIndexMode.GREEK
        han > 0 -> RawIndexMode.CHINESE_PINYIN
        else -> RawIndexMode.LATIN
    }
}

// ─────────────── Key 生成 ───────────────

fun indexKeyOf(
    raw: String?,
    mode: RawIndexMode
): String {
    val source = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return "#"

    val first = source.firstOrNull { it.isLetterOrDigit() } ?: return "#"

    return when (mode) {
        RawIndexMode.MIXED -> mixedIndexKey(source)

        RawIndexMode.AUTO,
        RawIndexMode.LATIN,
        RawIndexMode.CHINESE_PINYIN -> latinOrPinyinKey(source)

        RawIndexMode.JAPANESE -> japaneseIndexKey(source)

        RawIndexMode.KOREAN -> koreanIndexKey(first)

        RawIndexMode.CYRILLIC -> cyrillicIndexKey(first)

        RawIndexMode.GREEK -> greekIndexKey(first)
    }
}

private fun mixedIndexKey(source: String): String {
    val first = source.firstOrNull { it.isLetterOrDigit() } ?: return "#"

    return when {
        first in 'A'..'Z' || first in 'a'..'z' -> {
            first.uppercaseChar().toString()
        }

        first.isDigit() -> "#"

        isHan(first) -> {
            // 中文统一走拼音 A-Z
            latinOrPinyinKey(source)
        }

        isKana(first) -> {
            kanaRow(first)
        }

        isHangul(first) -> {
            koreanIndexKey(first)
        }

        isCyrillic(first) -> {
            cyrillicIndexKey(first)
        }

        isGreek(first) -> {
            greekIndexKey(first)
        }

        else -> "#"
    }
}

private fun latinOrPinyinKey(source: String): String {
    val normalized = normalizeForAlphabetSort(source, RawIndexMode.CHINESE_PINYIN)
    val first = normalized.firstOrNull { it.isLetterOrDigit() } ?: return "#"

    if (first.isDigit()) return "#"

    val upper = first.uppercaseChar()
    return if (upper in 'A'..'Z') upper.toString() else "#"
}

private fun japaneseIndexKey(source: String): String {
    val first = source.firstOrNull { it.isLetterOrDigit() } ?: return "#"

    if (first in 'A'..'Z' || first in 'a'..'z') {
        return first.uppercaseChar().toString()
    }

    if (first.isDigit()) return "#"

    if (isKana(first)) {
        return kanaRow(first)
    }

    if (isHan(first)) {
        // 没有日文读音词典，汉字归 #
        return "#"
    }

    return "#"
}

private fun koreanIndexKey(first: Char): String {
    if (first in 'A'..'Z' || first in 'a'..'z') {
        return first.uppercaseChar().toString()
    }

    if (first.isDigit()) return "#"

    if (!isHangul(first)) return "#"

    val code = first.code - 0xAC00
    if (code !in 0 until 11172) return "#"

    val choseong = code / (21 * 28)

    return when (choseong) {
        0 -> "ㄱ"
        2 -> "ㄴ"
        3 -> "ㄷ"
        5 -> "ㄹ"
        6 -> "ㅁ"
        7 -> "ㅂ"
        9 -> "ㅅ"
        11 -> "ㅇ"
        12 -> "ㅈ"
        14 -> "ㅊ"
        15 -> "ㅋ"
        16 -> "ㅌ"
        17 -> "ㅍ"
        18 -> "ㅎ"
        else -> "#"
    }
}

private fun cyrillicIndexKey(first: Char): String {
    val upper = first.uppercaseChar()

    if (upper in 'A'..'Z') return upper.toString()
    if (first.isDigit()) return "#"

    return when (upper) {
        'Ё' -> "Е"
        in 'А'..'Я' -> upper.toString()
        else -> "#"
    }
}

private fun greekIndexKey(first: Char): String {
    val upper = first.uppercaseChar()

    if (upper in 'A'..'Z') return upper.toString()
    if (first.isDigit()) return "#"

    return when (upper) {
        'Ά' -> "Α"
        'Έ' -> "Ε"
        'Ή' -> "Η"
        'Ί', 'Ϊ' -> "Ι"
        'Ό' -> "Ο"
        'Ύ', 'Ϋ' -> "Υ"
        'Ώ' -> "Ω"
        in 'Α'..'Ω' -> upper.toString()
        else -> "#"
    }
}

// ─────────────── 假名行归类 ───────────────

private fun kanaRow(c: Char): String {
    val h = katakanaToHiragana(c)

    return when (h) {
        in "あいうえおぁぃぅぇぉ" -> "あ"
        in "かきくけこがぎぐげご" -> "か"
        in "さしすせそざじずぜぞ" -> "さ"
        in "たちつてとだぢづでどっ" -> "た"
        in "なにぬねの" -> "な"
        in "はひふへほばびぶべぼぱぴぷぺぽ" -> "は"
        in "まみむめも" -> "ま"
        in "やゆよゃゅょ" -> "や"
        in "らりるれろ" -> "ら"
        in "わをん" -> "わ"
        else -> "#"
    }
}

private fun katakanaToHiragana(c: Char): Char {
    return if (c.code in 0x30A1..0x30F6) {
        (c.code - 0x60).toChar()
    } else {
        c
    }
}

// ─────────────── 排序 & 压缩 ───────────────

private fun normalizeForAlphabetSort(
    raw: String,
    mode: RawIndexMode
): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "#"

    val first = trimmed.first()

    if (
        first in 'A'..'Z' ||
        first in 'a'..'z' ||
        first.isDigit() ||
        mode == RawIndexMode.KOREAN ||
        mode == RawIndexMode.CYRILLIC ||
        mode == RawIndexMode.GREEK ||
        mode == RawIndexMode.JAPANESE
    ) {
        return trimmed.uppercase()
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        runCatching {
            android.icu.text.Transliterator
                .getInstance("Han-Latin; Latin-ASCII")
                .transliterate(trimmed)
                .uppercase()
        }.getOrDefault(trimmed.uppercase())
    } else {
        trimmed.uppercase()
    }
}

private fun indexSortOrder(
    key: String,
    mode: RawIndexMode
): String {
    val labels = labelsForMode(mode)
    val index = labels.indexOf(key)

    return if (index >= 0) {
        index.toString().padStart(3, '0')
    } else {
        "999"
    }
}

private fun compressIndexLabels(
    labels: List<String>,
    targets: Map<String, Int>,
    maxCount: Int
): List<String> {
    val hitLabels = labels.filter { targets.containsKey(it) }.toMutableList()
    if (targets.containsKey("#") && !hitLabels.contains("#")) hitLabels.add("#")
    if (hitLabels.isEmpty()) return emptyList()
    if (hitLabels.size <= maxCount) return hitLabels

    val reserveHash = hitLabels.contains("#")
    val ordinaryLabels = hitLabels.filterNot { it == "#" }
    val ordinarySlots = (maxCount - if (reserveHash) 1 else 0).coerceAtLeast(1)
    val step = ordinaryLabels.size.toFloat() / ordinarySlots.toFloat()
    val compressed = buildList {
        var cursor = 0f
        while (size < ordinarySlots && cursor < ordinaryLabels.size) {
            val label = ordinaryLabels[cursor.toInt().coerceIn(0, ordinaryLabels.lastIndex)]
            if (!contains(label)) add(label)
            cursor += step
        }
    }
    return if (reserveHash) compressed + "#" else compressed
}

private fun resolveAlphabetTargetIndex(
    letter: String,
    visibleLabels: List<String>,
    allLabels: List<String>,
    targets: Map<String, Int>
): Int? {
    targets[letter]?.let { return it }

    val currentInAll = allLabels.indexOf(letter)
    if (currentInAll >= 0) {
        for (i in currentInAll + 1 until allLabels.size) {
            targets[allLabels[i]]?.let { return it }
        }

        for (i in currentInAll - 1 downTo 0) {
            targets[allLabels[i]]?.let { return it }
        }
    }

    val currentVisible = visibleLabels.indexOf(letter)
    if (currentVisible >= 0) {
        for (i in currentVisible + 1 until visibleLabels.size) {
            targets[visibleLabels[i]]?.let { return it }
        }

        for (i in currentVisible - 1 downTo 0) {
            targets[visibleLabels[i]]?.let { return it }
        }
    }

    return null
}

// ─────────────── 字符检测 ───────────────

private fun isHan(c: Char): Boolean {
    return c.code in 0x4E00..0x9FFF ||
        c.code in 0x3400..0x4DBF
}

private fun isKana(c: Char): Boolean {
    return c.code in 0x3040..0x309F ||
        c.code in 0x30A0..0x30FF
}

private fun isHangul(c: Char): Boolean {
    return c.code in 0xAC00..0xD7AF ||
        c.code in 0x1100..0x11FF ||
        c.code in 0x3130..0x318F
}

private fun isCyrillic(c: Char): Boolean {
    return c.code in 0x0400..0x04FF
}

private fun isGreek(c: Char): Boolean {
    return c.code in 0x0370..0x03FF
}
