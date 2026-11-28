package com.nexus.chat.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialAddressPolicyTest {
    @Test void aServerIsItsSchemeHostAndPortAndNothingElse() {
        assertEquals("https://api.example.test:443", CredentialAddressPolicy.server("https://api.example.test/v1"));
        assertEquals(CredentialAddressPolicy.server("https://api.example.test/v1"),
                CredentialAddressPolicy.server(" HTTPS://API.Example.Test:443/other/path?x=1 "));
        assertEquals("http://api.example.test:80", CredentialAddressPolicy.server("http://api.example.test"));
        assertNull(CredentialAddressPolicy.server(null));
        assertNull(CredentialAddressPolicy.server("   "));
    }

    @Test void anotherSchemePortOrHostIsAnotherServer() {
        String saved = CredentialAddressPolicy.server("https://api.example.test/v1");
        for (String moved : new String[]{"http://api.example.test/v1", "https://api.example.test:8443/v1",
                "https://api.example.test.evil.test/v1", "https://evil.test/api.example.test/v1",
                // The part before @ is a user name; the request goes to the host after it.
                "https://api.example.test@evil.test/v1"}) {
            assertNotEquals(saved, CredentialAddressPolicy.server(moved), moved);
        }
    }

    @Test void aValueThatIsNotAUrlOnlyEqualsItself() {
        assertEquals("not a url", CredentialAddressPolicy.server("not a url"));
        assertNotEquals(CredentialAddressPolicy.server("api.example.test"), CredentialAddressPolicy.server("https://api.example.test"));
    }

    @Test void theSwitchDecidesWhetherAMoveNeedsTheSecretAgain() {
        CredentialAddressPolicy off = new CredentialAddressPolicy(false);
        CredentialAddressPolicy on = new CredentialAddressPolicy(true);
        assertFalse(off.mustReenter("https://a.test:443", "https://b.test:443"));
        assertTrue(on.mustReenter("https://a.test:443", "https://b.test:443"));
        assertTrue(on.mustReenter("https://a.test:443", null));
        assertTrue(on.mustReenter(null, "https://b.test:443"));
        assertFalse(on.mustReenter("https://a.test:443", "https://a.test:443"));
        assertFalse(on.mustReenter(null, null));
    }
}
