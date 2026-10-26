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
        LiveComment comment = new LiveComment();
        comment.setRoomId(roomId);
        comment.setUserId(userId);
        comment.setNickname(nickname != null && !nickname.trim().isEmpty() ? nickname.trim() : "采购代表");
        comment.setAvatar(avatar != null ? avatar : "");
        comment.setContent(content.trim());
        comment.setMsgType(msgType != null ? msgType : "chat");
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
        if (roomId == null) return false;
        return liveRoomMapper.updatePinnedProduct(roomId, prodId) > 0;
    }

    @Override
    @Transactional
    public LiveRoom createRoom(Long merId, Long userId, LiveRoom room, List<LiveProduct> products) {
        room.setMerId(merId);
        room.setUserId(userId);
        if (room.getStatus() == null) {
            room.setStatus(1); // 默认开播
        }
        if (room.getViewerCount() == null || room.getViewerCount() <= 0) {
            room.setViewerCount(68);
        }
        if (room.getLikeCount() == null) {
            room.setLikeCount(0L);
        }
        if (room.getStartTime() == null) {
            room.setStartTime(LocalDateTime.now());
        }
        liveRoomMapper.insert(room);

        if (products != null && !products.isEmpty()) {
            int order = 1;
            for (LiveProduct lp : products) {
                lp.setRoomId(room.getRoomId());
                lp.setSortOrder(order++);
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
        if (roomId == null || status == null) return false;
        return liveRoomMapper.updateRoomStatus(roomId, status) > 0;
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
}
