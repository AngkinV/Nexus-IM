package com.nexus.chat.service;

import com.nexus.chat.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Temporary lock after repeated failed sign-ins to one account, so a password cannot be
 * guessed at network speed. Off unless {@code nexus.auth.lockout.max-failures} is positive
 * (the {@code public} profile sets it). Counters live in memory: a restart clears them.
 */
@Component
public class LoginThrottle {
    public static final String LOCKED = "尝试次数过多，请稍后再试";
    /** Unknown names are counted too; this bounds what a flood of made-up names can occupy. */
    static final int MAX_TRACKED = 50_000;

    private record Attempts(int failures, long lastFailure) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final long lockMillis;
    private final LongSupplier clock;

    @Autowired
    public LoginThrottle(@Value("${nexus.auth.lockout.max-failures:0}") int maxFailures,
                         @Value("${nexus.auth.lockout.minutes:15}") int minutes) {
        this(maxFailures, minutes, System::currentTimeMillis);
    }

    LoginThrottle(int maxFailures, int minutes, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.lockMillis = minutes * 60_000L;
        this.clock = clock;
    }

    /** Throws while the key is locked. Call before the password is looked at. */
    public void requireAllowed(String key) {
        if (maxFailures <= 0) return;
        Attempts seen = attempts.get(key);
        if (seen == null) return;
        if (clock.getAsLong() - seen.lastFailure() >= lockMillis) {
            attempts.remove(key, seen);
        } else if (seen.failures() >= maxFailures) {
            throw new BusinessException(LOCKED);
        }
    }

    public void failed(String key) {
        if (maxFailures <= 0) return;
        long now = clock.getAsLong();
        if (attempts.size() >= MAX_TRACKED && !attempts.containsKey(key)) {
            attempts.values().removeIf(seen -> now - seen.lastFailure() >= lockMillis);
            if (attempts.size() >= MAX_TRACKED) return;
        }
        attempts.merge(key, new Attempts(1, now), (seen, first) ->
                now - seen.lastFailure() >= lockMillis ? first : new Attempts(seen.failures() + 1, now));
    }

    public void succeeded(String key) {
        attempts.remove(key);
    }
}
