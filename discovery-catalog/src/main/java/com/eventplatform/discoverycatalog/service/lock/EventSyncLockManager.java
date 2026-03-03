package com.eventplatform.discoverycatalog.service.lock;

import com.eventplatform.discoverycatalog.domain.value.EventSyncLockKey;
import java.time.Duration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventSyncLockManager {

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;

    public EventSyncLockManager(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean acquire(EventSyncLockKey key) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key.asString(), "1", LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(EventSyncLockKey key) {
        redisTemplate.delete(key.asString());
    }
}
