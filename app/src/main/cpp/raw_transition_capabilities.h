#pragma once

#include <cstdint>

namespace rawsmusic::transition_capabilities {

enum Feature : uint64_t {
    TransitionTrace = 1ULL << 0,
    TrackSlotState = 1ULL << 1,
    PcmSlotQueue = 1ULL << 2,
    RenderHandoffBarrier = 1ULL << 3,
    TransportFade = 1ULL << 4,
    SeekBarrier = 1ULL << 5,
    ManualCrossfade = 1ULL << 6,
    AutomaticCrossfade = 1ULL << 7,
    GaplessStitcher = 1ULL << 8,
    UsbTransitionGainOwner = 1ULL << 9,
    ContinuousPcmQueue = 1ULL << 10,
    TrackRenderTimeline = 1ULL << 11,
    AtomicPendingQueueBind = 1ULL << 12,
};

constexpr uint64_t kRequiredPhase9Mask =
        TransitionTrace |
        TrackSlotState |
        PcmSlotQueue |
        RenderHandoffBarrier |
        TransportFade |
        SeekBarrier |
        ManualCrossfade |
        AutomaticCrossfade |
        GaplessStitcher |
        UsbTransitionGainOwner |
        ContinuousPcmQueue |
        TrackRenderTimeline |
        AtomicPendingQueueBind;

uint64_t compiledFeatureMask() noexcept;

}  // namespace rawsmusic::transition_capabilities
