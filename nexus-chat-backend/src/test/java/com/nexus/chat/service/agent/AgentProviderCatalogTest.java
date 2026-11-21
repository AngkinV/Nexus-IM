package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.agent.AgentProviderDtos;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.service.CompanionCryptoService;
import com.nexus.chat.util.ModelEndpointGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentProviderCatalogTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelCredentialRepository repo = mock(ModelCredentialRepository.class);
    private final CompanionCryptoService crypto = mock(CompanionCryptoService.class);
    private final PythonModelGateway gateway = mock(PythonModelGateway.class);
    private final AgentProviderService service = new AgentProviderService(repo, crypto, mapper, mock(ModelEndpointGuard.class), gateway);

    private ModelCredential channel() {
        ModelCredential c = new ModelCredential();
        c.setId(42L); c.setUserId(7L); c.setProvider("custom"); c.setBaseUrl("https://example.invalid/v1");
        c.setApiKeyEncrypted("cipher"); c.setDefaultModel("saved");
        when(repo.findByIdAndUserId(42L,7L)).thenReturn(Optional.of(c));
        when(crypto.decrypt("cipher")).thenReturn("dummy");
        return c;
    }

    @Test void discoveryNeedsNoDefaultModelAndNeverTestsIt() throws Exception {
        channel().setDefaultModel(null);
        when(gateway.catalog(eq(7L), any(), eq("chat"))).thenReturn(mapper.readTree("{\"status\":\"ready\",\"models\":[{\"id\":\"a\"}]}"));
        var catalog = service.catalog(7L, 42L);
        assertEquals("a",catalog.at("/models/0/id").asText());
        assertEquals(42, catalog.path("providerId").intValue());
        assertFalse(catalog.path("revision").asText().isBlank());
        verify(gateway, never()).test(anyLong(),any(),anyString(),anyList(),anyDouble(),anyInt(),anyInt());
    }

    @Test void foreignProviderIdDoesNotResolveToServerDefault() {
        assertThrows(AgentException.class, () -> service.catalog(8L,42L));
        assertThrows(AgentException.class, () -> service.resolveForRequest(8L,42L));
        verifyNoInteractions(gateway);
    }

    @Test void providerLimitIsNotAnInvalidKey() throws Exception {
        ModelCredential c = channel(); c.setStatus(ModelCredential.CredentialStatus.ok);
        when(gateway.test(eq(7L),any(),eq("chat"),anyList(),anyDouble(),anyInt(),anyInt()))
                .thenReturn(mapper.readTree("{\"ok\":false,\"message\":\"限流\",\"error\":{\"code\":\"RATE_LIMIT\",\"httpStatus\":429,\"retryAfterSeconds\":5}}"));
        var result = service.test(7L,42L);
        assertFalse(result.isOk());
        assertEquals("RATE_LIMIT",result.getError().path("code").asText());
        verify(repo, never()).save(any());
        verify(repo, never()).setTestStatus(anyLong(),anyLong(),any(),any(),any(),any(),any());
    }

    @Test void changedSettingsCannotReceiveAnOldTestResult() throws Exception {
        channel();
        when(gateway.test(anyLong(),any(),anyString(),anyList(),anyDouble(),anyInt(),anyInt()))
                .thenReturn(mapper.readTree("{\"ok\":true,\"message\":\"ok\"}"));
        when(repo.setTestStatus(42L,7L,"cipher","https://example.invalid/v1","saved",null,ModelCredential.CredentialStatus.ok)).thenReturn(0);
        var result = service.test(7L,42L);
        assertFalse(result.isOk());
        assertEquals("CONFIG_CHANGED", result.getError().path("code").asText());
        verify(repo,never()).save(any());
    }

    @Test void editingModelInvalidatesAnEarlierSuccessfulTest() {
        ModelCredential c = channel(); c.setStatus(ModelCredential.CredentialStatus.ok);
        when(repo.findByUserIdAndProviderAndPurpose(7L,"custom","chat")).thenReturn(Optional.of(c));
        when(repo.saveAndFlush(c)).thenReturn(c);
        AgentProviderDtos.UpsertProviderRequest req = new AgentProviderDtos.UpsertProviderRequest();
        req.setProvider("custom"); req.setDefaultModel("changed"); req.setBaseUrl(c.getBaseUrl());
        assertEquals("unknown", service.upsert(7L,req).getStatus());
    }
}
