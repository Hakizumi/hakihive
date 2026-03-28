package org.hakizumi.cepheuna.dto.event;

import org.hakizumi.cepheuna.dto.ConversationRequest;
import org.hakizumi.cepheuna.dto.ConversationResponse;
import org.hakizumi.cepheuna.service.LLMService;

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
