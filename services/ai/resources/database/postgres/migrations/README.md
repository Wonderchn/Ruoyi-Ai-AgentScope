# 迁移链已退役（E3/C8，2026-10-05）

本目录自 E3 单元二起是**冻结档案**，不再是任何应用的运行迁移链：

- E2 已把本链（V1..V12）的历史归档为 `platform.flyway_schema_history_ai_legacy`，
  统一库的唯一后续迁移链是 platform 的 `V1..V10+`（见
  `services/platform/docs/script/sql/postgres/`）。
- 本目录文件字节不动，仅作为 E2"旧双 schema 升级"入口的升级输入继续存在
  （`tools/local-pg/` 与 `scripts/ci/verify-unified-migrations.sh` 的模式 B 仍会按字节应用它们）。
- **禁止**在本目录新增迁移；AI 域的一切新表/新列一律走 platform 统一链。
- AI 运行时不持有、不执行任何迁移（无 Flyway 运行时依赖）；
  `TenantContextGuard` 白名单中的迁移历史表条目已随退役移除。
