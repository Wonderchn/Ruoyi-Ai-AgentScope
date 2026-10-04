package org.ruoyi.aiintegration.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.http.HttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class GatewayRawUploadFilterTest {
    @Test void authenticationParameterLookupCannotConsumeTheUpload() throws Exception {
        byte[] raw="--boundary\r\nraw multipart body\r\n--boundary--".getBytes();
        var request=new MockHttpServletRequest("POST","/api/ai/v1/documents/uploads") {
            @Override public String getParameter(String name){throw new AssertionError("container parameter parsing invoked");}
        };
        request.setContent(raw);request.addHeader("ClientID","test-client");
        new GatewayRawUploadFilter().doFilter(request,new MockHttpServletResponse(),(input,response)->{
            var wrapped=(HttpServletRequest)input;
            assertNull(wrapped.getParameter("clientid"));assertNull(wrapped.getParameterValues("kbId"));
            assertTrue(wrapped.getParameterMap().isEmpty());assertFalse(wrapped.getParameterNames().hasMoreElements());
            assertEquals("test-client",wrapped.getHeader("ClientID"));assertArrayEquals(raw,wrapped.getInputStream().readAllBytes());
        });
    }
}
