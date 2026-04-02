package org.hakizumi.hakihive.memory;

/**
 * Conversation state enumeration.
 *
 * @see Conversation
 *
 * @since 1.0.0
 * @author Hakizumi
 */
public enum ConversationState {
    /**
     * Both user and assistant are not active
     */
    IDLE,

    /**
     * User is speaking
     */
    LISTENING,

    /**
     * Assistant is thinking ( Nothing output )
     */
    THINKING,

    /**
     * Assistant is replying ( Both voice and conversation )
     */
    REPLYING
}
