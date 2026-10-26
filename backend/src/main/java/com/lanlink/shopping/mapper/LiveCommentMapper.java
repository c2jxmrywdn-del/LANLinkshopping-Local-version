package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LiveComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LiveCommentMapper extends BaseMapper<LiveComment> {

    @Select("SELECT * FROM t_live_comment WHERE room_id = #{roomId} ORDER BY comment_id DESC LIMIT #{limit}")
    List<LiveComment> selectRecentComments(@Param("roomId") Long roomId, @Param("limit") int limit);
}
