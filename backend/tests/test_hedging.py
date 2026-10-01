"""Hedged assessment calls: a slow or failing first call never decides the latency on stage."""

from __future__ import annotations

import asyncio
import time

import pytest

from escudo.damage_assessor import hedged

pytestmark = pytest.mark.anyio


def calls(*behaviours):  # noqa: ANN001, ANN201
    """Each call takes the next (seconds, result-or-exception)."""
    queue = list(behaviours)
    started: list[int] = []

    async def call():  # noqa: ANN202
        seconds, outcome = queue.pop(0)
        started.append(len(started) + 1)
        await asyncio.sleep(seconds)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    return call, started


async def test_fast_first_call_needs_no_backup():
    call, started = calls((0.01, "a"))
    result, timing = await hedged(call, hedge_after=0.2, deadline=1)
    assert result == "a" and not timing["hedged"] and started == [1]


async def test_slow_first_call_is_overtaken_by_the_backup():
    call, started = calls((5, "slow"), (0.02, "fast"))
    t0 = time.perf_counter()
    result, timing = await hedged(call, hedge_after=0.05, deadline=2, attempts=2)
    assert result == "fast" and timing == {**timing, "hedged": True, "winner": 2}
    assert time.perf_counter() - t0 < 0.5 and started == [1, 2]


async def test_failed_first_call_starts_the_backup_at_once():
    call, _ = calls((0.0, RuntimeError("503")), (0.02, "ok"))
    t0 = time.perf_counter()
    result, timing = await hedged(call, hedge_after=10, deadline=20, attempts=2)
    assert result == "ok" and timing["winner"] == 2 and time.perf_counter() - t0 < 0.5


async def test_a_third_attempt_rescues_two_stalled_ones():
    call, started = calls((5, "slow"), (5, "slow"), (0.02, "third"))
    result, timing = await hedged(call, hedge_after=0.05, deadline=2, attempts=3)
    assert result == "third" and timing["winner"] == 3 and started == [1, 2, 3]


async def test_deadline_and_all_failures_raise():
    call, started = calls((5, "never"), (5, "never"), (5, "never"))
    with pytest.raises(TimeoutError):
        await hedged(call, hedge_after=0.02, deadline=0.15, attempts=3)
    assert started == [1, 2, 3]
    call, _ = calls((0.0, RuntimeError("a")), (0.0, RuntimeError("b")))
    with pytest.raises(TimeoutError):
        await hedged(call, hedge_after=1, deadline=2, attempts=2)
