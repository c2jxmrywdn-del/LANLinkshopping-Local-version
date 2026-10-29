package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.MerchantChat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MerchantChatMapper extends BaseMapper<MerchantChat> {

    /** find-or-create（唯一键 ux_chat_buyer_mer + 幂等插入，防双击建双会话） */
    @Insert("INSERT INTO t_merchant_chat (buyer_id, mer_id, prod_id, last_msg_content, last_sender_role,"
            + " unread_count, status, create_time) VALUES (#{buyerId}, #{merId}, #{prodId}, '', '', 0, 'active', NOW())"
            + " ON DUPLICATE KEY UPDATE prod_id = VALUES(prod_id), update_time = NOW()")
    int findOrCreate(@Param("buyerId") Long buyerId, @Param("merId") Long merId, @Param("prodId") Long prodId);

    /** 按 (buyer, mer) 取会话（find-or-create 后查询用） */
    @Select("SELECT * FROM t_merchant_chat WHERE buyer_id = #{buyerId} AND mer_id = #{merId}")
    MerchantChat selectByBuyerAndMer(@Param("buyerId") Long buyerId, @Param("merId") Long merId);

    /** 买家维度未读：最后发送方为商家 → 会话未读归买家 */
    @Select("SELECT COALESCE(SUM(unread_count), 0) FROM t_merchant_chat"
            + " WHERE buyer_id = #{buyerId} AND status = 'active' AND last_sender_role = 'merchant'")
    long buyerUnreadTotal(@Param("buyerId") Long buyerId);

    /** 商户维度未读：最后发送方为买家 → 会话未读归商家 */
    @Select("SELECT COALESCE(SUM(unread_count), 0) FROM t_merchant_chat"
            + " WHERE mer_id = #{merId} AND status = 'active' AND last_sender_role = 'buyer'")
    long merchantUnreadTotal(@Param("merId") Long merId);

    /** 进入会话时按角色清零对方未读（幂等：已是 0 则无影响） */
    @Update("UPDATE t_merchant_chat SET unread_count = 0, update_time = NOW()"
            + " WHERE id = #{sessionId} AND last_sender_role = #{oppositeRole}")
    int clearUnread(@Param("sessionId") Long sessionId, @Param("oppositeRole") String oppositeRole);
}
