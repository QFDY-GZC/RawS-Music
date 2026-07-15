#include "raw_fft_convolver.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define FFT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RawFftConvolver", __VA_ARGS__)
#define FFT_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RawFftConvolver", __VA_ARGS__)
#else
#define FFT_LOGI(...) ((void)0)
#define FFT_LOGE(...) ((void)0)
#endif

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace {

struct ComplexF {
    float r = 0.0f;
    float i = 0.0f;
};

enum class RoutingMode : int {
    SharedDiagonal = 0,
    PerChannelDiagonal = 1,
    FullMatrix = 2,
};

static inline bool finiteSample(float value) {
    return std::isfinite(value);
}

static inline float clampFloat(float value, float lo, float hi) {
    return std::max(lo, std::min(hi, value));
}

static inline int clampInt(int value, int lo, int hi) {
    return std::max(lo, std::min(hi, value));
}

static int preDelayFramesFromMs(float ms, int sampleRate) {
    const float safeMs = finiteSample(ms) ? clampFloat(ms, 0.0f, 500.0f) : 0.0f;
    return static_cast<int>(std::lround(
        safeMs * static_cast<float>(std::max(sampleRate, 8000)) / 1000.0f
    ));
}

static int choosePartitionSize(int irFrames, int pathCount) {
    // Uniform partitioning keeps the realtime FFT fixed at 2*partitionSize.
    // The previous implementation transformed the entire IR every block, which
    // became a 65536-point FFT for a 32768-frame IR and caused audio underruns.
    if (pathCount > 2) {
        if (irFrames <= 2048) return 512;
        if (irFrames <= 8192) return 1024;
        return 2048;
    }
    if (irFrames <= 2048) return 256;
    if (irFrames <= 8192) return 512;
    return 1024;
}

static void fft(ComplexF* values, int size, bool inverse) {
    if (!values || size <= 1) return;

    for (int i = 1, j = 0; i < size; ++i) {
        int bit = size >> 1;
        for (; (j & bit) != 0; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) std::swap(values[i], values[j]);
    }

    for (int len = 2; len <= size; len <<= 1) {
        const double angle = (inverse ? 2.0 : -2.0) * M_PI / static_cast<double>(len);
        const float stepR = static_cast<float>(std::cos(angle));
        const float stepI = static_cast<float>(std::sin(angle));
        const int half = len >> 1;

        for (int base = 0; base < size; base += len) {
            float wr = 1.0f;
            float wi = 0.0f;
            for (int j = 0; j < half; ++j) {
                const ComplexF left = values[base + j];
                const ComplexF right = values[base + j + half];
                const float vr = right.r * wr - right.i * wi;
                const float vi = right.r * wi + right.i * wr;

                values[base + j].r = left.r + vr;
                values[base + j].i = left.i + vi;
                values[base + j + half].r = left.r - vr;
                values[base + j + half].i = left.i - vi;

                const float nextWr = wr * stepR - wi * stepI;
                const float nextWi = wr * stepI + wi * stepR;
                wr = nextWr;
                wi = nextWi;
            }
        }
    }

    if (inverse) {
        const float scale = 1.0f / static_cast<float>(size);
        for (int i = 0; i < size; ++i) {
            values[i].r *= scale;
            values[i].i *= scale;
        }
    }
}

struct Kernel {
    int sampleRate = 44100;
    int streamChannels = 2;
    int irFrames = 0;
    int irChannels = 0;
    int partitionSize = 0;
    int fftSize = 0;
    int partitionCount = 0;
    int pathCount = 0;
    RoutingMode routing = RoutingMode::SharedDiagonal;
    std::vector<ComplexF> spectra;

    size_t spectrumOffset(int path, int partition) const {
        return (
            static_cast<size_t>(path) * static_cast<size_t>(partitionCount) +
            static_cast<size_t>(partition)
        ) * static_cast<size_t>(fftSize);
    }

    int pathFor(int inputChannel, int outputChannel) const {
        switch (routing) {
            case RoutingMode::SharedDiagonal:
                return inputChannel == outputChannel ? 0 : -1;
            case RoutingMode::PerChannelDiagonal:
                return inputChannel == outputChannel ? outputChannel : -1;
            case RoutingMode::FullMatrix:
                // Input-major: in0->out0, in0->out1, in1->out0, ...
                return inputChannel * streamChannels + outputChannel;
        }
        return -1;
    }
};

static const char* routingName(RoutingMode routing) {
    switch (routing) {
        case RoutingMode::SharedDiagonal: return "shared-diagonal";
        case RoutingMode::PerChannelDiagonal: return "per-channel-diagonal";
        case RoutingMode::FullMatrix: return "full-matrix";
    }
    return "unknown";
}

struct ProcessingState {
    explicit ProcessingState(std::shared_ptr<const Kernel> source)
        : kernel(std::move(source)) {
        const int channels = kernel->streamChannels;
        const int partitions = kernel->partitionCount;
        const int fftSize = kernel->fftSize;
        const int block = kernel->partitionSize;

        pendingInput.assign(static_cast<size_t>(block) * channels, 0.0f);
        inputHistory.assign(
            static_cast<size_t>(channels) * partitions * fftSize,
            ComplexF()
        );
        overlap.assign(static_cast<size_t>(channels) * block, 0.0f);
        wetBlock.assign(static_cast<size_t>(channels) * block, 0.0f);
        fftInput.assign(fftSize, ComplexF());
        fftOutput.assign(fftSize, ComplexF());

        // At steady state at most one complete partition is queued.
        outputQueue.assign(static_cast<size_t>(block) * channels * 2U, 0.0f);

        maxPreDelayFrames = std::max(1, kernel->sampleRate / 2 + 1);
        wetDelay.assign(static_cast<size_t>(maxPreDelayFrames) * channels, 0.0f);
        limiterRelease = 1.0f - std::exp(
            -1.0f / (0.160f * static_cast<float>(std::max(kernel->sampleRate, 8000)))
        );
    }

    std::shared_ptr<const Kernel> kernel;
    std::vector<float> pendingInput;
    std::vector<ComplexF> inputHistory;
    std::vector<float> overlap;
    std::vector<float> wetBlock;
    std::vector<ComplexF> fftInput;
    std::vector<ComplexF> fftOutput;
    std::vector<float> outputQueue;
    std::vector<float> wetDelay;

    int pendingFrames = 0;
    int writePartition = 0;
    int outputRead = 0;
    int outputCount = 0;
    int wetDelayWriteFrame = 0;
    int maxPreDelayFrames = 1;
    bool outputPrimed = false;
    float activationMix = 0.0f;
    float limiterGain = 1.0f;
    float limiterRelease = 0.0f;
    uint64_t resetEpochSeen = 0;

    size_t inputSpectrumOffset(int channel, int partition) const {
        return (
            static_cast<size_t>(channel) * kernel->partitionCount +
            static_cast<size_t>(partition)
        ) * static_cast<size_t>(kernel->fftSize);
    }

    void reset(uint64_t epoch) {
        std::fill(pendingInput.begin(), pendingInput.end(), 0.0f);
        std::fill(inputHistory.begin(), inputHistory.end(), ComplexF());
        std::fill(overlap.begin(), overlap.end(), 0.0f);
        std::fill(wetBlock.begin(), wetBlock.end(), 0.0f);
        std::fill(outputQueue.begin(), outputQueue.end(), 0.0f);
        std::fill(wetDelay.begin(), wetDelay.end(), 0.0f);
        pendingFrames = 0;
        writePartition = 0;
        outputRead = 0;
        outputCount = 0;
        wetDelayWriteFrame = 0;
        outputPrimed = false;
        activationMix = 0.0f;
        limiterGain = 1.0f;
        resetEpochSeen = epoch;
    }

    void pushOutput(float value) {
        if (outputQueue.empty()) return;
        if (outputCount >= static_cast<int>(outputQueue.size())) {
            outputRead = (outputRead + 1) % static_cast<int>(outputQueue.size());
            --outputCount;
        }
        const int write = (outputRead + outputCount) % static_cast<int>(outputQueue.size());
        outputQueue[write] = value;
        ++outputCount;
    }

    float popOutput() {
        if (outputCount <= 0 || outputQueue.empty()) return 0.0f;
        const float value = outputQueue[outputRead];
        outputRead = (outputRead + 1) % static_cast<int>(outputQueue.size());
        --outputCount;
        return value;
    }

    void processBlock(float wet, float dry, float gainLinear, int requestedPreDelayFrames) {
        const Kernel& k = *kernel;
        const int channels = k.streamChannels;
        const int block = k.partitionSize;
        const int fftSize = k.fftSize;
        const int partitions = k.partitionCount;
        const int slot = writePartition;

        // One forward FFT per input channel. Store spectra in a partition ring.
        for (int inputChannel = 0; inputChannel < channels; ++inputChannel) {
            std::fill(fftInput.begin(), fftInput.end(), ComplexF());
            for (int frame = 0; frame < block; ++frame) {
                fftInput[frame].r = pendingInput[
                    static_cast<size_t>(frame) * channels + inputChannel
                ];
            }
            fft(fftInput.data(), fftSize, false);
            ComplexF* destination = inputHistory.data() + inputSpectrumOffset(inputChannel, slot);
            std::memcpy(destination, fftInput.data(), static_cast<size_t>(fftSize) * sizeof(ComplexF));
        }

        // One accumulated inverse FFT per output channel.
        for (int outputChannel = 0; outputChannel < channels; ++outputChannel) {
            std::fill(fftOutput.begin(), fftOutput.end(), ComplexF());

            for (int inputChannel = 0; inputChannel < channels; ++inputChannel) {
                const int path = k.pathFor(inputChannel, outputChannel);
                if (path < 0) continue;

                for (int partition = 0; partition < partitions; ++partition) {
                    int historySlot = slot - partition;
                    if (historySlot < 0) historySlot += partitions;

                    const ComplexF* x = inputHistory.data() +
                        inputSpectrumOffset(inputChannel, historySlot);
                    const ComplexF* h = k.spectra.data() +
                        k.spectrumOffset(path, partition);

                    for (int bin = 0; bin < fftSize; ++bin) {
                        const float xr = x[bin].r;
                        const float xi = x[bin].i;
                        const float hr = h[bin].r;
                        const float hi = h[bin].i;
                        fftOutput[bin].r += xr * hr - xi * hi;
                        fftOutput[bin].i += xr * hi + xi * hr;
                    }
                }
            }

            fft(fftOutput.data(), fftSize, true);
            float* channelOverlap = overlap.data() + static_cast<size_t>(outputChannel) * block;
            for (int frame = 0; frame < block; ++frame) {
                const float convolved = fftOutput[frame].r + channelOverlap[frame];
                wetBlock[static_cast<size_t>(frame) * channels + outputChannel] =
                    finiteSample(convolved) ? convolved * gainLinear : 0.0f;
                channelOverlap[frame] = finiteSample(fftOutput[frame + block].r)
                    ? fftOutput[frame + block].r
                    : 0.0f;
            }
        }

        const int preDelayFrames = clampInt(
            requestedPreDelayFrames,
            0,
            maxPreDelayFrames - 1
        );

        for (int frame = 0; frame < block; ++frame) {
            for (int channel = 0; channel < channels; ++channel) {
                const size_t index = static_cast<size_t>(frame) * channels + channel;
                const float currentWet = wetBlock[index];
                float delayedWet = currentWet;

                if (preDelayFrames > 0) {
                    int readFrame = wetDelayWriteFrame - preDelayFrames;
                    if (readFrame < 0) readFrame += maxPreDelayFrames;
                    const size_t readIndex = static_cast<size_t>(readFrame) * channels + channel;
                    delayedWet = wetDelay[readIndex];
                }

                const size_t writeIndex =
                    static_cast<size_t>(wetDelayWriteFrame) * channels + channel;
                wetDelay[writeIndex] = finiteSample(currentWet) ? currentWet : 0.0f;

                const float drySample = pendingInput[index];
                const float mixed = dry * drySample + wet * delayedWet;
                pushOutput(finiteSample(mixed) ? mixed : 0.0f);
            }

            ++wetDelayWriteFrame;
            if (wetDelayWriteFrame >= maxPreDelayFrames) wetDelayWriteFrame = 0;
        }

        outputPrimed = true;
        writePartition = (writePartition + 1) % partitions;
        pendingFrames = 0;
    }
};

} // namespace

