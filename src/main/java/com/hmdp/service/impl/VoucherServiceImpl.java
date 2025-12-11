package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public Result addSeckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher=seckillVoucherService.getById(voucherId);
        //2.判读是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("活动尚未开始");
        }
        //3.判断是否已经过期
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("活动已结束");
        }
        //4.判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }
        //5.扣减库存
        /**
         * setSql("stock = stock - 1")	设置自定义 SQL 片段，这里是让 stock 字段减 1
         * eq("voucher_id", voucherId)	添加 WHERE 条件：voucher_id = ?
         * update() 执行更新操作，返回是否成功的结果
         */
        boolean success = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .update();

        if(!success){
            return Result.fail("库存不足");
        }

        //6.1订单id
        VoucherOrder voucherOrder=new VoucherOrder();
        long id= redisWorker.nextId("order");
        voucherOrder.setId(id);
        //6.2用户id
        Long id1 = UserHolder.getUser().getId();
        voucherOrder.setUserId(id1);
        //6.3代金券id
        voucherOrder.setVoucherId(voucherId);
        voucherOrderService.save(voucherOrder);
        //7.返回订单id

        return Result.ok(id);
    }
}
