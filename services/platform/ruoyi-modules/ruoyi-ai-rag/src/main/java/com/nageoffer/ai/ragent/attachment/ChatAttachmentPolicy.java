package com.nageoffer.ai.ragent.attachment;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.imageio.ImageIO;

/** Bounded, byte-based policy shared by upload and every later use. */
public final class ChatAttachmentPolicy {
    public static final int MAX_BYTES=5*1024*1024;
    public static final int MAX_TEXT=16000;
    private static final Set<String> TYPES=Set.of("text/plain","application/pdf","image/png","image/jpeg");
    private ChatAttachmentPolicy() { }
    public static String validate(String name,String mime,byte[] bytes) {
        if(name==null || name.isBlank() || name.length()>255 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(c->c<32) || !TYPES.contains(mime==null?"":mime)
                || bytes==null || bytes.length==0 || bytes.length>MAX_BYTES)
            throw new IllegalArgumentException("Attachment name, type or size is not supported (maximum 5 MiB)");
        try {
            if("text/plain".equals(mime)) {
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if(text.isBlank() || text.length()>MAX_TEXT || text.indexOf(0)>=0) throw new IllegalArgumentException("UTF-8 attachment text must be 1..16000 characters");
            } else if("application/pdf".equals(mime)) {
                if(bytes.length<5 || !"%PDF-".equals(new String(bytes,0,5,StandardCharsets.US_ASCII)))
                    throw new IllegalArgumentException("Attachment bytes do not match application/pdf");
            } else {
                boolean png=bytes.length>=8 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71
                        && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10;
                boolean jpeg=bytes.length>=3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255;
                if("image/png".equals(mime)?!png:!jpeg) throw new IllegalArgumentException("Attachment bytes do not match image MIME");
                try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers=ImageIO.getImageReaders(in);
                    if(!readers.hasNext()) throw new IllegalArgumentException("Invalid image");
                    var reader=readers.next();
                    try {
                        reader.setInput(in,true,true);
                        long width=reader.getWidth(0),height=reader.getHeight(0);
                        if(width<1 || height<1 || width>8192 || height>8192 || width*height>16000000)
                            throw new IllegalArgumentException("Image exceeds the 16 megapixel limit");
                    } finally { reader.dispose(); }
                }
            }
            return mime;
        } catch(IllegalArgumentException ex) { throw ex; }
        catch(Exception ex) { throw new IllegalArgumentException("Invalid attachment content",ex); }
    }
}
