# syntax=docker/dockerfile:1
# Build the SPA with Node 22 + pnpm, serve it with nginx (SPA fallback to index.html).

FROM node:22-alpine AS build
WORKDIR /app
# Slow or flaky networks: long fetch time-outs and retries.
ENV npm_config_fetch_retries=6 npm_config_fetch_timeout=300000 npm_config_fetch_retry_maxtimeout=180000
RUN npm install -g pnpm@10
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --network-concurrency=4
COPY . .
# Per-port image (compose profile "ports"; the single-origin gateway builds its own copy, gateway/Dockerfile).
# Compiled into the bundle by Vite (src/design/lib/links.ts): the API base URL and the three app addresses for the
# cross-links. Absolute URLs here make this portal's base path "/" (its own port).
ARG VITE_API_BASE_URL=http://localhost:8080/api/v1
ARG VITE_SITE_URL=http://localhost:5170
ARG VITE_CANDIDATE_URL=http://localhost:5173
ARG VITE_HR_URL=http://localhost:5174
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL} VITE_SITE_URL=${VITE_SITE_URL} VITE_CANDIDATE_URL=${VITE_CANDIDATE_URL} VITE_HR_URL=${VITE_HR_URL}
RUN pnpm build

FROM nginx:1.27-alpine
# nginx.conf is a template: ${API_ORIGIN} (backend origin for the CSP connect-src) is filled in by envsubst at start-up.
# It must be the origin of VITE_API_BASE_URL above. Override at run time with -e API_ORIGIN=https://api.example.com
# (or "environment:" in docker compose).
ARG API_ORIGIN=http://localhost:8080
ENV API_ORIGIN=${API_ORIGIN}
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
# busybox wget ships with the alpine image; the SPA answers / with index.html.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
