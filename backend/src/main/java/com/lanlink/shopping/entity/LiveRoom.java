package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品直播间实体。
 * 状态：0=预告(UPCOMING)，1=直播中(LIVE)，2=已结束(ENDED)。
 */
@Data
@TableName("t_live_room")
public class LiveRoom {
    @TableId(type = IdType.AUTO)
    private Long roomId;
    private Long merId;
    private Long userId;
    private String title;
    private String coverUrl;
    private String streamUrl;
    private Integer status;
    /** 平台审核：0待审核 1通过 2驳回（开播准入门槛，平台后台批准） */
    private Integer reviewStatus;
    /** 审核驳回原因（回显给商户） */
    private String rejectReason;
    private Integer viewerCount;
    /** 观看峰值（进出场原子维护，直播复盘核心指标） */
    private Integer peakViewerCount;
    private Long likeCount;
    private Long pinnedProdId;
    private String notice;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 关联商户名称 (非数据库字段) */
    @TableField(exist = false)
    private String merchantName;

    /** 关联商户头像 (非数据库字段) */
    @TableField(exist = false)
    private String merchantAvatar;

    /** 关联讲解中商品 (非数据库字段) */
    @TableField(exist = false)
    private Product pinnedProduct;

    /** 直播间关联商品总数 (非数据库字段) */
    @TableField(exist = false)
    private Integer productCount;
}
