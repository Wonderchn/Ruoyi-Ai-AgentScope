package org.ruoyi.agent.manager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 架构初始化器
 * 在应用启动完成后自动初始化表结构缓存
 */
@Slf4j
@Component
// 默认不装配：该钩子在每次启动读取表结构元数据，是旧 Agent 链路残留的 MySQL 依赖。
// 需要时显式设 agent.mysql.enabled=true 才恢复。
@ConditionalOnProperty(name = "agent.mysql.enabled", havingValue = "true")
public class TableSchemaInitializer {

    @Autowired(required = false)
    private TableSchemaManager tableSchemaManager;

    /**
     * 应用启动完成后初始化
     */
    @EventListener(ContextRefreshedEvent.class)
    public void initializeOnStartup() {
        if (tableSchemaManager != null) {
            tableSchemaManager.initializeSchema();
        }
    }
}
