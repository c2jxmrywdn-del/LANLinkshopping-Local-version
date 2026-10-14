package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.TrafficMapper;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TrafficService {
    private static final int MAX_DAYS=90;
    private static final Set<String> EVENTS=Set.of("VISIT","VIEW_PRODUCT","CLICK_PRODUCT","FAVORITE","ADD_CART","CHECKOUT","PAY");
    private final TrafficMapper mapper; private final MerchantService merchantService;
    public TrafficService(TrafficMapper mapper,MerchantService merchantService){this.mapper=mapper;this.merchantService=merchantService;}

    public Map<String,Object> overview(Long userId){
        Long m=mustMerId(userId); Map<String,Object> r=mapper.sumRecent(m,30), p=mapper.sumPrevPeriod(m,30,60), t=mapper.sumTotal(m);
        long on=mapper.countOnSale(m), active=mapper.countActiveProducts(m), orders=num(r.get("orders")); BigDecimal a=dec(r.get("amount")), pa=dec(p.get("amount"));
        Map<String,Object> o=new LinkedHashMap<>(); o.put("onSaleProducts",on);o.put("totalAmount",t.get("amount"));o.put("totalSold",t.get("sold"));o.put("recentAmount",r.get("amount"));o.put("recentOrders",orders);
        o.put("prevAmount",p.get("amount"));o.put("chainGrowthPct",pa.signum()>0?a.subtract(pa).divide(pa,4,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue():null);
        o.put("avgOrderValue",orders>0?a.divide(BigDecimal.valueOf(orders),2,RoundingMode.HALF_UP):BigDecimal.ZERO);o.put("activeProducts",active);
        o.put("activeRatePct",on>0?BigDecimal.valueOf(active*100L).divide(BigDecimal.valueOf(on),1,RoundingMode.HALF_UP):BigDecimal.ZERO);return o;
    }
    public List<Map<String,Object>> trend(Long userId,Integer days){
        int d=norm(days); List<Map<String,Object>> rows=mapper.trendByDay(mustMerId(userId),d); Map<String,Map<String,Object>> by=new HashMap<>(); for(Map<String,Object> r:rows)by.put(String.valueOf(r.get("date")),r);
        List<Map<String,Object>> out=new ArrayList<>(); LocalDate cur=LocalDate.now().minusDays(d-1L); for(int i=0;i<d;i++){String k=cur.format(DateTimeFormatter.ISO_LOCAL_DATE);Map<String,Object> r=by.get(k);
            if(r==null){r=new LinkedHashMap<>();r.put("date",k);r.put("amount",BigDecimal.ZERO);r.put("orders",0L);}out.add(r);cur=cur.plusDays(1);}return out;
    }
    public List<Map<String,Object>> channels(Long u,Integer d){return mapper.channelStats(mustMerId(u),norm(d));}
    public List<Map<String,Object>> productRanking(Long u,Integer d){return mapper.productRanking(mustMerId(u),norm(d));}
    public List<Map<String,Object>> sources(Long u,Integer d){return mapper.sourceStats(mustMerId(u),norm(d));}
    public Map<String,Object> conversion(Long u,Integer d){
        Long m=mustMerId(u);int n=norm(d);Map<String,Object> f=mapper.funnel(m,n);long v=num(f.get("visits")),views=num(f.get("views")),c=num(f.get("carts")),p=num(f.get("pays"));
        Map<String,Object> o=new LinkedHashMap<>();o.put("visits",v);o.put("views",views);o.put("carts",c);o.put("pays",p);o.put("viewRate",rate(views,v));o.put("cartRate",rate(c,views));o.put("payRate",rate(p,c));o.put("overallRate",rate(p,v));o.put("products",mapper.productFunnel(m,n));return o;
    }
    public List<Map<String,Object>> diagnosis(Long u,Integer d){
        Map<String,Object> f=mapper.funnel(mustMerId(u),norm(d));long v=num(f.get("visits")),views=num(f.get("views")),c=num(f.get("carts")),p=num(f.get("pays"));List<Map<String,Object>> out=new ArrayList<>();
        if(v==0){add(out,"NO_DATA","暂无流量事件","当前窗口没有采集到访问事件，请确认商城页面已接入流量采集。","info");return out;}
        if(rate(views,v)<30)add(out,"VIEW_LOSS","访问→商品浏览流失较高","优化首页曝光、搜索结果与落地页首屏。","warning");
        if(rate(c,views)<10)add(out,"CART_LOSS","商品浏览→加购偏低","检查价格、规格、库存与商品详情页决策信息。","warning");
        if(rate(p,c)<20)add(out,"PAY_LOSS","加购→支付偏低","检查结算流程、支付渠道及费用提示。","warning");
        if(out.isEmpty())add(out,"HEALTHY","流量转化健康","继续扩大高转化商品与优质来源的曝光。","success");return out;
    }
    public void track(Map<String,Object> raw){
        String event=String.valueOf(raw.getOrDefault("eventType",""));if(!EVENTS.contains(event))throw new BusinessException("不支持的流量事件");
        String sid=String.valueOf(raw.getOrDefault("sessionId",""));if(sid.length()<8||sid.length()>128)throw new BusinessException("无效会话标识");
        Object mid=raw.get("merchantId");if(mid==null)throw new BusinessException("缺少商户标识");
        Map<String,Object> e=new HashMap<>();e.put("merchantId",mid);e.put("productId",raw.get("productId"));e.put("userId",raw.get("userId"));e.put("sessionId",sid);
        e.put("eventType",event);e.put("sourceType",clip(raw.getOrDefault("sourceType","direct"),32));e.put("sourceDetail",clip(raw.get("sourceDetail"),255));e.put("pageUrl",clip(raw.get("pageUrl"),500));e.put("deviceType",clip(raw.getOrDefault("deviceType","unknown"),16));mapper.insertEvent(e);
    }
    private Long mustMerId(Long u){Merchant m=merchantService.getByUser(u);if(m==null)throw new BusinessException("仅入驻商户可查看流量数据");return m.getMerId();}
    private int norm(Integer d){return d==null||d<=0?30:Math.min(d,MAX_DAYS);}
    private static BigDecimal dec(Object o){return o==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(o));}
    private static long num(Object o){return o==null?0:Long.parseLong(String.valueOf(o));}
    private static double rate(long a,long b){return b==0?0:Math.round(a*10000.0/b)/100.0;}
    private static String clip(Object o,int n){if(o==null)return null;String s=String.valueOf(o);return s.length()>n?s.substring(0,n):s;}
    private static void add(List<Map<String,Object>> x,String code,String title,String suggestion,String level){Map<String,Object> m=new LinkedHashMap<>();m.put("code",code);m.put("title",title);m.put("suggestion",suggestion);m.put("level",level);x.add(m);}
}