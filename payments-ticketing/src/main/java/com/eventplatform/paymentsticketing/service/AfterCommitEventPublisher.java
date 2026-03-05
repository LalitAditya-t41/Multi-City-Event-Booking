package com.eventplatform.paymentsticketing.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AfterCommitEventPublisher {

  private final ApplicationEventPublisher applicationEventPublisher;

  public AfterCommitEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
    this.applicationEventPublisher = applicationEventPublisher;
  }

  public void publish(Object event) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              applicationEventPublisher.publishEvent(event);
            }
          });
      return;
    }
    applicationEventPublisher.publishEvent(event);
  }
}
