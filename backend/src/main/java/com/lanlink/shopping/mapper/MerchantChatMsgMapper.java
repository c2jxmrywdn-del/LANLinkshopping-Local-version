package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.MerchantChatMsg;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MerchantChatMsgMapper extends BaseMapper<MerchantChatMsg> {

    /** 会话内对方发送的消息置为已读（进入会话/收到新消息查看后调用） */
    @Update("UPDATE t_merchant_chat_msg SET read_flag = 1"
            + " WHERE session_id = #{sessionId} AND sender_role = #{oppositeRole} AND read_flag = 0")
    int markRead(@Param("sessionId") Long sessionId, @Param("oppositeRole") String oppositeRole);
}
