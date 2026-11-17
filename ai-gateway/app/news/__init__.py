# -*- coding: utf-8 -*-
"""资讯处理管线包：结构化抓取/指纹/判重/路由。"""
from .dedup import Deduplicator  # noqa: F401
from .fingerprint import body_fingerprint  # noqa: F401

__all__ = ["Deduplicator", "body_fingerprint"]
