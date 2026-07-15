#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <vector>
#include <memory>
#include <cstring>
#include <cmath>
#include <math.h>
#include "raw_fft_convolver.h"
#include "speaker_output_effect.h"
#include "stereo_width_processor.h"

using namespace std;

#define TAG "NativeDSP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// 频率转换系数：标准 RBJ 使用 2π
#define FREQ_CONST (2.0 * M_PI)

static inline float sanitizeAndLimitSample(float sample) {
    if (!std::isfinite(sample)) {
        return 0.0f;
    }

    constexpr float threshold = 0.95f;
    constexpr float ceiling = 0.999f;
    const float absSample = std::fabs(sample);
    if (absSample <= threshold) {
        return sample;
    }

    const float sign = sample < 0.0f ? -1.0f : 1.0f;
    const float over = (absSample - threshold) / (ceiling - threshold);
    const float shaped = threshold + (ceiling - threshold) * std::tanh(over);
    return sign * std::min(shaped, ceiling);
}

static inline void applyOutputSafetyLimiter(float* samples, int length) {
    for (int i = 0; i < length; ++i) {
        samples[i] = sanitizeAndLimitSample(samples[i]);
    }
}

// 滤波器类型枚举
enum FilterType {
    FILTER_PEAK = 0,        // 峰值EQ (RBJ标准)
    FILTER_LOW_SHELF = 1,   // 低架滤波
    FILTER_HIGH_SHELF = 2,  // 高架滤波
    FILTER_LOW_PASS = 3,    // 低通 (高切)
    FILTER_HIGH_PASS = 4,   // 高通 (低切)
    FILTER_BAND_PASS = 5,   // 带通
    FILTER_NOTCH = 6,       // 陷波
    FILTER_PEAK_ANALOG = 7  // 峰值EQ (模拟建模)
};

// 滤波器参数结构
struct FilterParams {
    FilterType type;
    float frequency;    // 中心频率 (Hz)
    float gainDB;       // 增益 (dB)
    float Q;            // 品质因数
    bool enabled;       // 是否启用
};

// BiQuad滤波器系数 (float精度足够音频处理，减少内存带宽)
struct BiQuadCoeffs {
    float b0, b1, b2;  // 分子系数
    float a0, a1, a2;  // 分母系数 (a0归一化后为1)
};

// BiQuad滤波器类
class BiQuad {
public:
    BiQuadCoeffs coeffs;

    // 历史状态变量 - 数组索引消除分支: [ch][0]=x1, [ch][1]=x2, [ch][2]=y1, [ch][3]=y2
    // ch=0: Left/Mono, ch=1: Right
    float m_state[2][4];

    BiQuad() { reset(); }

    void reset() {
        coeffs = {1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f};
        memset(m_state, 0, sizeof(m_state));
    }

    // 统一归一化入口 (参数保留double保证系数计算精度，存储为float)
    void setCoeffs(double b0, double b1, double b2, double a0, double a1, double a2) {
        coeffs.b0 = (float)(b0 / a0);
        coeffs.b1 = (float)(b1 / a0);
        coeffs.b2 = (float)(b2 / a0);
        coeffs.a0 = 1.0f;
        coeffs.a1 = (float)(a1 / a0);
        coeffs.a2 = (float)(a2 / a0);
    }

    // 核心：差分方程 y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
    // ch: 0=Left/Mono, 1=Right (数组索引，无分支)
    float processSample(float input, int ch) {
        float* s = m_state[ch];
        float output = coeffs.b0 * input + coeffs.b1 * s[0] + coeffs.b2 * s[1]
                       - coeffs.a1 * s[2] - coeffs.a2 * s[3];
        s[1] = s[0]; s[0] = input;
        s[3] = s[2]; s[2] = output;
        return output;
    }

    // RBJ标准峰值EQ
    void setPEQ_RBJ(float sampleRate, float frequency, float Q, float gainDB) {
        double A = pow(10.0, gainDB / 40.0);
        double w0 = (2.0 * M_PI * frequency) / sampleRate;
        w0 = max(min(w0, 3.0013), 0.00000001);

        double sinw0 = sin(w0);
        double cosw0 = cos(w0);
        double alpha = sinw0 / (2.0 * max(Q, 0.00000001f));

        double b0 = 1.0 + alpha * A;
        double b1 = -2.0 * cosw0;
        double b2 = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha / A;

        setCoeffs(b0, b1, b2, a0, a1, a2);
    }

    // 二阶低通 (高切)
    void setLP2_RBJ(float sampleRate, float frequency, float Q) {
        double w0 = (2.0 * M_PI * frequency) / sampleRate;
        w0 = max(min(w0, 3.0013), 0.00000001);

        double sinw0 = sin(w0);
        double cosw0 = cos(w0);
        double alpha = sinw0 / (2.0 * max(Q, 0.00000001f));

        double b0 = (1.0 - cosw0) / 2.0;
        double b1 = 1.0 - cosw0;
        double b2 = (1.0 - cosw0) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;

        setCoeffs(b0, b1, b2, a0, a1, a2);
    }

    // 二阶全通 (仅移相不改幅度, 用于后方声像去相关)
    // H(s) = (s² - s/Q + 1) / (s² + s/Q + 1)
    void setAP2_RBJ(float sampleRate, float frequency, float Q) {
        double w0 = (2.0 * M_PI * frequency) / sampleRate;
        w0 = max(min(w0, 3.0013), 0.00000001);

        double sinw0 = sin(w0);
        double cosw0 = cos(w0);
        double alpha = sinw0 / (2.0 * max(Q, 0.00000001f));

        double b0 = 1.0 - alpha;
        double b1 = -2.0 * cosw0;
        double b2 = 1.0 + alpha;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;

        setCoeffs(b0, b1, b2, a0, a1, a2);
    }

    // 二阶高通 (低切)
    void setHP2_RBJ(float sampleRate, float frequency, float Q) {
        double w0 = (2.0 * M_PI * frequency) / sampleRate;
        w0 = max(min(w0, 3.0013), 0.00000001);

        double sinw0 = sin(w0);
        double cosw0 = cos(w0);
        double alpha = sinw0 / (2.0 * max(Q, 0.00000001f));

        double b0 = (1.0 + cosw0) / 2.0;
        double b1 = -(1.0 + cosw0);
        double b2 = (1.0 + cosw0) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;

        setCoeffs(b0, b1, b2, a0, a1, a2);
    }

    // 二阶低架滤波
    void setLS2_RBJ(float sampleRate, float frequency, float Q, float gainDB) {
        double A = pow(10.0, gainDB / 40.0);
        double w0 = (2.0 * M_PI * frequency) / sampleRate;
        w0 = max(min(w0, 3.0013), 0.00000001);

        double sinw0 = sin(w0);
        double cosw0 = cos(w0);
        double alpha = sinw0 / (2.0 * max(Q, 0.00000001f));
        double sqrtA = sqrt(A);

        double b0 = A * ((A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha);
        double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0);
        double b2 = A * ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha);
        double a0 = (A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
        double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0);
        double a2 = (A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha;

        setCoeffs(b0, b1, b2, a0, a1, a2);
    }

    // 二阶高架滤波
    void setHS2_RBJ(float sampleRate, float frequency, float Q, float gainDB) {
        double A = pow(10.0, gainDB / 40.0);
        double w0 = (2.0 * M_PI * frequency) / sampleRate;
        w0 = max(min(w0, 3.0013), 0.00000001);

        double sinw0 = sin(w0);
        double cosw0 = cos(w0);
        double alpha = sinw0 / (2.0 * max(Q, 0.00000001f));
        double sqrtA = sqrt(A);

        double b0 = A * ((A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha);
        double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0);
        double b2 = A * ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha);
        double a0 = (A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
        double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosw0);
        double a2 = (A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha;

        setCoeffs(b0, b1, b2, a0, a1, a2);
    }

    // 带通滤波器 (Constant 0 dB Peak Gain)
    void setBP(float sampleRate, float frequency, float Q, float gainDB, bool invertPhase, bool altQ) {
        double w0 = (2.0 * M_PI * frequency) / sampleRate;
        w0 = max(min(w0, 3.0013), 0.00000001);
        double sinw0 = sin(w0);
        double cosw0 = cos(w0);
        double alpha = sinw0 / (2.0 * max(Q, 0.00000001f));

        double b0, b1, b2, a0, a1, a2;
        if (altQ) {
            // 带通 (峰值增益不依赖带宽)
            b0 = alpha;
            b1 = 0.0;
            b2 = -alpha;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosw0;
            a2 = 1.0 - alpha;
        } else {
            // 带通 (0dB 峰值增益)
            b0 = sinw0 / 2.0; // or: alpha
            b1 = 0.0;
            b2 = -sinw0 / 2.0; // or: -alpha
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosw0;
            a2 = 1.0 - alpha;
        }

        // 应用增益
        double A = pow(10.0, gainDB / 40.0);
        b0 *= A;
        b1 *= A;
        b2 *= A;

        if (invertPhase) { b0 = -b0; b1 = -b1; b2 = -b2; }
        setCoeffs(b0, b1, b2, a0, a1, a2);
    }

    // 陷波滤波器
    void setNotch(float sampleRate, float frequency, float Q, float gainDB, bool invertPhase, bool altQ) {
        double w0 = (2.0 * M_PI * frequency) / sampleRate;
        w0 = max(min(w0, 3.0013), 0.00000001);
        double sinw0 = sin(w0);
        double cosw0 = cos(w0);
        double alpha = sinw0 / (2.0 * max(Q, 0.00000001f));

        double b0 = 1.0;
        double b1 = -2.0 * cosw0;
        double b2 = 1.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;

        // 应用增益
        double A = pow(10.0, gainDB / 40.0);
        b0 *= A;
        b1 *= A;
        b2 *= A;

        if (invertPhase) { b0 = -b0; b1 = -b1; b2 = -b2; }
        setCoeffs(b0, b1, b2, a0, a1, a2);
    }

