package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //在redis查询id
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //存在，返回缓存
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //存在空字符串，报错
        if ("".equals(shopJson)){
            return null;
        }
        //未命中，缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            Boolean isLock = tryLock(lockKey);
            //失败，等待并重试
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //成功，重建缓存
            //到数据库查id
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            //不存在，存空字符串
            if (shop == null){
                //存空字符串
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在，存入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }
        //返回数据

        return shop;
    }

    public Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //在redis查询id
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //存在，返回缓存
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //存在空字符串，报错
        if ("".equals(shopJson)){
            return null;
        }
        //不存在，到数据库查id
        Shop shop = getById(id);
        //不存在，存空字符串
        if (shop == null){
            //存空字符串
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在，存入redis
        String shopNewCache = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key,shopNewCache,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回数据

        return shop;
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
