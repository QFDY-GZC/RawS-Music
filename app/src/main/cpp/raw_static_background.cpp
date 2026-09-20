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


constexpr float kReferenceInitialPixelOffset = 10.0f / 21.0f;
constexpr float kReferenceOddPassPixelOffset = 4.0f / 3.0f;
constexpr float kReferenceEvenPassPixelOffset = 5.0f / 3.0f;

float quantizeUnorm8(float value) {
    return std::round(std::clamp(value, 0.0f, 1.0f) * 255.0f) / 255.0f;
}

Pixel quantizeUnorm8(Pixel value) {
    value.r = quantizeUnorm8(value.r);
    value.g = quantizeUnorm8(value.g);
    value.b = quantizeUnorm8(value.b);
    return value;
}

void referenceFourDiagonalPass(
        const std::vector<Pixel>& source,
        int source_width,
        int source_height,
        std::vector<Pixel>& output,
        int output_size,
        float pixel_offset,
        bool final_aa_pass,
        float saturation,
        float brightness) {
    const float normalized_offset = pixel_offset / output_size;
    for (int y = 0; y < output_size; ++y) {
        const float v = (static_cast<float>(y) + 0.5f) / output_size;
        for (int x = 0; x < output_size; ++x) {
            const float u = (static_cast<float>(x) + 0.5f) / output_size;
            const auto sample_at = [&](float sample_u, float sample_v) {
                return sampleBilinear(
                    source,
                    source_width,
                    source_height,
                    sample_u * source_width - 0.5f,
                    sample_v * source_height - 0.5f);
            };
            const Pixel top_left = sample_at(u - normalized_offset, v - normalized_offset);
            const Pixel top_right = sample_at(u + normalized_offset, v - normalized_offset);
            const Pixel bottom_left = sample_at(u - normalized_offset, v + normalized_offset);
            const Pixel bottom_right = sample_at(u + normalized_offset, v + normalized_offset);
            Pixel value{
                (top_left.r + top_right.r + bottom_left.r + bottom_right.r) * 0.25f,
                (top_left.g + top_right.g + bottom_left.g + bottom_right.g) * 0.25f,
                (top_left.b + top_right.b + bottom_left.b + bottom_right.b) * 0.25f
            };
            if (final_aa_pass) {
                // blur_aa_fs.glsl: four-sample sum * (intensity * 0.25), then saturation.
                value.r *= brightness;
                value.g *= brightness;
                value.b *= brightness;
                const float aa_luma = value.r * 0.2125f + value.g * 0.7154f + value.b * 0.0721f;
                value.r = aa_luma + (value.r - aa_luma) * saturation;
                value.g = aa_luma + (value.g - aa_luma) * saturation;
                value.b = aa_luma + (value.b - aa_luma) * saturation;
            }
            // retained-view implementation allocates the ping-pong target with GL_RGBA/GL_UNSIGNED_BYTE.
            output[static_cast<size_t>(y) * output_size + x] = quantizeUnorm8(value);
        }
    }
}

