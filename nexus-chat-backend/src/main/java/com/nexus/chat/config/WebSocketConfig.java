package com.nexus.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private WebSocketAuthChannelInterceptor webSocketAuthChannelInterceptor;

    @Autowired
    private MessageValidationInterceptor messageValidationInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        log.info("配置 WebSocket 消息代理: /topic, /queue");
        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000})  // 服务端心跳: 10秒发送, 10秒期望接收
                .setTaskScheduler(heartbeatScheduler());
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler heartbeatScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        log.info("注册 WebSocket STOMP 端点: /ws");
        // Register with SockJS, allowing XHR fallback
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://chat.angkin.cn",
                        "https://*.angkin.cn",
                        "app://*"
                )
                .withSockJS()
                .setSessionCookieNeeded(false)
                .setStreamBytesLimit(512 * 1024)
                .setHttpMessageCacheSize(1000)
                .setDisconnectDelay(30 * 1000);

        // Also register native WebSocket endpoint (no SockJS)
        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://chat.angkin.cn",
                        "https://*.angkin.cn",
                        "app://*"
                );
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        log.debug("配置 WebSocket 入站通道拦截器");
        registration.interceptors(webSocketAuthChannelInterceptor, messageValidationInterceptor);
    }

}
