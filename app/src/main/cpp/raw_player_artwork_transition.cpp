#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace {

constexpr int kStateEntering = 0;
constexpr int kStateLeaving = 1;
constexpr int kStateRemoved = 2;
constexpr int kStateStable = 3;
constexpr int kMaxDrawCommands = 16;
constexpr int kFrameHeaderFloats = 4;
constexpr int kDrawStrideFloats = 4;

struct Entry {
    int token = 0;
    float progress = 0.0f;
    int durationMs = -1;
    int state = kStateStable;
};

struct Lane {
    std::vector<Entry> entries;
};

class ArtworkTransition {
public:
    void setArtwork(int token, bool primary, int requestGeneration, int durationMs) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (token == 0) return;

        const int laneIndex = ((primary ? 1 : 0) ^ (parity_ ? 1 : 0) ^
                               ((generation_ - requestGeneration) & 1));
        Lane& lane = lanes_[laneIndex & 1];

        if (!lane.entries.empty() && lane.entries.back().token == token) {
            Entry& first = lane.entries.front();
            configureEntering(first, durationMs);
            return;
        }

        if (durationMs > 0 && masterAlpha_ != 0.0f) {
            int stableIndex = -1;
            for (int i = static_cast<int>(lane.entries.size()) - 1; i >= 0; --i) {
                if (lane.entries[static_cast<size_t>(i)].progress == 1.0f) {
                    stableIndex = i;
                    break;
                }
            }

            if (stableIndex >= 0) {
                Entry& stable = lane.entries[static_cast<size_t>(stableIndex)];
                stable.progress = 1.0f;
                stable.durationMs = -1;
                stable.state = kStateStable;
                if (stableIndex >= 1) {
                    lane.entries.erase(lane.entries.begin(), lane.entries.begin() + stableIndex);
                    stableIndex = 0;
                }
            }

            const size_t leaveFrom = stableIndex >= 0 ? 1u : 0u;
            for (size_t i = leaveFrom; i < lane.entries.size(); ++i) {
                Entry& entry = lane.entries[i];
                entry.durationMs = durationMs;
                entry.state = kStateLeaving;
                entry.progress = std::clamp(entry.progress, 0.0f, 1.0f);
            }
        } else {
            lane.entries.clear();
        }

        Entry entry;
        entry.token = token;
        configureEntering(entry, durationMs);
        lane.entries.push_back(entry);
    }

    void setRatio(float ratio) {
        std::lock_guard<std::mutex> lock(mutex_);
        ratio_ = std::clamp(ratio, 0.0f, 1.0f);
    }

    void commit(int newGeneration, int flags) {
        std::lock_guard<std::mutex> lock(mutex_);

        if ((flags & 0x2) != 0) {
            laneForParity(parity_).entries.clear();
        }

        if (newGeneration != 0) {
            generation_ = newGeneration;
            parity_ = !parity_;
            ratio_ = 1.0f - ratio_;
        }

        if ((flags & 0x1) == 0) {
            laneForParity(parity_).entries.clear();
            ratio_ = 0.0f;
        }
    }

    bool advance(int64_t deltaNs, float* output, int outputCount) {
        std::lock_guard<std::mutex> lock(mutex_);
        const bool masterDone = updateAnimation(masterAlpha_, masterDurationMs_, masterState_, deltaNs);
        const bool lane0Done = updateLane(lanes_[0], deltaNs);
        const bool lane1Done = updateLane(lanes_[1], deltaNs);

        std::vector<DrawCommand> commands;
        commands.reserve(kMaxDrawCommands);
        buildDrawCommands(commands);
        cleanupLane(lanes_[0]);
        cleanupLane(lanes_[1]);

        const bool needsMoreFrames = !masterDone || !lane0Done || !lane1Done;
        if (output != nullptr && outputCount >= kFrameHeaderFloats) {
            std::fill(output, output + outputCount, 0.0f);
            output[0] = needsMoreFrames ? 1.0f : 0.0f;
            output[1] = ratio_;
            output[2] = parity_ ? 1.0f : 0.0f;
            const int capacity = (outputCount - kFrameHeaderFloats) / kDrawStrideFloats;
            const int count = std::min<int>(static_cast<int>(commands.size()), capacity);
            output[3] = static_cast<float>(count);
            for (int i = 0; i < count; ++i) {
                const DrawCommand& command = commands[static_cast<size_t>(i)];
                const int base = kFrameHeaderFloats + i * kDrawStrideFloats;
                output[base] = static_cast<float>(command.token);
                output[base + 1] = command.alpha;
                output[base + 2] = static_cast<float>(command.lane);
                output[base + 3] = command.progress;
            }
        }
        return needsMoreFrames;
    }

