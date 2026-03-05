package com.eventplatform.scheduling;

import com.eventplatform.scheduling.api.controller.ShowSlotController;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = {ShowSlotController.class, GlobalExceptionHandler.class})
public class SchedulingTestApplication {}
