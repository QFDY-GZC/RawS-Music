package com.rawsmusic.core.ui.scene.pages

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.ui.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PROJECT_URL = "https://github.com/QFDY-GZC/RawS-Music"
private const val HALCYON_URL = "https://github.com/Kifranei/Halcyon"
private const val QQ_GROUP_URL = "https://qm.qq.com/q/bOvqTQPABi"

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val versionName = remember(context) { context.rawSMusicVersionName() }
    val listState = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val heroTravelPx = with(density) { 180.dp.toPx() }
    val scrollProgress by remember(listState, heroTravelPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / heroTravelPx).coerceIn(0f, 1f)
        }
    }
    val blurEnabled = remember { isRenderEffectSupported() }
    val titleBlend = remember(isDark) { aboutTitleBlendColors(isDark) }
    val cardBlend = remember(isDark) { aboutCardBlendColors(isDark) }

    AboutDynamicBackground(
        modifier = Modifier.fillMaxSize(),
        backgroundModifier = Modifier.layerBackdrop(backdrop)
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = (1f - scrollProgress * 1.35f).coerceIn(0f, 1f)
                    translationY = -heroTravelPx * 0.55f * scrollProgress
                }
                .padding(top = 148.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.about_app_name),
                color = scheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
                modifier = Modifier
                    .padding(bottom = 5.dp)
                    .then(
                        if (blurEnabled) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 150f,
                                noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                colors = BlurColors(blendColors = titleBlend),
                                contentBlendMode = BlendMode.DstIn,
                                enabled = true
                            )
                        } else Modifier
                    )
            )
            Text(
                text = stringResource(R.string.about_version_format, versionName),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.about_tagline),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, start = 28.dp, end = 28.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 360.dp)
        ) {
            item {
                SmallTitle(text = stringResource(R.string.about_project_section))
                AboutFrostedCard(backdrop, blurEnabled, cardBlend, scrollProgress) {
                    BasicComponent(
                        title = stringResource(R.string.about_project_home),
                        summary = PROJECT_URL,
                        onClick = { uriHandler.openUri(PROJECT_URL) }
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_open_source_license),
                        summary = "Apache-2.0",
                        onClick = { uriHandler.openUri("https://www.apache.org/licenses/LICENSE-2.0") }
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_qq_group),
                        summary = stringResource(R.string.about_qq_group_summary),
                        onClick = { uriHandler.openUri(QQ_GROUP_URL) }
                    )
                }
            }

            item {
                SmallTitle(text = stringResource(R.string.about_open_source_projects))
                AboutFrostedCard(backdrop, blurEnabled, cardBlend, scrollProgress) {
                    AboutLibrary("Halcyon", R.string.about_halcyon_summary, HALCYON_URL)
                    AboutLibrary("AndroidLiquidGlass", R.string.about_android_liquid_glass_summary, "https://github.com/Kyant0/AndroidLiquidGlass")
                    AboutLibrary("Miuix", R.string.about_miuix_summary, "https://github.com/compose-miuix-ui/miuix")
                    AboutLibrary("FFmpeg", R.string.about_ffmpeg_summary, "https://ffmpeg.org")
                    AboutLibrary("libusb", R.string.about_libusb_summary, "https://github.com/libusb/libusb")
                    AboutLibrary("TagLib", R.string.about_taglib_summary, "https://taglib.org")
                    AboutLibrary("Coil", R.string.about_coil_summary, "https://github.com/coil-kt/coil")
                    AboutLibrary("Lyricon", R.string.about_lyricon_summary, "https://github.com/proify/lyricon")
                    AboutLibrary("AndroidX / Kotlin Coroutines", R.string.about_androidx_summary, "https://developer.android.com/jetpack/androidx")
                }
            }

            item {
                Spacer(Modifier.height(160.dp).navigationBarsPadding())
            }
        }

        SmallTopAppBar(
            title = stringResource(R.string.about_title),
            color = scheme.surface.copy(alpha = scrollProgress),
            titleColor = scheme.onSurface.copy(alpha = scrollProgress),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = stringResource(R.string.settings_back),
                        tint = scheme.onSurface
                    )
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).zIndex(10f)
        )
    }
}

@Composable
private fun AboutLibrary(name: String, summaryRes: Int, url: String) {
    val uriHandler = LocalUriHandler.current
    BasicComponent(
        title = name,
        summary = stringResource(summaryRes),
        onClick = { uriHandler.openUri(url) }
    )
}

@Composable
private fun AboutFrostedCard(
    backdrop: LayerBackdrop,
    blurEnabled: Boolean,
    blendColors: List<BlendColorEntry>,
    scrollProgress: Float,
    content: @Composable () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .then(
                if (blurEnabled) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(16.dp),
                        blurRadius = if (isDark) 72f else 64f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = BlurColors(blendColors = blendColors),
                        enabled = true
                    )
                } else Modifier
            ),
        colors = CardDefaults.defaultColors(
            color = if (blurEnabled) Color.Transparent else if (isDark) {
                Color(0xFF252528).copy(alpha = 0.86f + 0.08f * scrollProgress)
            } else {
                scheme.surfaceContainer
            },
            contentColor = scheme.onSurface
        )
    ) {
        content()
    }
}

private fun aboutTitleBlendColors(isDark: Boolean): List<BlendColorEntry> =
    if (isDark) {
        listOf(
            BlendColorEntry(Color(0xE6A1A1A1), BlurBlendMode.ColorDodge),
            BlendColorEntry(Color(0x4DE6E6E6), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xFF1AF500), BlurBlendMode.Lab)
        )
    } else {
        listOf(
            BlendColorEntry(Color(0xCC4A4A4A), BlurBlendMode.ColorBurn),
            BlendColorEntry(Color(0xFF4F4F4F), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xFF1AF200), BlurBlendMode.Lab)
        )
    }

private fun aboutCardBlendColors(isDark: Boolean): List<BlendColorEntry> =
    if (isDark) {
        listOf(BlendColorEntry(Color(0x757A7A7A), BlurBlendMode.Luminosity))
    } else {
        listOf(
            BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
            BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight)
        )
    }

private fun Context.rawSMusicVersionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
}.getOrDefault("-")
