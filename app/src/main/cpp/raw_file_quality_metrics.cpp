#include "raw_file_quality_metrics.h"
#include "third_party/libebur128/ebur128.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr double kS32Scale = 2147483648.0;
float amplitudeDb(double amplitude) {
    // Preserve nonzero raw levels below the FFT display floor and TP above 0 dB.
    return amplitude == 0.0 ? -120.0f : static_cast<float>(20.0 * std::log10(amplitude));
}
int64_t magnitude(int32_t value) {
    const int64_t wide = value;
    return wide < 0 ? -wide : wide;
}
} // namespace

RawFileQualityMetrics::RawFileQualityMetrics(int sampleRate, int channels)
    : channels_(channels) {
    static_assert(sizeof(int) == sizeof(int32_t), "libebur128 requires 32-bit int PCM");
    // The upstream K-weighting shelf (1681.974 Hz) must be below Nyquist.
    // Invalid rates/layouts never prevent measurement of raw signal statistics.
    if (sampleRate < 3364 || sampleRate > 2822400 || channels < 1 || channels > 2) return;
    const uint64_t samples100ms = (static_cast<uint64_t>(sampleRate) + 5) / 10;
    // The LRA ring is 3 s of doubles. Limit its RAM to 64 MiB; at extreme rates
    // retain I/TP with a 400 ms ring and report LRA unavailable.
    const uint64_t lraBytes = samples100ms * 31 * channels * sizeof(double);
    const bool enableLra = lraBytes <= 64u * 1024u * 1024u;
    const int mode = EBUR128_MODE_I | EBUR128_MODE_TRUE_PEAK | EBUR128_MODE_HISTOGRAM |
        (enableLra ? EBUR128_MODE_LRA : 0);
    ebu_ = ebur128_init(static_cast<unsigned>(channels), static_cast<unsigned long>(sampleRate), mode);
    if (!ebu_) return;
    if (channels == 1) ebur128_set_channel(ebu_, 0, EBUR128_CENTER); // Mono, not dual mono.
    if (enableLra) {
        nextLraFrame_ = samples100ms * 30;
        lraHop_ = samples100ms * 10;
    }
}

RawFileQualityMetrics::~RawFileQualityMetrics() {
    if (ebu_) ebur128_destroy(&ebu_);
}

void RawFileQualityMetrics::addFrame(int32_t left, int32_t right, bool anyChannelAtHardRail) {
    if (finished_) return;
    ++frames_;
    const double l = static_cast<double>(left) / kS32Scale;
    const double r = static_cast<double>(right) / kS32Scale;
    leftPeak_ = std::max(leftPeak_, magnitude(left));
    rightPeak_ = std::max(rightPeak_, magnitude(right));
    leftSquareSum_ += l * l;
    rightSquareSum_ += r * r;
    // Welford covariance avoids catastrophic cancellation for quiet AC on DC.
    const double dl = l - leftMean_;
    const double dr = r - rightMean_;
    const double inverseCount = 1.0 / static_cast<double>(frames_);
    leftMean_ += dl * inverseCount;
    rightMean_ += dr * inverseCount;
    leftVarianceSum_ += dl * (l - leftMean_);
    rightVarianceSum_ += dr * (r - rightMean_);
    covarianceSum_ += dl * (r - rightMean_);
    if (channels_ > 1) {
        const double mid = (l + r) * 0.5;
        const double side = (l - r) * 0.5;
        midSquareSum_ += mid * mid;
        sideSquareSum_ += side * side;
    }
    if (std::abs(l) <= 1e-5 && std::abs(r) <= 1e-5) {
        ++result_.silentFrameCount;
        ++silentRun_;
        result_.longestSilentFrameCount = std::max(result_.longestSilentFrameCount, silentRun_);
    } else {
        silentRun_ = 0;
    }
    if (anyChannelAtHardRail) ++result_.hardRailFrameCount;
    if (!ebu_) return;
    const size_t offset = ebuBufferedFrames_ * channels_;
    ebuSamples_[offset] = left;
    if (channels_ == 2) ebuSamples_[offset + 1] = right;
    ++ebuBufferedFrames_;
    if (ebuBufferedFrames_ == 1024 || frames_ == nextLraFrame_) flushEbu();
    if (ebu_ && frames_ == nextLraFrame_) {
        double shortterm = 0.0;
        if (ebur128_loudness_shortterm(ebu_, &shortterm) == EBUR128_SUCCESS &&
            std::isfinite(shortterm) && shortterm >= -70.0) hasLraBlock_ = true;
        nextLraFrame_ += lraHop_;
    }
}

