package com.eventplatform.paymentsticketing.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BookingRefGenerator {

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

  private final AtomicLong dailySequence = new AtomicLong(0);
  private final Clock clock = Clock.systemUTC();

  public String nextRef() {
    long next = dailySequence.incrementAndGet();
    return "BK-" + LocalDate.now(clock).format(DATE_FORMATTER) + "-" + String.format("%03d", next);
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
  void resetCounter() {
    dailySequence.set(0);
  }
}
