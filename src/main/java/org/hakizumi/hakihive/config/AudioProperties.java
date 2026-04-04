package org.hakizumi.hakihive.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audio configuration properties.
 * <p>
 * Defaults are tuned for low-latency streaming ASR.
 *
 * @since 1.7.0
 * @author Hakizumi
 */
@ConfigurationProperties("hakihive.audio")
@Getter
@Setter
@ToString
public class AudioProperties {
    // ====== Audio format (recommended for streaming ASR) ======

    /**
     * Target sample rate in Hz for browser streaming ASR.
     */
    private int sampleRate = 16000;

    // ====== ASR properties ======

    /**
     * Audio recognize service threads.
     */
    private int asrThreads = 1;

    /**
     * Fallback VAD RMS threshold when adaptive noise floor is not stable yet.
     */
    private float vadRmsThreshold = 0.012f;

    /**
     * Lower bound for adaptive VAD threshold.
     */
    private float minVadRmsThreshold = 0.008f;

    /**
     * Adaptive threshold = noiseFloor * vadNoiseMultiplier.
     */
    private float vadNoiseMultiplier = 2.4f;

    /**
     * Exponential moving average alpha for noise floor tracking.
     */
    private float vadNoiseFloorAlpha = 0.92f;

    /**
     * Silence frames required to finalize one utterance.
     */
    private long silenceTriggerFrame = 12;

    /**
     * Minimum speech frames required to accept one utterance.
     */
    private long speechTriggerFrame = 6;

    /**
     * Consecutive speaking frames required before barge-in stops assistant.
     */
    private long bargeInSpeechFrames = 3;
}
