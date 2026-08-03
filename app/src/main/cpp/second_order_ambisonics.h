#pragma once

#include <array>

/**
 * Fixed-source second-order Ambisonics encoder with optional world-locked head
 * tracking. Channel order is ACN/SN3D:
 *   0 W, 1 Y, 2 Z, 3 X, 4 V, 5 T, 6 R, 7 S, 8 U.
 */
class SecondOrderAmbisonicsEncoder {
public:
    static constexpr int kChannelCount = 9;
    static constexpr int kSourceCount = 7;

    SecondOrderAmbisonicsEncoder();

    void setHeadRotation(float quaternionX, float quaternionY, float quaternionZ, float quaternionW, float amount);
    void resetHeadRotation();

    void encode(
        const std::array<float, kSourceCount>& sources,
        std::array<float, kChannelCount>& bus
    ) const;

    static std::array<float, kChannelCount> sphericalHarmonics(
        float azimuthDegrees,
        float elevationDegrees
    );

private:
    struct Direction {
        float x = 1.0f;
        float y = 0.0f;
        float z = 0.0f;
    };

    static Direction directionFromDegrees(float azimuthDegrees, float elevationDegrees);
    static std::array<float, kChannelCount> sphericalHarmonics(const Direction& direction);
    static Direction rotateByInverseQuaternion(
        const Direction& direction,
        float x,
        float y,
        float z,
        float w
    );
    void rebuildCoefficients();

    std::array<Direction, kSourceCount> sourceDirection_{};
    std::array<std::array<float, kChannelCount>, kSourceCount> sourceCoefficients_{};
    float headQuaternionX_ = 0.0f;
    float headQuaternionY_ = 0.0f;
    float headQuaternionZ_ = 0.0f;
    float headQuaternionW_ = 1.0f;
    float headTrackingAmount_ = 0.0f;
};
