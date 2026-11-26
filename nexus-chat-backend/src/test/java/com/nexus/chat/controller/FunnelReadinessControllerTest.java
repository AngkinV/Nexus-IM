package com.nexus.chat.controller;

import com.nexus.chat.config.AllowedOrigins;
import com.nexus.chat.config.FunnelSecretGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FunnelReadinessControllerTest {
    private static final String ORIGIN = "https://nexus-im-web-2026-fwohlcnoevd.qoder.zone";

    private MockMvc mvc(String address, String exclusive) {
        MockEnvironment environment = new MockEnvironment().withProperty("server.address", address)
                .withProperty("cors.exclusive-origins", exclusive)
                .withProperty("funnel.public-origin", ORIGIN);
        for (String key : new String[]{"jwt.secret", "agent.internal.token", "agent.internal.signing-secret", "companion.api-key.secret"}) {
            environment.withProperty(key, "fixture-only-not-for-use-outside-tests-" + key);
        }
        return MockMvcBuilders.standaloneSetup(new FunnelReadinessController(new AllowedOrigins("", exclusive), environment,
                new FunnelSecretGuard(environment))).build();
    }

    @Test
    void readyOnlyWithLoopbackBindingAndTheExclusiveOriginPolicy() throws Exception {
        mvc("127.0.0.1", ORIGIN).perform(get("/internal/funnel/readiness").with(request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        })).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.policyVersion").value("nexus-funnel-v2"))
                .andExpect(jsonPath("$.origins[0]").value(ORIGIN));
    }

    @Test
    void refusesWildcardBindingAndMissingOrBroadOrigins() throws Exception {
        for (String[] configuration : new String[][]{{"0.0.0.0", ORIGIN}, {"127.0.0.1", ""}, {"127.0.0.1", "https://*.qoder.zone"}}) {
            mvc(configuration[0], configuration[1]).perform(get("/internal/funnel/readiness").with(request -> {
                request.setRemoteAddr("127.0.0.1");
                return request;
            })).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.ready").value(false));
        }
    }

    @Test
    void loopbackAndElectronClientsCanCoexistWithoutAllowingAnotherPublicOrigin() throws Exception {
        String origins = ORIGIN + ",http://localhost:*,http://127.0.0.1:*,app://*";
        mvc("127.0.0.1", origins).perform(get("/internal/funnel/readiness").with(request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        })).andExpect(status().isOk()).andExpect(jsonPath("$.publicOrigin").value(ORIGIN))
                .andExpect(jsonPath("$.origins.length()").value(4));
        mvc("127.0.0.1", origins + ",https://evil.example").perform(get("/internal/funnel/readiness").with(request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        })).andExpect(status().isServiceUnavailable());
    }

    @Test
    void privateNetworkClientsCannotReadTheProbe() throws Exception {
        mvc("127.0.0.1", ORIGIN).perform(get("/internal/funnel/readiness").with(request -> {
            request.setRemoteAddr("192.168.1.10");
            return request;
        })).andExpect(status().isForbidden()).andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.policyVersion").doesNotExist());
    }
}
