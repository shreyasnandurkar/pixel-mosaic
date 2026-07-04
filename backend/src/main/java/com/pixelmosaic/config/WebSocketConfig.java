package com.pixelmosaic.config;

import com.pixelmosaic.ws.MosaicWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers the mosaic WebSocket endpoint and raises the container's binary message buffer
 * so a full 10&nbsp;MB image arrives as a single message. Text frames stay small (only JSON
 * control messages flow that way).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final int MAX_TEXT_MESSAGE_BYTES = 8192;
    private static final int MAX_BINARY_MESSAGE_BYTES = 10 * 1024 * 1024 + 1024;

    private final MosaicWebSocketHandler handler;
    private final String allowedOrigins;

    public WebSocketConfig(MosaicWebSocketHandler handler,
                           @Value("${pixelmosaic.allowed-origins}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/mosaic")
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_BINARY_MESSAGE_BYTES);
        return container;
    }
}
