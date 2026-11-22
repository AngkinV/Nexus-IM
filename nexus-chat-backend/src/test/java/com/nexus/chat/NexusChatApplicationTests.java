package com.nexus.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// A startup gate must never depend on local credentials or touch the running database.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexus_context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "agent.internal.token=settings-context-test-only-not-a-production-token",
        "agent.python-base-url=http://127.0.0.1:1"
})
class NexusChatApplicationTests {
    @org.springframework.boot.test.context.TestConfiguration
    static class IsolatedRedis {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory isolatedMainRedis() {
            return org.mockito.Mockito.mock(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class);
        }
    }
    @org.springframework.boot.test.mock.mockito.MockBean(name = "agentMemoryConnectionFactory")
    org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory agentMemoryConnections;
    @org.springframework.boot.test.mock.mockito.MockBean
    org.springframework.data.redis.core.StringRedisTemplate redisStrings;
    @org.springframework.boot.test.mock.mockito.MockBean
    org.springframework.data.redis.listener.RedisMessageListenerContainer redisListeners;

    @Test
    void contextLoads() {
    }
}
