package com.rawsmusic.module.player.dsp;

/** Java facade for the native speaker-output processor. */
public final class SpeakerOutputEffect {
    public enum Mode {
        ELASTICITY,
        POWERFUL,
        WIDE
    }

    private NativeDSPEngine engine;

    public SpeakerOutputEffect(NativeDSPEngine engine) {
        this.engine = engine;
    }

    public void connectEngine(NativeDSPEngine engine) {
        this.engine = engine;
    }

    public void setEnabled(boolean enabled) {
        if (engine != null) engine.setSpeakerOutputEnabled(enabled);
    }

    public void setMode(Mode mode) {
        if (engine == null) return;
        SpeakerOutputEffectController.Mode nativeMode;
        if (mode == Mode.POWERFUL) {
            nativeMode = SpeakerOutputEffectController.Mode.POWERFUL;
        } else if (mode == Mode.WIDE) {
            nativeMode = SpeakerOutputEffectController.Mode.WIDE;
        } else {
            nativeMode = SpeakerOutputEffectController.Mode.ELASTICITY;
        }
        engine.setSpeakerOutputMode(nativeMode);
    }

    public void applyElasticityParameters(ElasticityParameters parameters) {
        if (engine == null || parameters == null) return;
        engine.setSpeakerOutputElasticityParams(
                parameters.strengthPercent,
                parameters.detectorLowHz,
                parameters.detectorHighHz,
                parameters.sensitivityPercent,
                parameters.gateThresholdDb,
                parameters.fastAttackMs,
                parameters.fastReleaseMs,
                parameters.slowAttackMs,
                parameters.slowReleaseMs,
                parameters.gainAttackMs,
                parameters.gainReleaseMs,
                parameters.maxBoostDb,
                parameters.peakCeilingDb
        );
    }

    public void applyPowerfulParameters(PowerfulParameters parameters) {
        if (engine == null || parameters == null) return;
        engine.setSpeakerPowerfulParameters(
                new SpeakerOutputEffectController.PowerfulParameters(
                        parameters.strengthPercent,
                        parameters.bodyLowHz,
                        parameters.bodyHighHz,
                        parameters.bassBoostDb,
                        parameters.harmonicPercent,
                        parameters.compressorThresholdDb,
                        parameters.compressorRatio,
                        parameters.compressorAttackMs,
                        parameters.compressorReleaseMs,
                        parameters.parallelMixPercent,
                        parameters.makeupGainDb,
                        parameters.presenceBoostDb,
                        dbToLinear(parameters.peakCeilingDb)
                )
        );
    }

    public void applyWideParameters(WideParameters parameters) {
        if (engine == null || parameters == null) return;
        engine.setSpeakerWideParameters(
                new SpeakerOutputEffectController.WideParameters(
                        parameters.strengthPercent,
                        parameters.crossoverHz,
                        parameters.widthDb,
                        parameters.decorrelationPercent,
                        parameters.bassCenterPercent,
                        parameters.centerProtectionPercent,
                        dbToLinear(parameters.peakCeilingDb)
                )
        );
    }

    public static final class ElasticityParameters {
        public final float strengthPercent;
        public final float detectorLowHz;
        public final float detectorHighHz;
        public final float sensitivityPercent;
        public final float gateThresholdDb;
        public final float fastAttackMs;
        public final float fastReleaseMs;
        public final float slowAttackMs;
        public final float slowReleaseMs;
        public final float gainAttackMs;
        public final float gainReleaseMs;
        public final float maxBoostDb;
        public final float peakCeilingDb;

