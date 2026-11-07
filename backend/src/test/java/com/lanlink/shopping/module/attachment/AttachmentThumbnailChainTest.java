package com.lanlink.shopping.module.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import com.lanlink.shopping.module.attachment.service.AttachmentThumbnails;
import com.lanlink.shopping.module.attachment.service.AttachmentThumbStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缩略图异步链路（S5）：认领、复核、生成、回写、失败分级与拓扑。
 *
 * <p>这里要守住的不是"能生成缩略图"，而是消息语义：重投不能重复消耗一次解码，
 * 坏图不能变成无限重试，存储抖动必须留在可观测的失败路径上而不是被当成"没有预览"悄悄吞掉。
 */
class AttachmentThumbnailChainTest {

    private AttachmentMapper mapper;
    private StorageService storage;
    private SimpleMeterRegistry registry;
    private AttachmentImageConsumer consumer;

    @BeforeEach
    void setUp() {
        mapper = mock(AttachmentMapper.class);
        storage = mock(StorageService.class);
        registry = new SimpleMeterRegistry();
        consumer = new AttachmentImageConsumer(mapper, storage, new ObjectMapper(), registry);
    }

    private static byte[] png(int w, int h, int imageType) {
        BufferedImage img = new BufferedImage(w, h, imageType);
        for (int x = 0; x < w; x += 7) {
            for (int y = 0; y < h; y += 7) {
                img.setRGB(x, y, 0x3366CC);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String payload(String no, String key, String contentType) {
        return "{\"attachNo\":\"" + no + "\",\"objectKey\":\"" + key
                + "\",\"contentType\":\"" + contentType + "\",\"fileSize\":123}";
    }

    private Attachment row(String no, String key, String contentType, String thumbStatus) {
        Attachment row = new Attachment();
        row.setAttachNo(no);
        row.setObjectKey(key);
        row.setContentType(contentType);
        row.setThumbStatus(thumbStatus);
        row.setStatus("PENDING");
        row.setThumbAttempts(0);
        return row;
    }

    /** CAS 抢到一行 */
    private void claimWins() {
        when(mapper.update(any(Attachment.class), any())).thenReturn(1);
    }

    private Attachment lastUpdate() {
        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).update(captor.capture(), any());
        return captor.getValue();
    }

    private double outcome(String name) {
        return registry.get("lanlink.attachment.thumbnail.processed").tag("outcome", name).counter().count();
    }

    // ===== 纯渲染 =====

    @Test
    void renderShrinksLongEdgeAndReportsOriginalSize() throws IOException {
        AttachmentThumbnails.Result r = AttachmentThumbnails.render(png(1200, 800, BufferedImage.TYPE_3BYTE_BGR));

        assertEquals(1200, r.width(), "宽度记录的是原件尺寸，列表要按它判断要不要给原图");
        assertEquals(800, r.height());
        assertEquals("image/jpeg", r.contentType());
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(r.content()));
        assertTrue(back.getWidth() <= AttachmentThumbnails.MAX_EDGE
                && back.getHeight() <= AttachmentThumbnails.MAX_EDGE, "长边必须收到 512 内");
        assertEquals(1200.0 / 800, (double) back.getWidth() / back.getHeight(), 0.05, "不能被拉变形");
    }

    @Test
    void renderFlattensTransparentPngIntoJpeg() throws IOException {
        AttachmentThumbnails.Result r = AttachmentThumbnails.render(png(60, 40, BufferedImage.TYPE_INT_ARGB));

        assertEquals("image/jpeg", r.contentType());
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(r.content()));
        assertTrue(!back.getColorModel().hasAlpha(),
                "带 alpha 的源图直接写 JPEG 会让 ImageIO 抛 Bogus input colorspace，必须先铺白底");
    }

    @Test
    void renderRefusesTruncatedImage() {
        byte[] truncated = png(200, 200, BufferedImage.TYPE_3BYTE_BGR);
        byte[] half = java.util.Arrays.copyOf(truncated, truncated.length / 2);

        assertThrows(IOException.class, () -> AttachmentThumbnails.render(half),
                "截断文件必须报错，不能产出半张预览当作成功");
    }

