# Python agent for the public stack. Compilers stay in the build stage.
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
FROM python:3.11-slim@sha256:61e01f72680f86a5279ad510818ab7b2ee9f049822589d4e34e33e044360356c AS build
ARG PIP_INDEX_URL
RUN apt-get update && apt-get install -y --no-install-recommends build-essential && rm -rf /var/lib/apt/lists/*
RUN python -m venv /venv
COPY requirements.txt .
RUN /venv/bin/pip install --no-cache-dir --disable-pip-version-check -i "$PIP_INDEX_URL" -r requirements.txt

FROM python:3.11-slim@sha256:61e01f72680f86a5279ad510818ab7b2ee9f049822589d4e34e33e044360356c
# libmagic1: unstructured's file-type detection needs it at import time.
RUN apt-get update && apt-get install -y --no-install-recommends libmagic1 && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 10001 nexus && useradd -u 10001 -g 10001 -M -d /app -s /usr/sbin/nologin nexus \
    && mkdir -p /app/data && chown -R 10001:10001 /app
COPY --from=build /venv /venv
WORKDIR /app
COPY app ./app
COPY main.py .
ENV PATH=/venv/bin:$PATH PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
# Loopback only: the Java backend in the same network namespace is the only caller.
CMD ["uvicorn", "app:app", "--host", "127.0.0.1", "--port", "8100"]
