package com.lanlink.shopping.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.mapper.*;
import com.lanlink.shopping.service.LiveService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class LiveServiceImpl implements LiveService {

    private final LiveRoomMapper liveRoomMapper;
    private final LiveProductMapper liveProductMapper;
    private final LiveCommentMapper liveCommentMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final EnterpriseMapper enterpriseMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lanlink.shopping.service.MessageService messageService;

    public LiveServiceImpl(LiveRoomMapper liveRoomMapper,
                           LiveProductMapper liveProductMapper,
                           LiveCommentMapper liveCommentMapper,
                           ProductMapper productMapper,
                           MerchantMapper merchantMapper,
                           EnterpriseMapper enterpriseMapper) {
        this.liveRoomMapper = liveRoomMapper;
        this.liveProductMapper = liveProductMapper;
        this.liveCommentMapper = liveCommentMapper;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.enterpriseMapper = enterpriseMapper;
    }

    @Override
    public List<LiveRoom> listRooms(Integer status) {
        var query = Wrappers.<LiveRoom>lambdaQuery();
        if (status != null) {
            query.eq(LiveRoom::getStatus, status);
        }
        query.orderByDesc(LiveRoom::getStatus)
             .orderByDesc(LiveRoom::getViewerCount)
             .orderByDesc(LiveRoom::getRoomId);

        List<LiveRoom> list = liveRoomMapper.selectList(query);
        for (LiveRoom room : list) {
            enrichRoom(room);
        }
        return list;
    }

    @Override
    public LiveRoom getRoomDetail(Long roomId) {
        if (roomId == null) return null;
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room != null) {
            enrichRoom(room);
        }
        return room;
    }

    private void enrichRoom(LiveRoom room) {
        if (room == null) return;
        // 补全商户信息：商户表无店铺名/头像列，商户名取所属企业名称
        if (room.getMerId() != null) {
            Merchant m = merchantMapper.selectById(room.getMerId());
            if (m != null) {
                String name = null;
                if (m.getEntId() != null) {
                    Enterprise ent = enterpriseMapper.selectById(m.getEntId());
                    if (ent != null) name = ent.getName();
                }
                room.setMerchantName(name);
                room.setMerchantAvatar(null);
            }
        }
        // 补全讲解中商品
        if (room.getPinnedProdId() != null) {
            Product p = productMapper.selectById(room.getPinnedProdId());
            room.setPinnedProduct(p);
        }
        // 统计商品数
        Long count = liveProductMapper.selectCount(
                Wrappers.<LiveProduct>lambdaQuery().eq(LiveProduct::getRoomId, room.getRoomId())
        );
        room.setProductCount(count != null ? count.intValue() : 0);
    }

    @Override
    public List<LiveProduct> getRoomProducts(Long roomId) {
        if (roomId == null) return Collections.emptyList();
        List<LiveProduct> list = liveProductMapper.selectByRoomId(roomId);
        for (LiveProduct lp : list) {
            if (lp.getProdId() != null) {
                Product p = productMapper.selectById(lp.getProdId());
                lp.setProduct(p);
            }
        }
        return list;
    }

    @Override
    public List<LiveComment> getRecentComments(Long roomId, int limit) {
        if (roomId == null) return Collections.emptyList();
        int safeLimit = Math.max(5, Math.min(100, limit));
        List<LiveComment> list = liveCommentMapper.selectRecentComments(roomId, safeLimit);
        // 按时间升序排序，便于前端从上往下自然展示
        Collections.reverse(list);
        return list;
    }

    @Override
    @Transactional
    public LiveComment addComment(Long roomId, Long userId, String nickname, String avatar, String content, String msgType) {
        if (roomId == null || content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("弹幕内容不能为空");
        }
        // 房间必须存在（防向不存在房间灌垃圾数据）
        if (liveRoomMapper.selectById(roomId) == null) {
            throw new IllegalArgumentException("直播间不存在");
        }
        // msgType 白名单：system/gift 为内部语义，外部输入只允许 chat/buy（防伪造系统公告/成交播报）
        String safeMsgType = "buy".equals(msgType) ? "buy" : "chat";
        String safeContent = content.trim();
        if (safeContent.length() > 500) {
            throw new IllegalArgumentException("弹幕内容过长");
        }
        LiveComment comment = new LiveComment();
        comment.setRoomId(roomId);
        comment.setUserId(userId);
        comment.setNickname(nickname != null && !nickname.trim().isEmpty() ? nickname.trim() : "采购代表");
        comment.setAvatar(avatar != null ? avatar : "");
        comment.setContent(safeContent);
        comment.setMsgType(safeMsgType);
        comment.setCreateTime(LocalDateTime.now());
        liveCommentMapper.insert(comment);
        return comment;
    }

    @Override
    public int addLikes(Long roomId, int count) {
        if (roomId == null) return 0;
        int safeCount = Math.max(1, Math.min(100, count));
        liveRoomMapper.incrementLikes(roomId, safeCount);
        return safeCount;
    }

    @Override
    public boolean pinProduct(Long roomId, Long prodId) {
        if (roomId == null || prodId == null) return false;
        // 完整校验：房间存在且直播中 + 商品必须已挂载本房间（同时锁定归属，杜绝置顶别家/未审商品）
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room == null) throw new com.lanlink.shopping.common.BusinessException("直播间不存在");
        if (room.getStatus() == null || room.getStatus() != 1) {
            throw new com.lanlink.shopping.common.BusinessException("仅直播中的直播间可切换讲解商品");
        }
        if (liveProductMapper.selectByRoomAndProduct(roomId, prodId) == null) {
            throw new com.lanlink.shopping.common.BusinessException("该商品未在本直播间挂载");
        }
        return liveRoomMapper.updatePinnedProduct(roomId, prodId) > 0;
    }

    @Override
    @Transactional
    public LiveRoom createRoom(Long merId, Long userId, LiveRoom room, List<LiveProduct> products) {
        // 一商户同一时刻仅一个活跃直播间（防垃圾灌水与控制台/归因口径漂移）
        Long active = liveRoomMapper.selectCount(Wrappers.<LiveRoom>lambdaQuery()
                .eq(LiveRoom::getMerId, merId)
                .in(LiveRoom::getStatus, 0, 1));
        if (active != null && active > 0) {
            throw new com.lanlink.shopping.common.BusinessException("您已有进行中/预告中的直播间，请先结束或等待审核完成");
        }
        room.setMerId(merId);
        room.setUserId(userId);
        // 平台准入：新直播间一律待审核，批准后商户方可开播（status 停在预告 0）
        room.setStatus(0);
        room.setReviewStatus(0);
        room.setRejectReason(null);
        // 真实计数起步：开播即主播本人在线；峰值同步
        room.setViewerCount(1);
        room.setPeakViewerCount(1);
        if (room.getLikeCount() == null) {
            room.setLikeCount(0L);
        }
        // start_time 留空：真实开播时间由开播动作写入（预告期不计入直播时长）
        // 长度校验（列宽 128/512/512/255，防 DB 500）
        if (room.getTitle() == null || room.getTitle().trim().length() < 2 || room.getTitle().trim().length() > 128) {
            throw new com.lanlink.shopping.common.BusinessException("直播主题需 2-128 个字符");
        }
        room.setTitle(room.getTitle().trim());
        if (room.getCoverUrl() != null && room.getCoverUrl().length() > 512) throw new com.lanlink.shopping.common.BusinessException("封面地址过长");
        if (room.getStreamUrl() != null && room.getStreamUrl().length() > 512) throw new com.lanlink.shopping.common.BusinessException("视频流地址过长");
        if (room.getNotice() != null && room.getNotice().length() > 255) throw new com.lanlink.shopping.common.BusinessException("公告过长");
        // 商品归属校验前置（fail-fast：任何非法挂载都在写入前拒绝）
        if (products != null) {
            for (LiveProduct lp : products) {
                Product p = lp.getProdId() == null ? null : productMapper.selectById(lp.getProdId());
                if (p == null || !merId.equals(p.getMerId())) {
                    throw new com.lanlink.shopping.common.BusinessException("直播商品不属于当前商户: " + lp.getProdId());
                }
                if (lp.getLivePrice() != null && lp.getLivePrice().signum() < 0) {
                    throw new com.lanlink.shopping.common.BusinessException("直播价不能为负数");
                }
            }
        }
        liveRoomMapper.insert(room);

        if (products != null && !products.isEmpty()) {
            int order = 1;
            for (LiveProduct lp : products) {
                lp.setRoomId(room.getRoomId());
                lp.setSortOrder(order++);
                lp.setClickCount(0);
                lp.setCreateTime(LocalDateTime.now());
                liveProductMapper.insert(lp);
            }
            if (room.getPinnedProdId() == null) {
                liveRoomMapper.updatePinnedProduct(room.getRoomId(), products.get(0).getProdId());
            }
        }

        return getRoomDetail(room.getRoomId());
    }

    @Override
    public boolean updateRoomStatus(Long roomId, Integer status) {
        if (roomId == null || status == null || status < 0 || status > 2) return false;
        // 平台准入门槛：开播（→1）必须已通过审核。条件更新闭筑 TOCTOU：
        // 与平台驳回/强制下架并发时，UPDATE 侧再校验 review_status=1，affected=0 即拒绝。
        if (status == 1) {
            LiveRoom room = liveRoomMapper.selectById(roomId);
            if (room == null) return false;
            if (room.getReviewStatus() == null || room.getReviewStatus() != 1) {
                throw new com.lanlink.shopping.common.BusinessException("直播间待平台审核通过后方可开播");
            }
            return liveRoomMapper.startIfApproved(roomId) > 0;
        }
        return liveRoomMapper.updateRoomStatus(roomId, status) > 0;
    }

    // ==================== 平台治理：审核批准 / 驳回 ====================

    /** 平台审核直播间：approved=true 通过（商户可开播）；false 驳回（回显原因，可重新提交创建） */
    @Override
    @Transactional
    public boolean reviewRoom(Long roomId, boolean approved, String reason, Long adminUserId) {
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room == null) throw new com.lanlink.shopping.common.BusinessException("直播间不存在");
        LiveRoom upd = new LiveRoom();
        upd.setRoomId(roomId);
        if (approved) {
            upd.setReviewStatus(1);
            upd.setRejectReason(null);
        } else {
            upd.setReviewStatus(2);
            upd.setRejectReason(reason == null || reason.isBlank() ? "直播内容不符合平台规范" : reason.trim());
            // 驳回同时把预告/直播中的房间下线（上下架处置）
            if (room.getStatus() != null && room.getStatus() != 2) {
                liveRoomMapper.updateRoomStatus(roomId, 2);
            }
        }
        upd.setUpdateTime(LocalDateTime.now());
        liveRoomMapper.updateById(upd);
        // 站内通知商户审核结果
        if (room.getUserId() != null) {
            messageService.send(room.getUserId(), "live", approved ? "直播间审核通过" : "直播间审核未通过",
                    approved ? "直播间「" + room.getTitle() + "」已通过平台审核，可在商户中心开播。"
                            : "直播间「" + room.getTitle() + "」未通过审核：" + upd.getRejectReason() + "。可修改后重新提交。",
                    "live:" + roomId);
        }
        return true;
    }

    @Override
    public LiveRoom getMerchantActiveRoom(Long merId) {
        if (merId == null) return null;
        LiveRoom room = liveRoomMapper.selectOne(
                Wrappers.<LiveRoom>lambdaQuery()
                        .eq(LiveRoom::getMerId, merId)
                        .in(LiveRoom::getStatus, 1, 0)
                        .orderByDesc(LiveRoom::getStatus)
                        .orderByDesc(LiveRoom::getRoomId)
                        .last("LIMIT 1")
        );
        if (room != null) {
            enrichRoom(room);
        }
        return room;
    }

    // ==================== 商业闭环：观看归因 / 成交复盘 / 治理 ====================

    @Override
    public LiveRoom findActiveRoomByProduct(Long prodId) {
        if (prodId == null) return null;
        return liveRoomMapper.selectActiveRoomByProduct(prodId);
    }

    @Override
    public void joinRoom(Long roomId) {
        if (roomId != null) liveRoomMapper.adjustViewers(roomId, 1);
    }

    @Override
    public void leaveRoom(Long roomId) {
        if (roomId != null) liveRoomMapper.adjustViewers(roomId, -1);
    }

    @Override
    public LiveProduct trackProductClick(Long roomId, Long prodId) {
        if (roomId == null || prodId == null) return null;
        LiveProduct lp = liveProductMapper.selectByRoomAndProduct(roomId, prodId);
        if (lp == null) return null;
        liveProductMapper.incrementClick(lp.getId());
        lp.setClickCount((lp.getClickCount() == null ? 0 : lp.getClickCount()) + 1);
        Product p = productMapper.selectById(prodId);
        lp.setProduct(p);
        return lp;
    }

    @Override
    public java.util.Map<String, Object> roomStats(Long roomId) {
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room == null) throw new com.lanlink.shopping.common.BusinessException("直播间不存在");
        enrichRoom(room);
        // 商品清单 + 按商品归因成交合并
        List<LiveProduct> products = getRoomProducts(roomId);
        java.util.Map<Long, java.util.Map<String, Object>> salesByProd = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : liveRoomMapper.salesByProduct(roomId)) {
            salesByProd.put(((Number) row.get("prodId")).longValue(), row);
        }
        java.util.List<java.util.Map<String, Object>> productRows = new java.util.ArrayList<>();
        for (LiveProduct lp : products) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", lp.getId());
            m.put("prodId", lp.getProdId());
            m.put("title", lp.getProduct() == null ? null : lp.getProduct().getTitle());
            m.put("coverUrl", lp.getProduct() == null ? null : lp.getProduct().getCoverUrl());
            m.put("price", lp.getProduct() == null ? null : lp.getProduct().getPrice());
            m.put("livePrice", lp.getLivePrice());
            m.put("clickCount", lp.getClickCount() == null ? 0 : lp.getClickCount());
            m.put("pinned", lp.getProdId() != null && lp.getProdId().equals(room.getPinnedProdId()));
            java.util.Map<String, Object> s = salesByProd.get(lp.getProdId());
            m.put("orders", s == null ? 0 : ((Number) s.get("orders")).intValue());
            m.put("gmv", s == null ? java.math.BigDecimal.ZERO : s.get("gmv"));
            m.put("qty", s == null ? 0 : ((Number) s.get("qty")).intValue());
            productRows.add(m);
        }
        java.util.Map<String, Object> total = liveRoomMapper.salesTotal(roomId);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("room", room);
        out.put("products", productRows);
        out.put("totalOrders", total == null || total.get("orders") == null ? 0 : ((Number) total.get("orders")).intValue());
        out.put("totalGmv", total == null || total.get("gmv") == null ? java.math.BigDecimal.ZERO : total.get("gmv"));
        out.put("commentCount", liveCommentMapper.selectCount(
                Wrappers.<com.lanlink.shopping.entity.LiveComment>lambdaQuery()
                        .eq(com.lanlink.shopping.entity.LiveComment::getRoomId, roomId)));
        return out;
    }

    @Override
    public boolean adminForceClose(Long roomId) {
        if (roomId == null) return false;
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room == null || room.getStatus() == null || room.getStatus() == 2) return false;
        // 处置同时撤销开播资格（review_status=2 + 原因），否则商户可凭既有 approval 立即复播
        LiveRoom upd = new LiveRoom();
        upd.setRoomId(roomId);
        upd.setStatus(2);
        upd.setReviewStatus(2);
        upd.setRejectReason("平台强制下架");
        upd.setUpdateTime(java.time.LocalDateTime.now());
        liveRoomMapper.updateById(upd);
        return liveRoomMapper.updateRoomStatus(roomId, 2) > 0;
    }
}
