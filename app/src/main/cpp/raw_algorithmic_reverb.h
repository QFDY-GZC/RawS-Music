#pragma once
#include <atomic>
#include <array>
#include <mutex>
#include <vector>

namespace rawsmusic::dsp {
struct ReverbParams {
    int space = 0;
    float mix = .15f, size = .35f, decay = 1.2f, preDelay = 12.f;
    float damping = .45f, diffusion = .7f, width = 1.f;
    float lowCut = 100.f, highCut = 12000.f, earlyLate = .65f, gainDb = 0.f;
};
class AlgorithmicReverb {
    struct Delay {
        std::vector<float> data; size_t position = 0;
        void init(size_t capacity); void clear(); void push(float value); float read(float frames) const;
    };
    struct Bank {
        ReverbParams params;
        std::array<Delay,8> fdn, diffuser;
        std::array<Delay,2> pre;
        std::array<float,8> feedback{}, length{}, dampState{};
        std::array<float,2> hpInput{}, hpOutput{}, lpOutput{};
        float hp=0, lp=0, damping=0, gain=1, dryMix=1, wetMix=0; int rate=48000;
        void init(int sampleRate); void clear(); void configure(ReverbParams p);
        void copyState(const Bank& other); std::array<float,2> tick(float left, float right);
    };
    std::array<Bank,2> banks_;
    std::mutex mailboxMutex_;
    ReverbParams pending_;
    unsigned version_=1, appliedVersion_=0;
    int rate_=48000, active_=0, fadeRemaining_=0, fadeTotal_=2400;
    float activation_=0;
    std::atomic<bool> enabled_{false}, fading_{false}, resetRequested_{false};
public:
    void init(int sampleRate);
    void setParams(const ReverbParams& params);
    void setEnabled(bool enabled);
    bool isActive() const;
    void reset();
    void process(float* samples, int frames, int channels);
};
}
