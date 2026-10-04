package org.ruoyi.aiintegration.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class GatewayMultipartConfigurationTest {
    @Test void onlyExactGatewayUploadRetainsItsRawBody() {
        var resolver = new GatewayMultipartConfiguration().multipartResolver();
        for (String context : new String[]{"", "/platform"}) {
            var request = new MockHttpServletRequest("POST", context + "/api/ai/v1/documents/uploads");
            request.setContextPath(context);
            request.setContentType("multipart/form-data; boundary=test");
            assertFalse(resolver.isMultipart(request));
            request.setRequestURI(context + "/api/ai/v1/documents/uploads-other");
            assertTrue(resolver.isMultipart(request));
            request.setRequestURI(context + "/resource/oss/upload");
            assertTrue(resolver.isMultipart(request));
        }
    }
}
