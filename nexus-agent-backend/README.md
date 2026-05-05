# nexus-agent-backend

Python Agent backend for Nexus IM Agent integration.

Implements the contract documented in `agent开发文档/Java 网关与 Python Agent 接口契约.md`.

## Module layout

```
app/
  __init__.py      # FastAPI app factory
  config.py        # Settings (env-driven)
  schemas.py       # Pydantic request / response models
  security.py      # HMAC verification dependency
  memory.py        # Redis short-term memory
  prompts.py       # System / business prompt builder + injection sanitizer
  tools.py         # Tool registry + executor (calls Java internal API)
  orchestrator.py  # Agent loop (model + tool + memory) producing event stream
  mock.py          # Deterministic mock used when no OpenAI key is set
  sse.py           # SSE frame helpers
  routes.py        # /v1/agent/* endpoints
main.py            # Launcher
```

## Run

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# optional: real model
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.openai.com/v1
export MODEL_NAME=gpt-4.1-mini

# required: shared secret pair with Java side
export INTERNAL_SIGNING_SECRET=<same-as-agent.internal.signing-secret>
export JAVA_INTERNAL_TOKEN=<same-as-agent.internal.token>
export JAVA_INTERNAL_BASE_URL=http://localhost:8080/internal/agent

python main.py            # listens on :8100
```

If `OPENAI_API_KEY` is unset, the orchestrator falls back to deterministic mock
answers — useful for local development without burning credits.

## Test

```bash
pip install pytest pytest-asyncio
pytest tests/
```
