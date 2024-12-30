package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData { //用于逻辑过期解决缓存击穿
    private LocalDateTime expireTime;
    private Object data;

}
