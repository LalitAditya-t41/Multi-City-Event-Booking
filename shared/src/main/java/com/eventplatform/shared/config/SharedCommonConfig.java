package com.eventplatform.shared.config;

import com.eventplatform.shared.eventbrite.config.EbProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(EbProperties.class)
public class SharedCommonConfig {
}
