# RecruitPro HR Workspace

Standalone React, TypeScript, and Vite client for HR sign-in. The candidate portal remains in `../frontend` and is not modified by this app.

## Local development

```powershell
pnpm install
pnpm dev
```

Vite binds to `http://localhost:5174` and fails if the port is already occupied. Set `VITE_API_BASE_URL` in `.env.local` when the backend API is not at `http://localhost:8080/api/v1`. The backend must separately allow `http://localhost:5174` in its CORS origin list; this scaffold does not change backend configuration.

Build with `pnpm build`.

## Sign-in and session notes

- Sign-in calls `POST /api/v1/auth/login` and reads the backend `success` / `data` response envelope.
- Only `ADMIN` and `HR_MANAGER` responses are accepted. Other roles are not stored; the app makes a best-effort logout request to revoke the returned refresh token.
- There is no registration form or role selector. The existing public registration flow remains in the candidate portal and assigns `CANDIDATE` on the server.
- The accepted session is kept in `sessionStorage`, so it is scoped to this browser tab session and the HR origin. Tokens remain accessible to JavaScript; an XSS vulnerability could expose them. Do not treat the stored role as an authorization control: the backend must authorize every protected API request.
- Logout asks the backend to revoke the refresh token and clears browser session state even if that request fails. The backend issues stateless access JWTs, so a logged-out access token can remain valid until its expiry.
- This frontend does not yet include a dashboard or job-management screens.
