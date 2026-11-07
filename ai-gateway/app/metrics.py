# -*- coding: utf-8 -*-
"""LANLink AI Gateway Prometheus 监控指标收集与暴露模块。

提供符合 Prometheus 0.0.4 规范的纯文本格式指标输出，包含：
1. 请求总数与 QPS：ai_gateway_requests_total{endpoint, method, status, role}
2. 端到端延迟分布：ai_gateway_request_duration_seconds{endpoint, role} (Histogram P50/P90/P95/P99)
3. 工具调用与成功率：ai_gateway_tool_calls_total{tool, role, success}
4. 工具执行耗时分布：ai_gateway_tool_duration_seconds{tool, role} (Histogram)
5. Token 消耗统计：ai_gateway_tokens_total{model, role, token_type}
6. 令牌桶限流拦截：ai_gateway_rate_limit_hits_total{client_id, role}
7. 活跃中并发请求数：ai_gateway_active_requests{endpoint}
8. 宿主机 GPU 显存与利用率联动指标（从 /metrics/gpu.txt 读取）：
   ai_gateway_gpu_memory_total_megabytes, ai_gateway_gpu_memory_used_megabytes, ai_gateway_gpu_utilization_percent
"""
import os
import threading
import time
from pathlib import Path

DEFAULT_BUCKETS = (0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)
TOOL_BUCKETS = (0.01, 0.025, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0)


class _MetricBase:
    def __init__(self, name: str, doc: str, labelnames: tuple[str, ...]):
        self.name = name
        self.doc = doc
        self.labelnames = tuple(labelnames)
        self._lock = threading.Lock()

    def _label_key(self, labels: dict[str, str]) -> tuple[tuple[str, str], ...]:
        return tuple(sorted((k, str(labels.get(k, ""))) for k in self.labelnames))

    @staticmethod
    def _format_labels(label_tuples: tuple[tuple[str, str], ...], extra: dict[str, str] | None = None) -> str:
        all_labels = dict(label_tuples)
        if extra:
            all_labels.update(extra)
        if not all_labels:
            return ""
        items = [f'{k}="{v}"' for k, v in sorted(all_labels.items())]
        return "{" + ",".join(items) + "}"


class Counter(_MetricBase):
    def __init__(self, name: str, doc: str, labelnames: tuple[str, ...] = ()):
        super().__init__(name, doc, labelnames)
        self._values: dict[tuple, float] = {}

    def inc(self, labels: dict[str, str] | None = None, amount: float = 1.0) -> None:
        key = self._label_key(labels or {})
        with self._lock:
            self._values[key] = self._values.get(key, 0.0) + amount

    def render(self) -> list[str]:
        lines = [
            f"# HELP {self.name} {self.doc}",
            f"# TYPE {self.name} counter",
        ]
        with self._lock:
            for key, val in sorted(self._values.items()):
                lbl = self._format_labels(key)
                val_str = f"{val:.0f}" if val.is_integer() else f"{val:.4f}"
                lines.append(f"{self.name}{lbl} {val_str}")
        return lines


class Gauge(_MetricBase):
    def __init__(self, name: str, doc: str, labelnames: tuple[str, ...] = ()):
        super().__init__(name, doc, labelnames)
        self._values: dict[tuple, float] = {}

    def set(self, val: float, labels: dict[str, str] | None = None) -> None:
        key = self._label_key(labels or {})
        with self._lock:
            self._values[key] = float(val)

    def inc(self, labels: dict[str, str] | None = None, amount: float = 1.0) -> None:
        key = self._label_key(labels or {})
        with self._lock:
            self._values[key] = self._values.get(key, 0.0) + amount

    def dec(self, labels: dict[str, str] | None = None, amount: float = 1.0) -> None:
        self.inc(labels, -amount)

    def render(self) -> list[str]:
        lines = [
            f"# HELP {self.name} {self.doc}",
            f"# TYPE {self.name} gauge",
        ]
        with self._lock:
            for key, val in sorted(self._values.items()):
                lbl = self._format_labels(key)
                val_str = f"{val:.0f}" if val.is_integer() else f"{val:.4f}"
                lines.append(f"{self.name}{lbl} {val_str}")
        return lines


class Histogram(_MetricBase):
    def __init__(self, name: str, doc: str, labelnames: tuple[str, ...] = (), buckets: tuple[float, ...] = DEFAULT_BUCKETS):
        super().__init__(name, doc, labelnames)
        self.buckets = tuple(sorted(buckets))
        self._counts: dict[tuple, dict[float, int]] = {}
        self._sums: dict[tuple, float] = {}
        self._totals: dict[tuple, int] = {}

    def observe(self, val: float, labels: dict[str, str] | None = None) -> None:
        key = self._label_key(labels or {})
        val = float(val)
        with self._lock:
            if key not in self._counts:
                self._counts[key] = {b: 0 for b in self.buckets}
                self._sums[key] = 0.0
                self._totals[key] = 0

            for b in self.buckets:
                if val <= b:
                    self._counts[key][b] += 1
            self._sums[key] += val
            self._totals[key] += 1

    def render(self) -> list[str]:
        lines = [
            f"# HELP {self.name} {self.doc}",
            f"# TYPE {self.name} histogram",
        ]
        with self._lock:
            for key in sorted(self._totals.keys()):
                bucket_counts = self._counts[key]
                total = self._totals[key]
                sum_val = self._sums[key]

                for b in self.buckets:
                    lbl = self._format_labels(key, extra={"le": f"{b}"})
                    lines.append(f"{self.name}_bucket{lbl} {bucket_counts[b]}")
                lbl_inf = self._format_labels(key, extra={"le": "+Inf"})
                lines.append(f"{self.name}_bucket{lbl_inf} {total}")
                lbl_base = self._format_labels(key)
                lines.append(f"{self.name}_count{lbl_base} {total}")
                lines.append(f"{self.name}_sum{lbl_base} {sum_val:.6f}")
        return lines


