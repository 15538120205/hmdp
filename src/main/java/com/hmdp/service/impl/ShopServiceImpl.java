package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryWithPassThrough("cache:shop:" + id, id, Shop.class, id1 -> getById(id1), 30L, TimeUnit.MINUTES);
        //缓存击穿(互斥锁)
       Shop shop = queryWithMutex(id);
        //缓存击穿(逻辑过期)
        //Shop shop = queryWithLogicExpire(id);
        //cacheClient.queryWithLogicExpire("cache:shop:" + id, id, Shop.class, id1 -> getById(id1), 30L, TimeUnit.MINUTES);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    //创建一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicExpire(Long id){
        //从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //存在,返回
        if (StrUtil.isBlank(shopJson)) {
           //如果没有命中,直接返回
            return null;
        }
        //命中,需要反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //判断是否过期

        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期,直接返回
            return shop;
        }
        //过期,需要缓存重建
        //缓存重建
        //获取互斥锁
        String lockkey = "lock:shop:" + id;
        //是否获取锁成功
        boolean isLocked = tryLock(lockkey);
        if (isLocked) {
            //成功,开启独立线程,实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockkey);
                }
            });

        }
        //返回过期数据
        return shop;
    }

    public Shop queryWithMutex(Long id){
        //从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //存在,返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;

        }
        if (shopJson != null){
            return null;
        }
        //实现缓存重建
        //获取互斥锁
        String lockkey = "lock:shop:" + id;
        try {
            boolean isLocked = tryLock(lockkey);
            //判断是否获取锁成功
            if (!isLocked) {
                //获取锁失败,重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取锁成功,根据id查询数据库,
            Shop shop = getById(id);
            //数据库不存在,返回错误
            if (shop == null){
                stringRedisTemplate.opsForValue().set("cache:shop:" + id, "",2, TimeUnit.MINUTES);
                return null;
            }
            //数据库存在,写入redis
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
            return shop;
        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unLock(lockkey);
        }

    }

    public Shop queryWithPassThrough(Long id){
        //从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //存在,返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;

        }
        if (shopJson != null){
            return null;
        }
        //不存在,查询数据库
        Shop shop = getById(id);
        //数据库不存在,返回错误
            if (shop == null){
                stringRedisTemplate.opsForValue().set("cache:shop:" + id, "",2, TimeUnit.MINUTES);
            return null;
        }
        //数据库存在,写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);

        return shop;
    }
    private boolean tryLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


    public void saveShop2Redis(Long id,Long expireTime){
        //查询店铺数据
        Shop shop = getById(id);
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        //写入Redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null){
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById( shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
