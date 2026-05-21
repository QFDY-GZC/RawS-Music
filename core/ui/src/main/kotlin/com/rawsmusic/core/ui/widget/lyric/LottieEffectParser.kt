package com.rawsmusic.core.ui.widget.lyric

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 工业级 Lottie 歌词动画效果解析器
 * 严格遵循 Lottie/Bodymovin 规范，完全由 JSON 数据驱动
 */
class LottieEffectParser {

    companion object {
        private const val TAG = "LottieEffectParser"
        private const val DEFAULT_FPS = 24f
        private const val DEFAULT_DESIGN_WIDTH = 1080f
        private const val DEFAULT_DESIGN_HEIGHT = 2700f

        /**
         * 从分层 assets 结构加载 Lottie 动画配置
         * 支持 input.json + layer1/data.json + layer2/data.json 的结构
         */
        fun loadFromInputAssets(context: Context, inputAssetPath: String): LyricAnimationConfig? {
            return try {
                // 1. 加载 input.json
                val inputJsonStr = context.assets.open(inputAssetPath).bufferedReader().use { it.readText() }
                val inputJson = JSONObject(inputJsonStr)

                val fps = inputJson.optInt("fr", 24).toFloat()
                val designWidth = inputJson.optInt("w", 1080).toFloat()
                val designHeight = inputJson.optInt("h", 2700).toFloat()

                // 2. 遍历 layers，找到 lyric 层
                val layersArray = inputJson.optJSONArray("layers") ?: return null
                val lyricLayerPaths = mutableListOf<String>()

                for (i in 0 until layersArray.length()) {
                    val layerObj = layersArray.optJSONObject(i) ?: continue
                    val lyric = layerObj.optInt("lyric", -1)
                    if (lyric == 1) {
                        val path = layerObj.optString("path", "")
                        val jsonName = layerObj.optString("jsonName", "data.json")
                        val relativePath = if (path.endsWith("/")) "$path$jsonName" else "$path/$jsonName"
                        lyricLayerPaths.add(relativePath)
                    }
                }

                if (lyricLayerPaths.isEmpty()) {
                    Log.w(TAG, "未找到歌词层 (lyric=1)")
                    return null
                }

                // 3. 解析每个歌词层
                val inputDir = inputAssetPath.substringBeforeLast("/")
                val lineConfigs = mutableListOf<LineConfig>()

                for (relativePath in lyricLayerPaths) {
                    val lyricLayerAssetPath = if (inputDir.isEmpty()) relativePath else "$inputDir/$relativePath"
                    Log.i(TAG, "加载歌词层: $lyricLayerAssetPath")

                    val lyricJsonStr = context.assets.open(lyricLayerAssetPath).bufferedReader().use { it.readText() }
                    val lyricJson = JSONObject(lyricJsonStr)

                    val parser = LottieEffectParser()
                    val lineConfig = parser.parseLayer(lyricJson, fps, designWidth, designHeight)
                    if (lineConfig != null) {
                        lineConfigs.add(lineConfig)
                    }
                }

                if (lineConfigs.isEmpty()) {
                    Log.w(TAG, "没有成功解析任何歌词层")
                    return null
                }

                return LyricAnimationConfig(
                    fps = fps,
                    designWidth = designWidth,
                    designHeight = designHeight,
                    layers = lineConfigs
                )
            } catch (e: Exception) {
                Log.e(TAG, "加载失败", e)
                null
            }
        }
    }

