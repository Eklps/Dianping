package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;// 在一人一票处理中是"order:"+userId,
    // order可以变因为这个工具类可能有很多个不同的部门使用，所以加上用途的名称
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    private static final String THREAD_ID_PREFIX = UUID.randomUUID().toString() + "-";

    // 静态初始化lua脚本，避免每次都加载
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeSec) {
        // 先要获取线程的标识,用于后面对于value的存储
        String threadId = THREAD_ID_PREFIX + Thread.currentThread().getId();
        // 尝试上锁，并用success记录是否上锁成功
        boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(
                        KEY_PREFIX + name,
                        threadId + "", timeSec,
                        TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        // 获取线程标识,用于后面获取完value的判定
        String targetId = THREAD_ID_PREFIX + Thread.currentThread().toString();
        // 获取锁中记录的线程标识，也就是存储的value
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        if (targetId.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
            // 如果两个id匹配就说明就是要找的，已经有的锁，然后开锁
        }
    }

}
