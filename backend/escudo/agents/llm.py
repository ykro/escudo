"""The Gemini model every Escudo ADK agent uses, with retries for transient Vertex errors."""

from __future__ import annotations

from google.adk.models import Gemini
from google.genai import types

from ..config import Settings

# 429/5xx happen on shared capacity; a quick retry beats failing a claim on stage.
RETRY = types.HttpRetryOptions(attempts=3, initial_delay=1.0, max_delay=8.0, http_status_codes=[429, 500, 502, 503, 504])


def gemini(settings: Settings) -> Gemini:
    return Gemini(model=settings.gemini_model, retry_options=RETRY)
