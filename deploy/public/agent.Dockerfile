# Python agent for the public stack. Compilers stay in the build stage.
# pypi.org is not reachable from the VM. On 2026-11-26 this mirror and USTC's served every pin
# in requirements.txt to pip; Aliyun's did not (markdown 3.11.4 was missing for pip).
ARG PIP_INDEX_URL=https://mirrors.cloud.tencent.com/pypi/simple/
FROM python:3.11-slim@sha256:61e01f72680f86a5279ad510818ab7b2ee9f049822589d4e34e33e044360356c AS build
ARG PIP_INDEX_URL
RUN apt-get update && apt-get install -y --no-install-recommends build-essential && rm -rf /var/lib/apt/lists/*
RUN python -m venv /venv
COPY requirements.txt .
# The report records the URL and SHA-256 of every file pip installed, so what the mirror
# served can be compared with pypi.org afterwards (it stays in the image at /venv/pip-report.json).
RUN /venv/bin/pip install --no-cache-dir --disable-pip-version-check -i "$PIP_INDEX_URL" \
    --report /venv/pip-report.json -r requirements.txt

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
