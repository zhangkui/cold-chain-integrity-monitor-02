package com.coldchain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 Redis SET NX PX 的互斥锁，用于同一设备哈希链追加的串行化。
 * Redis 不可用时降级为进程内 {@link ReentrantLock}（docker-compose 单副本部署同样安全）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLockService {

    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(8);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ReentrantLock fallbackLock = new ReentrantLock();

    public AutoCloseableLock tryLock(String key) {
        return tryLock(key, DEFAULT_WAIT, Duration.ofSeconds(30));
    }

    public AutoCloseableLock tryLock(String key, Duration wait, Duration ttl) {
        String redisKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                Boolean acquired = redis.opsForValue()
                        .setIfAbsent(redisKey, token, ttl.toMillis(), TimeUnit.MILLISECONDS);
                if (Boolean.TRUE.equals(acquired)) {
                    return () -> unlockRedis(redisKey, token);
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取锁被中断: " + key);
        } catch (Exception e) {
            log.warn("redis 不可用({})，降级为进程内锁", e.getMessage());
            if (fallbackLock.tryLock()) {
                return () -> fallbackLock.unlock();
            }
        }
        throw new IllegalStateException("获取锁超时: " + key);
    }

    private void unlockRedis(String key, String token) {
        try {
            // 只释放自己持有的锁，避免误删别人的锁
            redis.execute(UNLOCK_SCRIPT, List.of(key), token);
        } catch (Exception ignored) {
            // TTL 到期会自动释放
        }
    }

    public interface AutoCloseableLock extends AutoCloseable {
        @Override
        void close();
    }
}
