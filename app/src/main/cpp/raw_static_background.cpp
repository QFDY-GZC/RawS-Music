#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

struct Pixel {
    float r;
    float g;
    float b;
};

Pixel unpack(uint32_t color) {
    return {
        static_cast<float>((color >> 16) & 0xff) / 255.0f,
        static_cast<float>((color >> 8) & 0xff) / 255.0f,
        static_cast<float>(color & 0xff) / 255.0f
    };
}

Pixel mix(const Pixel& a, const Pixel& b, float t) {
    return {
        a.r + (b.r - a.r) * t,
        a.g + (b.g - a.g) * t,
        a.b + (b.b - a.b) * t
    };
}

Pixel sampleBilinear(
        const std::vector<Pixel>& pixels,
        int width,
        int height,
        float x,
        float y) {
    const float clamped_x = std::clamp(x, 0.0f, static_cast<float>(width - 1));
    const float clamped_y = std::clamp(y, 0.0f, static_cast<float>(height - 1));
    const int x0 = static_cast<int>(std::floor(clamped_x));
    const int y0 = static_cast<int>(std::floor(clamped_y));
    const int x1 = std::min(x0 + 1, width - 1);
    const int y1 = std::min(y0 + 1, height - 1);
    const float tx = clamped_x - x0;
    const float ty = clamped_y - y0;
    return mix(
        mix(pixels[static_cast<size_t>(y0) * width + x0],
            pixels[static_cast<size_t>(y0) * width + x1], tx),
        mix(pixels[static_cast<size_t>(y1) * width + x0],
            pixels[static_cast<size_t>(y1) * width + x1], tx),
        ty);
}

float luma(const Pixel& value) {
    return value.r * 0.2126f + value.g * 0.7152f + value.b * 0.0722f;
}

float chroma(const Pixel& value) {
    return std::max({value.r, value.g, value.b}) - std::min({value.r, value.g, value.b});
}

