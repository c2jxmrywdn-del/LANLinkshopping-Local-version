package com.lanlink.shopping.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lanlink.shopping.module.campaign.dto.CampaignView;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.pricemonitor.dto.PriceAlertView;
import com.lanlink.shopping.module.pricemonitor.entity.PriceAlert;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 遗留模块响应 DTO 与实体序列化字段一致（含 Campaign 派生 promoType）。 */
class LegacyViewContractTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private void assertSameFields(Object entity, Object view) throws Exception {
        assertEquals(fieldsOf(entity), fieldsOf(view),
                "DTO 字段与实体序列化字段不一致：" + view.getClass().getSimpleName());
    }

    private Set<String> fieldsOf(Object value) throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(value));
        Set<String> names = new TreeSet<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    @Test
    void campaignViewMatchesEntityFields() throws Exception {
        assertSameFields(new Campaign(), CampaignView.of(new Campaign()));
    }

    @Test
    void priceAlertViewMatchesEntityFields() throws Exception {
        assertSameFields(new PriceAlert(), PriceAlertView.of(new PriceAlert()));
    }
}
