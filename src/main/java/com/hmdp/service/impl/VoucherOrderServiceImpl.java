package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 下面是为了实现redis消息队列的消费者 定义的常量和线程池
    // stream队列名称
    private static final String STREAM_KEY = "stream.orders";
    // 消费者组名称
    private static final String GROUP_NAME = "g1";
    // 消费者名称
    private static final String CONSUMER_NAME = "c1";
    // 线程池（单线程，用于异步处理订单）
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 静态初始化lua脚本，避免每次都加载
    private static final DefaultRedisScript<Long> SEKILL_SCRIPT;
    static {
        SEKILL_SCRIPT = new DefaultRedisScript<>();
        SEKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SEKILL_SCRIPT.setResultType(Long.class);
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息
                    // MapRecord是Spring data redis中的一个类，用于表示stream中的消息
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    // 回顾一下上面的指令，BLOCK 2000指的是如果队列中没有订单信息就阻塞2000ms等待
                    // spring要生成争取恶的XREADGROUP指令，下面就必须对位传入这三个关键的参数
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME)
                            // 这里StreamReadOptions.empty()是为了创建一个默认的参数
                            , StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

                    // 2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 没有消息，继续下一次while循环
                        continue;
                    }

                    // 3.解析消息中的订单信息
                    // MapRecord里的第一个成员String实际上是Redis Stream 的名称，这里是stream.order
                    MapRecord<String, Object, Object> record = list.get(0);
                    // 取出来的的是userId(map的key),voucherId(map的value)
                    Map<Object, Object> values = record.getValue();
                    // values是一个map，但是他们的键值名称分别是userId,voucherId,可以和VoucherOrder中的成员变量userId,voucherId对应
                    // 故BeanUtil可自动填入
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 4.执行业务：创建订单
                    handleVoucherOrder(voucherOrder);

                    // 5.ACK确认
                    // 温习ack就是消费者完成了任务从pendlist中确认删除这条消息记录
                    /**
                     * param1:redis Stream的名称
                     * param2:处理这个消息的消费者组名
                     * param3:record.getId() 是什么？
                     * record.getId() 获取的是当前被读取到的那条消息在 Stream 中的唯一标识符（ID）。
                     * 格式： 这个 ID 是一个特殊的字符串，
                     * 格式通常是 时间戳毫秒数-序号，例如：1642999999000-0。
                     */
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 出现异常，处理plending list中的消息
                    handlePendingList();
                }

            }
        }

    }

    @PostConstruct
    private void init() {
        // 尝试创建消费者组（如果已存在会抛异常，捕获忽略即可）
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP_NAME);
        } catch (Exception e) {
            // 组已存在，忽略
        }
        // 启动消费者线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 处理认领了但是没ACK的消息
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 读取 Pending List 中的消息（用 0 而不是 >）
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(STREAM_KEY, ReadOffset.from("0")));

                // 如果pending list没有消息了，说明处理完了，退出循环
                if (list == null || list.isEmpty()) {
                    break;
                }

                // 解析并处理,与上面的主逻辑类似，将redis stream中的消息转换为java中的MapRecord
                // 然后将MapRecord转换为Map(下面的value),然后把value的数据填入一个新的VoucherOrder
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                handleVoucherOrder(voucherOrder);

                // ACK
                stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());

            } catch (Exception e) {
                log.error("在处理pleanding list中出现错误", e);
                try {
                    Thread.sleep(50);// 休息一下再重试
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // 将订单写入数据库
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 这里直接写数据库，不需要再判断库存和一人一单（Lua 已经做过了）
        save(voucherOrder);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisWorker.nextId("order");
        // 1.执行lua脚本，也就是判断当前用户能否买票，
        // 并且lua脚本调用的redis的xadd方法在redis中创建了stream
        // 把符合条件的扔到stream上
        //
        Long result = stringRedisTemplate.execute(
                SEKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),
                orderId.toString());

        // 2.判断结果是否为0
        int res = result.intValue();
        // 2.1 不为0，没有购买资格
        if (res != 0) {
            return Result.fail(res == 1 ? "库存不足,下单失败" : "一人可购一单");
        }

        // TODO 加入到阻塞队列

        // 3. 返回订单id
        return Result.ok(orderId);
    }

    /*
     * @Override
     * 
     * @Transactional
     * public Result seckillVoucher(Long voucherId) {
     * // 1.查询优惠券
     * SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
     * // 2.判读是否开始
     * if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
     * return Result.fail("活动尚未开始");
     * }
     * // 3.判断是否已经过期
     * if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
     * return Result.fail("活动已结束");
     * }
     * // 4.判断库存是否充足
     * if (voucher.getStock() < 1) {
     * return Result.fail("库存不足");
     * }
     * // 5.扣减库存
     *//**
        * setSql("stock = stock - 1") 设置自定义 SQL 片段，这里是让 stock 字段减 1
        * eq("voucher_id", voucherId) 添加 WHERE 条件：voucher_id = ?
        * update() 执行更新操作，返回是否成功的结果
        */

    /*
     * boolean success = seckillVoucherService.update().setSql("stock = stock -1")
     * .eq("voucher_id", voucherId).gt("stock", 0)
     * .update();
     * // 原本gt处是eq也就是判断括号内的两者相等，但这里采用gt就是说stock大于零也就是还有库存
     * 
     * if (!success) {
     * return Result.fail("库存不足");
     * }
     * 
     * Long userId=UserHolder.getUser().getId();
     * // //尝试上锁
     * // SimpleRedisLock lock=new
     * SimpleRedisLock("order:"+userId,stringRedisTemplate);
     * RLock lock=
     * redissonClient.getLock("lock:order:"+userId);//这里是为了通过Redisson实现一人一票上的锁
     * //RLock即Redisson的锁
     * boolean bool=lock.tryLock();
     *//**
        * 这里lock.tryLock()里面有几种传参类型
        * 1.可选无参，也就是获取锁失败立即返回false
        * 2. 三参数(long time1,long time2,TimeUnit timeUnit)
        * time1是最长等待时间，也就是在time1这段时间会进行自动的多次尝试获取锁，如果到time1还没有获取到锁那就返回false
        * time2是锁的最长施放时间，超时
        *//*
           * if(!bool){
           * //如果没能成功上锁，说明已经买过一张了
           * return Result.fail("一人限购一张");
           * }
           * try{
           * //获取代理对象（事物）
           * IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
           * return proxy.createSeckillVoucher(voucherId);//voucherId是秒杀优惠券的ID，
           * 偶后面的VoucherOrder的id是秒杀订单的id
           * }finally {
           * //释放锁
           * lock.unlock();
           * }
           * }
           */

    @Transactional
    public Result createSeckillVoucher(Long voucherId) {
        // 6.1订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        long id = redisWorker.nextId("order");// 获取唯一的订单id
        voucherOrder.setId(id);
        // 6.2用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 7.返回订单id

        return Result.ok(id);

    }

}