std::vector<Pixel> renderReferenceAaTexture(
        const std::vector<Pixel>& artwork,
        int artwork_width,
        int artwork_height,
        int blur_level,
        float saturation,
        float brightness) {
    // The native blur target is square and its side is constructed from the artwork texture width.
    const int size = std::max(1, artwork_width);
    std::vector<Pixel> current = artwork;
    int current_width = artwork_width;
    int current_height = artwork_height;
    std::vector<Pixel> next(static_cast<size_t>(size) * size);
    const int clamped_blur = std::clamp(blur_level, 0, 15);
    const int pass_count = clamped_blur + 1;
    for (int pass = 0; pass < pass_count; ++pass) {
        const bool final_aa_pass = pass == pass_count - 1;
        const float pixel_offset = pass == 0
            ? kReferenceInitialPixelOffset
            : ((pass & 1) != 0 ? kReferenceOddPassPixelOffset : kReferenceEvenPassPixelOffset);
        referenceFourDiagonalPass(
            current,
            current_width,
            current_height,
            next,
            size,
            pixel_offset,
            final_aa_pass,
            saturation,
            brightness);
        current.swap(next);
        current_width = size;
        current_height = size;
        next.resize(static_cast<size_t>(size) * size);
    }
    return current;
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
    (void) texture_strength;
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
        jint gradient_color_argb,
        jfloat blur_level) {
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

    // StaticArtworkLoader applies aa_bg_gradient to the artwork bitmap before native_load_aa() and blur.
    const bool gradient_enabled = gradient > 0.0f;
    const float gradient_amount = std::clamp(gradient / 10.0f, 0.0f, 1.0f);
    const float gradient_start = 0.75f + (0.10f - 0.75f) * gradient_amount;
    const float gradient_end = 1.0f + (0.85f - 1.0f) * gradient_amount;
    const uint32_t gradient_rgb = static_cast<uint32_t>(gradient_color_argb) & 0x00ffffffu;
    const Pixel gradient_color = unpack(gradient_rgb);
    // StaticArtworkLoader.В() strips the configured color alpha. Black uses opaque alpha; non-black
    // gradients interpolate their terminal alpha from 170 to 255 as aa_bg_gradient goes 0..10.
    const float gradient_color_alpha = gradient_rgb == 0u
        ? 1.0f
        : std::round(170.0f + (255.0f - 170.0f) * gradient_amount) / 255.0f;
    for (int y = 0; y < artwork_height; ++y) {
        // Canvas/LinearGradient is evaluated at destination pixel centers after setRectToRect(FILL).
        const float v = (static_cast<float>(y) + 0.5f) / artwork_height;
        const float gradient_alpha = !gradient_enabled || v <= gradient_start
            ? 0.0f
            : std::clamp((v - gradient_start) / (gradient_end - gradient_start), 0.0f, 1.0f)
                * gradient_color_alpha;
        for (int x = 0; x < artwork_width; ++x) {
            Pixel& value = artwork[static_cast<size_t>(y) * artwork_width + x];
            // Android Canvas draws the gradient into an ARGB_8888 bitmap before native_load_aa.
            value = quantizeUnorm8(mix(value, gradient_color, gradient_alpha));
        }
    }

    // Reference retained-view implementation native artwork path:
    //   * blur is clamped to 0..15;
    //   * one initial four-diagonal pass always runs;
    //   * blur additional ping-pong passes follow;
    //   * the final pass switches from blur_fs to blur_aa_fs and applies intensity/saturation.
    // u_pixelSize is initialized to (10/21)/size, then alternates (1/size)/0.75 and
    // (1/size)/0.60. In pixel coordinates those normalized offsets are the constants below.
    const int blur_passes = std::clamp(static_cast<int>(std::lround(blur_level)), 0, 15);
    const std::vector<Pixel> aa_texture = renderReferenceAaTexture(
        artwork,
        artwork_width,
        artwork_height,
        blur_passes,
        saturation,
        brightness);
    // Keep the JNI ABI stable with the previous renderer. The Java side passes aaSize for both
    // width/height; retaining these arguments prevents a stale incremental native build from
    // interpreting float tuning arguments as integer dimensions and returning a black fallback.
    const int aa_size = std::max(1, artwork_width);
    if (width != aa_size || height != aa_size) {
        // New multipass output is the native artwork texture itself, so its target must be square.
        // Do not silently allocate a screen-sized endpoint again.
        width = aa_size;
        height = aa_size;
    }
    (void) width;
    (void) height;

    // Keep the prepared endpoint as the small square artwork texture. Reference leaves this texture in
    // the renderer and uses GL_LINEAR when it is finally mapped to the player surface; RawSMusic's
    // persistent Compose owner performs that presentation step instead of baking a screen raster.
    std::vector<jint> output(static_cast<size_t>(aa_size) * aa_size);
    for (size_t index = 0; index < aa_texture.size(); ++index) {
        const Pixel value = aa_texture[index];
        const auto r = static_cast<uint32_t>(std::lround(std::clamp(value.r, 0.0f, 1.0f) * 255.0f));
        const auto g = static_cast<uint32_t>(std::lround(std::clamp(value.g, 0.0f, 1.0f) * 255.0f));
        const auto b = static_cast<uint32_t>(std::lround(std::clamp(value.b, 0.0f, 1.0f) * 255.0f));
        output[index] = static_cast<jint>(0xff000000u | (r << 16) | (g << 8) | b);
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}