struct RawFftConvolver::Impl {
    std::atomic<int> sampleRate {44100};
    std::atomic<int> streamChannels {2};
    std::atomic<bool> requestedEnabled {false};
    std::atomic<bool> transitionActive {false};
    std::atomic<float> wet {1.0f};
    std::atomic<float> dry {0.0f};
    std::atomic<float> gainLinear {1.0f};
    std::atomic<float> preDelayMs {0.0f};
    std::atomic<int> preDelayFrames {0};
    std::atomic<uint64_t> resetEpoch {1};
    std::shared_ptr<ProcessingState> state;

    std::shared_ptr<ProcessingState> loadState() const {
        return std::atomic_load_explicit(&state, std::memory_order_acquire);
    }

    void storeState(std::shared_ptr<ProcessingState> next) {
        std::atomic_store_explicit(&state, std::move(next), std::memory_order_release);
    }
};

RawFftConvolver::RawFftConvolver() : m_impl(new Impl()) {}
RawFftConvolver::~RawFftConvolver() { delete m_impl; }

void RawFftConvolver::setFormat(int sampleRate, int channels) {
    const int safeRate = std::max(8000, sampleRate);
    const int safeChannels = clampInt(channels, 1, kMaxStreamChannels);
    m_impl->sampleRate.store(safeRate, std::memory_order_release);
    m_impl->streamChannels.store(safeChannels, std::memory_order_release);
    m_impl->preDelayFrames.store(
        preDelayFramesFromMs(m_impl->preDelayMs.load(std::memory_order_relaxed), safeRate),
        std::memory_order_release
    );
    // A kernel is routed for the stream channel count at load time. The Kotlin
    // controller reloads the source IR after every engine/format rebuild.
    m_impl->storeState(nullptr);
    m_impl->transitionActive.store(false, std::memory_order_release);
    m_impl->resetEpoch.fetch_add(1, std::memory_order_acq_rel);
}

