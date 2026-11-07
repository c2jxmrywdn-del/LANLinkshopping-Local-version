package com.lanlink.shopping.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lanlink.shopping.module.techservice.api.TechViews;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechAppraisal;
import com.lanlink.shopping.module.techservice.entity.TechConsultation;
import com.lanlink.shopping.module.techservice.entity.TechDeal;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechHosting;
import com.lanlink.shopping.module.techservice.entity.TechMilestone;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechPolicy;
import com.lanlink.shopping.module.techservice.entity.TechPost;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 契约回归：实体 → DTO 的 JSON 字段集合必须完全一致，保证「实体不出模块」不改变外部 API。
 */
class TechViewContractTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

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
    void demandContract() throws Exception {
        assertSameFields(new TechDemand(), TechViews.ofDemand(new TechDemand()));
    }

    @Test
    void consultationContract() throws Exception {
        assertSameFields(new TechConsultation(), TechViews.ofConsultation(new TechConsultation()));
    }

    @Test
    void appraisalContract() throws Exception {
        assertSameFields(new TechAppraisal(), TechViews.ofAppraisal(new TechAppraisal()));
    }

    @Test
    void hostingContract() throws Exception {
        assertSameFields(new TechHosting(), TechViews.ofHosting(new TechHosting()));
    }

    @Test
    void dealContract() throws Exception {
        assertSameFields(new TechDeal(), TechViews.ofDeal(new TechDeal()));
    }

    @Test
    void expertContract() throws Exception {
        assertSameFields(new TechExpert(), TechViews.ofExpert(new TechExpert()));
    }

    @Test
    void projectAndMilestoneContract() throws Exception {
        assertSameFields(new TechProject(), TechViews.ofProject(new TechProject()));
        assertSameFields(new TechMilestone(), TechViews.ofMilestone(new TechMilestone()));
    }

    @Test
    void achievementContract() throws Exception {
        assertSameFields(new TechAchievement(), TechViews.ofAchievement(new TechAchievement()));
    }

    @Test
    void serviceProductAndPolicyContract() throws Exception {
        assertSameFields(new TechServiceProduct(), TechViews.ofServiceProduct(new TechServiceProduct()));
        assertSameFields(new TechPolicy(), TechViews.ofPolicy(new TechPolicy()));
    }

    @Test
    void organizationAndPostContract() throws Exception {
        assertSameFields(new TechOrganization(), TechViews.ofOrganization(new TechOrganization()));
        assertSameFields(new TechPost(), TechViews.ofPost(new TechPost()));
    }
}
