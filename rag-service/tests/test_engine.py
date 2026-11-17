"""RagEngine 后端选择与健康信息单测（不初始化 RAGAnything）。"""
from __future__ import annotations

import os
import types

import pytest

from app import engine as engine_mod
from app.engine import RagEngine


def _settings(backend: str, uri: str = "redis://redis:6379/0"):
    return types.SimpleNamespace(
        vector_backend=backend,
        resolved_redis_uri=lambda: uri,
        working_dir="/tmp/rag",
    )


@pytest.fixture
def fake_registry(monkeypatch):
    calls = {"register": 0}

    def _register():
        calls["register"] += 1
        return True

    monkeypatch.setattr(engine_mod, "register_redis_vector_storage", _register)
    monkeypatch.setattr(engine_mod, "storage_available", lambda: True)
    return calls


@pytest.mark.parametrize("value", ["nano", "nanovectordb", "nano_vector_db", "  NANO  "])
def test_resolve_nano_backend(monkeypatch, fake_registry, value):
    monkeypatch.setattr(engine_mod, "settings", _settings(value))
    assert RagEngine()._resolve_vector_backend() == "NanoVectorDBStorage"
    assert fake_registry["register"] == 0


@pytest.mark.parametrize("value", ["redis", "redisvector", "redis_vector", " Redis "])
def test_resolve_redis_backend(monkeypatch, fake_registry, value):
    monkeypatch.setattr(engine_mod, "settings", _settings(value))
    monkeypatch.delenv("REDIS_URI", raising=False)
    assert RagEngine()._resolve_vector_backend() == "RedisVectorStorage"
    assert fake_registry["register"] == 1
    # 适配器读 os.environ['REDIS_URI']，引擎必须把拼好的串写回环境
    assert os.environ["REDIS_URI"] == "redis://redis:6379/0"
    monkeypatch.delenv("REDIS_URI", raising=False)


def test_resolve_unknown_backend_raises(monkeypatch, fake_registry):
    monkeypatch.setattr(engine_mod, "settings", _settings("chroma"))
    with pytest.raises(ValueError, match="RAG_VECTOR_BACKEND"):
        RagEngine()._resolve_vector_backend()


def test_resolve_redis_raises_when_registration_fails(monkeypatch):
    monkeypatch.setattr(engine_mod, "settings", _settings("redis"))
    monkeypatch.setattr(engine_mod, "register_redis_vector_storage", lambda: False)
    with pytest.raises(RuntimeError, match="注册失败"):
        RagEngine()._resolve_vector_backend()


def test_resolve_redis_raises_when_availability_check_fails(monkeypatch):
    monkeypatch.setattr(engine_mod, "settings", _settings("redis"))
    monkeypatch.setattr(engine_mod, "register_redis_vector_storage", lambda: True)
    monkeypatch.setattr(engine_mod, "storage_available", lambda: False)
    with pytest.raises(RuntimeError, match="校验失败"):
        RagEngine()._resolve_vector_backend()


def test_vector_health_non_redis_backend():
    eng = RagEngine()
    eng._vector_backend = "NanoVectorDBStorage"
    assert eng._vector_health() == {"backend": "NanoVectorDBStorage"}


def test_vector_health_uninitialized():
    # 构造时 _vector_backend 初值是 "unknown"，与「未初始化」同义
    assert RagEngine()._vector_health() == {"backend": "unknown"}


def test_vector_health_redis_lists_three_indexes():
    eng = RagEngine()
    eng._vector_backend = "RedisVectorStorage"
    eng._rag = None
    out = eng._vector_health()
    assert out["backend"] == "redis"
    assert out["indexes"] == {
        "entities": "rag-idx:_:entities",
        "relationships": "rag-idx:_:relationships",
        "chunks": "rag-idx:_:chunks",
    }