void RawFftConvolver::setEnabled(bool enabled) {
    const bool previous = m_impl->requestedEnabled.exchange(enabled, std::memory_order_acq_rel);
    const auto current = m_impl->loadState();

    if (enabled && !previous && current) {
        // Start from a clean history and fade from the live input only after the
        // first wet partition is available. This avoids the zero-filled warmup
        // block causing a click or a short mute when the effect is enabled.
        auto fresh = std::make_shared<ProcessingState>(current->kernel);
        const uint64_t epoch =
            m_impl->resetEpoch.fetch_add(1, std::memory_order_acq_rel) + 1;
        fresh->resetEpochSeen = epoch;
        m_impl->storeState(std::move(fresh));
        m_impl->transitionActive.store(true, std::memory_order_release);
    } else if (!enabled && previous && current) {
        // Keep process() in the chain until it has crossfaded back to bypass.
        m_impl->transitionActive.store(true, std::memory_order_release);
    } else if (!current) {
        m_impl->transitionActive.store(false, std::memory_order_release);
    }

    FFT_LOGI("ENABLED value=%d ready=%d", enabled ? 1 : 0, isReady() ? 1 : 0);
}

bool RawFftConvolver::isEnabled() const {
    return m_impl->requestedEnabled.load(std::memory_order_acquire) ||
        m_impl->transitionActive.load(std::memory_order_acquire);
}

