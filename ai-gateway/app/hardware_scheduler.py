# -*- coding: utf-8 -*-
"""硬件调度 + 智能模型切换策略（GPU 优先、CPU 辅助、溢出保护）。

设计目标（本机 i7-14650HX / RTX 4070 Laptop 8GB / 24GB RAM）：
  1. 默认全部推理走 RTX 4070（速度优先），llama.cpp router 按模型名自动加载/切换；
  2. 长文本（>= HARDWARE_LONG_TEXT_CHARS 字符）强制走 qwen2.5-7b 文本模型，
     不因为会话多轮/资料长而误路由到视觉模型；
  3. 当显存不足以完整容纳目标模型（例如其他 GPU 程序占用、刚切模型）时，
     允许"溢出一点"：优先切换到 qwen2.5-7b-cpu-assist（仅 3/29 层放 CPU，
     其余仍在 GPU），速度接近全 GPU；仍放不下则降级 Ollama/mock，不 500、不挂死；
  4. 视觉请求只在显存足够时加载 qwen2.5-vl（视觉编码器必须 GPU），否则返回明确提示。

说明：Intel UHD 核显无法参与当前 CUDA 版 llama.cpp 的同一推理图；这里"CPU 辅助"
指 llama.cpp 的 partial offload（少量层在 CPU），是 8GB 显存下最现实的溢出方案。
"""
import os
import subprocess
import time

from .model_manager import KIND_TEXT, KIND_VL


def _int_env(name: str, default: int) -> int:
    try:
        return int(float(os.environ.get(name, str(default))))
    except (TypeError, ValueError):
        return default


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        return default


