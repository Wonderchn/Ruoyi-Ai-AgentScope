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

package com.nageoffer.ai.ragent.audit.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.audit.controller.request.BizChangeLogPageRequest;
import com.nageoffer.ai.ragent.audit.controller.vo.BizChangeLogVO;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogReadScope;

/**
 * 业务变更日志查询（F18 op3）。
 *
 * <p>RW-23：读方法都要求显式 {@link BizChangeLogReadScope} —— 限域是调用点的决定，
 * 缺主体/缺 tenant 的构造 fail-closed，服务内部不再有"不过滤"的路径。
 */
public interface BizChangeLogService {

    IPage<BizChangeLogVO> page(BizChangeLogPageRequest requestParam, BizChangeLogReadScope scope);

    /**
     * 详情；不在限域内（含跨租户 id）与不存在同外显。
     */
    BizChangeLogVO get(String id, BizChangeLogReadScope scope);
}
