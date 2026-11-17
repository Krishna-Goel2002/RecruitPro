# syntax=docker/dockerfile:1
# Single-origin gateway (docker compose service `gateway`, http://localhost:8088): ONE address for the whole product.
#
#   /            landing site    (site/)         built with Vite base "/"
#   /candidate/  candidate portal (frontend/)    built with Vite base "/candidate/"
#   /hr/         HR portal        (hr-frontend/) built with Vite base "/hr/"
#   /api/        backend:8080, proxied           the portals call the relative "/api/v1" (same origin, no CORS)
#
# The gateway builds the three apps itself and serves their files directly (no proxy hop to three more nginx
# containers): one image, one CSP, one place to look. The per-port images (frontend/Dockerfile, hr-frontend/Dockerfile,
# site/Dockerfile) are built separately for the old addresses (compose profile "ports").
#
# Build context: the repository root. gateway/Dockerfile.dockerignore sends only the three web apps (without
# node_modules/dist) and gateway/; the COPY lines name the source files explicitly as well, so a host node_modules can
# never end up in the image.

FROM node:22-alpine AS web
# Slow or flaky networks: long fetch time-outs and retries.
ENV npm_config_fetch_retries=6 npm_config_fetch_timeout=300000 npm_config_fetch_retry_maxtimeout=180000
RUN npm install -g pnpm@10
# The link config of all three apps (src/design/lib/links.ts). These are its defaults, stated so the gateway build
# never depends on them silently. Paths on the same origin: a domain change needs no rebuild.
ENV VITE_SITE_URL=/ VITE_CANDIDATE_URL=/candidate VITE_HR_URL=/hr VITE_API_BASE_URL=/api/v1
WORKDIR /app

FROM web AS site
COPY site/package.json site/pnpm-lock.yaml site/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --network-concurrency=4
COPY site/index.html site/tsconfig.json site/vite.config.ts ./
COPY site/public ./public
COPY site/src ./src
RUN pnpm build

FROM web AS candidate
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --network-concurrency=4
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/public ./public
COPY frontend/src ./src
RUN pnpm build

FROM web AS hr
COPY hr-frontend/package.json hr-frontend/pnpm-lock.yaml hr-frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --network-concurrency=4
COPY hr-frontend/index.html hr-frontend/tsconfig.json hr-frontend/vite.config.ts ./
COPY hr-frontend/public ./public
COPY hr-frontend/src ./src
RUN pnpm build

# Pinned patch release (same 1.27 line as the portal images).
FROM nginx:1.27.5-alpine
# Our own nginx.conf replaces the image's (non-root: pid and temp files under /tmp, listens on 8080) and the
# default site is removed (the image's entrypoint then has nothing to rewrite, so it also runs as a non-root user).
RUN rm -f /etc/nginx/conf.d/default.conf
COPY gateway/nginx.conf /etc/nginx/nginx.conf
COPY gateway/snippets/ /etc/nginx/snippets/
# /srv/www/<app>/: the URL path is the directory (root /srv/www for /candidate/ and /hr/; /srv/www/site for /).
COPY --from=site /app/dist /srv/www/site
COPY --from=candidate /app/dist /srv/www/candidate
COPY --from=hr /app/dist /srv/www/hr
USER nginx
EXPOSE 8080
# busybox wget ships with the alpine image; "/" is the landing page (static, answers without the backend).
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
