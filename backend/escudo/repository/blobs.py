"""Claim photos: Cloud Storage bucket in the cloud, a dict in tests."""

from __future__ import annotations

import asyncio
from typing import Protocol


class BlobStore(Protocol):
    bucket: str

    async def put(self, path: str, data: bytes, content_type: str) -> str: ...
    async def get(self, uri: str) -> bytes: ...


def split_gs_uri(uri: str) -> tuple[str, str]:
    if not uri.startswith("gs://"):
        raise ValueError("not a gs:// uri")
    bucket, _, path = uri[5:].partition("/")
    return bucket, path


class MemoryBlobStore:
    def __init__(self, bucket: str = "memory-bucket") -> None:
        self.bucket = bucket
        self._blobs: dict[str, bytes] = {}

    async def put(self, path: str, data: bytes, content_type: str) -> str:
        uri = f"gs://{self.bucket}/{path}"
        self._blobs[uri] = data
        return uri

    async def get(self, uri: str) -> bytes:
        return self._blobs[uri]


class GcsBlobStore:
    def __init__(self, project: str, bucket: str) -> None:
        from google.cloud import storage

        self.bucket = bucket
        self._bucket = storage.Client(project=project).bucket(bucket)

    async def put(self, path: str, data: bytes, content_type: str) -> str:
        blob = self._bucket.blob(path)
        await asyncio.to_thread(blob.upload_from_string, data, content_type=content_type)
        return f"gs://{self.bucket}/{path}"

    async def get(self, uri: str) -> bytes:
        bucket, path = split_gs_uri(uri)
        if bucket != self.bucket:
            raise PermissionError("only photos in the claims bucket can be read")
        return await asyncio.to_thread(self._bucket.blob(path).download_as_bytes)
