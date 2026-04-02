package org.hakizumi.hakihive.service;

import org.hakizumi.hakihive.dto.ConversationResponse;
import org.hakizumi.hakihive.dto.UserAudioRequest;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Output callback abstraction used by the server pipeline to push events to the browser.
 *
 * @since 1.7.0
 * @author Hakizumi
 */
public interface OutstreamService {
    OutstreamService NOOP_OUTSTREAM = new OutstreamService() {
        @Override
        public void onUserPartialText(@NonNull UserAudioRequest request) {
        }

        @Override
        public void onUserFinalText(@NonNull UserAudioRequest request) {
        }

        @Override
        public void onAssistantEvent(@NotNull ServerSentEvent<@NotNull ConversationResponse> event) {
        }
    };

    /**
     * User STT partial text update.
     *
     * @since 1.7.0
     */
    void onUserPartialText(@NonNull UserAudioRequest request);

    /**
     * User STT final text update.
     *
     * @since 1.7.0
     */
    void onUserFinalText(@NonNull UserAudioRequest request);

    /**
     * Assistant token/status event.
     *
     * @since 1.7.0
     */
    void onAssistantEvent(@NotNull ServerSentEvent<@NotNull ConversationResponse> event);

    /**
     * Called when a session is connected.
     *
     * @since 1.7.0
     */
    default void onConnected(@NonNull String cid) {
        // no-op
    }

    /**
     * Called when the current turn is stopped.
     *
     * @since 1.7.0
     */
    default void onStopped(@NonNull String cid) {
        // no-op
    }

    /**
     * Called as a reply for ping.
     *
     * @since 1.7.0
     */
    default void onPong(@NonNull String cid) {
        // no-op
    }

    /**
     * Called on server-side errors.
     *
     * @since 1.7.0
     */
    default void onError(@NonNull String cid, @NonNull String message) {
        // no-op
    }

    /**
     * Stops local playback/output state immediately.
     *
     * @since 1.7.0
     */
    default void stop() {
        // no-op
    }
}