    // 计算频率响应幅度 (dB) - 使用复数向量模长法
    float calcMagnitude(float freq, float sampleRate) const {
        double w = 2.0 * M_PI * freq / sampleRate;

        // H(e^jw) = (b0 + b1*e^-jw + b2*e^-2jw) / (1 + a1*e^-jw + a2*e^-2jw)
        // e^-jw = cos(w) - j*sin(w)
        double cosW = cos(w);
        double sinW = sin(w);
        double cos2W = cos(2.0 * w);
        double sin2W = sin(2.0 * w);

        // 分子: b0 + b1*cos(w) + b2*cos(2w) - j*(b1*sin(w) + b2*sin(2w))
        double numRe = coeffs.b0 + coeffs.b1 * cosW + coeffs.b2 * cos2W;
        double numIm = -(coeffs.b1 * sinW + coeffs.b2 * sin2W);

        // 分母: 1 + a1*cos(w) + a2*cos(2w) - j*(a1*sin(w) + a2*sin(2w))
        double denRe = 1.0 + coeffs.a1 * cosW + coeffs.a2 * cos2W;
        double denIm = -(coeffs.a1 * sinW + coeffs.a2 * sin2W);

        // |H| = |num| / |den|
        double numMag = sqrt(numRe * numRe + numIm * numIm);
        double denMag = sqrt(denRe * denRe + denIm * denIm);

        if (denMag < 1e-30) return 0.0f;
        return (float)(20.0 * log10(numMag / denMag));
    }
};

// 参量均衡器类
class ParametricEQ {
    static constexpr int MAX_FILTERS = 40;
    BiQuad m_filters[MAX_FILTERS];
    FilterParams m_params[MAX_FILTERS];
    int m_numFilters = 0;
    int m_sampleRate = 44100;
    bool m_enabled = false;
    float m_preampDB = 0.0f;       // 前置放大器增益 (dB)
    float m_preampLinear = 1.0f;   // 缓存线性增益，避免每次process计算powf
    int m_enabledIndices[MAX_FILTERS]; // 预计算启用的滤波器索引
    int m_numEnabled = 0;          // 启用的滤波器数量

    std::atomic<bool> m_pendingEnabled{false};
    std::atomic<float> m_pendingPreampDB{0.0f};
    std::atomic<uint64_t> m_filterDirtyMask{0};
    std::atomic<int> m_pendingClearGeneration{0};
    std::atomic<int> m_appliedClearGeneration{0};
    std::atomic<int> m_filterGeneration{0};
    std::atomic<int> m_pendingRemoveIndex{-1};

    struct AtomicFilterParams {
        std::atomic<int> generation{0};
        std::atomic<int> type{FILTER_PEAK};
        std::atomic<float> frequency{1000.0f};
        std::atomic<float> gainDB{0.0f};
        std::atomic<float> q{1.0f};
        std::atomic<bool> enabled{false};
    };
    AtomicFilterParams m_pendingParams[MAX_FILTERS];

public:
    ParametricEQ() {
        for (int i = 0; i < MAX_FILTERS; i++) {
            m_params[i] = {FILTER_PEAK, 1000.0f, 0.0f, 1.0f, false};
        }
        m_numEnabled = 0;
    }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0) {
            m_sampleRate = sampleRate;
            updateAllCoeffs();
        }
    }

    void setEnabled(bool enabled) {
        m_pendingEnabled.store(enabled, std::memory_order_release);
    }

    bool isEnabled() const { return m_pendingEnabled.load(std::memory_order_acquire); }

    void setPreamp(float gainDB) {
        m_pendingPreampDB.store(gainDB, std::memory_order_release);
    }

    float getPreamp() const { return m_preampDB; }

    int getNumFilters() const { return m_numFilters; }

    void setFilter(int index, const FilterParams& params) {
        if (index < 0 || index >= MAX_FILTERS) return;
        int generation = m_filterGeneration.load(std::memory_order_acquire);
        m_pendingParams[index].generation.store(generation, std::memory_order_relaxed);
        m_pendingParams[index].type.store((int)params.type, std::memory_order_relaxed);
        m_pendingParams[index].frequency.store(params.frequency, std::memory_order_relaxed);
        m_pendingParams[index].gainDB.store(params.gainDB, std::memory_order_relaxed);
        m_pendingParams[index].q.store(params.Q, std::memory_order_relaxed);
        m_pendingParams[index].enabled.store(params.enabled, std::memory_order_relaxed);
        m_filterDirtyMask.fetch_or(1ull << index, std::memory_order_release);
    }

    FilterParams getFilter(int index) const {
        if (index < 0 || index >= MAX_FILTERS) return {FILTER_PEAK, 1000.0f, 0.0f, 1.0f, false};
        return m_params[index];
    }

    void removeFilter(int index) {
        if (index < 0 || index >= MAX_FILTERS) return;
        m_pendingRemoveIndex.store(index, std::memory_order_release);
    }

    void clearAll() {
        m_filterGeneration.fetch_add(1, std::memory_order_acq_rel);
        m_pendingClearGeneration.fetch_add(1, std::memory_order_acq_rel);
    }

    // 计算总频率响应 (用于绘制曲线)
    void calcFrequencyResponse(float* frequencies, float* magnitudes, int numPoints) {
        applyPendingParams();
        const int numEnabled = m_numEnabled;
        const int* indices = m_enabledIndices;
        const float preamp = m_preampDB;

        for (int i = 0; i < numPoints; i++) {
            float totalMag = preamp;  // 前置放大器增益

            for (int f = 0; f < numEnabled; f++) {
                totalMag += m_filters[indices[f]].calcMagnitude(frequencies[i], m_sampleRate);
            }

            magnitudes[i] = totalMag;
        }
    }

    // 处理音频数据 - 真正的 BiQuad IIR 滤波
    void process(float* samples, int numFrames, int channels) {
        applyPendingParams();
        if (!m_enabled) return;

        const int numEnabled = m_numEnabled;
        const int* indices = m_enabledIndices;
        const float gain = m_preampLinear;

        for (int i = 0; i < numFrames; i++) {
            for (int ch = 0; ch < channels; ch++) {
                float sample = samples[i * channels + ch] * gain;  // 应用前置放大器(已缓存)
                // 仅遍历启用的滤波器(预计算索引)
                for (int f = 0; f < numEnabled; f++) {
                    sample = m_filters[indices[f]].processSample(sample, ch);
                }
                samples[i * channels + ch] = sample;
            }
        }
    }

private:
    void applyPendingParams() {
        bool enabled = m_pendingEnabled.load(std::memory_order_acquire);
        if (enabled != m_enabled) {
            m_enabled = enabled;
        }

        float pendingPreamp = m_pendingPreampDB.load(std::memory_order_acquire);
        if (pendingPreamp != m_preampDB) {
            m_preampDB = pendingPreamp;
            m_preampLinear = powf(10.0f, pendingPreamp / 20.0f);
        }

        int clearGeneration = m_pendingClearGeneration.load(std::memory_order_acquire);
        if (clearGeneration != m_appliedClearGeneration.load(std::memory_order_relaxed)) {
            m_numFilters = 0;
            for (int i = 0; i < MAX_FILTERS; i++) {
                m_params[i] = {FILTER_PEAK, 1000.0f, 0.0f, 1.0f, false};
                m_filters[i].reset();
                // 不修改 m_pendingParams[i].enabled，因为后续 dirty filter 处理
                // 会从 m_pendingParams 读取 enabled 值（setFilter 已设置），
                // 如果这里覆盖为 false 会导致 setFilter 的 enabled=true 被丢失
            }
            m_numEnabled = 0;
            m_appliedClearGeneration.store(clearGeneration, std::memory_order_release);
        }

        int removeIndex = m_pendingRemoveIndex.exchange(-1, std::memory_order_acq_rel);
        if (removeIndex >= 0 && removeIndex < m_numFilters) {
            for (int i = removeIndex; i < m_numFilters - 1; i++) {
                m_params[i] = m_params[i + 1];
                m_filters[i] = m_filters[i + 1];
            }
            m_numFilters--;
            m_params[m_numFilters] = {FILTER_PEAK, 1000.0f, 0.0f, 1.0f, false};
            m_filters[m_numFilters].reset();
            rebuildEnabledIndices();
        }

        uint64_t dirty = m_filterDirtyMask.exchange(0, std::memory_order_acq_rel);
        const bool hadDirtyFilters = dirty != 0;
        while (dirty != 0) {
            int index = __builtin_ctzll(dirty);
            dirty &= ~(1ull << index);
            if (index < 0 || index >= MAX_FILTERS) continue;
            int paramGeneration = m_pendingParams[index].generation.load(std::memory_order_relaxed);
            int currentGeneration = m_filterGeneration.load(std::memory_order_acquire);
            if (paramGeneration != currentGeneration) continue;

            int type = m_pendingParams[index].type.load(std::memory_order_relaxed);
            if (type < FILTER_PEAK || type > FILTER_PEAK_ANALOG) {
                type = FILTER_PEAK;
            }
            float freq = m_pendingParams[index].frequency.load(std::memory_order_relaxed);
            float gain = m_pendingParams[index].gainDB.load(std::memory_order_relaxed);
            float q    = m_pendingParams[index].q.load(std::memory_order_relaxed);
            bool  en   = m_pendingParams[index].enabled.load(std::memory_order_relaxed);

            // 参数安全保护：防止异常预设/导入数据导致滤波器不稳定或爆音
            float nyquist = (float)m_sampleRate * 0.5f;
            freq = std::max(10.0f, std::min(freq, nyquist * 0.95f));
            gain = std::max(-24.0f, std::min(gain, 24.0f));
            q    = std::max(0.05f, std::min(q, 24.0f));

            m_params[index] = {
                static_cast<FilterType>(type),
                freq, gain, q, en
            };
            updateFilterCoeff(index);
            if (index >= m_numFilters) {
                m_numFilters = index + 1;
            }
        }

        if (hadDirtyFilters) {
            rebuildEnabledIndices();
        }
    }

    void rebuildEnabledIndices() {
        m_numEnabled = 0;
        for (int i = 0; i < m_numFilters; i++) {
            if (m_params[i].enabled) {
                m_enabledIndices[m_numEnabled++] = i;
            }
        }
    }

    void updateFilterCoeff(int index) {
        if (index < 0 || index >= MAX_FILTERS) return;

        const FilterParams& p = m_params[index];

        switch (p.type) {
            case FILTER_PEAK:
                m_filters[index].setPEQ_RBJ(m_sampleRate, p.frequency, p.Q, p.gainDB);
                break;
            case FILTER_LOW_SHELF:
                m_filters[index].setLS2_RBJ(m_sampleRate, p.frequency, p.Q, p.gainDB);
                break;
            case FILTER_HIGH_SHELF:
                m_filters[index].setHS2_RBJ(m_sampleRate, p.frequency, p.Q, p.gainDB);
                break;
            case FILTER_LOW_PASS:
                m_filters[index].setLP2_RBJ(m_sampleRate, p.frequency, p.Q);
                break;
            case FILTER_HIGH_PASS:
                m_filters[index].setHP2_RBJ(m_sampleRate, p.frequency, p.Q);
                break;
            case FILTER_BAND_PASS:
                m_filters[index].setBP(m_sampleRate, p.frequency, p.Q, p.gainDB, false, false);
                break;
            case FILTER_NOTCH:
                m_filters[index].setNotch(m_sampleRate, p.frequency, p.Q, p.gainDB, false, false);
                break;
            case FILTER_PEAK_ANALOG:
                // 模拟峰值暂用标准RBJ实现
                m_filters[index].setPEQ_RBJ(m_sampleRate, p.frequency, p.Q, p.gainDB);
                break;
        }
    }

    void updateAllCoeffs() {
        for (int i = 0; i < m_numFilters; i++) {
            updateFilterCoeff(i);
        }
    }
};

