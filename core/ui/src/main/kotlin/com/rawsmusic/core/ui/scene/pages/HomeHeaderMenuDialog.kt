package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomeHeaderSettingsSection(
    options: HomeHeaderOptionsState,
) {
    val scheme = MiuixTheme.colorScheme
    Text(
        text = stringResource(R.string.side_rail_home_settings_title),
        color = scheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
    HomeHeaderToggleRow(
        title = stringResource(R.string.home_header_weather_title),
        summary = stringResource(R.string.home_header_weather_summary),
        checked = options.weatherVisible,
        onCheckedChange = options::updateWeatherVisible,
    )
    Spacer(Modifier.height(7.dp))
    HomeHeaderToggleRow(
        title = stringResource(R.string.home_header_carousel_lyric_title),
        summary = stringResource(R.string.home_header_carousel_lyric_summary),
        checked = options.carouselLyricVisible,
        onCheckedChange = options::updateCarouselLyricVisible,
    )
    Spacer(Modifier.height(7.dp))
    val styleDropdown = DropdownEntry(
        items = HomeArtworkCarouselStyle.entries.map { style ->
            DropdownItem(
                text = stringResource(style.titleRes()),
                summary = stringResource(style.summaryRes()),
                selected = style == options.carouselStyle,
                onClick = { options.updateCarouselStyle(style) },
            )
        },
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.82f)),
    ) {
        RawWindowDropdownPreference(
            entry = styleDropdown,
            title = stringResource(R.string.home_header_artwork_style_title),
            summary = null,
            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            showValue = true,
            maxHeight = 220.dp,
            collapseOnSelection = false,
        )
    }
}

@Composable
private fun HomeHeaderToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.82f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                color = scheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = summary,
                color = scheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
                maxLines = 2,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun HomeArtworkCarouselStyle.titleRes(): Int = when (this) {
    HomeArtworkCarouselStyle.CurrentCarousel -> R.string.home_header_artwork_style_current
    HomeArtworkCarouselStyle.VerticalDial -> R.string.home_header_artwork_style_dial
}

private fun HomeArtworkCarouselStyle.summaryRes(): Int = when (this) {
    HomeArtworkCarouselStyle.CurrentCarousel -> R.string.home_header_artwork_style_current_summary
    HomeArtworkCarouselStyle.VerticalDial -> R.string.home_header_artwork_style_dial_summary
}

@Composable
internal fun HomeHeaderMenuDialog(
    show: Boolean,
    weatherVisible: Boolean,
    onWeatherVisibleChange: (Boolean) -> Unit,
    carouselLyricVisible: Boolean,
    onCarouselLyricVisibleChange: (Boolean) -> Unit,
    carouselStyle: HomeArtworkCarouselStyle,
    onCarouselStyleChange: (HomeArtworkCarouselStyle) -> Unit,
    onDismissRequest: () -> Unit
) {
    RawMiuixOverlayDialog(
        show = show,
        title = stringResource(R.string.home_header_menu_title),
        summary = stringResource(R.string.home_header_menu_summary),
        onDismissRequest = onDismissRequest,
        renderInRootScaffold = true
    ) {
        val scheme = MiuixTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.45f))
                .clickable { onWeatherVisibleChange(!weatherVisible) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_header_weather_title),
                    color = scheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.home_header_weather_summary),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 13.sp
                )
            }
            Switch(
                checked = weatherVisible,
                onCheckedChange = onWeatherVisibleChange
            )
        }


        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.45f))
                .clickable { onCarouselLyricVisibleChange(!carouselLyricVisible) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_header_carousel_lyric_title),
                    color = scheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.home_header_carousel_lyric_summary),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 13.sp
                )
            }
            Switch(
                checked = carouselLyricVisible,
                onCheckedChange = onCarouselLyricVisibleChange
            )
        }

        Spacer(Modifier.height(16.dp))

        val carouselStyleDropdown = DropdownEntry(
            items = HomeArtworkCarouselStyle.entries.map { style ->
                DropdownItem(
                    text = stringResource(style.titleRes()),
                    summary = stringResource(style.summaryRes()),
                    selected = style == carouselStyle,
                    onClick = { onCarouselStyleChange(style) }
                )
            }
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.45f))
        ) {
            RawWindowDropdownPreference(
                entry = carouselStyleDropdown,
                title = stringResource(R.string.home_header_artwork_style_title),
                summary = null,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                showValue = true,
                maxHeight = 260.dp,
                collapseOnSelection = true
            )
        }
    }
}
