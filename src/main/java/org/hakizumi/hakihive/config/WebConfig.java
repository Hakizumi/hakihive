package org.hakizumi.hakihive.config;

import org.hakizumi.hakihive.controller.ServerWebSocketHandler;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class WebConfig implements WebSocketConfigurer, WebMvcConfigurer {
    private final ServerWebSocketHandler serverWebSocketHandler;

    public WebConfig(ServerWebSocketHandler serverWebSocketHandler) {
        this.serverWebSocketHandler = serverWebSocketHandler;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/backend/chat/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(serverWebSocketHandler, "/backend/websocket/chat")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        registry.addRedirectViewController("/index.html", "/chat");
    }
}