        public ElasticityParameters(
                float strengthPercent,
                float detectorLowHz,
                float detectorHighHz,
                float sensitivityPercent,
                float gateThresholdDb,
                float fastAttackMs,
                float fastReleaseMs,
                float slowAttackMs,
                float slowReleaseMs,
                float gainAttackMs,
                float gainReleaseMs,
                float maxBoostDb,
                float peakCeilingDb
        ) {
            this.strengthPercent = clampFinite(strengthPercent, 82f, 0f, 100f);
            this.detectorLowHz = clampFinite(detectorLowHz, 85f, 50f, 250f);
            this.detectorHighHz = Math.max(
                    this.detectorLowHz + 100f,
                    clampFinite(detectorHighHz, 1350f, 400f, 2500f)
            );
            this.sensitivityPercent = clampFinite(sensitivityPercent, 82f, 0f, 100f);
            this.gateThresholdDb = clampFinite(gateThresholdDb, -50f, -72f, -24f);
            this.fastAttackMs = clampFinite(fastAttackMs, 0.35f, 0.2f, 5f);
            this.fastReleaseMs = clampFinite(fastReleaseMs, 20f, 8f, 100f);
            this.slowAttackMs = Math.max(
                    this.fastAttackMs + 1f,
                    clampFinite(slowAttackMs, 34f, 4f, 80f)
            );
            this.slowReleaseMs = Math.max(
                    this.fastReleaseMs + 10f,
                    clampFinite(slowReleaseMs, 165f, 40f, 500f)
            );
            this.gainAttackMs = clampFinite(gainAttackMs, 0.3f, 0.2f, 10f);
            this.gainReleaseMs = clampFinite(gainReleaseMs, 62f, 10f, 250f);
            this.maxBoostDb = clampFinite(maxBoostDb, 4.2f, 0f, 6f);
            this.peakCeilingDb = clampFinite(peakCeilingDb, -0.2f, -6f, -0.1f);
        }
    }

    public static final class PowerfulParameters {
        public final float strengthPercent;
        public final float bodyLowHz;
        public final float bodyHighHz;
        public final float bassBoostDb;
        public final float harmonicPercent;
        public final float compressorThresholdDb;
        public final float compressorRatio;
        public final float compressorAttackMs;
        public final float compressorReleaseMs;
        public final float parallelMixPercent;
        public final float makeupGainDb;
        public final float presenceBoostDb;
        public final float peakCeilingDb;

        public PowerfulParameters(
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
                float peakCeilingDb
        ) {
            this.strengthPercent = clampFinite(strengthPercent, 84f, 0f, 100f);
            this.bodyLowHz = clampFinite(bodyLowHz, 65f, 40f, 140f);
            this.bodyHighHz = Math.max(
                    this.bodyLowHz + 100f,
                    clampFinite(bodyHighHz, 390f, 180f, 700f)
            );
            this.bassBoostDb = clampFinite(bassBoostDb, 4f, 0f, 6f);
            this.harmonicPercent = clampFinite(harmonicPercent, 34f, 0f, 100f);
            this.compressorThresholdDb = clampFinite(compressorThresholdDb, -20f, -36f, -6f);
            this.compressorRatio = clampFinite(compressorRatio, 3.5f, 1f, 8f);
            this.compressorAttackMs = clampFinite(compressorAttackMs, 10f, 2f, 80f);
            this.compressorReleaseMs = clampFinite(compressorReleaseMs, 200f, 40f, 500f);
            this.parallelMixPercent = clampFinite(parallelMixPercent, 48f, 0f, 100f);
            this.makeupGainDb = clampFinite(makeupGainDb, 3.4f, 0f, 6f);
            this.presenceBoostDb = clampFinite(presenceBoostDb, 1.3f, 0f, 4f);
            this.peakCeilingDb = clampFinite(peakCeilingDb, -0.25f, -6f, -0.1f);
        }
    }

    public static final class WideParameters {
        public final float strengthPercent;
        public final float crossoverHz;
        public final float widthDb;
        public final float decorrelationPercent;
        public final float bassCenterPercent;
        public final float centerProtectionPercent;
        public final float peakCeilingDb;

        public WideParameters(
                float strengthPercent,
                float crossoverHz,
                float widthDb,
                float decorrelationPercent,
                float bassCenterPercent,
                float centerProtectionPercent,
                float peakCeilingDb
        ) {
            this.strengthPercent = clampFinite(strengthPercent, 76f, 0f, 100f);
            this.crossoverHz = clampFinite(crossoverHz, 760f, 300f, 2200f);
            this.widthDb = clampFinite(widthDb, 3.2f, 0f, 6f);
            this.decorrelationPercent = clampFinite(decorrelationPercent, 18f, 0f, 60f);
            this.bassCenterPercent = clampFinite(bassCenterPercent, 58f, 0f, 100f);
            this.centerProtectionPercent = clampFinite(centerProtectionPercent, 70f, 0f, 100f);
            this.peakCeilingDb = clampFinite(peakCeilingDb, -0.25f, -6f, -0.1f);
        }
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static float clampFinite(float value, float fallback, float minimum, float maximum) {
        float safe = Float.isFinite(value) ? value : fallback;
        return Math.max(minimum, Math.min(maximum, safe));
    }
}
