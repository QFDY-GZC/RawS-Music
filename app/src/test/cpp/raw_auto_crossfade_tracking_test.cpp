#include "raw_auto_crossfade.h"
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
using namespace rawsmusic::auto_crossfade;
int main() {
    Processor mixer;
    mixer.arm(8000, 4400);
    float previous = 0, midpoint = 0;
    for (int block = 0; block < 80; ++block) {
        std::array<int16_t, 100> current, pending;
        current.fill(8192);
        pending.fill(block < 10 ? 512 : 24576);
        const float old_out = mixer.outgoing_gain(), old_in = mixer.incoming_gain();
        assert(mixer.process(reinterpret_cast<uint8_t*>(current.data()),
            reinterpret_cast<const uint8_t*>(pending.data()), 200, 1000, 2, SampleFormat::PcmS16) == 100);
        const float gain = mixer.incoming_gain();
        assert(std::abs(current[0] - (8192.0f * old_out + pending[0] * old_in)) <= 1.0f);
        assert(std::abs(gain + mixer.outgoing_gain() - 1.0f) < 0.0001f);
        assert(gain + 0.0001f >= previous);
        assert(gain - previous < 0.04f);
        previous = gain;
        if (block == 39) midpoint = gain;
    }
    std::printf("midpoint=%.5f\n", midpoint);
    assert(midpoint < 0.5f);
    assert(std::abs(mixer.incoming_gain() - 1.0f) < 0.0001f);
    assert(std::abs(mixer.outgoing_gain()) < 0.0001f);
    assert(!mixer.is_active_or_pending());
    assert(mixer.consumed_frames() == 8000);
    mixer.arm(21000, 8250, 6000);
    for (int block = 0; block < 210; ++block) {
        std::array<int16_t, 100> current, pending;
        current.fill(8192); pending.fill(8192);
        const float before = mixer.incoming_gain();
        mixer.process(reinterpret_cast<uint8_t*>(current.data()),
            reinterpret_cast<const uint8_t*>(pending.data()), 200, 1000, 2, SampleFormat::PcmS16);
        if (block >= 60) {
            assert(20.f * std::log10(mixer.incoming_gain() / before) <= .27f);
            assert(mixer.incoming_gain() - before < .014f);
        }
    }
    assert(!mixer.is_active_or_pending());
    assert(mixer.consumed_frames() == 21000);
    mixer.arm(10000, 5500, 6000);
    for (int block = 0; block < 100; ++block) {
        std::array<int16_t, 100> current, pending;
        current.fill(8192); pending.fill(8192);
        mixer.process(reinterpret_cast<uint8_t*>(current.data()),
            reinterpret_cast<const uint8_t*>(pending.data()), 200, 1000, 2, SampleFormat::PcmS16);
        if (block == 9) assert(std::abs(mixer.incoming_gain() - .025f) < .0001f);
        if (block == 59) assert(std::abs(mixer.incoming_gain() - .05f) < .0001f);
        if (block == 79) assert(std::abs(mixer.incoming_gain() - .3152158f) < .0001f);
        assert(mixer.incoming_gain() + mixer.outgoing_gain() <= 1.0001f);
        if (mixer.incoming_gain() >= .9f) assert(mixer.outgoing_gain() < .0001f);
    }
    assert(!mixer.is_active_or_pending());
    assert(mixer.consumed_frames() == 10000);
    mixer.arm(11000, 6000, 6000);
    for (int block = 0; block < 110; ++block) {
        std::array<int16_t, 100> current, pending;
        current.fill(8192); pending.fill(8192);
        const float before = mixer.incoming_gain();
        mixer.process(reinterpret_cast<uint8_t*>(current.data()),
            reinterpret_cast<const uint8_t*>(pending.data()), 200, 1000, 2, SampleFormat::PcmS16);
        if (block >= 60) {
            assert(20.f * std::log10(mixer.incoming_gain() / before) <= .8f);
            assert(mixer.incoming_gain() - before < .04f);
        }
    }
    assert(!mixer.is_active_or_pending());
    // Exact user-log geometry: 18,817 ms prelude at 44.1 kHz, with a real main window.
    mixer.arm(24817, 3300, 18817);
    float last_gain = 0.f;
    for (int block = 0; block < 1069; ++block) {
        std::array<int16_t, 1024> current, pending;
        current.fill(0); pending.fill(8192);
        mixer.process(reinterpret_cast<uint8_t*>(current.data()),
            reinterpret_cast<const uint8_t*>(pending.data()), 2048, 44100, 2, SampleFormat::PcmS16);
        const float gain = mixer.incoming_gain();
        if (block >= 809) assert(gain - last_gain < .02f);
        last_gain = gain;
    }
    assert(!mixer.is_active_or_pending());
    assert(std::abs(mixer.incoming_gain() - 1.f) < .0001f);
    mixer.arm(7000, 3300, 1000);
    for (int block = 0; block < 70; ++block) {
        std::array<int16_t, 100> current, pending;
        current.fill(8192); pending.fill(12000);
        const float old_gain = block == 0 ? 1.f : mixer.outgoing_gain();
        const float new_gain = block == 0 ? 0.f : mixer.incoming_gain();
        mixer.process(reinterpret_cast<uint8_t*>(current.data()),
            reinterpret_cast<const uint8_t*>(pending.data()), 200, 1000, 2, SampleFormat::PcmS16);
        assert(std::abs(current[0] - (8192.f * old_gain + 12000.f * new_gain)) <= 1.f);
        if (block >= 10) {
            assert(old_gain - mixer.outgoing_gain() < .042f);
            assert(mixer.incoming_gain() - new_gain < .04f);
        }
        assert(mixer.outgoing_gain() + mixer.incoming_gain() <= 1.0001f);
        if (mixer.incoming_gain() >= .9f) assert(mixer.outgoing_gain() < .0001f);
    }
    assert(!mixer.is_active_or_pending());
    mixer.arm(21000, 8250, 6000);
    for (int block = 0; block < 30; ++block) {
        std::array<int16_t, 100> current, pending;
        current.fill(1638); pending.fill(16384);
        mixer.process(reinterpret_cast<uint8_t*>(current.data()),
            reinterpret_cast<const uint8_t*>(pending.data()), 200, 1000, 2, SampleFormat::PcmS16);
    }
    assert(std::abs(mixer.incoming_gain() - .015f) < .0001f);
    assert(mixer.outgoing_gain() >= .9849f);
}
