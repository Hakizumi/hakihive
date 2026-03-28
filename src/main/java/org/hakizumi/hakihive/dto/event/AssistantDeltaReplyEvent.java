package org.hakizumi.hakihive.dto.event;

import org.hakizumi.hakihive.dto.ConversationRequest;
import org.hakizumi.hakihive.dto.ConversationResponse;
import org.hakizumi.hakihive.service.LLMService;

/**
 * Specialized event dto class of {@link AssistantReplyEvent}.
 * Calls when the {@link LLMService#streaming(ConversationRequest)} produced a new assistants' reply delta.
 *
 * @since 1.4.0
 * @author Hakizumi
 *
 * @see AssistantReplyEvent
 */
public class AssistantDeltaReplyEvent extends AssistantReplyEvent {
    public AssistantDeltaReplyEvent(ConversationRequest request, ConversationResponse deltaResponse) {
        super(request, deltaResponse);
    }
}
