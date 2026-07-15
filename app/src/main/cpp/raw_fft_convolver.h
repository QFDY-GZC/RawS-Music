#pragma once

#include <cstddef>

/**
 * Realtime-safe uniform partitioned FFT convolver.
 *
 * Input/output are interleaved float PCM. The realtime process() path performs
 * no heap allocation and takes no mutex. IR preprocessing is done by loadIr()
 * off the audio thread and atomically swapped into the processor. Enable/disable
 * transitions are crossfaded after the wet path is primed, and the convolved
 * branch has its own linked safety limiter so it does not depend on later effects.
 *
 * IR channel routing for a C-channel playback stream:
 *   1 channel  : one shared diagonal kernel (same IR on every channel)
 *   C channels : independent diagonal kernels (IR[ch] -> output[ch])
 *   C*C channels: full convolution matrix, input-major order:
 *                 in0->out0, in0->out1 ... in1->out0 ...
 *                 For stereo this is LL, LR, RL, RR.
 *
 * No implicit downmix is performed.
 */
class RawFftConvolver {
public:
    RawFftConvolver();
    ~RawFftConvolver();

    RawFftConvolver(const RawFftConvolver&) = delete;
    RawFftConvolver& operator=(const RawFftConvolver&) = delete;

    void setFormat(int sampleRate, int channels);

    void setEnabled(bool enabled);
    bool isEnabled() const;
    bool isReady() const;

    void setWetDry(float wet, float dry);
    void setGainDb(float gainDb);
    void setPreDelayMs(float preDelayMs);
    void setMix(float wet, float dry, float gainDb, float preDelayMs);

    bool loadIr(const float* interleavedIr, int frames, int irChannels);
    void clearIr();

    int latencyFrames() const;
    int preDelayFrames() const;
    int irFrames() const;
    int irChannels() const;
    int streamChannels() const;

    void resetState();
    void process(float* interleaved, int numFrames, int channels);

    static constexpr int kMaxIrFrames = 32768;
    static constexpr int kMaxStreamChannels = 8;
    static constexpr int kMaxIrChannels = kMaxStreamChannels * kMaxStreamChannels;

private:
    struct Impl;
    Impl* m_impl;
};
