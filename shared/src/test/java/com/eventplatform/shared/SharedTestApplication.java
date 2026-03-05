package com.eventplatform.shared;

import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.eventbrite.webhook.EbWebhookController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = {EbWebhookController.class, GlobalExceptionHandler.class})
public class SharedTestApplication {}
