package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE;
        //在redis查询type
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        //存在，返回缓存
        if (StrUtil.isNotBlank(shopTypeJson)){
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }
        //不存在，到数据库查type
        List<ShopType> typeList = this.query().orderByAsc("sort").list();
        //不存在，返回空
        if (typeList.isEmpty()){
            return Result.ok(typeList);
        }
        //存在，存入redis
        String typeNewCache = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(key,typeNewCache);
        //返回数据
        return Result.ok(typeList);
    }
}
