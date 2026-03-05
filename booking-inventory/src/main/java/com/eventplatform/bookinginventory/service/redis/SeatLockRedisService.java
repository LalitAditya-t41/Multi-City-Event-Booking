package com.eventplatform.bookinginventory.service.redis;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class SeatLockRedisService {

  public enum AcquireResult {
    ACQUIRED,
    ALREADY_YOURS,
    CONFLICT
  }

  private static final DefaultRedisScript<String> ACQUIRE_SCRIPT =
      new DefaultRedisScript<>(
          """
            local key = KEYS[1]
            local owner = ARGV[1]
            local ttl = tonumber(ARGV[2])
            local current = redis.call('GET', key)
            if not current then
              redis.call('PSETEX', key, ttl, owner)
              return 'ACQUIRED'
            end
            if current == owner then
              return 'ALREADY_YOURS'
            end
            return 'CONFLICT'
            """,
          String.class);

  private static final DefaultRedisScript<String> RELEASE_SCRIPT =
      new DefaultRedisScript<>(
          """
            local key = KEYS[1]
            local owner = ARGV[1]
            local current = redis.call('GET', key)
            if not current then
              return 'EXPIRED'
            end
            if current ~= owner then
              return 'NOT_OWNER'
            end
            redis.call('DEL', key)
            return 'RELEASED'
            """,
          String.class);

  private static final DefaultRedisScript<String> EXTEND_SCRIPT =
      new DefaultRedisScript<>(
          """
            local key = KEYS[1]
            local owner = ARGV[1]
            local ttl = tonumber(ARGV[2])
            local current = redis.call('GET', key)
            if not current then
              return 'EXPIRED'
            end
            if current ~= owner then
              return 'NOT_OWNER'
            end
            redis.call('PEXPIRE', key, ttl)
            return 'EXTENDED'
            """,
          String.class);

  private final StringRedisTemplate stringRedisTemplate;

  public SeatLockRedisService(StringRedisTemplate stringRedisTemplate) {
    this.stringRedisTemplate = stringRedisTemplate;
  }

  public AcquireResult acquire(Long seatId, Long userId, Duration ttl) {
    String key = seatKey(seatId);
    String result =
        stringRedisTemplate.execute(
            ACQUIRE_SCRIPT, List.of(key), String.valueOf(userId), String.valueOf(ttl.toMillis()));
    if ("ACQUIRED".equals(result)) {
      return AcquireResult.ACQUIRED;
    }
    if ("ALREADY_YOURS".equals(result)) {
      return AcquireResult.ALREADY_YOURS;
    }
    return AcquireResult.CONFLICT;
  }

  public boolean extend(Long seatId, Long userId, Duration ttl) {
    String result =
        stringRedisTemplate.execute(
            EXTEND_SCRIPT,
            List.of(seatKey(seatId)),
            String.valueOf(userId),
            String.valueOf(ttl.toMillis()));
    return "EXTENDED".equals(result);
  }

  public boolean release(Long seatId, Long userId) {
    String result =
        stringRedisTemplate.execute(
            RELEASE_SCRIPT, List.of(seatKey(seatId)), String.valueOf(userId));
    return "RELEASED".equals(result) || "EXPIRED".equals(result);
  }

  public boolean isOwnedBy(Long seatId, Long userId) {
    String owner = stringRedisTemplate.opsForValue().get(seatKey(seatId));
    return String.valueOf(userId).equals(owner);
  }

  public String seatKey(Long seatId) {
    return "seat:lock:" + seatId;
  }
}
