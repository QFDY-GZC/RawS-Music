#include "raw_transition_capabilities.h"

#include "raw_auto_crossfade.h"
#include "raw_gapless_stitcher.h"
#include "raw_manual_crossfade.h"
#include "raw_pcm_slot_queue.h"
#include "raw_render_handoff_barrier.h"
#include "raw_seek_barrier.h"
#include "raw_track_slot_state.h"
#include "raw_track_render_timeline.h"
#include "raw_transition_trace.h"
#include "raw_transport_fade.h"

namespace {

// These retained symbol references turn the capability mask into a link-time
// proof. If a feature source is removed from the final rawscoreservice link,
// the shared-library link fails instead of returning a false "complete" mask.
[[gnu::used]] const auto kTraceLinkProof = &rawsmusic::transition_trace::record;
[[gnu::used]] const auto kTrackSlotLinkProof = &rawsmusic::track_slot::isPendingReady;
[[gnu::used]] const auto kTrackTimelineLinkProof = &rawsmusic::track_timeline::Timeline::currentPositionFrames;
[[gnu::used]] const auto kPcmSlotLinkProof = &rawsmusic::pcm_slot_queue::availableFrames;
[[gnu::used]] const auto kContinuousPcmQueueLinkProof = &rawsmusic::pcm_slot_queue::maximumSlotBytes;
[[gnu::used]] const auto kAtomicPendingQueueBindLinkProof = &rawsmusic::track_slot::beginPending;
[[gnu::used]] const auto kHandoffLinkProof = &rawsmusic::render_handoff::commitAtBlockBoundary;
[[gnu::used]] const auto kTransportFadeLinkProof = &rawsmusic::transport_fade::Processor::current_gain;
[[gnu::used]] const auto kSeekBarrierLinkProof = &rawsmusic::seek_barrier::Barrier::snapshot;
[[gnu::used]] const auto kManualCrossfadeLinkProof = &rawsmusic::manual_crossfade::Processor::processed_frames;
[[gnu::used]] const auto kAutoCrossfadeLinkProof = &rawsmusic::auto_crossfade::Processor::consumed_frames;
[[gnu::used]] const auto kGaplessLinkProof = &rawsmusic::gapless::stitch_block;

}  // namespace

namespace rawsmusic::transition_capabilities {

uint64_t compiledFeatureMask() noexcept {
    return kRequiredPhase9Mask;
}

}  // namespace rawsmusic::transition_capabilities
