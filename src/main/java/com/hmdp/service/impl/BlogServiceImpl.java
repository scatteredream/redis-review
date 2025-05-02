package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 保存探店博文
        save(blog);
        List<Long> followers = followService.lambdaQuery()
                .eq(Follow::getFollowUserId, userId).list().stream()
                .map(Follow::getFollowUserId).toList();
        for (Long followerId : followers) {
            String key = FEED_KEY + followerId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result getBlogLikers(Long blogId) {
        Long userId = UserHolder.getUser().getId();
        Set<String> top5Likers = stringRedisTemplate.opsForZSet()
                .range(BLOG_LIKED_KEY + blogId, 0, 4);
        if (top5Likers == null || top5Likers.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5Likers.stream().map(Long::valueOf).toList();
        String idStr = StrUtil.join(",", ids);// 1,3,2,4

        List<UserDTO> userDTOs = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map((user) -> BeanUtil.copyProperties(user, UserDTO.class)).toList();
        return Result.ok(userDTOs);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        os = minTime == max ? os : os + offset;
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            setBlogUserInfo(blog);
            // 5.2.查询blog是否被点赞
            setBlogIsLike(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    @Override
    public Result getBlog(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("您请求的blog不存在");
        }
        setBlogUserInfo(blog);
        setBlogIsLike(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long blogId) {
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet()
                .score(BLOG_LIKED_KEY + blogId, userId.toString());
        if (score == null) {
            // 修改点赞数量
            boolean successLike = update().setSql("liked = liked + 1")
                    .eq("id", blogId).update();
            if (successLike) {
                stringRedisTemplate.opsForZSet()
                        .add(BLOG_LIKED_KEY + blogId, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean successDislike = update().setSql("liked = liked - 1")
                    .eq("id", blogId).update();
            if (successDislike) {
                stringRedisTemplate.opsForZSet()
                        .remove(BLOG_LIKED_KEY + blogId, userId.toString());
            }
        }
        return Result.ok();
    }

    private void setBlogUserInfo(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void setBlogIsLike(Blog blog) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return;
        }
        Long userId = userDTO.getId();
        Double score = stringRedisTemplate.opsForZSet()
                .score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
//        Boolean member = stringRedisTemplate.opsForSet()
//                                  .isMember(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        Boolean member = score != null;
        blog.setIsLike(Boolean.TRUE.equals(member));
    }


    @Override
    public Result getMyBlog(Integer current) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", userId).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result getHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            setBlogUserInfo(blog);
            setBlogIsLike(blog);
        });
        return Result.ok(records);
    }


}
