# 部署说明

面向运维与首次部署。功能怎么用见 [使用说明](usage-guide.md)；镜像发布与 manifest 规则见
[image-release.md](runbooks/image-release.md)。

## 1. 组件与端口

| 组件 | 说明 | 默认端口 |
| --- | --- | --- |
| platform | 唯一后端（`ruoyi-admin`，内嵌 AI 运行时） | 6039 |
| web | 工作台 `/` + 管理端 `/admin/`，同源反代 | 80 |
| PostgreSQL 17 + pgvector | 统一迁移链的业务库 | 5432 |
| Redis | 会话、缓存、SSE 协调 | 6379 |

浏览器只看到一个源：`/api`、`/auth`、`/system`、`/resource`、`/monitor` 由 web 代理到 platform，
SSE 不缓冲，`/internal/` 与 `/actuator` 拒绝访问。

## 2. 必填环境变量

`PLATFORM_DB_PASSWORD` 与 `REDIS_PASSWORD` **没有默认值**，缺失时启动失败。

```dotenv
SPRING_PROFILES_ACTIVE=prod,embedded
PLATFORM_DB_HOST=<postgres-host>
PLATFORM_DB_PORT=5432
PLATFORM_DB_NAME=ruoyi_agent
PLATFORM_DB_SCHEMA=platform,extensions
PLATFORM_DB_USERNAME=<app-role>
PLATFORM_DB_PASSWORD=<db-password>
REDIS_HOST=<redis-host>
REDIS_PORT=6379
REDIS_PASSWORD=<redis-password>
DEEPSEEK_API_KEY=<provider-key>     # 按实际提供方选填
DASHSCOPE_API_KEY=<provider-key>
```

模型提供方 key 缺失只会让对应模型不可调用，不会阻止启动。真实凭据只通过环境或受控 env 文件注入，
不要写进仓库。

## 3. 方式 A：容器镜像（推荐）

```bash
docker pull ghcr.io/wonderchn/ruoyi-ai-agentscope-platform@sha256:<digest>
docker pull ghcr.io/wonderchn/ruoyi-ai-agentscope-web@sha256:<digest>

docker run -d --name platform --network app \
  --env-file /etc/ruoyi-ai/platform.env \
  -v /srv/ruoyi-ai/private-objects:/data/private-objects \
  ghcr.io/wonderchn/ruoyi-ai-agentscope-platform@sha256:<digest>

docker run -d --name web --network app -p 8080:80 \
  ghcr.io/wonderchn/ruoyi-ai-agentscope-web@sha256:<digest>
```

- platform 镜像内置的启动命令会固定 profile，务必确认最终生效的是 `prod,embedded`。
- web 镜像同时复制两个应用的 dist，管理端基路径在构建期已设为 `/admin/`。
- 固定版本用 manifest 里的 `@sha256:`，不要用 `latest` 锁定生产。

## 4. 方式 B：源码构建

```bash
# 后端
cd services/platform
mvn -B -ntp -Pdev clean verify

SPRING_PROFILES_ACTIVE=prod,embedded \
  java -jar ruoyi-admin/target/ruoyi-admin.jar --server.port=6039
```

```bash
# 前端
cd services/web
pnpm install --frozen-lockfile
pnpm build
# 管理端必须同时带子路径与公开客户端标识；漏掉 VITE_CLIENT_ID 会导致登录返回
# “请求参数校验失败”（请求体 clientId 为空）
WEB_ADMIN_BASE_PATH=/admin/ \
VITE_CLIENT_ID=<public-client-id> \
pnpm --filter @ruoyi/admin build
```

`VITE_CLIENT_ID` 是公开客户端标识，不是密钥，必须与平台 `sys_client` 中 `grantType=password`
的那条客户端一致。工作台也同样需要它（构建工作台时同样注入）。

`services/web` 的构建产物：工作台 dist 部署到 `/`，管理端 dist 部署到 `/admin/`。
`infra/dev/nginx.conf` 可直接作为同源反代模板：白名单前缀、SSE 不缓冲、上传上限与产品限制一致。

## 5. 方式 C：本地开发栈

```bash
cat > infra/dev/.env <<'EOF'
DEV_PG_PASSWORD=<local-password>
DEV_REDIS_PASSWORD=<local-password>
DEV_SERVICE_CREDENTIAL=<local-credential>
DEV_CLIENT_ID=dev-client
DEV_WEB_PORT=8080
EOF

docker compose --env-file infra/dev/.env -f infra/dev/compose.yaml up -d --build
```

启动顺序由 compose 保证：`postgres` → `db-bootstrap`（角色/schema/扩展）→ `db-migrate`
（Flyway 平台链 + 冻结 AI 链）→ `platform` → `web`。开发栈只跑一个后端。

## 6. 数据库迁移

- 统一链：`services/platform/docs/script/sql/postgres`。
- 冻结旧链：`services/platform/docs/script/sql/legacy-ai`，只用于旧库升级验证。
- 版本号不连续，**不要补号、不要改既有迁移、不要另起一条链**。
- 迁移只向前；应用回退不会回滚 schema，回退版本必须兼容已应用的迁移。

## 7. 启动校验

```bash
curl -fsS http://127.0.0.1:6039/auth/tenant/list >/dev/null && echo platform-ok
curl -fsS http://127.0.0.1:8080/admin/ >/dev/null && echo web-ok
```

`actuator` 受鉴权保护，不适合做匿名探针；用上面的公开端点。

部署完成后再做一次业务确认：

1. 打开 `/admin/`，用平台管理员账号登录；
2. 租户管理能看到租户列表；
3. 打开 `/admin/ai`，能看到模型、配额与能力计数；
4. 打开 `/admin/ai/usage`，配额数字与数据库一致。

## 8. 回退

1. 保留上一版 JAR（或镜像 digest）与前端静态目录；
2. 恢复后重启进程/容器；
3. 确认第 7 节两个探针恢复 200；
4. 不要为了回退重跑或改写迁移历史；
5. 数据库与私有对象目录不随应用回退出清理。

## 9. 已知边界

- provider 凭据是部署级，租户不能自带 key。
- 运行配额上限需要运维写入 `ai_tenant_budget`，页面没有写接口。
- 按租户的人民币成本账本尚未实现。
- 生产目标环境需按本清单单独验收；CI 与开发栈的通过不等于生产验收。
