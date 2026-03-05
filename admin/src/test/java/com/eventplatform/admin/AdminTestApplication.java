package com.eventplatform.admin;

import com.eventplatform.admin.api.controller.OrgEventbriteController;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = {OrgEventbriteController.class, GlobalExceptionHandler.class})
public class AdminTestApplication {}
