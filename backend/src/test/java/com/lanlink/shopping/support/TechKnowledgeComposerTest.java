package com.lanlink.shopping.support;

import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.service.TechKnowledgeComposer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 技术服务知识块的可信边界：演示数据必须自带隔离标记，易变数值不得写进可召回文本，
 * 内部标识与证照号不得入索引。
 */
class TechKnowledgeComposerTest {

    private final TechKnowledgeComposer composer = new TechKnowledgeComposer();

    @Test
    void achievementChunkUsesItsOwnSourceAndPlainIdRef() {
        TechAchievement a = TechKnowledgeFixture.achievement();
        List<TechKnowledgeComposer.Chunk> chunks = composer.fromAchievement(a);

        assertEquals(1, chunks.size());
        TechKnowledgeComposer.Chunk c = chunks.get(0);
        assertEquals(TechKnowledgeComposer.SOURCE_ACHIEVEMENT, c.source());
        assertEquals("1042", c.refId(), "refId 必须是纯主键，前端才能拼出详情页深链");
        assertTrue(c.text().contains("ZL202320456789.1"), "专利号是判别性标签，必须进文本");
        assertTrue(c.text().contains("授权"), "法律状态决定成果能不能交易");
        assertTrue(c.title().contains("废溶剂回收"));
    }

    @Test
    void volatileNumbersAreKeptOutOfEmbedTextAndPointBackToDetail() {
        TechAchievement a = TechKnowledgeFixture.achievement();
        a.setExpectedPrice(new BigDecimal("850000"));
        String text = composer.fromAchievement(a).get(0).text();

        assertFalse(text.contains("850000"), "报价会随时间变化，写进向量文本会让 AI 报过期数字");
        assertTrue(text.contains("回源") || text.contains("详情页"), "必须显式提示命中后回源核对实时值");
    }

    @Test
    void unverifiedRowsCarryDemoMarkerAndVerifiedRowsDoNot() {
        TechOrganization demo = TechKnowledgeFixture.organization();
        demo.setPartnershipStatus("POTENTIAL");
        String demoText = composer.fromOrganization(demo).get(0).text();
        assertTrue(demoText.contains("演示") || demoText.contains("未签约"),
                "未签约机构若不自带标记，AI 会把「生态参考」说成已合作方");

        TechOrganization signed = TechKnowledgeFixture.organization();
        signed.setPartnershipStatus("PARTNER");
        String signedText = composer.fromOrganization(signed).get(0).text();
        assertFalse(signedText.contains("不可作为事实"), "已签约主体不该被打成演示数据");
    }

    @Test
    void internalIdsAndCredentialNumbersNeverEnterChunks() {
        TechExpert e = TechKnowledgeFixture.expert();
        e.setUserId(99L);
        e.setOrganization("沪联技术转移中心（示例）");
        String text = composer.fromExpert(e).get(0).text();

        assertFalse(text.contains("userId"), "内部账号标识不进索引");
        assertFalse(text.contains("99"), "内部账号数值不进索引");
        assertTrue(text.contains("示例"), "实体名自带的示例标注必须原样保留，不能被洗成真实主体");

        TechOrganization o = TechKnowledgeFixture.organization();
        o.setLicenseNo("31200000456");
        assertFalse(composer.fromOrganization(o).get(0).text().contains("31200000456"),
                "执业许可证号属核验材料，不作为可召回知识");
    }

    @Test
    void chunksStayUnderVectorStoreCap() {
        TechAchievement a = TechKnowledgeFixture.achievement();
        a.setSummary("很长".repeat(900));
        a.setHighlights("要点".repeat(400));
        String text = composer.fromAchievement(a).get(0).text();

        assertTrue(text.length() <= 1200, "aikb 的 CHUNK_MAX 是 1200，超长块会被下游裁切");
    }

    @Test
    void chunkNeverInventsEndorsementOrConclusion() {
        TechAchievement a = TechKnowledgeFixture.achievement();
        a.setVerified(0);
        String text = composer.fromAchievement(a).get(0).text();

        for (String banned : List.of("国家级", "政府指定", "官方背书", "保证成交", "已获国家认可")) {
            assertFalse(text.contains(banned), "组合器不得自行加入背书或结论性表述：" + banned);
        }
        assertTrue(text.contains("未核验") || text.contains("演示") || text.contains("机构"),
                "成果评价结论归属必须留给机构，组合器不能把未核验成果说得像已评价");
    }

    @Test
    void emptyRowsProduceNoChunks() {
        assertTrue(composer.fromAchievement(null).isEmpty());
        assertTrue(composer.fromExpert(new TechExpert()).isEmpty(), "缺主键的行不该生成知识块");
    }
}
