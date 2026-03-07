package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Test
    void testSaveShop(){
        shopService.saveShop2Redis(1L,10L);
    }
    private ExecutorService e = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker(){

        CountDownLatch countDownLatch = new CountDownLatch(50);
        Runnable task = () -> {
           for (int i = 0; i < 1000; i++) {
               long id = redisIdWorker.nextId("order");
               System.out.println("id = " + id);
           }
           countDownLatch.countDown();
       };
       for (int i = 0; i < 50; i++){
           e.submit(task);
       }
        try {
            countDownLatch.await();
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }
    @Test
    void loadShopData(){
        //查询店铺信息
        List<Shop> list = shopService.list();
        //把店铺按照typeid分组,id一致放入一个集合中
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批写入Redis
        for (Map.Entry<Long, List<Shop>> longListEntry : map.entrySet()) {
            //获取类型id
            Long typeId = longListEntry.getKey();
            //获取同类型的店铺集合
            List<Shop> value = longListEntry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
                //stringRedisTemplate.opsForGeo().add("shop:geo:" + typeId,new Point(shop.getX(),shop.getY()),shop.getId().toString());
            }
            stringRedisTemplate.opsForGeo().add("shop:geo:" + typeId,locations);
        }
    }



}
