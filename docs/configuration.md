# 运行配置

公开仓库只保留环境变量引用，不保存真实密码或 API Key。部署时通过运行环境提供相应变量。

下表由 `scripts/import/Sanitize-Source.ps1` 根据固定上游快照生成；修改上游版本后重新核对。

| 来源位置 | 环境变量 |
|---|---|
| `services/platform/docs/docker/ minio/ docker-compose.yml:12` | `PROJECT_SERVICES_PLATFORM_DOCS_DOCKER__MINIO__DOCKER_COMPOSE_YML_MINIO_ACCESS_KEY_12` |
| `services/platform/docs/docker/milvus/docker-compose.yml:23` | `PROJECT_SERVICES_PLATFORM_DOCS_DOCKER_MILVUS_DOCKER_COMPOSE_YML_MINIO_ACCESS_KEY_23` |
| `services/platform/docs/docker/ruoyi-ai/docker-compose-all.yaml:25` | `PROJECT_SERVICES_PLATFORM_DOCS_DOCKER_RUOYI_AI_DOCKER_COMPOSE_ALL_YAML_MYSQL_ROOT_PASSWORD_25` |
| `services/platform/docs/docker/ruoyi-ai/docker-compose-all.yaml:89` | `PROJECT_SERVICES_PLATFORM_DOCS_DOCKER_RUOYI_AI_DOCKER_COMPOSE_ALL_YAML_MINIO_ROOT_PASSWORD_89` |
| `services/platform/docs/docker/ruoyi-ai/docker-compose-all.yaml:110` | `PROJECT_SERVICES_PLATFORM_DOCS_DOCKER_RUOYI_AI_DOCKER_COMPOSE_ALL_YAML_SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_PASSWORD_110` |
| `services/platform/docs/docker/ruoyi-ai/docker-compose.yaml:20` | `PROJECT_SERVICES_PLATFORM_DOCS_DOCKER_RUOYI_AI_DOCKER_COMPOSE_YAML_MYSQL_ROOT_PASSWORD_20` |
| `services/platform/docs/docker/ruoyi-ai/docker-compose.yaml:92` | `PROJECT_SERVICES_PLATFORM_DOCS_DOCKER_RUOYI_AI_DOCKER_COMPOSE_YAML_MINIO_ROOT_PASSWORD_92` |
| `services/platform/docs/docker/ruoyi-ai/docker-compose.yaml:115` | `PROJECT_SERVICES_PLATFORM_DOCS_DOCKER_RUOYI_AI_DOCKER_COMPOSE_YAML_SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_PASSWORD_115` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:10` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_USERPASSWORD_10` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:12` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_PASSWORD_12` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:51` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_TOKEN_51` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:83` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_PASSWORD_83` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:194` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_ACCESS_KEY_ID_194` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:196` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_ACCESS_KEY_SECRET_196` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:202` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_ACCESS_KEY_ID_202` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:203` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_ACCESS_KEY_SECRET_203` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:218` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_218` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:224` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_224` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:229` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_229` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:234` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_234` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:238` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_238` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:242` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_242` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:246` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_246` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:250` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_250` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:254` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_254` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:259` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_259` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:263` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_263` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:268` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_268` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:272` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_272` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:276` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_276` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:281` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_281` |
| `services/platform/ruoyi-admin/src/main/resources/application-dev.yml:288` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_CLIENT_SECRET_288` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:11` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_USERPASSWORD_11` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:13` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_PASSWORD_13` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:32` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_TOKEN_32` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:64` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_PASSWORD_64` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:175` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_ACCESS_KEY_ID_175` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:177` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_ACCESS_KEY_SECRET_177` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:183` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_ACCESS_KEY_ID_183` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:184` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_ACCESS_KEY_SECRET_184` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:199` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_199` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:205` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_205` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:210` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_210` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:215` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_215` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:219` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_219` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:223` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_223` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:227` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_227` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:231` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_231` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:235` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_235` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:240` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_240` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:244` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_244` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:249` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_249` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:253` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_253` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:257` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_257` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:262` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_262` |
| `services/platform/ruoyi-admin/src/main/resources/application-prod.yml:269` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_CLIENT_SECRET_269` |
| `services/platform/ruoyi-admin/src/main/resources/application.yml:380` | `PROJECT_SERVICES_PLATFORM_RUOYI_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_YML_API_KEY_380` |
| `services/platform/ruoyi-extend/ruoyi-monitor-admin/src/main/resources/application.yml:17` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_MONITOR_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_YML_PASSWORD_17` |
| `services/platform/ruoyi-extend/ruoyi-monitor-admin/src/main/resources/application.yml:49` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_MONITOR_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_YML_USERPASSWORD_49` |
| `services/platform/ruoyi-extend/ruoyi-monitor-admin/src/main/resources/application.yml:51` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_MONITOR_ADMIN_SRC_MAIN_RESOURCES_APPLICATION_YML_PASSWORD_51` |
| `services/platform/ruoyi-extend/ruoyi-snailjob-server/src/main/resources/application-dev.yml:7` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_SNAILJOB_SERVER_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_PASSWORD_7` |
| `services/platform/ruoyi-extend/ruoyi-snailjob-server/src/main/resources/application-dev.yml:49` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_SNAILJOB_SERVER_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_USERPASSWORD_49` |
| `services/platform/ruoyi-extend/ruoyi-snailjob-server/src/main/resources/application-dev.yml:51` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_SNAILJOB_SERVER_SRC_MAIN_RESOURCES_APPLICATION_DEV_YML_PASSWORD_51` |
| `services/platform/ruoyi-extend/ruoyi-snailjob-server/src/main/resources/application-prod.yml:7` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_SNAILJOB_SERVER_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_PASSWORD_7` |
| `services/platform/ruoyi-extend/ruoyi-snailjob-server/src/main/resources/application-prod.yml:49` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_SNAILJOB_SERVER_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_USERPASSWORD_49` |
| `services/platform/ruoyi-extend/ruoyi-snailjob-server/src/main/resources/application-prod.yml:51` | `PROJECT_SERVICES_PLATFORM_RUOYI_EXTEND_RUOYI_SNAILJOB_SERVER_SRC_MAIN_RESOURCES_APPLICATION_PROD_YML_PASSWORD_51` |
