# Reproducible static build of the RuoYi web frontend, served by the restricted
# same-origin proxy from infra/dev/nginx.conf. Build context is the repository
# root (see /.dockerignore whitelist); runtimes are pinned: node 22.23.2 (engines
# floor >=22.13.0) and the packageManager-pinned pnpm 11.0.9 - never "latest".
#
# Install and verify both applications and both shared packages from one lockfile.
# syntax=docker/dockerfile:1
FROM node:22.23.2-alpine AS build
# Public client id baked into the frontend login flow (not a secret; must match a
# sys_client row on the platform).
ARG VITE_CLIENT_ID=dev-client
ENV VITE_CLIENT_ID=${VITE_CLIENT_ID}
ENV WEB_ADMIN_BASE_PATH=/admin/
WORKDIR /src
RUN corepack enable && corepack prepare pnpm@11.0.9 --activate
COPY services/web/package.json services/web/pnpm-lock.yaml services/web/pnpm-workspace.yaml ./
COPY services/web/packages/events/package.json ./packages/events/
COPY services/web/packages/platform-client/package.json ./packages/platform-client/
COPY services/web/apps/workbench/package.json ./apps/workbench/
COPY services/web/apps/admin/package.json ./apps/admin/
# pnpm's SQLite-backed store cannot live on the overlay filesystem (disk I/O
# errors); keep it in a BuildKit cache mount, which also speeds up rebuilds.
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    --mount=type=cache,target=/tmp/pnpm-cache \
    pnpm config set cache-dir /tmp/pnpm-cache && pnpm install --frozen-lockfile
COPY services/web/ ./
# The test loader transpiles without checking types; run the separate workspace check.
RUN pnpm test && pnpm typecheck && pnpm lint && pnpm build

FROM nginx:1.27-alpine
COPY --from=build /src/apps/workbench/dist /usr/share/nginx/html
COPY --from=build /src/apps/admin/dist /usr/share/nginx/html/admin
# Restricted same-origin delivery: static assets plus a bounded proxy to the
# platform public gateway only (see infra/dev/nginx.conf).
COPY infra/dev/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
