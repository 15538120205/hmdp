package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisIdWorker redisIdWorker;
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


}
