"""Deterministic ADK agent for MOCK_AI=1: same tool, same A2A wiring, no model call.

It reads the JSON request, calls the partner's tool function directly and
answers with JSON — so tests exercise the real A2A path offline.
"""

from __future__ import annotations

import json
from typing import Any, AsyncGenerator, Awaitable, Callable

from google.adk.agents import BaseAgent
from google.adk.agents.invocation_context import InvocationContext
from google.adk.events import Event
from google.genai import types
from pydantic import ConfigDict


class ScriptedAgent(BaseAgent):
    model_config = ConfigDict(arbitrary_types_allowed=True)
    handler: Callable[[dict[str, Any]], Awaitable[Any]]

    async def _run_async_impl(self, ctx: InvocationContext) -> AsyncGenerator[Event, None]:
        text = "".join(p.text or "" for p in (ctx.user_content.parts if ctx.user_content else []))
        try:
            request = json.loads(text)
        except json.JSONDecodeError:
            request = {"text": text}
        result = await self.handler(request)
        yield Event(
            author=self.name,
            invocation_id=ctx.invocation_id,
            content=types.Content(role="model", parts=[types.Part(text=json.dumps(result, ensure_ascii=False))]),
        )
