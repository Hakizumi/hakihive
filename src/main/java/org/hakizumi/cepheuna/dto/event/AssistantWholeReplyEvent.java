package org.hakizumi.cepheuna.dto.event;

import org.hakizumi.cepheuna.dto.ConversationRequest;
import org.hakizumi.cepheuna.dto.ConversationResponse;
import org.hakizumi.cepheuna.service.LLMService;

/**
 * Specialized event dto class of {@link AssistantReplyEvent}.
 * Calls when the {@link LLMService#nonStreaming(ConversationRequest)} produced an assistants' whole reply message,
 * or the end of {@link LLMService#streaming(ConversationRequest)}
 *
 * @since 1.4.0
 * @author Hakizumi
 *
 * @see AssistantReplyEvent
 */
public class AssistantWholeReplyEvent extends AssistantReplyEvent {
    public AssistantWholeReplyEvent(ConversationRequest request, ConversationResponse wholeMessageResponse) {
        super(request, wholeMessageResponse);
    }
}
