# 需要外部服务的上游测试

AI 服务的 `ci` Maven profile 暂时排除以下 8 个 rag 测试类；它们使用完整 Spring 测试上下文，当前导入配置依赖 Redis，并可能进一步依赖 PostgreSQL、向量库、模型或对象存储。它们在本机无这些服务时失败，不能作为无密钥 PR CI 的绿色门禁。

- `InvoiceIndexDocumentTests`
- `SiliconFlowEmbeddingServiceTests`
- `SimpleIntentClassifierTests`
- `MultiQuestionRewriteServiceTests`
- `QueryRewriteTests`
- `RagentCoreApplicationTests`
- `ConversationMessageServiceTests`
- `MilvusCollectionTests`

普通 PR 使用 `mvn -Pci verify` 运行其余测试，`check-surefire.py` 确保真的执行了测试。完整 `mvn verify` 仍会运行这些套件。后续建立可复现的外部测试环境并提供受控供应商凭据后，将其拆成明确的 integration profile 和独立工作流；逐一证明依赖、费用和测试数据清理机制后再接入。
