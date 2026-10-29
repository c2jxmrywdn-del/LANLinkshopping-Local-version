# -*- coding: utf-8 -*-
"""硬件调度/智能模型切换策略单测（不依赖真实 GPU / llama.cpp）。"""
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.hardware_scheduler import HardwareScheduler  # noqa: E402


class FakeManager:
    def __init__(self, loaded=None):
        self.loaded = loaded

    def current_loaded(self):
        return self.loaded

    def models(self):
        # 模拟 router 可达：暴露 text/vl 两个模型（loaded 状态按需）
        return [
            {"id": "qwen2.5-7b", "kind": "text",
             "loaded": self.loaded == "qwen2.5-7b"},
            {"id": "qwen2.5-vl", "kind": "vl",
             "loaded": self.loaded == "qwen2.5-vl"},
        ]


def snap(total=8188, used=0, util=0):
    return {
        "gpu_name": "Fake GPU",
        "vram_total_mb": total,
        "vram_used_mb": used,
        "vram_free_mb": total - used,
        "gpu_util": util,
        "cpu_count": 24,
        "load1": 0.5,
        "ram_total_mb": 12000,
        "ram_available_mb": 6000,
    }


class HardwareSchedulerTest(unittest.TestCase):
    def make(self, used=0, loaded=None, **env):
        old = {}
        for k, v in env.items():
            old[k] = os.environ.get(k)
            os.environ[k] = str(v)
        try:
            return HardwareScheduler(FakeManager(loaded), snapshot_fn=lambda: snap(used=used))
        finally:
            for k, v in old.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v

    def test_short_text_runs_on_gpu(self):
        d = self.make().decide_text(100)
        self.assertEqual(d["mode"], "gpu")
        self.assertEqual(d["model"], "qwen2.5-7b")
        self.assertFalse(d["long_text"])
        self.assertTrue(d["allow"])

    def test_long_text_forces_text_model_on_gpu(self):
        d = self.make().decide_text(20000)
        self.assertEqual(d["mode"], "gpu")
        self.assertEqual(d["model"], "qwen2.5-7b")
        self.assertTrue(d["long_text"])

    def test_vision_uses_vl_when_vram_fits(self):
        d = self.make().decide_vision()
        self.assertEqual(d["mode"], "gpu")
        self.assertEqual(d["model"], "qwen2.5-vl")
        self.assertTrue(d["allow"])

    def test_vision_falls_back_when_vram_short(self):
        d = self.make(used=1800).decide_vision()
        self.assertEqual(d["mode"], "fallback")
        self.assertFalse(d["allow"])

    def test_text_uses_cpu_assist_on_slight_vram_overflow(self):
        # free_after_switch=5800MB：不够文本全 GPU（6650+250），但够 CPU 轻量辅助（5550+250）
        d = self.make(used=2388).decide_text(100)
        self.assertEqual(d["mode"], "cpu_assist")
        self.assertEqual(d["model"], "qwen2.5-7b-cpu-assist")
        self.assertTrue(d["allow"])

    def test_cooldown_keeps_cpu_assist_until_recovered(self):
        # 进入 cpu-assist 后冷却期内，即使显存恢复也不立即回 GPU（防换模抖动）
        state = {"used": 2388}
        old = {k: os.environ.get(k) for k in ("HARDWARE_SWITCH_COOLDOWN_SEC", "HARDWARE_CACHE_SEC")}
        os.environ["HARDWARE_SWITCH_COOLDOWN_SEC"] = "600"
        os.environ["HARDWARE_CACHE_SEC"] = "0"
        try:
            s = HardwareScheduler(FakeManager(None), snapshot_fn=lambda: snap(used=state["used"]))
            self.assertEqual(s.decide_text(100)["mode"], "cpu_assist")
            state["used"] = 0
            self.assertEqual(s.decide_text(100)["mode"], "cpu_assist")
            # 冷却期结束 + 滞回余量满足（8188 >= 6650+250+500）→ 回 GPU 并清冷却
            s._assist_until = time.time() - 1
            d = s.decide_text(100)
            self.assertEqual(d["mode"], "gpu")
            self.assertEqual(s._assist_until, 0.0)
        finally:
            for k, v in old.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v

    def test_hysteresis_blocks_return_when_vram_still_tight(self):
        # 冷却期结束后 free_after_switch=7000：够 GPU（6900）但不够滞回（7400）→ 留在 cpu-assist
        state = {"used": 1188}
        old = {k: os.environ.get(k) for k in ("HARDWARE_SWITCH_COOLDOWN_SEC", "HARDWARE_CACHE_SEC")}
        os.environ["HARDWARE_SWITCH_COOLDOWN_SEC"] = "600"
        os.environ["HARDWARE_CACHE_SEC"] = "0"
        try:
            s = HardwareScheduler(FakeManager(None), snapshot_fn=lambda: snap(used=state["used"]))
            s._assist_until = time.time() - 1
            d = s.decide_text(100)
            self.assertEqual(d["mode"], "cpu_assist")
            self.assertEqual(d["reason"], "vram_short_cpu_assist")
        finally:
            for k, v in old.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v

    def test_current_vl_does_not_count_as_other_usage(self):
        # 已加载 VL（7600MB）时切换文本：VL 会被卸载，所以 7600 不应算作其他进程占用
        d = self.make(used=7600, loaded="qwen2.5-vl").decide_text(100)
        self.assertEqual(d["mode"], "gpu")
        self.assertEqual(d["model"], "qwen2.5-7b")

    def test_explicit_cpu_assist_is_honored(self):
        d = self.make().decide_text(100, "qwen2.5-7b-cpu-assist")
        self.assertEqual(d["mode"], "cpu_assist")
        self.assertEqual(d["model"], "qwen2.5-7b-cpu-assist")

    def test_summary_contains_policy_and_metrics(self):
        summary = self.make().summary()
        self.assertEqual(summary["gpu"]["vram_total_mb"], 8188)
        self.assertEqual(summary["policy"]["long_text_chars"], 6000)
        self.assertEqual(summary["policy"]["cpu_assist_model"], "qwen2.5-7b-cpu-assist")


if __name__ == "__main__":
    unittest.main()
