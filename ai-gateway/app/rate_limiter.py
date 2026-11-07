# -*- coding: utf-8 -*-
"""分布式令牌桶限流器与熔断降级中间件（Redis + 本地内存双轨）。

设计特性：
1. 双轨限流：优先基于 Redis + Lua 脚本执行原子令牌桶计算；Redis 宕机或离线测试时无缝降级至本地内存滑动计数；
2. 多租户隔离：按客户端 API Key 哈希（或来源 IP）隔离统计配额，防止恶意用户或失控 Agent 耗尽算力；
3. 标准响应头：超限返回 429 Too Many Requests，并注入 Retry-After、X-RateLimit-Limit、X-RateLimit-Remaining；
4. 轻量熔断器 (Circuit Breaker)：针对后端 Java 服务的调用失败率实施熔断保护，防止雪崩。
"""
import hashlib
import logging
import os
import threading
import time
from typing import Tuple

logger = logging.getLogger("lanlink.rate-limiter")

# 默认配额：每分钟 120 次请求，突发容量 30 个令牌
DEFAULT_RATE_PER_MIN = int(os.environ.get("GATEWAY_RATE_LIMIT_PER_MIN", "120"))
DEFAULT_BURST = int(os.environ.get("GATEWAY_RATE_LIMIT_BURST", "30"))

# Redis Lua 令牌桶原子脚本
LUA_TOKEN_BUCKET = """
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2]) -- 每毫秒补充的令牌数
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4] or 1)

local data = redis.call('HMGET', key, 'tokens', 'last_time')
local tokens = tonumber(data[1])
local last_time = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    last_time = now
else
    local delta = math.max(0, now - last_time)
    tokens = math.min(capacity, tokens + delta * rate)
    last_time = now
end

if tokens >= requested then
    tokens = tokens - requested
    redis.call('HMSET', key, 'tokens', tokens, 'last_time', last_time)
    redis.call('EXPIRE', key, math.ceil(capacity / (rate * 1000) * 2 + 60))
    return {1, math.floor(tokens), 0}
else
    local wait_ms = math.ceil((requested - tokens) / rate)
    local retry_after = math.max(1, math.ceil(wait_ms / 1000))
    return {0, math.floor(tokens), retry_after}
end
"""


class InMemoryTokenBucket:
    """本地内存令牌桶（无 Redis 或单测环境降级兜底）。"""

    def __init__(self, capacity: int, fill_rate_per_sec: float):
        self.capacity = float(capacity)
        self.fill_rate = fill_rate_per_sec
        self.tokens = float(capacity)
        self.last_time = time.time()
        self.lock = threading.Lock()

    def consume(self, amount: int = 1) -> Tuple[bool, int, int]:
        with self.lock:
            now = time.time()
            delta = max(0.0, now - self.last_time)
            self.tokens = min(self.capacity, self.tokens + delta * self.fill_rate)
            self.last_time = now

            if self.tokens >= amount:
                self.tokens -= amount
                return True, int(self.tokens), 0
            else:
                deficit = amount - self.tokens
                wait_sec = max(1, int(deficit / self.fill_rate) + 1)
                return False, int(self.tokens), wait_sec


