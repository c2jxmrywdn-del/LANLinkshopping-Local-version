# -*- coding: utf-8 -*-
"""单测全局环境初始化：保证离线运行（无论测试执行顺序）。"""
import os

os.environ["LLM_BACKEND"] = "mock"
os.environ["LLAMA_CPP_URL"] = ""
os.environ["MODEL_MANAGER_URL"] = ""
for _name in ("REDIS_URL", "REDIS_URLS", "REDIS_PASSWORD"):
    os.environ.pop(_name, None)
