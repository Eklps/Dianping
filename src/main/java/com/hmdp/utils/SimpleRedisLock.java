package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;//在一人一票处理中是"order:"+userId,
    //order可以变因为这个工具类可能有很多个不同的部门使用，所以加上用途的名称

    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.name=name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeSec){
        //先要获取线程的标识,用于后面对于value的存储
        long threadId=Thread.currentThread().getId();
        //尝试上锁，并用success记录是否上锁成功
        boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(
                        KEY_PREFIX+name,
                        threadId+"",timeSec,
                        TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock(){
        stringRedisTemplate.delete(KEY_PREFIX+name);
    }

}
