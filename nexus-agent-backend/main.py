"""Local launcher. The actual app lives in `app/__init__.py`.

Run: `python main.py` or `uvicorn app:app --port 8100`.
"""
from __future__ import annotations

import uvicorn

from app import app  # noqa: F401  (re-exported for `uvicorn main:app` callers)
from app.config import get_settings


if __name__ == "__main__":
    s = get_settings()
    uvicorn.run("app:app", host="0.0.0.0", port=s.service_port, reload=False)
