package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class LoginInterceptor implements HandlerInterceptor {


    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//
//        //获取session中的用户
////        HttpSession session = request.getSession();
////
////        Object user = session.getAttribute("user");
//        String token = request.getHeader("Authorization");
//        if (StrUtil.isBlank( token)){
//            response.setStatus(401);
//            return false;
//        }
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries("login:token:" + token);
//        if (userMap.isEmpty()){
//            response.setStatus(401);
//            return false;
//        }
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//
//
//        //存在,保存在threadlocal
//        UserHolder.saveUser((userDTO));
//        //保存在redis
//
//        //放行
//        stringRedisTemplate.expire("login:token:" + token, 30, TimeUnit.MINUTES);

        //判断是否需要拦截(threadlocal有用户)
        if (UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }

        return true;
    }


}