// 互馈 (Crossfeed) 类 - 模拟音箱串音，消除头中效应
// 信号流：对侧声道 → 高通(低切) → 低通(高切) → 衰减 → 混入本侧
class Crossfeed {
    BiQuad m_hp;  // 高通滤波器 (低切)
    BiQuad m_lp;  // 低通滤波器 (高切)
    bool m_enabled = false;
    int m_sampleRate = 44100;

    // 参数
    float m_lowCutFreq = 300.0f;    // 高通截止频率 (Hz)
    float m_highCutFreq = 2000.0f;  // 低通截止频率 (Hz)
    float m_attenuationDB = 6.0f;   // 互馈衰减量 (dB)
    float m_crossGainLinear = powf(10.0f, -6.0f / 20.0f); // 缓存线性增益
    std::atomic<bool> m_pendingEnabled{false};
    std::atomic<float> m_pendingLowCutFreq{300.0f};
    std::atomic<float> m_pendingHighCutFreq{2000.0f};
    std::atomic<float> m_pendingAttenuationDB{6.0f};
    std::atomic<bool> m_paramsDirty{false};

    void updateCoeffs() {
        // Q=0.707 巴特沃斯响应
        m_hp.setHP2_RBJ(m_sampleRate, m_lowCutFreq, 0.707f);
        m_lp.setLP2_RBJ(m_sampleRate, m_highCutFreq, 0.707f);
    }

public:
    Crossfeed() { updateCoeffs(); }

