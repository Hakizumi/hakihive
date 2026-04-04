package org.hakizumi.hakihive.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hakizumi.hakihive.dto.ConversationResponse;
import org.hakizumi.hakihive.dto.OutstreamResponse;
import org.hakizumi.hakihive.dto.UserAudioRequest;
import org.hakizumi.hakihive.service.OutstreamService;
import org.hakizumi.hakihive.utils.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Websocket outstream service.
 *
 * @see OutstreamService
 *
 * @since 1.7.0
 * @author Hakizumi
 */
public final class SessionOutstreamService implements OutstreamService {
    private final WebSocketSession session;
    private final ObjectMapper objectMapper;
    private final String cid;
    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    SessionOutstreamService(WebSocketSession session, ObjectMapper objectMapper, String cid) {
        this.session = session;
        this.objectMapper = objectMapper;
        this.cid = cid;
    }

    void markClosed() {
        closed.set(true);
    }

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
    @Override
    public void onUserPartialText(@NonNull UserAudioRequest request) {
        sendResponse(
                new OutstreamResponse("stt_partial",request.cid(),request.text())
        );
    }

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
    @Override
    public void onUserFinalText(@NonNull UserAudioRequest request) {
        sendResponse(
                new OutstreamResponse("stt_final",request.cid(),request.text())
        );
    }

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
    @Override
    public void onAssistantEvent(@NonNull ServerSentEvent<@NonNull ConversationResponse> event) {
        String eventName = event.event();

        if (event.data() == null) return;

        String data = event.data().getMessage();

        if ("delta".equals(eventName)) {
            sendResponse(
                    new OutstreamResponse("assistant_text",cid,StringUtils.nullToEmpty(data))
            );
        }
        else if ("status".equals(eventName) && data != null) {
            if (data.contains("start")) {
                sendResponse(
                        new OutstreamResponse("assistant_start",cid)
                );
            }
            else if (data.contains("done")) {
                sendResponse(
                        new OutstreamResponse("assistant_finish",cid)
                );
            }
        }
        else if ("error".equals(eventName) && data != null) {
            sendResponse(new OutstreamResponse(
                    "error", cid, data)
            );
        }
    }

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
    @Override
    public void onConnected(@NonNull String cid) {
        sendResponse(
                new OutstreamResponse("connected", cid)
        );
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
    @Override
    public void onStopped(@NonNull String cid) {
        sendResponse(
                new OutstreamResponse("stopped",cid)
        );
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
    @Override
    public void onPong(@NonNull String cid) {
        sendResponse(
                new OutstreamResponse("pong", cid)
        );
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
    @Override
    public void onError(@NonNull String cid, @NonNull String message) {
        sendResponse(
                new OutstreamResponse( "error", cid, message)
        );
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
    @Override
    public void stop() {
        sendResponse(
                new OutstreamResponse("client_stop",cid)
        );
    }

    private void sendResponse(OutstreamResponse payload) {
        try {
            sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize websocket payload", e);
        }
    }

    private void sendMessage(@NonNull WebSocketMessage<?> message) {
        if (closed.get() || !session.isOpen()) {
            return;
        }
        synchronized (sendLock) {
            if (closed.get() || !session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(message);
            } catch (IOException e) {
                closed.set(true);
            }
        }
    }
}