class MetricsRegistry:
    """网关核心指标注册表"""
    def __init__(self):
        # 1. 请求指标
        self.requests_total = Counter(
            "ai_gateway_requests_total",
            "Total number of requests handled by the AI Gateway",
            labelnames=("endpoint", "method", "status", "role")
        )
        self.request_duration = Histogram(
            "ai_gateway_request_duration_seconds",
            "Latency of requests handled by the AI Gateway in seconds",
            labelnames=("endpoint", "role"),
            buckets=DEFAULT_BUCKETS
        )
        self.active_requests = Gauge(
            "ai_gateway_active_requests",
            "Current number of in-flight requests in the AI Gateway",
            labelnames=("endpoint",)
        )

        # 2. 工具调用指标
        self.tool_calls_total = Counter(
            "ai_gateway_tool_calls_total",
            "Total number of e-commerce and multi-agent tool calls",
            labelnames=("tool", "role", "success")
        )
        self.tool_duration = Histogram(
            "ai_gateway_tool_duration_seconds",
            "Execution latency of tools in seconds",
            labelnames=("tool", "role"),
            buckets=TOOL_BUCKETS
        )

        # 3. 大模型 Token 指标
        self.tokens_total = Counter(
            "ai_gateway_tokens_total",
            "Total number of tokens processed (prompt and completion)",
            labelnames=("model", "role", "token_type")
        )

        # 4. 限流与熔断降级指标
        self.rate_limit_hits_total = Counter(
            "ai_gateway_rate_limit_hits_total",
            "Total number of rate limit rejections (429 Too Many Requests)",
            labelnames=("client_id", "role")
        )
        self.fallback_total = Counter(
            "ai_gateway_fallback_total",
            "Total number of fallback / degraded responses triggered",
            labelnames=("reason", "role", "strategy")
        )
        self.circuit_breaker_state = Gauge(
            "ai_gateway_circuit_breaker_state",
            "State of the LLM circuit breaker (1 for active state)",
            labelnames=("state",)
        )
        self.circuit_breaker_state.set(1.0, {"state": "closed"})
        self.circuit_breaker_state.set(0.0, {"state": "open"})
        self.circuit_breaker_state.set(0.0, {"state": "half_open"})

        # 5. 语义缓存指标
        self.cache_hits_total = Counter(
            "ai_gateway_cache_hits_total",
            "Total number of semantic cache hits (exact or semantic)",
            labelnames=("type", "role")
        )
        self.cache_misses_total = Counter(
            "ai_gateway_cache_misses_total",
            "Total number of semantic cache misses",
            labelnames=("role",)
        )

        # 6. 硬件与混部指标
        self.gpu_memory_total_mb = Gauge(
            "ai_gateway_gpu_memory_total_megabytes",
            "Total GPU VRAM in megabytes"
        )
        self.gpu_memory_used_mb = Gauge(
            "ai_gateway_gpu_memory_used_megabytes",
            "Used GPU VRAM in megabytes"
        )
        self.gpu_utilization_percent = Gauge(
            "ai_gateway_gpu_utilization_percent",
            "GPU core compute utilization percentage"
        )

    def update_gpu_metrics(self) -> None:
        """从挂载的 /metrics/gpu.txt 或本级 metrics/gpu.txt 读取最新 GPU 显存数据。"""
        paths = [
            Path("/metrics/gpu.txt"),
            Path("ai-gateway/metrics/gpu.txt"),
            Path("metrics/gpu.txt"),
            Path(__file__).resolve().parent.parent / "metrics" / "gpu.txt",
        ]
        content = None
        for p in paths:
            if p.is_file():
                try:
                    content = p.read_text(encoding="utf-8").strip()
                    if content and "unavailable" not in content:
                        break
                except Exception:
                    continue

        if content and "," in content:
            parts = [x.strip() for x in content.split(",")]
            if len(parts) >= 5:
                try:
                    total_mb = float(parts[1])
                    used_mb = float(parts[2])
                    util_pct = float(parts[4])
                    self.gpu_memory_total_mb.set(total_mb)
                    self.gpu_memory_used_mb.set(used_mb)
                    self.gpu_utilization_percent.set(util_pct)
                except Exception:
                    pass

    def update_circuit_breaker_metrics(self) -> None:
        """更新本地大模型熔断器状态指标"""
        try:
            from .llm import LLM
            cb = getattr(getattr(LLM, "llama", None), "circuit_breaker", None)
            if cb:
                cur_state = str(cb.state).lower()
                for s in ("closed", "open", "half_open"):
                    self.circuit_breaker_state.set(1.0 if s == cur_state else 0.0, {"state": s})
        except Exception:
            pass

    def export_text(self) -> str:
        """生成符合 Prometheus 0.0.4 规范的指标文本。"""
        self.update_gpu_metrics()
        self.update_circuit_breaker_metrics()
        lines: list[str] = []
        metrics = [
            self.requests_total,
            self.request_duration,
            self.active_requests,
            self.tool_calls_total,
            self.tool_duration,
            self.tokens_total,
            self.rate_limit_hits_total,
            self.fallback_total,
            self.circuit_breaker_state,
            self.cache_hits_total,
            self.cache_misses_total,
            self.gpu_memory_total_mb,
            self.gpu_memory_used_mb,
            self.gpu_utilization_percent,
        ]
        for m in metrics:
            lines.extend(m.render())
        return "\n".join(lines) + "\n"


# 全局单例注册表
REGISTRY = MetricsRegistry()
