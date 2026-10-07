/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 数据库持久层配置类
 * 配置 MyBatis-Plus 相关分页插件等
 *
 * <p><b>归属（E3/C1、C7）</b>：本类只服务独立运行的 ragent 应用（bootstrap）。
 * 内嵌进 platform 后唯一 {@code MybatisPlusInterceptor} 由 platform
 * {@code MybatisPlusConfig} 提供（租户行 → 数据权限 → 分页 → 乐观锁），
 * 唯一 {@code MetaObjectHandler} 由 platform {@code InjectionMetaObjectHandler} 提供
 * （其对非 BaseEntity 实体按 createTime/updateTime(Date) 填充，与 AI 实体字段兼容；
 * {@code deleted} 无 INSERT fill 注解，由列默认值兜底）。
 * 内嵌装配清单<b>不得</b>注册本类，禁止用 allow-bean-definition-overriding 绕过。
 */
@Configuration
public class DataBaseConfiguration {

    /**
     * MyBatis-Plus PostgreSQL 分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * MyBatis-Plus 源数据自动填充类
     */
    @Bean
    public MetaObjectHandler myMetaObjectHandler() {
        return new MyMetaObjectHandler();
    }
}
