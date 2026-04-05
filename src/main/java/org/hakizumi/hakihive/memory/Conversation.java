package org.hakizumi.hakihive.memory;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The conversation entity.
 *
 * @since 1.3.0
 * @author Hakizumi
 */
@RequiredArgsConstructor
public class Conversation {
    /**
     * Conversation identifier string
     */
    @Getter
    private final @NonNull String cid;

    /**
     * Memory messages list
     */
    @Getter
    private final List<Message> messages = new ArrayList<>();

    public @NonNull ConversationState state = ConversationState.IDLE;

    /**
     * Last user's voice word token
     */
    public @NonNull String lastPartial = "";

    /**
     * Current segment index
     */
    public long segmentIndex = 0;

    /**
     * Silent ( No voice ) time frames
     */
    public long silenceFrames = 0;

    /**
     * User saying frames
     */
    public long speechFrames = 0;

    /**
     * Consecutive speaking frames for barge-in / debounce
     */
    public long speakingStreakFrames = 0;

    /**
     * Sample rate declared by browser for the current audio stream
     */
    public int clientSampleRate = 16000;

    /**
     * Estimated ambient noise floor for adaptive VAD
     */
    public float vadNoiseFloor = 0.0f;

    /**
     * Current utterance id
     */
    public volatile @Nullable String currentUtteranceId = "";

    /**
     * Current assistant subscription flux
     */
    public volatile @Nullable Disposable currentAssistantSubscription = null;

    /**
     * Assistant is replying or thinking
     */
    private final AtomicBoolean assistantActive = new AtomicBoolean(false);

    /**
     * Turns
     */
    public final AtomicLong turnCounter = new AtomicLong(0);

    public boolean isAssistantActive() {
        return assistantActive.get();
    }

    public void setAssistantActive(boolean active) {
        assistantActive.set(active);
    }

    /**
     * Push a message to the conversation memory's back.
     *
     * @param message The message to push back
     * @return Added memory messages list
     *
     * @throws NullPointerException If {@code message} is null.
     *
     * @since 1.3.0
     */
    public List<Message> pushMessage(@NonNull Message message) {
        messages.add(message);
        return messages;
    }

    /**
     * Set the {@code system prompt} of the conversation.
     * If the conversation has not set system prompt,
     * this function will push the {@code systemMessage} to the front of the conversation memory list.
     *
     * @param systemMessage The system message to cover or set.
     *
     * @throws NullPointerException If {@code systemMessage} is null.
     *
     * @since 1.3.0
     */
    public void coverSystemPrompt(@NonNull SystemMessage systemMessage) {
        if (!messages.isEmpty() && messages.getFirst().getMessageType() == MessageType.SYSTEM) {
            messages.set(0, systemMessage);
        }
        else {
            messages.addFirst(systemMessage);
        }
    }

    /**
     * Easy usage for {@link Conversation#coverSystemPrompt(SystemMessage)}.
     *
     * @param message The system message to cover or set.
     */
    public void coverSystemPrompt(@NonNull String message) {
        coverSystemPrompt(new SystemMessage(message));
    }

    /**
     * Cancel assistant response & audio for barge-in.
     */
    public void cancelAssistant() {
        Disposable subscription = currentAssistantSubscription;
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }

        currentAssistantSubscription = null;
        assistantActive.set(false);
        currentUtteranceId = null;
    }

    /**
     * Reset runtime fields related to one streaming speech segment.
     */
    public void resetSpeechRuntime() {
        lastPartial = "";
        segmentIndex = 0;
        silenceFrames = 0;
        speechFrames = 0;
        speakingStreakFrames = 0;
        state = ConversationState.IDLE;
    }

    /**
     * Get conversation messages without system prompt.
     *
     * @return Result memory list.
     *
     * @since 1.3.0
     */
    public @NonNull List<Message> getMessagesWithoutSystem() {
        return messages.stream()
                .filter((msg) -> msg.getMessageType() != MessageType.SYSTEM)
                .toList();
    }
}
