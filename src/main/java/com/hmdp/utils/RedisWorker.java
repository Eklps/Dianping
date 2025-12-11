package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final long BEGIN_TIMESTAMP=1640995200L;//2022年一月一日
    private static final long COUNT_BITS=32;

    public long nextId(String prefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSeconds-BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:mm:dd"));

        //2.2获取自增id
        long count = stringRedisTemplate.opsForValue().increment("icr:"+prefix+":"+date);

        return timeStamp<<COUNT_BITS | count;//当前的时间戳<<32+redis中setnx的自增id
    }
    }
