package org.hakizumi.hakihive.service;

import org.hakizumi.hakihive.controller.SessionOutstreamService;
import org.hakizumi.hakihive.dto.ConversationResponse;
import org.hakizumi.hakihive.dto.UserAudioRequest;
import org.jspecify.annotations.NonNull;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Output callback abstraction used by the server pipeline to push events to the browser.
 * <p>
 * Implementations:
 * {@link SessionOutstreamService} - {@link OutstreamService} for websocket session.
 *
 * @since 1.7.0
 * @author Hakizumi
 *
 * @see SessionOutstreamService
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
        public void onAssistantEvent(@NonNull ServerSentEvent<@NonNull ConversationResponse> event) {
        }
    };

    /**
     * User STT partial text update.
     * <p>
     * Reply JSON like:
     * <blockquote>
     * <pre>
     *  {"type":"stt_partial","cid":"conversation-id","text":"stt_partial_text"}
     * </pre>
     * </blockquote>
     *
     * @since 1.7.0
     */
    void onUserPartialText(@NonNull UserAudioRequest request);

    /**
     * User STT final text update.
     * <p>
     * Reply JSON like:
     * <blockquote>
     * <pre>
     *  {"type":"stt_final","cid":"conversation-id","text":"stt_final_whole_text"}
     * </pre>
     * </blockquote>
     *
     * @since 1.7.0
     */
    void onUserFinalText(@NonNull UserAudioRequest request);

    /**
     * Assistant token/status event.
     <p>
     * Reply JSON like:
     * <blockquote>
     * <pre>
     *  {"type":"assistant_text","cid":"conversation-id","text":"assistant_reply_dela"}
     * </pre>
     * </blockquote>
     * Or is status flag:
     * <blockquote>
     * <pre>
     *  {"type":"assistant_start","cid":"conversation-id"}
     *  {"type":"assistant_finish","cid":"conversation-id"}
     * </pre>
     * </blockquote>
     * Or when error occurred
     * <blockquote>
     * <pre>
     *  {"type":"error","cid":"conversation-id","text":"error_message"}
     * </pre>
     * </blockquote>
     *
     * @since 1.7.0
     */
    void onAssistantEvent(@NonNull ServerSentEvent<@NonNull ConversationResponse> event);

    /**
     * Called when a session is connected.
     * <p>
     * Reply JSON like:
     * <blockquote>
     * <pre>
     *  {"type":"connected","cid":"conversation-id"}
     * </pre>
     * </blockquote>
     *
     * @since 1.7.0
     */
    default void onConnected(@NonNull String cid) {
        // no-op
    }

    /**
     * Called when the current turn is stopped.
     * <p>
     * Reply JSON like:
     * <blockquote>
     * <pre>
     *  {"type":"stopped","cid":"conversation-id"}
     * </pre>
     * </blockquote>
     *
     * @since 1.7.0
     */
    default void onStopped(@NonNull String cid) {
        // no-op
    }

    /**
     * Called as a reply for ping.
     * <p>
     * Reply JSON like:
     * <blockquote>
     * <pre>
     *  {"type":"pong","cid":"conversation-id"}
     * </pre>
     * </blockquote>
     *
     * @since 1.7.0
     */
    default void onPong(@NonNull String cid) {
        // no-op
    }

    /**
     * Called on server-side errors.
     * <p>
     * Reply JSON like:
     * <blockquote>
     * <pre>
     *  {"type":"error","cid":"conversation-id","text":"error_message"}
     * </pre>
     * </blockquote>
     *
     * @since 1.7.0
     */
    default void onError(@NonNull String cid, @NonNull String message) {
        // no-op
    }

    /**
     * Stops local playback/output state immediately.
     * <p>
     * Reply JSON like:
     * <blockquote>
     * <pre>
     *  {"type":"client_stop","cid":"conversation-id"}
     * </pre>
     * </blockquote>
     *
     * @since 1.7.0
     */
    default void stop() {
        // no-op
    }
}
