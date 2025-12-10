package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 测试1：预热【已过期】的缓存数据
     * 用于测试缓存击穿时的异步重建逻辑
     * 运行此测试后，用 JMeter 或 Postman 访问 /shop/1，会触发缓存重建
     */
    @Test
    void testSaveShopWithExpiredCache() {
        Long id = 1L;
        // 使用 CacheClient 工具类，设置逻辑过期时间为 1 秒（立即过期）
        Shop shop = shopService.getById(id);
        cacheClient.setWithLogicExpire(RedisConstants.CACHE_SHOP_KEY + id, shop, 1L, TimeUnit.SECONDS);
        System.out.println("【已过期】缓存预热成功！ID: " + id);
        System.out.println("请稍等 2 秒后再测试，确保缓存已逻辑过期...");
    }

    /**
     * 测试2：预热【未过期】的缓存数据
     * 用于测试直接命中缓存的场景
     * 运行此测试后，用 Postman 访问 /shop/1，会直接返回缓存数据
     */
    @Test
    void testSaveShopWithValidCache() {
        Long id = 1L;
        // 使用 CacheClient 工具类，设置逻辑过期时间为 10 分钟（未过期）
        Shop shop = shopService.getById(id);
        cacheClient.setWithLogicExpire(RedisConstants.CACHE_SHOP_KEY + id, shop, 10L, TimeUnit.MINUTES);
        System.out.println("【未过期】缓存预热成功！ID: " + id);
        System.out.println("现在去 Postman 测试 /shop/1，应该直接返回数据，不会触发重建。");
    }

    /**
     * 测试3：清除缓存
     * 用于重置测试环境
     */
    @Test
    void testClearCache() {
        Long id = 1L;
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + id);
        System.out.println("缓存已清除！ID: " + id);
    }
}
