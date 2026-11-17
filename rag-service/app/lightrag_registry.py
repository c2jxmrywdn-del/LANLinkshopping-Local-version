"""把自定义 Redis 向量存储注册进 LightRAG 的存储注册表。

为什么必须显式注册
------------------
`lightrag/lightrag.py:1177 _get_storage_class()` 走两条路：
  1. 硬编码的 4 个默认类（JsonKVStorage / NanoVectorDBStorage /
     NetworkXStorage / JsonDocStatusStorage）；
  2. 其余一律查 `lightrag.kg.STORAGES[storage_name]` 拿模块路径，再
     `lazy_external_import()` 动态导入。

而且 `LightRAG.__post_init__` 里会先调
`verify_storage_implementation("VECTOR_STORAGE", storage_name)`，
它要求类名必须出现在 `STORAGE_IMPLEMENTATIONS["VECTOR_STORAGE"]["implementations"]`
白名单里——不在白名单就直接抛 `ValueError`，连导入都不会发生。

所以「只写一个适配器类」是不够的：必须同时改这两个模块级字典。
这里用 patch 的方式在运行时注入，避免 fork 上游包（升级 lightrag 时会丢）。
"""
from __future__ import annotations

import logging

from lightrag.kg import STORAGE_IMPLEMENTATIONS, STORAGES

log = logging.getLogger("rag.registry")

CLASS_NAME = "RedisVectorStorage"
"""
必须是**绝对**模块路径（不带前导点）。

`lazy_external_import()` 内部是 `importlib.import_module(module_name, package=package)`，
而 `package` 取自调用方模块 `lightrag.lightrag` 的 `__package__`（即 `"lightrag"`）。
写相对路径 `.app.redis_vector_store` 会被解析成 `lightrag.app.redis_vector_store`，
ImportError。

本服务的启动命令是 `uvicorn app.main:app`（cwd=/app），`app` 是顶层包，
所以 `app.redis_vector_store` 可直接导入；绝对名会让 importlib 忽略 package 参数。
"""
MODULE_PATH = "app.redis_vector_store"


def register_redis_vector_storage() -> bool:
    """注册 RedisVectorStorage；重复调用安全（幂等）。

    Returns:
        True 表示注册成功（首次或已存在），False 表示注册失败。
    """
    try:
        impls = STORAGE_IMPLEMENTATIONS["VECTOR_STORAGE"]["implementations"]
        if CLASS_NAME not in impls:
            impls.append(CLASS_NAME)
            log.info("[rag-registry] registered %s into STORAGE_IMPLEMENTATIONS", CLASS_NAME)

        if CLASS_NAME not in STORAGES:
            STORAGES[CLASS_NAME] = MODULE_PATH
            log.info("[rag-registry] registered %s -> %s in STORAGES", CLASS_NAME, MODULE_PATH)

        return True
    except Exception as exc:  # noqa: BLE001 - 注册失败要显式暴露，不能静默降级
        log.error("[rag-registry] 注册 RedisVectorStorage 失败: %s", exc)
        return False


def storage_available() -> bool:
    """注册表里是否已有 RedisVectorStorage（供 /health 暴露）。"""
    try:
        return (
            CLASS_NAME in STORAGE_IMPLEMENTATIONS["VECTOR_STORAGE"]["implementations"]
            and CLASS_NAME in STORAGES
        )
    except Exception:  # noqa: BLE001
        return False