bool RawFftConvolver::isReady() const {
    return static_cast<bool>(m_impl->loadState());
}

void RawFftConvolver::setWetDry(float wet, float dry) {
    m_impl->wet.store(
        finiteSample(wet) ? clampFloat(wet, 0.0f, 2.0f) : 1.0f,
        std::memory_order_release
    );
    m_impl->dry.store(
        finiteSample(dry) ? clampFloat(dry, 0.0f, 2.0f) : 0.0f,
        std::memory_order_release
    );
}

void RawFftConvolver::setGainDb(float gainDb) {
    const float safeDb = finiteSample(gainDb) ? clampFloat(gainDb, -24.0f, 24.0f) : 0.0f;
    m_impl->gainLinear.store(std::pow(10.0f, safeDb / 20.0f), std::memory_order_release);
}

void RawFftConvolver::setPreDelayMs(float preDelayMs) {
    const float safeMs = finiteSample(preDelayMs) ? clampFloat(preDelayMs, 0.0f, 500.0f) : 0.0f;
    m_impl->preDelayMs.store(safeMs, std::memory_order_release);
    m_impl->preDelayFrames.store(
        preDelayFramesFromMs(safeMs, m_impl->sampleRate.load(std::memory_order_acquire)),
        std::memory_order_release
    );
}

