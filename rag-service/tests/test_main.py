"""HTTP 层单测：鉴权、fail-closed、/health 不泄露内部配置。"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import main as main_mod

SECRET = "test-internal-secret"


@pytest.fixture
def client():
    return TestClient(main_mod.app)


@pytest.fixture
def secret(monkeypatch):
    monkeypatch.setattr(main_mod.settings, "internal_secret", SECRET)
    return SECRET


FULL_HEALTH = {
    "ok": True,
    "initialized": True,
    "workingDir": "/data/rag_storage",
    "llmBaseUrl": "http://lanlink-ai-gateway:8090/v1",
    "llmModel": "qwen2.5-7b",
    "visionModel": "qwen2.5-vl",
    "embedModel": "bge-m3",
    "embedDim": 1024,
    "vectorBackend": "RedisVectorStorage",
    "vector": {"backend": "redis"},
}


@pytest.fixture
def stub_engine(monkeypatch):
    calls = []

    async def fake_health():
        return dict(FULL_HEALTH)

    async def fake_ingest(doc_id, content_list, file_path=None):
        calls.append(("ingest", doc_id, content_list, file_path))
        return {"docId": doc_id, "chunks": 1, "status": "processed"}

    async def fake_query(question, mode="hybrid", top_k=None):
        calls.append(("query", question, mode, top_k))
        return "answer"

    async def fake_adelete(doc_id, purge_query_cache=True):
        calls.append(("adelete", doc_id, purge_query_cache))
        return {
            "docId": doc_id,
            "existed": True,
            "deleted": {"text_chunks": 2, "graph_nodes": 1},
            "residual": False,
            "warnings": [],
        }

    monkeypatch.setattr(main_mod.engine, "health", fake_health)
    monkeypatch.setattr(main_mod.engine, "ingest", fake_ingest)
    monkeypatch.setattr(main_mod.engine, "query", fake_query)
    monkeypatch.setattr(main_mod.engine, "adelete", fake_adelete)
    return calls


def test_health_needs_no_secret_and_leaks_nothing(client, stub_engine, monkeypatch):
    monkeypatch.setattr(main_mod.settings, "internal_secret", SECRET)
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body == {"ok": True, "initialized": True}
    for leaked in ("llmBaseUrl", "embedModel", "llmModel", "visionModel", "workingDir", "vector"):
        assert leaked not in body


def test_health_reports_failure_without_details(client, monkeypatch):
    async def failing():
        return {"ok": False, "error": "redis down"}

    monkeypatch.setattr(main_mod.engine, "health", failing)
    body = client.get("/health").json()
    assert body == {"ok": False, "initialized": False}
    assert "error" not in body


def test_detail_requires_secret(client, stub_engine, secret):
    assert client.get("/detail").status_code == 403
    assert client.get("/detail", headers={"X-Internal-Secret": "wrong"}).status_code == 403

    resp = client.get("/detail", headers={"X-Internal-Secret": secret})
    assert resp.status_code == 200
    body = resp.json()
    assert body["vectorBackend"] == "RedisVectorStorage"
    assert body["embedModel"] == "bge-m3"


@pytest.mark.parametrize(
    ("path", "payload"),
    [
        ("/ingest", {"docId": "d1", "contentList": [{"type": "text", "text": "hi"}]}),
        ("/query", {"question": "你好"}),
    ],
)
def test_write_endpoints_require_secret(client, stub_engine, secret, path, payload):
    assert client.post(path, json=payload).status_code == 403
    assert client.post(path, json=payload, headers={"X-Internal-Secret": "wrong"}).status_code == 403
    assert stub_engine == []


@pytest.mark.parametrize(
    ("method", "path", "payload"),
    [
        ("post", "/ingest", {"docId": "d1", "contentList": [{"type": "text", "text": "hi"}]}),
        ("post", "/query", {"question": "你好"}),
        ("get", "/detail", None),
    ],
)
def test_missing_configured_secret_fails_closed(client, stub_engine, monkeypatch, method, path, payload):
    """RAG_INTERNAL_SECRET 为空 → 503，即使请求带了任意密钥。"""
    monkeypatch.setattr(main_mod.settings, "internal_secret", "")
    kwargs = {"json": payload} if payload is not None else {}
    resp = getattr(client, method)(path, **kwargs)
    assert resp.status_code == 503
    assert "RAG_INTERNAL_SECRET" in resp.json()["detail"]


def test_ingest_passes_through_with_valid_secret(client, stub_engine, secret):
    resp = client.post(
        "/ingest",
        json={"docId": "d1", "contentList": [{"type": "text", "text": "hi"}], "filePath": "a.pdf"},
        headers={"X-Internal-Secret": secret},
    )
    assert resp.status_code == 200
    assert resp.json()["docId"] == "d1"
    assert stub_engine == [("ingest", "d1", [{"type": "text", "text": "hi"}], "a.pdf")]


def test_query_passes_through_with_valid_secret(client, stub_engine, secret):
    resp = client.post(
        "/query",
        json={"question": "你好", "mode": "hybrid", "topK": 5},
        headers={"X-Internal-Secret": secret},
    )
    assert resp.status_code == 200
    assert resp.json() == {"answer": "answer", "mode": "hybrid"}
    assert stub_engine == [("query", "你好", "hybrid", 5)]


def test_ingest_maps_value_error_to_400(client, secret, monkeypatch):
    async def bad_ingest(*_a, **_k):
        raise ValueError("content_list 不能为空")

    monkeypatch.setattr(main_mod.engine, "ingest", bad_ingest)
    resp = client.post(
        "/ingest",
        json={"docId": "d1", "contentList": [{"type": "text", "text": "hi"}]},
        headers={"X-Internal-Secret": secret},
    )
    assert resp.status_code == 400


def test_ingest_maps_unexpected_error_to_500(client, secret, monkeypatch):
    async def boom(*_a, **_k):
        raise RuntimeError("lightrag exploded")

    monkeypatch.setattr(main_mod.engine, "ingest", boom)
    resp = client.post(
        "/ingest",
        json={"docId": "d1", "contentList": [{"type": "text", "text": "hi"}]},
        headers={"X-Internal-Secret": secret},
    )
    assert resp.status_code == 500
    assert "索引失败" in resp.json()["detail"]


def test_query_maps_unexpected_error_to_500(client, secret, monkeypatch):
    async def boom(*_a, **_k):
        raise RuntimeError("llm timeout")

    monkeypatch.setattr(main_mod.engine, "query", boom)
    resp = client.post(
        "/query",
        json={"question": "你好"},
        headers={"X-Internal-Secret": secret},
    )
    assert resp.status_code == 500
    assert "检索失败" in resp.json()["detail"]


def test_docs_are_disabled(client):
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404


# ---------------------------------------------------------------- 删除端点


def test_delete_doc_requires_secret(client, stub_engine, secret):
    assert client.delete("/doc/d1").status_code == 403
    assert client.delete("/doc/d1", headers={"X-Internal-Secret": "wrong"}).status_code == 403
    assert stub_engine == []


def test_delete_doc_fails_closed_without_configured_secret(client, stub_engine, monkeypatch):
    monkeypatch.setattr(main_mod.settings, "internal_secret", "")
    resp = client.delete("/doc/d1", headers={"X-Internal-Secret": "anything"})
    assert resp.status_code == 503
    assert "RAG_INTERNAL_SECRET" in resp.json()["detail"]


def test_delete_doc_passes_through_with_valid_secret(client, stub_engine, secret):
    resp = client.delete("/doc/d1", headers={"X-Internal-Secret": secret})
    assert resp.status_code == 200
    body = resp.json()
    assert body["docId"] == "d1"
    assert body["residual"] is False
    assert body["deleted"] == {"text_chunks": 2, "graph_nodes": 1}
    assert stub_engine == [("adelete", "d1", True)]


def test_delete_doc_is_idempotent_for_unknown_doc(client, secret, monkeypatch):
    """文档不存在时仍返回 200：重复删除不能报错。"""

    async def fake_adelete(doc_id, purge_query_cache=True):
        return {
            "docId": doc_id, "existed": False, "deleted": {},
            "residual": False, "warnings": [],
        }

    monkeypatch.setattr(main_mod.engine, "adelete", fake_adelete)
    resp = client.delete("/doc/missing", headers={"X-Internal-Secret": secret})
    assert resp.status_code == 200
    assert resp.json()["existed"] is False


def test_delete_doc_maps_empty_id_to_400(client, stub_engine, secret):
    # 路径参数为纯空白时也要拒绝，避免下游把它当成合法 docId
    resp = client.delete("/doc/%20%20%20", headers={"X-Internal-Secret": secret})
    assert resp.status_code == 400
    assert stub_engine == []


def test_delete_doc_returns_500_when_residual(client, secret, monkeypatch):
    """复核仍能查到残留时必须报错，不能返回 200 假装删干净。"""

    async def fake_adelete(doc_id, purge_query_cache=True):
        return {
            "docId": doc_id,
            "existed": True,
            "deleted": {"text_chunks": 0},
            "residual": True,
            "warnings": ["doc_status 仍存在该文档记录"],
        }

    monkeypatch.setattr(main_mod.engine, "adelete", fake_adelete)
    resp = client.delete("/doc/d1", headers={"X-Internal-Secret": secret})
    assert resp.status_code == 500
    detail = resp.json()["detail"]
    assert detail["docId"] == "d1"
    assert detail["warnings"]


def test_delete_doc_maps_unexpected_error_to_500(client, secret, monkeypatch):
    async def boom(*_a, **_k):
        raise RuntimeError("lightrag exploded")

    monkeypatch.setattr(main_mod.engine, "adelete", boom)
    resp = client.delete("/doc/d1", headers={"X-Internal-Secret": secret})
    assert resp.status_code == 500
    assert "删除失败" in resp.json()["detail"]


# ---------------------------------------------------------------- 缓存清理端点


def test_purge_cache_requires_secret(client, secret, monkeypatch):
    called = []
    monkeypatch.setattr(main_mod.engine, "purge_query_cache", lambda: called.append(1) or 3)

    assert client.post("/purge-cache").status_code == 403
    assert client.post("/purge-cache", headers={"X-Internal-Secret": "wrong"}).status_code == 403
    assert called == []

    resp = client.post("/purge-cache", headers={"X-Internal-Secret": secret})
    assert resp.status_code == 200
    assert resp.json() == {"removed": 3}
    assert called == [1]


def test_purge_cache_fails_closed_without_configured_secret(client, monkeypatch):
    monkeypatch.setattr(main_mod.settings, "internal_secret", "")
    resp = client.post("/purge-cache", headers={"X-Internal-Secret": "anything"})
    assert resp.status_code == 503


def test_purge_cache_maps_error_to_500(client, secret, monkeypatch):
    def boom():
        raise RuntimeError("disk full")

    monkeypatch.setattr(main_mod.engine, "purge_query_cache", boom)
    resp = client.post("/purge-cache", headers={"X-Internal-Secret": secret})
    assert resp.status_code == 500
    assert "清理缓存失败" in resp.json()["detail"]
