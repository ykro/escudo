"""Wiring: which implementation of each dependency runs (cloud or fake)."""

from __future__ import annotations

from dataclasses import dataclass

from .auth import Authenticator
from .config import Settings
from .damage_assessor import Assessor, GeminiAssessor, MockAssessor
from .events import EventWriter
from .push import FcmPush, Push, RecordingPush
from .repository import (
    BlobStore,
    FirestoreDocumentStore,
    GcsBlobStore,
    MemoryBlobStore,
    MemoryDocumentStore,
    Repository,
)


@dataclass
class Services:
    settings: Settings
    repo: Repository
    blobs: BlobStore
    events: EventWriter
    push: Push
    assessor: Assessor
    auth: Authenticator
    escudo_agent: "EscudoAgent"  # noqa: F821 - set right after construction


def build_services(settings: Settings) -> Services:
    from .agents.escudo_agent import EscudoAgent

    settings.require_models()
    if settings.store == "memory":
        repo = Repository(MemoryDocumentStore())
        blobs: BlobStore = MemoryBlobStore(settings.claims_bucket)
    else:
        repo = Repository(FirestoreDocumentStore(settings.project_id, settings.firestore_database))
        blobs = GcsBlobStore(settings.project_id, settings.claims_bucket)

    events = EventWriter(repo, settings.contracts_dir / "events.schema.json")
    push: Push = RecordingPush() if settings.mock_ai else FcmPush(repo, settings.project_id)
    assessor: Assessor = (
        MockAssessor() if settings.mock_ai else GeminiAssessor(settings.project_id, settings.location, settings.gemini_model)
    )
    services = Services(
        settings=settings,
        repo=repo,
        blobs=blobs,
        events=events,
        push=push,
        assessor=assessor,
        auth=Authenticator(settings),
        escudo_agent=None,  # type: ignore[arg-type]
    )
    services.escudo_agent = EscudoAgent(settings, events)
    return services