    @Test
    void supportsOnlyFormatsJdkCanDecode() {
        assertTrue(AttachmentThumbnails.supports("image/jpeg"));
        assertTrue(AttachmentThumbnails.supports("IMAGE/PNG"));
        assertTrue(!AttachmentThumbnails.supports("image/webp"), "JDK 无 WebP 解码器，异步侧只能跳过");
        assertTrue(!AttachmentThumbnails.supports("application/pdf"));
        assertTrue(!AttachmentThumbnails.supports(null));
    }

    // ===== 消费链路 =====

    @Test
    void happyPathStoresThumbnailAndWritesReadyRow() {
        byte[] source = png(900, 600, BufferedImage.TYPE_3BYTE_BGR);
        claimWins();
        when(mapper.selectOne(any())).thenReturn(row("AT1", "attachments/a/b.jpg", "image/jpeg",
                AttachmentThumbStatus.PROCESSING));
        when(storage.getObject("attachments/a/b.jpg")).thenReturn(source);

        consumer.consume(payload("AT1", "attachments/a/b.jpg", "image/jpeg"));

        verify(storage).put(eq("attachments/a/thumb/b.jpg"), any(byte[].class), eq("image/jpeg"));
        Attachment written = lastUpdate();
        assertEquals(AttachmentThumbStatus.READY, written.getThumbStatus());
        assertEquals("attachments/a/thumb/b.jpg", written.getThumbKey());
        assertEquals(900, written.getWidth());
        assertEquals(600, written.getHeight());
        assertEquals(1.0, outcome("ready"));
    }

    @Test
    void redeliveryDoesNotDecodeAgain() {
        when(mapper.update(any(Attachment.class), any())).thenReturn(0);

        consumer.consume(payload("AT1", "attachments/a/b.jpg", "image/jpeg"));

        verify(storage, never()).getObject(anyString());
        verify(storage, never()).put(anyString(), any(), anyString());
        assertEquals(1.0, outcome("duplicate"), "抢不到认领就必须在做昂贵工作之前退出");
    }

