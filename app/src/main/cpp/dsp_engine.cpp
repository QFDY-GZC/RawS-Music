#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <vector>
#include <memory>
#include <cstring>
#include <cmath>

#define TAG "NativeDSP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class StereoExpander {
    float m_factor = 0.0f;
    float m_smoothedFactor = 0.0f;
    bool m_enabled = false;
    int m_sampleRate = 44100;

    // === Side 通道滤波器 ===
    float m_sideHpX1 = 0.0f, m_sideHpY1 = 0.0f;
    float m_sideHpAlpha = 0.0f;

    // === Mid 通道高频滤波器 (人声临场感增强) ===
    float m_midHpX1 = 0.0f, m_midHpY1 = 0.0f;
    float m_midHpAlpha = 0.0f;

    // === 立体声联动压限器 ===
    float m_limGain = 1.0f;
    float m_limAttack = 0.0f;
    float m_limRelease = 0.0f;
    float m_limThreshold = 0.85f;    

    void updateCoeffs() {
        // Side 高通：600 Hz，提取侧边高频
        float fc_side = 600.0f;
        float rc_side = 1.0f / (2.0f * (float)M_PI * fc_side);
        float dt = 1.0f / (float)m_sampleRate;
        m_sideHpAlpha = rc_side / (rc_side + dt);

        // Mid 高通：2500 Hz，提取人声临场感/齿音频段
        float fc_mid = 2500.0f;
        float rc_mid = 1.0f / (2.0f * (float)M_PI * fc_mid);
        m_midHpAlpha = rc_mid / (rc_mid + dt);

        // 压限器参数
        m_limAttack = 1.0f - expf(-1.0f / (0.0001f * m_sampleRate));
        m_limRelease = 1.0f - expf(-1.0f / (0.150f * m_sampleRate));
    }

public:
    StereoExpander() { updateCoeffs(); }

    void process(float* samples, int numFrames, int channels) {
        if (channels != 2 || !m_enabled) return;

        for (int i = 0; i < numFrames; ++i) {
            m_smoothedFactor += (m_factor - m_smoothedFactor) * 0.003f;

            float L = samples[i * 2];
            float R = samples[i * 2 + 1];

            // 1. M/S 变换
            float mid  = (L + R) * 0.5f;
            float side = (L - R) * 0.5f;

            // ==========================================
            // 2. Mid 通道：人声临场感增强
            // ==========================================
            float midHp = m_midHpAlpha * (m_midHpY1 + mid - m_midHpX1);
            m_midHpX1 = mid;
            m_midHpY1 = midHp;
            float midLp = mid - midHp; // Mid 的低频部分（底鼓、贝斯，绝不碰）

            // 增强中高频 Mid，让人声靠前。最大增强 1.5 倍
            float midPresenceGain = 1.0f + m_smoothedFactor * 0.5f;
            float outMid = midLp + midHp * midPresenceGain;

            // ==========================================
            // 3. Side 通道：立体声展宽
            // ==========================================
            float sideHp = m_sideHpAlpha * (m_sideHpY1 + side - m_sideHpX1);
            m_sideHpX1 = side;
            m_sideHpY1 = sideHp;
            float sideLp = side - sideHp;

            // 高频强力展宽 (3.0倍) 再衰减 -3dB (0.707)
            float highFreqExpandGain = (1.0f + m_smoothedFactor * 3.0f) * 0.707f;
            // 低频微弱展宽 (0.3倍)
            float lowFreqExpandGain = 1.0f + m_smoothedFactor * 0.3f;

            float outSide = sideLp * lowFreqExpandGain + sideHp * highFreqExpandGain;

            // ==========================================
            // 4. 重组 L/R
            // ==========================================
            float outL = outMid + outSide;
            float outR = outMid - outSide;

            // ==========================================
            // 5. 立体声联动压限器 (死守防爆音底线)
            // ==========================================
            float maxAbs = std::max(fabsf(outL), fabsf(outR));
            float targetGain = 1.0f;
            if (maxAbs > m_limThreshold) {
                targetGain = m_limThreshold / maxAbs;
            }

            if (targetGain < m_limGain) {
                m_limGain += (targetGain - m_limGain) * m_limAttack;
            } else {
                m_limGain += (targetGain - m_limGain) * m_limRelease;
            }

            outL *= m_limGain;
            outR *= m_limGain;

            outL = std::max(-1.0f, std::min(1.0f, outL));
            outR = std::max(-1.0f, std::min(1.0f, outR));

            samples[i * 2]     = outL;
            samples[i * 2 + 1] = outR;
        }
    }

    void setParameter(int paramId, float value) {
        if (paramId == 0) {
            bool wasEnabled = m_enabled;
            m_factor = value;
            m_enabled = value > 0.01f;
            if (!wasEnabled && m_enabled) {
                m_sideHpX1 = m_sideHpY1 = 0.0f;
                m_midHpX1 = m_midHpY1 = 0.0f;
                m_limGain = 1.0f;
            }
        }
    }

    void setSampleRate(int sampleRate) {
        if (sampleRate != m_sampleRate && sampleRate > 0) {
            m_sampleRate = sampleRate;
            m_sideHpX1 = m_sideHpY1 = 0.0f;
            m_midHpX1 = m_midHpY1 = 0.0f;
            m_limGain = 1.0f;
            updateCoeffs();
        }
    }

    bool isEnabled() const { return m_enabled; }
};

// ==========================================
// JNI 引擎框架 (预分配内存)
// ==========================================
class DSPChain {
    std::unique_ptr<StereoExpander> m_expander;
    std::vector<float> m_floatBuf;
    int m_sampleRate = 44100;
    int m_channels = 2;

public:
    DSPChain() : m_expander(std::make_unique<StereoExpander>()) {}

    void init(int sampleRate, int channels) {
        m_sampleRate = sampleRate;
        m_channels = channels;
        m_expander->setSampleRate(sampleRate);
        m_floatBuf.resize(48000 * 2);
    }

    void process(float* samples, int numFrames, int channels) {
        if (m_expander->isEnabled()) {
            m_expander->process(samples, numFrames, channels);
        }
    }

    float* getFloatBuffer() { return m_floatBuf.data(); }
    StereoExpander* getExpander() { return m_expander.get(); }
};

extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeCreate(
        JNIEnv*, jobject, jint sampleRate, jint channels) {
    auto* chain = new DSPChain();
    chain->init(sampleRate, channels);
    return reinterpret_cast<jlong>(chain);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeRelease(
        JNIEnv*, jobject, jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<DSPChain*>(handle);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetStereoWiden(
        JNIEnv*, jobject, jlong handle, jfloat factor) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    if (chain->getExpander()) {
        chain->getExpander()->setParameter(0, factor);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeProcess(
        JNIEnv* env, jobject, jlong handle, jshortArray buffer, jint length, jint channels) {
    if (handle == 0) return -1;

    jshort* samples = (jshort*)env->GetPrimitiveArrayCritical(buffer, nullptr);
    if (samples == nullptr) return -2;

    auto* chain = reinterpret_cast<DSPChain*>(handle);
    float* floatBuf = chain->getFloatBuffer();
    int numFrames = length / channels;

    for (int i = 0; i < length; ++i) {
        floatBuf[i] = (float)samples[i] / 32768.0f;
    }

    chain->process(floatBuf, numFrames, channels);

    for (int i = 0; i < length; ++i) {
        float v = floatBuf[i] * 32768.0f;
        v = std::max(-32768.0f, std::min(v, 32767.0f));
        samples[i] = (short)v;
    }

    env->ReleasePrimitiveArrayCritical(buffer, samples, 0);
    return 0;
}
