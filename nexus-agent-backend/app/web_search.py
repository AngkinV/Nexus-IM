"""Web search: one fixed public endpoint, and no pretending when it fails.

The only request made is a GET to the fixed endpoint below with the user's
query. Result URLs are returned to the caller as text; they are never fetched,
and nothing from an attachment is ever put into the query.

A challenge page, a redirect, a rate limit, a timeout or an oversized body is
reported as `WebSearchError`. An empty result list means the provider answered
with a valid, empty feed — not that the search was unavailable.
"""
from __future__ import annotations

import re
from urllib.parse import urlsplit
from xml.etree import ElementTree

import httpx

SEARCH_ENDPOINT = "https://cn.bing.com/search"
MAX_QUERY_CHARS = 500
MAX_BODY_BYTES = 2 * 1024 * 1024
TIMEOUT_SEC = 10.0


class WebSearchError(RuntimeError):
    """The search could not be completed; the message says why."""


def _validate(query: str, limit: int) -> str:
    text = (query or "").strip()
    if not text:
        raise WebSearchError("the search query is empty")
    if len(text) > MAX_QUERY_CHARS:
        raise WebSearchError(f"the search query is longer than {MAX_QUERY_CHARS} characters")
    if limit < 1:
        raise WebSearchError("the result limit must be at least 1")
    return text


async def _body(response: httpx.Response) -> bytes:
    declared = response.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_BODY_BYTES:
        raise WebSearchError("the search response is larger than the size limit")
    received = bytearray()
    async for piece in response.aiter_bytes(65_536):
        received.extend(piece)
        # Stop at the cap instead of reading on: a response that fills it is refused,
        # and nothing past it is ever buffered.
        if len(received) >= MAX_BODY_BYTES:
            raise WebSearchError("the search response reaches the size limit")
    return bytes(received)


def _check(response: httpx.Response) -> None:
    if response.is_redirect:
        raise WebSearchError(f"the search endpoint answered with a redirect ({response.status_code})")
    if response.status_code == 429:
        raise WebSearchError("the search endpoint is rate limiting (429)")
    if response.status_code == 202:
        raise WebSearchError("the search endpoint answered with a challenge (202)")
    if response.status_code != 200:
        raise WebSearchError(f"the search endpoint refused the request ({response.status_code})")


def _parse(payload: bytes) -> list[dict]:
    text = payload.lstrip()
    if text[:9].lower() == b"<!doctype":
        raise WebSearchError("the search endpoint answered with a page instead of RSS")
    try:
        root = ElementTree.fromstring(payload)
    except ElementTree.ParseError as exc:
        raise WebSearchError("the search endpoint did not return readable RSS") from exc
    if root.tag != "rss":
        raise WebSearchError("the search endpoint did not return RSS")
    channel = root.find("channel")
    if channel is None:
        raise WebSearchError("the search response has no channel")
    items: list[dict] = []
    for node in channel.findall("item"):
        title = (node.findtext("title") or "").strip()
        link = (node.findtext("link") or "").strip()
        snippet = re.sub(r"<[^>]+>", "", (node.findtext("description") or "")).strip()
        parts = urlsplit(link)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            continue
        items.append({"title": title, "url": link, "snippet": snippet})
    return items


async def search_web(query: str, *, limit: int = 5) -> list[dict]:
    """Up to `limit` results, in the order the provider returned them."""
    text = _validate(query, limit)
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT_SEC, follow_redirects=False) as client:
            async with client.stream("GET", SEARCH_ENDPOINT, params={"q": text, "format": "rss"}) as response:
                _check(response)
                payload = await _body(response)
    except WebSearchError:
        raise
    except httpx.TimeoutException as exc:
        raise WebSearchError("the search request timed out") from exc
    except httpx.HTTPError as exc:
        raise WebSearchError(f"the search request failed: {type(exc).__name__}") from exc
    results: list[dict] = []
    seen: set[str] = set()
    for item in _parse(payload):
        if item["url"] in seen:
            continue
        seen.add(item["url"])
        results.append(item)
        if len(results) >= limit:
            break
    return results