void RawFftConvolver::setMix(float wet, float dry, float gainDb, float preDelayMs) {
    setWetDry(wet, dry);
    setGainDb(gainDb);
    setPreDelayMs(preDelayMs);
}

bool RawFftConvolver::loadIr(const float* interleavedIr, int frames, int irChannels) {
    if (!interleavedIr || frames <= 0 || irChannels <= 0) return false;

    const int channels = m_impl->streamChannels.load(std::memory_order_acquire);
    const int sampleRate = m_impl->sampleRate.load(std::memory_order_acquire);
    if (channels < 1 || channels > kMaxStreamChannels || irChannels > kMaxIrChannels) {
        return false;
    }

    RoutingMode routing;
    int pathCount = 0;
    if (irChannels == 1) {
        routing = RoutingMode::SharedDiagonal;
        pathCount = 1;
    } else if (irChannels == channels) {
        routing = RoutingMode::PerChannelDiagonal;
        pathCount = channels;
    } else if (irChannels == channels * channels && channels <= 4) {
        routing = RoutingMode::FullMatrix;
        pathCount = channels * channels;
    } else {
        FFT_LOGE(
            "IR_REJECT channels=%d streamChannels=%d expected=1,%d,%d",
            irChannels,
            channels,
            channels,
            channels * channels
        );
        return false;
    }

    const int safeFrames = std::min(frames, kMaxIrFrames);
    const int partitionSize = choosePartitionSize(safeFrames, pathCount);
    const int fftSize = partitionSize * 2;
    const int partitionCount = (safeFrames + partitionSize - 1) / partitionSize;

    auto kernel = std::make_shared<Kernel>();
    kernel->sampleRate = sampleRate;
    kernel->streamChannels = channels;
    kernel->irFrames = safeFrames;
    kernel->irChannels = irChannels;
    kernel->partitionSize = partitionSize;
    kernel->fftSize = fftSize;
    kernel->partitionCount = partitionCount;
    kernel->pathCount = pathCount;
    kernel->routing = routing;
    kernel->spectra.assign(
        static_cast<size_t>(pathCount) * partitionCount * fftSize,
        ComplexF()
    );

    std::vector<ComplexF> scratch(fftSize);
    for (int path = 0; path < pathCount; ++path) {
        const int sourceIrChannel = path;
        for (int partition = 0; partition < partitionCount; ++partition) {
            std::fill(scratch.begin(), scratch.end(), ComplexF());
            const int firstFrame = partition * partitionSize;
            const int count = std::min(partitionSize, safeFrames - firstFrame);
            for (int frame = 0; frame < count; ++frame) {
                const size_t sourceIndex =
                    static_cast<size_t>(firstFrame + frame) * irChannels + sourceIrChannel;
                const float value = interleavedIr[sourceIndex];
                scratch[frame].r = finiteSample(value)
                    ? clampFloat(value, -8.0f, 8.0f)
                    : 0.0f;
            }
            fft(scratch.data(), fftSize, false);
            ComplexF* destination = kernel->spectra.data() +
                kernel->spectrumOffset(path, partition);
            std::memcpy(destination, scratch.data(), static_cast<size_t>(fftSize) * sizeof(ComplexF));
        }
    }

    auto nextState = std::make_shared<ProcessingState>(kernel);
    const uint64_t epoch = m_impl->resetEpoch.fetch_add(1, std::memory_order_acq_rel) + 1;
    nextState->resetEpochSeen = epoch;
    m_impl->storeState(std::move(nextState));
    m_impl->transitionActive.store(
        m_impl->requestedEnabled.load(std::memory_order_acquire),
        std::memory_order_release
    );

    FFT_LOGI(
        "IR_LOADED frames=%d irChannels=%d streamChannels=%d routing=%s "
        "partition=%d partitions=%d fft=%d latency=%d",
        safeFrames,
        irChannels,
        channels,
        routingName(routing),
        partitionSize,
        partitionCount,
        fftSize,
        partitionSize + m_impl->preDelayFrames.load(std::memory_order_acquire)
    );
    return true;
}

