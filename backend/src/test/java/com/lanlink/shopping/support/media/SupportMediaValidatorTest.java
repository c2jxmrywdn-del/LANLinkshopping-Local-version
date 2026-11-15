package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 媒体准入校验：魔数、扩展名、大小边界。
 */
class SupportMediaValidatorTest {

    private final SupportMediaProperties props = new SupportMediaProperties();
    private final SupportMediaValidator validator = new SupportMediaValidator(props);

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void sniffsKnownFormats() {
        assertEquals("jpg", validator.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0)).extension());
        assertEquals("png", validator.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)).extension());
        assertEquals("gif", validator.sniff(ascii("GIF89a....")).extension());
        assertEquals("webp", validator.sniff(ascii("RIFF0000WEBPVP8 ")).extension());
        assertEquals("pdf", validator.sniff(ascii("%PDF-1.7 ....")).extension());
        assertEquals("zip", validator.sniff(bytes(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)).extension());
        assertEquals("mp4", validator.sniff(ascii("0000ftypisom0000")).extension());
        assertEquals("mov", validator.sniff(ascii("0000ftypqt  0000")).extension());
        assertEquals("m4a", validator.sniff(ascii("0000ftypM4A 0000")).extension());
        assertEquals("mp3", validator.sniff(bytes(0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0)).extension());
        assertEquals("wav", validator.sniff(ascii("RIFF0000WAVEfmt ")).extension());
        assertEquals("ogg", validator.sniff(ascii("OggS0000000000")).extension());
        assertEquals("webm", validator.sniff(bytes(0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0)).extension());
    }

    @Test
    void rejectsUnknownOrTooShortContent() {
        assertThrows(BusinessException.class, () -> validator.sniff(new byte[]{1, 2}));
        assertThrows(BusinessException.class, () -> validator.sniff(ascii("<html>hello</html>")));
    }

    @Test
    void acceptsWhitelistedExtensionAndNormalizesJpeg() {
        SupportMediaValidator.ValidatedRequest request =
                validator.validateRequest(SupportMediaKind.IMAGE, "contract.JPEG", "image/jpeg", 1024L);
        assertEquals("jpg", request.extension());
        assertEquals("image/jpeg", request.contentType());
    }

    @Test
    void rejectsExtensionOutsideKind() {
        assertThrows(BusinessException.class,
                () -> validator.validateRequest(SupportMediaKind.IMAGE, "payload.exe", "image/png", 1024L));
        assertThrows(BusinessException.class,
                () -> validator.validateRequest(SupportMediaKind.DOCUMENT, "clip.mp4", "application/pdf", 1024L));
    }

    @Test
    void enforcesSizeBoundary() {
        long max = SupportMediaKind.IMAGE.maxBytes(props);
        assertEquals(max, validator.validateRequest(SupportMediaKind.IMAGE, "a.png", "image/png", max).size());
        assertThrows(BusinessException.class,
                () -> validator.validateRequest(SupportMediaKind.IMAGE, "a.png", "image/png", max + 1));
        assertThrows(BusinessException.class,
                () -> validator.validateRequest(SupportMediaKind.IMAGE, "a.png", "image/png", 0L));
        assertThrows(BusinessException.class,
                () -> validator.validateRequest(SupportMediaKind.IMAGE, "a.png", "image/png", null));
    }

    @Test
    void rejectsContentTypeMismatch() {
        assertThrows(BusinessException.class,
                () -> validator.validateRequest(SupportMediaKind.IMAGE, "a.png", "application/pdf", 1024L));
        assertThrows(BusinessException.class,
                () -> validator.validateRequest(SupportMediaKind.IMAGE, "a.png", "image/png\r\nX-Evil: 1", 1024L));
    }

    @Test
    void confirmsMagicMatchesDeclaredKindAndExtension() {
        SupportMediaValidator.Detected detected = validator.validateUploaded(
                SupportMediaKind.IMAGE, "png", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A));
        assertEquals("image/png", detected.contentType());

        // 声明是图片但内容是 PDF：魔数复核必须拒绝
        assertThrows(BusinessException.class, () -> validator.validateUploaded(
                SupportMediaKind.IMAGE, "png", ascii("%PDF-1.7 ....")));
        // 魔数是 PNG 但扩展名写 jpg：同样拒绝
        assertThrows(BusinessException.class, () -> validator.validateUploaded(
                SupportMediaKind.IMAGE, "jpg", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)));

        // WebM 录音：声明为 AUDIO 且魔数为 webm 时正常通过
        SupportMediaValidator.Detected webmAudio = validator.validateUploaded(
                SupportMediaKind.AUDIO, "webm", bytes(0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0));
        assertEquals(SupportMediaKind.AUDIO, webmAudio.kind());
        assertEquals("webm", webmAudio.extension());
        assertEquals("audio/webm", webmAudio.contentType());
    }
}