    void setEnabled(bool enabled) {
        m_pendingEnabled.store(enabled, std::memory_order_release);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    bool isEnabled() const { return m_pendingEnabled.load(std::memory_order_acquire); }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0 && sampleRate != m_sampleRate) {
            m_sampleRate = sampleRate;
            m_hp.reset();
            m_lp.reset();
            updateCoeffs();
        }
    }

    void setLowCutFreq(float freq) {
        m_pendingLowCutFreq.store(freq, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    void setHighCutFreq(float freq) {
        m_pendingHighCutFreq.store(freq, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    void setAttenuationDB(float db) {
        m_pendingAttenuationDB.store(db, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    float getLowCutFreq() const { return m_lowCutFreq; }
    float getHighCutFreq() const { return m_highCutFreq; }
    float getAttenuationDB() const { return m_attenuationDB; }

    // 处理立体声音频
    void process(float* samples, int numFrames, int channels) {
        applyPendingParams();
        if (!m_enabled || channels < 2) return;

        const float crossGain = m_crossGainLinear; // 已缓存

        for (int i = 0; i < numFrames; i++) {
            float L = samples[i * 2];
            float R = samples[i * 2 + 1];

            // L→R 互馈：L 信号经 HP+LP 滤波后混入 R
            float crossL = m_hp.processSample(L, 0);
            crossL = m_lp.processSample(crossL, 0);

            // R→L 互馈：R 信号经 HP+LP 滤波后混入 L
            float crossR = m_hp.processSample(R, 1);
            crossR = m_lp.processSample(crossR, 1);

            samples[i * 2]     = L + crossR * crossGain;
            samples[i * 2 + 1] = R + crossL * crossGain;
        }
    }

private:
    void applyPendingParams() {
        if (!m_paramsDirty.exchange(false, std::memory_order_acq_rel)) return;

        const bool enabled = m_pendingEnabled.load(std::memory_order_acquire);
        if (!m_enabled && enabled) {
            m_hp.reset();
            m_lp.reset();
        }
        m_enabled = enabled;

        float lowCut = m_pendingLowCutFreq.load(std::memory_order_relaxed);
        lowCut = (lowCut < 50.0f) ? 50.0f : (lowCut > 1000.0f) ? 1000.0f : lowCut;
        float highCut = m_pendingHighCutFreq.load(std::memory_order_relaxed);
        highCut = (highCut < 500.0f) ? 500.0f : (highCut > 8000.0f) ? 8000.0f : highCut;
        float attenuation = m_pendingAttenuationDB.load(std::memory_order_relaxed);
        attenuation = (attenuation < 0.0f) ? 0.0f : (attenuation > 15.0f) ? 15.0f : attenuation;

        if (lowCut != m_lowCutFreq) {
            m_lowCutFreq = lowCut;
            m_hp.setHP2_RBJ(m_sampleRate, m_lowCutFreq, 0.707f);
        }
        if (highCut != m_highCutFreq) {
            m_highCutFreq = highCut;
            m_lp.setLP2_RBJ(m_sampleRate, m_highCutFreq, 0.707f);
        }
        m_attenuationDB = attenuation;
        m_crossGainLinear = powf(10.0f, -attenuation / 20.0f);
    }
};

// ==========================================
// 压限器 (Compressor) - 动态范围压缩
// ==========================================
class Compressor {
    bool m_enabled = false;
    int m_sampleRate = 44100;

    // 参数
    float m_thresholdDB = -20.0f;   // 阈值 (dB), 范围: -60 ~ 0
    float m_ratio = 4.0f;           // 压缩比, 范围: 1 ~ 20
    float m_attackMs = 10.0f;       // 启动时间 (ms), 范围: 0.1 ~ 100
    float m_releaseMs = 200.0f;     // 释放时间 (ms), 范围: 10 ~ 1000
    float m_makeupGainDB = 0.0f;    // 补偿增益 (dB), 范围: 0 ~ 24
    float m_kneeWidthDB = 6.0f;     // 拐点宽度 (dB), 范围: 0 ~ 30
    int m_detectionMode = 1;        // 0=Peak, 1=RMS

    // 预计算系数
    float m_alphaAttack = 0.0f;     // 启动平滑系数
    float m_alphaRelease = 0.0f;    // 释放平滑系数
    float m_makeupGainLinear = 1.0f;
    float m_ratioInv = 0.25f;       // 1/ratio - 1

    // 状态变量
    float m_gainSmooth = 0.0f;      // 平滑后的增益衰减 (dB)
    float m_rmsLevel = 0.0f;        // RMS电平（指数平均）
    float m_alphaRms = 0.0f;        // RMS平滑系数

    // 用于GR Meter
    std::atomic<float> m_currentGR{0.0f};       // 当前增益衰减量 (dB)
    std::atomic<bool> m_pendingEnabled{false};
    std::atomic<float> m_pendingThresholdDB{-20.0f};
    std::atomic<float> m_pendingRatio{4.0f};
    std::atomic<float> m_pendingAttackMs{10.0f};
    std::atomic<float> m_pendingReleaseMs{200.0f};
    std::atomic<float> m_pendingMakeupGainDB{0.0f};
    std::atomic<float> m_pendingKneeWidthDB{6.0f};
    std::atomic<int> m_pendingDetectionMode{1};
    std::atomic<bool> m_paramsDirty{false};

    void updateCoeffs() {
        float fs = (float)m_sampleRate;
        m_alphaAttack = expf(-1.0f / (m_attackMs * 0.001f * fs));
        m_alphaRelease = expf(-1.0f / (m_releaseMs * 0.001f * fs));
        m_makeupGainLinear = powf(10.0f, m_makeupGainDB / 20.0f);
        m_ratioInv = 1.0f / m_ratio - 1.0f;
        // RMS 时间常数约 10ms
        m_alphaRms = expf(-1.0f / (0.010f * fs));
    }

public:
    Compressor() { updateCoeffs(); }

    void setEnabled(bool enabled) {
        m_pendingEnabled.store(enabled, std::memory_order_release);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    bool isEnabled() const { return m_pendingEnabled.load(std::memory_order_acquire); }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0 && sampleRate != m_sampleRate) {
            m_sampleRate = sampleRate;
            updateCoeffs();
        }
    }

    void setParams(float thresholdDB, float ratio, float attackMs, float releaseMs, float makeupGainDB) {
        m_pendingThresholdDB.store(thresholdDB, std::memory_order_relaxed);
        m_pendingRatio.store(ratio, std::memory_order_relaxed);
        m_pendingAttackMs.store(attackMs, std::memory_order_relaxed);
        m_pendingReleaseMs.store(releaseMs, std::memory_order_relaxed);
        m_pendingMakeupGainDB.store(makeupGainDB, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    void setKneeWidth(float kneeWidthDB) {
        m_pendingKneeWidthDB.store(kneeWidthDB, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    void setDetectionMode(int mode) {
        m_pendingDetectionMode.store(mode, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    float getCurrentGR() const { return m_currentGR.load(std::memory_order_acquire); }

    void process(float* samples, int numFrames, int channels) {
        applyPendingParams();
        if (!m_enabled) return;

        const float threshold = m_thresholdDB;
        const float halfW = m_kneeWidthDB * 0.5f;
        const float ratioInvM1 = m_ratioInv; // 1/ratio - 1
        const float alphaA = m_alphaAttack;
        const float alphaR = m_alphaRelease;
        const float makeup = m_makeupGainLinear;
        const bool isRMS = (m_detectionMode == 1);
        const float alphaRmsVal = m_alphaRms;
        const float epsilon = 1e-10f;

        float gainSmooth = m_gainSmooth;
        float rmsLevel = m_rmsLevel;

        for (int i = 0; i < numFrames; i++) {
            // 计算检测电平（取所有声道最大值）
            float maxAbs = 0.0f;
            for (int ch = 0; ch < channels; ch++) {
                float s = fabsf(samples[i * channels + ch]);
                if (s > maxAbs) maxAbs = s;
            }

            float detectLevel;
            if (isRMS) {
                // RMS 检测：指数平均
                rmsLevel = alphaRmsVal * rmsLevel + (1.0f - alphaRmsVal) * maxAbs * maxAbs;
                detectLevel = sqrtf(rmsLevel);
            } else {
                // Peak 检测
                detectLevel = maxAbs;
            }

            // 转换到 dB 域
            float linDB = 20.0f * log10f(detectLevel + epsilon);

            // 计算目标增益衰减
            float delta = linDB - threshold;
            float gainTarget;

            if (delta < -halfW) {
                // 低于阈值，不压缩
                gainTarget = 0.0f;
            } else if (delta < halfW) {
                // 软拐点区域：二次曲线平滑过渡
                gainTarget = (0.5f / m_kneeWidthDB) * (delta + halfW) * (delta + halfW) * ratioInvM1;
            } else {
                // 线性压缩区
                gainTarget = delta * ratioInvM1;
            }

            // 包络跟随器：平滑增益变化
            if (gainTarget < gainSmooth) {
                // 启动：快速响应
                gainSmooth = alphaA * gainSmooth + (1.0f - alphaA) * gainTarget;
            } else {
                // 释放：缓慢恢复
                gainSmooth = alphaR * gainSmooth + (1.0f - alphaR) * gainTarget;
            }

            // 应用增益（含补偿增益）
            float gainLinear = powf(10.0f, (gainSmooth + m_makeupGainDB) / 20.0f);
            for (int ch = 0; ch < channels; ch++) {
                samples[i * channels + ch] *= gainLinear;
            }
        }

        m_gainSmooth = gainSmooth;
        m_rmsLevel = rmsLevel;
        m_currentGR.store(-gainSmooth, std::memory_order_release); // GR Meter 显示正值
    }

    void reset() {
        m_gainSmooth = 0.0f;
        m_rmsLevel = 0.0f;
        m_currentGR.store(0.0f, std::memory_order_release);
    }

private:
    void applyPendingParams() {
        if (!m_paramsDirty.exchange(false, std::memory_order_acq_rel)) return;

        const bool enabled = m_pendingEnabled.load(std::memory_order_acquire);
        if (!m_enabled && enabled) {
            m_gainSmooth = 0.0f;
            m_rmsLevel = 0.0f;
        }
        m_enabled = enabled;
        m_thresholdDB = m_pendingThresholdDB.load(std::memory_order_relaxed);
        m_thresholdDB = (m_thresholdDB < -60.0f) ? -60.0f : (m_thresholdDB > 0.0f) ? 0.0f : m_thresholdDB;
        m_ratio = m_pendingRatio.load(std::memory_order_relaxed);
        m_ratio = (m_ratio < 1.0f) ? 1.0f : (m_ratio > 20.0f) ? 20.0f : m_ratio;
        m_attackMs = m_pendingAttackMs.load(std::memory_order_relaxed);
        m_attackMs = (m_attackMs < 0.1f) ? 0.1f : (m_attackMs > 100.0f) ? 100.0f : m_attackMs;
        m_releaseMs = m_pendingReleaseMs.load(std::memory_order_relaxed);
        m_releaseMs = (m_releaseMs < 10.0f) ? 10.0f : (m_releaseMs > 1000.0f) ? 1000.0f : m_releaseMs;
        m_makeupGainDB = m_pendingMakeupGainDB.load(std::memory_order_relaxed);
        m_makeupGainDB = (m_makeupGainDB < 0.0f) ? 0.0f : (m_makeupGainDB > 24.0f) ? 24.0f : m_makeupGainDB;
        m_kneeWidthDB = m_pendingKneeWidthDB.load(std::memory_order_relaxed);
        m_kneeWidthDB = (m_kneeWidthDB < 0.0f) ? 0.0f : (m_kneeWidthDB > 30.0f) ? 30.0f : m_kneeWidthDB;
        int mode = m_pendingDetectionMode.load(std::memory_order_relaxed);
        m_detectionMode = (mode == 0) ? 0 : 1;
        updateCoeffs();
    }
};

// ==========================================
// 低音增强 (BassBoost) - 基于 LowShelf BiQuad
// ==========================================
class BassBoost {
    BiQuad m_filter;
    bool m_enabled = false;
    int m_sampleRate = 44100;

    // 参数
    float m_gainDB = 0.0f;          // 增益 (dB), 范围: -12 ~ +12
    float m_frequency = 100.0f;     // 转折频率 (Hz), 范围: 50 ~ 500
    float m_Q = 0.707f;             // 品质因数 (Butterworth)
    std::atomic<bool> m_pendingEnabled{false};
    std::atomic<float> m_pendingGainDB{0.0f};
    std::atomic<float> m_pendingFrequency{100.0f};
    std::atomic<bool> m_paramsDirty{false};

    void updateFilter() {
        if (m_enabled && m_gainDB != 0.0f) {
            m_filter.setLS2_RBJ((float)m_sampleRate, m_frequency, m_Q, m_gainDB);
        }
    }

public:
    BassBoost() {}

    void setEnabled(bool enabled) {
        m_pendingEnabled.store(enabled, std::memory_order_release);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    bool isEnabled() const { return m_pendingEnabled.load(std::memory_order_acquire); }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0 && sampleRate != m_sampleRate) {
            m_sampleRate = sampleRate;
            m_filter.reset();
            updateFilter();
        }
    }

    void setParams(float gainDB, float frequency) {
        m_pendingGainDB.store(gainDB, std::memory_order_relaxed);
        m_pendingFrequency.store(frequency, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    void process(float* samples, int numFrames, int channels) {
        applyPendingParams();
        if (!m_enabled || m_gainDB == 0.0f) return;

        for (int i = 0; i < numFrames; i++) {
            for (int ch = 0; ch < channels; ch++) {
                samples[i * channels + ch] = m_filter.processSample(samples[i * channels + ch], ch);
            }
        }
    }

    void reset() {
        m_filter.reset();
    }

private:
    void applyPendingParams() {
        if (!m_paramsDirty.exchange(false, std::memory_order_acq_rel)) return;

        const bool enabled = m_pendingEnabled.load(std::memory_order_acquire);
        if (!m_enabled && enabled) {
            m_filter.reset();
        }
        m_enabled = enabled;
        m_gainDB = m_pendingGainDB.load(std::memory_order_relaxed);
        m_gainDB = (m_gainDB < -12.0f) ? -12.0f : (m_gainDB > 12.0f) ? 12.0f : m_gainDB;
        m_frequency = m_pendingFrequency.load(std::memory_order_relaxed);
        m_frequency = (m_frequency < 50.0f) ? 50.0f : (m_frequency > 500.0f) ? 500.0f : m_frequency;
        updateFilter();
    }
};

// ==========================================
// 高音增强 (TrebleBoost) - 基于 HighShelf BiQuad
// ==========================================
class TrebleBoost {
    BiQuad m_filter;
    bool m_enabled = false;
    int m_sampleRate = 44100;

    // 参数
    float m_gainDB = 0.0f;          // 增益 (dB), 范围: -12 ~ +12
    float m_frequency = 8000.0f;    // 转折频率 (Hz), 范围: 2000 ~ 16000
    float m_Q = 0.707f;             // 品质因数 (Butterworth)
    std::atomic<bool> m_pendingEnabled{false};
    std::atomic<float> m_pendingGainDB{0.0f};
    std::atomic<float> m_pendingFrequency{8000.0f};
    std::atomic<bool> m_paramsDirty{false};

    void updateFilter() {
        if (m_enabled && m_gainDB != 0.0f) {
            m_filter.setHS2_RBJ((float)m_sampleRate, m_frequency, m_Q, m_gainDB);
        }
    }

public:
    TrebleBoost() {}

    void setEnabled(bool enabled) {
        m_pendingEnabled.store(enabled, std::memory_order_release);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    bool isEnabled() const { return m_pendingEnabled.load(std::memory_order_acquire); }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0 && sampleRate != m_sampleRate) {
            m_sampleRate = sampleRate;
            m_filter.reset();
            updateFilter();
        }
    }

    void setParams(float gainDB, float frequency) {
        m_pendingGainDB.store(gainDB, std::memory_order_relaxed);
        m_pendingFrequency.store(frequency, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    void process(float* samples, int numFrames, int channels) {
        applyPendingParams();
        if (!m_enabled || m_gainDB == 0.0f) return;

        for (int i = 0; i < numFrames; i++) {
            for (int ch = 0; ch < channels; ch++) {
                samples[i * channels + ch] = m_filter.processSample(samples[i * channels + ch], ch);
            }
        }
    }

    void reset() {
        m_filter.reset();
    }

private:
    void applyPendingParams() {
        if (!m_paramsDirty.exchange(false, std::memory_order_acq_rel)) return;

        const bool enabled = m_pendingEnabled.load(std::memory_order_acquire);
        if (!m_enabled && enabled) {
            m_filter.reset();
        }
        m_enabled = enabled;
        m_gainDB = m_pendingGainDB.load(std::memory_order_relaxed);
        m_gainDB = (m_gainDB < -12.0f) ? -12.0f : (m_gainDB > 12.0f) ? 12.0f : m_gainDB;
        m_frequency = m_pendingFrequency.load(std::memory_order_relaxed);
        m_frequency = (m_frequency < 2000.0f) ? 2000.0f : (m_frequency > 16000.0f) ? 16000.0f : m_frequency;
        updateFilter();
    }
};

// ==========================================
// 360° 环绕音 (Surround360) - 2D 水平面双耳渲染
// Woodworth 球头模型: ILD + ITD + 头影低通 + 全通去相关
// ==========================================
class Surround360 {
    bool m_enabled = false;
    int m_sampleRate = 44100;

    // 参数
    float m_intensity = 0.5f;       // 效果强度 0.0 ~ 1.0
    float m_azimuthRad = 0.0f;      // 方位角 (弧度), 0=前, π/2=右, π=后, -π/2=左

    // 常量
    static constexpr float HEAD_RADIUS = 0.0875f;   // 头半径 (m), KEMAR 平均
    static constexpr float SPEED_OF_SOUND = 343.0f;  // 声速 (m/s)
    static constexpr float MAX_DELAY_SEC = HEAD_RADIUS / SPEED_OF_SOUND * (M_PI + 1.0f);
    // ≈ 0.0008s, 留余量取 2ms

    // === 分数延迟线 (线性插值) ===
    static const int DELAY_BUF_SIZE = 256;  // 2ms @48kHz ≈ 96 样本, 192kHz ≈ 200 样本, 取 256 保证高采样率不溢出
    float m_delayBufL[DELAY_BUF_SIZE];
    float m_delayBufR[DELAY_BUF_SIZE];
    int m_delayWriteIdx = 0;
    float m_delaySamples = 0.0f;     // 当前延迟量 (样本, 含小数)

    // === 头影低通滤波器 (远耳) ===
    BiQuad m_shadowLpL;
    BiQuad m_shadowLpR;

    // === 全通去相关滤波器 (用于后方声像) ===
    BiQuad m_apfL;
    BiQuad m_apfR;

    // === 预计算 ILD 增益 ===
    float m_gainL = 1.0f;
    float m_gainR = 1.0f;
    float m_shadowCutoffL = 20000.0f;
    float m_shadowCutoffR = 20000.0f;
    float m_apfMix = 0.0f;          // 全通混合量 (后方更强)

    // === 逐样本平滑值 (消除快速旋转时的跳变) ===
    float m_smoothGainL = 1.0f;
    float m_smoothGainR = 1.0f;
    float m_smoothDelay = 0.0f;
    float m_smoothAzmRad = 0.0f;
    // 平滑系数: 随采样率调整, 保持约10ms的时间常数
    // coeff = 1 - exp(-1 / (tau * fs)), tau=0.010s
    float m_smoothCoeff = 0.002f;  // 默认值 @48kHz
    std::atomic<bool> m_pendingEnabled{false};
    std::atomic<float> m_pendingIntensity{50.0f};
    std::atomic<float> m_pendingAzimuthDeg{0.0f};
    std::atomic<bool> m_paramsDirty{false};

    void updateParams() {
        float theta = m_azimuthRad;
        float s = sinf(theta);
        float c = cosf(theta);
        float absS = fabsf(s);
        float intensity = m_intensity;

        // --- ILD (Woodworth 球头幅度模型, 功率归一化) ---
        // θ=0(前): L=R=1; θ=π/2(右): L≈0.67, R≈1.33; θ=-π/2(左): L≈1.33, R≈0.67
        float ildL = 1.0f - s * 0.5f * intensity;
        float ildR = 1.0f + s * 0.5f * intensity;
        // 功率归一化: 使 L²+R² 恒定, 避免环绕效果导致整体响度变化
        float normFactor = 1.0f / sqrtf(ildL * ildL + ildR * ildR) * 1.4142f; // /√2 保持前方位为1
        m_gainL = ildL * normFactor;
        m_gainR = ildR * normFactor;

        // --- ITD (Woodworth 延迟模型) ---
        // Δt = (r/c) * (θ + sin θ), 正 θ → 右耳延迟
        float itdSec = (HEAD_RADIUS / SPEED_OF_SOUND) * (theta + s) * intensity;
        m_delaySamples = fabsf(itdSec) * (float)m_sampleRate;
        // clamp 到 [0, DELAY_BUF_SIZE-2] 防止越界 (留 1 样本给插值)
        if (m_delaySamples > (float)(DELAY_BUF_SIZE - 2))
            m_delaySamples = (float)(DELAY_BUF_SIZE - 2);

        // --- 头影低通 (远耳高频衰减) ---
        // f_c(θ) = 15000 - 13000 * |sin(θ)|
        float shadowFreq = 15000.0f - 13000.0f * absS * intensity;
        shadowFreq = max(shadowFreq, 1000.0f);
        if (s > 0.0f) {
            // 声源偏右: 左耳为远耳 → 左耳加低通
            m_shadowLpL.setLP2_RBJ((float)m_sampleRate, shadowFreq, 0.707f);
            m_shadowLpR.setLP2_RBJ((float)m_sampleRate, 20000.0f, 0.707f);
            m_shadowCutoffL = shadowFreq;
            m_shadowCutoffR = 20000.0f;
        } else {
            // 声源偏左: 右耳为远耳 → 右耳加低通
            m_shadowLpL.setLP2_RBJ((float)m_sampleRate, 20000.0f, 0.707f);
            m_shadowLpR.setLP2_RBJ((float)m_sampleRate, shadowFreq, 0.707f);
            m_shadowCutoffL = 20000.0f;
            m_shadowCutoffR = shadowFreq;
        }

        // --- 全通去相关 (后方声源增强前后区分度) ---
        // 实际前后区分: |θ| > 90° 即 cos < 0
        float behindFactor = max(0.0f, -c);  // 0(前方) ~ 1(正后方)
        m_apfMix = behindFactor * 0.4f * intensity;

        // 两个不同频率的全通, 产生频率相关的相位差 (全通只移相不改幅度)
        m_apfL.setAP2_RBJ((float)m_sampleRate, 700.0f, 0.5f);
        m_apfR.setAP2_RBJ((float)m_sampleRate, 1100.0f, 0.5f);
    }

public:
    Surround360() {
        memset(m_delayBufL, 0, sizeof(m_delayBufL));
        memset(m_delayBufR, 0, sizeof(m_delayBufR));
        updateParams();
    }

    void setEnabled(bool enabled) {
        m_pendingEnabled.store(enabled, std::memory_order_release);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    bool isEnabled() const { return m_pendingEnabled.load(std::memory_order_acquire); }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0 && sampleRate != m_sampleRate) {
            m_sampleRate = sampleRate;
            memset(m_delayBufL, 0, sizeof(m_delayBufL));
            memset(m_delayBufR, 0, sizeof(m_delayBufR));
            m_delayWriteIdx = 0;
            // 调整平滑系数保持约10ms时间常数
            m_smoothCoeff = 1.0f - expf(-1.0f / (0.010f * (float)sampleRate));
            updateParams();
        }
    }

    void setParams(float intensity, float azimuthDeg) {
        m_pendingIntensity.store(intensity, std::memory_order_relaxed);
        m_pendingAzimuthDeg.store(azimuthDeg, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    void process(float* samples, int numFrames, int channels) {
        applyPendingParams();
        if (!m_enabled || channels < 2) return;

        const float targetGainL = m_gainL;
        const float targetGainR = m_gainR;
        const float targetDelay = m_delaySamples;
        const float apfMix = m_apfMix;
        const int mask = DELAY_BUF_SIZE - 1;
        const float a = m_smoothCoeff;
        const float b = 1.0f - a;
        int wIdx = m_delayWriteIdx;

        // 取当前平滑值
        float sGainL = m_smoothGainL;
        float sGainR = m_smoothGainR;
        float sDelay = m_smoothDelay;
        float sAzmRad = m_smoothAzmRad;

        for (int i = 0; i < numFrames; i++) {
            // 逐样本指数平滑
            sGainL = sGainL * b + targetGainL * a;
            sGainR = sGainR * b + targetGainR * a;
            sDelay = sDelay * b + targetDelay * a;
            sAzmRad = sAzmRad * b + m_azimuthRad * a;

            float inL = samples[i * 2];
            float inR = samples[i * 2 + 1];

            // 写入延迟缓冲
            m_delayBufL[wIdx] = inL;
            m_delayBufR[wIdx] = inR;

            // 用平滑后的延迟量做分数延迟读取 (线性插值)
            float readPos = (float)wIdx - sDelay;
            int readIdx0 = (int)floorf(readPos);
            float frac = readPos - (float)readIdx0;
            readIdx0 %= DELAY_BUF_SIZE;
            if (readIdx0 < 0) readIdx0 += DELAY_BUF_SIZE;
            int readIdx1 = (readIdx0 + 1) & mask;

            float dL = m_delayBufL[readIdx0] * (1.0f - frac) + m_delayBufL[readIdx1] * frac;
            float dR = m_delayBufR[readIdx0] * (1.0f - frac) + m_delayBufR[readIdx1] * frac;

            // 用平滑方位角决定延迟耳 (避免左右快速跳变)
            // θ>0(声源偏右): 左耳为远耳 → 延迟左耳; θ<0(声源偏左): 右耳为远耳 → 延迟右耳
            bool delayLeft = (sAzmRad > 0.0f);

            // 应用 ITD: 远耳用延迟信号, 近耳用原始信号
            float outL, outR;
            if (delayLeft) {
                outL = dL;
                outR = inR;
            } else {
                outL = inL;
                outR = dR;
            }

            // 应用平滑后的 ILD 增益
            outL *= sGainL;
            outR *= sGainR;

            // 应用头影低通 (远耳) — 用 buffer 首样本的滤波器系数 (已由 updateParams 设置)
            // 近耳设为 20kHz LPF 时仍需通过滤波器保持一致的相位/延迟
            outL = m_shadowLpL.processSample(outL, 0);
            outR = m_shadowLpR.processSample(outR, 1);

            // 全通去相关 (后方声像混合)
            if (apfMix > 0.001f) {
                float apfOutL = m_apfL.processSample(inL, 0);
                float apfOutR = m_apfR.processSample(inR, 1);
                outL = outL * (1.0f - apfMix) + apfOutL * apfMix;
                outR = outR * (1.0f - apfMix) + apfOutR * apfMix;
            }

            // 软限幅
            outL = max(-1.0f, min(1.0f, outL));
            outR = max(-1.0f, min(1.0f, outR));

            samples[i * 2]     = outL;
            samples[i * 2 + 1] = outR;

            wIdx = (wIdx + 1) & mask;
        }

        // 保存平滑状态
        m_smoothGainL = sGainL;
        m_smoothGainR = sGainR;
        m_smoothDelay = sDelay;
        m_smoothAzmRad = sAzmRad;
        m_delayWriteIdx = wIdx;
    }

    void reset() {
        memset(m_delayBufL, 0, sizeof(m_delayBufL));
        memset(m_delayBufR, 0, sizeof(m_delayBufR));
        m_delayWriteIdx = 0;
        m_shadowLpL.reset();
        m_shadowLpR.reset();
        m_apfL.reset();
        m_apfR.reset();
        m_smoothGainL = m_gainL;
        m_smoothGainR = m_gainR;
        m_smoothDelay = m_delaySamples;
        m_smoothAzmRad = m_azimuthRad;
    }

private:
    void applyPendingParams() {
        if (!m_paramsDirty.exchange(false, std::memory_order_acq_rel)) return;

        const bool enabled = m_pendingEnabled.load(std::memory_order_acquire);
        const bool enablingNow = !m_enabled && enabled;
        if (enablingNow) {
            memset(m_delayBufL, 0, sizeof(m_delayBufL));
            memset(m_delayBufR, 0, sizeof(m_delayBufR));
            m_delayWriteIdx = 0;
            m_shadowLpL.reset();
            m_shadowLpR.reset();
            m_apfL.reset();
            m_apfR.reset();
        }
        m_enabled = enabled;

        float intensity = m_pendingIntensity.load(std::memory_order_relaxed);
        m_intensity = (intensity < 0.0f) ? 0.0f : (intensity > 100.0f) ? 100.0f : intensity;
        m_intensity /= 100.0f;

        float deg = fmodf(m_pendingAzimuthDeg.load(std::memory_order_relaxed), 360.0f);
        if (deg > 180.0f) deg -= 360.0f;
        if (deg < -180.0f) deg += 360.0f;
        m_azimuthRad = deg * (float)M_PI / 180.0f;

        updateParams();
        if (enablingNow) {
            m_smoothGainL = m_gainL;
            m_smoothGainR = m_gainR;
            m_smoothDelay = m_delaySamples;
            m_smoothAzmRad = m_azimuthRad;
        }
    }
};

// ==========================================
// 360° 全景音 (Panoramic360) - 3D 球面双耳渲染
// 在 Surround360 基础上增加: 耳廓 EQ、3D 方位角修正、早期反射、FDN 混响
// ==========================================
class Panoramic360 {
    bool m_enabled = false;
    int m_sampleRate = 44100;

    // 参数
    float m_intensity = 0.5f;       // 效果强度 0 ~ 1
    float m_azimuthDeg = 0.0f;      // 方位角 0~360°
    float m_elevationDeg = 0.0f;    // 仰角 -90~+90°

    // 常量
    static constexpr float HEAD_RADIUS = 0.0875f;
    static constexpr float SPEED_OF_SOUND = 343.0f;

    // === 内嵌 Surround360 用于基础 ILD/ITD ===
    Surround360 m_surround;

    // === 耳廓 EQ (Pinna simulation) ===
    // 高频增益随仰角变化: G_pinna(φ) = G_max * sin(φ), 频段 8~12kHz
    BiQuad m_pinnaL;
    BiQuad m_pinnaR;
    float m_pinnaGainDB = 0.0f;

    // === 早期反射 (6墙镜像源法) ===
    static const int NUM_REFLECTIONS = 6;
    static const int REFL_DELAY_MAX = 4096;  // ~85ms @48kHz
    float m_reflDelayBufL[REFL_DELAY_MAX];
    float m_reflDelayBufR[REFL_DELAY_MAX];
    int m_reflWriteIdx = 0;

    // 反射参数: 延迟(样本)、增益、左右分配
    struct Reflection {
        int delaySamples;
        float gainL;
        float gainR;
    };
    Reflection m_reflections[NUM_REFLECTIONS];

    // === FDN 混响 (Feedback Delay Network) ===
    static const int FDN_ORDER = 4;
    static const int FDN_DELAY_MAX = 8192;
    float m_fdnDelayBuf[FDN_ORDER][FDN_DELAY_MAX];
    int m_fdnWriteIdx = 0;
    float m_fdnGains[FDN_ORDER];       // 延迟线输出增益 (反馈矩阵对角)
    int m_fdnDelays[FDN_ORDER];        // 延迟长度 (样本)
    float m_fdnFeedback = 0.7f;        // 反馈量
    float m_fdnMix = 0.0f;             // 混响混合量 (dry=1-mix, wet=mix)
    float m_fdnDamping = 0.4f;         // 高频阻尼
    BiQuad m_fdnDampFilters[FDN_ORDER]; // 每条延迟线的阻尼低通

    // === 高通滤波器状态 (成员变量, 跨 buffer 保持连续) ===
    float m_reflHpPrevIn = 0.0f;
    float m_reflHpPrevOut = 0.0f;
    float m_fdnHpPrevIn = 0.0f;
    float m_fdnHpPrevOut = 0.0f;
    std::atomic<bool> m_pendingEnabled{false};
    std::atomic<float> m_pendingIntensity{50.0f};
    std::atomic<float> m_pendingAzimuthDeg{0.0f};
    std::atomic<float> m_pendingElevationDeg{0.0f};
    std::atomic<bool> m_paramsDirty{false};

    void updateReflections() {
        // 房间尺寸 (简化: 4m x 3m x 2.5m)
        // 6面墙: 左、右、前、后、上、下
        // 镜像源距离 → 延迟, 增益随反射次数衰减
        float roomW = 4.0f, roomH = 3.0f, roomD = 2.5f;
        float reflGains[NUM_REFLECTIONS] = {0.35f, 0.35f, 0.30f, 0.30f, 0.20f, 0.20f};

        // 简化: 固定延迟模拟不同墙壁距离
        float delays_ms[NUM_REFLECTIONS] = {5.8f, 6.2f, 8.3f, 8.7f, 12.1f, 12.5f};

        // 左右分配随方位角旋转
        float azRad = m_azimuthDeg * (float)M_PI / 180.0f;
        float c = cosf(azRad);
        float s = sinf(azRad);

        for (int i = 0; i < NUM_REFLECTIONS; i++) {
            m_reflections[i].delaySamples = (int)(delays_ms[i] * 0.001f * (float)m_sampleRate);
            if (m_reflections[i].delaySamples >= REFL_DELAY_MAX)
                m_reflections[i].delaySamples = REFL_DELAY_MAX - 1;

            // 简单的左右分配: 奇数墙偏左, 偶数墙偏右, 随方位角旋转
            float basePanL = (i % 2 == 0) ? 0.3f : 0.7f;
            float basePanR = 1.0f - basePanL;
            // 旋转
            float rotL = basePanL * (1.0f + c * 0.3f) + basePanR * (-s * 0.2f);
            float rotR = basePanR * (1.0f - c * 0.3f) + basePanL * (s * 0.2f);
            rotL = max(0.0f, min(1.0f, rotL));
            rotR = max(0.0f, min(1.0f, rotR));

            m_reflections[i].gainL = reflGains[i] * rotL;
            m_reflections[i].gainR = reflGains[i] * rotR;
        }
    }

    void updateFDN() {
        // 质数互素延迟长度, 避免梳状滤波
        int primes[FDN_ORDER] = {113, 163, 223, 311};
        float baseTime_ms = 30.0f + m_intensity * 50.0f;  // 30~80ms
        for (int i = 0; i < FDN_ORDER; i++) {
            m_fdnDelays[i] = (int)(baseTime_ms * 0.001f * (float)m_sampleRate * (float)primes[i] / 113.0f);
            if (m_fdnDelays[i] >= FDN_DELAY_MAX) m_fdnDelays[i] = FDN_DELAY_MAX - 1;
            m_fdnGains[i] = 1.0f;
            // 阻尼低通: 高频衰减更快
            float dampFreq = 4000.0f + (1.0f - m_fdnDamping) * 12000.0f;
            m_fdnDampFilters[i].setLP2_RBJ((float)m_sampleRate, dampFreq, 0.707f);
        }
        // 反馈量随强度变化 (修复低频拖拉机声: 0.5~0.85 → 0.4~0.62, 远离不稳定边界)
        m_fdnFeedback = 0.4f + m_intensity * 0.22f;  // 0.4 ~ 0.62
        // 混响混合量 (较保守, 避免过度模糊)
        m_fdnMix = m_intensity * 0.12f;  // 0 ~ 12% (从 15% 下调)
    }

    void updatePinnaEQ() {
        // 耳廓高频增益: G_pinna(φ,θ) = G_max * sin(φ), φ=仰角, θ=方位角偏移
        // 左右耳因方位角不同, 耳廓效应略有差异
        float elevRad = m_elevationDeg * (float)M_PI / 180.0f;
        float azRad = m_azimuthDeg * (float)M_PI / 180.0f;
        float pinnaEffect = sinf(elevRad);  // -1 ~ +1

        // 方位角偏移: 声源偏右时左耳接收更多耳廓反射, 偏左时右耳更多
        float azOffset = sinf(azRad) * 0.3f;  // ±0.3 的方位角偏移

        // 左耳: 声源偏右(azOffset>0) → 耳廓效应增强; 偏左 → 减弱
        float pinnaGainL = pinnaEffect * (1.0f + azOffset) * 6.0f * m_intensity;
        // 右耳: 与左耳相反
        float pinnaGainR = pinnaEffect * (1.0f - azOffset) * 6.0f * m_intensity;

        // 使用高频搁架模拟
        float pinnaFreq = 8000.0f;
        m_pinnaL.setHS2_RBJ((float)m_sampleRate, pinnaFreq, 0.707f, pinnaGainL);
        m_pinnaR.setHS2_RBJ((float)m_sampleRate, pinnaFreq, 0.707f, pinnaGainR);
        m_pinnaGainDB = (pinnaGainL + pinnaGainR) * 0.5f;  // 平均值用于启用判断
    }

public:
    Panoramic360() {
        memset(m_reflDelayBufL, 0, sizeof(m_reflDelayBufL));
        memset(m_reflDelayBufR, 0, sizeof(m_reflDelayBufR));
        for (int i = 0; i < FDN_ORDER; i++) {
            memset(m_fdnDelayBuf[i], 0, sizeof(m_fdnDelayBuf[i]));
        }
        updateReflections();
        updateFDN();
        updatePinnaEQ();
    }

    void setEnabled(bool enabled) {
        m_pendingEnabled.store(enabled, std::memory_order_release);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    bool isEnabled() const { return m_pendingEnabled.load(std::memory_order_acquire); }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0 && sampleRate != m_sampleRate) {
            m_sampleRate = sampleRate;
            m_surround.setSampleRate(sampleRate);
            memset(m_reflDelayBufL, 0, sizeof(m_reflDelayBufL));
            memset(m_reflDelayBufR, 0, sizeof(m_reflDelayBufR));
            m_reflWriteIdx = 0;
            for (int i = 0; i < FDN_ORDER; i++) {
                memset(m_fdnDelayBuf[i], 0, sizeof(m_fdnDelayBuf[i]));
            }
            m_fdnWriteIdx = 0;
            updateReflections();
            updateFDN();
            updatePinnaEQ();
        }
    }

    void setParams(float intensity, float azimuthDeg, float elevationDeg) {
        m_pendingIntensity.store(intensity, std::memory_order_relaxed);
        m_pendingAzimuthDeg.store(azimuthDeg, std::memory_order_relaxed);
        m_pendingElevationDeg.store(elevationDeg, std::memory_order_relaxed);
        m_paramsDirty.store(true, std::memory_order_release);
    }

    void process(float* samples, int numFrames, int channels) {
        applyPendingParams();
        if (!m_enabled || channels < 2) return;

        // === 1. 基础 2D ILD/ITD (Surround360) ===
        m_surround.process(samples, numFrames, channels);

        // === 2. 耳廓 EQ ===
        if (fabsf(m_pinnaGainDB) > 0.1f) {
            for (int i = 0; i < numFrames; i++) {
                samples[i * 2]     = m_pinnaL.processSample(samples[i * 2], 0);
                samples[i * 2 + 1] = m_pinnaR.processSample(samples[i * 2 + 1], 1);
            }
        }

        // === 3. 早期反射 (6墙镜像源) ===
        const int reflMask = REFL_DELAY_MAX - 1;
        int reflW = m_reflWriteIdx;
        // 反射强度从 0.6 降到 0.42, 修复低频驻波堆积
        float reflIntensity = m_intensity * 0.42f;
        // 一阶 RC 高通状态 (修复低频拖拉机声: 防止 6 路反射在 80-170Hz 形成驻波)
        // 与 FDN 高通一致 (fc ≈ 380Hz @48k)
        float reflHpPrevIn = m_reflHpPrevIn;
        float reflHpPrevOut = m_reflHpPrevOut;
        const float reflHpCoeff = 0.995f;

        for (int i = 0; i < numFrames; i++) {
            float dryL = samples[i * 2];
            float dryR = samples[i * 2 + 1];

            // 写入反射缓冲 (取左右均值, 先高通去低频防止驻波)
            float mono = (dryL + dryR) * 0.5f;
            float reflHpIn = mono;
            float reflHpOut = reflHpCoeff * (reflHpPrevOut + reflHpIn - reflHpPrevIn);
            reflHpPrevIn = reflHpIn;
            reflHpPrevOut = reflHpOut;
            m_reflDelayBufL[reflW] = reflHpOut;
            m_reflDelayBufR[reflW] = reflHpOut;

            float reflL = 0.0f, reflR = 0.0f;
            for (int r = 0; r < NUM_REFLECTIONS; r++) {
                int readIdx = reflW - m_reflections[r].delaySamples;
                if (readIdx < 0) readIdx += REFL_DELAY_MAX;
                readIdx &= reflMask;
                reflL += m_reflDelayBufL[readIdx] * m_reflections[r].gainL;
                reflR += m_reflDelayBufR[readIdx] * m_reflections[r].gainR;
            }

            samples[i * 2]     = dryL + reflL * reflIntensity;
            samples[i * 2 + 1] = dryR + reflR * reflIntensity;

            reflW = (reflW + 1) & reflMask;
        }
        m_reflWriteIdx = reflW;
        // 保存反射HP滤波器状态到成员变量
        m_reflHpPrevIn = reflHpPrevIn;
        m_reflHpPrevOut = reflHpPrevOut;

        // === 4. FDN 晚期混响 ===
        if (m_fdnMix > 0.001f) {
            const int fdnMask = FDN_DELAY_MAX - 1;
            int fdnW = m_fdnWriteIdx;
            float fb = m_fdnFeedback;
            float mix = m_fdnMix;
            // 一阶 RC 高通状态 (修复低频拖拉机声: 防止低频在 FDN 中持续累积)
            // fc ≈ sampleRate / (2π * rc_coeff), rc_coeff 选 0.995 → fc ≈ 380Hz @48k
            float hpPrevIn = m_fdnHpPrevIn;
            float hpPrevOut = m_fdnHpPrevOut;
            const float hpCoeff = 0.995f;  // 越接近 1 截止频率越低

            for (int i = 0; i < numFrames; i++) {
                float inL = samples[i * 2];
                float inR = samples[i * 2 + 1];
                float inputMono = (inL + inR) * 0.5f;

                // 写入前先高通去低频能量
                float hpIn = inputMono;
                float hpOut = hpCoeff * (hpPrevOut + hpIn - hpPrevIn);
                hpPrevIn = hpIn;
                hpPrevOut = hpOut;
                inputMono = hpOut;

                // 从各延迟线读取并应用阻尼
                float fdnOut[FDN_ORDER];
                for (int d = 0; d < FDN_ORDER; d++) {
                    int readIdx = fdnW - m_fdnDelays[d];
                    if (readIdx < 0) readIdx += FDN_DELAY_MAX;
                    readIdx &= fdnMask;
                    float delayed = m_fdnDelayBuf[d][readIdx];
                    // 应用高频阻尼 (BiQuad 仅支持 ch 0-1, 延迟线为单声道统一用 ch 0)
                    delayed = m_fdnDampFilters[d].processSample(delayed, 0);
                    fdnOut[d] = delayed;
                }

                // Hadamard 反馈矩阵 (简化: 4阶)
                // [1  1  1  1]   [fdnOut[0]]
                // [1 -1  1 -1] * [fdnOut[1]]
                // [1  1 -1 -1]   [fdnOut[2]]
                // [1 -1 -1  1]   [fdnOut[3]]
                float fbSignals[FDN_ORDER];
                fbSignals[0] = (fdnOut[0] + fdnOut[1] + fdnOut[2] + fdnOut[3]) * 0.5f;
                fbSignals[1] = (fdnOut[0] - fdnOut[1] + fdnOut[2] - fdnOut[3]) * 0.5f;
                fbSignals[2] = (fdnOut[0] + fdnOut[1] - fdnOut[2] - fdnOut[3]) * 0.5f;
                fbSignals[3] = (fdnOut[0] - fdnOut[1] - fdnOut[2] + fdnOut[3]) * 0.5f;

                // 写入延迟线: 输入 + 反馈 (tanh 软限幅, 修复反馈信号溢出)
                for (int d = 0; d < FDN_ORDER; d++) {
                    float writeVal = inputMono + fbSignals[d] * fb;
                    // tanh 软饱和: 大信号自然压缩, 消除拖拉机声
                    writeVal = tanhf(writeVal);
                    m_fdnDelayBuf[d][fdnW] = writeVal;
                }

                // 混响输出: 取前两个延迟线作左右 (不用 tanh, 避免压缩混响尾音)
                float reverbL = (fdnOut[0] + fdnOut[2]) * 0.5f;
                float reverbR = (fdnOut[1] + fdnOut[3]) * 0.5f;

                samples[i * 2]     = inL * (1.0f - mix) + reverbL * mix;
                samples[i * 2 + 1] = inR * (1.0f - mix) + reverbR * mix;

                fdnW = (fdnW + 1) & fdnMask;
            }
            m_fdnWriteIdx = fdnW;
            // 保存FDN高通滤波器状态到成员变量
            m_fdnHpPrevIn = hpPrevIn;
            m_fdnHpPrevOut = hpPrevOut;
        }
    }

    void reset() {
        m_surround.reset();
        memset(m_reflDelayBufL, 0, sizeof(m_reflDelayBufL));
        memset(m_reflDelayBufR, 0, sizeof(m_reflDelayBufR));
        m_reflWriteIdx = 0;
        for (int i = 0; i < FDN_ORDER; i++) {
            memset(m_fdnDelayBuf[i], 0, sizeof(m_fdnDelayBuf[i]));
            m_fdnDampFilters[i].reset();
        }
        m_fdnWriteIdx = 0;
        m_pinnaL.reset();
        m_pinnaR.reset();
        m_reflHpPrevIn = 0.0f;
        m_reflHpPrevOut = 0.0f;
        m_fdnHpPrevIn = 0.0f;
        m_fdnHpPrevOut = 0.0f;
    }

private:
    void applyPendingParams() {
        if (!m_paramsDirty.exchange(false, std::memory_order_acq_rel)) return;

        const bool enabled = m_pendingEnabled.load(std::memory_order_acquire);
        if (!m_enabled && enabled) {
            reset();
        }
        m_enabled = enabled;

        float intensity = m_pendingIntensity.load(std::memory_order_relaxed);
        m_intensity = (intensity < 0.0f) ? 0.0f : (intensity > 100.0f) ? 100.0f : intensity;
        m_intensity /= 100.0f;

        m_azimuthDeg = fmodf(m_pendingAzimuthDeg.load(std::memory_order_relaxed), 360.0f);
        if (m_azimuthDeg < 0.0f) m_azimuthDeg += 360.0f;

        m_elevationDeg = m_pendingElevationDeg.load(std::memory_order_relaxed);
        m_elevationDeg = (m_elevationDeg < -90.0f) ? -90.0f : (m_elevationDeg > 90.0f) ? 90.0f : m_elevationDeg;

        m_surround.setEnabled(enabled);
        m_surround.setParams(m_intensity * 100.0f, m_azimuthDeg);
        updateReflections();
        updateFDN();
        updatePinnaEQ();
    }
};

// ==========================================
// JNI 引擎框架 (预分配内存)
// ==========================================
class DSPChain {
    std::unique_ptr<StereoWidthProcessor> m_expander;
    std::unique_ptr<ParametricEQ> m_peq;
    std::unique_ptr<Crossfeed> m_crossfeed;
    std::unique_ptr<Compressor> m_compressor;
    std::unique_ptr<BassBoost> m_bassBoost;
    std::unique_ptr<TrebleBoost> m_trebleBoost;
    std::unique_ptr<Surround360> m_surround360;
    std::unique_ptr<Panoramic360> m_panoramic360;
    std::unique_ptr<RawFftConvolver> m_convolver;
    std::unique_ptr<SpeakerOutputEffect> m_speakerOutputEffect;
    std::vector<float> m_floatBuf;
    int m_sampleRate = 44100;
    int m_channels = 2;

public:
    DSPChain() : m_expander(std::make_unique<StereoWidthProcessor>()),
                 m_peq(std::make_unique<ParametricEQ>()),
                 m_crossfeed(std::make_unique<Crossfeed>()),
                 m_compressor(std::make_unique<Compressor>()),
                 m_bassBoost(std::make_unique<BassBoost>()),
                 m_trebleBoost(std::make_unique<TrebleBoost>()),
                 m_surround360(std::make_unique<Surround360>()),
                 m_panoramic360(std::make_unique<Panoramic360>()),
                 m_convolver(std::make_unique<RawFftConvolver>()),
                 m_speakerOutputEffect(std::make_unique<SpeakerOutputEffect>()) {}

    void init(int sampleRate, int channels) {
        m_sampleRate = sampleRate;
        m_channels = channels;
        m_expander->setSampleRate(sampleRate);
        m_peq->setSampleRate(sampleRate);
        m_crossfeed->setSampleRate(sampleRate);
        m_compressor->setSampleRate(sampleRate);
        m_bassBoost->setSampleRate(sampleRate);
        m_trebleBoost->setSampleRate(sampleRate);
        m_surround360->setSampleRate(sampleRate);
        m_panoramic360->setSampleRate(sampleRate);
        m_convolver->setFormat(sampleRate, channels);
        m_speakerOutputEffect->setSampleRate(sampleRate);
        m_floatBuf.resize(48000 * 2);
    }

    void process(float* samples, int numFrames, int channels) {
        // 处理链顺序: BassBoost → TrebleBoost → PEQ → FFT Convolver → Compressor → Surround360 → Panoramic360 → Crossfeed → StereoWidth → SpeakerOutput

        // 1. 低音增强
        if (m_bassBoost->isEnabled()) {
            m_bassBoost->process(samples, numFrames, channels);
        }

        // 2. 高音增强
        if (m_trebleBoost->isEnabled()) {
            m_trebleBoost->process(samples, numFrames, channels);
        }

        // 3. 参量均衡器
        if (m_peq->isEnabled()) {
            m_peq->process(samples, numFrames, channels);
        }

        // 4. FFT 卷积器：用于耳机/音箱校正 IR 或轻量空间 IR
        if (m_convolver->isEnabled() && m_convolver->isReady()) {
            m_convolver->process(samples, numFrames, channels);
        }

        // 5. 压限器
        if (m_compressor->isEnabled()) {
            m_compressor->process(samples, numFrames, channels);
        }

        // 6. 360° 环绕音 (2D 水平面双耳渲染)
        if (m_surround360->isEnabled()) {
            m_surround360->process(samples, numFrames, channels);
        }

        // 7. 360° 全景音 (3D 球面双耳渲染)
        if (m_panoramic360->isEnabled()) {
            m_panoramic360->process(samples, numFrames, channels);
        }

        // 8. 互馈 (Crossfeed)：先完成耳机串音模拟，再由立体声扩展
        // 对最终左右差异执行连续 Mid/Side 展宽。
        if (m_crossfeed->isEnabled()) {
            m_crossfeed->process(samples, numFrames, channels);
        }

        // 9. 立体声扩展：0–100% 连续映射到 1–3 倍 Side，
        // 随后使用双声道联动峰值控制保持声像稳定。
        if (m_expander->isEnabled()) {
            m_expander->process(samples, numFrames, channels);
        }

        // 10. 扬声器外放效果：在最终混合信号上识别低中频瞬态，
        // 只施加短促联动增益，之后仍由统一输出安全限幅器兜底。
        if (m_speakerOutputEffect->isEnabled()) {
            m_speakerOutputEffect->process(samples, numFrames, channels);
        }
    }

    bool hasActiveEffects() const {
        return m_bassBoost->isEnabled() ||
               m_trebleBoost->isEnabled() ||
               m_peq->isEnabled() ||
               (m_convolver->isEnabled() && m_convolver->isReady()) ||
               m_compressor->isEnabled() ||
               m_surround360->isEnabled() ||
               m_panoramic360->isEnabled() ||
               m_expander->isEnabled() ||
               m_crossfeed->isEnabled() ||
               m_speakerOutputEffect->isEnabled();
    }

    float* getFloatBuffer() { return m_floatBuf.data(); }
    StereoWidthProcessor* getExpander() { return m_expander.get(); }
    ParametricEQ* getPEQ() { return m_peq.get(); }
    Crossfeed* getCrossfeed() { return m_crossfeed.get(); }
    Compressor* getCompressor() { return m_compressor.get(); }
    BassBoost* getBassBoost() { return m_bassBoost.get(); }
    TrebleBoost* getTrebleBoost() { return m_trebleBoost.get(); }
    Surround360* getSurround360() { return m_surround360.get(); }
    Panoramic360* getPanoramic360() { return m_panoramic360.get(); }
    RawFftConvolver* getConvolver() { return m_convolver.get(); }
    SpeakerOutputEffect* getSpeakerOutputEffect() { return m_speakerOutputEffect.get(); }
};

extern "C" SpeakerOutputEffect* rawsmusic_dsp_get_speaker_output_effect(jlong handle) {
    if (handle == 0) return nullptr;
    return reinterpret_cast<DSPChain*>(handle)->getSpeakerOutputEffect();
}

extern "C" RawFftConvolver* rawsmusic_dsp_get_fft_convolver(jlong handle) {
    if (handle == 0) return nullptr;
    return reinterpret_cast<DSPChain*>(handle)->getConvolver();
}

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

    const bool shouldLimitOutput = chain->hasActiveEffects();
    chain->process(floatBuf, numFrames, channels);
    if (shouldLimitOutput) {
        applyOutputSafetyLimiter(floatBuf, length);
    }

    for (int i = 0; i < length; ++i) {
        float v = floatBuf[i] < 0.0f ? floatBuf[i] * 32768.0f : floatBuf[i] * 32767.0f;
        samples[i] = (short)v;
    }

    env->ReleasePrimitiveArrayCritical(buffer, samples, 0);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeProcessFloat(
        JNIEnv* env, jobject, jlong handle, jfloatArray buffer, jint length, jint channels) {
    if (handle == 0) return -1;

    jfloat* samples = (jfloat*)env->GetPrimitiveArrayCritical(buffer, nullptr);
    if (samples == nullptr) return -2;

    auto* chain = reinterpret_cast<DSPChain*>(handle);
    int numFrames = length / channels;

    // Float32 数据已经是 [-1.0, 1.0] 范围，直接处理
    const bool shouldLimitOutput = chain->hasActiveEffects();
    chain->process(samples, numFrames, channels);
    if (shouldLimitOutput) {
        applyOutputSafetyLimiter(samples, length);
    }

    env->ReleasePrimitiveArrayCritical(buffer, samples, 0);
    return 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeHasActiveEffects(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    return chain->hasActiveEffects() ? JNI_TRUE : JNI_FALSE;
}

// ==========================================
// 参量均衡器 JNI 接口
// ==========================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetPEQEnabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getPEQ()->setEnabled(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetPEQFilter(
        JNIEnv*, jobject, jlong handle, jint index, jint type,
        jfloat frequency, jfloat gainDB, jfloat Q, jboolean enabled) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);

    FilterParams params;
    params.type = static_cast<FilterType>(type);
    params.frequency = frequency;
    params.gainDB = gainDB;
    params.Q = Q;
    params.enabled = enabled;

    chain->getPEQ()->setFilter(index, params);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeRemovePEQFilter(
        JNIEnv*, jobject, jlong handle, jint index) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getPEQ()->removeFilter(index);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeClearPEQFilters(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getPEQ()->clearAll();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeCalcPEQResponse(
        JNIEnv* env, jobject, jlong handle, jfloatArray frequencies,
        jfloatArray magnitudes, jint numPoints) {
    if (handle == 0) return;

    auto* chain = reinterpret_cast<DSPChain*>(handle);

    jfloat* freqs = env->GetFloatArrayElements(frequencies, nullptr);
    jfloat* mags = env->GetFloatArrayElements(magnitudes, nullptr);

    if (freqs && mags) {
        chain->getPEQ()->calcFrequencyResponse(freqs, mags, numPoints);
    }

    env->ReleaseFloatArrayElements(frequencies, freqs, 0);
    env->ReleaseFloatArrayElements(magnitudes, mags, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetPreamp(
        JNIEnv*, jobject, jlong handle, jfloat gainDB) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getPEQ()->setPreamp(gainDB);
}

// ==========================================
// 互馈 (Crossfeed) JNI 接口
// ==========================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetCrossfeedEnabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getCrossfeed()->setEnabled(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetCrossfeedParams(
        JNIEnv*, jobject, jlong handle, jfloat lowCutFreq, jfloat highCutFreq, jfloat attenuationDB) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    auto* cf = chain->getCrossfeed();
    cf->setLowCutFreq(lowCutFreq);
    cf->setHighCutFreq(highCutFreq);
    cf->setAttenuationDB(attenuationDB);
}


// ==========================================
// 压限器 (Compressor) JNI 接口
// ==========================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetCompressorEnabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getCompressor()->setEnabled(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetCompressorParams(
        JNIEnv*, jobject, jlong handle,
        jfloat thresholdDB, jfloat ratio, jfloat attackMs, jfloat releaseMs, jfloat makeupGainDB) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getCompressor()->setParams(thresholdDB, ratio, attackMs, releaseMs, makeupGainDB);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetCompressorKneeWidth(
        JNIEnv*, jobject, jlong handle, jfloat kneeWidthDB) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getCompressor()->setKneeWidth(kneeWidthDB);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetCompressorDetectionMode(
        JNIEnv*, jobject, jlong handle, jint mode) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getCompressor()->setDetectionMode(mode);
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeGetCompressorGR(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return 0.0f;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    return chain->getCompressor()->getCurrentGR();
}

// ==========================================
// 低音增强 (BassBoost) JNI 接口
// ==========================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetBassBoostEnabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getBassBoost()->setEnabled(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetBassBoostParams(
        JNIEnv*, jobject, jlong handle, jfloat gainDB, jfloat frequency) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getBassBoost()->setParams(gainDB, frequency);
}

// ==========================================
// 高音增强 (TrebleBoost) JNI 接口
// ==========================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetTrebleBoostEnabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getTrebleBoost()->setEnabled(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetTrebleBoostParams(
        JNIEnv*, jobject, jlong handle, jfloat gainDB, jfloat frequency) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getTrebleBoost()->setParams(gainDB, frequency);
}

// ==========================================
// 360° 环绕音 (Surround360) JNI 接口
// ==========================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetSurround360Enabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getSurround360()->setEnabled(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetSurround360Params(
        JNIEnv*, jobject, jlong handle, jfloat intensity, jfloat azimuthDeg) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getSurround360()->setParams(intensity, azimuthDeg);
}

// ==========================================
// 360° 全景音 (Panoramic360) JNI 接口
// ==========================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetPanoramic360Enabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getPanoramic360()->setEnabled(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetPanoramic360Params(
        JNIEnv*, jobject, jlong handle, jfloat intensity, jfloat azimuthDeg, jfloat elevationDeg) {
    if (handle == 0) return;
    auto* chain = reinterpret_cast<DSPChain*>(handle);
    chain->getPanoramic360()->setParams(intensity, azimuthDeg, elevationDeg);
}
