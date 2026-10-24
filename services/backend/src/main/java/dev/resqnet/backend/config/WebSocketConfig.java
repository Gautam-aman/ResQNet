package dev.resqnet.backend.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import dev.resqnet.backend.security.WebSocketAuthInterceptor;
import dev.resqnet.backend.web.EventStreamHandler;

/**
 * Registers the dashboard event stream.
 *
 * <p>Origins are restricted to the configured allow-list.
 * The WebSocket upgrade is authenticated by WebSocketAuthInterceptor.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EventStreamHandler handler;
    private final WebSocketAuthInterceptor authInterceptor;
    private final List<String> allowedOrigins;

    public WebSocketConfig(
            EventStreamHandler handler,
            WebSocketAuthInterceptor authInterceptor,
            @Value("${resqnet.security.allowed-origins:http://localhost:5173}")
            List<String> allowedOrigins) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler();
        handshakeHandler.setSupportedProtocols(WebSocketAuthInterceptor.PROTOCOL);

        registry.addHandler(handler, "/ws/incidents")
                .addInterceptors(authInterceptor)
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOrigins(allowedOrigins.toArray(new String[0]));
    }
}