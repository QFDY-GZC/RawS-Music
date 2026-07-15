#pragma once

#include <memory>

/**
 * 通用扬声器外放增强器。
 *
 * 当前包含三种互斥模式：
 * - Elasticity：低中频瞬态并行增强，强调冲击、收紧与回弹。
 * - Powerful：动态低频、低频谐波、并行密度和轻量存在感，强调饱满与能量。
 * - Wide：低频保持稳定，中高频扩展侧声道并加入轻量去相关，强调开阔感。
 *
 * 模块不负责判断 Android 输出路由；上层决定何时启用。
 */
class SpeakerOutputEffect {
public:
    enum class Mode : int {
        Elasticity = 0,
        Powerful = 1,
        Wide = 2
    };

    SpeakerOutputEffect();
    ~SpeakerOutputEffect();

    SpeakerOutputEffect(const SpeakerOutputEffect&) = delete;
    SpeakerOutputEffect& operator=(const SpeakerOutputEffect&) = delete;

    void setSampleRate(int sampleRate);
    void reset();

    void setEnabled(bool enabled);
    bool isEnabled() const;

    /**
     * 切换当前外放模式。切换在音频块边界生效，并清理上一模式的包络与滤波状态，
     * 避免模式之间残留动态处理尾巴。
     */
    void setMode(Mode mode);

    /**
     * 更新“弹性”参数。参数先写入原子 pending 区，再由音频线程在块边界应用。
     *
     * @param strengthPercent 总体强度，0..100%。
     * @param detectorLowHz 起音频段下限，40..300 Hz。
     * @param detectorHighHz 起音频段上限，300..3000 Hz。
     * @param fastAttackMs 快包络启动，0.1..10 ms。
     * @param fastReleaseMs 快包络释放，5..150 ms。
     * @param slowAttackMs 慢包络启动，2..100 ms。
     * @param slowReleaseMs 慢包络释放，30..500 ms。
     * @param gainAttackMs 并行冲击进入速度，0.1..10 ms。
     * @param gainReleaseMs 冲击与主体回收的恢复速度，10..250 ms。
     * @param maxBoostDb 起音频段最大并行提升，0..6 dB。
     * @param noiseGateDb 检测门限，-80..-24 dBFS。
     * @param headroomCeiling 峰值目标，0.70..0.995 线性满幅。
     * @param peakReleaseMs 动态余量压力释放，10..300 ms。
     * @param sensitivity 瞬态灵敏度，0.25..3.0。
     */
    void setElasticityParameters(
        float strengthPercent,
        float detectorLowHz,
        float detectorHighHz,
        float fastAttackMs,
        float fastReleaseMs,
        float slowAttackMs,
        float slowReleaseMs,
        float gainAttackMs,
        float gainReleaseMs,
        float maxBoostDb,
        float noiseGateDb,
        float headroomCeiling,
        float peakReleaseMs,
        float sensitivity
    );

    /**
     * 更新“澎湃”参数。该模式由动态低频、轻度低频谐波、并行压缩密度和存在感组成。
     * 各分支共享峰值预测与输出保护，不会简单叠加为持续全频增益。
     *
     * @param strengthPercent 总体强度，0..100%。同时缩放低频、密度、谐波和存在感。
     * @param bodyLowHz 能量频段高通，40..140 Hz；过滤位移噪声与不可用超低频。
     * @param bodyHighHz 能量频段低通，180..700 Hz；决定厚度覆盖范围。
     * @param bassBoostDb 动态低频最大并行提升，0..6 dB。
     * @param harmonicPercent 低频谐波量，0..100%；用于小扬声器的可感知低频。
     * @param compressorThresholdDb 并行密度压缩阈值，-36..-6 dBFS。
     * @param compressorRatio 压缩比，1..8。
     * @param compressorAttackMs 压缩启动，2..80 ms；保留鼓点前沿时不宜过短。
     * @param compressorReleaseMs 压缩释放，40..500 ms；决定能量持续感。
     * @param parallelMixPercent 压缩分支混合比例，0..100%。
     * @param makeupGainDb 压缩分支补偿增益，0..6 dB。
     * @param presenceBoostDb 中高频存在感并行提升，0..4 dB。
     * @param headroomCeiling 输出峰值目标，0.70..0.995 线性满幅。
     */
    void setPowerfulParameters(
        float strengthPercent,
        float bodyLowHz,
        float bodyHighHz,
        float bassBoostDb,
        float harmonicPercent,
        float compressorThresholdDb,
        float compressorRatio,
        float compressorAttackMs,
        float compressorReleaseMs,
        float parallelMixPercent,
        float makeupGainDb,
        float presenceBoostDb,
        float headroomCeiling
    );

    /**
     * 更新“宽广”参数。该模式只扩展中高频侧声道，并通过低频居中、中心保护和峰值预测
     * 保持人声与低频稳定；数学上的单声道和声仍保持为原始 Mid 分量。
     *
     * @param strengthPercent 总体强度，0..100%。
     * @param crossoverHz 侧声道扩展起始频率，300..2200 Hz。
     * @param widthDb 中高频侧声道最大提升，0..6 dB。
     * @param decorrelationPercent 轻量全通去相关混合，0..60%。
     * @param bassCenterPercent 低于分频点的侧声道收拢量，0..100%。
     * @param centerProtectionPercent 中心保护强度，0..100%；越高越主动限制侧声道过强。
     * @param headroomCeiling 输出峰值目标，0.70..0.995 线性满幅。
     */
    void setWideParameters(
        float strengthPercent,
        float crossoverHz,
        float widthDb,
        float decorrelationPercent,
        float bassCenterPercent,
        float centerProtectionPercent,
        float headroomCeiling
    );

    void process(float* samples, int numFrames, int channels);

private:
    class Impl;
    std::unique_ptr<Impl> m_impl;
};
