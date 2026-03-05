package com.eventplatform.engagement.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.eventplatform.engagement.api.dto.response.ReviewResponse;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.ControllerAdvice;

@AnalyzeClasses(packages = "com.eventplatform.engagement")
class EngagementArchTest {

    @ArchTest
    static final ArchRule should_not_import_other_module_internal_classes =
        noClasses().that().resideInAPackage("..engagement..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.eventplatform.paymentsticketing..",
                "com.eventplatform.bookinginventory..",
                "com.eventplatform.identity..",
                "com.eventplatform.promotions..",
                "com.eventplatform.scheduling..",
                "com.eventplatform.discoverycatalog.."
            );

    @ArchTest
    static final ArchRule should_not_call_eventbrite_directly =
        noClasses().that().resideInAPackage("..engagement..")
            .should().dependOnClassesThat().resideInAnyPackage("..com.eventbrite..");

    @ArchTest
    static final ArchRule should_not_call_openai_sdk_directly =
        noClasses().that().resideInAPackage("..engagement..")
            .should().dependOnClassesThat().resideInAnyPackage("..com.theokanning.openai..");

    @ArchTest
    static final ArchRule should_not_have_controller_advice_in_engagement =
        noClasses().that().resideInAPackage("..engagement..")
            .should().beAnnotatedWith(ControllerAdvice.class);

    @ArchTest
    static final ArchRule service_and_controller_should_not_construct_review_response_directly =
        noClasses().that().resideInAnyPackage("..engagement.service..", "..engagement.api.controller..")
            .should().callConstructor(ReviewResponse.class);
}
