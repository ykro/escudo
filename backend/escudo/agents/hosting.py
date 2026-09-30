"""Serve an ADK agent over A2A with its Agent Card, under a path prefix.

Card:  {prefix}/.well-known/agent-card.json   (A2A 1.0 well-known path)
RPC:   {prefix}                                (JSON-RPC binding)
"""

from __future__ import annotations

from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import AgentCapabilities, AgentCard, AgentInterface, AgentProvider, AgentSkill
from a2a.utils.constants import AGENT_CARD_WELL_KNOWN_PATH, PROTOCOL_VERSION_CURRENT, TransportProtocol
from google.adk.a2a.executor.a2a_agent_executor import A2aAgentExecutor
from google.adk.agents import BaseAgent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from starlette.routing import BaseRoute


def build_card(
    *,
    name: str,
    description: str,
    url: str,
    organization: str,
    skills: list[AgentSkill],
    version: str = "1.0.0",
) -> AgentCard:
    return AgentCard(
        name=name,
        description=description,
        version=version,
        provider=AgentProvider(organization=organization, url=url),
        supported_interfaces=[
            AgentInterface(url=url, protocol_binding=TransportProtocol.JSONRPC.value, protocol_version=PROTOCOL_VERSION_CURRENT)
        ],
        capabilities=AgentCapabilities(streaming=False),
        default_input_modes=["application/json", "text/plain"],
        default_output_modes=["application/json"],
        skills=skills,
    )


def a2a_routes(agent: BaseAgent, card: AgentCard, prefix: str) -> list[BaseRoute]:
    runner = Runner(
        app_name=agent.name,
        agent=agent,
        session_service=InMemorySessionService(),
        auto_create_session=True,
    )
    handler = DefaultRequestHandler(
        agent_executor=A2aAgentExecutor(runner=runner),
        task_store=InMemoryTaskStore(),
        agent_card=card,
    )
    return [
        *create_agent_card_routes(card, card_url=f"{prefix}{AGENT_CARD_WELL_KNOWN_PATH}"),
        *create_jsonrpc_routes(handler, prefix),
    ]
