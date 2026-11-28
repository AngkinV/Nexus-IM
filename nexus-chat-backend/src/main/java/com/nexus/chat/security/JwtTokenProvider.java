package com.nexus.chat.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private static final String SESSION_POLICY_CLAIM = "sessionPolicy";
    private static final String SINGLE_SESSION_POLICY = "single-v1";

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    @Value("${nexus.auth.single-session:false}")
    private boolean singleSession;

    private javax.crypto.SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, null);
    }

    public String generateToken(Long userId, String username, String sessionToken) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        if (sessionToken != null) {
            claims.put("sid", sessionToken);
            if (singleSession) claims.put(SESSION_POLICY_CLAIM, SINGLE_SESSION_POLICY);
        }

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(), Jwts.SIG.HS512)
                .compact();
    }

    /** Session token embedded at login, used to identify the current device session. */
    public String getSessionTokenFromToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.get("sid", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.get("userId", Long.class);
    }

    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.getSubject();
    }

    public java.time.Instant getExpirationFromToken(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
        return claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
    }

    /** Older releases reused a sid across successful logins; those JWTs cannot establish public sessions. */
    public boolean hasCurrentSessionPolicy(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
            return SINGLE_SESSION_POLICY.equals(claims.get(SESSION_POLICY_CLAIM, String.class));
        } catch (Exception invalid) {
            return false;
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
