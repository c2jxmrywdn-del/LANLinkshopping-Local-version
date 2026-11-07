package com.lanlink.shopping.module.techservice.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.techservice.entity.TechPilotResource;
import com.lanlink.shopping.module.techservice.mapper.TechPilotResourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * P1 中试 / 概念验证资源库冷启动（演示与验收环境）。
 *
 * <p>按资源名称幂等补齐，不覆盖运营已编辑的记录。真实环境应由合作机构提交资质与设备清单，
 * 运营核验后上架，不得自动灌入虚构资源。
 */
@Component
@Order(21)
public class TechP1SeedInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TechP1SeedInitializer.class);

    @Value("${lanlink.demo-data.enabled:false}")
    private boolean enabled;

    private final TechPilotResourceMapper resourceMapper;

    public TechP1SeedInitializer(TechPilotResourceMapper resourceMapper) {
        this.resourceMapper = resourceMapper;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        int added = 0;
        for (Seed seed : SEEDS) {
            if (exists(seed.name())) continue;
            TechPilotResource resource = new TechPilotResource();
            resource.setName(seed.name());
            resource.setOrgName(seed.org());
            resource.setRegion(seed.region());
            resource.setResourceType(seed.type());
            resource.setCapabilities(seed.capabilities());
            resource.setEquipment(seed.equipment());
            resource.setPriceMode(seed.priceMode());
            resource.setPrice(new BigDecimal(seed.price()));
            resource.setContact(seed.contact());
            resource.setStatus(1);
            resource.setSort(seed.sort());
            resourceMapper.insert(resource);
            added++;
        }
        if (added > 0) {
            log.info("[tech-p1-seed] 中试资源库补齐完成：新增 {} 条", added);
        }
    }

    private boolean exists(String name) {
        Long count = resourceMapper.selectCount(Wrappers.<TechPilotResource>lambdaQuery()
                .eq(TechPilotResource::getName, name));
        return count != null && count > 0;
    }

    private record Seed(String name, String org, String region, String type, String capabilities,
                        String equipment, String priceMode, String price, String contact, int sort) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("石墨烯复合散热膜中试线", "华东某新材料中试基地（示例）", "上海", "PILOT",
                    "导热膜配方放大、涂布与固化工艺验证、100L 级中试",
                    "涂布线、热压固化炉、导热系数测试仪", "QUOTE", "80000",
                    "中试基地服务台 021-****", 10),
            new Seed("退役动力电池梯次利用验证平台", "某新能源研究院（示例）", "上海", "VERIFICATION",
                    "电化学阻抗测试、快速分选算法验证、梯次电池一致性评估",
                    "电池充放电柜、阻抗分析仪、分选线", "FIXED", "120000",
                    "验证平台预约邮箱", 20),
            new Seed("半导体晶圆缺陷光学检测验证", "某半导体设备公司（示例）", "上海", "VERIFICATION",
                    "多波段照明方案验证、缺陷检出率对比、光学系统标定",
                    "晶圆检测平台、光谱仪、标准缺陷片", "QUOTE", "150000",
                    "检测实验室联系人", 30),
            new Seed("工业废水重金属吸附材料中试", "某环保材料团队（示例）", "杭州", "PILOT",
                    "吸附材料放大制备、柱试验、再生性能验证",
                    "反应釜、吸附柱、ICP-MS", "QUOTE", "60000",
                    "环保中试线联系人", 40),
            new Seed("柔性显示低温银浆验证线", "某电子材料实验室（示例）", "杭州", "PILOT",
                    "银浆配方验证、丝网印刷、低温固化导电性能测试",
                    "丝网印刷机、低温固化炉、四探针测试仪", "QUOTE", "70000",
                    "电子材料实验室联系人", 50),
            new Seed("协作机器人精密装配验证单元", "某高校机器人研究所（示例）", "上海", "LAB",
                    "视觉引导标定、力控装配、节拍与精度验证",
                    "协作机器人、工业相机、六维力传感器", "FIXED", "30000",
                    "机器人实验室预约", 60),
            new Seed("储能系统能量管理半实物仿真", "某高校能源工程学院（示例）", "杭州", "LAB",
                    "储能控制策略验证、半实物仿真、并网工况复现",
                    "RTDS、储能变流器、电池模拟器", "FIXED", "45000",
                    "能源实验室联系人", 70),
            new Seed("装备可靠性加速试验平台", "某检测认证机构（示例）", "苏州", "PILOT",
                    "振动、温循、盐雾等可靠性试验与环境适应性验证",
                    "振动台、温循箱、盐雾箱", "QUOTE", "50000",
                    "检测机构服务台", 80)
    );
}
