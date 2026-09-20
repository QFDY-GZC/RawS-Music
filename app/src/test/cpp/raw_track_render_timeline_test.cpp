#include "raw_track_render_timeline.h"

#include <cassert>

int main() {
    using rawsmusic::track_timeline::Event;
    using rawsmusic::track_timeline::Timeline;

    Timeline timeline;
    assert(timeline.installCurrent(77, 7, 48000, 24000));
    assert(timeline.currentSerial() == 77);
    assert(timeline.currentPositionFrames() == 24000);
    assert(timeline.armPending(101, 7, 48000));
    auto first = timeline.onPendingFramesRendered(101, 7, 480);
    assert(first.event == Event::Started);
    assert(first.position_frames == 480);
    auto retired = timeline.retirePrevious(77, 7);
    assert(retired.event == Event::Retired);
    auto second = timeline.onPendingFramesRendered(101, 7, 960);
    assert(second.event == Event::None);
    assert(second.position_frames == 1440);
    assert(timeline.currentPositionFrames() == 1440);
    auto commit = timeline.commitPending(101, 7);
    assert(commit.event == Event::Committed);
    assert(commit.position_frames == 1440);

    assert(timeline.armPending(202, 7, 44100));
    assert(timeline.onPendingFramesRendered(999, 7, 441).event == Event::None);
    auto next = timeline.onPendingFramesRendered(202, 7, 441);
    assert(next.event == Event::Started);
    assert(next.position_frames == 441);
    return 0;
}
