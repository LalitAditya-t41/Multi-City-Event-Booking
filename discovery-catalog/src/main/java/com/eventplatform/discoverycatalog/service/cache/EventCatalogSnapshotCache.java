package com.eventplatform.discoverycatalog.service.cache;

import com.eventplatform.discoverycatalog.domain.value.SnapshotPayload;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventCatalogSnapshotCache {

  private static final Duration SNAPSHOT_TTL = Duration.ofHours(1);

  private final RedisTemplate<String, Object> redisTemplate;

  public EventCatalogSnapshotCache(RedisTemplate<String, Object> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public Optional<SnapshotPayload> getSnapshot(Long organizationId, Long cityId) {
    Object value = redisTemplate.opsForValue().get(key(organizationId, cityId));
    if (value instanceof SnapshotPayload payload) {
      return Optional.of(payload);
    }
    return Optional.empty();
  }

  public void putSnapshot(Long organizationId, Long cityId, SnapshotPayload payload) {
    redisTemplate.opsForValue().set(key(organizationId, cityId), payload, SNAPSHOT_TTL);
  }

  public void invalidate(Long organizationId, Long cityId) {
    redisTemplate.delete(key(organizationId, cityId));
  }

  public void invalidateBatch(Long organizationId, List<Long> cityIds) {
    if (cityIds == null || cityIds.isEmpty()) {
      return;
    }
    List<String> keys =
        cityIds.stream().map(cityId -> key(organizationId, cityId)).collect(Collectors.toList());
    redisTemplate.delete(keys);
  }

  private String key(Long organizationId, Long cityId) {
    return "catalog:snapshot:%d:%d".formatted(organizationId, cityId);
  }
}
