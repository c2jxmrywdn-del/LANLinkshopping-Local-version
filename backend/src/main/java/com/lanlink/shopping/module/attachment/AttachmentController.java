package com.lanlink.shopping.module.attachment;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.attachment.service.AttachmentBizType;
import com.lanlink.shopping.module.attachment.service.AttachmentService;
import com.lanlink.shopping.module.attachment.service.AttachmentView;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 材料中转端点。
 *
 * <p>资源型地址（会被 &lt;img&gt;/&lt;a&gt; 直接引用），因此错误用真实 HTTP 状态码而不是项目
 * 常规的「200 + body code」——后者对浏览器资源请求等于"请求成功但内容是 JSON"，只会渲染成破图。
 * 先例见 controller/CustomerCommunicationController 的 ResponseEntity 用法。
 */
@RestController
@RequestMapping("/attachment")
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    private final AttachmentService attachments;
    private final AuditService auditService;

    public AttachmentController(AttachmentService attachments, AuditService auditService) {
        this.attachments = attachments;
        this.auditService = auditService;
    }

    @PostMapping("/upload")
    public R<AttachmentView> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("bizType") String bizType,
                                    HttpServletRequest request) throws IOException {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        AttachmentBizType type = AttachmentBizType.fromWireName(bizType);
        AttachmentView view = attachments.upload(type, file.getOriginalFilename(), file.getBytes(), userId);
        // 审计只记单号与业务域：桶匿名可读，对象键进了审计表等于留下一条可下载地址
        auditService.record(userId, "ATTACHMENT_UPLOAD",
                "上传材料 " + view.no() + "（业务域 " + type.wireName() + "，" + view.size() + " 字节）", request);
        return R.ok(view);
    }

    @PostMapping("/bind")
    public R<Void> bind(@RequestBody BindRequest body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        boolean admin = UserContext.identity(request).has("admin:all");
        attachments.bind(body.attachmentNo(), AttachmentBizType.fromWireName(body.bizType()),
                body.bizId(), userId, admin);
        auditService.record(userId, "ATTACHMENT_BIND",
                "绑定材料 " + body.attachmentNo() + " 到 " + body.bizType() + "#" + body.bizId(), request);
        return R.ok(null);
    }

    @GetMapping("/list")
    public R<List<AttachmentView>> list(@RequestParam("bizType") String bizType,
                                        @RequestParam(value = "bizId", required = false) Long bizId,
                                        HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return R.ok(attachments.list(userId, AttachmentBizType.fromWireName(bizType), bizId,
                UserContext.identity(request).has("admin:all")));
    }

    @DeleteMapping("/{attachmentNo}")
    public R<Void> delete(@PathVariable("attachmentNo") String attachmentNo, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        attachments.delete(attachmentNo, userId, UserContext.identity(request).has("admin:all"));
        auditService.record(userId, "ATTACHMENT_DELETE", "下线材料 " + attachmentNo, request);
        return R.ok(null);
    }

    /** 绑定入参：只接受单号与业务坐标，对象键永远不由客户端提供。 */
    public record BindRequest(String attachmentNo, String bizType, Long bizId) {
    }

    @GetMapping("/{attachmentNo}/content")
    public ResponseEntity<byte[]> content(@PathVariable("attachmentNo") String attachmentNo,
                                          @RequestParam(value = "variant", required = false) String variant,
                                          HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        boolean admin = UserContext.identity(request).has("admin:all");
        AttachmentService.Content content;
        try {
            content = attachments.loadContent(attachmentNo, userId, admin, "thumb".equals(variant));
        } catch (BusinessException e) {
            // 资源请求只看状态码：把业务码原样抬成 HTTP 状态，空响应体不带出任何路径或键名
            if (e.getCode() != null && e.getCode() >= 500) {
                log.error("[attachment] 材料中转失败 no={}", attachmentNo, e);
            }
            return ResponseEntity.status(e.getCode()).build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                // 会话 Cookie 在生产态是 SameSite=None：不带这两组头时，第三方页面里的
                // <img src=中转地址> 会带着用户 Cookie 直接把他人材料渲染出来。
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .cacheControl(CacheControl.noStore())
                .body(content.bytes());
    }
}
