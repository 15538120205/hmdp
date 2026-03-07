package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long id = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + blog.getId(), id.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {

        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否已经点过赞
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + id, userId.toString());
        if (score ==  null){
            //没有点赞,数据库点赞数+1,保存用户到redis集合
            boolean id1 = update().setSql("liked = liked + 1").eq("id", id).update();
            if (id1) {
                stringRedisTemplate.opsForZSet().add("blog:liked:" + id,userId.toString(), System.currentTimeMillis());
            }
        }else {
            //点赞了,数据库点赞数-1,从redis集合中移除用户
            boolean id2 = update().setSql("liked = liked - 1").eq("id", id).update();
            if (id2) {
                stringRedisTemplate.opsForZSet().remove("blog:liked:" + id, userId.toString());
            }
        }
        //返回结果
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {

        Set<String> top5 = stringRedisTemplate.opsForZSet().range("blog:liked:" + id, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String strId = StrUtil.join(",", ids);
        List<UserDTO> collect = userService.query().in("id",ids).last("ORDER BY FIELD(id," + strId + ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(collect);
    }
}
