/**
 * Promotions module — owns Coupons, Promotions, Eligibility rules, and Eventbrite discount sync.
 *
 * <p>Module dependency contract (HARD RULE #10): - MAY import from: shared/ only - MUST NOT import:
 * booking-inventory, payments-ticketing, identity, or any other module @Service / @Entity
 * / @Repository directly - Cross-module reads go via CartSnapshotReader (shared interface) or REST
 * calls - Cross-module writes are triggered by Spring ApplicationEvents
 *
 * <p>Canonical package layout (per docs/MODULE_STRUCTURE_TEMPLATE.md): api/controller/ — REST
 * controllers api/dto/ — request / response DTOs domain/ — @Entity classes and enums
 * event/listener/ — @TransactionalEventListener beans event/published/ — module-internal events (if
 * any) exception/ — module-specific exception classes mapper/ — MapStruct @Mapper interfaces
 * repository/ — Spring Data @Repository interfaces service/ — @Service beans (orchestration +
 * eligibility pipeline) statemachine/ — EB discount sync state machine (create/modify/delete)
 * scheduler/ — @Scheduled / Quartz jobs for expiry + sync
 */
package com.eventplatform.promotions;
