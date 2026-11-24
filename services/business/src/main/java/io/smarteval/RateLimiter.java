package io.smarteval;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
class RateLimiter {
    private static final int MAX_KEYS = 20_000;
    private final ConcurrentHashMap<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    boolean allow(String key, int maxAttempts, long windowSeconds) {
        long now = Instant.now().getEpochSecond();
        long cutoff = now - windowSeconds;
        if (buckets.size() > MAX_KEYS) {
            buckets.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    while (!entry.getValue().isEmpty() && entry.getValue().peekFirst() <= cutoff) {
                        entry.getValue().pollFirst();
                    }
                    return entry.getValue().isEmpty();
                }
            });
        }
        Deque<Long> queue = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() <= cutoff) queue.pollFirst();
            if (queue.size() >= maxAttempts) return false;
            queue.addLast(now);
            return true;
        }
    }

    void reset(String key) { buckets.remove(key); }

    void clear() { buckets.clear(); }

    static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (isTrustedProxy(remote)) {
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank() && real.length() <= 64) return real.strip();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).strip();
                if (!first.isEmpty() && first.length() <= 64) return first;
            }
        }
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private static boolean isTrustedProxy(String remote) {
        if (remote == null || remote.isBlank()) return false;
        try {
            var addr = java.net.InetAddress.getByName(remote);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
