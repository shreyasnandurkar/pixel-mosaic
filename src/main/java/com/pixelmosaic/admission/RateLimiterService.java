package com.pixelmosaic.admission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-client-IP rate limiter backed by a Caffeine cache. Each IP gets a counter that resets
 * an hour after its first request in the window (expire-after-write); requests beyond the
 * configured hourly limit are rejected. The cache is size-bounded so a flood of distinct IPs
 * cannot exhaust memory.
 */
@Service
public class RateLimiterService {

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100_000)
            .build();

    private final int limitPerHour;

    public RateLimiterService(@Value("${pixelmosaic.rate-limit-per-hour}") int limitPerHour) {
        this.limitPerHour = limitPerHour;
    }

    /** @return true if this request is within the client's hourly allowance. */
    public boolean tryAcquire(String clientIp) {
        AtomicInteger count = counters.get(clientIp, k -> new AtomicInteger(0));
        return count.incrementAndGet() <= limitPerHour;
    }
}
