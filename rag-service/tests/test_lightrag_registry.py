"""lightrag 存储注册表注入单测。"""
from __future__ import annotations

import pytest
from lightrag.kg import STORAGE_IMPLEMENTATIONS, STORAGES

from app.lightrag_registry import (
    CLASS_NAME,
    MODULE_PATH,
    register_redis_vector_storage,
    storage_available,
)


@pytest.fixture(autouse=True)
def _restore_registry():
    """注册是进程级副作用，测试后还原，避免污染其它测试。"""
    impls = STORAGE_IMPLEMENTATIONS["VECTOR_STORAGE"]["implementations"]
    had_impl = CLASS_NAME in impls
    had_store = CLASS_NAME in STORAGES
    prev_store = STORAGES.get(CLASS_NAME)
    if had_impl:
        impls.remove(CLASS_NAME)
    STORAGES.pop(CLASS_NAME, None)
    yield
    impls[:] = [i for i in impls if i != CLASS_NAME]
    STORAGES.pop(CLASS_NAME, None)
    if had_impl:
        impls.append(CLASS_NAME)
    if had_store:
        STORAGES[CLASS_NAME] = prev_store


def test_register_injects_into_both_registries():
    assert CLASS_NAME not in STORAGE_IMPLEMENTATIONS["VECTOR_STORAGE"]["implementations"]
    assert CLASS_NAME not in STORAGES

    assert register_redis_vector_storage() is True

    assert CLASS_NAME in STORAGE_IMPLEMENTATIONS["VECTOR_STORAGE"]["implementations"]
    assert STORAGES[CLASS_NAME] == MODULE_PATH
    assert MODULE_PATH == "app.redis_vector_store"
    assert storage_available() is True


def test_register_is_idempotent():
    assert register_redis_vector_storage() is True
    assert register_redis_vector_storage() is True
    impls = STORAGE_IMPLEMENTATIONS["VECTOR_STORAGE"]["implementations"]
    assert impls.count(CLASS_NAME) == 1
    assert list(STORAGES).count(CLASS_NAME) == 1


def test_storage_available_false_before_register():
    assert storage_available() is False


def test_register_returns_false_on_broken_registry(monkeypatch):
    monkeypatch.setitem(STORAGE_IMPLEMENTATIONS, "VECTOR_STORAGE", {"implementations": None})
    assert register_redis_vector_storage() is False


def test_storage_available_returns_false_on_broken_registry(monkeypatch):
    monkeypatch.setitem(STORAGE_IMPLEMENTATIONS, "VECTOR_STORAGE", {"implementations": None})
    assert storage_available() is False
