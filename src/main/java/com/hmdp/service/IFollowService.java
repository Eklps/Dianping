package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {
    Result followCommons(Long id);

    //关注与取关
    Result follow(Long followUserId, Boolean isFollow);

    //判断是否关注
    Result isFollow(Long followUserId);
}
