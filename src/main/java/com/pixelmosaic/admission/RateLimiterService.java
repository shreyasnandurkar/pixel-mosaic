package com.pixelmosaic.admission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


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

    public boolean tryAcquire(String clientIp) {
        AtomicInteger count = counters.get(clientIp, k -> new AtomicInteger(0));
        return count.incrementAndGet() <= limitPerHour;
    }
}