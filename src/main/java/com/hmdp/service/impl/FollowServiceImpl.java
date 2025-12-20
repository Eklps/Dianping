package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.entity.User;

import javax.annotation.Resource;

import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow){
        //1.获取登录用户
        Long userId= UserHolder.getUser().getId();
        String key="follows:"+userId;
        //2.判断关注还是取关
        if(isFollow){
            //2.1关注，新增数据
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess=save(follow);
            if(isSuccess){
                //把关注用户的id，放入Redis的Set集合 sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
            //2.2取关，删除
            //delete from tb_follow where user_id = ? and follow_user_id =?
            boolean isSuccess=remove(new QueryWrapper<Follow>()
                    .eq("user_id",userId)
                    .eq("follow_user_id",followUserId)
            );
            //在这里把关注关系从RedisSet删除
            stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId){
        //1.获取登录用户
        Long userId=UserHolder.getUser().getId();
        // 2. 查询是否关注
        // select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count=query().eq("user_id",userId)
                .eq("follow_user_id",followUserId)
                .count();
        // 3. 返回判断
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id){
        //1.获取当前用户
        Long userId=UserHolder.getUser().getId();
        String key1="follows:"+userId;
        //求交集
        String key2="follows:"+id;
        Set<String> intersect=stringRedisTemplate.opsForSet().intersect(key1,key2);

        if(intersect==null||intersect.isEmpty()){
            //无交集
            return Result.ok(Collections.emptyList());
        }

        //3.有交集，解析id集合
        //map 方法会遍历流中的每一个元素。
        // Long::valueOf 是方法引用，等同于 s -> Long.valueOf(s)
        List<Long> ids=intersect.stream().map(Long::valueOf).collect(Collectors.toList());;

        //4.查询用户
        // 4. 查询用户并转化为 DTO 列表
        List<UserDTO> users = userService.lambdaQuery()
                .in(User::getId, ids) // 相当于 WHERE id IN (ids...)
                .list()               // 结束数据库查询，返回 List<User>
                .stream()             // 开启 Stream 流
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)) // 属性映射
                .collect(Collectors.toList()); // 收集结果
        return Result.ok(users);
    }

}
