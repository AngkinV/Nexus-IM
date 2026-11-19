package com.nexus.chat.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Uses literal IPs so no test depends on DNS. The DNS-resolution branch is
 * exercised implicitly by every literal-IP case (getAllByName on a literal
 * never queries a resolver).
 */
class ModelEndpointGuardTest {

    private final ModelEndpointGuard permissive = new ModelEndpointGuard(true);
    private final ModelEndpointGuard strict = new ModelEndpointGuard(false);

    @Test
    void blankEndpointIsOptional() {
        assertDoesNotThrow(() -> strict.validate(null));
        assertDoesNotThrow(() -> strict.validate(""));
        assertDoesNotThrow(() -> strict.validate("   "));
    }

    @Test
    void publicAddressAllowedInBothModes() {
        assertDoesNotThrow(() -> permissive.validate("https://8.8.8.8/v1"));
        assertDoesNotThrow(() -> strict.validate("https://8.8.8.8/v1"));
    }

    @Test
    void nonHttpSchemesRejected() {
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("ftp://8.8.8.8/"));
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("gopher://8.8.8.8/"));
    }

    @Test
    void embeddedCredentialsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> permissive.validate("http://user:pass@8.8.8.8/v1"));
    }

    @Test
    void missingHostRejected() {
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("http://"));
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("not a url"));
    }

    @Test
    void metadataAndLinkLocalAlwaysRejected() {
        for (ModelEndpointGuard guard : new ModelEndpointGuard[]{permissive, strict}) {
            assertThrows(IllegalArgumentException.class, () -> guard.validate("http://169.254.169.254/latest/meta-data/"));
            assertThrows(IllegalArgumentException.class, () -> guard.validate("http://0.0.0.0/"));
            assertThrows(IllegalArgumentException.class, () -> guard.validate("http://[fe80::1]/"));
            assertThrows(IllegalArgumentException.class, () -> guard.validate("http://224.0.0.1/"));
        }
    }

    @Test
    void loopbackAllowedOnlyInPermissiveMode() {
        assertDoesNotThrow(() -> permissive.validate("http://127.0.0.1:11434/v1"));
        assertDoesNotThrow(() -> permissive.validate("http://[::1]:11434/v1"));
        assertThrows(IllegalArgumentException.class, () -> strict.validate("http://127.0.0.1:11434/v1"));
        assertThrows(IllegalArgumentException.class, () -> strict.validate("http://[::1]:11434/v1"));
    }

    @Test
    void privateRangesAllowedOnlyInPermissiveMode() {
        for (String url : new String[]{"http://10.0.0.5/v1", "http://172.16.3.4/v1", "http://192.168.1.10/v1"}) {
            assertDoesNotThrow(() -> permissive.validate(url));
            assertThrows(IllegalArgumentException.class, () -> strict.validate(url));
        }
    }

    @Test
    void ipv6UniqueLocalAllowedOnlyInPermissiveMode() {
        assertDoesNotThrow(() -> permissive.validate("http://[fc00::1]/v1"));
        assertThrows(IllegalArgumentException.class, () -> strict.validate("http://[fc00::1]/v1"));
        assertThrows(IllegalArgumentException.class, () -> strict.validate("http://[fd00:ec2::254]/"));
    }
}