    /**
     * 解析单个 Lottie 图层 JSON 为 LineConfig
     */
    fun parseLayer(json: JSONObject, fps: Float, designWidth: Float, designHeight: Float): LineConfig? {
        return try {
            val ks = json.optJSONObject("ks") ?: return null

            // 解析图层级属性
            val positionProp = parseProperty(ks.opt("p"))
            val scaleProp = parseProperty(ks.opt("s"))
            val opacityProp = parseProperty(ks.opt("o"))
            val anchorProp = parseProperty(ks.opt("a"))

            // 解析文字动画器
            val tObj = json.optJSONObject("t")
            val animators = tObj?.optJSONArray("a")

            var rangeOffsetProp: ParsedProperty? = null
            var rangeRandomize = false
            var charPositionProp: ParsedProperty? = null
            var charScaleProp: ParsedProperty? = null
            var charOpacityProp: ParsedProperty? = null
            var charBlurProp: ParsedProperty? = null

            if (animators != null && animators.length() > 0) {
                val animator = animators.optJSONObject(0)
                if (animator != null) {
                    // Range Selector
                    val sObj = animator.optJSONObject("s")
                    if (sObj != null) {
                        rangeRandomize = sObj.optInt("rn", 0) == 1
                        val offsetObj = sObj.opt("o")
                        rangeOffsetProp = parseProperty(offsetObj)
                    }

                    // 逐字属性
                    val aObj = animator.optJSONObject("a")
                    if (aObj != null) {
                        charPositionProp = parseProperty(aObj.opt("p"))
                        charScaleProp = parseProperty(aObj.opt("s"))
                        charOpacityProp = parseProperty(aObj.opt("o"))
                        charBlurProp = parseProperty(aObj.opt("bl"))
                    }
                }
            }

            // 转换为关键帧数组
            val positionKfs = convertToKeyframes(positionProp)
            val scaleKfs = convertToKeyframes(scaleProp)
            val opacityKfs = convertToKeyframes(opacityProp)
            val anchorArr = extractStaticValue(anchorProp, floatArrayOf(0f, 0f, 0f))
            val rangeOffsetKfs = convertToKeyframes(rangeOffsetProp)
            val charPositionKfs = convertToKeyframes(charPositionProp)
            val charScaleKfs = convertToKeyframes(charScaleProp)
            val charOpacityKfs = convertToKeyframes(charOpacityProp)
            val charBlurKfs = convertToKeyframes(charBlurProp)

            LineConfig(
                anchor = anchorArr,
                positionKfs = positionKfs,
                scaleKfs = scaleKfs,
                opacityKfs = opacityKfs,
                rangeOffsetKfs = rangeOffsetKfs,
                rangeRandomize = rangeRandomize,
                charPositionKfs = charPositionKfs,
                charScaleKfs = charScaleKfs,
                charOpacityKfs = charOpacityKfs,
                charBlurKfs = charBlurKfs,
                designWidth = designWidth,
                designHeight = designHeight
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析图层失败", e)
            null
        }
    }

    /**
     * 解析 Lottie 属性（支持静态和动画）
     */
    private fun parseProperty(obj: Any?): ParsedProperty? {
        if (obj == null) return null

        return when (obj) {
            is JSONObject -> {
                val animated = obj.optInt("a", 0) == 1
                val kObj = obj.opt("k")

                if (!animated) {
                    // 静态属性
                    val value = extractFloatArray(kObj) ?: return null
                    ParsedProperty.Static(value)
                } else {
                    // 动画属性
                    val kfArray = kObj as? org.json.JSONArray ?: return null
                    val rawKeyframes = mutableListOf<RawKeyframe>()

                    for (i in 0 until kfArray.length()) {
                        val kfObj = kfArray.optJSONObject(i) ?: continue
                        val t = kfObj.optDouble("t", 0.0).toFloat()
                        val s = extractFloatArray(kfObj.opt("s"))

                        // 提取切线 (Lottie 规范：o 是出口切线，i 是入口切线)
                        val oTangent = extractTangent(kfObj.optJSONObject("o"))
                        val iTangent = extractTangent(kfObj.optJSONObject("i"))

                        rawKeyframes.add(RawKeyframe(t, s, oTangent, iTangent))
                    }
                    if (rawKeyframes.isEmpty()) return null
                    ParsedProperty.Animated(rawKeyframes)
                }
            }
            is org.json.JSONArray -> {
                // 直接是数组（静态值）
                val value = extractFloatArray(obj)
                if (value != null) ParsedProperty.Static(value) else null
            }
            else -> null
        }
    }

    private fun extractFloatArray(obj: Any?): FloatArray? {
        return when (obj) {
            is org.json.JSONArray -> {
                val result = FloatArray(obj.length())
                for (i in result.indices) result[i] = obj.optDouble(i, 0.0).toFloat()
                result
            }
            is Number -> floatArrayOf(obj.toFloat())
            else -> null
        }
    }

    /**
     * 提取切线，返回 [cpX, cpY]
     * Lottie 切线可能是对象 {x: [...], y: [...]}，也可能是单一数字
     */
    private fun extractTangent(tangentObj: JSONObject?): FloatArray {
        if (tangentObj == null) return floatArrayOf(0.167f, 0.167f) // 默认线性近似
        val xArr = tangentObj.optJSONArray("x")
        val yArr = tangentObj.optJSONArray("y")
        val x = xArr?.optDouble(0, 0.167)?.toFloat() ?: tangentObj.optDouble("x", 0.167).toFloat()
        val y = yArr?.optDouble(0, 0.167)?.toFloat() ?: tangentObj.optDouble("y", 0.167).toFloat()
        return floatArrayOf(x, y)
    }

    private fun extractStaticValue(prop: ParsedProperty?, default: FloatArray): FloatArray {
        return if (prop is ParsedProperty.Static) prop.value else default
    }

    /**
     * 将内部解析的 RawKeyframe 转换为 LottieMathEngine 需要的标准关键帧
     * Lottie 规范：当前帧的 s 是起始值，下一个帧的 s 是当前帧的结束值。
     */
    private fun convertToKeyframes(prop: ParsedProperty?): List<LottieKeyframe> {
        if (prop !is ParsedProperty.Animated) return emptyList()
        val rawKfs = prop.keyframes
        val result = mutableListOf<LottieKeyframe>()

        for (i in rawKfs.indices) {
            val currentKf = rawKfs[i]
            // 结束值取下一帧的 s，如果下一帧没有 s，则取当前帧的 s (兼容处理)
            val endValue = if (i < rawKfs.size - 1) {
                rawKfs[i + 1].s ?: currentKf.s ?: floatArrayOf()
            } else {
                currentKf.s ?: floatArrayOf()
            }

            result.add(LottieKeyframe(
                t = currentKf.t,
                s = currentKf.s ?: floatArrayOf(),
                o = currentKf.o, // 当前帧的出口切线 cp1
                i = currentKf.i  // 当前帧的入口切线 cp2
            ))
        }
        return result
    }
}

/**
 * 解析后的属性（静态或动画）
 */
private sealed class ParsedProperty {
    data class Static(val value: FloatArray) : ParsedProperty()
    data class Animated(val keyframes: List<RawKeyframe>) : ParsedProperty()
}

/**
 * 原始关键帧数据
 */
private data class RawKeyframe(
    val t: Float,
    val s: FloatArray?,
    val o: FloatArray,
    val i: FloatArray
)
