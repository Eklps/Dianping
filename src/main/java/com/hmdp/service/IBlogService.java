package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {
    /**
     * 查询热门笔记
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据ID查询笔记详情
     */
    Result queryBlogById(Long id);

    /**
     * 点赞或取消点赞
     */
    Result likeBlog(Long id);

    /**
     * 查询点赞排行榜
     */
    Result queryBlogLikes(Long id);

    /**
     * 保存笔记
     */
    Result saveBlog(Blog blog);

}
