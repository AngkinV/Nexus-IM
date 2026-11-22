import httpx
import pytest


@pytest.fixture
def provider(monkeypatch):
    """Replace only the network transport; exercise HTTP requests and streamed bodies."""
    client = httpx.AsyncClient

    def install(handler):
        def make_client(**kwargs):
            return client(**kwargs, transport=httpx.MockTransport(handler))
        monkeypatch.setattr(httpx, "AsyncClient", make_client)

    return install


def _rss(items):
    return f'<rss version="2.0"><channel><title>Search results</title>{items}</channel></rss>'.encode()


def _item(title, url, snippet):
    return f"<item><title>{title}</title><link>{url}</link><description>{snippet}</description></item>"


async def test_search_uses_only_fixed_endpoint_and_query_returns_provider_items(provider):
    from app.web_search import search_web

    query = "https://example.org/?q=language&format=html"
    seen = []

    def serve(req):
        seen.append(req.url)
        assert req.method == "GET" and req.url.scheme == "https"
        assert req.url.host == "cn.bing.com" and req.url.path == "/search"
        assert dict(req.url.params) == {"q": query, "format": "rss"}
        items = (
            _item("Not a web URL", "file:///tmp/note", "not a hit")
            + _item("No host", "https:///no-host", "not a hit")
            + _item("Docs &amp; API", "https://docs.example.org/", "An &lt;b&gt;actual&lt;/b&gt; snippet")
            + _item("Duplicate", "https://docs.example.org/", "again")
            + _item("News", "http://news.example.org/article", "")
            + _item("Too many", "https://other.example.org/", "later")
        )
        return httpx.Response(200, headers={"Content-Type": "text/xml; charset=utf-8"}, content=_rss(items))

    provider(serve)
    assert await search_web(query, limit=2) == [
        {"title": "Docs & API", "url": "https://docs.example.org/", "snippet": "An actual snippet"},
        {"title": "News", "url": "http://news.example.org/article", "snippet": ""},
    ]
    assert len(seen) == 1  # Neither the query URL nor any result URL is fetched.


async def test_valid_empty_feed_is_a_real_no_results_response(provider):
    from app.web_search import search_web

    provider(lambda req: httpx.Response(200, content=_rss(""), headers={"Content-Type": "application/rss+xml"}))
    assert await search_web("a truly obscure term") == []


@pytest.mark.parametrize(("status", "content", "headers", "reason"), [
    (403, b"forbidden", {}, "403"), (429, b"rate limited", {}, "429"),
    (302, b"", {"Location": "https://example.org/"}, "302|redirect"),
    (202, b"verify", {}, "202|challenge"),
    (200, b"<html><title>Verify you are human</title></html>", {"Content-Type": "text/html"}, "HTML|challenge|RSS"),
    (200, b"<html><form>challenge</form></html>", {"Content-Type": "text/xml"}, "challenge|RSS"),
    (200, b"<rss><channel>", {"Content-Type": "text/xml"}, "XML|RSS"),
    (200, b"<!DOCTYPE rss><rss><channel/></rss>", {"Content-Type": "text/xml"}, "DTD|DOCTYPE|RSS"),
    (200, _rss(""), {"Content-Type": "text/xml", "Content-Length": "9000000"}, "large|size|limit"),
])
async def test_search_errors_are_not_results(provider, status, content, headers, reason):
    from app.web_search import WebSearchError, search_web

    def serve(req):
        assert req.url.host == "cn.bing.com"  # Redirect targets must never be contacted.
        return httpx.Response(status, headers=headers, content=content)

    provider(serve)
    with pytest.raises(WebSearchError, match=f"(?i){reason}"):
        await search_web("weather")


async def test_search_bounds_stream_when_provider_omits_length(provider):
    from app.web_search import WebSearchError, search_web

    class LargeBody(httpx.AsyncByteStream):
        async def __aiter__(self):
            for _ in range(32):
                yield b"x" * 65_536
            pytest.fail("search buffered more than 2 MiB")

    provider(lambda req: httpx.Response(200, headers={"Content-Type": "text/xml"}, stream=LargeBody()))
    with pytest.raises(WebSearchError, match="(?i)large|size|limit"):
        await search_web("weather")


async def test_search_timeout_is_reported(provider):
    from app.web_search import WebSearchError, search_web

    def timeout(req):
        raise httpx.ReadTimeout("timed out", request=req)

    provider(timeout)
    with pytest.raises(WebSearchError, match="(?i)timeout|timed out"):
        await search_web("weather")


@pytest.mark.parametrize(("query", "limit"), [
    ("   ", 1), pytest.param("x" * 10_000, 1, id="query-too-long"), ("valid", 0), ("valid", -1),
])
async def test_invalid_search_never_uses_network(provider, query, limit):
    from app.web_search import WebSearchError, search_web

    provider(lambda req: pytest.fail("invalid input reached the provider"))
    with pytest.raises(WebSearchError, match="(?i)query|limit"):
        await search_web(query, limit=limit)
