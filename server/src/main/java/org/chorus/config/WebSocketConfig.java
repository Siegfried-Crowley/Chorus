package org.chorus.config;

import org.chorus.gateway.GatewayWebSocketHandler;
import org.chorus.voice.VoiceAudioHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final GatewayWebSocketHandler gatewayHandler;
    private final VoiceAudioHandler voiceAudioHandler;

    public WebSocketConfig(GatewayWebSocketHandler gatewayHandler,
                           VoiceAudioHandler voiceAudioHandler) {
        this.gatewayHandler = gatewayHandler;
        this.voiceAudioHandler = voiceAudioHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gatewayHandler, "/ws")
                .setAllowedOrigins("*");
        registry.addHandler(voiceAudioHandler, "/ws/voice")
                .setAllowedOrigins("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(65536);
        container.setMaxBinaryMessageBufferSize(65536);
        // 语音会话会长时间静音挂机(如只听不说话),默认 60s 会把它们杀掉;提高到 300s,
        // 配合前端 25s 心跳(控制帧 ping)即可维持
        container.setMaxSessionIdleTimeout(300000L);
        return container;
    }
}
