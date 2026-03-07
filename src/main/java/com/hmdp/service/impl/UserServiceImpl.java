package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private String utoken = "";

    /**
     * 发送验证码
     * @param phone 手机号
     * @return 发送结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //2.若不符合,返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3.符合,生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session/redis
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set("login:code:"+phone, code, 5, TimeUnit.MINUTES);
        //5.发送验证码
        log.info("发送验证码成功,验证码为:{}",code);
        //返回ok
        return Result.ok();
    }
    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //校验手机号和验证码
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            //2.若不符合,返回错误信息
            return Result.fail("手机号格式错误");
        }
//        String cacheCode = (String) session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get("login:code:"+loginForm.getPhone());
        if (cacheCode == null ||!cacheCode.equals(loginForm.getCode())){
            //不一致,报错
            return Result.fail("验证码错误");
        }
        //一致,根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        //判断用户是否存在
        if (user == null){
            //不存在,创建新用户并保存
            user = creatUserWithPhone(loginForm.getPhone());

        }

        String token = UUID.randomUUID().toString();
        utoken = token;

        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        //保存用户信息到session/redis
//        session.setAttribute("user",userDTO);

        Map<String, Object> map = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((s, o) -> o.toString()));
        stringRedisTemplate.opsForHash().putAll("login:token:"+token,map);
        stringRedisTemplate.expire("login:token:"+token,30,TimeUnit.HOURS);
        return Result.ok(token);
    }

    @Transactional
    @Override
    public Result logout() {
        Boolean result = stringRedisTemplate.delete(LOGIN_USER_KEY + utoken);
        if(result){
            return Result.ok();
        }
        //System.out.println("key = " + LOGIN_USER_KEY + utoken);
        return Result.fail(LOGIN_USER_KEY + utoken);
    }

    @Override
    public Result sign() {

        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接 key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuffix;
        //获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接 key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuffix;
        //获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取这个月到今天为止的所有的签到记录(返回的是10进制数字)
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == 0 || num == null){
            return Result.ok(0);
        }
        int count = 0;
        //循环遍历
        while (true){
        //让数字与1做与运算,得到数字的最后一个比特位
            // 判断这个比特位是否为0(未签到)
        if ((num & 1) == 0){
            break;
        }else {
            //不为零,计数器加一
            count++;
        }
        //数字右移继续下一个比特位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User creatUserWithPhone(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
