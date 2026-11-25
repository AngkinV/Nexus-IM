package com.nexus.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The two user-visible promises, through the real HTTP layer, security filter, JPA
 * mapping and static file handler (in-memory database, no local credentials):
 * an avatar that was set is served back at its URL, and an account can hold many
 * model channels, official and custom, without one replacing another.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexus_flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "agent.internal.token=flow-test-only-not-a-production-token",
        "agent.python-base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class AvatarAndChannelFlowTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 9, 8, 7};

    @TempDir static Path uploads;

    @DynamicPropertySource
    static void uploadsDirectory(DynamicPropertyRegistry registry) {
        registry.add("nexus.uploads.dir", uploads::toString);
    }

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @Autowired UserRepository users;
    @Autowired ModelCredentialRepository credentials;

    private User owner, stranger;

    @BeforeEach void accounts() {
        credentials.deleteAll();
        users.deleteAll();
        owner = account("owner");
        stranger = account("stranger");
    }

    private User account(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@example.invalid");
        user.setPasswordHash("not-a-real-hash");
        user.setNickname(name);
        return users.save(user);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + tokens.generateToken(user.getId(), user.getUsername()));
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    // ---------------------------------------------------------------- avatars

    @Test void anAvatarThatWasSetIsServedBackFromItsUrl() throws Exception {
        String picture = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);
        String url = body(as(owner, post("/api/users/{id}/avatar/base64", owner.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("avatar", picture))))
                .path("avatarUrl").asText();

        assertTrue(url.startsWith("/uploads/avatars/avatar_" + owner.getId() + "_") && url.endsWith(".png"), url);
        assertEquals(url, users.findById(owner.getId()).orElseThrow().getAvatarUrl(), "the row holds the URL, not the image");
        // No token: an <img> tag cannot send one.
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        assertEquals(url, body(as(owner, get("/api/users/{id}/profile", owner.getId()))).path("avatarUrl").asText());
    }

    @Test void registeredPublicAvatarUsesConfiguredStorageDirectory() throws Exception {
        Path avatar = uploads.resolve("2026/11/22/group.jpeg");
        Files.createDirectories(avatar.getParent());
        Files.write(avatar, PNG);
        String url = "/uploads/2026/11/22/group.jpeg";
        mvc.perform(get(url)).andExpect(status().isNotFound());
        owner.setAvatarUrl(url);
        users.save(owner);
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        mvc.perform(get("/uploads/2026/11/22/gone.jpeg")).andExpect(status().isNotFound());
    }

    @Test void anAvatarCannotBeSetForSomeoneElseOrWithoutSigningIn() throws Exception {
        byte[] request = json.writeValueAsBytes(Map.of("avatar", "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG)));
        mvc.perform(as(stranger, post("/api/users/{id}/avatar/base64", owner.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mvc.perform(post("/api/users/{id}/avatar/base64", owner.getId())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().is4xxClientError());
        assertNull(users.findById(owner.getId()).orElseThrow().getAvatarUrl());
    }

    @Test void aRefusedPictureSaysWhy() throws Exception {
        String svg = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg/>".getBytes());
        mvc.perform(as(owner, post("/api/users/{id}/avatar/base64", owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("avatar", svg))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.messageKey").value("error.user.file.image.only"));
    }

    // --------------------------------------------------------------- channels

    private JsonNode save(User user, Map<String, Object> channel) throws Exception {
        return body(as(user, post("/api/agent/providers")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(channel))).path("data");
    }

    private static Map<String, Object> channel(String vendor, String format, String name, String model) {
        return new java.util.HashMap<>(Map.of("vendor", vendor, "apiFormat", format, "displayName", name,
                "baseUrl", "http://127.0.0.1:9/v1", "apiKey", "key-of-" + name, "defaultModel", model, "purpose", "chat"));
    }

    @Test void oneAccountKeepsManyOfficialAndCustomChannels() throws Exception {
        List<String> ids = new ArrayList<>();
        String[][] wanted = {
                {"openai", "openai-compatible"}, {"deepseek", "openai-compatible"}, {"anthropic", "anthropic"},
                {"gemini", "gemini"}, {"openrouter", "openai-compatible"}, {"custom", "openai-compatible"},
                {"custom", "openai-compatible"}, {"custom", "anthropic"}, {"deepseek", "openai-compatible"}};
        for (int i = 0; i < wanted.length; i++) {
            ids.add(save(owner, channel(wanted[i][0], wanted[i][1], "Channel " + i, "model-" + i)).path("provider").asText());
        }
        assertEquals(List.of("openai", "deepseek", "anthropic", "gemini", "openrouter", "custom", "custom-2", "custom-3", "deepseek-2"), ids);

        JsonNode list = body(as(owner, get("/api/agent/providers").param("purpose", "chat"))).path("data");
        assertEquals(wanted.length, list.size());
        for (JsonNode row : list) {
            int i = Integer.parseInt(row.path("displayName").asText().substring("Channel ".length()));
            assertEquals(wanted[i][0], row.path("vendor").asText());
            assertEquals(wanted[i][1], row.path("apiFormat").asText());
            assertEquals("model-" + i, row.path("defaultModel").asText(), "each channel kept its own model");
            assertTrue(row.path("hasApiKey").asBoolean());
            assertFalse(row.path("keyOptional").asBoolean());
            assertEquals(i == 0, row.path("default").asBoolean(), "only the first channel became the default");
            assertFalse(row.toString().contains("key-of-"), "a key is never sent back");
        }
        assertEquals(0, body(as(stranger, get("/api/agent/providers"))).path("data").size(), "channels are private to the account");
    }

    @Test void editingByIdChangesThatChannelOnly() throws Exception {
        JsonNode first = save(owner, channel("custom", "openai-compatible", "Relay A", "a"));
        JsonNode second = save(owner, channel("custom", "openai-compatible", "Relay B", "b"));

        Map<String, Object> edit = channel("custom", "openai-compatible", "Relay A renamed", "a2");
        edit.put("id", first.path("id").asLong());
        edit.remove("apiKey");
        JsonNode saved = save(owner, edit);
        assertEquals(first.path("id").asLong(), saved.path("id").asLong());
        assertEquals("custom", saved.path("provider").asText());
        assertTrue(saved.path("hasApiKey").asBoolean(), "the stored key survives an edit without one");

        JsonNode list = body(as(owner, get("/api/agent/providers"))).path("data");
        assertEquals(2, list.size());
        for (JsonNode row : list) {
            boolean edited = row.path("id").asLong() == first.path("id").asLong();
            assertEquals(edited ? "Relay A renamed" : "Relay B", row.path("displayName").asText());
            assertEquals(edited ? "a2" : "b", row.path("defaultModel").asText());
        }

        edit.put("displayName", "Hijacked");
        mvc.perform(as(stranger, post("/api/agent/providers")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(edit))).andExpect(status().isBadRequest());
        mvc.perform(as(owner, post("/api/agent/providers/{id}/default", second.path("id").asLong()))).andExpect(status().isOk());
        list = body(as(owner, get("/api/agent/providers"))).path("data");
        for (JsonNode row : list) {
            assertEquals(row.path("id").asLong() == second.path("id").asLong(), row.path("default").asBoolean());
            assertNotEquals("Hijacked", row.path("displayName").asText());
        }
    }

    @Test void aRequestFromAClientThatPredatesVendorsStillWorks() throws Exception {
        Map<String, Object> legacy = new java.util.HashMap<>(Map.of("provider", "openai", "baseUrl", "http://127.0.0.1:9/v1",
                "apiKey", "k", "defaultModel", "m1"));
        JsonNode created = save(owner, legacy);
        legacy.put("defaultModel", "m2");
        legacy.remove("apiKey");
        JsonNode updated = save(owner, legacy);
        assertEquals(created.path("id").asLong(), updated.path("id").asLong());
        assertEquals("m2", updated.path("defaultModel").asText());
        assertEquals("openai", updated.path("vendor").asText());
        assertEquals("openai-compatible", updated.path("apiFormat").asText());
        assertEquals(1, credentials.count());
    }
}
