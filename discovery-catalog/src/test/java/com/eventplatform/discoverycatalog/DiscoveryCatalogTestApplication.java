package com.eventplatform.discoverycatalog;

import com.eventplatform.discoverycatalog.api.controller.CityCatalogController;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = {CityCatalogController.class, GlobalExceptionHandler.class})
public class DiscoveryCatalogTestApplication {
}
