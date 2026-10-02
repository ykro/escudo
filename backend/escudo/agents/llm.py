"""The Gemini model every Escudo ADK agent uses, with retries for transient Vertex errors."""

from __future__ import annotations

from google.adk.models import Gemini
from google.genai import types

from ..config import Settings

# 429/5xx happen on shared capacity; a quick retry beats failing a claim on stage.
RETRY = types.HttpRetryOptions(attempts=3, initial_delay=1.0, max_delay=8.0, http_status_codes=[429, 500, 502, 503, 504])


def gemini(settings: Settings, timeout_seconds: float | None = None) -> Gemini:
    """timeout_seconds bounds each model call: on 2026-10-02 one chat call stalled for 2 minutes. A
    timed-out turn surfaces as an error frame and the conversation continues with the next message."""
    if timeout_seconds is None:
        return Gemini(model=settings.gemini_model, retry_options=RETRY)
    options = types.HttpOptions(timeout=int(timeout_seconds * 1000), retry_options=RETRY)
    return Gemini(model=settings.gemini_model, retry_options=RETRY,
                  client_kwargs={"vertexai": True, "project": settings.project_id, "location": settings.location,
                                 "http_options": options})

