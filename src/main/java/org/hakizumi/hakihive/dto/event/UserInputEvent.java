package org.hakizumi.hakihive.dto.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.hakizumi.hakihive.dto.ConversationRequest;

/**
 * User ask event dto.
 * Calls before assistant reply and after user's message request reaches the backend.
 * <p>
 * Specialization:
 * {@link UserStreamingInputEvent} User ask event and requires streaming reply.
 * {@link UserNonStreamingInputEvent} User ask event and requires non-streaming reply.
 *
 * @since 1.4.0
 * @author Hakizumi
 *
 * @see UserStreamingInputEvent
 * @see UserNonStreamingInputEvent
 */
@AllArgsConstructor
@Getter
@Setter
public class UserInputEvent {
    private final ConversationRequest request;
}
