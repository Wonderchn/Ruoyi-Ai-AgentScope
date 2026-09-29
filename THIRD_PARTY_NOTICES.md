# 上游来源与许可证

本仓库导入了以下上游项目的受 Git 跟踪文件，并进行了配置清理与 CI 调整：

- [ruoyi-ai](https://github.com/ageerle/ruoyi-ai)，提交 `7c7e64311e6ab7ac42ef6d89b87a0c662b620a35`，许可证见 `services/platform/LICENSE`。
- [ragent](https://github.com/nageoffer/ragent)，提交 `aefd979634cf72f6c531caf25317faab165e3d99`，许可证见 `services/ai/LICENSE`。
- [ruoyi-web](https://github.com/ageerle/ruoyi-web)，提交 `834fd3dedeb297681e74425040b19821ea332319`，MIT 许可证见 `services/ruoyi-web/license`；该前端的 npm 依赖许可尚未逐包盘点。

完整来源清单见 `docs/upstreams.lock.json`。各导入目录内可能还有第三方文件和依赖，其原有声明保留在对应目录；正式产品分发前需按具体依赖和使用方式生成完整清单。
