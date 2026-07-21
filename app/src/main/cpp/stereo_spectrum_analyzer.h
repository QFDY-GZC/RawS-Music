#pragma once

#include <array>
#include <complex>
#include <cstdint>
#include <vector>

/**
 * Mono visualization analyzer.
 *
 * The file/JNI name is kept for source compatibility with the existing project, but the
 * analyzer no longer keeps or transforms independent left/right channels. Every PCM frame is
 * downmixed once and a single FFT spectrum is produced for the mirrored stereo view.
 */
class MonoSpectrumAnalyzer {
public:
    static constexpr int kBandCount = 112;

    MonoSpectrumAnalyzer();

    bool analyzePcm(
        const std::uint8_t* data,
        int byteCount,
        int channels,
        int sourceSampleRate,
        int sampleEncoding,
        int validBitsPerSample,
        float* output,
        int outputCount
    );

    bool advanceIdle(bool paused, float* output, int outputCount);
    void reset();

private:
    static constexpr int kFftSize = 4096;
    static constexpr int kAnalysisSampleRate = 48000;

    float decodeSample(const std::uint8_t* sample, int sampleEncoding, int validBitsPerSample) const;
    void appendSourceFrame(float mono, int sourceSampleRate);
    void appendFrame(float mono);
    void transform(std::array<float, kBandCount>& targetBands);
    void fft(std::vector<std::complex<float>>& values) const;
    void updateSmoothedBands(
        const std::array<float, kBandCount>& targets,
        float inputRms,
        float deltaSeconds,
        bool breatheWhenSilent
    );
    float nextDeltaSeconds();
    void copyOutput(float* output, int outputCount) const;

    std::array<float, kFftSize> m_ring{};
    std::array<float, kBandCount> m_smoothed{};
    std::array<float, kBandCount> m_lastTargets{};
    std::array<float, kFftSize> m_hann{};
    std::vector<std::complex<float>> m_fftBuffer;
    int m_writeIndex = 0;
    int m_validFrames = 0;
    int m_sourceSampleRate = 0;
    std::int64_t m_resampleAccumulator = 0;
    float m_lowPassMono = 0.0f;
    double m_lastAnalyzeSeconds = 0.0;
    double m_lastPcmSeconds = 0.0;
    float m_breathPhase = 0.0f;
    float m_energyEnvelope = 0.0f;
    float m_lastInputRms = 0.0f;
};