private:
    struct DrawCommand {
        int token;
        float alpha;
        int lane;
        float progress;
    };

    static void configureEntering(Entry& entry, int durationMs) {
        if (durationMs <= 0) {
            entry.progress = 1.0f;
            entry.durationMs = -1;
            entry.state = kStateStable;
        } else {
            entry.durationMs = durationMs;
            entry.state = kStateEntering;
            entry.progress = std::clamp(entry.progress, 0.0f, 1.0f);
        }
    }

    static bool updateAnimation(float& value, int& durationMs, int& state, int64_t deltaNs) {
        if (durationMs < 0) return true;
        if (durationMs == 0) {
            if (state == kStateEntering) {
                value = 1.0f;
                state = kStateStable;
            } else {
                value = 0.0f;
                state = kStateRemoved;
            }
            durationMs = -1;
            return true;
        }

        const float delta = static_cast<float>(deltaNs) /
                            (static_cast<float>(durationMs) * 1000000.0f);
        if (state == kStateEntering) {
            value += delta;
            if (value >= 1.0f) {
                value = 1.0f;
                durationMs = -1;
                state = kStateStable;
                return true;
            }
        } else {
            value -= delta;
            if (value <= 0.0f) {
                value = 0.0f;
                durationMs = -1;
                state = kStateRemoved;
                return true;
            }
        }
        return false;
    }

    static bool updateEntry(Entry& entry, int64_t deltaNs) {
        return updateAnimation(entry.progress, entry.durationMs, entry.state, deltaNs);
    }

    static bool updateLane(Lane& lane, int64_t deltaNs) {
        bool allDone = true;
        for (Entry& entry : lane.entries) {
            if (!updateEntry(entry, deltaNs)) allDone = false;
        }
        return allDone;
    }

    static float laneCoverage(const Lane& lane) {
        float maximum = 0.0f;
        for (auto it = lane.entries.rbegin(); it != lane.entries.rend(); ++it) {
            if (it->progress == 1.0f) return 1.0f;
            maximum = std::max(maximum, it->progress);
        }
        return maximum;
    }

    static void drawLane(const Lane& lane,
                         int laneIndex,
                         bool forceFirstAsBase,
                         float baseAlpha,
                         float regularAlpha,
                         std::vector<DrawCommand>& output) {
        bool force = forceFirstAsBase;
        for (const Entry& entry : lane.entries) {
            const float alpha = force ? baseAlpha : entry.progress * regularAlpha;
            if (alpha != 0.0f && output.size() < kMaxDrawCommands) {
                output.push_back({entry.token, alpha, laneIndex, entry.progress});
                force = false;
            }
        }
    }

    void buildDrawCommands(std::vector<DrawCommand>& output) const {
        const float lane0 = laneCoverage(lanes_[0]);
        float lane1 = laneCoverage(lanes_[1]);
        if (lane0 == 0.0f && lane1 == 0.0f) return;

        float foldedRatio = ratio_;
        bool localParity = parity_;
        if (foldedRatio > 0.5f) {
            localParity = !localParity;
            foldedRatio = 1.0f - foldedRatio;
        }

        const float dominantCoverage = localParity ? lane0 : lane1;
        const int dominantLane = localParity ? 0 : 1;
        if (!localParity) lane1 = lane0;
        const int secondaryLane = localParity ? 1 : 0;
        const float secondaryContribution = lane1 * foldedRatio;

        if (dominantCoverage > 0.0f) {
            drawLane(lanes_[dominantLane], dominantLane,
                     secondaryContribution < 1.0f,
                     masterAlpha_, masterAlpha_, output);
        }
        if (secondaryContribution > 0.0f) {
            drawLane(lanes_[secondaryLane], secondaryLane,
                     dominantCoverage == 0.0f,
                     masterAlpha_, foldedRatio * masterAlpha_, output);
        }
    }

    static void cleanupLane(Lane& lane) {
        for (int i = static_cast<int>(lane.entries.size()) - 1; i >= 0; --i) {
            const int state = lane.entries[static_cast<size_t>(i)].state;
            if (state == kStateRemoved) {
                lane.entries.erase(lane.entries.begin() + i);
            } else if (state == kStateStable && i != 0) {
                lane.entries.erase(lane.entries.begin(), lane.entries.begin() + i);
                return;
            }
        }
    }

    Lane& laneForParity(bool parity) {
        // Commit the inactive lane, then flip parity so the newly prepared lane becomes primary.
        return lanes_[parity ? 1 : 0];
    }

    std::mutex mutex_;
    float masterAlpha_ = 1.0f;
    int masterDurationMs_ = -1;
    int masterState_ = kStateStable;
    bool parity_ = false;
    int generation_ = 1;
    std::array<Lane, 2> lanes_;
    float ratio_ = 0.0f;
};

ArtworkTransition* fromHandle(jlong handle) {
    return reinterpret_cast<ArtworkTransition*>(static_cast<intptr_t>(handle));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_core_ui_widget_bitmaps_NativePlayerArtworkBridge_nativeCreate(
        JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(new ArtworkTransition()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_core_ui_widget_bitmaps_NativePlayerArtworkBridge_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_core_ui_widget_bitmaps_NativePlayerArtworkBridge_nativeSetArtwork(
        JNIEnv*, jobject, jlong handle, jint token, jboolean primary,
        jint requestGeneration, jint durationMs) {
    if (ArtworkTransition* state = fromHandle(handle)) {
        state->setArtwork(token, primary == JNI_TRUE, requestGeneration, durationMs);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_core_ui_widget_bitmaps_NativePlayerArtworkBridge_nativeSetRatio(
        JNIEnv*, jobject, jlong handle, jfloat ratio) {
    if (ArtworkTransition* state = fromHandle(handle)) {
        state->setRatio(ratio);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_core_ui_widget_bitmaps_NativePlayerArtworkBridge_nativeCommit(
        JNIEnv*, jobject, jlong handle, jint generation, jint flags) {
    if (ArtworkTransition* state = fromHandle(handle)) {
        state->commit(generation, flags);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_core_ui_widget_bitmaps_NativePlayerArtworkBridge_nativeAdvance(
        JNIEnv* env, jobject, jlong handle, jlong deltaNs, jfloatArray output) {
    ArtworkTransition* state = fromHandle(handle);
    if (state == nullptr || output == nullptr) return JNI_FALSE;

    const jsize count = env->GetArrayLength(output);
    jboolean isCopy = JNI_FALSE;
    jfloat* values = env->GetFloatArrayElements(output, &isCopy);
    if (values == nullptr) return JNI_FALSE;
    const bool active = state->advance(static_cast<int64_t>(deltaNs), values, count);
    env->ReleaseFloatArrayElements(output, values, 0);
    return active ? JNI_TRUE : JNI_FALSE;
}
