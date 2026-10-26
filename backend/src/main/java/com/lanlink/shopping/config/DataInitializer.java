package com.lanlink.shopping.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.mapper.*;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动初始化: 若示例数据不存在则写入(密码用 BCrypt 正确加密)
 * 演示账号(密码均为 123456):
 *   13800000000 平台运营 admin
 *   13900000001 采购方 buyer
 *   13700000002 商户 merchant(已通过审核)
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${lanlink.demo-data.enabled:true}")
    private boolean demoDataEnabled;

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final ThirdAuthMapper thirdAuthMapper;
    private final MessageMapper messageMapper;
    private final CampaignMapper campaignMapper;
    private final com.lanlink.shopping.module.pricemonitor.mapper.MarketBenchmarkMapper benchmarkMapper;
    private final com.lanlink.shopping.module.pricemonitor.mapper.PriceHistoryMapper priceHistoryMapper;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserMapper userMapper, RoleMapper roleMapper, EnterpriseMapper enterpriseMapper,
                           MerchantMapper merchantMapper, ProductMapper productMapper,
                           CategoryMapper categoryMapper, ThirdAuthMapper thirdAuthMapper,
                           MessageMapper messageMapper,
                           CampaignMapper campaignMapper,
                           com.lanlink.shopping.module.pricemonitor.mapper.MarketBenchmarkMapper benchmarkMapper,
                           com.lanlink.shopping.module.pricemonitor.mapper.PriceHistoryMapper priceHistoryMapper,
                           PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper; this.roleMapper = roleMapper;
        this.enterpriseMapper = enterpriseMapper; this.merchantMapper = merchantMapper;
        this.productMapper = productMapper; this.categoryMapper = categoryMapper;
        this.thirdAuthMapper = thirdAuthMapper;
        this.messageMapper = messageMapper;
        this.campaignMapper = campaignMapper;
        this.benchmarkMapper = benchmarkMapper;
        this.priceHistoryMapper = priceHistoryMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!demoDataEnabled) {
            log.info("演示数据初始化已关闭，跳过示例用户、营销及授权数据");
            return;
        }

        log.info("==== 检查并初始化演示数据 ====");
        Role buyer = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getRoleCode, "buyer"));
        Role merchantRole = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getRoleCode, "merchant"));
        Role admin = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getRoleCode, "admin"));
        if (buyer == null || merchantRole == null || admin == null) {
            throw new IllegalStateException("t_role 缺少 buyer/merchant/admin 基础数据, 请检查 sql/schema.sql 是否执行成功");
        }

        // 1. 企业（幂等）
        Enterprise ent = enterpriseMapper.selectOne(Wrappers.<Enterprise>lambdaQuery().eq(Enterprise::getName, "华晨建材集团有限公司"));
        if (ent == null) {
            ent = new Enterprise();
            ent.setName("华晨建材集团有限公司");
            ent.setCreditCode("91500000MA5U0000XA");
            ent.setMemberLevel(2);
            ent.setCreateTime(LocalDateTime.now());
            enterpriseMapper.insert(ent);
        }

        // 2. 演示用户（幂等确保 3 个演示账号存在且密码为 123456）
        User uAdmin = ensureUser("13800000000", "平台运营", admin.getRoleId(), null);
        User uBuyer = ensureUser("13900000001", "重庆建工采购", buyer.getRoleId(), null);
        User uMer   = ensureUser("13700000002", "华晨建材", merchantRole.getRoleId(), ent.getEntId());

        // 3. 演示商户（幂等）
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, uMer.getUserId()));
        if (m == null) {
            m = new Merchant();
            m.setEntId(ent.getEntId()); m.setUserId(uMer.getUserId());
            m.setRegType("公司"); m.setRegCapital(new BigDecimal("5000000"));
            m.setTaxStatus(1); m.setJoinType("邀约"); m.setReviewStatus(1);
            m.setLegalPerson("张建国");
            m.setLegalIdCard("110101198503151234");
            m.setBankAccount("6222021001123456789");
            m.setBankName("招商银行股份有限公司北京建国门支行");
            m.setContactPhone("13700000002");
            m.setContactEmail("zhangjg@huachen-group.cn");
            m.setBusinessAddress("北京市海淀区中关村南大街1号科技大厦8层801");
            m.setCreateTime(LocalDateTime.now()); m.setUpdateTime(LocalDateTime.now());
            merchantMapper.insert(m);
        } else if (m.getLegalPerson() == null) {
            m.setLegalPerson("张建国");
            m.setLegalIdCard("110101198503151234");
            m.setBankAccount("6222021001123456789");
            m.setBankName("招商银行股份有限公司北京建国门支行");
            m.setContactPhone("13700000002");
            m.setContactEmail("zhangjg@huachen-group.cn");
            m.setBusinessAddress("北京市海淀区中关村南大街1号科技大厦8层801");
            m.setUpdateTime(LocalDateTime.now());
            merchantMapper.updateById(m);
        }

        // 4. 演示商品（幂等：商品表为空时补齐）
        if (productMapper.selectCount(null) == 0) {
            List<Product> products = new ArrayList<>();
            products.add(mkProduct(m.getMerId(), "建筑钢材 HRB400 螺纹钢 Φ20", "华晨", "Φ20*9m", "3850.00", 1L, 5000, "吨", 320));
            products.add(mkProduct(m.getMerId(), "硅酸盐水泥 P.O42.5 袋装", "华新", "50kg/袋", "420.00", 2L, 8000, "吨", 210));
            products.add(mkProduct(m.getMerId(), "PPR 给水管材 DN25", "日丰", "25mm*4m", "28.50", 3L, 12000, "根", 150));
            products.add(mkProduct(m.getMerId(), "全棉坯布 40*40 133*72", "华纺", "幅宽160cm", "8.60", 4L, 30000, "米", 90));
            products.add(mkProduct(m.getMerId(), "涤纶短纤 1.56D*38mm", "恒力", "1.56D", "6800.00", 5L, 2000, "吨", 60));
            products.add(mkProduct(m.getMerId(), "工业级碳酸钠 99.2%", "三友", "25kg/袋", "1850.00", 7L, 4000, "吨", 45));
            products.add(mkProduct(m.getMerId(), "抗磨液压油 L-HM46", "长城", "18L/桶", "360.00", 8L, 1500, "桶", 75));
            products.add(mkProduct(m.getMerId(), "贴片电阻 0805 10KΩ", "风华", "0805", "0.02", 9L, 500000, "只", 980));
            products.add(mkProduct(m.getMerId(), "板对板连接器 2.0mm 双排", "立讯", "2x10P", "1.85", 10L, 60000, "只", 260));
            products.add(mkProduct(m.getMerId(), "IPS 显示面板 7寸 1024*600", "京东方", "7inch", "96.00", 11L, 3000, "片", 130));
            for (Product p : products) {
                productMapper.insert(p);
                // 挂上无水印商品图（public/products/{id}.webp，已由位图转为 WebP 以减小体积）
                p.setCoverUrl("/products/" + p.getProdId() + ".webp");
                productMapper.updateById(p);
            }
        }

        // 5. 营销中台演示数据
        seedMarketingDemo();
        // 6. 第三方授权演示数据
        seedThirdAuthDemo();
        // 7. 站内消息演示数据
        seedMessageDemo();
        // 8. 价格监控演示数据（行业基准 30 天游走 + 商品价格留痕）
        seedPriceMonitorDemo();

        log.info("==== 演示数据就绪: 演示账号(13800000000/13900000001/13700000002)密码均为 123456 ====");
    }

    /**
     * 价格监控演示数据：近 30 天行业基准价（随机游走，幂等按 ind+day）+
     * 演示商品的价格留痕（从现价反推逐日演变，幂等按 prod_id）。
     */
    private void seedPriceMonitorDemo() {
        LocalDateTime now = LocalDateTime.now();
        // 1) 行业基准：行业 1-4，各 30 天；基准价按行业量级起步，日波动 ±2%
        Map<Long, Double> base = Map.of(1L, 2200.0, 2L, 3200.0, 3L, 1600.0, 4L, 220.0);
        java.time.LocalDate today = java.time.LocalDate.now();
        for (Map.Entry<Long, Double> e : base.entrySet()) {
            double v = e.getValue();
            for (int i = 29; i >= 0; i--) {
                java.time.LocalDate day = today.minusDays(i);
                Long dup = benchmarkMapper.selectCount(Wrappers.<com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark>lambdaQuery()
                        .eq(com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark::getIndId, e.getKey())
                        .eq(com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark::getDay, day));
                if (dup > 0) continue;
                com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark mb =
                        new com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark();
                mb.setIndId(e.getKey());
                mb.setDay(day);
                mb.setBenchmark(BigDecimal.valueOf(Math.round(v * 100) / 100.0));
                benchmarkMapper.insert(mb);
                v *= 1 + (Math.random() - 0.48) * 0.04; // 轻微上偏的随机游走
            }
        }
        // 2) 商品价格留痕：每个演示商品 4 条（近 30 天），从现价反推
        for (var p : productMapper.selectList(Wrappers.<Product>lambdaQuery().last("LIMIT 20"))) {
            Long cnt = priceHistoryMapper.selectCount(Wrappers.<com.lanlink.shopping.module.pricemonitor.entity.PriceHistory>lambdaQuery()
                    .eq(com.lanlink.shopping.module.pricemonitor.entity.PriceHistory::getProdId, p.getProdId()));
            if (cnt > 0) continue;
            double cur = p.getPrice().doubleValue();
            double prev = cur;
            for (int i = 4; i >= 1; i--) {
                double old = cur * (1 + (Math.random() - 0.5) * 0.08);
                com.lanlink.shopping.module.pricemonitor.entity.PriceHistory h =
                        new com.lanlink.shopping.module.pricemonitor.entity.PriceHistory();
                h.setProdId(p.getProdId());
                h.setMerId(p.getMerId());
                h.setIndId(p.getIndId());
                h.setOldPrice(BigDecimal.valueOf(Math.round(old * 100) / 100.0));
                h.setNewPrice(BigDecimal.valueOf(Math.round(prev * 100) / 100.0));
                h.setChangePct(BigDecimal.valueOf(Math.round((prev / old - 1) * 10000) / 100.0));
                h.setSource("SEED");
                h.setCreateTime(now.minusDays(i * 6L));
                priceHistoryMapper.insert(h);
                prev = old;
            }
        }
    }

    /** 营销中台演示数据：统一活动体系（领券/满减/折扣/秒杀/激励/商户激励，按标题幂等） */
    private void seedMarketingDemo() {
        LocalDateTime now = LocalDateTime.now();
        // 领券活动（用户在活动中心领取，结算时核销）
        seedCampaign(mkCampaign("新客专享 · 满 200 减 30 券", "coupon", "red",
                        now.minusDays(1), now.plusDays(30))
                .setThreshold(new BigDecimal("200")).setBenefitAmount(new BigDecimal("30"))
                .setPerUserLimit(1).setTotalQuota(1000));
        seedCampaign(mkCampaign("建材行业 · 满 1000 减 120 券", "coupon", "purple",
                        now.minusDays(1), now.plusDays(20))
                .setThreshold(new BigDecimal("1000")).setBenefitAmount(new BigDecimal("120"))
                .setScope("ind").setIndId(1L).setPerUserLimit(2).setTotalQuota(500));
        seedCampaign(mkCampaign("全场通用 95 折券（最高减 ¥80）", "coupon", "green",
                        now.minusDays(1), now.plusDays(15))
                .setThreshold(new BigDecimal("300")).setDiscountRate(new BigDecimal("0.95"))
                .setBenefitAmount(new BigDecimal("80")).setPerUserLimit(1).setTotalQuota(2000));
        // 促销活动（结算自动应用，与会员折扣互斥取优）
        seedCampaign(mkCampaign("全场满 500 减 50", "full_reduce", "blue",
                        now.minusDays(1), now.plusDays(30))
                .setThreshold(new BigDecimal("500")).setBenefitAmount(new BigDecimal("50")));
        seedCampaign(mkCampaign("建材行业 95 折", "discount", "blue",
                        now.minusDays(1), now.plusDays(30))
                .setThreshold(new BigDecimal("100")).setDiscountRate(new BigDecimal("0.95"))
                .setScope("ind").setIndId(1L));
        seedCampaign(mkCampaign("午间限时秒杀 · 全场 9 折", "flash_sale", "purple",
                        now.minusHours(2), now.plusHours(6))
                .setDiscountRate(new BigDecimal("0.90")).setBenefitAmount(new BigDecimal("200")));
        // 用户激励活动
        seedCampaign(mkCampaign("新客注册有礼", "register", "gold",
                        now.minusDays(1), now.plusDays(30)).setRewardPoints(100));
        seedCampaign(mkCampaign("行业采购节 · 消费有礼", "purchase", "gold",
                        now.minusDays(1), now.plusDays(30)).setRewardPoints(100));
        // 商户激励 + 商户可报名的平台大促
        seedCampaign(mkCampaign("商户销售冲刺 · 满 ¥50,000 奖 2000 积分", "merchant_incentive", "green",
                        now.minusDays(1), now.plusDays(30))
                .setMerTargetAmount(new BigDecimal("50000")).setMerRewardPoints(2000));
        seedCampaign(mkCampaign("双十一工业品大促 · 商户报名中", "full_reduce", "red",
                        now.minusDays(1), now.plusDays(15))
                .setThreshold(new BigDecimal("1000")).setBenefitAmount(new BigDecimal("100")).setJoinable(1));
    }

    private Campaign mkCampaign(String title, String type, String accent,
                                LocalDateTime start, LocalDateTime end) {
        Campaign c = new Campaign();
        c.setTitle(title);
        c.setType(type);
        c.setAccent(accent);
        c.setDescription("活动说明由平台运营在后台维护。");
        c.setStartTime(start);
        c.setEndTime(end);
        c.setStatus(1); // 上架
        c.setScope("all");
        c.setThreshold(BigDecimal.ZERO);
        c.setPerUserLimit(1);
        c.setTotalQuota(0);
        c.setClaimedCount(0);
        c.setJoinable(0);
        c.setCreateTime(LocalDateTime.now());
        c.setUpdateTime(LocalDateTime.now());
        return c;
    }

    private void seedCampaign(Campaign c) {
        if (campaignMapper.selectCount(Wrappers.<Campaign>lambdaQuery().eq(Campaign::getTitle, c.getTitle())) > 0) return;
        campaignMapper.insert(c);
    }

    private User ensureUser(String phone, String nick, Long roleId, Long entId) {
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));
        if (u == null) {
            u = new User();
            u.setPhone(phone);
            u.setPassword(passwordEncoder.encode("123456"));
            u.setNickname(nick);
            u.setRoleId(roleId);
            u.setEntId(entId);
            u.setCreateTime(LocalDateTime.now());
            u.setUpdateTime(LocalDateTime.now());
            userMapper.insert(u);
        } else if (u.getPassword() == null || !passwordEncoder.matches("123456", u.getPassword())) {
            u.setPassword(passwordEncoder.encode("123456"));
            u.setUpdateTime(LocalDateTime.now());
            userMapper.updateById(u);
        }
        return u;
    }

    /** 第三方授权演示数据：按手机号找演示买家（幂等：用户+应用 唯一） */
    private void seedThirdAuthDemo() {
        User buyer = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, "13900000001"));
        if (buyer != null) {
            seedThirdAuth(buyer.getUserId(), "微信开放平台", "userinfo,openid");
            seedThirdAuth(buyer.getUserId(), "支付宝开放平台", "userId,profile");
        }
    }

    private void seedThirdAuth(Long userId, String appName, String scopes) {
        if (thirdAuthMapper.selectCount(Wrappers.<ThirdAuth>lambdaQuery()
                .eq(ThirdAuth::getUserId, userId).eq(ThirdAuth::getAppName, appName)) > 0) {
            return;
        }
        ThirdAuth t = new ThirdAuth();
        t.setUserId(userId);
        t.setAppName(appName);
        t.setScopes(scopes);
        t.setAuthTime(LocalDateTime.now());
        t.setCreateTime(LocalDateTime.now());
        thirdAuthMapper.insert(t);
    }

    /** 站内消息演示数据：按手机号找演示买家（幂等：用户+类型+标题 唯一） */
    private void seedMessageDemo() {
        User buyer = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, "13900000001"));
        if (buyer == null) return;
        seedMessage(buyer.getUserId(), "promotion", "新客户专享 9 折优惠", "首次下单享 9 折，全场通用，有效期至月底。");
        seedMessage(buyer.getUserId(), "promotion", "行业采购节来袭", "建筑/纺织/石化/电子四大会场满减专场已开启。");
        seedMessage(buyer.getUserId(), "system", "欢迎使用 LANLinkshopping", "平台已为你开通采购账号，可前往个人中心完善资料。");
    }

    private void seedMessage(Long userId, String type, String title, String content) {
        if (messageMapper.selectCount(Wrappers.<Message>lambdaQuery()
                .eq(Message::getUserId, userId).eq(Message::getType, type).eq(Message::getTitle, title)) > 0) {
            return;
        }
        Message m = new Message();
        m.setUserId(userId);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        m.setReadFlag(0);
        m.setCreateTime(LocalDateTime.now());
        messageMapper.insert(m);
    }

    private Product mkProduct(Long merId, String title, String brand, String spec, String price,
                              Long catId, int stock, String unit, int sales) {
        Product p = new Product();
        p.setMerId(merId); p.setTitle(title); p.setBrand(brand); p.setSpec(spec);
        p.setPrice(new BigDecimal(price)); p.setCatId(catId);
        // 行业: 1-3建筑,4-5纺织,7-8石化,9-11电子 (按分类首段映射)
        p.setIndId(mapInd(catId));
        p.setStock(stock); p.setSales(sales); p.setStatus(1);
        p.setCoverUrl(""); p.setDetail("规格: " + spec + "；计量单位: " + unit + "。源头厂商直供，支持对公转账与账期结算。");
        p.setTierPriceJson("[{\"qty\":100,\"price\":" + price + "}]");
        p.setCreateTime(LocalDateTime.now()); p.setUpdateTime(LocalDateTime.now());
        return p;
    }

    private Long mapInd(Long catId) {
        if (catId <= 3) return 1L;      // 建筑
        if (catId <= 6) return 2L;      // 纺织
        if (catId <= 8) return 3L;      // 石化
        return 4L;                       // 电子
    }
}
