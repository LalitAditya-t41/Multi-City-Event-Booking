package com.eventplatform.bookinginventory.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.eventplatform.bookinginventory")
class BookingInventoryArchTest {

    @ArchTest
    static final ArchRule should_not_import_scheduling_domain_or_service =
        noClasses()
            .that().resideInAPackage("..bookinginventory..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..com.eventplatform.scheduling.domain..",
                "..com.eventplatform.scheduling.service..",
                "..com.eventplatform.scheduling.repository.."
            );
}
