#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::transition_trace {

enum class EventCode : int32_t {
    OutputCreate = 1,
    OutputStart = 2,
    OutputPause = 3,
    OutputStop = 4,
    OutputFlush = 5,
    OutputFirstWrite = 6,
    OutputWriteError = 7,
    OutputRouteChanged = 8,
    OutputClose = 9,

    TransportFadeArm = 10,
    TransportFadeComplete = 11,
    TransportFadeClear = 12,

    ManualCrossfadeArm = 20,
    ManualCrossfadeFirstMix = 21,
    ManualCrossfadeComplete = 22,
    ManualCrossfadeClear = 23,

    SeekArm = 30,
    SeekFadeOutComplete = 31,
    SeekDecoderCommitted = 32,
    SeekOutputCommitted = 33,
    SeekCancelled = 34,
    SeekFailed = 35,

    GaplessBlockStitched = 40,

    AutoCrossfadeArm = 50,
    AutoCrossfadeFirstMix = 51,
    AutoCrossfadeRetime = 52,
    AutoCrossfadeComplete = 53,
    AutoCrossfadeClear = 54,
};

void setContext(
    int64_t sessionId,
    int64_t transitionId,
    int32_t generation,
    int64_t outputGeneration,
    int32_t reason
) noexcept;

void record(EventCode code, int64_t arg0 = 0, int64_t arg1 = 0) noexcept;

std::string snapshot();

}  // namespace rawsmusic::transition_trace
