package com.eventplatform.bookinginventory.service.redis;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class GaInventoryRedisService {

    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>(
        """
            local key = KEYS[1]
            local requested = tonumber(ARGV[1])
            local current = tonumber(redis.call('GET', key) or '-1')
            if current < 0 then
              return -2
            end
            if current < requested then
              return -1
            end
            return redis.call('DECRBY', key, requested)
            """,
        Long.class
    );

    private static final DefaultRedisScript<Long> RESTORE_SCRIPT = new DefaultRedisScript<>(
        """
            local key = KEYS[1]
            local qty = tonumber(ARGV[1])
            return redis.call('INCRBY', key, qty)
            """,
        Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    public GaInventoryRedisService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long claim(Long slotId, Long tierId, int requested) {
        Long result = stringRedisTemplate.execute(CLAIM_SCRIPT, List.of(key(slotId, tierId)), String.valueOf(requested));
        return result == null ? -2 : result;
    }

    public long restore(Long slotId, Long tierId, int quantity) {
        Long result = stringRedisTemplate.execute(RESTORE_SCRIPT, List.of(key(slotId, tierId)), String.valueOf(quantity));
        return result == null ? -1 : result;
    }

    public void init(Long slotId, Long tierId, int quota) {
        stringRedisTemplate.opsForValue().setIfAbsent(key(slotId, tierId), String.valueOf(quota));
    }

    public Long getCurrent(Long slotId, Long tierId) {
        String value = stringRedisTemplate.opsForValue().get(key(slotId, tierId));
        return value == null ? null : Long.parseLong(value);
    }

    private String key(Long slotId, Long tierId) {
        return "ga:available:" + slotId + ":" + tierId;
    }
}
