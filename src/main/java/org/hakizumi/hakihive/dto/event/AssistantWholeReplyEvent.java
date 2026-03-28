package org.hakizumi.hakihive.dto.event;

import org.hakizumi.hakihive.dto.ConversationRequest;
import org.hakizumi.hakihive.dto.ConversationResponse;
import org.hakizumi.hakihive.service.LLMService;

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
