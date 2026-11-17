"""config.Settings 单测：Redis URI 拼装与 URL 编码。"""
from __future__ import annotations

import pytest

from app.config import Settings


@pytest.fixture(autouse=True)
def _clear_env(monkeypatch):
    for name in (
        "REDIS_URI", "REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD", "RAG_REDIS_DB",
    ):
        monkeypatch.delenv(name, raising=False)


def test_explicit_redis_uri_wins(monkeypatch):
    monkeypatch.setenv("REDIS_URI", "redis://cache.internal:6380/3")
    monkeypatch.setenv("REDIS_HOST", "ignored")
    monkeypatch.setenv("REDIS_PASSWORD", "ignored")
    assert Settings().resolved_redis_uri() == "redis://cache.internal:6380/3"


def test_uri_built_from_parts_without_password(monkeypatch):
    monkeypatch.setenv("REDIS_HOST", "redis")
    monkeypatch.setenv("REDIS_PORT", "6379")
    monkeypatch.setenv("RAG_REDIS_DB", "2")
    assert Settings().resolved_redis_uri() == "redis://redis:6379/2"


def test_uri_encodes_password_special_chars(monkeypatch):
    """base64 风格密码含 + / =，必须 URL 编码，否则 redis-py 报 Invalid URL。"""
    monkeypatch.setenv("REDIS_HOST", "redis")
    monkeypatch.setenv("REDIS_PORT", "6379")
    monkeypatch.setenv("REDIS_PASSWORD", "ab+cd/ef=")
    uri = Settings().resolved_redis_uri()
    assert uri == "redis://:ab%2Bcd%2Fef%3D@redis:6379/0"
    assert "+" not in uri.split("@")[0]
    assert uri.count("@") == 1


def test_uri_defaults():
    assert Settings().resolved_redis_uri() == "redis://redis:6379/0"


def test_vector_backend_is_lowercased(monkeypatch):
    monkeypatch.setenv("RAG_VECTOR_BACKEND", "  ReDiS  ")
    assert Settings().vector_backend == "redis"


def test_non_numeric_int_env_falls_back_to_default(monkeypatch):
    """端口/超时等被写成非数字时必须回退默认值，而不是启动即崩。"""
    monkeypatch.setenv("RAG_PORT", "not-a-port")
    monkeypatch.setenv("RAG_EMBED_DIM", "10x24")
    s = Settings()
    assert s.port == 8093
    assert s.embed_dim == 1024
