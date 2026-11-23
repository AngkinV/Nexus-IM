"""The v1 hash must not alias distinct sub-millisecond authorization windows.

Regression source only; T1 owns execution. Query/remote zero-data boundaries
are exercised separately by test_scope_v1 and test_scope_authority_paths.
"""
import pytest

from app.scope import ScopeDenied, TimeRange, epoch


@pytest.mark.parametrize("value", [
    "2026-11-22T00:00:00.0001Z",
    "2026-11-22T00:00:00.000001Z",
    "2026-11-22T00:00:00.000000001Z",
    # Spellings datetime.fromisoformat also accepts and silently truncates.
    "2026-11-22T00:00:00,1230004Z",
    "20261122T000000.1230004Z",
])
def test_finer_boundary_is_rejected_not_silently_floored(value):
    with pytest.raises(ScopeDenied):
        epoch(value)
    with pytest.raises(ValueError):
        TimeRange(fromInclusive=value, toExclusive=None, timeZone="UTC")


def test_exact_millisecond_zero_tail_is_the_same_instant():
    assert epoch("2026-11-22T00:00:00.123Z") == epoch("2026-11-22T00:00:00.123000000Z")
    assert epoch("2026-11-22T08:00:00.123+08:00") == epoch("2026-11-22T00:00:00.123Z")
    assert epoch("2026-11-22T00:00:00.124Z") - epoch("2026-11-22T00:00:00.123Z") == 1


@pytest.mark.parametrize("value", ["not-a-date", "", "2026-13-40T00:00:00Z"])
def test_unreadable_boundary_is_a_denial_not_a_crash(value):
    with pytest.raises(ScopeDenied):
        epoch(value)
