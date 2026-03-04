package com.eventplatform.promotions.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.eventplatform.shared.common.event.published.BookingCancelledEvent;
import com.eventplatform.shared.common.event.published.BookingConfirmedEvent;
import com.eventplatform.shared.common.event.published.CartAssembledEvent;
import com.eventplatform.shared.common.event.published.CouponAppliedEvent;
import com.eventplatform.shared.common.event.published.CouponValidatedEvent;
import com.eventplatform.shared.common.event.published.PaymentFailedEvent;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionalEventListener;

@AnalyzeClasses(packages = "com.eventplatform.promotions")
class PromotionsArchTest {

    @ArchTest
    static final ArchRule promotions_module_should_not_import_any_other_module_service_or_repository =
        noClasses().that().resideInAPackage("..promotions..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..bookinginventory.service..",
                "..bookinginventory.repository..",
                "..paymentsticketing.service..",
                "..paymentsticketing.repository..",
                "..identity.service..",
                "..identity.repository..",
                "..scheduling.service..",
                "..scheduling.repository.."
            );

    @ArchTest
    static final ArchRule promotions_module_should_only_call_eventbrite_via_EbDiscountSyncService_facade =
        noClasses().that().resideInAPackage("..promotions..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..shared.eventbrite.service.Default*",
                "..shared.eventbrite.client.."
            );

    @Test
    void CouponAppliedEvent_and_CouponValidatedEvent_should_reside_in_shared_module_only() {
        org.assertj.core.api.Assertions.assertThat(CouponAppliedEvent.class.getPackageName())
            .contains("shared.common.event.published");
        org.assertj.core.api.Assertions.assertThat(CouponValidatedEvent.class.getPackageName())
            .contains("shared.common.event.published");
    }

    @Test
    void promotions_event_listeners_should_only_accept_shared_event_types() {
        List<Class<?>> allowed = List.of(
            BookingConfirmedEvent.class,
            BookingCancelledEvent.class,
            PaymentFailedEvent.class,
            CartAssembledEvent.class
        );
        List<Class<?>> listeners = List.of(
            com.eventplatform.promotions.event.listener.BookingConfirmedListener.class,
            com.eventplatform.promotions.event.listener.BookingCancelledListener.class,
            com.eventplatform.promotions.event.listener.PaymentFailedListener.class,
            com.eventplatform.promotions.event.listener.CartAssembledListener.class
        );

        for (Class<?> listener : listeners) {
            for (Method method : listener.getDeclaredMethods()) {
                if (method.getAnnotation(TransactionalEventListener.class) == null) {
                    continue;
                }
                org.assertj.core.api.Assertions.assertThat(method.getParameterTypes())
                    .hasSize(1);
                org.assertj.core.api.Assertions.assertThat(allowed)
                    .contains(method.getParameterTypes()[0]);
            }
        }
    }
}
