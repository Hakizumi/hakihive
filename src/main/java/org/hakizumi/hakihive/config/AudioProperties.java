package org.hakizumi.hakihive.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audio configuration properties.
 * <p>
 * Defaults are tuned for low-latency streaming ASR:
 * 16kHz mono PCM16, 20ms frames, bounded queues, and short finalize timeouts.
 *
 * @since 1.7.0
 * @author Hakizumi
 */
@ConfigurationProperties("hakihive.audio")
@Getter
@Setter
@ToString
public class AudioProperties {
    // ====== ASR properties ======

    /**
     * Audio recognize service threads
     */
    private int asrThreads = 1;

    /**
     *  The rms value is greater than ? is considered as speaking
     */
    private float vadRmsThreshold = 0.015f;

    /**
     * The silence frames is greater than ? is considered stop speaking
     */
    private long silenceTriggerFrame = 18;    // treat as endpoint if silence persists

    /**
     * Speech frames is greater than ? frames is considered speeching
     */
    private long speechTriggerFrame = 18;
}
