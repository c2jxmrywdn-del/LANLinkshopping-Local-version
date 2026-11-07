package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.dto.TechPostCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechPost;
import com.lanlink.shopping.module.techservice.mapper.TechPostMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class TechCommunityService {

    private static final Set<String> POST_TYPES = Set.of("ARTICLE", "QUESTION", "DISCUSSION");

    private final TechPostMapper postMapper;
    private final TechComplianceGuard complianceGuard;

    public TechCommunityService(TechPostMapper postMapper, TechComplianceGuard complianceGuard) {
        this.postMapper = postMapper;
        this.complianceGuard = complianceGuard;
    }

    public TechPost createPost(Long userId, String nickname, TechPostCreateRequest req) {
        String type = req.getPostType() == null ? "" : req.getPostType().trim().toUpperCase();
        if (!POST_TYPES.contains(type)) {
            throw new BusinessException(400, "不支持的内容类型");
        }
        // 技术交流区同样受宣传合规约束：不得出现政府背书、成交担保类表述
        complianceGuard.validate("标题", req.getTitle());
        complianceGuard.validate("正文", req.getContent());
        TechPost post = new TechPost();
        post.setUserId(userId);
        post.setPostType(type);
        post.setTitle(req.getTitle().trim());
        post.setContent(req.getContent().trim());
        post.setTags(req.getTags());
        post.setIndustry(req.getIndustry());
        post.setAuthorName(nickname == null || nickname.isBlank() ? "平台用户" : nickname);
        post.setAuthorTitle("平台认证用户");
        post.setViewCount(0);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setStatus(1);
        postMapper.insert(post);
        return post;
    }

    public TechPost likePost(Long id) {
        TechPost post = postMapper.selectById(id);
        if (post == null || !Integer.valueOf(1).equals(post.getStatus())) {
            throw new BusinessException(404, "内容不存在");
        }
        postMapper.update(null, Wrappers.<TechPost>lambdaUpdate()
                .eq(TechPost::getPostId, id).setSql("like_count = like_count + 1"));
        post.setLikeCount((post.getLikeCount() == null ? 0 : post.getLikeCount()) + 1);
        return post;
    }

    public List<TechPost> myPosts(Long userId) {
        return postMapper.selectList(Wrappers.<TechPost>lambdaQuery()
                .eq(TechPost::getUserId, userId).orderByDesc(TechPost::getCreateTime));
    }
}
