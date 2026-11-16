# RecruitPro agent service (FastAPI). Listens on 0.0.0.0:8100; GET /health is public,
# /api/* requires the X-Internal-Service-Token header (AGENT_SERVICE_TOKEN).
# This service needs outbound internet access (code hosts, candidate websites, badge issuers); every candidate-supplied
# URL goes through the SSRF guard. Run it on a network segment with no route to internal services where possible.
# Pinned to Debian bookworm like the AI service image, so a new Debian release does not change the base silently.
FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_DEFAULT_TIMEOUT=180 \
    PIP_RETRIES=10

WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

COPY requirements.txt ./
RUN pip install -r requirements.txt

COPY app ./app
RUN chown -R app:app /app
USER app

EXPOSE 8100
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD python -c "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8100/health', timeout=2).status == 200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100", "--no-server-header"]
