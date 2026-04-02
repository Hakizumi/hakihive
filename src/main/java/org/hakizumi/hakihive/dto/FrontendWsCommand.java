package org.hakizumi.hakihive.dto;

import org.jspecify.annotations.NonNull;

/**
 * Browser websocket command DTO.
 * <p>
 * Serialized like:
 * <blockquote>
 * <pre>
 * {"type":"xx","cid":"conversation-id","text":"text"}
 * </pre>
 * </blockquote>
 *
 * @param type command type, e.g. chat / stop / ping
 * @param cid conversation id
 * @param text user conversation
 *
 * @since 1.7.0
 * @author Hakizumi
 */
public record FrontendWsCommand(String type, String cid, String text) {
    /**
     * Returns the normalized command type.
     */
    public @NonNull String normalizedType() {
        return type == null ? "" : type.trim();
    }
}
