package com.example.urlshortener.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final StringRedisTemplate redisTemplate;

    @Async
    public void incrementClick(String shortCode) {
        redisTemplate.opsForValue().increment("click:" + shortCode);
    }
}