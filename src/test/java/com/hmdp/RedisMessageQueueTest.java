package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@SpringBootTest
public class RedisMessageQueueTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 26. Redis消息队列 - 基于List实现消息队列
     * <p>
     * 优点：
     * 1. 利用 Redis 存储，不受限于 JVM 内存上限
     * 2. 基于 Redis 的持久化机制，数据安全性有保证
     * 3. 可以满足消息有序性
     * <p>
     * 缺点：
     * 1. 无法避免消息丢失（取出消息后，处理中异常，消息就丢了）
     * 2. 只支持单消费者（一个消息只能被一个消费者取走）
     */
    @Test
    void testListQueue() throws InterruptedException {
        String queueName = "simple.queue";

        // 1. 模拟消费者 (在后台线程运行)
        Thread consumer = new Thread(() -> {
            System.out.println("Consumer: 开始监听队列...");
            while (true) {
                // BRPOP key timeout: 移出并获取列表的最后一个元素， 如果列表没有元素会阻塞列表直到等待超时或发现可弹出元素为止。
                // 这里的 timeout = 0 表示一直等待
                String msg = stringRedisTemplate.opsForList().rightPop(queueName, 0, TimeUnit.SECONDS);
                if (msg != null) {
                    System.out.println("Consumer: 接收到消息 -> " + msg);
                }
                // 简单的终止条件，防止测试无限运行
                if ("exit".equals(msg)) {
                    break;
                }
            }
            System.out.println("Consumer: 结束监听。");
        });
        consumer.start();

        // 2. 模拟生产者
        System.out.println("Producer: 准备发送消息...");
        Thread.sleep(1000); // 等待消费者启动

        // LPUSH key value
        stringRedisTemplate.opsForList().leftPush(queueName, "Hello, Redis List!");
        System.out.println("Producer: 发送第一条消息");

        Thread.sleep(1000);
        stringRedisTemplate.opsForList().leftPush(queueName, "Redis Message Queue is cool!");
        System.out.println("Producer: 发送第二条消息");

        Thread.sleep(1000);
        stringRedisTemplate.opsForList().leftPush(queueName, "exit");
        System.out.println("Producer: 发送退出指令");

        // 等待消费者结束
        consumer.join();
    }

    /**
     * 27. Redis消息队列 - PubSub 实现消息队列
     * <p>
     * 优点：
     * 1. 典型的发布订阅模型
     * 2. 支持多生产、多消费
     * <p>
     * 缺点：
     * 1. 不支持数据持久化
     * 2. 无法避免消息丢失（如果消费者下线，期间的消息会全部丢失）
     * 3. 消息堆积有上限（超出缓冲区会丢包）
     */
    @Test
    void testPubSub() throws InterruptedException {
        String channelName = "test.channel";

        // 1. 模拟消费者
        Thread consumer = new Thread(() -> {
            System.out.println("Sub: 开始订阅频道 " + channelName);
            // 这是一个阻塞操作
            stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                connection.subscribe((message, pattern) -> {
                    String msg = new String(message.getBody());
                    System.out.println("Sub: 收到消息 -> " + msg);
                }, channelName.getBytes());
                return null;
            });
        });
        consumer.start();

        // 2. 模拟生产者
        Thread.sleep(1000);
        System.out.println("Pub: 发布消息 Hello");
        stringRedisTemplate.convertAndSend(channelName, "Hello PubSub");

        Thread.sleep(1000);
        System.out.println("Pub: 发布消息 World");
        stringRedisTemplate.convertAndSend(channelName, "World");

        // 让子线程跑一会，否则主线程结束应用就关闭了
        Thread.sleep(2000);
        System.out.println("Test 结束");
    }
}