class HardwareScheduler:
    """GPU/CPU/RAM 采样 + 模型选择策略；无外部依赖，单测可注入 snapshot_fn。"""

    def __init__(self, manager=None, snapshot_fn=None):
        self.manager = manager
        self._snapshot_fn = snapshot_fn
        self._cache = None
        self._cache_ts = 0.0
        self.cache_sec = _float_env("HARDWARE_CACHE_SEC", 2.0)
        self.text_model = os.environ.get("LLAMA_MODEL_TEXT", "qwen2.5-7b")
        self.vl_model = os.environ.get("LLAMA_MODEL_VL", "qwen2.5-vl")
        self.cpu_assist_model = os.environ.get("LLAMA_MODEL_CPU_ASSIST", "qwen2.5-7b-cpu-assist")
        self.long_text_chars = _int_env("HARDWARE_LONG_TEXT_CHARS", 6000)
        self.gpu_margin_mb = _int_env("HARDWARE_GPU_MARGIN_MB", 250)
        self.default_total_mb = _int_env("HARDWARE_VRAM_TOTAL_MB", 8188)
        # 模型显存估算（MB）：按当前 preset 实测校准（text ctx16384≈6.6G；CPU辅助≈5.5G；
        # VL 视觉峰值实测约 7.5G，按 7.3G 估算 + 250MB 余量，避免模型切换后的瞬时波动误判）
        self.est_text_mb = _int_env("HARDWARE_VRAM_TEXT_MB", 6650)
        self.est_cpu_assist_mb = _int_env("HARDWARE_VRAM_CPU_ASSIST_MB", 5550)
        self.est_vl_mb = _int_env("HARDWARE_VRAM_VL_MB", 7300)
        # 切换防抖：进入 cpu-assist 后冷却期内不回 GPU（每次换模=卸载+冷加载+KV 全失）；
        # 冷却期结束后还需额外滞回余量才回 GPU，避免显存在阈值附近时反复换模
        self.switch_cooldown_sec = _float_env("HARDWARE_SWITCH_COOLDOWN_SEC", 120.0)
        self.return_hysteresis_mb = _int_env("HARDWARE_RETURN_HYSTERESIS_MB", 500)
        self._assist_until = 0.0
        self.force_mode = (os.environ.get("HARDWARE_FORCE_MODE") or "").strip().lower()

    # ------------------------------ 采样 ------------------------------
    def snapshot(self, force: bool = False) -> dict:
        """GPU/CPU/RAM 快照（缓存 2s，避免每次请求都跑 nvidia-smi）。"""
        now = time.time()
        if not force and self._cache is not None and now - self._cache_ts < self.cache_sec:
            return dict(self._cache)
        snap = self._read_snapshot()
        self._cache = snap
        self._cache_ts = now
        return dict(snap)

    def _read_snapshot(self) -> dict:
        if self._snapshot_fn is not None:
            data = dict(self._snapshot_fn() or {})
            data.setdefault("source", "injected")
            return data
        snap = {
            "gpu_name": "",
            "vram_total_mb": self.default_total_mb,
            "vram_used_mb": 0,
            "vram_free_mb": self.default_total_mb,
            "gpu_util": 0,
            "cpu_count": os.cpu_count() or 1,
            "load1": 0.0,
            "ram_total_mb": 0,
            "ram_available_mb": 0,
            "source": "defaults",
        }
        # 首选 gpu-metrics sidecar 写入的指标文件（gateway 容器本身无 GPU 设备/nvidia-smi）
        metrics_file = os.environ.get("HARDWARE_GPU_METRICS_FILE") or "/metrics/gpu.txt"
        try:
            with open(metrics_file, "r", encoding="utf-8") as fh:
                line = fh.read().strip().splitlines()[0]
            parts = [p.strip() for p in line.split(",")]
            if len(parts) >= 5:
                snap.update(
                    gpu_name=parts[0],
                    vram_total_mb=int(float(parts[1])),
                    vram_used_mb=int(float(parts[2])),
                    vram_free_mb=int(float(parts[3])),
                    gpu_util=int(float(parts[4])),
                    source="gpu-metrics-file",
                )
        except Exception:
            pass
        if snap["source"] == "defaults":
            try:
                out = subprocess.run(
                    ["nvidia-smi", "--query-gpu=name,memory.total,memory.used,memory.free,utilization.gpu",
                     "--format=csv,noheader,nounits"],
                    capture_output=True, text=True, timeout=1.5)
                if out.returncode == 0:
                    parts = [p.strip() for p in out.stdout.strip().splitlines()[0].split(",")]
                    if len(parts) >= 5:
                        snap.update(
                            gpu_name=parts[0],
                            vram_total_mb=int(float(parts[1])),
                            vram_used_mb=int(float(parts[2])),
                            vram_free_mb=int(float(parts[3])),
                            gpu_util=int(float(parts[4])),
                            source="nvidia-smi",
                        )
            except Exception:
                pass
        try:
            with open("/proc/loadavg", "r", encoding="utf-8") as fh:
                snap["load1"] = float(fh.read().split()[0])
        except Exception:
            pass
        try:
            mem = {}
            with open("/proc/meminfo", "r", encoding="utf-8") as fh:
                for line in fh:
                    if ":" in line:
                        key, val = line.split(":", 1)
                        mem[key.strip()] = int(val.strip().split()[0])
            snap["ram_total_mb"] = mem.get("MemTotal", 0) // 1024
            snap["ram_available_mb"] = mem.get("MemAvailable", 0) // 1024
        except Exception:
            pass
        return snap

    def _current_loaded(self) -> str | None:
        if self.manager is None:
            return None
        try:
            return self.manager.current_loaded()
        except Exception:
            return None

    def _estimate_loaded_mb(self, model: str | None) -> int:
        if not model:
            return 0
        name = model.lower()
        if "vl" in name:
            return self.est_vl_mb
        if "cpu-assist" in name:
            return self.est_cpu_assist_mb
        return self.est_text_mb

    def _router_available(self) -> bool:
        """router 是否可达（不可达时无法判断已加载模型，显存策略应保持乐观，避免误拦截）。"""
        if self.manager is None:
            return False
        try:
            return bool(self.manager.models())
        except Exception:
            return False

    def _vram_plan(self, target_est_mb: int) -> dict:
        snap = self.snapshot()
        current = self._current_loaded()
        total = int(snap.get("vram_total_mb", self.default_total_mb))
        if not self._router_available():
            # 离线/mock/单测环境：无法得知已加载模型，按"可运行"处理，由 llama.cpp/Ollama 兜底。
            return {
                "current_loaded": current,
                "non_llama_mb": 0,
                "free_after_switch_mb": total,
                "required_mb": target_est_mb,
                "margin_mb": self.gpu_margin_mb,
                "fits": True,
                "assumed": True,
                "vram_total_mb": total,
                "vram_used_mb": int(snap.get("vram_used_mb", 0)),
                "gpu_util": int(snap.get("gpu_util", 0)),
            }
        non_llama = max(0, int(snap.get("vram_used_mb", 0)) - self._estimate_loaded_mb(current))
        free_after_switch = max(0, total - non_llama)
        fits = free_after_switch >= target_est_mb + self.gpu_margin_mb
        return {
            "current_loaded": current,
            "non_llama_mb": non_llama,
            "free_after_switch_mb": free_after_switch,
            "required_mb": target_est_mb,
            "margin_mb": self.gpu_margin_mb,
            "fits": fits,
            "vram_total_mb": total,
            "vram_used_mb": int(snap.get("vram_used_mb", 0)),
            "gpu_util": int(snap.get("gpu_util", 0)),
        }

    # ------------------------------ 策略 ------------------------------
    def decide_text(self, prompt_chars: int = 0, requested_model: str | None = None) -> dict:
        """文本请求：长文本固定走文本模型；GPU 优先，显存不足时 CPU 轻量辅助。"""
        long_text = prompt_chars >= self.long_text_chars
        requested = (requested_model or "").strip()
        # 显式指定 CPU 辅助模型时直接尊重（便于运维/压测）
        if requested == self.cpu_assist_model:
            plan = self._vram_plan(self.est_cpu_assist_mb)
            return self._decision("cpu_assist", self.cpu_assist_model, "explicit_cpu_assist",
                                  long_text, plan)
        if self.force_mode == "cpu_assist":
            plan = self._vram_plan(self.est_cpu_assist_mb)
            return self._decision("cpu_assist", self.cpu_assist_model, "forced_cpu_assist",
                                  long_text, plan)
        plan = self._vram_plan(self.est_text_mb)
        if plan["fits"] and time.time() >= self._assist_until:
            # 冷却期刚结束（_assist_until > 0）时要求额外滞回余量，显存未稳则继续留在 cpu-assist
            hyst_ok = (self._assist_until <= 0.0
                       or plan["free_after_switch_mb"] >= (self.est_text_mb + self.gpu_margin_mb
                                                           + self.return_hysteresis_mb))
            if hyst_ok:
                self._assist_until = 0.0
                return self._decision("gpu", self.text_model,
                                      "long_text_gpu" if long_text else "gpu", long_text, plan)
        assist = self._vram_plan(self.est_cpu_assist_mb)
        if assist["fits"]:
            self._assist_until = time.time() + self.switch_cooldown_sec
            return self._decision("cpu_assist", self.cpu_assist_model,
                                  "vram_short_cpu_assist", long_text, assist)
        # 连轻量辅助都放不下：保持文本模型（可能由 Ollama/mock 接管），不抛 500
        return self._decision("fallback", self.text_model, "vram_insufficient", long_text,
                              dict(plan, degraded=True))

    def decide_vision(self) -> dict:
        plan = self._vram_plan(self.est_vl_mb)
        if plan["fits"]:
            return self._decision("gpu", self.vl_model, "vision_gpu", False, plan)
        return self._decision("fallback", self.vl_model, "vram_insufficient_for_vision",
                              False, dict(plan, degraded=True))

    @staticmethod
    def _decision(mode: str, model: str, reason: str, long_text: bool, plan: dict) -> dict:
        return {
            "mode": mode,
            "model": model,
            "reason": reason,
            "long_text": bool(long_text),
            "allow": mode != "fallback",
            "vram": plan,
        }

    def summary(self) -> dict:
        snap = self.snapshot()
        current = self._current_loaded()
        return {
            "gpu": {
                "name": snap.get("gpu_name") or "",
                "vram_total_mb": snap.get("vram_total_mb"),
                "vram_used_mb": snap.get("vram_used_mb"),
                "vram_free_mb": snap.get("vram_free_mb"),
                "util": snap.get("gpu_util"),
            },
            "cpu": {
                "count": snap.get("cpu_count"),
                "load1": snap.get("load1"),
            },
            "ram": {
                "total_mb": snap.get("ram_total_mb"),
                "available_mb": snap.get("ram_available_mb"),
            },
            "policy": {
                "long_text_chars": self.long_text_chars,
                "gpu_margin_mb": self.gpu_margin_mb,
                "text_model": self.text_model,
                "cpu_assist_model": self.cpu_assist_model,
                "vl_model": self.vl_model,
                "current_loaded": current,
            },
        }
