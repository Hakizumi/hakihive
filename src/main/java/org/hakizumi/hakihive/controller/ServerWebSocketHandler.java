package org.hakizumi.hakihive.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.hakizumi.hakihive.dto.FrontendWsCommand;
import org.hakizumi.hakihive.service.OutstreamService;
import org.hakizumi.hakihive.service.ServerService;
import org.hakizumi.hakihive.utils.StringUtils;
import org.hakizumi.hakihive.utils.UriParseUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hakizumi.hakihive.service.OutstreamService.NOOP_OUTSTREAM;

/**
 * Browser websocket endpoint.
 * <p>
 * Text control messages remain JSON. Assistant audio chunks are sent as binary websocket frames
 * whose payload layout is: {@code UTF-8 JSON header + '\n' + raw audio bytes}.
 *
 * @since 1.7.0
 * @author Hakizumi
 */
@Component
@Slf4j
public class ServerWebSocketHandler extends AbstractWebSocketHandler {
    private final ServerService serverService;
    private final ObjectMapper objectMapper;
    private final Map<String, SessionOutstreamService> outstreams = new ConcurrentHashMap<>();

    public ServerWebSocketHandler(ServerService serverService, ObjectMapper objectMapper) {
        this.serverService = serverService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        String cid = parseCidFromSession(session);
        if (cid == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        SessionOutstreamService outstream = new SessionOutstreamService(session, objectMapper, cid);
        outstreams.put(session.getId(), outstream);
        outstream.onConnected(cid);
    }

    /**
     * Handle text command message from frontend.
     * <p>
     * Supported commands:
     * <blockquote>
     * <pre>
     * {"type":"chat","cid":"cid","text":"user message"} -- On user input
     * {"type":"stop","cid":"cid","text":"ignored"} -- Stop assistant reply
     * {"type":"ping","cid":"cid","text":"ignored"} -- Ping and pong
     * </pre>
     * </blockquote>
     *
     * @param session Websocket session entity
     * @param message Text message from frontend
     *
     * @since 1.7.0
     *
     * @see FrontendWsCommand
     * @see ServerService#onUserText(String, String, OutstreamService)
     */
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String cid = parseCidFromSession(session);
        if (cid == null) {
            return;
        }

        SessionOutstreamService outstream = getOutstream(session, cid);
        String payload = message.getPayload();
        FrontendWsCommand command = tryParseCommand(payload);

        if (command == null) {
            log.info("Could not parse command from frontend: {}", payload);
            return;
        }

        cid = parseCidFromCommand(command,cid);

        switch (command.normalizedType()) {
            case "chat" -> serverService.onUserText(cid, StringUtils.nullToEmpty(command.text()), outstream);
            case "stop" -> serverService.stopConversation(cid, outstream);
            case "ping" -> outstream.onPong(cid);
            default -> log.info("Unknown command from frontend: {}",command.normalizedType());
        }
    }

    /**
     * Handle text message from frontend ( User voice audio ).
     *
     * @param session Websocket session entity
     * @param message Text message from frontend
     *
     * @since 1.7.0
     *
     * @see ServerService#onUserVoice(String, ByteBuffer, OutstreamService)
     */
    @Override
    protected void handleBinaryMessage(@NonNull WebSocketSession session, @NonNull BinaryMessage message) {
        String cid = parseCidFromSession(session);
        if (cid == null) {
            return;
        }

        SessionOutstreamService outstream = getOutstream(session, cid);
        serverService.onUserVoice(cid, message.getPayload(), outstream);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) throws Exception {
        String cid = parseCidFromURI(session.getUri());
        SessionOutstreamService outstream = outstreams.remove(session.getId());
        if (outstream != null) {
            outstream.markClosed();
        }
        if (cid != null) {
            serverService.stopConversation(cid, NOOP_OUTSTREAM);
        }
        super.afterConnectionClosed(session, status);
    }

    /**
     * Try parse frontend command from payload
     */
    private @Nullable FrontendWsCommand tryParseCommand(String payload) {
        try {
            FrontendWsCommand command = objectMapper.readValue(payload, FrontendWsCommand.class);
            if (command.type() == null || command.type().isBlank()) {
                return null;
            }
            return command;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private @NonNull SessionOutstreamService getOutstream(@NonNull WebSocketSession session, String cid) {
        return outstreams.computeIfAbsent(session.getId(), (id) -> new SessionOutstreamService(session, objectMapper, cid));
    }

    private @Nullable String parseCidFromSession(@NonNull WebSocketSession session) {
        if (!session.isOpen()) {
            return null;
        }
        return parseCidFromURI(session.getUri());
    }

    private @Nullable String parseCidFromURI(@Nullable URI uri) {
        if (uri == null) {
            return null;
        }
        return UriParseUtil.parseUriQuery(uri).get("cid");
    }

    private String parseCidFromCommand(@NonNull FrontendWsCommand command, String fallbackCid) {
        return command.cid() == null || command.cid().isBlank() ? fallbackCid : command.cid();
    }
}