class DistributedRateLimiter:
    """生产级分布式限流器：Redis 优先，内存兜底。"""

    def __init__(self, rate_per_min: int = DEFAULT_RATE_PER_MIN, burst: int = DEFAULT_BURST):
        self.rate_per_min = rate_per_min
        self.burst = burst
        self.capacity = rate_per_min + burst
        self.rate_per_ms = (rate_per_min / 60.0) / 1000.0  # 令牌/毫秒
        self.rate_per_sec = rate_per_min / 60.0

        self._redis = None
        self._lua_sha = None
        self._local_buckets: dict[str, InMemoryTokenBucket] = {}
        self._local_lock = threading.Lock()
        self._redis_failed = False

    def bind_redis(self, redis_client) -> None:
        """绑定现有的 Redis 连接池。"""
        if redis_client:
            self._redis = redis_client
            try:
                self._lua_sha = self._redis.script_load(LUA_TOKEN_BUCKET)
                logger.info("[rate-limiter] Redis Lua 令牌桶脚本加载就绪")
            except Exception as e:
                logger.warning("[rate-limiter] Redis Lua 脚本加载失败，降级本地内存: %s", e)
                self._redis_failed = True

    def _get_local_bucket(self, client_id: str) -> InMemoryTokenBucket:
        with self._local_lock:
            if client_id not in self._local_buckets:
                # 限制内存桶最多 5000 个，防止内存膨胀
                if len(self._local_buckets) > 5000:
                    self._local_buckets.clear()
                self._local_buckets[client_id] = InMemoryTokenBucket(self.capacity, self.rate_per_sec)
            return self._local_buckets[client_id]

    def acquire(self, client_id: str, cost: int = 1) -> Tuple[bool, int, int]:
        """请求令牌。
        返回：(是否允许通过: bool, 剩余令牌数: int, 建议重试等待秒数: int)
        """
        # 1. 尝试 Redis 分布式限流
        if self._redis and not self._redis_failed:
            try:
                key = f"lanlink:rl:{client_id}"
                now_ms = int(time.time() * 1000)
                if self._lua_sha:
                    res = self._redis.evalsha(self._lua_sha, 1, key, self.capacity, self.rate_per_ms, now_ms, cost)
                else:
                    res = self._redis.eval(LUA_TOKEN_BUCKET, 1, key, self.capacity, self.rate_per_ms, now_ms, cost)
                allowed = bool(res[0])
                remaining = int(res[1])
                retry_after = int(res[2])
                return allowed, remaining, retry_after
            except Exception as exc:
                logger.debug("[rate-limiter] Redis 限流执行异常，平滑降级内存: %s", exc)

        # 2. 本地内存令牌桶降级
        bucket = self._get_local_bucket(client_id)
        return bucket.consume(cost)


class CircuitBreaker:
    """微服务调用熔断器（基于滑动失败计数）。"""

    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"

    def __init__(self, failure_threshold: int = 5, recovery_timeout: float = 15.0):
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self.state = self.CLOSED
        self.failure_count = 0
        self.last_failure_time = 0.0
        self.lock = threading.Lock()

    def allow_request(self) -> bool:
        with self.lock:
            now = time.time()
            if self.state == self.OPEN:
                if now - self.last_failure_time >= self.recovery_timeout:
                    logger.info("[circuit-breaker] 熔断期已满 (%ss)，进入 HALF_OPEN 试探状态", self.recovery_timeout)
                    self.state = self.HALF_OPEN
                    return True
                return False
            return True

    def record_success(self) -> None:
        with self.lock:
            if self.state in (self.HALF_OPEN, self.OPEN):
                logger.info("[circuit-breaker] 后端探测成功，熔断器恢复为 CLOSED 正常状态")
            self.state = self.CLOSED
            self.failure_count = 0

    def record_failure(self) -> None:
        with self.lock:
            self.failure_count += 1
            self.last_failure_time = time.time()
            if self.state == self.HALF_OPEN or self.failure_count >= self.failure_threshold:
                if self.state != self.OPEN:
                    logger.error("[circuit-breaker] 连续失败达到阈值 (%d次)，触发熔断 OPEN", self.failure_count)
                self.state = self.OPEN


# 全局单例
RATE_LIMITER = DistributedRateLimiter()
BACKEND_CIRCUIT_BREAKER = CircuitBreaker(failure_threshold=5, recovery_timeout=15.0)
# 向量知识库是独立依赖：单独熔断，避免它故障时连带短路商品/报价等无关工具。
KB_CIRCUIT_BREAKER = CircuitBreaker(failure_threshold=5, recovery_timeout=15.0)


def client_fingerprint(api_key: str, remote_ip: str) -> str:
    """生成客户端哈希指纹用于多租户限流隔离。"""
    raw = api_key if api_key else (remote_ip or "anonymous")
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]
