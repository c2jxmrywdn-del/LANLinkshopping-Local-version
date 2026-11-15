package com.lanlink.shopping.module.merchantexit.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.ExitStatusVO;
import com.lanlink.shopping.module.merchantexit.entity.MerchantDeposit;
import com.lanlink.shopping.module.merchantexit.entity.MerchantExitLog;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantDepositMapper;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantExitLogMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 退店只读查询（商户状态/保证金台账/管理端列表与详情）。全部 JdbcTemplate 直查或 MP 分页。 */
@Service
public class MerchantExitQueryService {

    /** 分页上限：防止 size=1000000 这类请求把整表拉进内存。 */
    private static final long MAX_PAGE_SIZE = 200;

    private final JdbcTemplate jdbc;
    private final MerchantDepositMapper depositMapper;
    private final MerchantExitLogMapper logMapper;
    private final MerchantDepositService depositService;

    public MerchantExitQueryService(JdbcTemplate jdbc, MerchantDepositMapper depositMapper,
                                    MerchantExitLogMapper logMapper, MerchantDepositService depositService) {
        this.jdbc = jdbc;
        this.depositMapper = depositMapper;
        this.logMapper = logMapper;
        this.depositService = depositService;
    }

    public ExitStatusVO status(Long merId) {
        List<ExitStatusVO> rows = jdbc.query(
                "SELECT exit_status, exit_request_time, notice_end_time, close_time, exit_reject_reason"
                        + " FROM t_merchant WHERE mer_id = ? AND deleted = 0",
                (rs, i) -> new ExitStatusVO(rs.getString("exit_status"),
                        toLdt(rs.getTimestamp("exit_request_time")),
                        toLdt(rs.getTimestamp("notice_end_time")),
                        toLdt(rs.getTimestamp("close_time")),
                        rs.getString("exit_reject_reason"), null),
                merId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "商户不存在");
        }
        ExitStatusVO base = rows.get(0);
        return new ExitStatusVO(base.exitStatus(), base.requestTime(), base.noticeEndTime(),
                base.closeTime(), base.rejectReason(), depositService.balanceOf(merId));
    }

    public Map<String, Object> deposits(Long merId, long current, long size) {
        long safeSize = size <= 0 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        long safeCurrent = current <= 0 ? 1 : current;
        IPage<MerchantDeposit> p = depositMapper.selectPage(new Page<>(safeCurrent, safeSize),
                Wrappers.<MerchantDeposit>lambdaQuery()
                        .eq(MerchantDeposit::getMerId, merId)
                        .orderByDesc(MerchantDeposit::getDepositId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", p.getRecords());
        out.put("total", p.getTotal());
        out.put("current", safeCurrent);
        out.put("size", safeSize);
        return out;
    }

    public List<MerchantExitLog> logs(Long merId) {
        return logMapper.selectList(Wrappers.<MerchantExitLog>lambdaQuery()
                .eq(MerchantExitLog::getMerId, merId)
                .orderByAsc(MerchantExitLog::getId));
    }

    public List<MerchantDeposit> allDeposits(Long merId) {
        return depositMapper.selectList(Wrappers.<MerchantDeposit>lambdaQuery()
                .eq(MerchantDeposit::getMerId, merId)
                .orderByAsc(MerchantDeposit::getDepositId));
    }

    public List<Long> dueMerchantIds() {
        return jdbc.queryForList("SELECT mer_id FROM t_merchant WHERE deleted = 0"
                + " AND exit_status = 'PUBLIC_NOTICE' AND notice_end_time IS NOT NULL"
                + " AND notice_end_time <= NOW()", Long.class);
    }

    public Map<String, Object> adminList(long current, long size, String exitStatus) {
        StringBuilder where = new StringBuilder(
                " FROM t_merchant m LEFT JOIN t_enterprise e ON e.ent_id = m.ent_id"
                        + " WHERE m.deleted = 0 AND m.exit_status IS NOT NULL ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(exitStatus)) {
            where.append("AND m.exit_status = ? ");
            args.add(exitStatus.trim());
        }
        String select = "SELECT m.mer_id, m.user_id, e.name AS enterprise_name, m.review_status,"
                + " m.exit_status, m.exit_request_time, m.notice_end_time, m.close_time,"
                + " m.exit_reason, m.exit_reject_reason " + where;
        return page(select, "ORDER BY m.mer_id DESC", current, size, args);
    }

    public Map<String, Object> adminRow(Long merId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.mer_id, m.user_id, e.name AS enterprise_name, m.review_status,"
                        + " m.exit_status, m.exit_request_time, m.notice_end_time, m.close_time,"
                        + " m.exit_reason, m.exit_reject_reason"
                        + " FROM t_merchant m LEFT JOIN t_enterprise e ON e.ent_id = m.ent_id"
                        + " WHERE m.mer_id = ? AND m.deleted = 0", merId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "商户不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> page(String selectSql, String orderBy, long current, long size,
                                     List<Object> args) {
        long safeSize = size <= 0 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        long safeCurrent = current <= 0 ? 1 : current;
        long offset = Math.max(0, (safeCurrent - 1) * safeSize);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + selectSql + ") t", Long.class,
                args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList(selectSql + " " + orderBy + " LIMIT ? OFFSET ?",
                append(args, safeSize, offset));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", total == null ? 0L : total);
        out.put("current", safeCurrent);
        out.put("size", safeSize);
        return out;
    }

    private static Object[] append(List<Object> args, Object... more) {
        Object[] r = Arrays.copyOf(args.toArray(), args.size() + more.length);
        System.arraycopy(more, 0, r, args.size(), more.length);
        return r;
    }

    private static LocalDateTime toLdt(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
