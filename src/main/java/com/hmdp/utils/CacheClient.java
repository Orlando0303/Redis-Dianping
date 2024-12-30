package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //在redis查询id
        String json = stringRedisTemplate.opsForValue().get(key);
        //存在，返回缓存
        if (StrUtil.isNotBlank(json)){
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //存在空字符串，报错
        if ("".equals(json)){
            return null;
        }
        //不存在，到数据库查id
        R r = dbFallback.apply(id);
        //不存在，存空字符串
        if (r == null){
            //存空字符串
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在，存入redis
        this.set(key, r, time, unit);
        //返回数据

        return r;
    }

    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, String lockKeyPrefix, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //在redis查询id
        String json = stringRedisTemplate.opsForValue().get(key);
        //存在，返回缓存
        if (StrUtil.isNotBlank(json)){
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //存在空字符串，报错
        if ("".equals(json)){
            return null;
        }
        //未命中，缓存重建
        //获取互斥锁
        String lockKey = lockKeyPrefix + id;
        R r = null;
        try {
            Boolean isLock = tryLock(lockKey);
            //失败，等待并重试
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, lockKeyPrefix, dbFallback, time, unit);
            }
            //成功，重建缓存
            //到数据库查id
            r = dbFallback.apply(id);
            //模拟重建延时
            Thread.sleep(200);
            //不存在，存空字符串
            if (r == null){
                //存空字符串
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在，存入redis
            this.set(key, r, time, unit);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }
        //返回数据

        return r;
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String dataKeyPrefix, ID id, Class<R> type, String lockKeyPrefix, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = dataKeyPrefix + id;
        //在redis查询id
        String json = stringRedisTemplate.opsForValue().get(key);
        //未命中，返回空（不用查数据库，因为热点key一定被提前缓存了）
        if (StrUtil.isBlank(json)){
            return null;
        }
        //命中，反序列化，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //未过期，返回数据
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return r;
        }
        //过期，获取互斥锁
        String lockKey = lockKeyPrefix + id;
        Boolean isLock = tryLock(lockKey);
        //成功，新线程重建缓存
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(()-> {
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //失败，返回旧数据

        return r;
    }


    private Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }



}
