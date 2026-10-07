package com.nageoffer.ai.ragent.runtime;

import com.nageoffer.ai.ragent.authorization.AuthorizedDownloadService;
import com.nageoffer.ai.ragent.ingest.EmbeddingGateway;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.runtime.*;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class D1RuntimePortBoundaryTest {
    @Test
    void workerContractsAndTheirDtosDoNotExposeFrameworkOrAdapterTypes() {
        for (Class<?> port : List.of(DocumentPort.class, ChatPort.class, MinerUPort.class,
                ChunkingPort.class, DocumentFileReader.class, EmbeddingGateway.class, PrivateObjectStore.class,
                AuthorizedRetrievalPort.class)) {
            assertTrue(port.isInterface(), port.getName());
            for (Class<?> type : port.getDeclaredClasses()) {
                for (var field : type.getDeclaredFields()) neutral(field.getGenericType());
            }
            for (var method : port.getDeclaredMethods()) {
                neutral(method.getGenericReturnType());
                Arrays.stream(method.getGenericParameterTypes()).forEach(this::neutral);
            }
        }
    }

    @Test
    void runtimeAndAclAreOwnedByRuntimeWhileContractsAreOwnedByApi() {
        var origin = RunAccessService.class.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(origin, AuthorizedDownloadService.class.getProtectionDomain().getCodeSource().getLocation());
        assertEquals(origin, com.nageoffer.ai.ragent.runtime.exec.DocumentIngestExecutor.class.getProtectionDomain().getCodeSource().getLocation());
        assertNotEquals(origin, DocumentPort.class.getProtectionDomain().getCodeSource().getLocation());
        assertEquals(DocumentPort.class.getProtectionDomain().getCodeSource().getLocation(),
                EmbeddingGateway.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private void neutral(Type type) {
        String signature = type.getTypeName();
        for (String forbidden : List.of("org.springframework", "jakarta.", "io.agentscope",
                "com.fasterxml", "framework.", "ingest.DocumentDao", "ingest.LocalMinerUClient", "rag.service.")) {
            assertFalse(signature.contains(forbidden), signature);
        }
    }
}
