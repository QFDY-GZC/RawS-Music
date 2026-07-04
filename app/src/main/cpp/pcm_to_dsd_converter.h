/**
 * PCM to DSD Converter
 *
 * Realtime PCM→DSD converter used by the native USB RAW_DATA path.
 * The public API is intentionally kept compatible with the existing engine.
 *
 * The implementation favors a stable, deterministic modulator topology over
 * dynamic quiet-passage helpers. Those helpers reduced absolute idle tones, but
 * they also introduced time-varying textures in real music at low DSD rates.
 */

#ifndef PCM_TO_DSD_CONVERTER_H
#define PCM_TO_DSD_CONVERTER_H

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

namespace rawsmusic {

/**
 * DSD output rates
 */
enum class DsdRate : int {
    DSD64 = 64,
    DSD128 = 128,
    DSD256 = 256,
    DSD512 = 512,
    DSD1024 = 1024
};

/**
 * Conversion algorithm type
 */
enum class DsdConversionType : int {
    Standard = 0,
    HighQuality = 1,
    LowLatency = 2
};

/**
 * Noise shaping coefficients kept for ABI/source compatibility with older code.
 */
struct NoiseShapingCoeffs {
    static constexpr int MAX_ORDER = 7;
    double coefficients[MAX_ORDER];
    double leakage_factor;
    int order;

    NoiseShapingCoeffs();
};

/**
 * Sigma-delta state kept for compatibility. The realtime path stores its
 * current output and first state here, while detailed error-feedback state is
 * private to PcmToDsdConverter.
 */
struct SigmaDeltaState {
    double accumulator;
    double feedback;
    double integrators[NoiseShapingCoeffs::MAX_ORDER];
    double delays[NoiseShapingCoeffs::MAX_ORDER];

    void reset();
};

/**
 * Bit packer state kept for compatibility with the non-realtime path.
 */
struct BitPackerState {
    uint8_t current_byte;
    int bit_count;
    std::vector<uint8_t> output_buffer;
    size_t output_position;

    void reset();
    void pack_bit(int bit);
    void flush();
};

/**
 * DSD configuration
 */
struct DsdConfig {
    DsdRate rate = DsdRate::DSD64;
    DsdConversionType type = DsdConversionType::Standard;
    bool enable_dither = false;
    bool enable_dop = false;
    double volume_scale = 1.0;
    int filter_length = 16;
};

/**
 * Lightweight FIR oversampler kept for compatibility. The realtime P2D path
 * below does exact ratio scheduling directly and does not depend on this class.
 */
class FirOversampler {
public:
    FirOversampler();
    ~FirOversampler();

    bool init(int input_rate, int output_rate, int filter_length = 32);
    void process(const std::vector<double>& input, std::vector<double>& output);
    void reset();
    int getUpsamplingFactor() const { return upsampling_factor_; }

private:
    std::vector<double> coefficients_;
    std::vector<double> delay_line_;
    int phase_count_;
    int phase_index_;
    int filter_length_;
    int upsampling_factor_;
};

/**
 * Main PCM to DSD converter
 */
class PcmToDsdConverter {
public:
    PcmToDsdConverter();
    ~PcmToDsdConverter();

    bool init(const DsdConfig& config, int input_rate = 44100);

    uint32_t convert(const void* pcm_data, uint32_t sample_count, int bit_depth,
                     uint8_t* dsd_output, uint32_t dsd_size);

    void reset();

    uint32_t getDsdRateHz() const;
    uint32_t getRateMultiplier() const;
    uint32_t getActualUpsamplingFactor() const;
    int getInputRate() const { return input_rate_; }

private:
    struct ErrorFeedbackState {
        static constexpr int MAX_TAPS = 7;
        std::array<float, MAX_TAPS> error{};
        float last_output = -1.0f;
        uint32_t rng = 0x2468ACE1u;
        int32_t prev_dither = 0;

        void reset(uint32_t seed);
    };

    struct P2dKernelSpec {
        int order;
        float input_gain;
        float clip;
        std::array<float, ErrorFeedbackState::MAX_TAPS> feedback_coeffs;
        float error_limit;
        float decision_limit;
        float dither_scale;
    };

    DsdConfig config_;
    std::unique_ptr<FirOversampler> oversampler_;
    NoiseShapingCoeffs noise_shaping_coeffs_;
    SigmaDeltaState sigma_delta_state_;
    BitPackerState bit_packer_;

    std::vector<float> source_cache_;

    ErrorFeedbackState ef_state_;

    uint32_t convertRealtimeP2d(const void* pcm_data, uint32_t sample_count, int bit_depth,
                                uint8_t* dsd_output, uint32_t dsd_size);
    uint32_t convertLowLatencyFast(const void* pcm_data, uint32_t sample_count, int bit_depth,
                                   uint8_t* dsd_output, uint32_t dsd_size);
    int quantizeP2dSample(float sample, const P2dKernelSpec& spec);
    double applyNoiseShaping(double error);
    int quantizeSample(double sample);
    double applyVolume(double sample, double scale);
    double applyDither(double sample, bool enable);
    double pcmToDouble(const void* data, int bit_depth, int index);
    const P2dKernelSpec& kernelSpec() const;
    bool isSilenceBlock(const std::vector<float>& samples) const;

    uint64_t samples_processed_;
    uint64_t bytes_output_;
    uint64_t target_bit_remainder_;
    uint32_t dither_state_;
    int32_t prev_dither_;
    int input_rate_;

    // Realtime state. Kept inside the converter so nativeWrite block
    // boundaries do not reset the SDM trajectory.
    std::array<float, ErrorFeedbackState::MAX_TAPS> ef_hist_;
    uint8_t silence_phase_;

    // Continuous low-rate assist factor derived from nominal DSD multiplier and
    // actual oversampling ratio. This keeps low-rate cleanup generic instead of
    // introducing a DSD64-only branch.
    float low_rate_assist_;
};

} // namespace rawsmusic

#endif // PCM_TO_DSD_CONVERTER_H
