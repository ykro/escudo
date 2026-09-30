"""Partner discovery: the only thing Escudo knows about a partner is a base URL.

For each base URL in `A2A_PEERS` we fetch the Agent Card from the A2A
well-known path and index the partner by the skills it advertises. The RPC
endpoint we call is whatever the card says — never a hard-coded path.
"""

from __future__ import annotations

import logging
import time
from typing import Callable

import httpx
from a2a.client.card_resolver import A2ACardResolver
from a2a.types import AgentCard

from ..errors import EscudoError

log = logging.getLogger(__name__)
CACHE_SECONDS = 300


class PeerUnavailable(EscudoError):
    status = 503


class PeerDirectory:
    def __init__(self, base_urls: tuple[str, ...], client_factory: Callable[[], httpx.AsyncClient]) -> None:
        self.base_urls = base_urls
        self._client_factory = client_factory
        self._by_skill: dict[str, AgentCard] = {}
        self._loaded_at = 0.0

    async def refresh(self) -> dict[str, AgentCard]:
        by_skill: dict[str, AgentCard] = {}
        async with self._client_factory() as client:
            for base in self.base_urls:
                try:
                    card = await A2ACardResolver(httpx_client=client, base_url=base).get_agent_card()
                except Exception as exc:  # one partner down must not hide the others
                    log.warning("agent card not available at %s: %s", base, exc)
                    continue
                for skill in card.skills:
                    by_skill[skill.id] = card
        self._by_skill = by_skill
        self._loaded_at = time.monotonic()
        return by_skill

    async def find(self, skill_id: str) -> AgentCard:
        if not self._by_skill or time.monotonic() - self._loaded_at > CACHE_SECONDS or skill_id not in self._by_skill:
            await self.refresh()
        card = self._by_skill.get(skill_id)
        if card is None:
            raise PeerUnavailable("peer_unavailable", "Ningún socio disponible ofrece ese servicio en este momento.")
        return card

    def forget(self) -> None:
        self._by_skill = {}
