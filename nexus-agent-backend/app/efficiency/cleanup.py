"""Bounded cancellation settlement, without pretending a hung task is closed."""
from __future__ import annotations

import asyncio
from typing import Iterable

from .results import BudgetLimited


class CleanupIncomplete(BudgetLimited):
    def __init__(self, pending_tasks: Iterable[asyncio.Future], *, reason: str = "cleanup-incomplete") -> None:
        self.pending_tasks = tuple(pending_tasks)
        super().__init__(reason, observed=len(self.pending_tasks))


def consume_exception(task: asyncio.Future) -> None:
    if not task.cancelled():
        task.exception()


async def settle(tasks: Iterable[asyncio.Future], timeout_seconds: float, *, cancel: bool = False) -> tuple[asyncio.Future, ...]:
    """Never use wait_for/gather to wait indefinitely for swallowed cancellation.

    Remaining tasks stay owned by the caller. They are NOT called cancelled or
    closed; the caller must quarantine resources and observe their real finish.
    """
    tasks = tuple(set(tasks))
    if not tasks:
        return ()
    if cancel:
        for task in tasks:
            if not task.done():
                task.cancel()
    if timeout_seconds > 0:
        try:
            await asyncio.wait(tasks, timeout=timeout_seconds)
        except asyncio.CancelledError:
            # A second cancellation cannot turn bounded cleanup into another
            # unbounded await. Report remaining ownership to the caller.
            pass
    for task in tasks:
        if task.done():
            consume_exception(task)
    return tuple(task for task in tasks if not task.done())
