#include "second_order_ambisonics.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kDegreesToRadians = kPi / 180.0f;
constexpr float kSqrt3 = 1.73205080756887729353f;
}

SecondOrderAmbisonicsEncoder::SecondOrderAmbisonicsEncoder() {
    sourceDirection_[0] = directionFromDegrees(0.0f, 0.0f);
    sourceDirection_[1] = directionFromDegrees(-58.0f, 0.0f);
    sourceDirection_[2] = directionFromDegrees(58.0f, 0.0f);
    sourceDirection_[3] = directionFromDegrees(-128.0f, 0.0f);
    sourceDirection_[4] = directionFromDegrees(128.0f, 0.0f);
    sourceDirection_[5] = directionFromDegrees(-52.0f, 43.0f);
    sourceDirection_[6] = directionFromDegrees(52.0f, 43.0f);
    rebuildCoefficients();
}

SecondOrderAmbisonicsEncoder::Direction
SecondOrderAmbisonicsEncoder::directionFromDegrees(
    float azimuthDegrees,
    float elevationDegrees
) {
    const float azimuth = azimuthDegrees * kDegreesToRadians;
    const float elevation = elevationDegrees * kDegreesToRadians;
    const float cosElevation = std::cos(elevation);
    return {
        std::cos(azimuth) * cosElevation,
        std::sin(azimuth) * cosElevation,
        std::sin(elevation),
    };
}

std::array<float, SecondOrderAmbisonicsEncoder::kChannelCount>
SecondOrderAmbisonicsEncoder::sphericalHarmonics(const Direction& direction) {
    const float x = direction.x;
    const float y = direction.y;
    const float z = direction.z;
    std::array<float, kChannelCount> result{};
    result[0] = 1.0f;
    result[1] = y;
    result[2] = z;
    result[3] = x;
    result[4] = kSqrt3 * x * y;
    result[5] = kSqrt3 * y * z;
    result[6] = 0.5f * (3.0f * z * z - 1.0f);
    result[7] = kSqrt3 * x * z;
    result[8] = 0.5f * kSqrt3 * (x * x - y * y);
    return result;
}

std::array<float, SecondOrderAmbisonicsEncoder::kChannelCount>
SecondOrderAmbisonicsEncoder::sphericalHarmonics(
    float azimuthDegrees,
    float elevationDegrees
) {
    return sphericalHarmonics(directionFromDegrees(azimuthDegrees, elevationDegrees));
}

SecondOrderAmbisonicsEncoder::Direction
SecondOrderAmbisonicsEncoder::rotateByInverseQuaternion(
    const Direction& direction,
    float x,
    float y,
    float z,
    float w
) {
    // Rotate by q^-1. q is normalized by setHeadRotation().
    const float ix = -x;
    const float iy = -y;
    const float iz = -z;
    const float iw = w;

    // Quaternion-vector multiplication, expanded to avoid temporary objects.
    const float tx = 2.0f * (iy * direction.z - iz * direction.y);
    const float ty = 2.0f * (iz * direction.x - ix * direction.z);
    const float tz = 2.0f * (ix * direction.y - iy * direction.x);
    return {
        direction.x + iw * tx + (iy * tz - iz * ty),
        direction.y + iw * ty + (iz * tx - ix * tz),
        direction.z + iw * tz + (ix * ty - iy * tx),
    };
}

void SecondOrderAmbisonicsEncoder::setHeadRotation(
    float quaternionX,
    float quaternionY,
    float quaternionZ,
    float quaternionW,
    float amount
) {
    const float norm = std::sqrt(
        quaternionX * quaternionX + quaternionY * quaternionY +
        quaternionZ * quaternionZ + quaternionW * quaternionW
    );
    if (!std::isfinite(norm) || norm < 1.0e-6f) {
        resetHeadRotation();
        return;
    }
    const float inverseNorm = 1.0f / norm;
    headQuaternionX_ = quaternionX * inverseNorm;
    headQuaternionY_ = quaternionY * inverseNorm;
    headQuaternionZ_ = quaternionZ * inverseNorm;
    headQuaternionW_ = quaternionW * inverseNorm;
    headTrackingAmount_ = std::max(0.0f, std::min(1.0f, amount));
    rebuildCoefficients();
}

void SecondOrderAmbisonicsEncoder::resetHeadRotation() {
    headQuaternionX_ = 0.0f;
    headQuaternionY_ = 0.0f;
    headQuaternionZ_ = 0.0f;
    headQuaternionW_ = 1.0f;
    headTrackingAmount_ = 0.0f;
    rebuildCoefficients();
}

void SecondOrderAmbisonicsEncoder::rebuildCoefficients() {
    for (int source = 0; source < kSourceCount; ++source) {
        const Direction base = sourceDirection_[static_cast<size_t>(source)];
        const Direction rotated = rotateByInverseQuaternion(
            base,
            headQuaternionX_,
            headQuaternionY_,
            headQuaternionZ_,
            headQuaternionW_
        );
        Direction blended{
            base.x + (rotated.x - base.x) * headTrackingAmount_,
            base.y + (rotated.y - base.y) * headTrackingAmount_,
            base.z + (rotated.z - base.z) * headTrackingAmount_,
        };
        const float norm = std::sqrt(
            blended.x * blended.x + blended.y * blended.y + blended.z * blended.z
        );
        if (norm > 1.0e-6f) {
            blended.x /= norm;
            blended.y /= norm;
            blended.z /= norm;
        }
        sourceCoefficients_[static_cast<size_t>(source)] = sphericalHarmonics(blended);
    }
}

void SecondOrderAmbisonicsEncoder::encode(
    const std::array<float, kSourceCount>& sources,
    std::array<float, kChannelCount>& bus
) const {
    bus.fill(0.0f);
    for (int source = 0; source < kSourceCount; ++source) {
        const float sample = sources[static_cast<size_t>(source)];
        const auto& coefficient = sourceCoefficients_[static_cast<size_t>(source)];
        for (int channel = 0; channel < kChannelCount; ++channel) {
            bus[static_cast<size_t>(channel)] += sample * coefficient[static_cast<size_t>(channel)];
        }
    }

    constexpr float kEncoderNormalization = 0.36f;
    for (float& sample : bus) sample *= kEncoderNormalization;
}
