package com.drzewiecki.shitcloud.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A shared password can be guessed by brute force, so failed guest sign-ins are counted per client IP
 * over a sliding window. In memory: it resets on restart and is per instance, which is enough while the
 * API runs as a single Railway instance.
 */
@Component
public class LoginAttemptLimiter {

    private final int maxFailures;
    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(AuthProperties properties) {
        this.maxFailures = properties.guest().maxFailures();
        this.window = properties.guest().failureWindow();
    }

    public boolean blocked(String ip) {
        Deque<Instant> recent = failures.computeIfPresent(ip, (key, times) -> prune(times));
        return recent != null && recent.size() >= maxFailures;
    }

    public void recordFailure(String ip) {
        failures.compute(ip, (key, times) -> {
            Deque<Instant> recent = times == null ? new ArrayDeque<>() : times;
            recent.addLast(Instant.now());
            return prune(recent);
        });
    }

    public void reset(String ip) {
        failures.remove(ip);
    }

    /** Returning null from a compute removes the entry, so an IP that stops failing stops costing memory. */
    private Deque<Instant> prune(Deque<Instant> times) {
        Instant cutoff = Instant.now().minus(window);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.pollFirst();
        }
        return times.isEmpty() ? null : times;
    }
}
