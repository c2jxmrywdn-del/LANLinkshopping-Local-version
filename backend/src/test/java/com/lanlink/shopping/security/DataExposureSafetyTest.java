package com.lanlink.shopping.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.GlobalExceptionHandler;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据安全专项测试（前端信息防泄露规范）：
 * 1. 实体序列化后不得包含存储层字段（逻辑删除标记、内部JSON快照、渠道密钥）；
 * 2. 数据库异常返回前端的文案必须脱敏，禁止出现 SQL/表名/字段名详情。
 */
class DataExposureSafetyTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void providerNeverLeaksApiKeyOrStorageFields() throws Exception {
        LogisticsProvider p = new LogisticsProvider();
        p.setProviderId(1L);
        p.setProviderName("顺丰");
        p.setApiKey("SECRET-KEY-XYZ");       // 渠道密钥：可写入但不得外发
        p.setCapabilityJson("{\"cod\":true}"); // 内部JSON：不得外发
        p.setDeleted(1);                       // 逻辑删除标记：不得外发
        String json = om.writeValueAsString(p);
        assertFalse(json.contains("SECRET-KEY-XYZ"), "渠道密钥泄漏到序列化输出: " + json);
        assertFalse(json.contains("capabilityJson"), "内部JSON字段名泄漏: " + json);
        assertFalse(json.contains("\"deleted\""), "逻辑删除标记泄漏: " + json);
        // 业务字段正常输出
        assertTrue(json.contains("顺丰"));
    }

    @Test
    void productNeverLeaksTierPriceJsonOrDeleted() throws Exception {
        Product prod = new Product();
        prod.setProdId(1L);
        prod.setTitle("测试商品");
        prod.setPrice(new BigDecimal("9.9"));
        prod.setTierPriceJson("[{\"min\":10,\"price\":8}]");
        prod.setDeleted(0);
        String json = om.writeValueAsString(prod);
        assertFalse(json.contains("tierPriceJson"), "阶梯价内部JSON泄漏: " + json);
        assertFalse(json.contains("\"deleted\""), "逻辑删除标记泄漏: " + json);
    }

    @Test
    void waybillNeverLeaksFreightCalcSnapshot() throws Exception {
        LogisticsWaybill w = new LogisticsWaybill();
        w.setWaybillId(1L);
        w.setWaybillNo("LL202610160001");
        w.setFreightCalcJson("{\"first\":12}");
        w.setDeleted(0);
        String json = om.writeValueAsString(w);
        assertFalse(json.contains("freightCalcJson"), "计费快照内部JSON泄漏: " + json);
        assertFalse(json.contains("\"deleted\""), "逻辑删除标记泄漏: " + json);
    }

    @Test
    void templateNeverLeakRegionRuleJson() throws Exception {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setTemplateId(1L);
        t.setTemplateName("模板");
        t.setRegionRuleJson("[{\"regions\":[\"新疆\"],\"surcharge\":10}]");
        t.setDeleted(0);
        String json = om.writeValueAsString(t);
        assertFalse(json.contains("regionRuleJson"), "区域加价规则JSON泄漏: " + json);
    }

    @Test
    void aftersaleNeverLeakEvidenceJson() throws Exception {
        LogisticsAftersale a = new LogisticsAftersale();
        a.setAftersaleId(1L);
        a.setAftersaleNo("LS202610160001");
        a.setEvidenceJson("[\"http://img/1.png\"]");
        a.setDeleted(0);
        String json = om.writeValueAsString(a);
        assertFalse(json.contains("evidenceJson"), "凭证内部JSON泄漏: " + json);
    }

    @Test
    void userNeverLeakPasswordOrDeleted() throws Exception {
        User u = new User();
        u.setUserId(1L);
        u.setPhone("13800000000");
        u.setPassword("hashed-secret");
        u.setDeleted(0);
        String json = om.writeValueAsString(u);
        assertFalse(json.contains("hashed-secret"), "密码泄漏: " + json);
        assertFalse(json.contains("password"), "密码字段名泄漏: " + json);
        assertFalse(json.contains("\"deleted\""), "逻辑删除标记泄漏: " + json);
    }

    @Test
    void dataAccessExceptionResponseIsSanitized() {
        // 数据库异常原文含 SQL/表名/字段名，绝不能出现在返回给前端的 message 中
        DataAccessResourceFailureException dbEx = new DataAccessResourceFailureException(
                "### Error querying database. Cause: java.sql.SQLException: "
                        + "Table 'lanlink_shopping.t_order' doesn't exist ... SELECT * FROM t_order WHERE order_no = ?");
        R<Void> r = new GlobalExceptionHandler().handleDataAccess(dbEx);
        String msg = r.getMessage();
        assertNotNull(msg);
        assertFalse(msg.contains("t_order"), "表名泄漏到错误响应: " + msg);
        assertFalse(msg.toLowerCase().contains("select"), "SQL语句泄漏到错误响应: " + msg);
        assertFalse(msg.contains("SQLException"), "数据库异常类型泄漏到错误响应: " + msg);
    }

    @Test
    void allEntitiesSuppressDeletedField() throws Exception {
        // 存储层逻辑删除标记 deleted 永不出现在任何实体的序列化输出中
        Object[] entities = {
                new LogisticsProvider(), new LogisticsTemplate(), new LogisticsWaybill(),
                new LogisticsAftersale(), new LogisticsSettlement(), new Product(), new Merchant()
        };
        for (Object e : entities) {
            String json = om.writeValueAsString(e);
            assertFalse(json.contains("\"deleted\""),
                    e.getClass().getSimpleName() + " 序列化输出包含存储层字段 deleted: " + json);
        }
    }
}
