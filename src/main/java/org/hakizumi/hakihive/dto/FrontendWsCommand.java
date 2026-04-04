package org.hakizumi.hakihive.dto;

import org.jspecify.annotations.NonNull;

/**
 * Browser websocket command DTO.
 * <p>
 * Serialized like:
 * <blockquote>
 * <pre>
 * {"type":"xx","text":"text","sampleRate":16000}
 * </pre>
 * </blockquote>
 *
 * @param type command type, e.g. chat / stop / ping / audio_meta
 * @param text user conversation
 * @param sampleRate sample rate in Hz for streamed audio chunks
 *
 * @since 1.7.0
 * @author Hakizumi
 */
public record FrontendWsCommand(String type, String text, Integer sampleRate) {
    /**
     * Returns the normalized command type.
     */
    public @NonNull String normalizedType() {
        return type == null ? "" : type.trim();
    }
}
