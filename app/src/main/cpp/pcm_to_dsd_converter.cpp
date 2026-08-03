/**
 * PCM to DSD Converter
 *
 * Realtime PCM→DSD converter.
 *
 * Keep the modulator topology fixed and predictable. Earlier builds layered
 * quiet-passage decorrelation, adaptive leakage, and extra one-pole smoothing
 * on top of the core 1-bit SDM. That helped some synthetic idle cases, but it
 * also created audible swirl / convolution-like texture on real music,
 * especially at DSD64. This version keeps deterministic silence handling, but
 * otherwise uses a static modulator with per-rate coefficients only.
 */

#include "pcm_to_dsd_converter.h"

#include <algorithm>
#if defined(__ANDROID__)
#include <pthread.h>
#include <sys/resource.h>
#endif
#include <cmath>
#include <cstring>
#include <limits>

namespace rawsmusic {

namespace {

constexpr float P2D_MAX_INPUT_LEVEL = 0.98f;
constexpr float P2D_SILENCE_THRESHOLD = 2.0e-8f;

static inline uint32_t xorshift32(uint32_t& state) {
    uint32_t x = state ? state : 0x2468ACE1u;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    state = x ? x : 0x13579BDFu;
    return state;
}

static inline float tpdfDither(uint32_t& state, int32_t& prev, float scale) {
    const uint32_t r = xorshift32(state);
    const int32_t lo = static_cast<int32_t>(r & 0xFFFFu);
    const int32_t hi = static_cast<int32_t>(r >> 16);
    const int32_t tpdf = lo - hi;
    const int32_t hp = tpdf - prev;
    prev = tpdf;
    return static_cast<float>(hp) * scale;
}

static inline float clampFloat(float v, float lo, float hi) {
    return std::max(lo, std::min(v, hi));
}

static inline uint32_t ceilDivU32(uint32_t a, uint32_t b) {
    return (b == 0) ? 0u : ((a + b - 1u) / b);
}

static int dsdBaseRateForInputRate(int inputRate) {
    if (inputRate > 0) {
        if (inputRate % 48000 == 0) return 48000;
        if (inputRate % 44100 == 0) return 44100;
    }
    return 44100;
}

static inline bool nearlySilent(float v) {
    return std::fabs(v) <= P2D_SILENCE_THRESHOLD;
}

static inline bool isPowerOfTwoU32(uint32_t value) {
    return value != 0u && (value & (value - 1u)) == 0u;
}

// 31-tap Kaiser-window halfband interpolator, scaled by two for interpolation.
// Every odd tap except the center is zero, so a 2x stage needs sixteen MACs
// for the even phase and one delayed sample for the odd phase. The state is
// persistent across nativeWrite calls.
constexpr std::array<float, 16> kHalfbandEvenTaps {
    -0.00009925975725f,  0.001284508051f, -0.005468882835f,  0.01604064802f,
    -0.03845527586f,    0.08307340932f,  -0.1824496163f,    0.6260910549f,
     0.6260910549f,    -0.1824496163f,    0.08307340932f,  -0.03845527586f,
     0.01604064802f,   -0.005468882835f,  0.001284508051f, -0.00009925975725f
};
constexpr float kHalfbandOddCenter = 0.999966829f;

} // namespace

NoiseShapingCoeffs::NoiseShapingCoeffs()
    : leakage_factor(0.9999), order(3) {
    for (double& c : coefficients) c = 0.0;
    coefficients[0] = 1.0;
    coefficients[1] = -0.25;
    coefficients[2] = 0.0;
}

void SigmaDeltaState::reset() {
    accumulator = 0.0;
    feedback = -1.0;
    for (int i = 0; i < NoiseShapingCoeffs::MAX_ORDER; i++) {
        integrators[i] = 0.0;
        delays[i] = 0.0;
    }
}

void BitPackerState::reset() {
    current_byte = 0;
    bit_count = 0;
    output_position = 0;
}

void BitPackerState::pack_bit(int bit) {
    current_byte = static_cast<uint8_t>((current_byte << 1) | (bit ? 1 : 0));
    bit_count++;
    if (bit_count >= 8) {
        if (output_position < output_buffer.size()) {
            output_buffer[output_position++] = current_byte;
        }
        current_byte = 0;
        bit_count = 0;
    }
}

void BitPackerState::flush() {
    if (bit_count > 0 && output_position < output_buffer.size()) {
        current_byte <<= (8 - bit_count);
        output_buffer[output_position++] = current_byte;
    }
    current_byte = 0;
    bit_count = 0;
}

FirOversampler::FirOversampler()
    : phase_count_(0), phase_index_(0), filter_length_(0), upsampling_factor_(0) {}

FirOversampler::~FirOversampler() = default;

bool FirOversampler::init(int input_rate, int output_rate, int filter_length) {
    if (input_rate <= 0 || output_rate <= 0) return false;
    upsampling_factor_ = static_cast<int>(ceilDivU32(static_cast<uint32_t>(output_rate),
                                                     static_cast<uint32_t>(input_rate)));
    if (upsampling_factor_ <= 0) return false;
    filter_length_ = std::max(1, filter_length);
    phase_count_ = upsampling_factor_;
    phase_index_ = 0;
    delay_line_.assign(static_cast<size_t>(filter_length_), 0.0);
    coefficients_.assign(static_cast<size_t>(filter_length_) * static_cast<size_t>(phase_count_), 0.0);
    coefficients_[0] = 1.0;
    return true;
}

void FirOversampler::process(const std::vector<double>& input, std::vector<double>& output) {
    const int up = std::max(1, upsampling_factor_);
    output.resize(input.size() * static_cast<size_t>(up));
    size_t o = 0;
    for (size_t i = 0; i < input.size(); i++) {
        const double cur = input[i];
        const double next = (i + 1 < input.size()) ? input[i + 1] : cur;
        for (int p = 0; p < up; p++) {
            const double t = static_cast<double>(p) / static_cast<double>(up);
            output[o++] = cur + (next - cur) * t;
        }
    }
}

void FirOversampler::reset() {
    std::fill(delay_line_.begin(), delay_line_.end(), 0.0);
    phase_index_ = 0;
}

void PcmToDsdConverter::ErrorFeedbackState::reset(uint32_t seed) {
    error.fill(0.0f);
    last_output = -1.0f;
    rng = seed ? seed : 0x2468ACE1u;
    prev_dither = 0;
}

void PcmToDsdConverter::Halfband2xStage::reset() {
    history.fill(0.0f);
    head = 0;
}

void PcmToDsdConverter::Halfband2xStage::process(float input, float& even, float& odd) {
    head = (head + HISTORY - 1u) % HISTORY;
    history[head] = input;

    float acc = 0.0f;
    for (size_t tap = 0; tap < HISTORY; ++tap) {
        acc += kHalfbandEvenTaps[tap] * history[(head + tap) % HISTORY];
    }
    even = acc;
    // The only non-zero odd polyphase tap is h[15], which addresses x[n-7].
    odd = kHalfbandOddCenter * history[(head + 7u) % HISTORY];
}

PcmToDsdConverter::PcmToDsdConverter()
    : samples_processed_(0),
      bytes_output_(0),
      target_bit_remainder_(0),
      dither_state_(0x2468ACE1u),
      prev_dither_(0),
      input_rate_(44100),
      silence_phase_(0),
      low_rate_assist_(0.0f),
      fixed_ratio_(0),
      p2d_ratio_(0),
      work_upsample_factor_(1),
      p2d_work_rate_hz_(44100),
      work_stage_count_(0),
      previous_input_(0.0f),
      have_previous_input_(false),
      previous_work_input_(0.0f),
      have_previous_work_input_(false) {
    ef_state_.reset(dither_state_);
    ef_hist_.fill(0.0f);
}

PcmToDsdConverter::~PcmToDsdConverter() = default;

bool PcmToDsdConverter::init(const DsdConfig& config, int input_rate) {
    config_ = config;
    input_rate_ = (input_rate > 0) ? input_rate : 44100;
    oversampler_ = std::make_unique<FirOversampler>();
    if (!oversampler_->init(input_rate_, static_cast<int>(getDsdRateHz()),
                            std::max(1, config_.filter_length))) {
        return false;
    }
    noise_shaping_coeffs_ = NoiseShapingCoeffs();
    const uint32_t dsdRateHz = getDsdRateHz();
    fixed_ratio_ = (input_rate_ > 0 && dsdRateHz > 0 &&
                    (dsdRateHz % static_cast<uint32_t>(input_rate_)) == 0u)
            ? (dsdRateHz / static_cast<uint32_t>(input_rate_))
            : 0u;
    // Every supported USB P2D transport packs full bytes. Keep the generic
    // fractional scheduler only for unusual rates which do not map exactly.
    if (fixed_ratio_ < 8u || (fixed_ratio_ & 7u) != 0u) fixed_ratio_ = 0u;
    configureWorkRate();
    {
        const float nominalMultiplier = static_cast<float>(getRateMultiplier());
        const float actualOsr = static_cast<float>(std::max<uint32_t>(1u, getActualUpsamplingFactor()));
        const float nominalAssist = clampFloat(
                (256.0f - std::min(nominalMultiplier, 256.0f)) / 192.0f,
                0.0f,
                1.0f);
        const float osrAssist = clampFloat(
                (64.0f - std::min(actualOsr, 64.0f)) / 32.0f,
                0.0f,
                1.0f);
        low_rate_assist_ = clampFloat(
                std::max(nominalAssist, osrAssist * 0.72f),
                0.0f,
                1.0f);
    }
    reset();
    return true;
}

void PcmToDsdConverter::configureWorkRate() {
    work_upsample_factor_ = 1u;
    work_stage_count_ = 0u;
    p2d_ratio_ = fixed_ratio_;
    p2d_work_rate_hz_ = static_cast<uint32_t>(std::max(1, input_rate_));

    if (fixed_ratio_ == 0u || !isPowerOfTwoU32(fixed_ratio_)) return;

    while (p2d_ratio_ > 128u && work_stage_count_ < work_rate_stages_.size()) {
        p2d_ratio_ >>= 1u;
        work_upsample_factor_ <<= 1u;
        ++work_stage_count_;
    }
    if (p2d_ratio_ < 8u || p2d_ratio_ > 128u || !isPowerOfTwoU32(p2d_ratio_)) {
        // Fall back to the generic scheduler rather than running an unsupported
        // direct R256/R512/R1024 CIFB kernel.
        p2d_ratio_ = 0u;
        work_upsample_factor_ = 1u;
        work_stage_count_ = 0u;
        p2d_work_rate_hz_ = static_cast<uint32_t>(std::max(1, input_rate_));
        return;
    }
    p2d_work_rate_hz_ = static_cast<uint32_t>(std::max(1, input_rate_)) *
                        work_upsample_factor_;
}

void PcmToDsdConverter::expandWorkRate(
        float input, std::array<float, 8>& output, uint32_t& count) {
    output.fill(0.0f);
    output[0] = input;
    count = 1u;

    std::array<float, 8> scratch{};
    for (uint32_t stageIndex = 0; stageIndex < work_stage_count_; ++stageIndex) {
        uint32_t nextCount = 0u;
        for (uint32_t i = 0; i < count; ++i) {
            float even = 0.0f;
            float odd = 0.0f;
            work_rate_stages_[stageIndex].process(output[i], even, odd);
            scratch[nextCount++] = even;
            scratch[nextCount++] = odd;
        }
        output = scratch;
        scratch.fill(0.0f);
        count = nextCount;
    }
}

uint32_t PcmToDsdConverter::convert(const void* pcm_data, uint32_t sample_count, int bit_depth,
                                    uint8_t* dsd_output, uint32_t dsd_size) {
    if (fixed_ratio_ != 0u) {
        return convertFixedRatioP2d(pcm_data, sample_count, bit_depth, dsd_output, dsd_size);
    }
    return convertRealtimeP2d(pcm_data, sample_count, bit_depth, dsd_output, dsd_size);
}


float PcmToDsdConverter::pcmToFloat(const void* data, int bit_depth, uint32_t index) const {
    const uint8_t* bytes = static_cast<const uint8_t*>(data);
    switch (bit_depth) {
        case 16: {
            const uint8_t* p = bytes + static_cast<size_t>(index) * 2u;
            const uint16_t u = static_cast<uint16_t>(p[0]) |
                               (static_cast<uint16_t>(p[1]) << 8);
            return static_cast<float>(static_cast<int16_t>(u)) * (1.0f / 32768.0f);
        }
        case 24: {
            const uint8_t* p = bytes + static_cast<size_t>(index) * 3u;
            uint32_t u = static_cast<uint32_t>(p[0]) |
                         (static_cast<uint32_t>(p[1]) << 8) |
                         (static_cast<uint32_t>(p[2]) << 16);
            if ((u & 0x00800000u) != 0u) u |= 0xFF000000u;
            return static_cast<float>(static_cast<int32_t>(u)) * (1.0f / 8388608.0f);
        }
        case 32: {
            const uint8_t* p = bytes + static_cast<size_t>(index) * 4u;
            const uint32_t u = static_cast<uint32_t>(p[0]) |
                               (static_cast<uint32_t>(p[1]) << 8) |
                               (static_cast<uint32_t>(p[2]) << 16) |
                               (static_cast<uint32_t>(p[3]) << 24);
            return static_cast<float>(static_cast<int32_t>(u)) * (1.0f / 2147483648.0f);
        }
        default:
            return 0.0f;
    }
}

uint32_t PcmToDsdConverter::convertFixedRatioP2d(
        const void* pcm_data, uint32_t sample_count, int bit_depth,
        uint8_t* dsd_output, uint32_t dsd_size) {
    if (!pcm_data || !dsd_output || sample_count == 0u || fixed_ratio_ == 0u ||
        p2d_ratio_ == 0u || work_upsample_factor_ == 0u) {
        return 0u;
    }

    const uint64_t bytesNeeded64 = static_cast<uint64_t>(sample_count) *
                                   static_cast<uint64_t>(fixed_ratio_ / 8u);
    if (bytesNeeded64 == 0u || bytesNeeded64 > static_cast<uint64_t>(dsd_size) ||
        bytesNeeded64 > static_cast<uint64_t>(std::numeric_limits<uint32_t>::max())) {
        return 0u;
    }
    const uint32_t bytesNeeded = static_cast<uint32_t>(bytesNeeded64);

    const P2dKernelSpec& spec = kernelSpec();
    const uint32_t bytesPerWorkSample = p2d_ratio_ / 8u;
    uint32_t outPos = 0u;

    std::array<float, 8> workSamples{};
    for (uint32_t i = 0; i < sample_count; ++i) {
        float current = pcmToFloat(pcm_data, bit_depth, i);
        if (config_.volume_scale != 1.0) current *= static_cast<float>(config_.volume_scale);
        if (!std::isfinite(current)) current = 0.0f;
        current = clampFloat(current, -spec.clip, spec.clip) * spec.input_gain;

        uint32_t workCount = 0u;
        expandWorkRate(current, workSamples, workCount);
        if (workCount != work_upsample_factor_) return 0u;

        for (uint32_t workIndex = 0; workIndex < workCount; ++workIndex) {
            const float workCurrent = workSamples[workIndex];
            const float start = have_previous_work_input_ ? previous_work_input_ : workCurrent;
            const float step = (workCurrent - start) / static_cast<float>(p2d_ratio_);
            float interpolated = start + step;

            for (uint32_t byteIndex = 0; byteIndex < bytesPerWorkSample; ++byteIndex) {
                uint8_t packed = 0u;
                for (int bit = 0; bit < 8; ++bit) {
                    const int oneBit = quantizeP2dSample(interpolated, spec);
                    packed = static_cast<uint8_t>((packed << 1) | (oneBit > 0 ? 1u : 0u));
                    interpolated += step;
                }
                if (outPos >= bytesNeeded) return 0u;
                dsd_output[outPos++] = packed;
            }
            previous_work_input_ = workCurrent;
            have_previous_work_input_ = true;
        }
        previous_input_ = current;
        have_previous_input_ = true;
    }

    if (outPos != bytesNeeded) return 0u;
    target_bit_remainder_ = 0u;
    sigma_delta_state_.feedback = ef_state_.last_output;
    sigma_delta_state_.accumulator = ef_hist_[0];
    for (int i = 0; i < NoiseShapingCoeffs::MAX_ORDER; ++i) {
        sigma_delta_state_.integrators[i] = ef_hist_[static_cast<size_t>(i)];
        sigma_delta_state_.delays[i] = ef_hist_[static_cast<size_t>(i)];
    }
    samples_processed_ += sample_count;
    bytes_output_ += outPos;
    dither_state_ = ef_state_.rng;
    prev_dither_ = ef_state_.prev_dither;
    return outPos;
}

uint32_t PcmToDsdConverter::convertLowLatencyFast(const void* pcm_data, uint32_t sample_count,
                                                  int bit_depth, uint8_t* dsd_output,
                                                  uint32_t dsd_size) {
    return convertRealtimeP2d(pcm_data, sample_count, bit_depth, dsd_output, dsd_size);
}

const PcmToDsdConverter::P2dKernelSpec& PcmToDsdConverter::kernelSpec() const {
    // Tuned SDM profiles. Keep the established stage order so output characteristics remain stable.
    // CIFB family and pull the input gains back toward the reference envelope.
    // The feedback taps remain intentionally conservative because this
    // converter runs block-realtime in our USB write path.
    static const P2dKernelSpec kDsd64 {
        // The DSD64 N5 profile caps stable input at roughly 0.700.
        // The previous 0.835 value over-drove the lowest-rate loop.
        5, 0.700f, 0.98f,
        { 1.000f, 0.245f, 0.095f, 0.0340f, 0.0105f, 0.0f, 0.0f },
        1.04f, 1.80f, 0.0f
    };
    static const P2dKernelSpec kDsd128 {
        5, 0.71428573f, 0.98f,
        { 1.000f, 0.155f, 0.054f, 0.0170f, 0.0048f, 0.0f, 0.0f },
        1.10f, 1.84f, 0.0f
    };
    static const P2dKernelSpec kDsd256 {
        4, 0.85f, 0.98f,
        { 1.000f, 0.125f, 0.040f, 0.0120f, 0.0035f, 0.0f, 0.0f },
        1.22f, 2.00f, 0.0f
    };
    static const P2dKernelSpec kDsd512 {
        3, 0.85f, 0.98f,
        { 1.000f, 0.110f, 0.034f, 0.0100f, 0.0029f, 0.0f, 0.0f },
        1.18f, 1.96f, 0.0f
    };
    static const P2dKernelSpec kDsd1024 {
        2, 0.85f, 0.98f,
        { 1.000f, 0.095f, 0.028f, 0.0080f, 0.0023f, 0.0f, 0.0f },
        1.12f, 1.90f, 0.0f
    };

    switch (config_.rate) {
        case DsdRate::DSD1024: return kDsd1024;
        case DsdRate::DSD512: return kDsd512;
        case DsdRate::DSD256: return kDsd256;
        case DsdRate::DSD128: return kDsd128;
        case DsdRate::DSD64:
        default: return kDsd64;
    }
}

bool PcmToDsdConverter::isSilenceBlock(const std::vector<float>& samples) const {
    for (float v : samples) {
        if (std::fabs(v) > P2D_SILENCE_THRESHOLD) return false;
    }
    return true;
}

uint32_t PcmToDsdConverter::convertRealtimeP2d(const void* pcm_data, uint32_t sample_count,
                                               int bit_depth, uint8_t* dsd_output,
                                               uint32_t dsd_size) {
    if (!pcm_data || !dsd_output || sample_count == 0 || dsd_size == 0 || input_rate_ <= 0) {
        return 0;
    }

    const uint32_t dsd_rate_hz = getDsdRateHz();
    if (dsd_rate_hz == 0) return 0;

    const uint64_t start_remainder = target_bit_remainder_;
    const uint64_t target_num = static_cast<uint64_t>(sample_count) * dsd_rate_hz + start_remainder;
    uint64_t target_bits_64 = target_num / static_cast<uint64_t>(input_rate_);
    target_bit_remainder_ = target_num % static_cast<uint64_t>(input_rate_);
    if (target_bits_64 == 0) return 0;

    uint32_t target_bits = static_cast<uint32_t>(std::min<uint64_t>(
            target_bits_64, static_cast<uint64_t>(std::numeric_limits<uint32_t>::max())));
    uint32_t dsd_bytes = (target_bits + 7u) / 8u;
    if (dsd_bytes > dsd_size) {
        dsd_bytes = dsd_size;
        target_bits = dsd_bytes * 8u;
        target_bit_remainder_ = 0;
    }

    source_cache_.resize(sample_count);
    bool allSilent = true;
    for (uint32_t i = 0; i < sample_count; i++) {
        double vd = pcmToDouble(pcm_data, bit_depth, static_cast<int>(i));
        if (config_.volume_scale != 1.0) vd = applyVolume(vd, config_.volume_scale);
        if (!std::isfinite(vd)) vd = 0.0;
        vd = std::max(-1.0, std::min(1.0, vd));
        const float vf = static_cast<float>(vd);
        if (std::fabs(vf) > P2D_SILENCE_THRESHOLD) allSilent = false;
        source_cache_[i] = vf;
    }

    if (allSilent && !config_.enable_dither) {
        // Keep absolute digital silence deterministic and noise-free.  Decay the
        // active states so low-level audio after a silent span does not splash.
        for (uint32_t i = 0; i < dsd_bytes; i++) {
            dsd_output[i] = 0xAAu;
        }
        for (float& e : ef_hist_) e *= 0.25f;
        ef_state_.error.fill(0.0f);
        ef_state_.last_output = -1.0f;
        previous_input_ = 0.0f;
        have_previous_input_ = true;
        samples_processed_ += sample_count;
        bytes_output_ += dsd_bytes;
        return dsd_bytes;
    }

    std::memset(dsd_output, 0, dsd_bytes);

    const P2dKernelSpec& spec = kernelSpec();

    uint32_t out_pos = 0;
    uint32_t bits_packed = 0;
    uint8_t current_byte = 0;
    int bit_count = 0;

    auto emitBit = [&](int bit) {
        current_byte = static_cast<uint8_t>((current_byte << 1) | (bit ? 1 : 0));
        bit_count++;
        if (bit_count == 8) {
            if (out_pos < dsd_bytes) dsd_output[out_pos++] = current_byte;
            current_byte = 0;
            bit_count = 0;
        }
    };

    auto preprocess = [&](float v) -> float {
        v = clampFloat(v, -spec.clip, spec.clip);
        return v * spec.input_gain;
    };

    for (uint32_t i = 0; i < sample_count && bits_packed < target_bits; i++) {
        const uint64_t bit_start = (static_cast<uint64_t>(i) * dsd_rate_hz + start_remainder) /
                                   static_cast<uint64_t>(input_rate_);
        const uint64_t bit_end = (static_cast<uint64_t>(i + 1u) * dsd_rate_hz + start_remainder) /
                                 static_cast<uint64_t>(input_rate_);
        uint32_t repeat = static_cast<uint32_t>(bit_end - bit_start);
        if (repeat == 0) continue;

        const float current = preprocess(source_cache_[i]);
        const float start = have_previous_input_ ? previous_input_ : current;
        const float step = (current - start) / static_cast<float>(repeat);

        for (uint32_t j = 0; j < repeat && bits_packed < target_bits; j++) {
            const float interpolated = start + step * static_cast<float>(j + 1u);
            int out;
            if (!config_.enable_dither &&
                nearlySilent(interpolated) &&
                nearlySilent(start) &&
                nearlySilent(current)) {
                // Keep exact silence deterministic.  We still decay the history
                // a little so a later non-silent block does not inherit stale
                // state from an earlier loud passage.
                silence_phase_ ^= 1u;
                out = silence_phase_ ? 1 : -1;
                for (float& e : ef_hist_) e *= 0.82f;
            } else {
                out = quantizeP2dSample(interpolated, spec);
            }
            emitBit(out > 0 ? 1 : 0);
            bits_packed++;
        }
        previous_input_ = current;
        have_previous_input_ = true;
    }

    while (bits_packed < target_bits) {
        silence_phase_ ^= 1u;
        const int out = silence_phase_ ? 1 : -1;
        emitBit(out > 0 ? 1 : 0);
        bits_packed++;
    }

    if (bit_count > 0 && out_pos < dsd_bytes) {
        current_byte <<= (8 - bit_count);
        dsd_output[out_pos++] = current_byte;
    }

    sigma_delta_state_.feedback = ef_state_.last_output;
    sigma_delta_state_.accumulator = ef_hist_[0];
    for (int i = 0; i < NoiseShapingCoeffs::MAX_ORDER; i++) {
        sigma_delta_state_.integrators[i] = ef_hist_[static_cast<size_t>(i)];
        sigma_delta_state_.delays[i] = ef_hist_[static_cast<size_t>(i)];
    }

    samples_processed_ += sample_count;
    bytes_output_ += out_pos;
    dither_state_ = ef_state_.rng;
    prev_dither_ = ef_state_.prev_dither;
    return out_pos;
}

int PcmToDsdConverter::quantizeP2dSample(float sample, const P2dKernelSpec& spec) {
    // Keep the modulator fixed. The previous low-level helper path made the
    // state transition with program level, which produced audible swirl and
    // roughness on real music. A static error-feedback loop is less fancy but
    // much cleaner and easier for a DAC to track.
    float x = clampFloat(sample, -P2D_MAX_INPUT_LEVEL, P2D_MAX_INPUT_LEVEL);
    const float absx = std::fabs(x);

    if (config_.enable_dither) {
        x += tpdfDither(ef_state_.rng, ef_state_.prev_dither, 0.18f / 2147483648.0f);
        x = clampFloat(x, -P2D_MAX_INPUT_LEVEL, P2D_MAX_INPUT_LEVEL);
    } else if (absx > P2D_SILENCE_THRESHOLD) {
        const float decorrelateScale = 0.0035f * low_rate_assist_;
        if (decorrelateScale > 0.0f) {
            const float fadeIn = clampFloat((absx - 8.0e-8f) / (3.2e-6f - 8.0e-8f), 0.0f, 1.0f);
            const float fadeOut = clampFloat((6.5e-4f - absx) / (6.5e-4f - 2.0e-5f), 0.0f, 1.0f);
            const float decorrelate = fadeIn * fadeOut;
            if (decorrelate > 0.0f) {
                x += tpdfDither(ef_state_.rng, ef_state_.prev_dither,
                                decorrelate * (decorrelateScale / 2147483648.0f));
                x = clampFloat(x, -P2D_MAX_INPUT_LEVEL, P2D_MAX_INPUT_LEVEL);
            }
        }
    }

    const int activeOrder = std::max(1, std::min(spec.order, 5));
    float shaped = x + ef_hist_[0];
    if (activeOrder >= 2) shaped += spec.feedback_coeffs[1] * ef_hist_[1];
    if (activeOrder >= 3) shaped += spec.feedback_coeffs[2] * ef_hist_[2];
    if (activeOrder >= 4) shaped += spec.feedback_coeffs[3] * ef_hist_[3];
    if (activeOrder >= 5) shaped += spec.feedback_coeffs[4] * ef_hist_[4];

    const float decision = clampFloat(shaped, -spec.decision_limit, spec.decision_limit);

    int out;
    if (decision > 0.0f) out = 1;
    else if (decision < 0.0f) out = -1;
    else out = (ef_state_.last_output > 0.0f) ? -1 : 1;

    const float y = static_cast<float>(out);
    const float err = x - y;

    const float quietAssist = low_rate_assist_ *
                              clampFloat((7.5e-4f - absx) / (7.5e-4f - 2.5e-5f), 0.0f, 1.0f);
    const float limitTrim = 1.0f - 0.03f * quietAssist;
    const float lim0 = spec.error_limit * limitTrim;
    const float lim1 = spec.error_limit * 0.78f * limitTrim;
    const float lim2 = spec.error_limit * 0.56f * limitTrim;
    const float lim3 = spec.error_limit * 0.40f * limitTrim;
    const float lim4 = spec.error_limit * 0.28f * limitTrim;

    const float leak0 = 0.99980f;
    const float leak1 = 0.99935f - 0.00008f * quietAssist;
    const float leak2 = 0.99895f - 0.00010f * quietAssist;
    const float leak3 = 0.99845f - 0.00012f * quietAssist;
    const float leak4 = 0.99785f - 0.00014f * quietAssist;

    const float f1 = 0.070f + 0.014f * low_rate_assist_;
    const float f2 = 0.045f + 0.005f * low_rate_assist_;
    const float f3 = 0.030f - 0.003f * low_rate_assist_;
    const float f4 = 0.020f - 0.006f * low_rate_assist_;

    ef_hist_[0] = clampFloat(leak0 * ef_hist_[0] + err, -lim0, lim0);
    if (activeOrder >= 2) ef_hist_[1] = clampFloat(leak1 * ef_hist_[1] + f1 * ef_hist_[0], -lim1, lim1);
    else ef_hist_[1] *= 0.92f;
    if (activeOrder >= 3) ef_hist_[2] = clampFloat(leak2 * ef_hist_[2] + f2 * ef_hist_[1], -lim2, lim2);
    else ef_hist_[2] *= 0.92f;
    if (activeOrder >= 4) ef_hist_[3] = clampFloat(leak3 * ef_hist_[3] + f3 * ef_hist_[2], -lim3, lim3);
    else ef_hist_[3] *= 0.92f;
    if (activeOrder >= 5) ef_hist_[4] = clampFloat(leak4 * ef_hist_[4] + f4 * ef_hist_[3], -lim4, lim4);
    else ef_hist_[4] *= 0.92f;

    for (int i = 5; i < ErrorFeedbackState::MAX_TAPS; i++) {
        ef_hist_[static_cast<size_t>(i)] *= 0.990f;
    }

    ef_state_.last_output = y;
    return out;
}

void PcmToDsdConverter::reset() {
    sigma_delta_state_.reset();
    bit_packer_.reset();
    if (oversampler_) oversampler_->reset();
    samples_processed_ = 0;
    bytes_output_ = 0;
    target_bit_remainder_ = 0;
    dither_state_ = 0x2468ACE1u;
    prev_dither_ = 0;
    ef_state_.reset(dither_state_);
    ef_hist_.fill(0.0f);
    silence_phase_ = 0;
    for (auto& stage : work_rate_stages_) stage.reset();
    previous_input_ = 0.0f;
    have_previous_input_ = false;
    previous_work_input_ = 0.0f;
    have_previous_work_input_ = false;
}

uint32_t PcmToDsdConverter::getDsdRateHz() const {
    const uint32_t mult = getRateMultiplier();
    const uint32_t base = static_cast<uint32_t>(dsdBaseRateForInputRate(input_rate_));
    return base * mult;
}

uint32_t PcmToDsdConverter::getRateMultiplier() const {
    return static_cast<uint32_t>(static_cast<int>(config_.rate));
}

uint32_t PcmToDsdConverter::getActualUpsamplingFactor() const {
    const uint32_t dsdRate = getDsdRateHz();
    if (input_rate_ <= 0 || dsdRate == 0) return getRateMultiplier();
    const uint32_t factor = ceilDivU32(dsdRate, static_cast<uint32_t>(input_rate_));
    return factor > 0 ? factor : getRateMultiplier();
}

double PcmToDsdConverter::applyNoiseShaping(double error) { return error; }

int PcmToDsdConverter::quantizeSample(double sample) {
    const P2dKernelSpec& spec = kernelSpec();
    return quantizeP2dSample(static_cast<float>(sample), spec);
}

double PcmToDsdConverter::applyVolume(double sample, double scale) { return sample * scale; }

double PcmToDsdConverter::applyDither(double sample, bool enable) {
    if (!enable) return sample;
    return sample + static_cast<double>(tpdfDither(dither_state_, prev_dither_, 1.0f / 2147483648.0f));
}

double PcmToDsdConverter::pcmToDouble(const void* data, int bit_depth, int index) {
    const uint8_t* bytes = static_cast<const uint8_t*>(data);
    switch (bit_depth) {
        case 16: {
            const int16_t* samples = reinterpret_cast<const int16_t*>(data);
            return static_cast<double>(samples[index]) / 32768.0;
        }
        case 24: {
            const uint8_t* sample_bytes = bytes + static_cast<size_t>(index) * 3u;
            uint32_t u = (static_cast<uint32_t>(sample_bytes[2]) << 16) |
                         (static_cast<uint32_t>(sample_bytes[1]) << 8) |
                         static_cast<uint32_t>(sample_bytes[0]);
            if (u & 0x800000u) u |= 0xFF000000u;
            const int32_t sample = static_cast<int32_t>(u);
            return static_cast<double>(sample) / 8388608.0;
        }
        case 32: {
            const int32_t* samples = reinterpret_cast<const int32_t*>(data);
            return static_cast<double>(samples[index]) / 2147483648.0;
        }
        default:
            return 0.0;
    }
}


StereoPcmToDsdConverter::StereoPcmToDsdConverter() = default;
StereoPcmToDsdConverter::~StereoPcmToDsdConverter() {
    reset();
}

bool StereoPcmToDsdConverter::init(const DsdConfig& config, int input_rate, int channels) {
    reset();
    if (input_rate <= 0 || channels <= 0 || channels > 2) return false;
    config_ = config;
    input_rate_ = input_rate;
    channels_ = channels;
    for (int ch = 0; ch < channels_; ++ch) {
        if (!converters_[static_cast<size_t>(ch)].init(config_, input_rate_)) {
            reset();
            return false;
        }
    }
    initialized_ = true;
    startParallelHelperIfNeeded();
    return true;
}

void StereoPcmToDsdConverter::startParallelHelperIfNeeded() {
    if (!initialized_ || channels_ != 2 ||
        static_cast<int>(config_.rate) < static_cast<int>(DsdRate::DSD256) ||
        parallel_helper_thread_.joinable()) {
        return;
    }
    parallel_helper_stop_ = false;
    parallel_helper_pending_ = false;
    parallel_helper_done_ = false;
    try {
        parallel_helper_thread_ = std::thread(&StereoPcmToDsdConverter::parallelHelperLoop, this);
    } catch (...) {
        // Serial conversion remains valid; initialization must not fail merely
        // because the platform could not create the optional transaction helper.
        parallel_helper_stop_ = true;
    }
}

void StereoPcmToDsdConverter::stopParallelHelper() {
    {
        std::lock_guard<std::mutex> lock(parallel_helper_mutex_);
        parallel_helper_stop_ = true;
        parallel_helper_pending_ = false;
    }
    parallel_helper_cv_.notify_all();
    parallel_helper_done_cv_.notify_all();
    if (parallel_helper_thread_.joinable()) {
        parallel_helper_thread_.join();
    }
    parallel_helper_stop_ = false;
    parallel_helper_pending_ = false;
    parallel_helper_done_ = false;
    parallel_pcm_ = nullptr;
    parallel_frame_count_ = 0;
    parallel_bit_depth_ = 0;
    parallel_output_ = nullptr;
    parallel_output_capacity_ = 0;
    parallel_written_ = 0;
}

void StereoPcmToDsdConverter::parallelHelperLoop() {
#if defined(__ANDROID__)
    pthread_setname_np(pthread_self(), "RawS-P2D-Ch1");
    setpriority(PRIO_PROCESS, 0, -12);
#endif
    for (;;) {
        const void* pcm = nullptr;
        uint32_t frames = 0;
        int bitDepth = 0;
        uint8_t* output = nullptr;
        uint32_t capacity = 0;
        {
            std::unique_lock<std::mutex> lock(parallel_helper_mutex_);
            parallel_helper_cv_.wait(lock, [&] {
                return parallel_helper_stop_ || parallel_helper_pending_;
            });
            if (parallel_helper_stop_) return;
            pcm = parallel_pcm_;
            frames = parallel_frame_count_;
            bitDepth = parallel_bit_depth_;
            output = parallel_output_;
            capacity = parallel_output_capacity_;
            parallel_helper_pending_ = false;
        }

        const uint32_t written = converters_[1].convert(
                pcm, frames, bitDepth, output, capacity);
        {
            std::lock_guard<std::mutex> lock(parallel_helper_mutex_);
            parallel_written_ = written;
            parallel_helper_done_ = true;
        }
        parallel_helper_done_cv_.notify_one();
    }
}

bool StereoPcmToDsdConverter::convertInterleaved(
        const void* pcm_data,
        uint32_t frame_count,
        int channels,
        int bit_depth,
        int bytes_per_sample,
        uint8_t* channel0_output,
        uint8_t* channel1_output,
        uint32_t output_capacity_per_channel,
        std::array<uint32_t, 2>& written_per_channel) {
    written_per_channel = {0u, 0u};
    if (!initialized_ || !pcm_data || frame_count == 0u || channels != channels_ ||
        channels <= 0 || channels > 2 || bytes_per_sample <= 0 ||
        !channel0_output || output_capacity_per_channel == 0u) {
        return false;
    }
    if (channels == 2 && !channel1_output) return false;

    const auto* input = static_cast<const uint8_t*>(pcm_data);
    const size_t frame_bytes = static_cast<size_t>(channels) * static_cast<size_t>(bytes_per_sample);
    const size_t mono_bytes = static_cast<size_t>(frame_count) * static_cast<size_t>(bytes_per_sample);
    for (int ch = 0; ch < channels; ++ch) {
        auto& scratch = pcm_scratch_[static_cast<size_t>(ch)];
        if (scratch.size() < mono_bytes) scratch.resize(mono_bytes);
    }

    for (uint32_t frame = 0; frame < frame_count; ++frame) {
        const uint8_t* src_frame = input + static_cast<size_t>(frame) * frame_bytes;
        for (int ch = 0; ch < channels; ++ch) {
            uint8_t* dst = pcm_scratch_[static_cast<size_t>(ch)].data() +
                           static_cast<size_t>(frame) * static_cast<size_t>(bytes_per_sample);
            std::memcpy(dst,
                        src_frame + static_cast<size_t>(ch) * static_cast<size_t>(bytes_per_sample),
                        static_cast<size_t>(bytes_per_sample));
        }
    }

    const bool parallelTransaction = channels == 2 && parallel_helper_thread_.joinable();
    if (parallelTransaction) {
        {
            std::lock_guard<std::mutex> lock(parallel_helper_mutex_);
            parallel_pcm_ = pcm_scratch_[1].data();
            parallel_frame_count_ = frame_count;
            parallel_bit_depth_ = bit_depth;
            parallel_output_ = channel1_output;
            parallel_output_capacity_ = output_capacity_per_channel;
            parallel_written_ = 0u;
            parallel_helper_done_ = false;
            parallel_helper_pending_ = true;
        }
        parallel_helper_cv_.notify_one();
    }

    written_per_channel[0] = converters_[0].convert(
            pcm_scratch_[0].data(), frame_count, bit_depth,
            channel0_output, output_capacity_per_channel);
    if (channels == 2) {
        if (parallelTransaction) {
            std::unique_lock<std::mutex> lock(parallel_helper_mutex_);
            parallel_helper_done_cv_.wait(lock, [&] {
                return parallel_helper_done_ || parallel_helper_stop_;
            });
            if (parallel_helper_stop_) return false;
            written_per_channel[1] = parallel_written_;
            parallel_helper_done_ = false;
        } else {
            written_per_channel[1] = converters_[1].convert(
                    pcm_scratch_[1].data(), frame_count, bit_depth,
                    channel1_output, output_capacity_per_channel);
        }
    }

    if (written_per_channel[0] == 0u) return false;
    if (channels == 2 && written_per_channel[1] != written_per_channel[0]) return false;
    return true;
}

void StereoPcmToDsdConverter::reset() {
    stopParallelHelper();
    for (auto& converter : converters_) converter.reset();
    for (auto& scratch : pcm_scratch_) scratch.clear();
    config_ = DsdConfig{};
    input_rate_ = 0;
    channels_ = 0;
    initialized_ = false;
}

uint32_t StereoPcmToDsdConverter::getDsdRateHz() const {
    return initialized_ ? converters_[0].getDsdRateHz() : 0u;
}
uint32_t StereoPcmToDsdConverter::getRateMultiplier() const {
    return initialized_ ? converters_[0].getRateMultiplier() : 0u;
}
uint32_t StereoPcmToDsdConverter::getActualUpsamplingFactor() const {
    return initialized_ ? converters_[0].getActualUpsamplingFactor() : 0u;
}
uint32_t StereoPcmToDsdConverter::getP2dWorkRateHz() const {
    return initialized_ ? converters_[0].getP2dWorkRateHz() : 0u;
}
uint32_t StereoPcmToDsdConverter::getP2dRatio() const {
    return initialized_ ? converters_[0].getP2dRatio() : 0u;
}
uint32_t StereoPcmToDsdConverter::getWorkUpsampleFactor() const {
    return initialized_ ? converters_[0].getWorkUpsampleFactor() : 0u;
}

} // namespace rawsmusic
