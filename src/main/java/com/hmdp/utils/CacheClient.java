package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.service.impl.ShopServiceImpl.CACHE_REBUILD_EXECUTOR;

@Slf4j // 日志注解
@Component // 交给Spring管理
public class CacheClient {
    // 因为这个stringRedisTemplate没有交给@Resource也就是spring的对象池，所以要手动写出类构造函数
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 这是一个通用的将object转为JSONString传入redis的工具类
     * 
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 这是一个带有逻辑过期时间LogicExipireTime的，将Object转为JSONString传入redis的工具类
     * 
     * @param key   前缀加id
     * @param value data的值
     * @param time  逻辑过期时间的增加时间
     * @param unit  单位
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 这是一个对于任意返回类型的处理缓存穿透的模板代码
     * 
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @return 这里传入的是一个查数据库表的方法
     * @param <R>
     * @param <ID>
     * @time 要设置的过期时间
     * @unit 时间的单位
     */
    public <R, ID> R queryWithPssThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中】
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在直接返回R 类对象给上层
            return JSONUtil.toBean(shopJson, type);
        }

        // 新增缓存穿透检查
        if ("".equals(shopJson)) {
            return null;
        }

        // 4.不存在，根据id查询数据库
        // 但是有一个问题，我们这个使用模板的方法怎么直到用哪个表的查询方法呢
        // 这时就要直接给queryWithPassThrough这个方法直接传入方法参数了
        R r = dbFallBack.apply(id);
        // 5.数据库判断id是否存在
        if (r == null) {
            // 6.不存在，将这个key和对应的空值存入redis以防缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
            // 返回错误,终结本次穿透
            return null;
        }
        // 7.存在，将商铺信息写入redisr
        this.set(key, r, time, unit);
        // 8.返回
        return r;
    }

    // 逻辑过期解决缓存击穿
    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if (StrUtil.isBlank(json)) {
            // 3.未命中则直接返回空值
            return null;
        }
        // 4.命中，需要判断逻辑过期时间,则需要先把json反序列化为RedisData
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回商铺信息,并释放锁
            return r;
        }
        // 6.过期，需要缓存重建
        // 6.1尝试获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 6.2 成功获取锁,double check的目的是防止在获取到锁的前一瞬间redis已更新，逻辑过期时间也被更新，大于当前时间
            String json2 = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json2)) {
                RedisData redisData1 = JSONUtil.toBean(json2, RedisData.class);
                if (redisData1.getExpireTime().isAfter(LocalDateTime.now())) {
                    // 如果取到的RedisData的逻辑过期时间大于当前，说明在刚刚拿到锁的一瞬间已经更新
                    unLock(lockKey);
                    return JSONUtil.toBean((JSONObject) redisData1.getData(), type);
                }
            }

            // 派出另一个线程进行从数据库向redis缓存的刷新工作
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallBack.apply(id);
                    // 建立redish缓存
                    this.setWithLogicExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }
        // 未能成功获取锁，返回旧的商铺信息,但是因为无论失败成功都要返回店铺信息，所以留到成功之后写返回语句
        // 7.返回
        return r;
    }

    // 建立互斥锁
    private boolean tryLock(String keyLock) {
        Boolean bool = stringRedisTemplate.opsForValue().setIfAbsent(keyLock, "1", RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.MINUTES);
        // 因为我们要返回的是boolean基本数据类型而不是Boolean这个对象（引用数据类型）
        // 如果return Boolean的话存在自动转换，可能会出现一些问题
        // 在一些情况可能Boolean可能会返回null,但是boolean在处理null的时候会有问题
        // 所以用这个isTrue()方法把false,null都返回null
        return BooleanUtil.isTrue(bool);
    }

    // 释放互斥锁
    private void unLock(String keyLock) {
        if (BooleanUtil.isTrue(stringRedisTemplate.hasKey(keyLock))) {
            stringRedisTemplate.delete(keyLock);
        }
    }

}