void RawFileQualityMetrics::flushEbu() {
    if (ebu_ && ebuBufferedFrames_ != 0 &&
        ebur128_add_frames_int(ebu_, ebuSamples_.data(), ebuBufferedFrames_) != EBUR128_SUCCESS) {
        ebur128_destroy(&ebu_);
    }
    ebuBufferedFrames_ = 0;
}

const RawFileQualityResult& RawFileQualityMetrics::finish() {
    if (finished_) return result_;
    finished_ = true;
    if (!frames_) return result_;
    result_.flags = 1;
    const double count = static_cast<double>(frames_);
    result_.leftPeakDb = amplitudeDb(static_cast<double>(leftPeak_) / kS32Scale);
    result_.rightPeakDb = amplitudeDb(static_cast<double>(rightPeak_) / kS32Scale);
    result_.leftRmsDb = amplitudeDb(std::sqrt(leftSquareSum_ / count));
    result_.rightRmsDb = amplitudeDb(std::sqrt(rightSquareSum_ / count));
    result_.leftDcOffset = static_cast<float>(leftMean_);
    if (channels_ > 1) {
        result_.rightDcOffset = static_cast<float>(rightMean_);
        result_.midRmsDb = amplitudeDb(std::sqrt(midSquareSum_ / count));
        result_.sideRmsDb = amplitudeDb(std::sqrt(sideSquareSum_ / count));
        if (result_.silentFrameCount < frames_ && leftVarianceSum_ > 0.0 && rightVarianceSum_ > 0.0) {
            result_.stereoCorrelation = static_cast<float>(std::clamp(
                covarianceSum_ / std::sqrt(leftVarianceSum_ * rightVarianceSum_), -1.0, 1.0));
        }
    }
    flushEbu();
    if (!ebu_) return result_;
    double value = 0.0;
    if (ebur128_loudness_global(ebu_, &value) == EBUR128_SUCCESS && std::isfinite(value)) {
        result_.integratedLufs = static_cast<float>(value);
        result_.flags |= 2;
    }
    // Upstream returns zero also when no eligible LRA blocks exist. Distinguish
    // that unavailable result from a measured zero range for a steady tone.
    if (hasLraBlock_ && ebur128_loudness_range(ebu_, &value) == EBUR128_SUCCESS &&
        std::isfinite(value)) {
        result_.loudnessRangeLu = static_cast<float>(value);
        result_.flags |= 4;
    }
    // Drain the FIR delay only after taking loudness snapshots. Padding is not
    // raw input and must not alter raw frame counts, RMS, DC, silence or LRA.
    if (ebu_->samplerate < 192000) {
        std::fill(ebuSamples_.begin(), ebuSamples_.end(), 0);
        if (ebur128_add_frames_int(ebu_, ebuSamples_.data(), 32) != EBUR128_SUCCESS) return result_;
    }
    double peak = 0.0;
    for (int channel = 0; channel < channels_; ++channel) {
        if (ebur128_true_peak(ebu_, static_cast<unsigned>(channel), &value) != EBUR128_SUCCESS ||
            !std::isfinite(value)) return result_;
        peak = std::max(peak, value);
    }
    if (peak > 0.0) {
        result_.truePeakDb = amplitudeDb(peak);
        result_.flags |= 8;
    }
    return result_;
}
