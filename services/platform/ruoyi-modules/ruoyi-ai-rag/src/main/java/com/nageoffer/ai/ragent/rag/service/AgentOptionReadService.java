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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.vo.AgentOptionVO;

import java.util.List;

/**
 * 用户端智能体发现面读端（F09:4-A）。
 *
 * <p>只读：数据来源仅租户域 {@code ai_agent_profile}（经租户过滤链）与公共模板域
 * {@link com.nageoffer.ai.ragent.template.PublicTemplateRepository} 的 {@code findProfiles()}。
 * 不读提示词正文、不装配引擎闭包、不写任何表、不新增权限行。
 */
public interface AgentOptionReadService {

    /**
     * 当前租户可见的智能体选项（最小投影）。
     *
     * <p>每行都带 {@code executable}（真值表结论）；实现不得返回"没有可执行行"的空列表
     * 而不作声——无可执行行是降级形态，必须留下可观测痕迹。
     */
    List<AgentOptionVO> listOptions();
}
