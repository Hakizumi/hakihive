package org.hakizumi.hakihive.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hakizumi.hakihive.dto.ConversationResponse;
import org.hakizumi.hakihive.dto.UserAudioRequest;
import org.hakizumi.hakihive.service.OutstreamService;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
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
     *
     * @since 1.7.0
     */
    @Override
    public void onUserPartialText(@NonNull UserAudioRequest request) {
        sendJson(Map.of(
                "type", "stt_partial",
                "cid", request.cid(),
                "text", request.text()
        ));
    }

    /**
     * Assistant token/status event.
     *
     * @since 1.7.0
     */
    @Override
    public void onUserFinalText(@NonNull UserAudioRequest request) {
        sendJson(Map.of(
                "type", "stt_final",
                "cid", request.cid(),
                "text", request.text()
        ));
    }

    /**
     * Called when a session is connected.
     *
     * @since 1.7.0
     */
    @Override
    public void onAssistantEvent(@NotNull ServerSentEvent<@NotNull ConversationResponse> event) {
        String eventName = event.event();

        if (event.data() == null) return;

        String data = event.data().getMessage();

        if ("token".equals(eventName)) {
            sendJson(Map.of(
                    "type", "assistant_text",
                    "cid", cid,
                    "text", data == null ? "" : data
            ));
            return;
        }

        if ("status".equals(eventName) && data != null) {
            if (data.contains("\"state\":\"start\"")) {
                sendJson(Map.of("type", "assistant_start", "cid", cid));
                return;
            }
            if (data.contains("\"state\":\"done\"")) {
                sendJson(Map.of("type", "assistant_finish", "cid", cid));
                return;
            }
            if (data.contains("\"error\"")) {
                sendJson(Map.of("type", "error", "cid", cid, "message", data));
            }
        }
    }

    /**
     * Called when the current turn is stopped.
     *
     * @since 1.7.0
     */
    @Override
    public void onConnected(@NonNull String cid) {
        sendJson(Map.of("type", "connected", "cid", cid));
    }

    /**
     * Called when the current turn is stopped.
     *
     * @since 1.7.0
     */
    @Override
    public void onStopped(@NonNull String cid) {
        sendJson(Map.of("type", "stopped", "cid", cid));
    }

    /**
     * Called as a reply for ping.
     *
     * @since 1.7.0
     */
    @Override
    public void onPong(@NonNull String cid) {
        sendJson(Map.of("type", "pong", "cid", cid));
    }

    /**
     * Called on server-side errors.
     *
     * @since 1.7.0
     */
    @Override
    public void onError(@NonNull String cid, @NonNull String message) {
        sendJson(Map.of("type", "error", "cid", cid, "message", message));
    }

    /**
     * Stops local playback/output state immediately.
     *
     * @since 1.7.0
     */
    @Override
    public void stop() {
        sendJson(Map.of("type", "client_stop", "cid", cid));
    }

    private void sendJson(Map<String, Object> payload) {
        try {
            sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize websocket payload", e);
        }
    }

    private void sendMessage(org.springframework.web.socket.@NotNull WebSocketMessage<?> message) {
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