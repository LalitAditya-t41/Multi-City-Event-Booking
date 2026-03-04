package com.eventplatform.bookinginventory.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.eventplatform.shared.common.event.published.CartAssembledEvent;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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

    @ArchTest
    static final ArchRule should_not_import_identity_entity_or_repository_in_booking_inventory =
        noClasses()
            .that().resideInAPackage("..bookinginventory..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..com.eventplatform.identity.domain..",
                "..com.eventplatform.identity.repository.."
            );

    @ArchTest
    static final ArchRule should_not_import_payments_ticketing_in_booking_inventory =
        noClasses()
            .that().resideInAPackage("..bookinginventory..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..com.eventplatform.paymentsticketing..");

    @ArchTest
    static final ArchRule should_not_call_eventbrite_http_directly_from_booking_inventory_service =
        noClasses()
            .that().resideInAPackage("..bookinginventory.service..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..com.eventplatform.shared.eventbrite.client..");

    @ArchTest
    static final ArchRule should_not_declare_ControllerAdvice_in_booking_inventory =
        classes()
            .that().resideInAPackage("..bookinginventory..")
            .should().notBeAnnotatedWith(org.springframework.web.bind.annotation.ControllerAdvice.class)
            .andShould().notBeAnnotatedWith(org.springframework.web.bind.annotation.RestControllerAdvice.class);

    @ArchTest
    static final ArchRule should_enforce_mapper_layer_for_all_domain_to_dto_conversions =
        noClasses()
            .that().resideInAPackage("..bookinginventory.api.controller..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..bookinginventory.domain..");

    @Test
    void should_not_publish_CartAssembledEvent_without_orgId_and_ebEventId() {
        RecordComponent[] components = CartAssembledEvent.class.getRecordComponents();
        assertThat(Arrays.stream(components).map(RecordComponent::getName))
            .contains("orgId", "ebEventId");
    }

    @Test
    void should_only_publish_events_with_primitives_ids_enums_no_entities() {
        RecordComponent[] components = CartAssembledEvent.class.getRecordComponents();
        assertThat(Arrays.stream(components).map(c -> c.getType().getSimpleName()))
            .containsOnly("Long", "Long", "Long", "Long", "String", "String");
    }

    @Test
    void should_only_save_audit_log_no_update_or_delete() {
        assertThat(Arrays.stream(com.eventplatform.bookinginventory.repository.SeatLockAuditLogRepository.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName))
            .noneMatch(name -> name.startsWith("delete") || name.startsWith("update"));
    }
}
