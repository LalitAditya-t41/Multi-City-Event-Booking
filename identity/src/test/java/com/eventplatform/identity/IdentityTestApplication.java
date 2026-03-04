package com.eventplatform.identity;

import com.eventplatform.identity.api.controller.AuthController;
import com.eventplatform.identity.api.controller.PreferenceOptionsController;
import com.eventplatform.identity.api.controller.UserSettingsController;
import com.eventplatform.identity.api.controller.UserWalletController;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = {
    AuthController.class,
    UserSettingsController.class,
    PreferenceOptionsController.class,
    UserWalletController.class,
    GlobalExceptionHandler.class
})
public class IdentityTestApplication {
}
