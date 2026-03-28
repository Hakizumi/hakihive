package org.hakizumi.hakihive.dto.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.hakizumi.hakihive.dto.ConversationRequest;
import org.hakizumi.hakihive.dto.ConversationResponse;
import org.hakizumi.hakihive.service.LLMService;

/**
 * Assistant reply event dto.
 * Calls after assistant reply.
 * <p>
 * When it is streaming reply,
 * {@link LLMService} publish the {@link AssistantDeltaReplyEvent} event,
 * and pass the wrapped assistants' {@code reply delta}.
 * <p>
 * When it is streaming reply,
 * {@link LLMService} publish the {@link AssistantWholeReplyEvent} event,
 * and pass the wrapped assistants' {@code reply whole message}.
 *
 * @since 1.4.0
 * @author Hakizumi
 *
 * @see AssistantDeltaReplyEvent
 * @see AssistantWholeReplyEvent
 */
@AllArgsConstructor
@Getter
@Setter
public class AssistantReplyEvent {
    private final ConversationRequest request;
    private final ConversationResponse response;
}
