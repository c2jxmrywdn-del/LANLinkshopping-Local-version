package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工业随货同行单证与质保证书实体 (CoA, MTR, CoC 等)。
 */
@Data
@TableName("t_logistics_document")
public class LogisticsDocument {

    @TableId(type = IdType.AUTO)
    private Long docId;

    /** 关联运单ID */
    private Long waybillId;

    /** 关联运单号 */
    private String waybillNo;

    /** 关联订单号 */
    private String orderNo;

    /** 单证类型: coa出厂检验报告 / mtr材质单 / coc合格证 / packing_list装箱单 / spec技术规格书 */
    private String docType;

    /** 单证文件原始名称 */
    private String docName;

    /** 文件存储路径 / 内容中转访问 URL */
    private String fileUrl;

    /** 文件字节大小 */
    private Long fileSize;

    /** 文件 MIME 类型 */
    private String mimeType;

    /** SHA-256 文件哈希防伪指纹 */
    private String sha256Hash;

    /** 生产批次号 / 炉批号 / 出厂编号 */
    private String batchNo;

    /** 上传操作人用户ID */
    private Long uploaderId;

    /** 创建时间 */
    private LocalDateTime createTime;
}
