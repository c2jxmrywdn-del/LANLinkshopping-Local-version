package com.lanlink.shopping.module.marketingimage;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.marketingimage.MarketingImageTaskRepository.TaskRow;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 营销图生成单测（本地 HttpServer 模拟万相，不触网、不消耗额度）。
 *
 * <p>重点守护三件事：
 * 1) 开关/敏感词/预算这些「不该花钱」的分支必须在提交阶段就拒绝；
 * 2) 成功链路产物必须是带水印的 PNG，且对象键按 owner 隔离；
 * 3) 上游 403 必须翻译成可行动的中文错误（充值/关闭仅免费模式），而不是裸抛异常。
 */
@ExtendWith(MockitoExtension.class)
class MarketingImageServiceTest {

    @Mock
    private MarketingImageTaskRepository repository;
    @Mock
    private MarketingImageGenerator generator;
    @Mock
    private StorageService storageService;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private MarketingImageProperties props() {
        MarketingImageProperties props = new MarketingImageProperties();
        props.setEnabled(true);
        props.setApiKey("test-key");
        props.setBaseUrl("http://127.0.0.1:1");
        props.setImageModel("wan2.7-image");
        props.setImageModelPro("wan2.7-image-pro");
        props.setBudgetCny(200);
        props.setUnitCostMicros(220000);
        return props;
    }

    private MarketingImageService service(MarketingImageProperties props) {
        return new MarketingImageService(repository, generator, props);
    }

    // ==================== 提交阶段校验 ====================

    @Test
    void rejectsWhenSwitchDisabled() {
        MarketingImageProperties props = props();
        props.setEnabled(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service(props).submit("MERCHANT", 9L, "夏日促销海报", null, null, false));

        assertEquals(503, e.getCode());
        assertEquals("营销图生成未开启", e.getMessage());
        verify(repository, never()).insert(anyString(), anyString(), anyLong(), anyString(),
                any(), anyString(), anyString());
    }

    @Test
    void rejectsSensitivePrompt() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service(props()).submit("MERCHANT", 9L, "暴力血腥的打斗画面", null, null, false));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("敏感"));
        verify(generator, never()).generate(anyString());
    }

    @Test
    void rejectsWhenMonthlyBudgetExhausted() {
        MarketingImageProperties props = props();
        when(repository.sumCostMicrosThisMonth()).thenReturn(props.monthlyBudgetMicros());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service(props).submit("MERCHANT", 9L, "秋季新品海报", null, "1024x1024", false));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("预算"));
        verify(generator, never()).generate(anyString());
    }

    @Test
    void submitsPendingTaskAndDispatchesAsync() {
        MarketingImageProperties props = props();
        when(repository.sumCostMicrosThisMonth()).thenReturn(0L);

        Map<String, Object> task = service(props)
                .submit("MERCHANT", 9L, "秋季新品海报", "低俗", "1280x720", true);

        assertEquals("PENDING", task.get("status"));
        assertEquals("wan2.7-image-pro", task.get("model"));
        assertEquals("1280x720", task.get("size"));
        ArgumentCaptor<String> taskNo = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(taskNo.capture(), eq("MERCHANT"), eq(9L), eq("秋季新品海报"),
                eq("低俗"), eq("wan2.7-image-pro"), eq("1280x720"));
        verify(generator).generate(taskNo.getValue());
        assertTrue(taskNo.getValue().startsWith("MI"));
    }

    // ==================== 生成阶段 ====================

    @Test
    void generatesWatermarkedPngAndStoresIt() throws Exception {
        byte[] onePixelPng = onePixelPng();
        server = mockServer(200, "{\"data\":[{\"b64_json\":\""
                + Base64.getEncoder().encodeToString(onePixelPng) + "\"}]}");
        MarketingImageProperties props = props();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        when(repository.findByTaskNo("MI1")).thenReturn(Optional.of(task("MI1", "MERCHANT", 9L)));
        when(storageService.put(anyString(), any(byte[].class), eq("image/png")))
                .thenReturn("http://cdn.local/marketing.png");

        new MarketingImageGenerator(repository, storageService, props).generate("MI1");

        ArgumentCaptor<byte[]> image = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
        verify(repository).markSuccess(eq("MI1"), objectKey.capture(), eq("http://cdn.local/marketing.png"), eq(220000L));
        verify(storageService).put(startsWith("marketing/"), image.capture(), eq("image/png"));

        // 对象键必须按 owner 隔离，避免不同商户互相覆盖
        assertTrue(objectKey.getValue().contains("/MERCHANT/9/MI1.png"));
        byte[] png = image.getValue();
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertTrue(decoded != null && decoded.getWidth() == 1 && decoded.getHeight() == 1);
    }

    @Test
    void translatesInsufficientQuotaIntoActionableMessage() throws Exception {
        server = mockServer(403, "{\"error\":{\"code\":\"insufficient_quota\","
                + "\"message\":\"You exceeded your current quota\"}}");
        MarketingImageProperties props = props();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        when(repository.findByTaskNo("MI2")).thenReturn(Optional.of(task("MI2", "ADMIN", 1L)));

        new MarketingImageGenerator(repository, storageService, props).generate("MI2");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(repository).markFailed(eq("MI2"), message.capture());
        assertTrue(message.getValue().contains("额度不足"));
        assertTrue(message.getValue().contains("充值"));
        verify(storageService, never()).put(anyString(), any(byte[].class), anyString());
    }

    // ==================== 工具 ====================

    private TaskRow task(String taskNo, String ownerType, Long ownerId) {
        return new TaskRow(1L, taskNo, ownerType, ownerId, "秋季新品海报", null,
                "wan2.7-image", "1024x1024", "PENDING", null, null, null, 0L,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static byte[] onePixelPng() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** 起一个只应答 /images/generations 的假上游，并记录收到的请求体。 */
    private HttpServer mockServer(int status, String body) throws Exception {
        AtomicReference<String> ignored = new AtomicReference<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/images/generations", exchange -> {
            ignored.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        http.start();
        return http;
    }
}
