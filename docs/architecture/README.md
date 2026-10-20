# 产品架构入口

产品为模块化单体：`services/platform` 是唯一后端构建根与 Spring Boot 入口（`ruoyi-admin`），统一承载租户、身份、业务权限、知识库/RAG、Agent（AgentScope 进程内执行）与运行数据；原独立 AI 服务 `services/ai` 已退出仓库，其冻结的 V1..V12 迁移档案保留在 `services/platform/docs/script/sql/legacy-ai`，只用于旧库升级验证。前端位于 `services/web`，用户工作台与管理控制台共享平台认证。

工程化基线提供独立构建、代码代理任务分支、CI 和镜像发布，不代表生产环境已上线；租户隔离与任务恢复等运行语义的验收以工程验收证据为准。
