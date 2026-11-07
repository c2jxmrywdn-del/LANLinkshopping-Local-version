package com.lanlink.shopping.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工业物流地理坐标注册表与 GIS 路线插值引擎。
 * 内置全国重点工业城市与物流枢纽坐标字典，提供城市匹配、坐标插值与车头航向角计算。
 */
public final class GeoCoordinateRegistry {

    private GeoCoordinateRegistry() {}

    public record GeoPoint(double lng, double lat, String name, String hubType) {
        public BigDecimal lngBd() {
            return BigDecimal.valueOf(lng).setScale(6, RoundingMode.HALF_UP);
        }
        public BigDecimal latBd() {
            return BigDecimal.valueOf(lat).setScale(6, RoundingMode.HALF_UP);
        }
    }

    private static final Map<String, GeoPoint> CITY_COORDS = new LinkedHashMap<>();

    static {
        // 长三角与华东工业枢纽
        add("昆山", 120.980737, 31.384644, "昆山工业高新区", "warehouse");
        add("苏州", 120.619585, 31.299379, "苏州工业园区", "warehouse");
        add("无锡", 120.301663, 31.574729, "无锡硕放分拨枢纽", "hub");
        add("常州", 119.946973, 31.772752, "常州武进工业区", "hub");
        add("上海", 121.473701, 31.230416, "上海西北综合物流园区", "hub");
        add("南京", 118.796877, 32.060255, "南京龙潭综合转运中心", "hub");
        add("杭州", 120.153576, 30.287459, "杭州钱塘分拨中心", "hub");
        add("宁波", 121.549792, 29.868388, "宁波北仑港口集运仓", "hub");
        add("合肥", 117.283042, 31.86119, "合肥蜀山干线分拨中心", "transfer");
        add("芜湖", 118.376451, 31.326319, "芜湖鸠江转运中心", "transfer");
        add("青岛", 120.382612, 36.067108, "青岛保税区物流园", "hub");
        add("济南", 117.000923, 36.675807, "济南盖世智慧物流港", "hub");

        // 华中枢纽
        add("武汉", 114.305393, 30.593099, "武汉东西湖综合物流枢纽", "hub");
        add("郑州", 113.665412, 34.757975, "郑州航空港国际物流园", "hub");
        add("长沙", 112.982279, 28.19409, "长沙黄花综合枢纽", "hub");

        // 华南与大湾区工业带
        add("东莞", 113.751765, 23.020673, "东莞松山湖先进制造仓", "warehouse");
        add("深圳", 114.057868, 22.543099, "深圳盐田集运枢纽", "hub");
        add("广州", 113.264434, 23.129162, "广州白云综合分拨中心", "hub");
        add("佛山", 113.122717, 23.028762, "佛山顺德装备集配站", "hub");
        add("中山", 113.382391, 22.521113, "中山火炬工业区集货站", "warehouse");

        // 华北与东北
        add("北京", 116.407526, 39.90403, "北京顺义国际空港枢纽", "hub");
        add("天津", 117.200983, 39.084158, "天津东丽综合转运港", "hub");
        add("沈阳", 123.429096, 41.796767, "沈阳于洪智能物流港", "hub");
        add("大连", 121.618622, 38.91459, "大连甘井子集运站", "hub");
        add("石家庄", 114.502461, 38.045474, "石家庄正定物流分拨区", "hub");

        // 西部核心
        add("成都", 104.065735, 30.659462, "成都青白江国际铁路港", "hub");
        add("重庆", 106.504962, 29.533155, "重庆沙坪坝团结村枢纽", "hub");
        add("西安", 108.948024, 34.263161, "西安国际港务区干线基地", "hub");

        // 备选默认中心（中国地理几何中心附近）
        add("默认", 116.407526, 39.90403, "华东干线集运中心", "hub");
    }

    private static void add(String key, double lng, double lat, String name, String hubType) {
        CITY_COORDS.put(key, new GeoPoint(lng, lat, name, hubType));
    }

    /**
     * 根据地址或地点描述文本提取最匹配的城市地理坐标
     */
    public static GeoPoint lookup(String text, GeoPoint fallback) {
        if (text == null || text.isBlank()) {
            return fallback != null ? fallback : CITY_COORDS.get("昆山");
        }
        for (Map.Entry<String, GeoPoint> entry : CITY_COORDS.entrySet()) {
            if (!"默认".equals(entry.getKey()) && text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return fallback != null ? fallback : CITY_COORDS.get("默认");
    }

    /**
     * 在起点与终点之间按比例线性插值
     * @param from 起点坐标
     * @param to 终点坐标
     * @param fraction 比例 0.0 ~ 1.0
     */
    public static GeoPoint interpolate(GeoPoint from, GeoPoint to, double fraction, String name, String hubType) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        double lng = from.lng() + (to.lng() - from.lng()) * f;
        double lat = from.lat() + (to.lat() - from.lat()) * f;
        return new GeoPoint(lng, lat, name, hubType);
    }

    /**
     * 计算两点间的大圆近似距离（公里）
     */
    public static double distanceKm(GeoPoint from, GeoPoint to) {
        if (from == null || to == null) return 0.0;
        double r = 6371.0;
        double dLat = Math.toRadians(to.lat() - from.lat());
        double dLng = Math.toRadians(to.lng() - from.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(from.lat())) * Math.cos(Math.toRadians(to.lat())) *
                   Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.round(r * c * 10.0) / 10.0;
    }

    /**
     * 计算由 p1 到 p2 的车头朝向方位角 (0 - 360 度)
     */
    public static double heading(GeoPoint from, GeoPoint to) {
        if (from == null || to == null) return 0.0;
        double dLng = Math.toRadians(to.lng() - from.lng());
        double lat1 = Math.toRadians(from.lat());
        double lat2 = Math.toRadians(to.lat());
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (degrees + 360.0) % 360.0;
    }
}
