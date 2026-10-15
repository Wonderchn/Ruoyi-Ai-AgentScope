package com.nageoffer.ai.ragent.attachment;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Red baseline is executable against the old tree: assertions fail, not compilation. */
@org.junit.jupiter.api.Tag("dev")
class ChatAttachmentPolicyTest {
    private static Class<?> policy() {
        try { return Class.forName("com.nageoffer.ai.ragent.attachment.ChatAttachmentPolicy"); }
        catch (ClassNotFoundException e) { throw new AssertionError("Attachment bytes currently have no admission policy",e); }
    }
    private static Object validate(String name,String mime,byte[] bytes) throws Exception {
        return policy().getMethod("validate",String.class,String.class,byte[].class).invoke(null,name,mime,bytes);
    }
    @Test void utf8DocumentHasARealPositivePath() throws Exception {
        assertThat(validate("note.txt","text/plain","private note".getBytes(java.nio.charset.StandardCharsets.UTF_8))) .isNotNull();
    }
    @Test void mimeSpoofingMustFailBeforeStorage() {
        assertThatThrownBy(()->validate("invoice.png","image/png","not a png".getBytes()))
            .isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(IllegalArgumentException.class);
    }
    @Test void oversizedAndExecutableFilesAreRejected() {
        assertThatThrownBy(()->validate("large.txt","text/plain",new byte[5*1024*1024+1]))
            .isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->validate("payload.html","text/html","<script>bad()</script>".getBytes()))
            .isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(IllegalArgumentException.class);
    }
    @Test void imageModelInputCannotBeSilentlyReducedToText() throws Exception {
        var gateway=Class.forName("com.nageoffer.ai.ragent.ingest.RealChatGateway");
        var method=java.util.Arrays.stream(gateway.getDeclaredMethods()).filter(m->m.getName().equals("serializeMessages")).findFirst();
        assertThat(method).as("gateway must explicitly serialize structured attachment content").isPresent();
        var message=new ChatMessage(ChatMessage.Role.USER,"describe this");
        var setter=java.util.Arrays.stream(ChatMessage.class.getMethods()).filter(m->m.getName().equals("setImageDataUrls")).findFirst();
        assertThat(setter).as("transient image bytes must reach provider content parts").isPresent();
        setter.get().invoke(message,List.of("data:image/png;base64,iVBORw0KGgo="));
        method.get().setAccessible(true);
        assertThat(method.get().invoke(null,List.of(message)).toString()).contains("image_url","data:image/png;base64,");
    }
}