    @Test
    void poisonPayloadIsRejectedWithoutRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume("{not-json"));
        assertEquals(1.0, outcome("poison"));
    }

    @Test
    void nonImageContentTypeIsSkippedBeforeDecode() {
        claimWins();
        when(mapper.selectOne(any())).thenReturn(row("AT2", "attachments/a/b.pdf", "application/pdf",
                AttachmentThumbStatus.PROCESSING));
        when(storage.getObject("attachments/a/b.pdf")).thenReturn("%PDF-1.4 ...".getBytes());

        consumer.consume(payload("AT2", "attachments/a/b.pdf", "application/pdf"));

        verify(storage, never()).put(anyString(), any(), anyString());
        assertEquals(AttachmentThumbStatus.SKIPPED, lastUpdate().getThumbStatus());
        assertEquals(1.0, outcome("unsupported"));
    }

    @Test
    void magicMismatchIsSkippedNotRetried() {
        claimWins();
        when(mapper.selectOne(any())).thenReturn(row("AT3", "attachments/a/b.jpg", "image/jpeg",
                AttachmentThumbStatus.PROCESSING));
        // 库里写着 jpg、字节其实是 HTML：伪装材料不能因为"重试也许能解"就反复进队列
        when(storage.getObject("attachments/a/b.jpg")).thenReturn("<html>not an image at all</html>".getBytes());

        consumer.consume(payload("AT3", "attachments/a/b.jpg", "image/jpeg"));

        assertEquals(AttachmentThumbStatus.SKIPPED, lastUpdate().getThumbStatus());
        assertEquals(1.0, outcome("magic-mismatch"));
    }

    @Test
    void undecodableImageEndsTerminalInsteadOfLooping() {
        claimWins();
        when(mapper.selectOne(any())).thenReturn(row("AT4", "attachments/a/b.png", "image/png",
                AttachmentThumbStatus.PROCESSING));
        byte[] full = png(200, 200, BufferedImage.TYPE_3BYTE_BGR);
        when(storage.getObject("attachments/a/b.png"))
                .thenReturn(java.util.Arrays.copyOf(full, full.length / 2));

        consumer.consume(payload("AT4", "attachments/a/b.png", "image/png"));

        assertEquals(AttachmentThumbStatus.SKIPPED, lastUpdate().getThumbStatus(),
                "重投一万次也解不出来，判终态才不会把消费者钉死在一张坏图上");
        assertEquals(1.0, outcome("undecodable"));
    }

    @Test
    void storageOutageMarksFailedAndRethrowsForRetry() {
        claimWins();
        when(mapper.selectOne(any())).thenReturn(row("AT5", "attachments/a/b.jpg", "image/jpeg",
                AttachmentThumbStatus.PROCESSING));
        when(storage.getObject("attachments/a/b.jpg")).thenThrow(new IllegalStateException("MinIO 503"));

        assertThrows(IllegalStateException.class,
                () -> consumer.consume(payload("AT5", "attachments/a/b.jpg", "image/jpeg")));

        assertEquals(AttachmentThumbStatus.FAILED, lastUpdate().getThumbStatus(),
                "基础设施抖动要留在 FAILED 上等重试，不能和坏图一样判终态");
        assertEquals(1.0, outcome("infra"));
    }

    @Test
    void missingRowIsNotAThumbnailFailure() {
        claimWins();
        when(mapper.selectOne(any())).thenReturn(null);

        consumer.consume(payload("AT6", "attachments/a/b.jpg", "image/jpeg"));

        verify(storage, never()).getObject(anyString());
        assertEquals(1.0, outcome("missing-row"));
    }

    // ===== 投递 =====

    @Test
    void publisherSendsJsonToOwnExchangeAndSwallowsFailures() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        AttachmentImagePublisher publisher = new AttachmentImagePublisher(rabbit, new ObjectMapper());

        publisher.publishQueued(new AttachmentUploaded("AT7", "attachments/a/b.jpg", "image/jpeg", 10L));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(AttachmentRabbitConfig.EXCHANGE),
                eq(AttachmentRabbitConfig.ROUTING_KEY), body.capture());
        Map<String, Object> sent = new HashMap<>(new ObjectMapper().readValue(body.getValue(), Map.class));
        assertEquals("AT7", sent.get("attachNo"));

        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
        publisher.publishQueued(new AttachmentUploaded("AT8", "k", "image/jpeg", 1L));
    }

    // ===== 拓扑 =====

    @Test
    void topologyHasOwnExchangeOwnDlxAndDurableQueues() {
        AttachmentRabbitConfig config = new AttachmentRabbitConfig();

        assertEquals("lanlink.attachment", config.attachmentExchange().getName());
        assertEquals("lanlink.attachment.dlx", config.attachmentDlx().getName(),
                "不借订单或通知的死信：那两条链路各自可关，借来用会让本链路死信静默丢失");
        assertTrue(config.attachmentProcessQueue().isDurable());
        assertEquals("lanlink.attachment.dlx",
                config.attachmentProcessQueue().getArguments().get("x-dead-letter-exchange"));
        assertEquals("attachment.dlq",
                config.attachmentProcessQueue().getArguments().get("x-dead-letter-routing-key"));
        assertEquals("classic", config.attachmentDlq().getArguments().get("x-queue-type"),
                "DLQ 要能被运维逐条 basic.get 取回复放");
        assertEquals(AttachmentRabbitConfig.QUEUE, config.attachmentBinding().getDestination());
        assertEquals("attachment.process", config.attachmentBinding().getRoutingKey());
    }

    @Test
    void attemptCapIsSharedByConsumerAndSweeper() {
        // 上界只有一处定义，否则消费端与补投扫描会各自算出不同的"还能试几次"
        assertEquals(5, AttachmentThumbStatus.MAX_ATTEMPTS);
        assertTrue(AttachmentThumbStatus.MAX_ATTEMPTS > 0);
    }
}
