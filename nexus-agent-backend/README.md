# nexus-agent-backend

The Python service behind Nexus's AI features. Java owns authentication,
credentials and data; this service owns everything that talks to a model.

It is an internal service: every request must carry the HMAC signature the Java
backend adds, and it is not meant to be reachable from outside.

## What it does

| Area | Routes | Notes |
|---|---|---|
| Agent turns | `/v1/agent/invoke`, `/v1/agent/invoke/stream` | One LangGraph run per turn, streamed as SSE |
| Unfinished runs | `/v1/agent/runs/resume/stream`, `/runs/pending`, `/runs/purge` | Continue a run that stopped early, or fetch the answer of one that ended while the client was away |
| Model access | `/v1/models/catalog`, `/test`, `/complete`, `/complete/stream` | Stateless: no agent prompt, tools, memory or RAG |
| Knowledge base | `/v1/knowledge/ingest`, `/delete`, `/query` | Needs an embedding provider |
| Health | `/v1/agent/health` | Reports the engine and default model |

## Module layout

```
app/
  __init__.py        FastAPI app; validates configuration at startup
  config.py          Settings, read from the environment / .env
  security.py        HMAC verification of requests from Java
  routes.py          Agent, run and knowledge-base routes
  model_routes.py    Model catalog / test / completion routes
  orchestrator.py    The agent graph: prepare_context -> reason <-> tools -> validate_output -> settle
  checkpoint.py      SQLite store for unfinished runs and the outcome of finished ones
  mcp_tools.py       Tool discovery and calls over MCP
  llm/               Provider adapters (OpenAI-compatible, Anthropic, Gemini) and the model service
  memory.py          Redis short-term memory
  rag/, knowledge/   Semantic memory and knowledge-base retrieval (Chroma)
  prompts.py         Prompt builder and input sanitizer
  tracing.py         OpenTelemetry setup
  mock.py            Deterministic answers when no model is configured
tests/               Unit tests; tests/evals holds the scripted end-to-end cases
```

## Tools

No tool is defined in this service. The Java backend publishes its tools as an
MCP server at `<JAVA_INTERNAL_BASE_URL>/mcp`; names, descriptions and argument
schemas are read from it and offered to the model as they are. Adding a tool
means adding one annotated method in Java (`AgentToolService`).

The user a call is made for travels in the `X-Actor-User-Id` request header,
never in the tool arguments, and Java checks permissions on every call.

If the tool server cannot be reached, an ordinary chat turn continues without
tools and says so in its event stream. A turn that depends on a tool (summary,
todo extraction) stops with `AGENT_TOOL_50301` and can be continued later.

Extra MCP servers can be allow-listed with `MCP_EXTERNAL_SERVERS`. Their tools
are prefixed with the server name, they receive none of the internal headers,
and their results reach the model marked as untrusted and cut to a fixed length.

## Run

Python 3.11 or newer is required.

```bash
uv venv --python 3.11 .venv311
uv pip install --python .venv311 -r requirements.txt

# shared with the Java backend
export INTERNAL_SIGNING_SECRET=<same as agent.internal.signing-secret>
export JAVA_INTERNAL_TOKEN=<same as agent.internal.token>
export JAVA_INTERNAL_BASE_URL=http://localhost:8080/internal/agent

.venv311/bin/python main.py        # listens on :8100
```

Model credentials normally arrive per request from Java. `OPENAI_API_KEY`,
`OPENAI_BASE_URL` and `MODEL_NAME` are only a fallback for agent turns sent
without a channel; when neither is present the agent answers from `mock.py`.
The model routes never use the fallback or the mock.

## Configuration

Settings are read from the environment or a `.env` file in the working
directory. The ones added with the LangGraph engine:

| Variable | Default | Meaning |
|---|---|---|
| `ENGINE` | `langgraph` | The only accepted value. `ENGINE=handcrafted` from an older `.env` stops the service at startup; change or delete the line |
| `CHECKPOINT_PATH` | `./data/checkpoints.sqlite` | Where unfinished runs and finished outcomes are kept |
| `CHECKPOINT_TTL_SEC` | `86400` | How long they are kept |
| `TOOL_TIMEOUT_SEC` | `3` | Deadline for one tool call |
| `TOOL_CATALOG_TTL_SEC` | `60` | How long the discovered tool list is reused |
| `MCP_EXTERNAL_SERVERS` | empty | JSON object of name -> http(s) URL |
| `MCP_EXTERNAL_RESULT_MAX_CHARS` | `8000` | Longest external tool result passed to the model |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset | OTLP/HTTP collector. Unset: spans are created but not exported |
| `TRACE_CAPTURE_CONTENT` | `false` | Adds prompt and answer text to spans |
| `MODEL_ENDPOINT_ALLOW_PRIVATE_NETWORK` | `true` | Whether model endpoints may resolve to private addresses |

## Limits

- One instance only. Run checkpoints live in a local SQLite file and the
  "one executor per run" guard is in-process.
- Continuing a run restarts the model call that was interrupted; steps that had
  finished, including tool calls, are not repeated.
- `langchain.mcp` is a beta API. It is pinned in `requirements.txt` together
  with `fastmcp` and `mcp`.

## Test

```bash
.venv311/bin/python -m pytest
```

Tests run in a temporary directory, use no network and read no `.env`.
To refresh the prompt snapshot after an intended prompt change:
`UPDATE_EVAL_SNAPSHOTS=1 .venv311/bin/python -m pytest tests/evals/test_golden.py`.
