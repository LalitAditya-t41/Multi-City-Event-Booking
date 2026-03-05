package com.eventplatform.discoverycatalog.repository;

import com.eventplatform.discoverycatalog.domain.WebhookConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, Long> {
  Optional<WebhookConfig> findByOrganizationId(Long organizationId);

  List<WebhookConfig> findByLastWebhookAtBeforeOrLastWebhookAtIsNull(Instant cutoff);
}
