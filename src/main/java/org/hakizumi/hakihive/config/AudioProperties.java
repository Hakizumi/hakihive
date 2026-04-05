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
    /**
     * Target sample rate in Hz for browser streaming ASR.
     */
    private int sampleRate = 16000;

    /**
     * Audio recognize service threads.
     */
    private int asrThreads = 1;

    /**
     * Minimum decoded frames required to accept one utterance.
     */
    private long speechTriggerFrame = 6;

    /**
     * Consecutive transcript updates required before barge-in stops assistant.
     */
    private long bargeInSpeechFrames = 2;

    /**
     * Max queued tasks per cid worker.
     */
    private int maxConversationTasks = 512;

    /**
     * Max pcm16le bytes allowed in one websocket binary frame.
     */
    private int maxAudioBytesPerFrame = 65536;

    /**
     * Sherpa endpoint rule1 trailing silence seconds.
     */
    private float sherpaRule1MinTrailingSilence = 2.4f;

    /**
     * Sherpa endpoint rule2 trailing silence seconds.
     */
    private float sherpaRule2MinTrailingSilence = 1.2f;

    /**
     * Sherpa endpoint rule3 minimum utterance length seconds.
     */
    private float sherpaRule3MinUtteranceLength = 20.0f;
}
