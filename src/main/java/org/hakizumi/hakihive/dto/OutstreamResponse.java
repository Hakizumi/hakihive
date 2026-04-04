package org.hakizumi.hakihive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hakizumi.hakihive.service.BaseLLMService;
import org.hakizumi.hakihive.service.LLMService;
import org.hakizumi.hakihive.service.OutstreamService;

/**
 * Outstream SSE response dto entity for {@link OutstreamService}.
 * <p>
 * Serialize like:
 * <blockquote>
 * <pre>
 * {"type":"xx","cid":"conversation-id"}
 * {"type":"xx","cid":"conversation-id","text":"message"}
 * </pre>
 * </blockquote>
 * <p>
 * Different from {@link ConversationResponse},
 * {@link OutstreamResponse} is as a response for outstream client such as {@code frontend browser},
 * {@link ConversationResponse} is the wrapped response for {@link LLMService} and {@link BaseLLMService},not exposed to the outside world
 *
 * @author Hakizumi
 * @see OutstreamService
 * @since 1.7.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutstreamResponse(String type, String cid, String text) {
    public OutstreamResponse(String type, String cid) {
        this(type, cid, null);
    }
}
