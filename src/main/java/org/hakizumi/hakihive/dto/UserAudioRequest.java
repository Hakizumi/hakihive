package org.hakizumi.hakihive.dto;

/**
 * User or assistant audio/conversation segment DTO.
 *
 * @param text conversation content
 * @param cid  conversation id
 *
 * @author Hakizumi
 * @since 1.7.0
 */
public record UserAudioRequest(
        String text,
        String cid
) {
}
