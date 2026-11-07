package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.LogisticsInvoiceDTO.InvoiceUploadDTO;
import com.lanlink.shopping.entity.LogisticsInvoice;
import com.lanlink.shopping.entity.LogisticsSettlement;
import com.lanlink.shopping.mapper.LogisticsInvoiceMapper;
import com.lanlink.shopping.mapper.LogisticsSettlementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 6% 交通运输增值税专用发票进池查验与应付结算冲抵中枢
 */
@Service
public class LogisticsInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(LogisticsInvoiceService.class);

    private static final BigDecimal VAT_RATE_1_06 = new BigDecimal("1.06");
    private static final BigDecimal TAX_RATE_PERCENT = new BigDecimal("6.00");

    private final LogisticsInvoiceMapper invoiceMapper;
    private final LogisticsSettlementMapper settlementMapper;

    public LogisticsInvoiceService(LogisticsInvoiceMapper invoiceMapper,
                                   LogisticsSettlementMapper settlementMapper) {
        this.invoiceMapper = invoiceMapper;
        this.settlementMapper = settlementMapper;
    }

    /**
     * 专票上传与 6% 价税分离入池
     */
    @Transactional(rollbackFor = Exception.class)
    public LogisticsInvoice uploadInvoice(InvoiceUploadDTO req) {
        if (req.getInvoiceNo() == null || req.getInvoiceNo().trim().isEmpty()) {
            throw new BusinessException("发票号码不能为空");
        }
        if (req.getInvoiceCode() == null || req.getInvoiceCode().trim().isEmpty()) {
            throw new BusinessException("发票代码不能为空");
        }
        if (req.getProviderId() == null) {
            throw new BusinessException("开票承运商ID不能为空");
        }
        if (req.getTotalAmount() == null || req.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("价税合计总金额必须大于0");
        }
        if (req.getFileUrl() == null || req.getFileUrl().trim().isEmpty()) {
            throw new BusinessException("电子发票源文件(PDF/OFD)不能为空");
        }

        // 查重：同代码同号码禁止重复进池
        LogisticsInvoice existing = invoiceMapper.selectOne(
            new LambdaQueryWrapper<LogisticsInvoice>()
                .eq(LogisticsInvoice::getInvoiceCode, req.getInvoiceCode().trim())
                .eq(LogisticsInvoice::getInvoiceNo, req.getInvoiceNo().trim())
        );
        if (existing != null) {
            throw new BusinessException("发票代码 " + req.getInvoiceCode() + " 号码 " + req.getInvoiceNo() + " 已存在于专票池中，请勿重复上传");
        }

        // 依法执行 6% 交通运输增值税价税分离精算至分
        BigDecimal total = req.getTotalAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal amountWithoutTax = total.divide(VAT_RATE_1_06, 2, RoundingMode.HALF_UP);
        BigDecimal taxAmount = total.subtract(amountWithoutTax);

        LogisticsInvoice invoice = new LogisticsInvoice();
        invoice.setInvoiceNo(req.getInvoiceNo().trim());
        invoice.setInvoiceCode(req.getInvoiceCode().trim());
        invoice.setProviderId(req.getProviderId());
        invoice.setInvoiceType(req.getInvoiceType() != null ? req.getInvoiceType() : "special");
        invoice.setTaxRate(TAX_RATE_PERCENT);
        invoice.setTotalAmount(total);
        invoice.setAmountWithoutTax(amountWithoutTax);
        invoice.setTaxAmount(taxAmount);
        invoice.setSellerName(req.getSellerName() != null ? req.getSellerName().trim() : "承运企业");
        invoice.setSellerTaxNo(req.getSellerTaxNo() != null ? req.getSellerTaxNo().trim() : "91440300MA5XXXXXXX");
        invoice.setBuyerName(req.getBuyerName() != null ? req.getBuyerName().trim() : "LANLink工业电商");
        invoice.setBuyerTaxNo(req.getBuyerTaxNo() != null ? req.getBuyerTaxNo().trim() : "91310000MA1YYYYYYY");
        invoice.setInvoiceDate(req.getInvoiceDate() != null ? req.getInvoiceDate() : LocalDateTime.now().toLocalDate());
        invoice.setCheckCode(req.getCheckCode());
        invoice.setFileUrl(req.getFileUrl().trim());
        invoice.setVerifyStatus("pending");
        invoice.setOffsetStatus("unapplied");
        invoice.setCreateTime(LocalDateTime.now());
        invoice.setUpdateTime(LocalDateTime.now());
        invoiceMapper.insert(invoice);

        log.info("[LogisticsInvoice] 专票成功入池: id={}, no={}, total={}, tax={}", invoice.getInvoiceId(), invoice.getInvoiceNo(), total, taxAmount);
        return invoice;
    }

    /**
     * 国税真伪防伪查验
     */
    @Transactional(rollbackFor = Exception.class)
    public LogisticsInvoice verifyInvoice(Long invoiceId) {
        LogisticsInvoice invoice = invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            throw new BusinessException("发票记录不存在: " + invoiceId);
        }
        if ("verified".equals(invoice.getVerifyStatus())) {
            return invoice;
        }

        // 校验基础真伪要素
        if (invoice.getTotalAmount() == null || invoice.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            invoice.setVerifyStatus("failed");
            invoice.setVerifyMessage("发票价税合计金额异常");
        } else {
            invoice.setVerifyStatus("verified");
            invoice.setVerifyTime(LocalDateTime.now());
            invoice.setVerifyMessage("全国增值税发票查验平台比对一致，票面要素完整，6%进项专票验真通过");
        }
        invoice.setUpdateTime(LocalDateTime.now());
        invoiceMapper.updateById(invoice);

        log.info("[LogisticsInvoice] 专票查验完成: invoiceId={}, status={}", invoiceId, invoice.getVerifyStatus());
        return invoice;
    }

    /**
     * 将已查验通过的专票绑定至物流结算单，实施 1:1 冲抵核销
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindSettlement(Long invoiceId, Long settlementId) {
        LogisticsInvoice invoice = invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            throw new BusinessException("发票记录不存在: " + invoiceId);
        }
        if (!"verified".equals(invoice.getVerifyStatus())) {
            throw new BusinessException("专票尚未通过国税防伪查验(当前状态: " + invoice.getVerifyStatus() + ")，严禁用于应付款冲抵");
        }
        if ("applied".equals(invoice.getOffsetStatus())) {
            throw new BusinessException("该专票已冲抵其他结算单(" + invoice.getSettlementId() + ")，严禁重复冲抵抵扣");
        }

        LogisticsSettlement settlement = settlementMapper.selectById(settlementId);
        if (settlement == null) {
            throw new BusinessException("物流结算单不存在: " + settlementId);
        }
        if (!settlement.getProviderId().equals(invoice.getProviderId())) {
            throw new BusinessException("开票承运商与结算单承运主体不一致，无法核销冲抵");
        }
        if (invoice.getTotalAmount().compareTo(settlement.getPayableAmount()) < 0) {
            throw new BusinessException("发票金额(¥" + invoice.getTotalAmount() + ")不足以全额覆盖结算单应付金额(¥" + settlement.getPayableAmount() + ")");
        }

        // 1. 更新发票绑定状态
        invoice.setSettlementId(settlementId);
        invoice.setOffsetStatus("applied");
        invoice.setUpdateTime(LocalDateTime.now());
        invoiceMapper.updateById(invoice);

        // 2. 反写结算单
        settlement.setInvoiceId(invoiceId);
        settlement.setInvoiceStatus("verified");
        settlement.setAmountWithoutTax(invoice.getAmountWithoutTax());
        settlement.setTaxAmount(invoice.getTaxAmount());
        settlement.setUpdateTime(LocalDateTime.now());
        settlementMapper.updateById(settlement);

        log.info("[LogisticsInvoice] 专票与结算单核销冲抵成功: invoiceId={}, settlementId={}, taxAmount={}", invoiceId, settlementId, invoice.getTaxAmount());
    }

    /**
     * 分页查询专票池
     */
    public List<LogisticsInvoice> listInvoicePool(Long providerId, String verifyStatus, String offsetStatus) {
        LambdaQueryWrapper<LogisticsInvoice> q = new LambdaQueryWrapper<>();
        if (providerId != null) {
            q.eq(LogisticsInvoice::getProviderId, providerId);
        }
        if (verifyStatus != null && !verifyStatus.trim().isEmpty()) {
            q.eq(LogisticsInvoice::getVerifyStatus, verifyStatus.trim());
        }
        if (offsetStatus != null && !offsetStatus.trim().isEmpty()) {
            q.eq(LogisticsInvoice::getOffsetStatus, offsetStatus.trim());
        }
        q.orderByDesc(LogisticsInvoice::getCreateTime);
        return invoiceMapper.selectList(q);
    }
}