float smoothstep(float edge0, float edge1, float value) {
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

Pixel suppressWhiteHighlight(const Pixel& value, const Pixel& anchor) {
    const float value_luma = luma(value);
    const float value_chroma = chroma(value);
    if (value_luma < 0.68f || value_chroma > 0.20f) return value;
    const float white_amount = smoothstep(0.68f, 0.94f, value_luma)
        * (1.0f - smoothstep(0.06f, 0.20f, value_chroma));
    return mix(value, anchor, 0.72f * white_amount);
}

Pixel tune(Pixel value, float saturation, float brightness, float texture) {
    const float luma = value.r * 0.2126f + value.g * 0.7152f + value.b * 0.0722f;
    value.r = (luma + (value.r - luma) * saturation) * brightness + texture;
    value.g = (luma + (value.g - luma) * saturation) * brightness + texture;
    value.b = (luma + (value.b - luma) * saturation) * brightness + texture;
    value.r = std::clamp(value.r, 0.0f, 1.0f);
    value.g = std::clamp(value.g, 0.0f, 1.0f);
    value.b = std::clamp(value.b, 0.0f, 1.0f);
    return value;
}

void blur(std::vector<Pixel>& pixels, int width, int height, int radius) {
    if (radius <= 0) return;
    std::vector<Pixel> temp(pixels.size());
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            Pixel sum{};
            int count = 0;
            for (int k = -radius; k <= radius; ++k) {
                const int sx = std::clamp(x + k, 0, width - 1);
                const Pixel& p = pixels[y * width + sx];
                sum.r += p.r;
                sum.g += p.g;
                sum.b += p.b;
                ++count;
            }
            temp[y * width + x] = {sum.r / count, sum.g / count, sum.b / count};
        }
    }
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            Pixel sum{};
            int count = 0;
            for (int k = -radius; k <= radius; ++k) {
                const int sy = std::clamp(y + k, 0, height - 1);
                const Pixel& p = temp[sy * width + x];
                sum.r += p.r;
                sum.g += p.g;
                sum.b += p.b;
                ++count;
            }
            pixels[y * width + x] = {sum.r / count, sum.g / count, sum.b / count};
        }
    }
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_rawsmusic_core_ui_widget_flow_NativeStaticBackground_render(
        JNIEnv* env,
        jclass,
        jintArray colors_array,
        jint width,
        jint height,
        jfloat saturation,
        jfloat brightness,
        jfloat texture_strength,
        jint blur_radius) {
    if (colors_array == nullptr || width <= 0 || height <= 0) return nullptr;
    const jsize count = env->GetArrayLength(colors_array);
    if (count <= 0) return nullptr;

    std::vector<jint> colors(static_cast<size_t>(count));
    env->GetIntArrayRegion(colors_array, 0, count, colors.data());
    std::vector<Pixel> palette;
    palette.reserve(static_cast<size_t>(count));
    for (const jint color : colors) {
        palette.push_back(unpack(static_cast<uint32_t>(color)));
    }
    const Pixel anchor = *std::max_element(
        palette.begin(),
        palette.end(),
        [](const Pixel& left, const Pixel& right) {
            const auto score = [](const Pixel& color) {
                const float mid_luma = 1.0f - std::abs(luma(color) - 0.46f);
                return chroma(color) * 0.72f + mid_luma * 0.28f;
            };
            return score(left) < score(right);
        });
    const auto safeColor = [&](jsize index) {
        const Pixel value = palette[static_cast<size_t>(std::min<jsize>(index, count - 1))];
        return suppressWhiteHighlight(value, anchor);
    };
    const Pixel c0 = safeColor(0);
    const Pixel c1 = safeColor(1);
    const Pixel c2 = safeColor(2);
    const Pixel c3 = safeColor(3);
    const Pixel c4 = safeColor(4);

    std::vector<Pixel> pixels(static_cast<size_t>(width) * height);
    for (int y = 0; y < height; ++y) {
        const float v = height == 1 ? 0.0f : static_cast<float>(y) / (height - 1);
        for (int x = 0; x < width; ++x) {
            const float u = width == 1 ? 0.0f : static_cast<float>(x) / (width - 1);
            // Use two vertically split matrices. Keep the cover's dominant colors
            // near the top, then converge into a dark lower
            // field instead of spreading every palette color across the page.
            constexpr float split = 0.48f;
            const bool lower = v > split;
            const float local_v = lower ? (v - split) / (1.0f - split) : v / split;
            const Pixel lower_target{
                c0.r * 0.055f + c2.r * 0.025f,
                c0.g * 0.055f + c2.g * 0.025f,
                c0.b * 0.055f + c2.b * 0.025f
            };
            const Pixel vertical = lower
                ? mix(c1, lower_target, smoothstep(0.0f, 1.0f, local_v))
                : mix(c0, c1, smoothstep(0.0f, 1.0f, local_v));
            const Pixel horizontal = mix(lower ? c3 : c2, lower ? c4 : c3, u);
            const float horizontal_weight = lower ? 0.08f * (1.0f - local_v) : 0.18f;
            Pixel matrix_color{
                vertical.r * (1.0f - horizontal_weight) + horizontal.r * horizontal_weight,
                vertical.g * (1.0f - horizontal_weight) + horizontal.g * horizontal_weight,
                vertical.b * (1.0f - horizontal_weight) + horizontal.b * horizontal_weight
            };
            const float edge_vignette = 1.0f - 0.12f * std::pow(std::abs(u * 2.0f - 1.0f), 1.6f);
            matrix_color.r *= edge_vignette;
            matrix_color.g *= edge_vignette;
            matrix_color.b *= edge_vignette;
            pixels[static_cast<size_t>(y) * width + x] =
                tune(matrix_color, saturation, brightness, 0.0f);
        }
    }
    blur(pixels, width, height, std::clamp(static_cast<int>(blur_radius), 0, 8));

    std::vector<jint> output(pixels.size());
    for (size_t i = 0; i < pixels.size(); ++i) {
        const auto r = static_cast<uint32_t>(std::lround(pixels[i].r * 255.0f));
        const auto g = static_cast<uint32_t>(std::lround(pixels[i].g * 255.0f));
        const auto b = static_cast<uint32_t>(std::lround(pixels[i].b * 255.0f));
        output[i] = static_cast<jint>(0xff000000u | (r << 16) | (g << 8) | b);
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_rawsmusic_core_ui_widget_flow_NativeStaticBackground_renderPlayer(
        JNIEnv* env,
        jclass,
        jintArray artwork_array,
        jint artwork_width,
        jint artwork_height,
        jint width,
        jint height,
        jfloat saturation,
        jfloat brightness,
        jfloat gradient,
        jfloat blur_level,
        jfloat detail_level) {
    if (artwork_array == nullptr || artwork_width <= 0 || artwork_height <= 0 ||
        width <= 0 || height <= 0) {
        return nullptr;
    }
    const jsize artwork_count = env->GetArrayLength(artwork_array);
    if (artwork_count < artwork_width * artwork_height) return nullptr;

    std::vector<jint> artwork_argb(static_cast<size_t>(artwork_count));
    env->GetIntArrayRegion(artwork_array, 0, artwork_count, artwork_argb.data());
    std::vector<Pixel> artwork(static_cast<size_t>(artwork_width) * artwork_height);
    for (size_t index = 0; index < artwork.size(); ++index) {
        artwork[index] = unpack(static_cast<uint32_t>(artwork_argb[index]));
    }

    // MilkLoader downsizes by the longest edge while preserving the complete
    // artwork aspect ratio. Do not center-crop before native processing.
    std::vector<Pixel> pixels(static_cast<size_t>(width) * height);
    for (int y = 0; y < height; ++y) {
        const float v = height == 1 ? 0.0f : static_cast<float>(y) / (height - 1);
        for (int x = 0; x < width; ++x) {
            const float u = width == 1 ? 0.0f : static_cast<float>(x) / (width - 1);
            pixels[static_cast<size_t>(y) * width + x] = sampleBilinear(
                artwork,
                artwork_width,
                artwork_height,
                u * (artwork_width - 1.0f),
                v * (artwork_height - 1.0f));
        }
    }
    const std::vector<Pixel> detail_source = pixels;
    const int resolved_blur_radius = blur_level <= 0.0f
        ? 0
        : std::clamp(static_cast<int>(std::lround(blur_level * 2.0f + 2.0f)), 0, 32);
    blur(pixels, width, height, resolved_blur_radius);
    blur(pixels, width, height, resolved_blur_radius);
    const float detail_amount = std::pow(
        std::clamp(detail_level / 10.0f, 0.0f, 1.0f), 1.25f) * 0.48f;
    if (detail_amount > 0.0f) {
        for (size_t index = 0; index < pixels.size(); ++index) {
            pixels[index] = mix(pixels[index], detail_source[index], detail_amount);
        }
    }

    for (int y = 0; y < height; ++y) {
        const float v = height == 1 ? 0.0f : static_cast<float>(y) / (height - 1);
        const float gradient_amount = std::clamp(gradient / 10.0f, 0.0f, 1.0f);
        const float gradient_start = 0.75f + (0.10f - 0.75f) * gradient_amount;
        const float gradient_end = 1.0f + (0.85f - 1.0f) * gradient_amount;
        const float gradient_max_alpha =
            (170.0f + (255.0f - 170.0f) * gradient_amount) / 255.0f;
        const float gradient_alpha = v <= gradient_start
            ? 0.0f
            : gradient_max_alpha *
                std::clamp((v - gradient_start) / (gradient_end - gradient_start), 0.0f, 1.0f);
        const float vertical_shade = 1.0f - gradient_alpha;
        for (int x = 0; x < width; ++x) {
            Pixel value = tune(
                pixels[static_cast<size_t>(y) * width + x],
                saturation,
                brightness * vertical_shade,
                0.0f);
            pixels[static_cast<size_t>(y) * width + x] = value;
        }
    }

    std::vector<jint> output(pixels.size());
    for (size_t i = 0; i < pixels.size(); ++i) {
        const auto r = static_cast<uint32_t>(std::lround(pixels[i].r * 255.0f));
        const auto g = static_cast<uint32_t>(std::lround(pixels[i].g * 255.0f));
        const auto b = static_cast<uint32_t>(std::lround(pixels[i].b * 255.0f));
        output[i] = static_cast<jint>(0xff000000u | (r << 16) | (g << 8) | b);
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}
