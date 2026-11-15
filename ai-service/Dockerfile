# RecruitPro AI service (FastAPI). Listens on 0.0.0.0:8000; GET /health is public,
# /api/* requires the X-Internal-Service-Token header (AI_SERVICE_TOKEN).
#
# Optional document tooling, both on by default (see README "Docker image"):
#   WITH_LIBREOFFICE=true  headless LibreOffice Writer (no GUI) for legacy .doc -> .docx conversion
#   WITH_OCR=true          the requirements-ocr.txt extra (RapidOCR on onnxruntime) for scanned resumes;
#                          OCR still only runs when OCR_ENABLED=true at run time (default false)
# Smallest image: docker build --build-arg WITH_LIBREOFFICE=false --build-arg WITH_OCR=false .
# The base is pinned to Debian bookworm so the apt package names below stay valid.
FROM python:3.11-slim-bookworm

ARG WITH_LIBREOFFICE=true
ARG WITH_OCR=true

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    SOFFICE_PATH=/usr/bin/soffice \
    OCR_ENABLED=false

WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# System packages: LibreOffice Writer without the GUI; libgl1 + libglib2.0-0 are the shared libraries
# opencv-python (a RapidOCR dependency) loads at import time. No recommends, apt lists removed.
RUN set -eux; \
    packages=""; \
    if [ "$WITH_LIBREOFFICE" = "true" ]; then packages="$packages libreoffice-writer-nogui"; fi; \
    if [ "$WITH_OCR" = "true" ]; then packages="$packages libgl1 libglib2.0-0"; fi; \
    if [ -n "$packages" ]; then \
        apt-get update; \
        apt-get install -y --no-install-recommends $packages; \
        apt-get clean; \
        rm -rf /var/lib/apt/lists/*; \
    fi

COPY requirements.txt requirements-ocr.txt ./
RUN set -eux; \
    if [ "$WITH_OCR" = "true" ]; then pip install -r requirements-ocr.txt; else pip install -r requirements.txt; fi

COPY . .
RUN chown -R app:app /app
USER app

EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD python -c "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).status == 200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
