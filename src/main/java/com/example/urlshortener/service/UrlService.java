package com.example.urlshortener.service;

import com.example.urlshortener.model.Url;
import com.example.urlshortener.repository.UrlRepository;
import com.example.urlshortener.util.Base62;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
    private final RedissonClient redissonClient; // ✅ Inject RedissonClient

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
        url.setShortCode(shortCode); // Hibernate auto-updates

        // Step 3: Cache original URL in Redis
        redisTemplate.opsForValue().set(shortCode, originalUrl, CACHE_TTL_HOURS, TimeUnit.HOURS);

        return shortCode;
    }

    // =========================
    // 2️⃣ Critical Path: Redirect
    // Bulkhead + Cache-first + Redisson Lock (distributed)
    // =========================
    @Bulkhead(name = "redirectService", type = Bulkhead.Type.SEMAPHORE)
    public String getOriginalUrl(String shortCode) {

        // 1️⃣ Check Redis cache first
        String cached = redisTemplate.opsForValue().get(shortCode);
        if (cached != null) {
            incrementClickAsync(shortCode); // Async increment in Redis
            return cached;
        }

        // 2️⃣ Cache miss → acquire distributed lock to prevent DB stampede
        RLock lock = redissonClient.getLock("lock:" + shortCode);
        boolean locked = false;
        try {
            // Try to acquire lock with timeout (wait up to LOCK_TTL_SECONDS)
            locked = lock.tryLock(0, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            if (locked) {
                // Double-check cache after acquiring lock
                cached = redisTemplate.opsForValue().get(shortCode);
                if (cached != null) {
                    incrementClickAsync(shortCode);
                    return cached;
                }

                // Fetch from DB
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

                // Async increment click
                incrementClickAsync(shortCode);

                return url.getOriginalUrl();
            } else {
                // Lock not acquired → wait briefly and retry cache
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                cached = redisTemplate.opsForValue().get(shortCode);
                if (cached != null) {
                    incrementClickAsync(shortCode);
                    return cached;
                }
                throw new RuntimeException("High traffic. Please retry.");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for lock", e);
        } finally {
            // Release lock if held
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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