package com.lanlink.shopping.module.attachment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.AttachmentUploaded;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 附件业务服务：对象键名、字节中转与附件行的唯一出口。 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private static final String RELAY_URL_PREFIX = "/api/attachment/";
    private static final String RELAY_URL_SUFFIX = "/content";
    private static final int MAX_FILE_NAME_LENGTH = 200;
    private static final long LIST_LIMIT = 50L;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_BOUND = "BOUND";
    /** 对外可见：回收/补投扫描都要按它排除已失效行，避免两处各写一份字面量 */
    public static final String STATUS_EXPIRED = "EXPIRED";
    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final StorageService storage;
    private final AttachmentMapper attachmentMapper;
    private final List<AttachmentOwnership> ownershipResolvers;

    /**
     * 缩略图任务投递（app.mq.attachment-flow=event 时该 Bean 不存在，这里为 null）。
     * 用字段可选注入而不是构造参数：投递失败不该改变上传的行为，更不该把已有构造签名的
     * 测试与调用方全部拖进来。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lanlink.shopping.module.attachment.AttachmentImagePublisher imagePublisher;

    public AttachmentService(StorageService storage, AttachmentMapper attachmentMapper,
                             List<AttachmentOwnership> ownershipResolvers) {
        this.storage = storage;
        this.attachmentMapper = attachmentMapper;
        this.ownershipResolvers = ownershipResolvers;
    }

    /**
     * 材料出站地址：后端中转端点，而非桶地址。
     * 桶是匿名只读（minio-init 执行 mc anonymous set download），把凭 key 可下载当成访问控制
     * 会让材料在 key 进入日志或浏览器历史时失控；中转端点由登录态与归属校验保护。
     */
    public String contentUrl(String attachmentNo) {
        return RELAY_URL_PREFIX + attachmentNo + RELAY_URL_SUFFIX;
    }

    /**
     * 清单可见性决策，与 SQL 拼装分离：wrapper 的列名与参数都要等 MyBatis 注册后才能解析，
     * 在单测里不可内省，所以把"谁能看到什么"做成可断言的纯函数，wrapper 只是它的薄翻译层。
     */
    public record Scope(Long ownerUserId, String bizType, Long bizId, String requiredStatus) {
    }

    static Scope scopeOf(Long userId, AttachmentBizType bizType, Long bizId, boolean admin) {
        if (admin && bizId != null) {
            return new Scope(null, bizType.wireName(), bizId, STATUS_BOUND);
        }
        return new Scope(userId, bizType.wireName(), null, null);
    }

    /**
     * 材料清单。本人看自己的全部状态（挂起件也要能删），运营按业务对象只看已绑定的材料
     * ——未提交的挂起件不该出现在审核台。
     * 条数上限固定，不随调用方参数放大。
     */
    public List<AttachmentView> list(Long userId, AttachmentBizType bizType, Long bizId, boolean admin) {
        Scope scope = scopeOf(userId, bizType, bizId, admin);
        LambdaQueryWrapper<Attachment> query = Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getBizType, scope.bizType());
        if (scope.ownerUserId() != null) {
            query.eq(Attachment::getOwnerUserId, scope.ownerUserId());
        }
        if (scope.bizId() != null) {
            query.eq(Attachment::getBizId, scope.bizId());
        }
        if (scope.requiredStatus() != null) {
            query.eq(Attachment::getStatus, scope.requiredStatus());
        }
        query.orderByDesc(Attachment::getAttachmentId);

        Page<Attachment> page = new Page<>(1, LIST_LIMIT);
        page.setSearchCount(false);
        return attachmentMapper.selectList(page, query).stream()
                .map(this::toView)
                .toList();
    }

    private AttachmentView toView(Attachment row) {
        String thumbStatus = row.getThumbStatus();
        boolean ready = AttachmentThumbStatus.READY.equals(thumbStatus)
                && row.getThumbKey() != null && !row.getThumbKey().isBlank();
        return new AttachmentView(row.getAttachNo(), contentUrl(row.getAttachNo()), row.getFileName(),
                row.getFileSize() == null ? 0L : row.getFileSize(), row.getContentType(), row.getStatus(),
                thumbStatus, ready ? contentUrl(row.getAttachNo()) + AttachmentView.THUMB_URL_QUERY : null);
    }

    /**
     * 删除材料：先把行标失效，再删对象。
     *
     * <p>顺序不能反——对象删了而行还在，前端会拿到一条永远下载失败的记录；行先失效最坏只留下
     * 无人引用的对象，可交给回收与运维兜底。删对象失败不向用户报错：业务上已经删除成功，
     * 把存储故障冒成"删除失败"只会让人反复点击、制造更多残留。
     * <p>已绑定（BOUND）属于交付内容，普通用户不能自行抹除，只有运营能下线。
     */
    public void delete(String attachmentNo, Long userId, boolean admin) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        Attachment row = requireRow(attachmentNo);
        if (!admin && !Objects.equals(row.getOwnerUserId(), userId)) {
            throw new BusinessException(403, "无权操作该材料");
        }
        if (STATUS_EXPIRED.equals(row.getStatus())) {
            return;
        }
        if (STATUS_BOUND.equals(row.getStatus()) && !admin) {
            throw new BusinessException(400, "材料已交付，需由平台运营下线");
        }

        Attachment dead = new Attachment();
        dead.setStatus(STATUS_EXPIRED);
        int marked = attachmentMapper.update(dead, Wrappers.<Attachment>lambdaUpdate()
                .eq(Attachment::getAttachNo, attachmentNo)
                .eq(Attachment::getStatus, row.getStatus()));
        if (marked == 0) {
            // 读行到置失效之间被别人抢先（例如刚完成绑定）：此时删对象会抹掉别人的交付材料，收手
            return;
        }
        try {
            storage.delete(row.getObjectKey());
        } catch (RuntimeException e) {
            log.warn("[attachment] 对象删除失败，行已置 EXPIRED 等待兜底清理 no={}", attachmentNo, e);
        }
        // 派生缩略图跟着原件一起删：它不在任何列表里，残留后没人能发现，桶只会越积越大
        if (row.getThumbKey() != null && !row.getThumbKey().isBlank()) {
            try {
                storage.delete(row.getThumbKey());
            } catch (RuntimeException e) {
                log.warn("[attachment] 缩略图对象删除失败 no={}", attachmentNo, e);
            }
        }
    }

    /**
     * 材料上传：类型由内容魔数决定，键名服务端生成。
     *
     * <p>这里刻意不加 @Transactional：行必须先独立提交，再去写对象。包成单事务时写失败会连行一起
     * 回滚，留下"库里查不到、桶里可能已有对象"的哑状态；现在失败会留下一条 EXPIRED 行可被审计与排查。
     */
    public AttachmentView upload(AttachmentBizType bizType, String originalFileName,
                                 byte[] content, Long ownerUserId) {
        AttachmentContents.Detected detected = AttachmentContents.sniff(content);
        String objectKey = AttachmentKeys.newKey(bizType, detected.extension());

        String no = nextNo();
        Attachment attachment = new Attachment();
        attachment.setAttachNo(no);
        attachment.setBizType(bizType.wireName());
        attachment.setOwnerUserId(ownerUserId);
        attachment.setFileName(sanitizeFileName(originalFileName, detected.extension()));
        attachment.setObjectKey(objectKey);
        attachment.setContentType(detected.contentType());
        attachment.setFileSize((long) content.length);
        attachment.setSha256(sha256Hex(content));
        attachment.setStatus(STATUS_PENDING);
        attachment.setVisibility("PRIVATE");
        // PDF 没有预览语义，直接落终态：让它进队列只会让消费者回源一次再判 SKIPPED
        attachment.setThumbStatus(AttachmentThumbnails.supports(detected.contentType())
                ? AttachmentThumbStatus.PENDING : AttachmentThumbStatus.SKIPPED);
        // create_time/update_time 不在这里赋值：表已有 DEFAULT CURRENT_TIMESTAMP，两处都写就是两个真值源
        attachmentMapper.insert(attachment);

        try {
            storage.put(objectKey, content, detected.contentType());
        } catch (RuntimeException e) {
            Attachment dead = new Attachment();
            dead.setStatus(STATUS_EXPIRED);
            attachmentMapper.update(dead, Wrappers.<Attachment>lambdaUpdate()
                    .eq(Attachment::getAttachNo, no));
            throw e;
        }

        // 原件已经稳了才投递：反过来会出现"队列里有任务、桶里还没有字节"的必然失败。
        // 投递失败不回滚上传——行仍是 PENDING，补投扫描会再推一次。
        if (AttachmentThumbStatus.PENDING.equals(attachment.getThumbStatus()) && imagePublisher != null) {
            imagePublisher.publishQueued(AttachmentUploaded.of(attachment));
        }

        return new AttachmentView(no, contentUrl(no), attachment.getFileName(),
                content.length, detected.contentType(), attachment.getStatus(),
                attachment.getThumbStatus(), null);
    }

    /**
     * 把挂起件绑到业务对象。
     *
     * <p>检查次序是安全约束而非风格：先归属后业务域，否则非 owner 可以用 400/403 的差异
     * 逐个线名试探，读出他人材料的业务域——这比枚举单号更强的 oracle。
     * <p>写库用「status='PENDING' 条件更新」而不是 updateById：后者会把读到的旧 update_time
     * 一起写回 SET，压掉表上的 ON UPDATE CURRENT_TIMESTAMP，审计时间戳就永远停在上传时刻。
     * 同域同对象的重复绑定视为成功且不写库，方便前端重试与网络抖动重放。
     */
    public void bind(String attachmentNo, AttachmentBizType bizType, Long bizId,
                     Long userId, boolean admin) {
        Attachment row = requireRow(attachmentNo);
        if (!admin && !Objects.equals(row.getOwnerUserId(), userId)) {
            throw new BusinessException(403, "无权操作该材料");
        }
        if (!bizType.wireName().equals(row.getBizType())) {
            throw new BusinessException(400, "材料与业务域不匹配");
        }
        if (STATUS_BOUND.equals(row.getStatus())) {
            if (Objects.equals(row.getBizId(), bizId)) {
                return;
            }
            throw new BusinessException(400, "材料已绑定其他业务对象");
        }
        if (!STATUS_PENDING.equals(row.getStatus())) {
            throw new BusinessException(400, "材料已失效，不能绑定");
        }
        // 附件归属人只证明"是谁传的"，不证明"要挂的业务对象是他的"。
        // 无实现支持该业务域（库里没有归属人列）时一律拒绝自助绑定，运营仍可直接绑定。
        if (!admin) {
            if (AttachmentOwnership.unsupportedByAll(ownershipResolvers, bizType)) {
                throw new BusinessException(403, "该业务域暂不支持自助绑定，请联系平台运营");
            }
            boolean ownsTarget = ownershipResolvers.stream()
                    .filter(r -> r.supports(bizType))
                    .anyMatch(r -> r.owns(bizType, bizId, userId));
            if (!ownsTarget) {
                throw new BusinessException(403, "无权将材料绑定到该业务对象");
            }
        }

        Attachment payload = new Attachment();
        payload.setStatus(STATUS_BOUND);
        payload.setBizId(bizId);
        int updated = attachmentMapper.update(payload, Wrappers.<Attachment>lambdaUpdate()
                .eq(Attachment::getAttachNo, attachmentNo)
                .eq(Attachment::getStatus, STATUS_PENDING));
        if (updated == 0) {
            // 条件更新抢输：并发的另一条请求已经改过状态，按最新值重新判定，不把覆盖当成成功
            Attachment latest = requireRow(attachmentNo);
            if (STATUS_BOUND.equals(latest.getStatus()) && Objects.equals(latest.getBizId(), bizId)) {
                return;
            }
            throw new BusinessException(400, "材料已绑定其他业务对象");
        }
    }

    /**
     * 取材料字节供中转下载。对象缺失回 404 而不是让本地读抛出带绝对路径的 IO 异常，
     * 否则 500 的日志与响应会把服务器目录结构暴露出去。
     */
    public Content loadContent(String attachmentNo, Long userId, boolean admin) {
        return loadContent(attachmentNo, userId, admin, false);
    }

    /**
     * @param thumbnail true 时取异步生成的预览而非原件；预览还没就绪返回 404，
     *                  调用方据此显示占位，而不是悄悄回落到原件让一张 8MB 原图挤进列表页。
     */
    public Content loadContent(String attachmentNo, Long userId, boolean admin, boolean thumbnail) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        Attachment row = requireRow(attachmentNo);
        if (!admin && !Objects.equals(row.getOwnerUserId(), userId)) {
            throw new BusinessException(403, "无权访问该材料");
        }
        if (STATUS_EXPIRED.equals(row.getStatus())) {
            // 删除是"先置失效再删对象"：对象删失败时字节仍在，但业务上已经删除，
            // 不认状态就等于让用户点下的删除按钮长期不生效
            throw new BusinessException(404, "材料不存在");
        }
        if (thumbnail) {
            if (!AttachmentThumbStatus.READY.equals(row.getThumbStatus())
                    || row.getThumbKey() == null || row.getThumbKey().isBlank()) {
                throw new BusinessException(404, "预览尚未生成");
            }
            return new Content(thumbFileName(row.getFileName()), "image/jpeg",
                    storage.getObject(row.getThumbKey()));
        }
        if (!storage.exists(row.getObjectKey())) {
            throw new BusinessException(404, "材料内容已失效");
        }
        return new Content(row.getFileName(), row.getContentType(), storage.getObject(row.getObjectKey()));
    }

    /** 预览文件名：换掉扩展名，避免前端按 .pdf 后缀去渲染一张 jpg */
    private static String thumbFileName(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : "preview") + ".jpg";
    }

    /** 中转下载载荷：只有展示名、服务端判定的类型与字节，不含对象键。 */
    public record Content(String fileName, String contentType, byte[] bytes) {
    }

    private Attachment requireRow(String attachmentNo) {
        Attachment row = attachmentMapper.selectOne(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getAttachNo, attachmentNo));
        if (row == null) {
            throw new BusinessException(404, "材料不存在");
        }
        return row;
    }

    /** 单号 = AT + 秒级时间戳 + 6 位随机段；库上有唯一索引兜底，秒内碰撞不会写出两行。 */
    private static String nextNo() {
        return "AT" + NO_STAMP.format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 原始名只作展示：先剥掉路径段（两端分隔符都要防），再按码点限长。
     * 剥完分隔符后残留的 ".." 已不具备逃逸能力，所以不做全局删点——那会把 "合同..v2.pdf"
     * 这类合法名称改坏；截断也不能按 UTF-16 单元做，否则会切出孤立代理项写进库和 JSON。
     */
    static String sanitizeFileName(String originalFileName, String extension) {
        String name = originalFileName == null ? "" : originalFileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            return "material." + extension;
        }
        if (name.codePointCount(0, name.length()) <= MAX_FILE_NAME_LENGTH) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, MAX_FILE_NAME_LENGTH));
    }
}
