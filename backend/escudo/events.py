"""Presenter timeline events.

Every step (tool call, approval pause, A2A hop, assessment) becomes one event
in Firestore `events`. Each event is validated against the shared contract
`contracts/events.schema.json` before it is written, so the web presenter view
can trust the shape no matter which component wrote it.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker

from .errors import InvalidInput
from .models import new_event_id, now_iso
from .repository import Repository

log = logging.getLogger(__name__)


class EventWriter:
    def __init__(self, repo: Repository, schema_path: Path) -> None:
        self._repo = repo
        schema = json.loads(schema_path.read_text())
        self._validator = Draft202012Validator(schema, format_checker=FormatChecker())
        self.enum = {
            name: set(schema["properties"][name]["enum"]) for name in ("actor", "surface", "kind")
        }

    def validate(self, event: dict[str, Any]) -> None:
        errors = sorted(self._validator.iter_errors(event), key=lambda e: list(e.path))
        if errors:
            where = "/".join(str(p) for p in errors[0].path) or "(root)"
            raise InvalidInput("invalid_event", f"Evento inválido en {where}: {errors[0].message}")

    async def emit(
        self,
        *,
        actor: str,
        surface: str,
        kind: str,
        summary: str,
        run_id: str,
        claim_id: str | None = None,
        tool: str | None = None,
        autonomy: str | None = None,
        approval_id: str | None = None,
        reason: str | None = None,
        data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        event = {
            "id": new_event_id(),
            "ts": now_iso(),
            "run_id": run_id,
            "claim_id": claim_id,
            "actor": actor,
            "surface": surface,
            "kind": kind,
            "tool": tool,
            "summary": summary,
            "autonomy": autonomy,
            "approval_id": approval_id,
            "reason": reason,
            "data": data or {},
        }
        self.validate(event)  # a malformed event is a bug in our code: fail loudly
        await self._store(event)
        return event

    async def append_external(self, event: dict[str, Any]) -> dict[str, Any]:
        """Events posted by Android/web: narrative `message`s only; the server owns `id` and `ts`."""
        if event.get("kind") != "message":
            raise InvalidInput("event_kind_not_allowed", "Las apps solo pueden publicar eventos de tipo message.")
        event = {**event, "id": new_event_id(), "ts": now_iso()}
        self.validate(event)
        await self._store(event)
        return event

    async def _store(self, event: dict[str, Any]) -> None:
        try:
            await self._repo.add_event(event)
        except Exception:  # the timeline must never break the customer's flow
            log.exception("could not store event %s", event["id"])
