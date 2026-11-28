package com.nexus.chat.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Whether a saved API key or MCP credential may follow its entry to a different server.
 *
 * A stored secret is never shown again, but anyone signed in to the account can edit the
 * entry. If the address could change while the secret stayed, the next call would hand
 * the secret to whatever server the new address names. With the switch on, such a save
 * is refused unless the secret is entered again (or, for MCP, explicitly cleared).
 *
 * Off by default, so a local install behaves as it always has; the public profile turns
 * it on ({@code nexus.credentials.bound-to-address=true}).
 */
@Component
public class CredentialAddressPolicy {
    private final boolean bound;

    public CredentialAddressPolicy(@Value("${nexus.credentials.bound-to-address:false}") boolean bound) {
        this.bound = bound;
    }

    /** True when a secret saved for {@code before} must be entered again before it is used at {@code after}. */
    public boolean mustReenter(String before, String after) {
        return bound && !Objects.equals(before, after);
    }

    /**
     * The server a URL talks to: scheme, host and port, with the default port filled in.
     * The path is left out on purpose, so correcting {@code /v1} on the same server does
     * not count as moving. Null for a blank value; a value that is not a usable URL is
     * returned as typed, so any edit of it counts as a move.
     */
    public static String server(String url) {
        if (url == null || url.isBlank()) return null;
        String typed = url.trim();
        try {
            URI uri = new URI(typed);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme == null || host == null) return typed;
            int port = uri.getPort() != -1 ? uri.getPort() : scheme.equals("https") ? 443 : scheme.equals("http") ? 80 : -1;
            return scheme + "://" + host + ":" + port;
        } catch (Exception notAUrl) {
            return typed;
        }
    }
}
