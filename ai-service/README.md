# RecruitPro resume intelligence service

This internal FastAPI service extracts evidence from private candidate resumes and compares structured resume facts with Spring Boot job requirements. Spring Boot remains authoritative for candidate identity, roles, resumes, applications, jobs, permissions, and persistent results. The service does not make hiring decisions.

## Processing behavior

- Text-based PDF and DOCX files are parsed locally with `pdfplumber` and `python-docx`.
- The parser stores no full resume text. It returns typed facts with source section, optional PDF page reference, and a short evidence excerpt. Email and phone facts are candidate-only at the Spring API; contact details are removed from other evidence snippets.
- Text extraction is rule-based and intentionally conservative. It recognizes a small versioned set of common skills and labeled education, experience, and certification sections. A partial result means facts were not all recognized; it does not mean an unobserved qualification is absent.
- Image-only scanned PDFs and DOCX files return `NEEDS_OCR`. OCR is not enabled. Legacy `.doc` uploads remain in resume history and can be used in applications, but extraction returns `UNSUPPORTED_FORMAT`.
- Matching is deterministic weighted requirement evidence coverage: mandatory requirements have weight 2, optional requirements weight 1, and only fully `MATCHED` requirements contribute. `UNCLEAR` and `NOT_OBSERVED` do not add points. The score is not a probability of success. `OTHER` requirements are left for human review; protected characteristics are not matched.
- No external model, paid API, cloud storage, or outbound resume upload is used. Semantic matching and OCR are not included in this initial implementation.

## Run locally

Python 3.10 or later is required. Set the same generated random value for `AI_SERVICE_TOKEN` in the environment of both processes before starting them. Do not commit the actual value.

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:AI_SERVICE_TOKEN = "<same-random-value-as-backend>"
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

The Python API has no browser CORS access and its interactive API documentation is disabled. Spring Boot calls the protected endpoints with `X-Internal-Service-Token`. Keep the service bound to localhost for development and on a private network in deployment.

## Tests

```powershell
pip install -r requirements-dev.txt
python -m unittest discover -s tests -v
```

The tests cover PDF/DOCX extraction, evidence and contact redaction, scanned/empty/malformed/oversized/unsupported files, deterministic matching, and internal service authentication.
