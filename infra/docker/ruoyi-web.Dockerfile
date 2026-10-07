# Reproducible static build of the RuoYi web frontend, served by the restricted
# same-origin proxy from infra/dev/nginx.conf. Build context is the repository
# root (see /.dockerignore whitelist); runtimes are pinned: node 22.23.2 (engines
# floor >=22.13.0) and the packageManager-pinned pnpm 11.0.9 - never "latest".
# syntax=docker/dockerfile:1
FROM node:22.23.2-alpine AS build
# Public client id baked into the frontend login flow (not a secret; must match a
# sys_client row on the platform).
ARG VITE_CLIENT_ID=dev-client
ENV VITE_CLIENT_ID=${VITE_CLIENT_ID}
WORKDIR /src
RUN corepack enable && corepack prepare pnpm@11.0.9 --activate
COPY services/ruoyi-web/package.json services/ruoyi-web/pnpm-lock.yaml services/ruoyi-web/pnpm-workspace.yaml ./
# pnpm's SQLite-backed store cannot live on the overlay filesystem (disk I/O
# errors); keep it in a BuildKit cache mount, which also speeds up rebuilds.
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    --mount=type=cache,target=/tmp/pnpm-cache \
    pnpm config set cache-dir /tmp/pnpm-cache && pnpm install --frozen-lockfile
COPY services/ruoyi-web/ ./
# The node:test loader transpiles without type checking, so the test project's
# type check runs on its own before the build (which re-runs vue-tsc -b).
RUN pnpm exec tsc -p tsconfig.tests.json --noEmit \
 && node --import ./tests/ts-loader.mjs --test ./tests/RunStreamClient.test.ts ./tests/SseSyntax.test.ts ./tests/RagApi.test.ts ./tests/RagTransport.test.ts \
 && pnpm build

FROM nginx:1.27-alpine
COPY --from=build /src/dist /usr/share/nginx/html
# Restricted same-origin delivery: static assets plus a bounded proxy to the
# platform public gateway only (see infra/dev/nginx.conf).
COPY infra/dev/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
