package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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

    @Autowired
    private IFollowService followService;
    @Autowired
    private IBlogService blogService;

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

    @Override
    public Result saveBlog(Blog blog) {

        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = save(blog);
        if (!save){
            return Result.fail("笔记保存失败");
        }
        //查询笔记作者所有粉丝
        Long id = UserHolder.getUser().getId();
        List<Follow> follows = followService.query().eq("follow_user_id", id).list();
        //推送笔记id给所有粉丝
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            //推送
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {

        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "feed:" + userId;
        // 获取用户收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        //解析数据,blogId,minTime(score时间戳).offset
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            String blogId = tuple.getValue();
            ids.add(Long.valueOf(blogId));
            long time = tuple.getScore().longValue();
            if (time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        String strId = StrUtil.join(",", ids);
        //获取blog
        List<Blog> blogs = blogService.query().in("id",ids).last("ORDER BY FIELD(id," + strId + ")").list();
        blogs.forEach(blog ->{
            Long userid = blog.getUserId();
            User user = userService.getById(userid);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        });
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        scrollResult.setList(blogs);
        //封装并返回
        return Result.ok(scrollResult);
    }
}
