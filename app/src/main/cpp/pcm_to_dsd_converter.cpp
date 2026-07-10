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

PcmToDsdConverter::PcmToDsdConverter()
    : samples_processed_(0),
      bytes_output_(0),
      target_bit_remainder_(0),
      dither_state_(0x2468ACE1u),
      prev_dither_(0),
      input_rate_(44100),
      silence_phase_(0),
      low_rate_assist_(0.0f) {
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

uint32_t PcmToDsdConverter::convert(const void* pcm_data, uint32_t sample_count, int bit_depth,
                                    uint8_t* dsd_output, uint32_t dsd_size) {
    return convertRealtimeP2d(pcm_data, sample_count, bit_depth, dsd_output, dsd_size);
}

uint32_t PcmToDsdConverter::convertLowLatencyFast(const void* pcm_data, uint32_t sample_count,
                                                  int bit_depth, uint8_t* dsd_output,
                                                  uint32_t dsd_size) {
    return convertRealtimeP2d(pcm_data, sample_count, bit_depth, dsd_output, dsd_size);
}

const PcmToDsdConverter::P2dKernelSpec& PcmToDsdConverter::kernelSpec() const {
    // Reference-shaped SDM profiles. Keep the stage order from the Poweramp
    // CIFB family and pull the input gains back toward the reference envelope.
    // The feedback taps remain intentionally conservative because this
    // converter runs block-realtime in our USB write path.
    static const P2dKernelSpec kDsd64 {
        5, 0.835f, 0.98f,
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
        const float next = preprocess((i + 1u < sample_count) ? source_cache_[i + 1u] : current);

        for (uint32_t j = 0; j < repeat && bits_packed < target_bits; j++) {
            const float phase = (repeat > 1u)
                    ? ((static_cast<float>(j) + 0.5f) / static_cast<float>(repeat))
                    : 0.5f;
            const float interpolated = current + (next - current) * phase;
            int out;
            if (!config_.enable_dither &&
                nearlySilent(interpolated) &&
                nearlySilent(current) &&
                nearlySilent(next)) {
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

} // namespace rawsmusic
