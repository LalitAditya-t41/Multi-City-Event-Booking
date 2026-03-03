package com.eventplatform.discoverycatalog.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.ControllerAdvice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.eventplatform.discoverycatalog")
class DiscoveryCatalogArchTest {

    @ArchTest
    static final ArchRule should_not_have_controller_advice =
        noClasses().should().beAnnotatedWith(ControllerAdvice.class);

    @ArchTest
    static final ArchRule listeners_should_not_import_scheduling_domain =
        noClasses()
            .that().resideInAPackage("..discoverycatalog.event.listener..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..com.eventplatform.scheduling.domain..", "..com.eventplatform.scheduling.repository..");
}
