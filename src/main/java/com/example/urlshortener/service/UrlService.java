package com.example.urlshortener.service;

import com.example.urlshortener.metrics.MetricsService;
import com.example.urlshortener.model.Url;
import com.example.urlshortener.repository.UrlRepository;
import com.example.urlshortener.util.Base62;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class UrlService {

    private final UrlRepository urlRepository;
    private final StringRedisTemplate redisTemplate;
    private final MetricsService metricsService;

    private static final long CACHE_TTL_HOURS = 24;
    private static final long LOCK_TTL_SECONDS = 5;

    // =========================
    // 1️⃣ Create Short URL
    // =========================
    @Transactional
    public String createShortUrl(String originalUrl) {

        // Step 1: Save entity to generate ID
        Url url = Url.builder()
                .originalUrl(originalUrl)
                .createdAt(LocalDateTime.now())
                .clickCount(0L)
                .build();
        urlRepository.save(url);

        // Step 2: Generate shortCode
        String shortCode = Base62.encode(url.getId());
        url.setShortCode(shortCode); // Hibernate will auto-save

        // Step 3: Cache original URL in Redis
        redisTemplate.opsForValue().set(shortCode, originalUrl, CACHE_TTL_HOURS, TimeUnit.HOURS);

        return shortCode;
    }

    // =========================
    // 2️⃣ Critical Path: Redirect
    // Protected by Bulkhead
    // Cache-first + Coalescing
    // =========================
    @Bulkhead(name = "redirectService", type = Bulkhead.Type.SEMAPHORE)
    public String getOriginalUrl(String shortCode) {

        return metricsService.getRedirectTimer().record(() -> {

            metricsService.incrementRedirect();

            // 1️⃣ Check Redis cache first
            String cached = redisTemplate.opsForValue().get(shortCode);
            if (cached != null) {
                incrementClickAsync(shortCode); // Async increment in Redis
                metricsService.incrementCacheHit();
                return cached;
            }

            // 2️⃣ Cache miss → Coalescing to prevent DB stampede
            metricsService.incrementCacheMiss();
            String lockKey = "lock:" + shortCode;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(acquired)) {
                try {
                    // Single thread hits DB
                    Url url = urlRepository.findByShortCode(shortCode)
                            .orElseThrow(() -> new RuntimeException("URL not found"));

                    // Optional expiry check
                    if (url.getExpiresAt() != null &&
                            url.getExpiresAt().isBefore(LocalDateTime.now())) {
                        throw new RuntimeException("URL expired");
                    }

                    // Cache result in Redis
                    redisTemplate.opsForValue().set(shortCode, url.getOriginalUrl(),
                            CACHE_TTL_HOURS, TimeUnit.HOURS);

                    // Async increment click in Redis
                    incrementClickAsync(shortCode);

                    return url.getOriginalUrl();

                } finally {
                    // Release lock
                    redisTemplate.delete(lockKey);
                }

            } else {
                // Other threads wait briefly and retry cache
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
                String retry = redisTemplate.opsForValue().get(shortCode);
                if (retry != null) {
                    incrementClickAsync(shortCode);
                    return retry;
                }
                throw new RuntimeException("High traffic. Please retry.");
            }
        });
    }

    // =========================
    // 3️⃣ Async click increment in Redis
    // Non-blocking for redirect
    // =========================
    @Async
    public void incrementClickAsync(String shortCode) {
        redisTemplate.opsForValue().increment("click:" + shortCode);
    }

    // =========================
    // 4️⃣ Get all URLs (Admin / Monitoring)
    // =========================
    public List<Url> getAll() {
        return urlRepository.findAll();
    }
}