# syntax=docker/dockerfile:1
# Public landing site: build with Node 22 + pnpm, serve the static files with nginx (same pattern as the portals).

FROM node:22-alpine AS build
WORKDIR /app
# Slow or flaky networks: long fetch time-outs and retries.
ENV npm_config_fetch_retries=6 npm_config_fetch_timeout=300000 npm_config_fetch_retry_maxtimeout=180000
RUN npm install -g pnpm@10
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --network-concurrency=4
COPY . .
# Where the calls to action point. Compiled into the bundle by Vite; change them for a real deployment.
ARG VITE_CANDIDATE_URL=http://localhost:5173
ARG VITE_HR_URL=http://localhost:5174
ENV VITE_CANDIDATE_URL=${VITE_CANDIDATE_URL} VITE_HR_URL=${VITE_HR_URL}
RUN pnpm build

FROM nginx:1.27-alpine
# The site calls no API, so the CSP needs no origin and nginx.conf is a plain config (not an envsubst template).
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
# busybox wget ships with the alpine image; the site answers / with index.html.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
