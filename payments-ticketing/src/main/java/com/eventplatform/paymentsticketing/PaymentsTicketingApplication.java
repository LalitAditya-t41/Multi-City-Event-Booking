package com.eventplatform.paymentsticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;

/**
 * Bootstrap class — for IDE / module scanning support only. The live application is started from
 * the app/ module.
 */
@SpringBootApplication
@Profile("payments-module-standalone")
public class PaymentsTicketingApplication {
  public static void main(String[] args) {
    SpringApplication.run(PaymentsTicketingApplication.class, args);
  }
}
