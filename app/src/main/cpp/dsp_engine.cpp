#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <vector>
#include <memory>
#include <cstring>
#include <cmath>
#include <math.h>

using namespace std;

#define TAG "NativeDSP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// 频率转换系数：标准 RBJ 使用 2π
#define FREQ_CONST (2.0 * M_PI)

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
    static const int MAX_FILTERS = 10;
    BiQuad m_filters[MAX_FILTERS];
    FilterParams m_params[MAX_FILTERS];
    int m_numFilters = 0;
    int m_sampleRate = 44100;
    bool m_enabled = false;
    float m_preampDB = 0.0f;  // 前置放大器增益 (dB)

public:
    ParametricEQ() {
        for (int i = 0; i < MAX_FILTERS; i++) {
            m_params[i] = {FILTER_PEAK, 1000.0f, 0.0f, 1.0f, false};
        }
    }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0) {
            m_sampleRate = sampleRate;
            updateAllCoeffs();
        }
    }

    void setEnabled(bool enabled) {
        m_enabled = enabled;
    }

    bool isEnabled() const { return m_enabled; }

    void setPreamp(float gainDB) {
        m_preampDB = gainDB;
    }

    float getPreamp() const { return m_preampDB; }

    int getNumFilters() const { return m_numFilters; }

    void setFilter(int index, const FilterParams& params) {
        if (index < 0 || index >= MAX_FILTERS) return;

        m_params[index] = params;
        updateFilterCoeff(index);

        // 始终跟踪最大索引（无论是否启用）
        if (index >= m_numFilters) {
            m_numFilters = index + 1;
        }
    }

    FilterParams getFilter(int index) const {
        if (index < 0 || index >= MAX_FILTERS) return {FILTER_PEAK, 1000.0f, 0.0f, 1.0f, false};
        return m_params[index];
    }

    void removeFilter(int index) {
        if (index < 0 || index >= m_numFilters) return;

        // 移动后面的滤波器
        for (int i = index; i < m_numFilters - 1; i++) {
            m_params[i] = m_params[i + 1];
            m_filters[i] = m_filters[i + 1];
        }

        m_numFilters--;
        m_params[m_numFilters] = {FILTER_PEAK, 1000.0f, 0.0f, 1.0f, false};
        m_filters[m_numFilters].reset();
    }

    void clearAll() {
        m_numFilters = 0;
        for (int i = 0; i < MAX_FILTERS; i++) {
            m_params[i] = {FILTER_PEAK, 1000.0f, 0.0f, 1.0f, false};
            m_filters[i].reset();
        }
    }

    // 计算总频率响应 (用于绘制曲线)
    void calcFrequencyResponse(float* frequencies, float* magnitudes, int numPoints) const {
        for (int i = 0; i < numPoints; i++) {
            float totalMag = m_preampDB;  // 前置放大器增益

            for (int f = 0; f < MAX_FILTERS; f++) {
                if (m_params[f].enabled) {
                    totalMag += m_filters[f].calcMagnitude(frequencies[i], m_sampleRate);
                }
            }

            magnitudes[i] = totalMag;
        }
    }

    // 处理音频数据 - 真正的 BiQuad IIR 滤波
    void process(float* samples, int numFrames, int channels) {
        if (!m_enabled) return;

        // 计算前置放大器线性增益: gain = 10^(dB/20)
        float preampGain = powf(10.0f, m_preampDB / 20.0f);

        for (int i = 0; i < numFrames; i++) {
            for (int ch = 0; ch < channels; ch++) {
                float sample = samples[i * channels + ch] * preampGain;  // 应用前置放大器
                // 依次通过所有激活的滤波器
                for (int f = 0; f < MAX_FILTERS; f++) {
                    if (m_params[f].enabled) {
                        sample = m_filters[f].processSample(sample, ch > 0);
                    }
                }
                samples[i * channels + ch] = sample;
            }
        }
    }

private:
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

    void updateCoeffs() {
        // Q=0.707 巴特沃斯响应
        m_hp.setHP2_RBJ(m_sampleRate, m_lowCutFreq, 0.707f);
        m_lp.setLP2_RBJ(m_sampleRate, m_highCutFreq, 0.707f);
    }

public:
    Crossfeed() { updateCoeffs(); }

    void setEnabled(bool enabled) {
        if (!m_enabled && enabled) {
            // 启用时重置滤波器状态，避免点击噪声
            m_hp.reset();
            m_lp.reset();
        }
        m_enabled = enabled;
    }

    bool isEnabled() const { return m_enabled; }

    void setSampleRate(int sampleRate) {
        if (sampleRate > 0 && sampleRate != m_sampleRate) {
            m_sampleRate = sampleRate;
            m_hp.reset();
            m_lp.reset();
            updateCoeffs();
        }
    }

    void setLowCutFreq(float freq) {
        freq = (freq < 50.0f) ? 50.0f : (freq > 1000.0f) ? 1000.0f : freq;
        if (freq != m_lowCutFreq) {
            m_lowCutFreq = freq;
            m_hp.setHP2_RBJ(m_sampleRate, m_lowCutFreq, 0.707f);
        }
    }

    void setHighCutFreq(float freq) {
        freq = (freq < 500.0f) ? 500.0f : (freq > 8000.0f) ? 8000.0f : freq;
        if (freq != m_highCutFreq) {
            m_highCutFreq = freq;
            m_lp.setLP2_RBJ(m_sampleRate, m_highCutFreq, 0.707f);
        }
    }

    void setAttenuationDB(float db) {
        db = (db < 0.0f) ? 0.0f : (db > 15.0f) ? 15.0f : db;
        m_attenuationDB = db;
    }

    float getLowCutFreq() const { return m_lowCutFreq; }
    float getHighCutFreq() const { return m_highCutFreq; }
    float getAttenuationDB() const { return m_attenuationDB; }

    // 处理立体声音频
    void process(float* samples, int numFrames, int channels) {
        if (!m_enabled || channels < 2) return;

        // 衰减量转线性增益: gain = 10^(-dB/20)
        float crossGain = powf(10.0f, -m_attenuationDB / 20.0f);

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
};

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
            float maxAbs = max(fabsf(outL), fabsf(outR));
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

            outL = max(-1.0f, min(1.0f, outL));
            outR = max(-1.0f, min(1.0f, outR));

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
    std::unique_ptr<ParametricEQ> m_peq;
    std::vector<float> m_floatBuf;
    int m_sampleRate = 44100;
    int m_channels = 2;

public:
    DSPChain() : m_expander(std::make_unique<StereoExpander>()),
                 m_peq(std::make_unique<ParametricEQ>()) {}

    void init(int sampleRate, int channels) {
        m_sampleRate = sampleRate;
        m_channels = channels;
        m_expander->setSampleRate(sampleRate);
        m_peq->setSampleRate(sampleRate);
        m_floatBuf.resize(48000 * 2);
    }

    void process(float* samples, int numFrames, int channels) {
        // 先处理PEQ
        if (m_peq->isEnabled()) {
            m_peq->process(samples, numFrames, channels);
        }

        // 再处理立体声扩展
        if (m_expander->isEnabled()) {
            m_expander->process(samples, numFrames, channels);
        }
    }

    float* getFloatBuffer() { return m_floatBuf.data(); }
    StereoExpander* getExpander() { return m_expander.get(); }
    ParametricEQ* getPEQ() { return m_peq.get(); }
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
        v = max(-32768.0f, min(v, 32767.0f));
        samples[i] = (short)v;
    }

    env->ReleasePrimitiveArrayCritical(buffer, samples, 0);
    return 0;
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
