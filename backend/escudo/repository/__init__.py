"""Persistence behind one typed repository.

`Repository` holds every query the domain needs. It talks to a tiny
`DocumentStore` interface with two implementations: Firestore (db `escudo`)
in the cloud and an in-memory fake for tests and `MOCK_AI=1` rehearsals.
Capabilities never import Firestore directly.
"""

from .blobs import BlobStore, GcsBlobStore, MemoryBlobStore
from .documents import DocumentStore, FirestoreDocumentStore, MemoryDocumentStore
from .repository import Repository

__all__ = [
    "BlobStore",
    "GcsBlobStore",
    "MemoryBlobStore",
    "DocumentStore",
    "FirestoreDocumentStore",
    "MemoryDocumentStore",
    "Repository",
]
