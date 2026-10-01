"""The Escudo API service (`uvicorn escudo.main:create_app --factory`): one FastAPI app, every surface mounted over the same domain.

    /policy, /claims, ...              REST (Android, web)          adapters/rest.py
    /mcp                               MCP streamable HTTP          adapters/mcp_server.py
    /a2a/escudo|tow|repair             A2A + Agent Cards            agents/
    /assistant/live                    voice (ADK + Gemini Live)    adapters/assistant_live.py
"""

from __future__ import annotations

import contextlib
import logging
import os

os.environ.setdefault("ADK_SUPPRESS_EXPERIMENTAL_FEATURE_WARNINGS", "true")
os.environ.setdefault("GOOGLE_GENAI_USE_ENTERPRISE", "true")

from fastapi import FastAPI  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402

from . import seed  # noqa: E402
from .adapters import assistant_live, rest  # noqa: E402
from .adapters.mcp_server import build_mcp  # noqa: E402
from .agents import escudo_agent, repair_agent, tow_agent  # noqa: E402
from .agents.hosting import PARTNER_KEY_HEADER, a2a_routes  # noqa: E402
from .config import Settings  # noqa: E402
from .errors import EscudoError  # noqa: E402
from .services import Services, build_services  # noqa: E402

logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(levelname)s %(name)s: %(message)s")


def create_app(services: Services | None = None) -> FastAPI:
    services = services or build_services(Settings.from_env())
    settings = services.settings
    os.environ.setdefault("GOOGLE_CLOUD_PROJECT", settings.project_id)
    os.environ.setdefault("GOOGLE_CLOUD_LOCATION", settings.location)
    mcp = build_mcp(services)
    mcp_app = mcp.streamable_http_app(
        streamable_http_path="/mcp",
        stateless_http=True,  # any Cloud Run instance can answer any request
        json_response=True,
        host="0.0.0.0",  # we sit behind Cloud Run's front end, not on localhost
    )

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI):  # noqa: ANN202
        if await seed.seed_if_empty(services.repo):
            logging.getLogger(__name__).info("seeded an empty database")
        async with mcp.session_manager.run():  # a mounted app's own lifespan never runs
            yield

    app = FastAPI(
        title="Escudo API",
        version="1.0.0",
        description="Aseguradora de autos lista para agentes: REST, MCP, A2A y voz sobre las mismas capacidades.",
        lifespan=lifespan,
    )
    app.state.services = services
    app.state.assistant_hub = assistant_live.AssistantHub()
    app.add_exception_handler(EscudoError, rest.escudo_error_handler)
    app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"], expose_headers=["X-Reset-Seconds"])
    app.include_router(rest.router)
    app.include_router(assistant_live.router)

    if settings.a2a_shared_secret:
        app.middleware("http")(partner_key_guard(settings.a2a_shared_secret))

    # A2A: three agents, each with its own Agent Card under its own prefix.
    app.router.routes.extend(a2a_routes(tow_agent.build_agent(settings, services.repo), tow_agent.card(settings), tow_agent.PREFIX))
    app.router.routes.extend(a2a_routes(repair_agent.build_agent(settings, services.repo), repair_agent.card(settings), repair_agent.PREFIX))
    app.router.routes.extend(a2a_routes(escudo_agent.build_a2a_agent(services), escudo_agent.card(settings), escudo_agent.PREFIX))

    # MCP last: it is mounted at the root and only answers /mcp (+ its OAuth metadata).
    app.mount("/", mcp_app)
    return app


PARTNER_PREFIXES = (tow_agent.PREFIX, repair_agent.PREFIX)


def partner_key_guard(secret: str):  # noqa: ANN201
    """Tow and Repair only answer callers holding the shared secret. Their Agent Cards stay
    public (discovery must work) and declare the scheme; the Escudo agent's own endpoint stays
    open because customers' agents are meant to reach it."""
    import hmac

    from fastapi.responses import JSONResponse

    async def guard(request, call_next):  # noqa: ANN001, ANN202
        path = request.url.path.rstrip("/")
        if request.method == "POST" and path in PARTNER_PREFIXES:
            if not hmac.compare_digest(request.headers.get(PARTNER_KEY_HEADER, ""), secret):
                return JSONResponse({"error": "partner_key_required", "message": "Este agente solo atiende a socios de Escudo."}, status_code=401)
        return await call_next(request)

    return guard
