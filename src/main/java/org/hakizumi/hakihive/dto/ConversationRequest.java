package org.hakizumi.hakihive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Conversation controller request dto.
 *
 * @since 1.0.0
 * @author Hakizumi
 *
 * @see org.hakizumi.hakihive.controller.ConversationController
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@AllArgsConstructor
public class ConversationRequest {
    /** Conversation's id */
    private String cid;

    private String message;
}
