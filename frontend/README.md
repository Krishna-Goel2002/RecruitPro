# RecruitPro Candidate Portal

React 18, TypeScript, and Vite candidate client for the Phase 4 backend APIs.

## Features

- Candidate account registration and sign-in, using the existing authentication endpoints.
- Candidate profile viewing and editing.
- Published job browsing and title search.
- Private resume upload, history, and downloads.
- Applications tied to the selected uploaded resume, history, and withdrawal while submitted.

## Setup

```bash
pnpm install
pnpm dev
```

Vite serves the app at `http://localhost:5173`. Set `VITE_API_BASE_URL` in `.env.local` if the backend API is not at `http://localhost:8080/api/v1`. The backend must allow the frontend origin through `CORS_ALLOWED_ORIGINS`.

Build with `pnpm build`.
