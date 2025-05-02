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

    Result getHotBlog(Integer current);

    Result getMyBlog(Integer current);

    Result getBlog(Long id);

    Result likeBlog(Long id);

    Result saveBlog(Blog blog);

    Result getBlogLikers(Long id);

    Result queryBlogOfFollow(Long max, Integer offset);
}
