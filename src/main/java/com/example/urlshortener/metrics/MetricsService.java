package com.example.urlshortener.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final Counter redirectCounter;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Timer redirectTimer;

    public MetricsService(MeterRegistry meterRegistry) {
        this.redirectCounter = meterRegistry.counter("redirect_requests_total");
        this.cacheHitCounter = meterRegistry.counter("cache_hits_total");
        this.cacheMissCounter = meterRegistry.counter("cache_misses_total");
        this.redirectTimer = meterRegistry.timer("redirect_latency_seconds");
    }

    public void incrementRedirect() {
        redirectCounter.increment();
    }

    public void incrementCacheHit(){
        cacheHitCounter.increment();
    }

    public void incrementCacheMiss(){
        cacheMissCounter.increment();
    }

    public Timer getRedirectTimer() {
        return redirectTimer;
    }
}