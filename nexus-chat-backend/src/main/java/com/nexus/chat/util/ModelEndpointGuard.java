package com.nexus.chat.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Boundary check for user-supplied model endpoints (BYOK base URLs). The
 * backend itself fetches these URLs (credential probes, tavern calls), so a
 * malicious value could otherwise turn the server into an SSRF proxy into
 * the deployment's internal network or the cloud metadata service.
 *
 * Always rejected: non-http(s) schemes, embedded credentials, unresolvable
 * hosts, wildcard / link-local / multicast addresses (169.254.169.254 metadata
 * falls in link-local).
 *
 * Loopback and private ranges are a config decision: local Ollama is a
 * first-class preset, so they are allowed by default. Public multi-tenant
 * deployments should set model.endpoint.allow-private-network=false.
 */
@Component
public class ModelEndpointGuard {

    private final boolean allowPrivateNetwork;

    public ModelEndpointGuard(
            @Value("${model.endpoint.allow-private-network:true}") boolean allowPrivateNetwork) {
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    /** @throws IllegalArgumentException with a user-facing reason when the URL is unsafe. */
    public void validate(String url) {
        if (url == null || url.isBlank()) return; // endpoint is optional
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid endpoint URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("endpoint must use http:// or https://");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("endpoint must not embed credentials (user:pass@host)");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("endpoint has no valid host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("endpoint host cannot be resolved: " + host);
        }
        for (InetAddress addr : addresses) {
            checkAddress(addr, allowPrivateNetwork);
        }
    }

    static void checkAddress(InetAddress addr, boolean allowPrivateNetwork) {
        if (addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isMulticastAddress()) {
            throw new IllegalArgumentException("endpoint address is not allowed: " + addr.getHostAddress());
        }
        boolean privateNetwork = addr.isLoopbackAddress() || addr.isSiteLocalAddress() || isIpv6UniqueLocal(addr);
        if (privateNetwork && !allowPrivateNetwork) {
            throw new IllegalArgumentException("endpoint points to a private/loopback network: " + addr.getHostAddress());
        }
    }

    /** fc00::/7 — Java's isSiteLocalAddress() only covers IPv4, so check ULA manually. */
    private static boolean isIpv6UniqueLocal(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}