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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class PgVectorStoreAdminTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RAGDefaultProperties properties = new RAGDefaultProperties();

    private PgVectorStoreAdmin admin() {
        properties.setDimension(1536);
        return new PgVectorStoreAdmin(jdbc, properties);
    }

    @Test
    void acceptsMigrationIndexWithoutRuntimeDdl() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(1536))).thenReturn(true);
        PgVectorStoreAdmin admin = admin();
        assertDoesNotThrow(() -> admin.ensureVectorSpace(new VectorSpaceSpec()));
        assertTrue(admin.vectorSpaceExists(new VectorSpaceId()));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void missingOrUnusableIndexFailsWithMigrationAction() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(1536))).thenReturn(false);
        PgVectorStoreAdmin admin = admin();
        ServiceException failure = assertThrows(ServiceException.class,
                () -> admin.ensureVectorSpace(new VectorSpaceSpec()));
        assertTrue(failure.getMessage().contains("迁移"));
        assertFalse(admin.vectorSpaceExists(new VectorSpaceId()));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void databaseFailureDoesNotReportAReadySpace() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(1536)))
                .thenThrow(new DataAccessResourceFailureException("synthetic unavailable database"));
        PgVectorStoreAdmin admin = admin();
        assertFalse(admin.vectorSpaceExists(new VectorSpaceId()));
        assertThrows(DataAccessResourceFailureException.class,
                () -> admin.ensureVectorSpace(new VectorSpaceSpec()));
        verify(jdbc, never()).execute(anyString());
    }
}
