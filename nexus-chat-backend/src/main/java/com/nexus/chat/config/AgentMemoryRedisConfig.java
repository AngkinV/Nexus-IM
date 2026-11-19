package com.nexus.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A second Redis connection, pinned to the database index the Python agent
 * writes its short-term conversation memory into.
 *
 * The app's main connection stays on index 0 — cache, rate limiting and the
 * pub/sub relay all live there. Short-term memory is the one thing two services
 * share, and the Python side has always used index 2. Reading it through the
 * main template meant the Java API never saw a single message: history came
 * back empty, and clearing a conversation left the model's memory untouched.
 *
 * Separate index, separate bean, so neither service has to move.
 */
@Configuration
public class AgentMemoryRedisConfig {

    /** Where the Python agent keeps short-term memory. Keep in sync with REDIS_URL in nexus-agent-backend/.env. */
    private static final int AGENT_MEMORY_DB_INDEX = 2;

    @Bean
    public LettuceConnectionFactory agentMemoryConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.username:}") String username,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.ssl:false}") boolean ssl) {

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(AGENT_MEMORY_DB_INDEX);
        if (username != null && !username.isBlank()) config.setUsername(username);
        if (password != null && !password.isBlank()) config.setPassword(RedisPassword.of(password));

        LettuceClientConfiguration clientConfig = ssl
                ? LettuceClientConfiguration.builder().useSsl().build()
                : LettuceClientConfiguration.builder().build();

        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean
    public StringRedisTemplate agentMemoryRedisTemplate(LettuceConnectionFactory agentMemoryConnectionFactory) {
        return new StringRedisTemplate(agentMemoryConnectionFactory);
    }
}
