package com.lanlink.shopping.config;

/**
 * 令牌桶限流器（服务端流量控制）。容量与速率可由动态配置热更新。
 */
public class TokenBucket {

    private final Object lock = new Object();
    private double tokens;
    private long lastRefillNanos;
    private volatile int capacity;
    private volatile double refillPerSecond;

    public TokenBucket(int capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public void configure(int capacity, double refillPerSecond) {
        synchronized (lock) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            if (tokens > capacity) tokens = capacity;
        }
    }

    public boolean tryAcquire() {
        synchronized (lock) {
            long now = System.nanoTime();
            double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSec * refillPerSecond);
            lastRefillNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
