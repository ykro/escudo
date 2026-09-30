"""Offline test harness: MOCK_AI + MOCK_AUTH + in-memory store, everything in-process.

A2A calls from the Escudo agent and MCP calls from test clients are routed to
the same ASGI app, so the real adapters run end to end without a network.
"""

from __future__ import annotations

import io
import os

os.environ.setdefault("ADK_SUPPRESS_EXPERIMENTAL_FEATURE_WARNINGS", "true")

import httpx
import httpx2
import pytest
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from escudo.config import Settings
from escudo.main import create_app
from escudo.services import build_services

BASE = "http://escudo.test"


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"



def tiny_jpeg() -> bytes:
    try:
        from PIL import Image

        buf = io.BytesIO()
        Image.new("RGB", (8, 8), (180, 180, 190)).save(buf, "JPEG")
        return buf.getvalue()
    except ImportError:  # pragma: no cover
        return b"\xff\xd8\xff\xe0" + b"\x00" * 64


def make_settings(**overrides) -> Settings:
    base = dict(
        mock_ai=True,
        mock_auth=True,
        store="memory",
        public_base_url=BASE,
        a2a_peers=(f"{BASE}/a2a/tow", f"{BASE}/a2a/repair"),
        admin_token="test-admin",
    )
    return Settings(**{**base, **overrides})


@pytest.fixture
async def app():
    services = build_services(make_settings())
    application = create_app(services)
    services.escudo_agent.client_factory = lambda: httpx.AsyncClient(
        transport=httpx.ASGITransport(app=application), base_url=BASE, timeout=30
    )
    async with application.router.lifespan_context(application):
        yield application


@pytest.fixture
def services(app):
    return app.state.services


@pytest.fixture
async def http(app):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=BASE) as client:
        yield client


@pytest.fixture
async def mcp(app):
    transport = httpx2.AsyncClient(transport=httpx2.ASGITransport(app=app), headers={"X-Escudo-Actor": "gemini"})
    async with Client(streamable_http_client(f"{BASE}/mcp", http_client=transport)) as client:
        yield client
