package com.nageoffer.ai.ragent.attachment;

import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.web.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

/** No public object URLs: authenticated gateway streams only. */
@RestController
@RequestMapping("/internal/ai/v1")
public class ChatAttachmentController {
    private final ChatAttachmentService service;
    private final DeliveryPermits permits;
    public ChatAttachmentController(ChatAttachmentService service,DeliveryPermits permits){this.service=service;this.permits=permits;}
    @PostMapping(value="/chat/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file,jakarta.servlet.http.HttpServletRequest request) {
        String requestId="req-"+UUID.randomUUID();
        try {
            if(file.getSize()>ChatAttachmentPolicy.MAX_BYTES) throw new RunApiException(RunErrorCode.BAD_REQUEST,"Attachment exceeds 5 MiB");
            var parts=request.getParts();
            if(parts.size()!=1 || !parts.iterator().next().getName().equals("file")) throw new RunApiException(RunErrorCode.BAD_REQUEST,"Exactly one file part is required");
            byte[] bytes;
            try(var in=file.getInputStream()){bytes=in.readNBytes(ChatAttachmentPolicy.MAX_BYTES+1);}
            return RunApiResponses.ok(HttpStatus.CREATED,service.upload(PrincipalContext.require(),file.getOriginalFilename(),file.getContentType(),bytes),requestId);
        }catch(RunApiException ex){return RunApiResponses.fail(ex.errorCode(),ex.getMessage(),requestId);}
        catch(java.io.IOException | jakarta.servlet.ServletException ex){return RunApiResponses.fail(RunErrorCode.BAD_REQUEST,"Invalid attachment upload",requestId);}
    }
    @GetMapping("/chat/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String attachmentId,@RequestParam long version) {
        var p=PrincipalContext.require();
        var permit=permits.enter(p,"document.read","attachment:"+attachmentId);
        try {
            var content=service.content(p,attachmentId,version);
            return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                    .header("Content-Security-Policy","default-src 'none'; sandbox")
                    .header("X-AI-Delivery-Permit",permit.permitId()).header("X-AI-Delivery-Operation",permit.operationId())
                    .contentType(MediaType.parseMediaType(content.mime())).body(content.bytes());
        }catch(RuntimeException ex){permit.close();throw ex;}
    }
}
