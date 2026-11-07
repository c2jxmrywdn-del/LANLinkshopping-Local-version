package com.lanlink.shopping.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 聊天媒体 video 扩展单测（out/refund-map-design.md §1.3 P0-C）：
 * ftyp brand 嗅探 isom→mp4 / qt→mov / M4A 不误收 / PNG 伪装拒绝 / 超限拒绝 / 空文件拒绝。
 * 快乐路径经 upload() 真实落盘，用例结束按返回 URL 回收。
 */
class ChatMediaServiceVideoTest {

    private final ChatMediaService service = new ChatMediaService();

    private String uploadedUrl;

    @AfterEach
    void cleanup() {
        if (uploadedUrl != null) {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.delete(uploadedUrl);
            uploadedUrl = null;
        }
    }

    private static MockMultipartFile file(String name, String contentType, byte[] body) {
        return new MockMultipartFile("file", name, contentType, body);
    }

    /** ftyp 盒头 + brand：前 4 字节任意尺寸，4-7 字节 "ftyp"，8-11 字节 brand */
    private static byte[] ftyp(String brand) {
        byte[] b = new byte[32];
        b[0] = 0; b[1] = 0; b[2] = 0; b[3] = 32;
        b[4] = 'f'; b[5] = 't'; b[6] = 'y'; b[7] = 'p';
        byte[] br = brand.getBytes();
        for (int i = 0; i < 4 && i < br.length; i++) b[8 + i] = br[i];
        return b;
    }

    @Test
    void acceptsIsomBrandAsMp4() throws Exception {
        Map<String, Object> out = service.upload(7L, file("demo.mp4", "video/mp4", ftyp("isom")), "video");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".mp4"), "isom brand 应回落为 .mp4：" + uploadedUrl);
        assertEquals("/api/uploads/chat/", uploadedUrl.substring(0, "/api/uploads/chat/".length()));
    }

    @Test
    void acceptsMp42BrandAsMp4() throws Exception {
        Map<String, Object> out = service.upload(7L, file("demo.mp4", "video/mp4", ftyp("mp42")), "video");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".mp4"));
    }

    @Test
    void acceptsQtBrandAsMov() throws Exception {
        Map<String, Object> out = service.upload(7L, file("demo.mov", "video/quicktime", ftyp("qt  ")), "video");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".mov"), "qt brand 应回落为 .mov：" + uploadedUrl);
    }

    @Test
    void rejectsM4aBrandUnderVideoKind() {
        // M4A 分支先行命中（voice 域），video kind 的 videoExt 白名单拒绝——语音录音不误收为视频
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(7L, file("rec.m4a", "audio/mp4", ftyp("M4A ")), "video"));
        assertTrue(ex.getMessage().contains("MP4/MOV"));
    }

    @Test
    void voiceKindStillAcceptsM4aAfterVideoBranch() throws Exception {
        // 回归护栏：M4A 语音上传不受 video 分支影响
        Map<String, Object> out = service.upload(7L, file("rec.m4a", "audio/mp4", ftyp("M4A ")), "voice");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".m4a"));
    }

    @Test
    void rejectsPngDisguisedAsVideo() {
        byte[] png = new byte[32];
        png[0] = (byte) 0x89; png[1] = 0x50; png[2] = 0x4E; png[3] = 0x47;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(7L, file("fake.mp4", "video/mp4", png), "video"));
        assertTrue(ex.getMessage().contains("MP4/MOV"));
    }

    @Test
    void rejectsUnknownFtypBrand() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(7L, file("weird.mp4", "video/mp4", ftyp("abcd")), "video"));
        assertTrue(ex.getMessage().contains("MP4/MOV"));
    }

    @Test
    void rejectsJpegDisguisedAsVideo() {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(7L, file("photo.mp4", "video/mp4", jpg), "video"));
        assertTrue(ex.getMessage().contains("MP4/MOV"));
    }

    @Test
    void rejectsOverVideoLimit() {
        // 21MB 伪 mp4：魔数合法但超 MAX_VIDEO(20MB) → 体积拦截
        byte[] big = new byte[20 * 1024 * 1024 + 1];
        big[4] = 'f'; big[5] = 't'; big[6] = 'y'; big[7] = 'p';
        big[8] = 'i'; big[9] = 's'; big[10] = 'o'; big[11] = 'm';
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(7L, file("big.mp4", "video/mp4", big), "video"));
        assertTrue(ex.getMessage().contains("20MB"));
    }

    @Test
    void rejectsEmptyFileAndUnknownKind() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> service.upload(7L, file("empty.mp4", "video/mp4", new byte[0]), "video"));
        assertTrue(empty.getMessage().contains("请选择文件"));

        IllegalArgumentException kind = assertThrows(IllegalArgumentException.class,
                () -> service.upload(7L, file("v.mp4", "video/mp4", ftyp("isom")), "film"));
        assertTrue(kind.getMessage().contains("不支持的上传类型"));
    }
}
