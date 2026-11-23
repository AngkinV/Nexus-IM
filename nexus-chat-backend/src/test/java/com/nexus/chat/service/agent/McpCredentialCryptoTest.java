package com.nexus.chat.service.agent;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** All key material here is synthetic and never used by a runtime service. Not run in T4A. */
class McpCredentialCryptoTest {
    private static final String ID = "714e3483-cf38-42e4-88cd-6cf56af62f71";
    private static final String OTHER = "9a75c91b-1f3c-45ec-a8e7-5541984eb020";
    private static final String SECRET = "fixture-only-sensitive-value";

    private McpCredentialCrypto crypto() {
        return new McpCredentialCrypto(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test void randomNonceAndAuthenticatedRoundTrip() {
        McpCredentialCrypto crypto = crypto();
        String first = crypto.encrypt(SECRET, 41, ID), second = crypto.encrypt(SECRET, 41, ID);
        assertNotEquals(first, second);
        assertFalse(first.contains(SECRET));
        assertEquals(SECRET, crypto.decrypt(first, 41, ID));
        assertFalse(crypto.toString().contains(SECRET));
    }

    @Test void ownerAndServerAreBoundInAad() {
        McpCredentialCrypto crypto = crypto();
        String cipher = crypto.encrypt(SECRET, 41, ID);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(cipher, 42, ID));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(cipher, 41, OTHER));
    }

    @Test void missingKeyCannotUseADevelopmentFallback() {
        assertThrows(IllegalStateException.class, () -> new McpCredentialCrypto("").encrypt(SECRET, 41, ID));
        assertThrows(IllegalStateException.class, () -> new McpCredentialCrypto("not-base64").encrypt(SECRET, 41, ID));
    }

    @Test void corruptionIsControlledAndDoesNotEchoSecretOrCiphertext() {
        McpCredentialCrypto crypto = crypto();
        String cipher = crypto.encrypt(SECRET, 41, ID);
        byte[] bytes = Base64.getDecoder().decode(cipher); bytes[bytes.length - 1] ^= 1;
        String altered = Base64.getEncoder().encodeToString(bytes);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> crypto.decrypt(altered, 41, ID));
        assertFalse(error.toString().contains(SECRET));
        assertFalse(error.toString().contains(altered));
        assertNull(error.getCause());
    }
}
