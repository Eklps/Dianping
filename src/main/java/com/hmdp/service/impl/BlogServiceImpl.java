package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据点赞数降序查询
        Page<Blog> page = query().orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        /**
         * page.getRecords() 的作用是 从分页查询的结果对象中
         * 提取出真正的业务数据列表（即当前页的博客列表）。
         *
         * 此时 records 是一个 List<Blog>，里面的每个 Blog 对象只包含数据库 tb_blog
         * 表里的原始信息（如标题、内容、点赞数、发帖人ID）
         * 但还缺少一些动态信息，就是下面lambda函数的icon,name&isLike
         */
        List<Blog> records = page.getRecords();

        // 遍历，为每一个Blog填充用户信息和点赞状态
        // 这里的两个方法分别填充了blog类中缺失的icon,name以及isLike
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取当前登陆的用户
        Long userId = UserHolder.getUser().getId();
        // 2.查看当前用户是否已经进行过点赞
        // key="blog:liked"+blogId
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            // 3.如果没点过赞，进行点赞操作
            // 3.1数据库点赞数++
            boolean isSuccess = update().setSql("liked= liked +1").eq("id", id).update();
            // 3.2保存用户到Redis的SortedSet集合，
            if (isSuccess) {
                // zadd key score member
                // score使用当前的时间戳，方便后面点赞按时间排序
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }

        } else {
            // 4.如果已点赞，取消点赞
            // 4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            // 4.2把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    // 这个是查看博客的点赞排行榜
    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" + id;
        // 1.查询top5点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 如果没人点赞，直接返回空列表
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 2.解析出其中的用户id
        // 先把Set<String>转换为List<Long>
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 然后转为带","的String好传入database
        String idStr = StrUtil.join(",", ids);

        // 3.根据用户id查询用户WHERE id IN (5,1) ORDER BY FIELD(id,5,1)
        // 这里的5,1只是举例，举例用户id是5，1（有顺序）
        // 这里必须用last("ORDER BY FIELD...")来保证顺序
        /**
         * SQL 的 IN 查询会自动按主键 ID 升序排序，这会弄乱 Redis 里的时间顺序
         * last 方法会将字符串原封不动拼接到生成的 SQL 语句最后。
         * 作用：强制 MySQL 按照 idStr（如 "5,1,3..."）给定的顺序返回结果。
         * .list()：
         * 执行者：执行最终查询，返回一个 List<User>。
         * .stream()将刚刚的List<User>转换为流
         * .map(...):流里面的每一个元素都是User对象，我们要把User数据脱敏为UserDTO
         * .collect... 将脱敏出来的数据流再重新装成List
         */
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店原文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }

        // 3.返回成功的id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 2.查询blog相关的用户
        queryBlogUser(blog);
        // 3.查询是否点赞（新增）
        isBlogLiked(blog);// 这是下面的成员方法
        return Result.ok(blog);
    }

    // 一个相关的用法，用于查询并填充作者信息
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    // 辅助方法：判断是否已经点赞，用于返回给前端的显示
    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();

        // 2.判断当前登录用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 3.如果score不为null,说明已经点过赞，将isLike设置为true
        blog.setIsLike(score != null);
    }
}
