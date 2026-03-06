package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.info("处理订单异常:{}",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userid);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock){
            //获取锁失败
            log.error("不允许重复下单");
        }
        //获取代理对象
        try {
            proxy.creatVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), UserHolder.getUser().getId().toString());
        //判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //不为0,没有资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //为0,有资格,将下单信息保到阻塞队列
        Long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();
        // 保存阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public void creatVoucherOrder(VoucherOrder voucherOrder) {
        Long userid = voucherOrder.getUserId();

        int count = query().eq("user_id", userid).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("不能重复下单");
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)//乐观锁
                .update();
        if (!success) {
            log.error("库存不足");
        }
        //创建订单
        save(voucherOrder);

    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀时间是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀时间未开始");
//        }
//        //判断秒杀时间是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀时间已结束");
//        }
//        //判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userid = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userid);
//        RLock lock = redissonClient.getLock("lock:order:" + userid);
//        boolean isLock = lock.tryLock();
//
//        if (!isLock){
//            //获取锁失败
//            return Result.fail("不允许重复下单");
//        }
//        //获取代理对象
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//
//    }
//
//    /**
//     *
//     * 一人一单
//     * @param voucherId
//     * @return
//     */
//    @Transactional
//    public  Result creatVoucherOrder(Long voucherId) {
//        //查询订单
//        Long userid = UserHolder.getUser().getId();
//
//            Integer count = query().eq("user_id", userid).eq("voucher_id", voucherId).count();
//            if (count > 0) {
//                return Result.fail("不能重复下单");
//            }
//
//            //扣减库存
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1")
//                    .eq("voucher_id", voucherId)
//                    .gt("stock", 0)//乐观锁
//                    .update();
//            if (!success) {
//                return Result.fail("库存不足");
//            }
//            //创建订单
//            save(voucherOrder);
//            return Result.ok(orderid);
//
//    }
}
