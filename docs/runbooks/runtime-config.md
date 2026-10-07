# 发布、撤销与回滚运行权威

内嵌部署使用 `platform + web`，显式选择 `prod,embedded` 或 `dev,embedded`。运行权威在当前登录租户的 `platform.ai_runtime_config_revision`，操作与审计同事务提交。`/chat/config` 修改旧配置表，响应头 `X-AI-Affects-Runtime-Authority: false` 与页面提示均说明它不影响运行权威。

## 准备

1. 用统一迁移器应用新增 V28；不要修改已冻结迁移。V28 只登记 `ai:config:read`、`ai:config:publish`、`ai:config:revoke` 三个独立权限，默认不分配给任何角色或套餐。管理员按租户给发布人员授予所需权限，再重新登录刷新策略版本。
2. 通过环境配置统一 PostgreSQL、Redis、对象存储和提供方连接。`application-embedded.yml` 覆盖旧 prod 的 MySQL 主库形态，使用 `PLATFORM_DB_HOST/PORT/NAME/SCHEMA/USERNAME/PASSWORD`；默认 schema 为 `platform,extensions`。数据库连接池等待默认 3 秒、验证 1 秒，驱动连接默认 3 秒、socket 等待默认 5 秒，分别可用 `AI_DB_*_TIMEOUT_*` 配置。
3. 连接绑定必须是 `p2.providers.connections.entries.<providerId>.*`。`endpoint` 填完整请求路径，例如 `https://api.deepseek.com/chat/completions`；`api-key` 只经环境注入。`credential-ref` 仅接受 `env:`、`vault:`、`secret:` 或 `masked:` 引用，禁止放明文 key。密钥不出现在发布请求、日志或证据里。
4. 确认真实外发开关、提供方白名单和预算。发布版本不会自动批准外发。未批准的提供方应在 run 路径拒绝，不能以“发布成功”判定模型调用已经可用。

以下是请求体示例，不包含登录凭据。使用当前租户的已有登录会话与部署要求的客户端头；不要把会话令牌粘贴进共享文档。

```json
{
  "providerId": "deepseek",
  "modelId": "deepseek-flash",
  "catalogVersion": "deployment-catalog-v1",
  "credentialRef": "env:deepseek",
  "dimension": 1536,
  "params": { "temperature": 0.7, "maxTokens": 1024 }
}
```

模型名与目录版本必须对应部署已验证的提供方契约；示例不能替代真实可用性验证。参数只接受 `temperature`、`maxTokens`、`topP`、`presencePenalty`、`frequencyPenalty`、`seed`，服务端生成规范 JSON 与 SHA-256；拒绝密钥、连接地址、嵌套对象和越界参数。

## 发布

向 `POST /api/ai/v1/runtime-config/revisions` 提交请求。返回 HTTP 200、`code=200` 与新 `revisionId/revisionNo/paramsHash` 才表示权威版本已提交。操作者与租户来自当前身份，不能通过 body 指定。并发发布按租户事务锁串行分配版本号。

保存发布前的已验证版本 ID，再用 `GET /api/ai/v1/runtime-config/revisions/{revisionId}` 读取发布结果。检查 provider、model、目录、维度、参数哈希及 state；该接口不回传连接密钥。用新 run 验证受理绑定的新版本，再验证一次真实输出与失败拒绝。已有 run 保留受理时的配置绑定，不可通过修改旧行换掉其事实。

## 撤销与回滚

- 撤销：`POST /api/ai/v1/runtime-config/revisions/{revisionId}/revoke`。需要 `ai:config:revoke`，旧版本变为 `REVOKED` 并追加审计。撤销不是删除；不要删版本或审计行。
- 回滚：`POST /api/ai/v1/runtime-config/revisions/{previousVerifiedRevisionId}/rollback`。需要 `ai:config:publish`，从历史版本追加一个新的 `PUBLISHED` 版本，生成新 ID/序号，不编辑历史行。连接引导、维度和参数仍重新验证。
- 发布错误时，先撤销错误版本，再从发布前保存的已验证版本追加回滚，读取结果并执行新 run 验证。若没有已验证历史版本，停止新提交并修正配置后发布，不能伪造“回滚成功”。

恢复后的验收证据必须绑定新源码提交、JAR SHA-256、部署配置和新 revision ID。旧报告保留原哈希，不能通过更新台账基线把旧结果改成新版本通过。数据库迁移的降级与应用配置回滚是不同操作；本手册不执行数据库降级。

## 故障诊断

`CONFIG_AUTHORITY_UNAVAILABLE` 或 `DEPENDENCY_UNAVAILABLE` 为 HTTP 503，修复依赖后重试。屏障未过租约或仍有有效 ACTIVE permit 时不解除；只有既有 CAS 条件满足才自愈。`reconciled_at/reconciled_by` 记录本轮恢复来源；不要直接把屏障改为 OPEN 绕过有效操作。

检索接口由网关在最终字节交付后自动发送许可回执，调用方不需要手动 ACK。空授权集合返回空列表或拒绝指定资源，不扩为全库。受控 MVC 测试只证明本地路由、包络与许可生命周期，真实数据库、embedding、MinerU 与浏览器需在候选部署另跑。MinerU 地址按实际运行位置配置，避免把容器、宿主和隧道地址混用。

数据库检查必须限定 schema 与 relation；`platform_mut` 等历史镜像不能通过同名列或约束被误认作权威。例如：

```sql
SELECT n.nspname, r.relname, c.conname, pg_get_constraintdef(c.oid)
FROM pg_constraint c
JOIN pg_class r ON r.oid = c.conrelid
JOIN pg_namespace n ON n.oid = r.relnamespace
WHERE n.nspname = 'platform'
  AND r.relname = 'ai_runtime_config_revision';
```

本流程不删除历史 schema。备份、依赖盘点与隔离库演练完成后，才由维护者另行安排清理。
