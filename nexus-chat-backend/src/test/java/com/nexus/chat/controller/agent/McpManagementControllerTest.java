package com.nexus.chat.controller.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.McpManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** BFF contract fixtures only; not a real JWT/database/Python end-to-end test. */
class McpManagementControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpManagementService service = mock(McpManagementService.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpManagementController(service, jwt, json))
            .setControllerAdvice(new McpManagementErrors(json)).build();

    private void authenticate() {
        when(jwt.validateToken("fixture-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("fixture-token")).thenReturn(41L);
    }

    @Test void missingAuthenticationReadsAndWritesNothing() throws Exception {
        mvc.perform(get("/api/agent/mcp/servers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/mcp/servers").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void malformedCredentialJsonIsNeverEchoed() throws Exception {
        authenticate();
        String secret = "fixture-only-sensitive-input";
        String output = mvc.perform(post("/api/agent/mcp/servers").header("Authorization", "Bearer fixture-token")
                .contentType("application/json").content("{\"credential\":{\"secret\":" + secret + "}}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(output.contains(secret));
        verifyNoInteractions(service);
    }

    @Test void probeRequiresOnlyTheCurrentExpectedVersionAndPassesAuthenticatedOwner() throws Exception {
        authenticate();
        when(service.probe(eq(41L), anyString(), eq("server_id"), eq(7L), eq("test")))
                .thenReturn(json.createObjectNode().put("ok", false));
        mvc.perform(post("/api/agent/mcp/servers/server_id/test").header("Authorization", "Bearer fixture-token")
                .contentType("application/json").content("{\"expectedConfigVersion\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
        verify(service).probe(eq(41L), anyString(), eq("server_id"), eq(7L), eq("test"));
    }

    @Test void identityFieldsCannotSneakIntoVersionOnlyActions() throws Exception {
        authenticate();
        mvc.perform(delete("/api/agent/mcp/servers/server_id").header("Authorization", "Bearer fixture-token")
                .contentType("application/json").content("{\"expectedConfigVersion\":1,\"ownerUserId\":42}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