void RawFftConvolver::clearIr() {
    m_impl->storeState(nullptr);
    m_impl->transitionActive.store(false, std::memory_order_release);
    m_impl->resetEpoch.fetch_add(1, std::memory_order_acq_rel);
    FFT_LOGI("IR_CLEARED");
}

int RawFftConvolver::latencyFrames() const {
    const auto state = m_impl->loadState();
    return state
        ? state->kernel->partitionSize + m_impl->preDelayFrames.load(std::memory_order_acquire)
        : 0;
}

int RawFftConvolver::preDelayFrames() const {
    return m_impl->preDelayFrames.load(std::memory_order_acquire);
}

int RawFftConvolver::irFrames() const {
    const auto state = m_impl->loadState();
    return state ? state->kernel->irFrames : 0;
}

int RawFftConvolver::irChannels() const {
    const auto state = m_impl->loadState();
    return state ? state->kernel->irChannels : 0;
}

int RawFftConvolver::streamChannels() const {
    return m_impl->streamChannels.load(std::memory_order_acquire);
}

void RawFftConvolver::resetState() {
    m_impl->resetEpoch.fetch_add(1, std::memory_order_acq_rel);
}

void RawFftConvolver::process(float* interleaved, int numFrames, int channels) {
    if (!interleaved || numFrames <= 0 || channels <= 0) return;

    const bool requestedEnabled =
        m_impl->requestedEnabled.load(std::memory_order_acquire);
    const bool transitionActive =
        m_impl->transitionActive.load(std::memory_order_acquire);
    if (!requestedEnabled && !transitionActive) return;

    const auto state = m_impl->loadState();
    if (!state || channels != state->kernel->streamChannels) {
        m_impl->transitionActive.store(false, std::memory_order_release);
        return;
    }

    const uint64_t epoch = m_impl->resetEpoch.load(std::memory_order_acquire);
    if (state->resetEpochSeen != epoch) {
        state->reset(epoch);
    }

    const float wet = m_impl->wet.load(std::memory_order_relaxed);
    const float dry = m_impl->dry.load(std::memory_order_relaxed);
    const float gain = m_impl->gainLinear.load(std::memory_order_relaxed);
    const int preDelay = m_impl->preDelayFrames.load(std::memory_order_relaxed);
    const int streamChannels = state->kernel->streamChannels;
    const int block = state->kernel->partitionSize;
    const float transitionStep = 1.0f / (
        0.024f * static_cast<float>(std::max(state->kernel->sampleRate, 8000))
    );
    constexpr float limiterCeiling = 0.94f;

    float inputFrame[kMaxStreamChannels] = {};
    float processedFrame[kMaxStreamChannels] = {};
    float outputFrame[kMaxStreamChannels] = {};

    for (int frame = 0; frame < numFrames; ++frame) {
        const bool hadWetOutput = state->outputPrimed && state->outputCount >= streamChannels;

        for (int channel = 0; channel < streamChannels; ++channel) {
            const size_t sampleIndex = static_cast<size_t>(frame) * streamChannels + channel;
            const float input = finiteSample(interleaved[sampleIndex])
                ? interleaved[sampleIndex]
                : 0.0f;
            inputFrame[channel] = input;
            state->pendingInput[
                static_cast<size_t>(state->pendingFrames) * streamChannels + channel
            ] = input;
            processedFrame[channel] = hadWetOutput ? state->popOutput() : input;
        }

        ++state->pendingFrames;
        if (state->pendingFrames >= block) {
            state->processBlock(wet, dry, gain, preDelay);
        }

        // Do not begin the enable transition until a real wet block exists.
        // Before that point the live input passes through unchanged.
        const bool targetEnabled =
            m_impl->requestedEnabled.load(std::memory_order_relaxed);
        if (targetEnabled && hadWetOutput) {
            state->activationMix = std::min(1.0f, state->activationMix + transitionStep);
        } else if (!targetEnabled) {
            state->activationMix = std::max(0.0f, state->activationMix - transitionStep);
        }

        const float mix = hadWetOutput ? state->activationMix : 0.0f;
        float processedPeak = 0.0f;
        for (int channel = 0; channel < streamChannels; ++channel) {
            processedPeak = std::max(
                processedPeak,
                std::fabs(finiteSample(processedFrame[channel]) ? processedFrame[channel] : 0.0f)
            );
        }

        // Linked, instant-attack safety gain on the convolved branch. Many
        // downloaded IRs contain positive gain or strong early reflections;
        // the old chain relied on StereoExpander's private limiter, which is
        // why enabling that effect appeared to reduce convolution pops.
        const float limiterTarget = processedPeak > limiterCeiling
            ? limiterCeiling / (processedPeak + 1.0e-12f)
            : 1.0f;
        if (limiterTarget < state->limiterGain) {
            state->limiterGain = limiterTarget;
        } else {
            state->limiterGain +=
                (limiterTarget - state->limiterGain) * state->limiterRelease;
        }

        for (int channel = 0; channel < streamChannels; ++channel) {
            const float limitedProcessed = processedFrame[channel] * state->limiterGain;
            const float blended = inputFrame[channel] +
                (limitedProcessed - inputFrame[channel]) * mix;
            outputFrame[channel] = finiteSample(blended) ? blended : 0.0f;
            const size_t sampleIndex = static_cast<size_t>(frame) * streamChannels + channel;
            interleaved[sampleIndex] = clampFloat(
                outputFrame[channel],
                -0.999f,
                0.999f
            );
        }
    }

    const bool enabledAfterBlock =
        m_impl->requestedEnabled.load(std::memory_order_acquire);
    const bool transitionFinished = enabledAfterBlock
        ? state->activationMix >= 0.9999f
        : state->activationMix <= 0.0001f;
    if (transitionFinished) {
        m_impl->transitionActive.store(false, std::memory_order_release);
    }
}
