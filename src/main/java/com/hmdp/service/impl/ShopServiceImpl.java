package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
//import jdk.vm.ci.meta.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    public static final String LOCK_SHOP_KEY = RedisConstants.LOCK_SHOP_KEY;
    public static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //使用缓存穿透的工具类
        //这里的id2->getById(id2)可以改写为
        /**
         this::getById
         **/
//        Shop shop = cacheClient.queryWithPssThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2),
//                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //使用缓存击穿的工具类
        Shop shop = cacheClient.queryWithLogicExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 最开始的缓存穿透的调用方法
        // Shop shop=queryWithCacheThrough(id);

        // 使用应对缓存击穿的解法：互斥锁
        //Shop shop = queryWithMutex(id);

        //缓存击穿的写法
        //设置逻辑过期时间来应对缓存击穿
//        Shop shop=queryWithLogicExpire(id);
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中】
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在直接返回前端
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // 缓存穿透检查
        if ("".equals(shopJson)) {
            return null;
        }

        // 4.实现缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 4.1 尝试获取互斥锁

            if (!tryLock(lockKey)) {
                // 4.2 获取失败，休眠
                Thread.sleep(50L);
                return queryWithMutex(id);
            }
            // 4.3获取成功，再次检查缓存（Double Check）
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 缓存命中正常数据
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 缓存命中空值（防穿透标记），直接返回不查库
            if ("".equals(shopJson)) {
                return null;
            }
            // 真正未命中，继续往下查库

            // 5.获取失败，转接给数据库看看是否存在数据
            shop = getById(id);// mybatis的便捷操作，getById()

            //模拟重建延时
             Thread.sleep(500);


            if (shop == null) {
                // 6.不存在，将这个key和对应的空值存入redis以防缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                // 返回错误,终结本次穿透
                return null;
            }
            // 7.存在，将商铺信息写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL,
                    TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 8.释放互斥锁
            unLock(lockKey);
        }

        return shop;
        // 9.返回
    }

//    // 缓存穿透写法
//    private Shop queryWithCacheThrough(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        // 1.从redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 2.判断缓存是否命中】
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 3.存在直接返回前端
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//
//        // 新增缓存穿透检查
//        if ("".equals(shopJson)) {
//            return null;
//        }
//
//        // 4.不存在，根据id查询数据库
//        Shop shop = getById(id);// mybatis的便捷操作，getById()
//        // 5.数据库判断id是否存在
//        if (shop == null) {
//            // 6.不存在，将这个key和对应的空值存入redis以防缓存穿透
//            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//            // 返回错误,终结本次穿透
//            return null;
//        }
//        // 7.存在，将商铺信息写入redis
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL,
//                TimeUnit.MINUTES);
//        // 8.返回
//        return shop;
//    }

//    //逻辑过期解决缓存击穿
//    private Shop queryWithLogicExpire(Long id){
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        // 1.从redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 2.判断缓存是否命中
//        if (StrUtil.isBlank(shopJson)) {
//            // 3.未命中则直接返回空值
//            return null;
//        }
//        //4.命中，需要判断逻辑过期时间,则需要先把json反序列化为RedisData
//        RedisData redisData=JSONUtil.toBean(shopJson,RedisData.class);
//        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
//        LocalDateTime expireTime=redisData.getExpireTime();
//        //5.判断是否过期
//        if(expireTime.isAfter(LocalDateTime.now())){
//            //未过期，直接返回商铺信息,并释放锁
//            return shop;
//        }
//        //6.过期，需要缓存重建
//        //6.1尝试获取互斥锁
//        String lockKey=LOCK_SHOP_KEY+id;
//        boolean isLock=tryLock(lockKey);
//        if(isLock){
//           //6.2 成功获取锁,double check的目的是防止在获取到锁的前一瞬间redis已更新，逻辑过期时间也被更新，大于当前时间
//            String shopJson2=stringRedisTemplate.opsForValue().get(key);
//            if(StrUtil.isNotBlank(shopJson2)){
//                RedisData redisData1=JSONUtil.toBean(shopJson2,RedisData.class);
//                if(redisData1.getExpireTime().isAfter(LocalDateTime.now())){
//                    //如果取到的RedisData的逻辑过期时间大于当前，说明在刚刚拿到锁的一瞬间已经更新
//                    unLock(lockKey);
//                    return JSONUtil.toBean((JSONObject) redisData1.getData(),Shop.class);
//                }
//            }
//
//            // 派出另一个线程进行从数据库向redis缓存的刷新工作
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    //重建缓存
//                    saveShop2Redis(id,1800L);
//                    Thread.sleep(300L);
//
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    //释放锁
//                    unLock(lockKey);
//                }
//            });
//
//
//
//        }
//            //未能成功获取锁，返回旧的商铺信息,但是因为无论失败成功都要返回店铺信息，所以留到成功之后写返回语句
//
//
//
//        // 7.返回
//        return shop;
//    }

    @Transactional
    @Override
    public Result updateShopById(Shop shop) {
        // 判断商店id为不为空
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 关于这里为什么传入的参数是shop,因为不仅要包含id信息还要包含各种商铺更新的信息
        // 另外最上面的extends ServiceImpl<ShopMapper, Shop>里面的参数SHop也决定了mybatis plus
        // 的语句要传入shop参数

        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();

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

    /**
     *将商铺信息转为带有逻辑TTL的对象存入redis
     * @param id 商铺id，数据库根据这个查询
     * @param expireSeconds 逻辑过期时间管理人员设置的过期时间
     */
    private void saveShop2Redis(Long id,Long expireSeconds){
        //1.查询商铺信息
        Shop shop=getById(id);

        //2.封装RedisData，也就是包含逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        //3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
