package com.example.urlshortener.service;

import com.example.urlshortener.model.Url;
import com.example.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class ClickFlushWorker {

    private final StringRedisTemplate redisTemplate;
    private final UrlRepository urlRepository;

    // =========================
    // Scheduled worker flushes Redis click counts to DB
    // Runs every 30 seconds
    // =========================
    @Scheduled(fixedRate = 30000)
    public void flushClicks() {

        Set<String> keys = redisTemplate.keys("click:*");
        if (keys == null || keys.isEmpty()) return;

        for (String key : keys) {
            try {
                String shortCode = key.substring(6); // Remove "click:" prefix
                String countStr = redisTemplate.opsForValue().get(key);
                if (countStr == null) continue;

                long count = Long.parseLong(countStr);

                // Update DB click count
                urlRepository.findByShortCode(shortCode).ifPresent(url -> {
                    url.setClickCount(url.getClickCount() + count);
                    urlRepository.save(url);
                });

                // Reset Redis counter
                redisTemplate.delete(key);

            } catch (Exception e) {
                e.printStackTrace(); // Log error but continue
            }
        }
    }
}