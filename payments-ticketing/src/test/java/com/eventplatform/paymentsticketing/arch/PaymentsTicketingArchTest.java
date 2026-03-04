package com.eventplatform.paymentsticketing.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@AnalyzeClasses(packages = "com.eventplatform.paymentsticketing")
class PaymentsTicketingArchTest {

    @ArchTest
    static final ArchRule no_class_in_paymentsticketing_imports_from_bookinginventory_package =
        noClasses()
            .that().resideInAPackage("..paymentsticketing..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..com.eventplatform.bookinginventory..");

    @ArchTest
    static final ArchRule no_class_in_paymentsticketing_imports_from_scheduling_package =
        noClasses()
            .that().resideInAPackage("..paymentsticketing..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..com.eventplatform.scheduling.domain..", "..com.eventplatform.scheduling.service..", "..com.eventplatform.scheduling.repository..");

    @ArchTest
    static final ArchRule no_class_in_paymentsticketing_imports_from_identity_package =
        noClasses()
            .that().resideInAPackage("..paymentsticketing..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..com.eventplatform.identity.domain..", "..com.eventplatform.identity.repository..");

    @ArchTest
    static final ArchRule no_controller_advice_annotation_in_paymentsticketing_package =
        noClasses()
            .that().resideInAPackage("..paymentsticketing..")
            .should().beAnnotatedWith(ControllerAdvice.class)
            .orShould().beAnnotatedWith(RestControllerAdvice.class);

    @ArchTest
    static final ArchRule no_stripe_sdk_import_outside_shared_stripe_package =
        noClasses()
            .that().resideInAPackage("..paymentsticketing..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.stripe..");
}
