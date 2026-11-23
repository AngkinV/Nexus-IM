package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.service.CompanionCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No model/embedding API is invoked; all values below are synthetic fixtures. */
class McpSecretRegistryServiceTest {
    private final ModelCredentialRepository repository = mock(ModelCredentialRepository.class);
    private final CompanionCryptoService crypto = mock(CompanionCryptoService.class);
    private final McpSecretRegistryService service = new McpSecretRegistryService(repository, crypto, new ObjectMapper());

    @Test void onlyOwnedEncryptedCredentialsEnterThePrivateQuarantineWrapper() {
        ModelCredential row = new ModelCredential(); row.setUserId(41L); row.setApiKeyEncrypted("fixture-cipher");
        when(repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(41L)).thenReturn(List.of(row));
        when(crypto.decrypt("fixture-cipher")).thenReturn("fixture-only-provider-key");
        var result = service.owned(41);
        assertEquals("fixture-only-provider-key", result.payload().get(0).asText());
        assertFalse(result.toString().contains("fixture-only-provider-key"));
        verify(repository).findByUserIdOrderByIsDefaultDescUpdatedAtDesc(41L);
        verifyNoMoreInteractions(repository);
    }

    @Test void anUnexpectedForeignRowIsNeverDecryptedOrReturned() {
        ModelCredential row = new ModelCredential(); row.setUserId(42L); row.setApiKeyEncrypted("foreign-cipher");
        when(repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(41L)).thenReturn(List.of(row));
        assertThrows(ResponseStatusException.class, () -> service.owned(41));
        verifyNoInteractions(crypto);
    }

    @Test void aFailedDecryptCannotBeSilentlyMarkedVerified() {
        ModelCredential row = new ModelCredential(); row.setUserId(41L); row.setApiKeyEncrypted("bad-cipher");
        when(repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(41L)).thenReturn(List.of(row));
        when(crypto.decrypt("bad-cipher")).thenThrow(new IllegalStateException("fixture-only-sensitive-cause"));
        var failure = assertThrows(ResponseStatusException.class, () -> service.owned(41));
        assertFalse(failure.toString().contains("fixture-only-sensitive-cause"));
        assertNull(failure.getCause());
    }
}
