package com.eventplatform.engagement.event.listener;

import com.eventplatform.engagement.event.published.ModerationRequiredEvent;
import com.eventplatform.engagement.service.ModerationService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ModerationRequiredListener {

  private final ModerationService moderationService;

  public ModerationRequiredListener(ModerationService moderationService) {
    this.moderationService = moderationService;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onModerationRequired(ModerationRequiredEvent event) {
    moderationService.triggerAutoModeration(event.reviewId());
  }
}
