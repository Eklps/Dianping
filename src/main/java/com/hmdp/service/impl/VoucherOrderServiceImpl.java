package com.hmdp.service.impl;

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
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判读是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始");
        }
        // 3.判断是否已经过期
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已结束");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        // 5.扣减库存
        /**
         * setSql("stock = stock - 1") 设置自定义 SQL 片段，这里是让 stock 字段减 1
         * eq("voucher_id", voucherId) 添加 WHERE 条件：voucher_id = ?
         * update() 执行更新操作，返回是否成功的结果
         */
        boolean success = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        // 原本gt处是eq也就是判断括号内的两者相等，但这里采用gt就是说stock大于零也就是还有库存

        if (!success) {
            return Result.fail("库存不足");
        }

        Long userId=UserHolder.getUser().getId();
        //尝试上锁
        SimpleRedisLock lock=new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        boolean bool=lock.tryLock(5);
        if(!bool){
            //如果没能成功上锁，说明已经买过一张了
            return Result.fail("一人限购一张");
        }
        try{
            //获取代理对象（事物）
            IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createSeckillVoucher(voucherId);//voucherId是秒杀优惠券的ID，偶后面的VoucherOrder的id是秒杀订单的id
        }finally {
            //释放锁
            lock.unLock();
        }
    }

    @Transactional
    public Result createSeckillVoucher(Long voucherId) {
        // 6.1订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        long id = redisWorker.nextId("order");//获取唯一的订单id
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
