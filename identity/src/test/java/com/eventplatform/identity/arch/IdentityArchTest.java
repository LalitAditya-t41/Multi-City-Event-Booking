package com.eventplatform.identity.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.ControllerAdvice;

@AnalyzeClasses(packages = "com.eventplatform.identity")
class IdentityArchTest {

    @ArchTest
    static final ArchRule should_enforce_identity_module_structure_and_layer_boundaries =
        noClasses()
            .that().resideInAPackage("..identity.api.controller..")
            .should().dependOnClassesThat()
            .resideInAPackage("..identity.repository..");

    @ArchTest
    static final ArchRule should_prevent_identity_from_direct_external_http_calls =
        noClasses()
            .that().resideInAPackage("..identity..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.web.client..",
                "org.springframework.web.reactive.function.client..",
                "java.net.http.."
            );

    @ArchTest
    static final ArchRule should_enforce_shared_global_exception_handler_only =
        noClasses()
            .that().resideInAPackage("..identity..")
            .should().beAnnotatedWith(ControllerAdvice.class);
}
