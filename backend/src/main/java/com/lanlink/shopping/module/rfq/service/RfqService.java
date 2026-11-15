package com.lanlink.shopping.module.rfq.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.rfq.dto.RfqCreateRequest;
import com.lanlink.shopping.module.rfq.entity.Rfq;
import com.lanlink.shopping.module.rfq.mapper.RfqMapper;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import com.lanlink.shopping.service.SequenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 买家询价单：发布、我的列表、本人详情与过期扫表。
 *
 * <p>取号走 SequenceService（行锁内原子自增），不用 TechNoGenerator（其 javadoc 自述非并发唯一）。
 * 合规文案复用技术服务守卫（平台不承诺成交、不出现政府背书类表述）。
 * 反滥用口径：同账号同标题（未取消）≥5 条即拒绝，具体化 1688「重复询价」规则。
 */
@Service
public class RfqService {

    private static final Logger log = LoggerFactory.getLogger(RfqService.class);
    /** 报价户数上限（我方设计值，不归因任何平台） */
    static final int MAX_QUOTES = 10;
    static final int REPEAT_TITLE_LIMIT = 5;

    private final RfqMapper rfqMapper;
    private final SequenceService sequenceService;
    private final TechComplianceGuard complianceGuard;

    public RfqService(RfqMapper rfqMapper, SequenceService sequenceService,
                      TechComplianceGuard complianceGuard) {
        this.rfqMapper = rfqMapper;
        this.sequenceService = sequenceService;
        this.complianceGuard = complianceGuard;
    }

    @Transactional
    public Rfq create(Long userId, RfqCreateRequest req) {
        String title = req.getTitle() == null ? "" : req.getTitle().trim();
        if (title.isEmpty()) throw new BusinessException(400, "询价标题不能为空");
        if (req.getQuantity() == null || req.getQuantity().signum() <= 0) {
            throw new BusinessException(400, "采购数量需大于 0");
        }
        if (req.getQuoteDeadline() == null || !req.getQuoteDeadline().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "报价截止时间必须晚于当前时间");
        }
        complianceGuard.validate("询价标题", title);
        complianceGuard.validate("需求描述", req.getDescription());
        // 第 5 条同标题即拒绝（≥5 条即违规的口径：已有 4 条时本次发布将凑满 5 条）
        if (rfqMapper.countSameTitle(userId, title) + 1 >= REPEAT_TITLE_LIMIT) {
            throw new BusinessException(400,
                    "重复询价过多（最近 7 天内同标题将达 " + REPEAT_TITLE_LIMIT + " 条），请修改标题或稍后再试");
        }

        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Rfq rfq = new Rfq();
        rfq.setRfqNo("RFQ" + day + sequenceService.nextNo("rfq:" + day, 6));
        rfq.setUserId(userId);
        rfq.setTitle(title);
        rfq.setDescription(req.getDescription());
        rfq.setProdId(req.getProdId());
        rfq.setQuantity(req.getQuantity());
        rfq.setUnit(req.getUnit());
        rfq.setQuoteDeadline(req.getQuoteDeadline());
        rfq.setMaxQuotes(MAX_QUOTES);
        rfq.setStatus("PUBLISHED");
        // 显式回填时间：MyBatis-Plus 仅插入非空字段，不显式赋值时响应视图里的 createTime 会是 null
        rfq.setCreateTime(LocalDateTime.now());
        rfq.setUpdateTime(rfq.getCreateTime());
        rfqMapper.insert(rfq);
        return rfq;
    }

    public List<Rfq> listMine(Long userId) {
        return rfqMapper.selectList(Wrappers.<Rfq>lambdaQuery()
                .eq(Rfq::getUserId, userId).orderByDesc(Rfq::getCreateTime));
    }

    public Rfq detail(Long userId, String rfqNo) {
        Rfq rfq = rfqMapper.selectOwned(userId, rfqNo);
        if (rfq == null) throw new BusinessException(404, "询价单不存在");
        return rfq;
    }

    @Scheduled(fixedDelayString = "${app.rfq.expire-interval-ms:600000}",
            initialDelayString = "${app.rfq.expire-initial-delay-ms:60000}")
    public void scheduledExpire() {
        int expired = expireOverdue();
        if (expired > 0) log.info("[rfq] 过期扫表置 EXPIRED {} 条", expired);
    }

    /** 单条条件 UPDATE，天然并发安全；无跨表副作用，不额外包事务 */
    public int expireOverdue() {
        return rfqMapper.expireOverdue();
    }
}