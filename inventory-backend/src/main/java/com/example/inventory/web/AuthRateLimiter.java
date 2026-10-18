package com.example.inventory.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.example.inventory.auth.AuthProperties;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class AuthRateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AuthProperties.RateLimit limits;

    public AuthRateLimiter(AuthProperties properties) {
        this.limits = properties.rateLimit();
    }

    private static final class Window {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant resetsAt;

        Window(Instant resetsAt) {
            this.resetsAt = resetsAt;
        }
    }

    public void checkRegistration(HttpServletRequest request) {
        check("register:" + clientKey(request), limits.registrationsPerHour(), Duration.ofHours(1),
                "Too many registration attempts. Try again later.");
    }

    public void checkLogin(HttpServletRequest request) {
        check("login:" + clientKey(request), limits.loginAttemptsPerMinute(), Duration.ofMinutes(1),
                "Too many login attempts. Try again shortly.");
    }

    private void check(String key, int permitted, Duration window, String message) {
        Instant now = Instant.now();

        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || now.isAfter(existing.resetsAt)) {
                return new Window(now.plus(window));
            }
            return existing;
        });

        if (current.count.incrementAndGet() > permitted) {
            Duration retryAfter = Duration.between(now, current.resetsAt);
            throw new RateLimitedException(
                    message, retryAfter.isNegative() ? Duration.ZERO : retryAfter);
        }

        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now.isAfter(entry.getValue().resetsAt));
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "unknown" : address;
    }
    public void checkPasswordChange(UUID userId) {
        check("pwchange:" + userId, limits.loginAttemptsPerMinute(), Duration.ofMinutes(1),
                "Too many attempts. Try again shortly.");
    }
}
