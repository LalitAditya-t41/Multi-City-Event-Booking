package com.eventplatform.bookinginventory.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.eventplatform.bookinginventory")
class BookingInventoryArchTest {

    /**
     * Hard Rule #1 — booking-inventory must not reach into scheduling internals directly.
     * All access to scheduling data goes through shared reader interfaces only.
     */
    @ArchTest
    static final ArchRule should_not_depend_on_scheduling_domain_service_or_repository =
        noClasses()
            .that().resideInAPackage("..bookinginventory..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..com.eventplatform.scheduling.domain..",
                "..com.eventplatform.scheduling.service..",
                "..com.eventplatform.scheduling.repository.."
            );

    /**
     * Interface migration rule — confirms SchedulingSlotClient (deleted HTTP client) has no remaining references.
     * booking-inventory must use SlotSummaryReader / SlotPricingReader from shared, not direct HTTP.
     */
    @ArchTest
    static final ArchRule should_use_shared_slot_reader_contracts_not_scheduling_http_client =
        noClasses()
            .that().resideInAPackage("..bookinginventory..")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("SchedulingSlotClient");
}
