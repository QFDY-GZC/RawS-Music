package com.rawsmusic.ui.settings

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.R

class RewardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        setContent {
            RewardAuthorScreen()
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(R.anim.settings_enter_from_left, R.anim.settings_exit_to_right)
        }
    }
}

private data class RewardImageSpec(
    val assetName: String,
    val saveFileName: String,
    val contentDescription: String,
    val aspectRatio: Float
)

private data class RewardImageData(
    val spec: RewardImageSpec,
    val originalBytes: ByteArray,
    val bitmap: ImageBitmap
)

private val REWARD_IMAGE_SPECS = listOf(
    RewardImageSpec(
        assetName = "reward_wechat.b64",
        saveFileName = "RawSMusic_WeChat_Reward.webp",
        contentDescription = "微信赞赏码",
        aspectRatio = 1f
    ),
    RewardImageSpec(
        assetName = "reward_alipay.b64",
        saveFileName = "RawSMusic_Alipay_Reward.webp",
        contentDescription = "支付宝赞赏码",
        aspectRatio = 2f / 3f
    )
)

@Composable
private fun RewardAuthorScreen() {
    val context = LocalContext.current
    val images = remember(context) {
        REWARD_IMAGE_SPECS.mapNotNull { context.loadRewardImage(it) }
    }
    var previewImage by remember { mutableStateOf<RewardImageData?>(null) }
    var pendingSave by remember { mutableStateOf<RewardImageData?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        val image = pendingSave
        if (uri != null && image != null) {
            val saved = context.writeRewardImage(uri, image.originalBytes)
            Toast.makeText(
                context,
                if (saved) "图片已保存" else "保存失败，请重试",
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingSave = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))

        images.forEachIndexed { index, image ->
            Image(
                bitmap = image.bitmap,
                contentDescription = image.spec.contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .aspectRatio(image.spec.aspectRatio)
                    .pointerInput(image.spec.assetName) {
                        detectTapGestures(
                            onLongPress = { previewImage = image }
                        )
                    }
            )
            if (index != images.lastIndex) {
                Spacer(Modifier.height(22.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "您的支持，是我最大的动力。\n" +
                "感谢每一份心意，也感谢你愿意陪 RawS Music 一起变得更好。\n" +
                "长按任一赞赏码可放大预览并保存。",
            color = Color(0xFF8A8A8A),
            fontSize = 12.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 22.dp)
        )
        Spacer(Modifier.height(28.dp))
    }

    previewImage?.let { image ->
        RewardImagePreview(
            image = image,
            onDismiss = { previewImage = null },
            onSave = {
                pendingSave = image
                saveLauncher.launch(image.spec.saveFileName)
            }
        )
    }
}

@Composable
private fun RewardImagePreview(
    image: RewardImageData,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var scale by remember(image.spec.assetName) { mutableFloatStateOf(1f) }
    var offset by remember(image.spec.assetName) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Image(
                bitmap = image.bitmap,
                contentDescription = image.spec.contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 66.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(image.spec.assetName) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = nextScale
                            offset = if (nextScale <= 1.001f) {
                                Offset.Zero
                            } else {
                                offset + pan
                            }
                        }
                    }
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
                ) {
                    Text("关闭")
                }
                TextButton(
                    onClick = onSave,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
                ) {
                    Text("保存图片")
                }
            }
        }
    }
}

private fun Context.loadRewardImage(spec: RewardImageSpec): RewardImageData? = runCatching {
    val encoded = assets.open(spec.assetName).bufferedReader().use { it.readText() }
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    RewardImageData(
        spec = spec,
        originalBytes = bytes,
        bitmap = decoded.asImageBitmap()
    )
}.getOrNull()

private fun Context.writeRewardImage(uri: Uri, bytes: ByteArray): Boolean = runCatching {
    contentResolver.openOutputStream(uri, "w")?.use { output ->
        output.write(bytes)
        output.flush()
    } ?: error("Unable to open destination")
}.isSuccess
