package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工业现场 e-POD 数字签收回单存证实体。
 */
@Data
@TableName("t_logistics_pod_record")
public class LogisticsPodRecord {

    @TableId(type = IdType.AUTO)
    private Long podId;

    /** 关联运单ID */
    private Long waybillId;

    /** 关联运单号 */
    private String waybillNo;

    /** 关联订单号 */
    private String orderNo;

    /** 上传用户ID (买方/现场人员) */
    private Long uploaderId;

    /** 现场签收人姓名 */
    private String signerName;

    /** 现场签收人联系电话 */
    private String signerPhone;

    /** 现场实际签收时间 */
    private LocalDateTime signedTime;

    /** 加盖公章/收货专用章的纸质签收原件照片 URL */
    private String stampPhotoUrl;

    /** 现场货物外观/托盘开箱照片 JSON 列表 */
    private String cargoPhotosJson;

    /** 签收现场打卡经度 */
    private BigDecimal geoLng;

    /** 签收现场打卡纬度 */
    private BigDecimal geoLat;

    /** 现场签收点验备注 */
    private String signRemark;

    /** 验签审核状态：pending 待审核 / approved 验签合格 / rejected 已驳回 */
    private String auditStatus;

    /** 验签审核人用户ID (商户/平台管理人员) */
    private Long auditorId;

    /** 验签审核意见 / 驳回理由 */
    private String auditNote;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
