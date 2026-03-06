package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr( value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr( value));
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds( time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time, TimeUnit unit) {
        //从redis中查询
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //存在,返回
        if (StrUtil.isNotBlank(json)) {
            R r = JSONUtil.toBean(json, type);
            return r;

        }
        if (json != null){
            return null;
        }
        //不存在,查询数据库
        R r = dbFallBack.apply( id);
        //数据库不存在,返回错误
        if (r == null){
            stringRedisTemplate.opsForValue().set(keyPrefix + id, "",time, unit);
            return null;
        }
        //数据库存在,写入redis
        this.set(keyPrefix + id, r, time, unit);

        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time, TimeUnit unit){
        //从redis中查询
        String json = stringRedisTemplate.opsForValue().get(keyPrefix+ id);
        //存在,返回
        if (StrUtil.isBlank(json)) {
            //如果没有命中,直接返回
            return null;
        }
        //命中,需要反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        //判断是否过期

        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期,直接返回
            return r;
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
                    R r1 = dbFallBack.apply(id);
                    this.setWithLogicalExpire(keyPrefix + id, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockkey);
                }
            });

        }
        //返回过期数据
        return r;
    }

    private boolean tryLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
