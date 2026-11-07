package com.lanlink.shopping.module.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 上传成功后把"请生成缩略图"推给消费端。
 *
 * <p>投递失败不抛出、不阻断上传：原件已经落库落桶，预览是派生物，缺了只是列表里少一张小图。
 * 但失败会把行留在 thumb_status=PENDING，由 {@link AttachmentThumbSweeper} 补投，
 * 所以"不阻断"不等于"丢掉"。
 */
@Component
@ConditionalOnProperty(name = "app.mq.attachment-flow", havingValue = "mq", matchIfMissing = true)
public class AttachmentImagePublisher {

    private static final Logger log = LoggerFactory.getLogger(AttachmentImagePublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper json;

    public AttachmentImagePublisher(RabbitTemplate rabbit, ObjectMapper json) {
        this.rabbit = rabbit;
        this.json = json;
    }

    public void publishQueued(AttachmentUploaded payload) {
        try {
            rabbit.convertAndSend(AttachmentRabbitConfig.EXCHANGE, AttachmentRabbitConfig.ROUTING_KEY,
                    json.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("[attachment-mq] 缩略图任务入队失败，交由补投扫描 no={} err={}",
                    payload.attachNo(), e.getMessage());
        }
    }
}
