package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.entity.TechPost;
import com.lanlink.shopping.module.techservice.mapper.TechPostMapper;
import org.springframework.stereotype.Service;

/** 技术交流读模型（写操作仍在 TechCommunityService）。 */
@Service
public class TechCommunityQueryService {

    private final TechPostMapper postMapper;

    public TechCommunityQueryService(TechPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    public Page<TechPost> postPage(long page, long size, String type, String industry, String keyword) {
        String kw = text(keyword);
        return postMapper.selectPage(TechPageable.of(page, size, TechPageable.PUBLIC_MAX_SIZE), Wrappers.<TechPost>lambdaQuery()
                .eq(TechPost::getStatus, 1)
                .eq(text(type) != null, TechPost::getPostType, text(type))
                .eq(text(industry) != null, TechPost::getIndustry, text(industry))
                .and(kw != null, w -> w.like(TechPost::getTitle, kw)
                        .or().like(TechPost::getTags, kw)
                        .or().like(TechPost::getContent, kw))
                .orderByDesc(TechPost::getCreateTime));
    }

    public TechPost postDetail(Long id) {
        TechPost post = postMapper.selectById(id);
        if (post == null || !Integer.valueOf(1).equals(post.getStatus())) {
            throw new BusinessException(404, "内容不存在");
        }
        postMapper.update(null, Wrappers.<TechPost>lambdaUpdate()
                .eq(TechPost::getPostId, id).setSql("view_count = view_count + 1"));
        post.setViewCount((post.getViewCount() == null ? 0 : post.getViewCount()) + 1);
        return post;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
