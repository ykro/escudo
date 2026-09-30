"""Document stores: the only code that knows about Firestore."""

from __future__ import annotations

import asyncio
import copy
from typing import Any, Callable, Protocol

Doc = dict[str, Any]
# Receives the current document (or None) and returns the fields to write.
# Raising aborts the write: that is how "check then update" stays atomic.
Mutation = Callable[[Doc | None], Doc]


class DocumentStore(Protocol):
    async def get(self, collection: str, doc_id: str) -> Doc | None: ...
    async def set(self, collection: str, doc_id: str, data: Doc) -> None: ...
    async def update(self, collection: str, doc_id: str, patch: Doc) -> None: ...
    async def where(self, collection: str, **equals: Any) -> list[Doc]: ...
    async def atomic_update(self, collection: str, doc_id: str, mutation: Mutation) -> Doc: ...
    async def clear(self, collection: str) -> None: ...


class MemoryDocumentStore:
    """In-memory fake with the same semantics, used by tests and MOCK_AI runs."""

    def __init__(self) -> None:
        self._data: dict[str, dict[str, Doc]] = {}
        self._lock = asyncio.Lock()

    async def get(self, collection: str, doc_id: str) -> Doc | None:
        doc = self._data.get(collection, {}).get(doc_id)
        return copy.deepcopy(doc) if doc is not None else None

    async def set(self, collection: str, doc_id: str, data: Doc) -> None:
        self._data.setdefault(collection, {})[doc_id] = copy.deepcopy(data)

    async def update(self, collection: str, doc_id: str, patch: Doc) -> None:
        docs = self._data.setdefault(collection, {})
        if doc_id not in docs:
            raise KeyError(f"{collection}/{doc_id}")
        docs[doc_id].update(copy.deepcopy(patch))

    async def where(self, collection: str, **equals: Any) -> list[Doc]:
        return [
            copy.deepcopy(d)
            for d in self._data.get(collection, {}).values()
            if all(d.get(k) == v for k, v in equals.items())
        ]

    async def atomic_update(self, collection: str, doc_id: str, mutation: Mutation) -> Doc:
        async with self._lock:
            current = await self.get(collection, doc_id)
            patch = mutation(current)
            merged = {**(current or {}), **patch}
            await self.set(collection, doc_id, merged)
            return merged

    async def clear(self, collection: str) -> None:
        self._data.pop(collection, None)


class FirestoreDocumentStore:
    """Firestore (Native mode) named database. Async client, transactions for atomic updates."""

    def __init__(self, project: str, database: str) -> None:
        from google.cloud import firestore

        self._firestore = firestore
        self._db = firestore.AsyncClient(project=project, database=database)

    async def get(self, collection: str, doc_id: str) -> Doc | None:
        snap = await self._db.collection(collection).document(doc_id).get()
        return snap.to_dict() if snap.exists else None

    async def set(self, collection: str, doc_id: str, data: Doc) -> None:
        await self._db.collection(collection).document(doc_id).set(data)

    async def update(self, collection: str, doc_id: str, patch: Doc) -> None:
        await self._db.collection(collection).document(doc_id).update(patch)

    async def where(self, collection: str, **equals: Any) -> list[Doc]:
        query = self._db.collection(collection)
        for key, value in equals.items():
            query = query.where(filter=self._firestore.FieldFilter(key, "==", value))
        return [snap.to_dict() async for snap in query.stream()]

    async def atomic_update(self, collection: str, doc_id: str, mutation: Mutation) -> Doc:
        ref = self._db.collection(collection).document(doc_id)

        @self._firestore.async_transactional
        async def run(transaction):  # type: ignore[no-untyped-def]
            snap = await ref.get(transaction=transaction)
            current = snap.to_dict() if snap.exists else None
            patch = mutation(current)
            transaction.set(ref, patch, merge=True)
            return {**(current or {}), **patch}

        return await run(self._db.transaction())

    async def clear(self, collection: str) -> None:
        await self._db.recursive_delete(self._db.collection(collection))
